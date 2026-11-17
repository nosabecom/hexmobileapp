import http.client
import json
from pathlib import Path
import sys
import tempfile
import threading
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from customizations import CustomizationStore, normalize, MAX_SETTINGS_BYTES
from hex_server import make_server
from test_server import wav


def settings():
    return {'provider_api_key': 'never-export-this', 'dictation_processing': {
        'default_mode': {'name': 'Global', 'replacements': [{'matched_phrase': 'open code', 'output': 'OpenCode'}],
                         'transformations': ['lowercase', 'no-trailing-punctuation']},
        'modes': [{'name': 'Custom', 'transformations': ['my-script'],
                   'post_processing': {'enabled': True, 'prompt': 'private prompt'}}]}}


class CustomizationTests(unittest.TestCase):
    def test_a_second_server_cannot_share_the_listening_port(self):
        server = make_server('127.0.0.1', 0, 'test-only', lambda _: '', 'test')
        try:
            with self.assertRaises(OSError):
                duplicate = make_server(*server.server_address, 'test-only', lambda _: '', 'test')
                duplicate.server_close()
        finally:
            server.server_close()

    def test_shared_vocabulary_and_aliases_reach_every_mode(self):
        source = settings()
        source['vocabulary'] = ['Python', {'term': ' TypeScript ', 'sounds_like': ['type script', 'type script']},
                                {'term': 'C++', 'sounds_like': ['C plus plus', 'C ??']}]
        snapshot = normalize(source)
        self.assertEqual(snapshot['vocabulary'], [
            {'term': 'Python', 'sounds_like': []},
            {'term': 'TypeScript', 'sounds_like': ['type script']},
            {'term': 'C++', 'sounds_like': ['C plus plus', 'C ??']}])
        aliases = [{'matched_phrase': 'type script', 'output': 'TypeScript'},
                   {'matched_phrase': 'C plus plus', 'output': 'C++'},
                   {'matched_phrase': 'C ??', 'output': 'C++'}]
        for mode in snapshot['modes']:
            self.assertEqual(mode['replacements'][-3:], aliases)
        # Plain terms are retained, but do not turn common words like "go" into "Go".
        self.assertEqual(len(snapshot['modes'][1]['replacements']), 3)
        self.assertEqual(normalize({})['vocabulary'], [])

    def test_explicit_corrections_precede_vocabulary_and_removal_clears_aliases(self):
        source = {'vocabulary': [{'term': 'OpenCode', 'sounds_like': ['open code']}],
                  'text_replacements': [{'matched_phrase': 'open code', 'output': 'my correction'}]}
        with tempfile.TemporaryDirectory() as directory:
            store = CustomizationStore(Path(directory) / 'settings.json')
            snapshot = store.update(source)
            self.assertEqual(snapshot['modes'][0]['replacements'], [
                {'matched_phrase': 'open code', 'output': 'my correction'},
                {'matched_phrase': 'open code', 'output': 'OpenCode'}])
            source['vocabulary'] = []
            removed = store.update(source)
            self.assertEqual(removed['vocabulary'], [])
            self.assertEqual(removed['modes'][0]['replacements'], source['text_replacements'])
            self.assertNotEqual(removed['revision'], snapshot['revision'])

    def test_invalid_vocabulary_preserves_the_previous_snapshot(self):
        invalid = [None, {}, [1], [' '], ['x' * 257], ['word'] * 1025,
                   [{'term': 'word', 'sounds_like': 'alias'}],
                   [{'term': 'word', 'sounds_like': ['']}],
                   [{'term': 'word', 'sounds_like': [None]}],
                   [{'term': 'word', 'sounds_like': ['x' * 257]}],
                   [{'term': 'word', 'sounds_like': ['alias'] * 33}],
                   [{'term': 'word', 'sounds_like': [str(i) for i in range(32)]}] * 129]
        with tempfile.TemporaryDirectory() as directory:
            store = CustomizationStore(Path(directory) / 'settings.json')
            before = store.update(settings())
            for vocabulary in invalid:
                with self.subTest(vocabulary=str(vocabulary)[:100]), self.assertRaises(ValueError):
                    store.update({'vocabulary': vocabulary})
                self.assertEqual(store.snapshot(), before)

    def test_only_text_preferences_are_exported_and_unsupported_work_is_visible(self):
        snapshot = normalize(settings())
        self.assertEqual(snapshot['source'], 'NewEra')
        self.assertEqual(snapshot['modes'][0]['transformations'], ['lowercase', 'no-trailing-punctuation'])
        self.assertEqual(snapshot['modes'][1]['unsupported'], ['my-script', 'OpenCode rewriting'])
        self.assertNotIn('never-export-this', json.dumps(snapshot))
        self.assertNotIn('private prompt', json.dumps(snapshot))

    def test_legacy_corrections_and_removed_rules(self):
        snapshot = normalize({'text_replacements': [{'matched_phrase': 'um', 'output': ''}]})
        self.assertEqual(snapshot['modes'][0]['replacements'], [{'matched_phrase': 'um', 'output': ''}])
        self.assertEqual(normalize({})['modes'][0]['replacements'], [])

    def test_custom_bundle_and_catalog_are_preserved_without_running_on_server(self):
        source = settings()
        source['script'] = 'var hexMobileConfig = {default: {transformations: {}}};'
        source['script_ids'] = ['my-script']
        snapshot = normalize(source)
        self.assertEqual(snapshot['script'], source['script'])
        self.assertEqual(snapshot['modes'][1]['unsupported'], ['OpenCode rewriting'])
        source['script'] = 'x' * (256 * 1024 + 1)
        with self.assertRaises(ValueError):
            normalize(source)

    def test_atomic_persistence_and_invalid_updates_preserve_the_last_snapshot(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'settings.json'
            store = CustomizationStore(path)
            self.assertFalse(store.snapshot()['configured'])
            before = store.update(settings())
            self.assertEqual(CustomizationStore(path).snapshot(), before)
            self.assertEqual(store.update(settings())['revision'], before['revision'])
            before = store.snapshot()
            for invalid in [[], {'dictation_processing': []}, {'dictation_processing': {'modes': [{}] * 129}},
                            {'text_replacements': [{'matched_phrase': 1, 'output': ''}]}]:
                with self.assertRaises(ValueError):
                    store.update(invalid)
                self.assertEqual(store.snapshot(), before)
            cleared = store.update({})
            self.assertEqual(cleared['modes'][0]['replacements'], [])
            self.assertNotEqual(cleared['revision'], before['revision'])

    def test_authenticated_sync_and_transcription_snapshot(self):
        with tempfile.TemporaryDirectory() as directory:
            store = CustomizationStore(Path(directory) / 'settings.json')
            server = make_server('127.0.0.1', 0, 'test-only', lambda _: 'Hello.', 'test', customizations=store)
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            def request(method, path, body=None, token='test-only', **headers):
                connection = http.client.HTTPConnection(*server.server_address)
                connection.request(method, path, body, {'Authorization': 'Bearer ' + token, **headers})
                response = connection.getresponse()
                result = response.status, json.loads(response.read())
                connection.close()
                return result
            try:
                source = settings()
                source['vocabulary'] = [{'term': 'TypeScript', 'sounds_like': ['type script']}]
                body = json.dumps(source)
                self.assertEqual(request('PUT', '/customizations', body, token='wrong')[0], 401)
                self.assertEqual(request('GET', '/customizations', token='wrong')[0], 401)
                self.assertFalse(request('GET', '/customizations')[1]['configured'])
                self.assertEqual(request('PUT', '/customizations', body, **{'Content-Type': 'application/json'})[0], 200)
                self.assertTrue(request('GET', '/customizations')[1]['configured'])
                before = store.snapshot()
                self.assertEqual(request('PUT', '/customizations', b'[]', **{'Content-Type': 'application/json'})[0], 400)
                self.assertEqual(request('PUT', '/customizations', b'{}', **{'Content-Length': str(MAX_SETTINGS_BYTES + 1)})[0], 413)
                self.assertEqual(request('PUT', '/customizations', b'{}', **{'Content-Type': 'text/plain'})[0], 415)
                self.assertEqual(store.snapshot(), before)
                status, response = request('POST', '/transcribe', wav(1000), **{'Content-Type': 'audio/wav'})
                self.assertEqual(status, 200)
                self.assertEqual(response['text'], 'Hello.')
                self.assertEqual(response['customizations']['revision'], before['revision'])
                self.assertEqual(response['customizations']['vocabulary'], source['vocabulary'])
                self.assertIn({'matched_phrase': 'type script', 'output': 'TypeScript'},
                              response['customizations']['modes'][0]['replacements'])
            finally:
                server.shutdown()
                server.server_close()
                thread.join()


if __name__ == '__main__':
    unittest.main()
