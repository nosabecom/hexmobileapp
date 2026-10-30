"""Pinned Hex-compatible GGUF catalog, atomic downloads and one resident model."""
import array
import hashlib
import io
import json
from pathlib import Path
import threading
import time
import urllib.request
import wave

CATALOG = [
    dict(id='parakeet_unified_en', name='Parakeet Unified English', detail='English · streaming-capable model · GPU recommended', size=731357568, file='parakeet-unified-en-0.6b-Q8_0.gguf', repo='handy-computer/parakeet-unified-en-0.6b-gguf', revision='7e948f21b7bdbac698d3318db9d350f1096f3b6c', sha256='4b50b6dd862bf6e346929aaf4f5eaacec003bfa3f56462d6c874b41ef2f38795'),
    dict(id='cohere_transcribe', name='Cohere Transcribe', detail='14 languages · English selected for this build', size=2410655232, file='cohere-transcribe-03-2026-Q8_0.gguf', repo='handy-computer/cohere-transcribe-03-2026-gguf', revision='dfa4adebb64f3076b7b6b90b721275cc069cb421', sha256='931916663432fd895423a4291a8400221802b288967ca2d435fc5e3141c9e71e'),
    dict(id='parakeet_v3', name='Parakeet Multilingual', detail='Multilingual speech recognition', size=739508576, file='parakeet-tdt-0.6b-v3-Q8_0.gguf', repo='handy-computer/parakeet-tdt-0.6b-v3-gguf', revision='85ac09ea12fc4b1112fa76810059364bc6adc9de', sha256='5859f77944efcd8eafa23a6350731960b2b55b2203df51f319665c807d802cc7'),
    dict(id='whisper_turbo', name='Whisper Large v3 Turbo', detail='Multilingual · general dictation', size=886381824, file='whisper-large-v3-turbo-Q8_0.gguf', repo='handy-computer/whisper-large-v3-turbo-gguf', revision='d222c9f621c1128299248f2ded4d8a1820519780', sha256='d5e65f2b0828802ae2c231673d31982cebe3a778c95d9494a9f3efee6bd17448'),
    dict(id='qwen3', name='Qwen3 ASR', detail='Multilingual · 0.6B parameters', size=850423456, file='Qwen3-ASR-0.6B-Q8_0.gguf', repo='handy-computer/Qwen3-ASR-0.6B-gguf', revision='e4e16599b900eb0cb36e524514756bb92eb092b7', sha256='f081b2d5e23bd669d92cc331d722a8a0681943b8e6f34b48996fd5c319b5acd8'),
    dict(id='sensevoice', name='SenseVoice Small', detail='Compact multilingual model', size=252684608, file='SenseVoiceSmall-Q8_0.gguf', repo='handy-computer/SenseVoiceSmall-gguf', revision='4a08b8e900b38a977e32eb08d5d0697d6e72ba04', sha256='6c759ee4c9748c9b3f7a5a60ca74f0f7e685fb9d45d1378fce7cfd62f59adf29'),
]


class ModelStore:
    def __init__(self, directory, backend='auto'):
        self.directory = Path(directory); self.directory.mkdir(parents=True, exist_ok=True)
        self.backend = backend
        self.progress = {}
        self.lock = threading.Lock()
        self.engine = None; self.loaded = None
        self.session = None
        self.last_compute = 0
        self.compute = threading.RLock()
        self.state_file = self.directory / "active-model.json"

    def definition(self, model_id):
        for model in CATALOG:
            if model['id'] == model_id: return model
        raise ValueError('Unknown model')

    def path(self, model): return self.directory / model['file']

    def installed(self, model):
        path = self.path(model)
        return path.exists() and path.stat().st_size == model['size'] and path.with_suffix('.verified').exists()

    def catalog(self):
        return [dict(id=m['id'], name=m['name'], detail=m['detail'], bytes=m['size'], installed=self.installed(m), download=self.progress.get(m['id']), loaded=m['id']==self.loaded) for m in CATALOG]

    def download(self, model_id):
        model = self.definition(model_id)
        if self.installed(model): return
        if not self.lock.acquire(False): raise ValueError('Another model download is already running')
        try:
            self.progress[model_id] = 'Downloading'
            path = self.path(model); partial = path.with_suffix('.part')
            url = f"https://huggingface.co/{model['repo']}/resolve/{model['revision']}/{model['file']}"
            digest = hashlib.sha256(); count = 0
            with urllib.request.urlopen(url, timeout=60) as source, partial.open('wb') as target:
                while chunk := source.read(1024*1024):
                    count += len(chunk)
                    if count > model['size']: raise ValueError('Model exceeds expected size')
                    target.write(chunk); digest.update(chunk)
                    self.progress[model_id] = f'Downloading {count * 100 // model["size"]}%'
            if count != model['size'] or digest.hexdigest() != model['sha256']: raise ValueError('Model checksum mismatch')
            partial.replace(path); path.with_suffix('.verified').write_text(model['sha256'])
            self.progress[model_id] = None
        except Exception:
            self.progress[model_id] = 'Download failed; tap to retry'
            raise
        finally:
            self.lock.release()

    def preferred(self, fallback):
        try:
            model_id = json.loads(self.state_file.read_text())["model"]
            return model_id if self.installed(self.definition(model_id)) else fallback
        except (OSError, ValueError, KeyError, TypeError):
            return fallback

    def prepare(self, model_id):
        """Serialize with inference. Retain weights, GPU buffers and a reusable session."""
        with self.compute:
            if self.loaded == model_id and self.session is not None:
                if time.monotonic() - self.last_compute > 60:
                    self.session.run(array.array('f', [0.0]) * 16000, language='en')
                    self.last_compute = time.monotonic()
                return False
            model = self.definition(model_id)
            if not self.installed(model):
                raise ValueError('Download this model first')
            import transcribe_cpp
            self.close()
            try:
                self.engine = transcribe_cpp.Model(str(self.path(model)), backend=self.backend)
                self.session = self.engine.session()
                # Exercise the kernels before the first recording arrives. Never use user audio.
                self.session.run(array.array('f', [0.0]) * 16000, language='en')
                self.last_compute = time.monotonic()
                self.loaded = model_id
                temporary = self.state_file.with_suffix('.tmp')
                temporary.write_text(json.dumps({'model': model_id}))
                temporary.replace(self.state_file)
                return True
            except Exception:
                self.close()
                raise

    def close(self):
        with self.compute:
            if self.session is not None:
                self.session.close(); self.session = None
            if self.engine is not None:
                self.engine.close(); self.engine = None
            self.loaded = None

    def __call__(self, data, model_id):
        with self.compute:
            self.prepare(model_id)
            with wave.open(io.BytesIO(data)) as audio:
                pcm = array.array('h', audio.readframes(audio.getnframes()))
            floats = array.array('f', (sample / 32768 for sample in pcm))
            chunks = []
            # Each batch run replaces the result; no user audio/transcript is used as a prompt.
            for offset in range(0, len(floats), 30 * 16000):
                result = self.session.run(floats[offset:offset + 30 * 16000], language='en')
                chunks.append(result.text.strip())
            self.last_compute = time.monotonic()
            return ' '.join(chunks).strip()
