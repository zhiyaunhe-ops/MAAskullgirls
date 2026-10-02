"""Run the actual WebUI with sample data; no MAA, ADB, network fetches or disk writes.

python tools/preview_webui.py --port 8800
All button actions affect this process's in-memory demo state only.
"""
import argparse
import json
import threading
import time
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, unquote, urlsplit

ROOT = Path(__file__).resolve().parent.parent
STATIC = ROOT / "tools" / "static"
LOCK = threading.RLock()
NOW = int(time.time())
SESSIONS = [
    {"id": "default", "name": "Monthly Prize Fight", "rule": None, "count": 24,
     "last_ts": NOW, "score_target": 10000000, "energy_cost": 4, "rest_every": 10, "rest_minutes": 5},
    {"id": "fire", "name": "Fire Element", "rule": {"type": "element", "value": "fire"},
     "count": 12, "last_ts": NOW - 3600, "score_target": 5000000, "energy_cost": 4,
     "scene": "TRIAL BY FIRE"},
]
QUEUE = ["fire"]   # 接力队列演示: 打完当前场自动接 Fire Element
STATE = {
    "svc": "sgm-pf-bot-preview", "demo": True, "status": "IDLE",
    "step": "演示数据 · 操作仅影响本次预览", "fight_no": 24, "score": 6842500, "streak": 18,
    "score_target": 10000000, "energy_cost": 4, "pf_rule": None,
    "filter_favorite": True, "close_on_goal": True, "rest_every": 10, "rest_minutes": 5,
    "rest_until": 0, "session_id": "default", "session_name": "Monthly Prize Fight",
    "scene": None, "queue": QUEUE,
    "shot_ver": 1, "shot_time": "14:32:08", "log_total": 8,
    "logs": [["14:28:01", "info", "演示预览：未连接模拟器，所有操作只保存在内存。"],
             ["14:28:04", "step", "[第 24 场] 开始 Prize Fight 循环"],
             ["14:28:05", "info", "选对手 → 火框倍率 × 2.5"],
             ["14:28:07", "info", "能量检查通过 · 3 名角色已就绪"],
             ["14:28:09", "step", "FIGHT → 自动战斗"],
             ["14:31:56", "info", "VICTORY · 连胜 18 · 本场 +425,000"],
             ["14:32:01", "warn", "连续 10 场完成，进入休息周期"],
             ["14:32:08", "info", "已暂停 · 等待下一次出发"]],
}
DAILY = {"queue": ["missions", "guild_ops", "social", "inbox"], "pool": {}, "names": {}}
MUMU = {"running": False, "busy": None, "close_on_goal": True}


