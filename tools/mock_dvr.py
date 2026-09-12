#!/usr/bin/env python3
"""OEM-schema mock DVR.

By default it serves deterministic transport-test bytes as video/mp4. Pass
--video-mp4 PATH to serve a real playable MP4 for UI playback tests. Only binds
loopback. Use adb reverse tcp:8765 tcp:8765 on an emulator.
"""
import argparse
import base64
import hashlib
import json
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, unquote, urlsplit

VIDEO = bytes(range(256)) * 8192
VIDEO_IS_PLAYABLE = False
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
            content_type = "image/png" if kind == "photo" else "video/mp4"
            range_header = self.headers.get("Range")
            start, end = 0, len(payload) - 1
            partial = False
            if range_header and range_header.startswith("bytes="):
                spec = range_header.removeprefix("bytes=")
                first, _, last = spec.partition("-")
                if first.isdigit():
                    start = min(int(first), len(payload))
                    if last.isdigit():
                        end = min(int(last), len(payload) - 1)
                    partial = True
            status = 206 if partial else 200
            body = payload[start:end + 1] if start <= end else b""
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("Content-Length", str(len(body)))
            if partial:
                self.send_header("Content-Range", f"bytes {start}-{end}/{len(payload)}")
            self.end_headers()
            try:
                for offset in range(0, len(body), 16384):
                    self.wfile.write(body[offset:offset + 16384])
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
    parser.add_argument("--video-mp4", help="Optional playable MP4 to serve for video entries")
    args = parser.parse_args()
    if args.video_mp4:
        with open(args.video_mp4, "rb") as handle:
            VIDEO = handle.read()
        VIDEO_IS_PLAYABLE = True
    kind = "playable MP4" if VIDEO_IS_PLAYABLE else "synthetic video bytes"
    print(f"Mock DVR on http://127.0.0.1:{args.port}; {kind} SHA256={hashlib.sha256(VIDEO).hexdigest()}", flush=True)
    ThreadingHTTPServer(("127.0.0.1", args.port), Handler).serve_forever()
