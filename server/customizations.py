"""Validated desktop settings snapshot; bundled scripts run only in the phone sandbox."""
import hashlib
import json
import os
from pathlib import Path
import threading
import time

MAX_SETTINGS_BYTES = 512 * 1024
BUILTINS = {'lowercase', 'spongebob-case', 'no-trailing-punctuation'}


def normalize_vocabulary(entries):
    if not isinstance(entries, list) or len(entries) > 1024:
        raise ValueError('Invalid vocabulary')
    vocabulary, rules = [], []
    for entry in entries:
        if isinstance(entry, str):
            term, aliases = entry, []
        elif isinstance(entry, dict):
            term, aliases = entry.get('term'), entry.get('sounds_like', [])
        else:
            raise ValueError('Invalid vocabulary entry')
        if not isinstance(term, str) or not term.strip() or len(term) > 256:
            raise ValueError('Invalid vocabulary term')
        if not isinstance(aliases, list) or len(aliases) > 32 or not all(
                isinstance(alias, str) and alias.strip() and len(alias) <= 256 for alias in aliases):
            raise ValueError('Invalid vocabulary aliases')
        term = term.strip()
        aliases = list(dict.fromkeys(alias.strip() for alias in aliases))
        vocabulary.append({'term': term, 'sounds_like': aliases})
        rules.extend({'matched_phrase': alias, 'output': term} for alias in aliases)
        if len(rules) > 4096:
            raise ValueError('Too many vocabulary aliases')
    return vocabulary, rules


def normalize(settings):
    if not isinstance(settings, dict):
        raise ValueError('Expected desktop settings object')
    processing = settings.get('dictation_processing', {})
    if not isinstance(processing, dict):
        raise ValueError('Invalid dictation processing settings')
    default = processing.get('default_mode', {})
    modes = processing.get('modes', [])
    if not isinstance(default, dict) or not isinstance(modes, list) or len(modes) > 128:
        raise ValueError('Invalid modes')
    legacy = settings.get('text_replacements', [])
    vocabulary, vocabulary_rules = normalize_vocabulary(settings.get('vocabulary', []))
    script = settings.get('script', '')
    script_ids = settings.get('script_ids', [])
    if not isinstance(script, str) or len(script.encode('utf-8')) > 256 * 1024:
        raise ValueError('Invalid custom script')
    if not isinstance(script_ids, list) or len(script_ids) > 128 or not all(
            isinstance(t, str) and len(t) <= 256 for t in script_ids):
        raise ValueError('Invalid custom transformations')
    supported = BUILTINS | (set(script_ids) if script else set())
    result = []
    for index, mode in enumerate([default, *modes]):
        if not isinstance(mode, dict):
            raise ValueError('Invalid mode')
        name = mode.get('name', 'Global' if index == 0 else 'Mode')
        rules = mode.get('replacements', []) or legacy
        transforms = mode.get('transformations', [])
        if not isinstance(name, str) or len(name) > 256:
            raise ValueError('Invalid mode name')
        if not isinstance(rules, list) or len(rules) > 1024:
            raise ValueError('Too many corrections')
        if not isinstance(transforms, list) or len(transforms) > 32 or not all(
                isinstance(t, str) and len(t) <= 256 for t in transforms):
            raise ValueError('Invalid transformations')
        replacements = []
        for rule in rules:
            if not isinstance(rule, dict):
                raise ValueError('Invalid correction')
            phrase, output = rule.get('matched_phrase'), rule.get('output')
            if not isinstance(phrase, str) or not isinstance(output, str) or max(len(phrase), len(output)) > 4096:
                raise ValueError('Invalid correction text')
            if phrase.strip():
                replacements.append({'matched_phrase': phrase.strip(), 'output': output})
        rewrite = mode.get('post_processing', {})
        if not isinstance(rewrite, dict):
            raise ValueError('Invalid rewrite settings')
        result.append({'name': name or ('Global' if index == 0 else 'Mode'),
                       # Keep explicit corrections first for equal-length matches. The
                       # phone already handles longest phrase wins and non-recursion.
                       'replacements': replacements + vocabulary_rules, 'transformations': transforms,
                       'unsupported': [t for t in transforms if t not in supported]
                           + (['OpenCode rewriting'] if rewrite.get('enabled') else [])})
    return {'source': 'NewEra', 'modes': result, 'script': script, 'vocabulary': vocabulary}


class CustomizationStore:
    def __init__(self, path):
        self.path = Path(path)
        self.lock = threading.Lock()

    def snapshot(self):
        with self.lock:
            try:
                return json.loads(self.path.read_text(encoding='utf-8'))
            except FileNotFoundError:
                return {'source': 'NewEra', 'configured': False, 'modes': []}

    def update(self, settings):
        snapshot = normalize(settings)
        encoded = json.dumps(snapshot, ensure_ascii=False, sort_keys=True).encode('utf-8')
        if len(encoded) > MAX_SETTINGS_BYTES:
            raise ValueError('Customizations are too large')
        snapshot.update(configured=True, revision=hashlib.sha256(encoded).hexdigest(),
                        updated_at=int(time.time() * 1000))
        with self.lock:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            temporary = self.path.with_suffix('.tmp')
            try:
                with temporary.open('w', encoding='utf-8') as file:
                    json.dump(snapshot, file, ensure_ascii=False)
                    file.flush()
                    os.fsync(file.fileno())
                os.replace(temporary, self.path)
            finally:
                temporary.unlink(missing_ok=True)
        return snapshot