class PreviewHandler(SimpleHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def reply(self, data, status=200):
        body = json.dumps(data, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        parts = urlsplit(self.path)
        path = parts.path
        with LOCK:
            if path == "/api/state":
                return self.reply(STATE)
            if path == "/api/mumu":
                return self.reply(MUMU)
            if path == "/api/sessions":
                return self.reply({"sessions": SESSIONS, "active": STATE["session_id"],
                                   "queue": list(QUEUE),
                                   "running": STATE["status"] == "RUNNING"})
            if path == "/api/daily":
                return self.reply({"data": DAILY, "saved": True})
            if path == "/api/summary":
                # 与真实服务端同构 (真实 eta_sec 由记分点速率算出; 前端自行用 target/score 复算)
                return self.reply({"per_min": 96500.0, "last_delta": 425000,
                                   "score": STATE["score"],
                                   "target": STATE.get("score_target") or 150_000_000,
                                   "eta_sec": None})
            if path == "/api/history":
                ids = parse_qs(parts.query).get("sessions", [STATE["session_id"]])[0].split(",")
                return self.reply({"series": [{"id": s["id"], "name": s["name"], "rule": s["rule"],
                    "points": [{"ts": NOW - (24-i)*65, "score": int(6842500*i/24),
                                "streak": max(0, i-6), "fight": i} for i in range(25)]}
                    for s in SESSIONS if s["id"] in ids]})
            if path == "/api/jjc":
                return self.reply({"snapshot": {"day": "DEMO", "revision": 1, "fp": "sample",
                    "daily_events": ["Filia", "Peacock", "Big Band"], "entries": [
                    {"label_cn": "月场 PF", "name": "Monthly Prize Fight", "active": True},
                    {"label_cn": "元素 PF", "kind": "element", "name": "Fire", "active": True},
                    {"label_cn": "角色 PF", "kind": "character", "name": "Peacock", "active": False}]},
                    "versions": {}, "session_names": {s["id"]: s["name"] for s in SESSIONS}})
        if path == "/":
            self.path = "/tools/static/webui.html"
        elif path.startswith("/static/"):
            target = (STATIC / unquote(path[len("/static/"):])).resolve()
            if STATIC not in target.parents or not target.is_file():
                return self.send_error(404)
            self.path = "/tools" + path
        elif path == "/shot.jpg":
            # 净化帧: 顶栏玩家名/等级/货币已抹平 (tools/static/mock_shot.jpg)。
            # 原 docs/screenshots/explore 截图 2026-09-30 起不入库 (gitignore)。
            self.path = "/tools/static/mock_shot.jpg"
        elif path != "/static/themes-preview.html":
            return self.send_error(404)
        return super().do_GET()

    def do_POST(self):
        try:
            size = int(self.headers.get("Content-Length", "0"))
            if size > 65536:
                return self.reply({"error": "Preview request too large"}, 413)
            body = json.loads(self.rfile.read(size) or b"{}")
            if not isinstance(body, dict):
                raise ValueError()
        except (ValueError, json.JSONDecodeError):
            return self.reply({"error": "Expected a JSON object"}, 400)
        path = urlsplit(self.path).path
        with LOCK:
            if path == "/api/daily":
                DAILY.clear()
                DAILY.update(body)
            elif path == "/api/settings":
                for key in ("filter_favorite", "close_on_goal"):
                    source = "close_mumu_on_goal" if key == "close_on_goal" else key
                    if source in body:
                        STATE[key] = bool(body[source])
            elif path in ("/api/start", "/api/sessions/select"):
                session = next((s for s in SESSIONS if s["id"] == body.get("session_id")), None)
                if session is None:
                    return self.reply({"error": "请选择演示场次"}, 400)
                STATE.update(session_id=session["id"], session_name=session["name"],
                             pf_rule=session["rule"], score_target=session.get("score_target"),
                             energy_cost=session.get("energy_cost", 4), rest_every=session.get("rest_every", 0),
                             rest_minutes=session.get("rest_minutes", 0), scene=session.get("scene"))
                if path == "/api/start":
                    STATE["status"] = "RUNNING"
            elif path == "/api/queue/set":
                ids = body.get("ids")
                known = {s["id"] for s in SESSIONS}
                seen: set = set()
                clean = []
                for x in (ids if isinstance(ids, list) else []):
                    if isinstance(x, str) and x in known and x not in seen:
                        seen.add(x)
                        clean.append(x)
                QUEUE[:] = clean      # 与真实 STORE.queue_set 同口径: 去重+丢不存在
                STATE["queue"] = list(QUEUE)
                return self.reply({"ok": True, "queue": list(QUEUE), "demo": True})
            elif path == "/api/end":
                STATE["status"] = "IDLE"
            elif path == "/api/stop":
                STATE["status"] = "STOPPED"
            elif path == "/api/sessions/update":
                session = next((s for s in SESSIONS if s["id"] == body.get("id")), None)
                if session:
                    session.update({k: v for k, v in body.items() if k != "id"})
                    if session["id"] == STATE["session_id"]:
                        for key in ("score_target", "energy_cost", "rest_every",
                                    "rest_minutes", "scene"):
                            if key in body:
                                STATE[key] = body[key]
                        STATE["pf_rule"] = session.get("rule")
            elif path == "/api/sessions/create":
                name = str(body.get("name", "")).strip()
                if not name:
                    return self.reply({"error": "请填写场次名称"}, 400)
                session = {"id": "demo-" + str(time.time_ns()), "name": name,
                           "rule": body.get("rule"), "count": 0, "last_ts": 0}
                for key in ("score_target", "energy_cost", "rest_every", "rest_minutes",
                            "parent", "scene"):
                    if key in body:
                        session[key] = body[key]
                SESSIONS.append(session)
                return self.reply({"ok": True, "session": session, "demo": True})
            elif path == "/api/sessions/delete":
                sid = body.get("id")
                if sid == "default" or sid == STATE["session_id"]:
                    return self.reply({"error": "演示中不能删除 Default 或当前场次"}, 409)
                SESSIONS[:] = [s for s in SESSIONS if s["id"] != sid]
                QUEUE[:] = [x for x in QUEUE if x != sid]
                STATE["queue"] = list(QUEUE)
            elif path == "/api/jjc/refresh":
                self.path = "/api/jjc"
                return self.do_GET()
            elif path.startswith("/api/mumu/"):
                MUMU["running"] = path != "/api/mumu/shutdown"
            else:
                return self.reply({"error": "此操作未接入演示；请在真实机器人中使用。"}, 409)
            return self.reply({"ok": True, "demo": True})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8800)
    args = parser.parse_args()
    handler = lambda *a, **kw: PreviewHandler(*a, directory=str(ROOT), **kw)
    server = ThreadingHTTPServer(("127.0.0.1", args.port), handler)
    print(f"Demo WebUI: http://127.0.0.1:{args.port} (sample data; no emulator actions)", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
