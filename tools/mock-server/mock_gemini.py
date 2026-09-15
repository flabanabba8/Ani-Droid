#!/usr/bin/env python3
"""Offline Gemini contract double. Logs book text/prompts, never request credentials."""
import argparse
import base64
import hashlib
import json
import re
import subprocess
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

LOCK = threading.Lock()
LOG_LOCK = threading.Lock()
AUDIO = {}


def speech(text):
    key = hashlib.sha256(text.encode()).hexdigest()
    with LOCK:
        if key not in AUDIO:
            with tempfile.TemporaryDirectory(prefix="reader-speech-") as folder:
                wav = str(Path(folder) / "speech.wav")
                subprocess.run(["flite", "-t", text, "-o", wav], check=True, capture_output=True)
                raw = subprocess.run(["ffmpeg", "-v", "error", "-i", wav, "-ar", "24000", "-ac", "1", "-f", "s16le", "-"], check=True, capture_output=True).stdout
                AUDIO[key] = raw
        return AUDIO[key]


def analyze(text):
    cast, lines = {}, []
    # Deliberately simple: this is a transport/playback test, not a substitute for a language model.
    for paragraph in re.findall(r'<p id="\d+">(.*?)</p>', text, re.S):
        for q, quote in re.findall(r'<q id="([\d.]+)">(.*?)</q>', paragraph, re.S):
            after = paragraph.split(f'</q>', 1)[-1]
            match = re.search(r'\b(?:said|cried|replied|asked|thought|shouted|answered)\s+(?:the\s+)?([A-Z][a-z]+(?:\s+[A-Z][a-z]+)?)', after)
            if not match:
                match = re.search(r'\b([A-Z][a-z]+)\s+(?:said|cried|replied|asked|thought)', paragraph)
            name = match.group(1) if match else "unknown"
            speaker = name.lower().replace(" ", "-")
            if name != "unknown":
                gender = "female" if name in ("Alice", "Elizabeth", "Jane", "Lydia", "Mary") else "unknown"
                cast[speaker] = dict(id=speaker, name=name, aliases=[], gender=gender, age="", description="Mock attribution from a speech tag", voiceStyle="Use bright, curious delivery." if gender == "female" else "Use a distinct, measured delivery.", suggestedVoice="Leda" if gender == "female" else "Charon")
            lines.append(dict(q=q, speaker=speaker, delivery="curious" if "?" in quote else "natural"))
    return dict(characters=list(cast.values()), lines=lines)


class Handler(BaseHTTPRequestHandler):
    def reply(self, value, code=200):
        data = json.dumps(value).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        field = "publisherModels" if "publishers/google" in self.path else "models"
        self.reply({field: [{"name": "models/mock-analysis"}, {"name": "models/gemini-3.1-flash-tts-preview"}]})

    def do_POST(self):
        try:
            body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))))
            vertex = "/projects/" in self.path
            if vertex and (not self.headers.get("Authorization", "").startswith("Bearer ") or not self.headers.get("x-goog-user-project")):
                self.reply({"error": {"message": "Vertex requires bearer token and project header"}}, 401)
                return
            with LOG_LOCK:
                print(json.dumps({"path": self.path, "bearer_auth": self.headers.get("Authorization", "").startswith("Bearer "), "billing_project": self.headers.get("x-goog-user-project"), "request": body}, ensure_ascii=False), flush=True)
            if self.path.endswith("text:synthesize"):
                import io
                import wave
                raw = speech(body["input"]["text"])
                output = io.BytesIO()
                with wave.open(output, "wb") as wav:
                    wav.setnchannels(1)
                    wav.setsampwidth(2)
                    wav.setframerate(24000)
                    wav.writeframes(raw)
                self.reply({"audioContent": base64.b64encode(output.getvalue()).decode()})
            elif self.path.endswith("interactions"):
                text = body["input"].split("#### TRANSCRIPT\n")[-1]
                self.reply({"output_audio": {"data": base64.b64encode(speech(text)).decode(), "mime_type": "audio/pcm;rate=24000"}})
            else:
                prompt = body["contents"][0]["parts"][0]["text"]
                if body.get("generationConfig", {}).get("responseMimeType") == "application/json":
                    part = {"text": json.dumps(analyze(prompt))}
                elif self.server.force_interactions:
                    self.reply({"error": {"message": "Use interactions for this mock scenario"}}, 404)
                    return
                else:
                    text = prompt.split("#### TRANSCRIPT\n")[-1]
                    part = {"inlineData": {"data": base64.b64encode(speech(text)).decode(), "mimeType": "audio/L16;rate=24000"}}
                self.reply({"candidates": [{"content": {"parts": [part]}}]})
        except Exception as error:
            self.reply({"error": {"message": str(error)}}, 500)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--force-interactions", action="store_true")
    args = parser.parse_args()
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    server.force_interactions = args.force_interactions
    print(f"Mock Gemini listening on {args.host}:{args.port}", flush=True)
    server.serve_forever()
