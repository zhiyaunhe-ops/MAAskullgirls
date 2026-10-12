"""poll_score 的离线测试: 起一次性 http.server 喂罐头 /api/state。

不碰真 bot (8790) —— 全部走 127.0.0.1 随机端口。
跑法: C:/Users/zhiya/anaconda3/python.exe -m pytest tests/test_poll_score.py -q
"""
import json
import sys
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO / "tools"))

import poll_score as P  # noqa: E402


class _Canned:
    """罐头 /api/state。rising>0 时每被读一次分数就涨一档 (模拟 bot 逐场得分)。"""

    def __init__(self, score=0, rising=0):
        self.score = score
        self.rising = rising

    def read(self):
        cur = self.score
        if self.rising:
            self.score += self.rising
        return cur


def _serve(canned):
    class H(BaseHTTPRequestHandler):
        def do_GET(self):
            body = json.dumps({"svc": "sgm-pf-bot", "status": "RUNNING",
                               "step": "战斗结算",
                               "score": canned.read()}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *a):  # 静音
            pass

    srv = HTTPServer(("127.0.0.1", 0), H)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv, f"http://127.0.0.1:{srv.server_address[1]}/api/state"


def test_到阈值即退0():
    canned = _Canned(1_200_000)
    srv, url = _serve(canned)
    lines = []
    try:
        rc = P.poll(1_000_000, 1, url, out=lines.append, interval=0)
    finally:
        srv.shutdown()
    assert rc == 0, f"到阈值应退 0, 实际 {rc}"
    assert any("REACHED" in ln for ln in lines), lines


def test_分数逐场涨_过线那轮就退():
    """bot 是一场场打的: 30w → 70w → 110w, 必须在过 100w 那一轮退 0。"""
    canned = _Canned(300_000, rising=400_000)
    srv, url = _serve(canned)
    lines = []
    try:
        rc = P.poll(1_000_000, 1, url, out=lines.append, interval=0)
    finally:
        srv.shutdown()
    assert rc == 0, f"过线那轮应退 0, 实际 {rc} (lines={lines})"
    # 只应读到第 3 次 (110w) 就停, 不该继续轮
    assert len([ln for ln in lines if "score=" in ln]) == 3, lines


def test_不到阈值超时退2():
    canned = _Canned(500)
    srv, url = _serve(canned)
    lines = []
    try:
        rc = P.poll(1_000_000, 0.05, url, out=lines.append, interval=0)
    finally:
        srv.shutdown()
    assert rc == 2, f"超时未到应退 2 (提醒调用方别停 bot), 实际 {rc}"
    assert any("TIMEOUT" in ln for ln in lines), lines


def test_服务不在不崩_留痕后继续轮():
    """端口没人听: 不能抛异常, 要留痕后继续轮 (直到超时)。"""
    srv = HTTPServer(("127.0.0.1", 0), BaseHTTPRequestHandler)
    port = srv.server_address[1]
    srv.server_close()
    lines = []
    rc = P.poll(1_000_000, 0.05, f"http://127.0.0.1:{port}/api/state",
                out=lines.append, interval=0, err_sleep=0)
    assert rc == 2, f"服务不在也应超时退 2, 实际 {rc}"
    assert any("取不到" in ln for ln in lines), lines
