#!/usr/bin/env python3
"""Lokální web s reportem: http://localhost:8390

GET  /              -> report.html
POST /refresh       -> spustí scraper na pozadí
GET  /status        -> {"running": bool, "updated": "..."}
"""
import json
import subprocess
import sys
import threading
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

BASE = Path(__file__).resolve().parent
REPORT = BASE / "report.html"
PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8390

lock = threading.Lock()
running = False


def scrape():
    global running
    try:
        subprocess.run([sys.executable, str(BASE / "duke_scraper.py")], cwd=BASE, timeout=900)
    finally:
        running = False


class H(BaseHTTPRequestHandler):
    def send(self, code, body, ctype="text/html; charset=utf-8"):
        data = body.encode() if isinstance(body, str) else body
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path in ("/", "/index.html", "/report.html"):
            if REPORT.exists():
                self.send(200, REPORT.read_bytes())
            else:
                self.send(200, "<p>Report ještě neexistuje – klikni na <form method=post action=/refresh>"
                               "<button>Aktualizovat</button></form></p>")
        elif self.path == "/status":
            upd = datetime.fromtimestamp(REPORT.stat().st_mtime).isoformat() if REPORT.exists() else None
            self.send(200, json.dumps({"running": running, "updated": upd}), "application/json")
        else:
            self.send(404, "Nenalezeno")

    def do_POST(self):
        global running
        if self.path != "/refresh":
            return self.send(404, "Nenalezeno")
        with lock:
            if not running:
                running = True
                threading.Thread(target=scrape, daemon=True).start()
        self.send(202, json.dumps({"running": True}), "application/json")

    def log_message(self, *a):
        pass


if __name__ == "__main__":
    print(f"Duke 390 hlídač běží na http://localhost:{PORT}")
    ThreadingHTTPServer(("127.0.0.1", PORT), H).serve_forever()
