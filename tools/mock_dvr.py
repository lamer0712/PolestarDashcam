#!/usr/bin/env python3
"""OEM-schema mock DVR. Synthetic video bytes test transport, not video playback.
Only binds loopback. Use adb reverse tcp:8765 tcp:8765 on an emulator.
"""
import argparse
import base64
import hashlib
import json
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, unquote, urlsplit

VIDEO = bytes(range(256)) * 8192
PHOTO = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=")
STATE = {"recording": "normal", "requireMode": False, "failRestore": False, "delay": 0, "failCategory": "", "posts": []}

def listing(kind):
    count = 53 if kind == "normal" else 2
    return [{"mediaType": kind, "name": f"{kind}_{i:03d}.{'jpg' if kind == 'photo' else 'mp4'}",
             "id": str(i), "size": len(PHOTO if kind == "photo" else VIDEO),
             "dateTime": 1789080000 - i * 60, "duration": 0 if kind == "photo" else 60}
            for i in range(count)]

class Handler(BaseHTTPRequestHandler):
    def json(self, value, status=200):
        data = json.dumps(value).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        url = urlsplit(self.path)
        query = parse_qs(url.query)
        if url.path == "/__state":
            return self.json({**STATE, "videoSha256": hashlib.sha256(VIDEO).hexdigest()})
        if url.path == "/status":
            return self.json({"usable": True, "recording": STATE["recording"]})
        if STATE["requireMode"] and STATE["recording"] != "in-file-list":
            return self.json({"error": 3, "message": "enter file-list mode first"}, 409)
        if url.path == "/mediaDirList":
            return self.json({"mediaList": [{"type": "image" if k == "photo" else "video", "mediaType": k,
                                            "mediaPath": f"DCIM/{k}", "fileCount": len(listing(k))}
                                           for k in ("normal", "emergency", "photo")]})
        if url.path == "/filelist":
            kind = query.get("type", [""])[0]
            if kind not in ("normal", "emergency", "photo"):
                return self.json({"error": 1, "message": "invalid category"}, 400)
            if STATE["failCategory"] == kind:
                return self.json({"error": 2, "message": "simulated category failure"}, 503)
            start, count = int(query.get("startIndex", [0])[0]), int(query.get("count", [50])[0])
            return self.json({"fileList": listing(kind)[start:start + count]})
        if url.path.startswith("/DCIM/"):
            _, _, kind, name = unquote(url.path).split("/", 3)
            if kind not in ("normal", "emergency", "photo") or name not in {x["name"] for x in listing(kind)}:
                return self.json({"error": 404, "message": "missing"}, 404)
            payload = PHOTO if kind == "photo" else VIDEO
            self.send_response(200)
            self.send_header("Content-Type", "image/png" if kind == "photo" else "video/mp4")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            try:
                for offset in range(0, len(payload), 16384):
                    self.wfile.write(payload[offset:offset + 16384])
                    self.wfile.flush()
                    time.sleep(STATE["delay"])
            except (BrokenPipeError, ConnectionResetError):
                pass
            return
        self.json({"error": 404, "message": "not found"}, 404)

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))))
        if self.path == "/__control":
            for key in STATE:
                if key in body:
                    STATE[key] = body[key]
            return self.json(STATE)
        if self.path != "/status" or body.get("app") != "gallery":
            return self.json({"error": 400, "message": "invalid request"}, 400)
        recording = body.get("recording")
        STATE["posts"].append(recording)
        if recording == "normal" and STATE["failRestore"]:
            return self.json({"error": 5, "message": "simulated restore failure"}, 503)
        if recording not in ("normal", "enter-file-list"):
            return self.json({"error": 400, "message": "invalid mode"}, 400)
        STATE["recording"] = "in-file-list" if recording == "enter-file-list" else "normal"
        self.json({"result": "ok"})

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args()
    print(f"Mock DVR on http://127.0.0.1:{args.port}; synthetic video SHA256={hashlib.sha256(VIDEO).hexdigest()}", flush=True)
    ThreadingHTTPServer(("127.0.0.1", args.port), Handler).serve_forever()
