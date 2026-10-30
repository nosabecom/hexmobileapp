import http.client
import io
from pathlib import Path
import struct
import sys
import threading
import unittest
import wave
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from hex_server import make_server, validate_wav, MAX_BYTES
from model_store import ModelStore, CATALOG
import tempfile
from unittest.mock import patch, MagicMock
from types import SimpleNamespace


def wav(level=0, rate=16000):
    out = io.BytesIO()
    with wave.open(out, 'wb') as f:
        f.setnchannels(1); f.setsampwidth(2); f.setframerate(rate)
        f.writeframes(struct.pack('<h', level) * 1600)
    return out.getvalue()


class ServerTests(unittest.TestCase):
    def setUp(self):
        self.calls = []
        self.server = make_server('127.0.0.1', 0, 'test-only', lambda data: self.calls.append(data) or 'Hello world.', 'test')
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown(); self.server.server_close(); self.thread.join()

    def request(self, body=None, auth='test-only', **headers):
        c = http.client.HTTPConnection(*self.server.server_address)
        c.request('GET' if body is None else 'POST', '/health' if body is None else '/transcribe', body,
                  {'Authorization': 'Bearer ' + auth, 'Content-Type': 'audio/wav', **headers})
        r = c.getresponse(); status, data = r.status, r.read(); c.close()
        return status, data

    def test_auth(self):
        self.assertEqual(self.request(auth='wrong')[0], 401)
        self.assertEqual(self.request()[0], 200)

    def test_silence_is_not_hallucinated(self):
        self.assertIn(b'"text": ""', self.request(wav())[1]); self.assertEqual(self.calls, [])

    def test_audio_reaches_engine(self):
        status, data = self.request(wav(1000)); self.assertEqual(status, 200)
        self.assertIn(b'Hello world.', data); self.assertEqual(len(self.calls), 1)

    def test_invalid_and_oversized(self):
        self.assertEqual(self.request(b'bad' * 100)[0], 400)
        self.assertEqual(self.request(wav(), **{'Content-Length': str(MAX_BYTES + 1)})[0], 413)
        self.assertEqual(self.request(wav(rate=8000))[0], 400)

    def test_reject_truncated_wav(self):
        with self.assertRaises(ValueError): validate_wav(wav()[:-20])


class ModelStoreTests(unittest.TestCase):
    def test_only_verified_models_are_installed(self):
        with tempfile.TemporaryDirectory() as directory:
            store=ModelStore(directory)
            self.assertEqual(len(store.catalog()),6)
            self.assertTrue(all(not m['installed'] for m in store.catalog()))
            with self.assertRaises(ValueError): store.definition('../other')

    def test_checksum_failure_never_installs_partial_weights(self):
        with tempfile.TemporaryDirectory() as directory:
            store=ModelStore(directory)
            with patch('urllib.request.urlopen', return_value=io.BytesIO(b'invalid model')):
                with self.assertRaises(ValueError): store.download('parakeet_unified_en')
            self.assertFalse(store.installed(CATALOG[0]))
            self.assertIn('failed',store.progress['parakeet_unified_en'])


class PreparationTests(unittest.TestCase):
    def test_reuses_session_and_closes_before_switch(self):
        with tempfile.TemporaryDirectory() as directory:
            store = ModelStore(directory)
            engines = []
            def make_model(*args, **kwargs):
                engine = MagicMock()
                engine.session.return_value.run.return_value.text = 'fresh result'
                engines.append(engine)
                return engine
            with patch.dict(sys.modules, {'transcribe_cpp': SimpleNamespace(Model=make_model)}), patch.object(store, 'installed', return_value=True):
                self.assertTrue(store.prepare('parakeet_unified_en'))
                self.assertFalse(store.prepare('parakeet_unified_en'))
                self.assertEqual(store(wav(1000), 'parakeet_unified_en'), 'fresh result')
                self.assertEqual(store(wav(1000), 'parakeet_unified_en'), 'fresh result')
                self.assertEqual(len(engines), 1)
                self.assertEqual(engines[0].session.call_count, 1)
                self.assertEqual(engines[0].session.return_value.run.call_count, 3)
                store.last_compute -= 61
                store.prepare('parakeet_unified_en')
                self.assertEqual(engines[0].session.return_value.run.call_count, 4)
                self.assertEqual(len(engines), 1)
                store.prepare('cohere_transcribe')
                engines[0].session.return_value.close.assert_called_once()
                engines[0].close.assert_called_once()
                self.assertEqual(store.preferred('parakeet_unified_en'), 'cohere_transcribe')
                store.close()

    def test_failed_preparation_is_retryable(self):
        with tempfile.TemporaryDirectory() as directory:
            store = ModelStore(directory)
            engine = MagicMock()
            engine.session.return_value.run.side_effect = RuntimeError('GPU not ready')
            with patch.dict(sys.modules, {'transcribe_cpp': SimpleNamespace(Model=lambda *a, **kw: engine)}), patch.object(store, 'installed', return_value=True):
                with self.assertRaises(RuntimeError): store.prepare('parakeet_unified_en')
                self.assertIsNone(store.loaded)
                self.assertIsNone(store.session)
                engine.session.return_value.run.side_effect = None
                self.assertTrue(store.prepare('parakeet_unified_en'))
                store.close()

    def test_authenticated_prepare_endpoint_and_readiness(self):
        store = MagicMock(); store.loaded = None; store.prepare.return_value = True
        server = make_server('127.0.0.1', 0, 'test-only', None, 'parakeet_unified_en', store)
        thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
        try:
            for token, expected in [('wrong', 401), ('test-only', 200)]:
                c = http.client.HTTPConnection(*server.server_address)
                c.request('POST', '/prepare', headers={'Authorization':'Bearer '+token, 'X-Hex-Model':'parakeet_unified_en'})
                r = c.getresponse(); self.assertEqual(r.status, expected); r.read(); c.close()
            store.prepare.assert_called_once_with('parakeet_unified_en')
            c = http.client.HTTPConnection(*server.server_address)
            c.request('GET','/health',headers={'Authorization':'Bearer test-only'})
            r = c.getresponse(); self.assertIn(b'"ready": false', r.read()); c.close()
        finally:
            server.shutdown(); server.server_close(); thread.join()


if __name__ == '__main__': unittest.main()
