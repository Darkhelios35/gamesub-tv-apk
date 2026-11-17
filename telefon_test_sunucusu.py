"""
GameSub TV — Telefon Kontrol Paneli (QR Eşleşme) Önizleme ve Test Sunucusu.
TV'ye APK'yı kurmadan önce telefonunuzdan QR okutarak mobil arayüzü (remote_panel.html)
canlı test etmenizi sağlar.
"""
import json
import socket
import time
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

import requests

ASSET_HTML = Path(__file__).parent / "app" / "src" / "main" / "assets" / "remote_panel.html"

STATE = {
    "roiX": 0.18,
    "roiY": 0.76,
    "roiW": 0.64,
    "roiH": 0.16,
    "ovlX": 0.18,
    "ovlY": 0.78,
    "ovlW": 0.64,
    "ovlH": 0.14,
    "engine": "hybrid",
    "apiKey": "",
    "fontSize": 26,
    "bgOpacity": 175,
    "textColor": "#FFFFFF",
    "strokeWidth": 4,
    "autoHideSec": 6,
    "scanIntervalMs": 320,
    "paused": False,
}


def get_local_ip() -> str:
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"


class PreviewHandler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        pass

    def _send_json(self, data: dict, status: int = 200):
        raw = json.dumps(data, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self):
        if self.path.startswith("/api/config"):
            self._send_json(STATE)
        elif self.path.startswith("/api/status"):
            self._send_json(
                {
                    "serviceRunning": True,
                    "engine": STATE.get("engine", "hybrid").upper(),
                    "lastEn": "Geralt: Hold on, I'll check the northern gate before midnight.",
                    "lastTr": "Geralt: Bekle biraz, gece yarısından önce kuzey kapısını kontrol edeceğim.",
                }
            )
        else:
            html_bytes = ASSET_HTML.read_bytes()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(html_bytes)))
            self.end_headers()
            self.wfile.write(html_bytes)

    def do_POST(self):
        if self.path.startswith("/api/config"):
            length = int(self.headers.get("Content-Length", 0))
            body = self.rfile.read(length).decode("utf-8")
            STATE.update(json.loads(body))
            print(f"[TV Senkronizasyon] Telefondan yeni ayarlar alındı -> Motor: {STATE['engine']}, Font: {STATE['fontSize']}sp")
            self._send_json({"ok": True})
        elif self.path.startswith("/api/benchmark"):
            self._send_json(
                {
                    "report": "✅ Hız testi tamamlandı (TV Simülasyonu)",
                    "speeds": {
                        "hybrid": "✅ 52 ms",
                        "groq_fast": "✅ 68 ms",
                        "lingva": "✅ 108 ms",
                        "groq_ai": "✅ 134 ms",
                        "mymemory": "✅ 162 ms",
                        "gemini_ai": "✅ 185 ms",
                    },
                }
            )


if __name__ == "__main__":
    ip = get_local_ip()
    url = f"http://{ip}:8765"
    print("=" * 60)
    print("  📺 GameSub TV — Telefon Kontrol Paneli Test Sunucusu")
    print(f"  📱 Telefonunuzun tarayıcısından şu adresi açın: {url}")
    print("=" * 60)
    HTTPServer(("0.0.0.0", 8765), PreviewHandler).serve_forever()
