"""Private, bounded WAV transcription endpoint. Audio lives only in RAM."""
import argparse
import hmac
import io
import json
import logging
import math
import os
from pathlib import Path
import secrets
import struct
import threading
import wave
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MAX_BYTES = 16000 * 2 * 120 + 44


def validate_wav(data):
    try:
        with wave.open(io.BytesIO(data)) as audio:
            if (audio.getnchannels(), audio.getsampwidth(), audio.getframerate(), audio.getcomptype()) != (1, 2, 16000, "NONE"):
                raise ValueError("Expected mono 16 kHz PCM16 WAV")
            frames = audio.getnframes()
            if not 1600 <= frames <= 16000 * 120:
                raise ValueError("Audio must be 0.1–120 seconds")
            pcm = audio.readframes(frames)
            if len(pcm) != frames * 2:
                raise ValueError("Truncated WAV")
            energy = sum(sample[0] ** 2 for sample in struct.iter_unpack('<h', pcm))
            return math.sqrt(energy / frames) / 32768
    except (wave.Error, EOFError) as error:
        raise ValueError("Invalid WAV") from error


def make_server(host, port, token, transcribe, model_name, store=None):
    gate = threading.Lock()

    class Handler(BaseHTTPRequestHandler):
        def setup(self):
            super().setup()
            self.connection.settimeout(15)

        def log_message(self, *_):
            pass  # No transcripts, authorization headers or request details in logs.

        def reply(self, status, payload):
            data = json.dumps(payload).encode()
            self.send_response(status)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(data)))
            self.send_header('Cache-Control', 'no-store')
            self.end_headers()
            try:
                self.wfile.write(data)
            except (BrokenPipeError, ConnectionResetError):
                pass

        def authorized(self):
            if not hmac.compare_digest(self.headers.get('Authorization', ''), 'Bearer ' + token):
                self.reply(401, {'error': 'Unauthorized'})
                return False
            return True

        def do_GET(self):
            if not self.authorized():
                return
            if self.path == '/models' and store is not None:
                return self.reply(200, {'models': store.catalog()})
            if self.path != '/health':
                return self.reply(404, {'error': 'Not found'})
            self.reply(200, {'model': model_name, 'ready': True})

        def do_POST(self):
            if not self.authorized():
                return
            if self.path.startswith('/models/') and self.path.endswith('/download') and store is not None:
                model_id = self.path.split('/')[2]
                try: store.definition(model_id)
                except ValueError: return self.reply(404, {'error': 'Unknown model'})
                def download():
                    try: store.download(model_id)
                    except Exception: logging.error('Model download failed: %s', model_id)
                threading.Thread(target=download, daemon=True).start()
                return self.reply(200, {'status': 'Requested'})
            if self.path != '/transcribe':
                return self.reply(404, {'error': 'Not found'})
            if self.headers.get('Transfer-Encoding'):
                return self.reply(400, {'error': 'Use Content-Length'})
            try:
                length = int(self.headers.get('Content-Length', '0'))
            except ValueError:
                return self.reply(400, {'error': 'Invalid Content-Length'})
            if not 44 <= length <= MAX_BYTES:
                return self.reply(413, {'error': 'Recording too large or empty'})
            if self.headers.get('Content-Type', '').split(';')[0] != 'audio/wav':
                return self.reply(415, {'error': 'Expected audio/wav'})
            if not gate.acquire(blocking=False):
                return self.reply(429, {'error': 'Already transcribing; try again'})
            try:
                data = self.rfile.read(length)
                if len(data) != length:
                    return self.reply(400, {'error': 'Incomplete request'})
                rms = validate_wav(data)
                model_id = self.headers.get('X-Hex-Model', model_name)
                text = '' if rms < 0.001 else (store(data, model_id) if store else transcribe(data))
                self.reply(200, {'text': text})
            except ValueError as error:
                self.reply(400, {'error': str(error)})
            except (TimeoutError, ConnectionError):
                self.reply(408, {'error': 'Upload timed out'})
            except Exception:
                logging.exception('Transcription engine failed')
                self.reply(500, {'error': 'Transcription failed'})
            finally:
                gate.release()

    return ThreadingHTTPServer((host, port), Handler)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--host', default='127.0.0.1', choices=['127.0.0.1', '::1'])
    parser.add_argument('--port', type=int, default=8765)
    parser.add_argument('--model', default='parakeet_unified_en')
    parser.add_argument('--models-dir', type=Path, default=Path('models'))
    parser.add_argument('--backend', default='auto', choices=['auto','cpu','vulkan','cuda'])
    parser.add_argument('--token-file', type=Path, default=Path('.server-token'))
    args = parser.parse_args()
    if not args.token_file.exists():
        fd = os.open(args.token_file, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, 'w') as file:
            file.write(secrets.token_urlsafe(32))
    token = args.token_file.read_text().strip()
    if len(token) < 24:
        raise SystemExit('Server token must be at least 24 characters')
    from model_store import ModelStore
    store = ModelStore(args.models_dir, args.backend)
    server = make_server(args.host, args.port, token, None, args.model, store)
    print(f'Hex server ready on {args.host}:{args.port}, model {args.model}, {args.backend}', flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == '__main__':
    main()
