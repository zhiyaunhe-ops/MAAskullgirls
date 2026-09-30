"""PF bot WebUI：stdlib http.server，运行日志/截图 + 图表页签（Chart.js 本地托管）。

GET  /                  页面（Prize Fighter Bot [运行/图表子页签] / 每日任务）
GET  /api/state         {status, step, fight_no, score, streak, logs, shot_ver, shot_time,
                         session_id, session_name, log_total}
GET  /api/sessions      {sessions: [{id,name,rule,count,last_ts}], active, running}
GET  /api/summary       {per_min, last_delta, score, target, eta_sec}  当前场次轻量统计
                        (小组件与 WebUI「目标进度」的预计完成时间共用; per_min 只计
                        相邻间隔≤180s 的活跃段。页面未设目标分时只显速率, 不沿用
                        这里 eta_sec 的 150M 兜底)
GET  /api/daily         {data:{queue,pool,names}, saved}  每日任务状态 (debug/pf/daily.json)
GET  /api/jjc           {snapshot:{day,entries,daily_events,...}, versions, session_names}
                        JJC 日程快照 + 场次×规则版本账本（peek, 不触网）
POST /api/jjc/refresh   强制从 sgmnow 抓一次新快照（唯一的 JJC 触网入口）
POST /api/daily         保存 {queue:[...], pool:{daily,guild}, names:{id:自定义名}}
GET  /api/history       ?sessions=a,b,c -> {series: [{id,name,rule,points}]}
GET  /static/...        静态文件（WebUI HTML / CSS / JS、Chart.js）
GET  /sgm/...           sgm 素材（元素/角色图标等）
GET  /shot.jpg          最新截图
POST /api/start         {session_id} 开始指定场次（运行中不可换）
POST /api/end           结束当前场次, 回 IDLE 待命 (进程与 WebUI 保留)
POST /api/stop          进程退出 (主循环退出, WebUI 一并关闭; 托盘/调度依赖)
POST /api/sessions/select  仅绑定当前场次不开始（运行中不可换）
POST /api/sessions/create|update|delete   场次管理（运行中禁改当前场次; Default 不可删）
GET  /api/mumu             {running, busy, close_on_goal, game_pkg}  模拟器状态
POST /api/mumu/launch      启动 MuMu（后台线程, 等 start_finished）
POST /api/mumu/game        启动 MuMu(如需) + adb monkey 拉起 Skullgirls
POST /api/mumu/shutdown    关闭 MuMu（bot 运行中会先结束场次; 只走 MuMuManager control）
POST /api/settings         {filter_favorite, close_mumu_on_goal}
                           close_mumu_on_goal: 总分达标时自动关闭 MuMu（默认开）

访问安全（所有请求先过 _gate 门卫，不合法一律 40x 并写入运行日志）:
  - 来源 IP 限 本机回环 / 内网 (10/172.16/192.168) / Tailscale (100.64/10)，公网来源 403
  - Host/Origin 头仅认 localhost、IP 字面量、局域网主机名（防 DNS rebinding / 跨站调用）
  - POST 仅接受 application/json；PUT/DELETE/HEAD/OPTIONS 等方法一律 405
  - 未知路径 404（favicon.ico 静默）
"""
import ipaddress
import json
import mimetypes
import socket
import threading
import time
import urllib.request
from contextlib import closing
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

from pf_env import (GAME_PKG, STATE, WEBUI_PORT, adb_connect, mumu_is_running,
                    mumu_launch_game, mumu_shutdown, mumu_start)
from pf_domain import UNSET, clean_rest, clean_target, clean_energy
from pf_store import STORE
from jjc_store import JJC, VERSIONS, ordered_entries, sgm_day

SVC_ID = "sgm-pf-bot"    # 本服务的身份标签, 见 _gate 说明与 /api/state

STATIC_DIR = Path(__file__).resolve().parent / "static"
SGM_DIR = Path(__file__).resolve().parent.parent / "sgm"
DAILY_PATH = Path(__file__).resolve().parent.parent / "debug" / "pf" / "daily.json"


def _load_daily() -> dict:
    try:
        with open(DAILY_PATH, encoding="utf-8") as f:
            data = json.load(f)
        return data if isinstance(data, dict) else {}
    except (OSError, json.JSONDecodeError):
        return {}


def _jjc_payload() -> str:
    """JJC 页签的读接口数据。

    peek() 不触网 —— 切换页签不该发起外部请求; 要最新的话走 /api/jjc/refresh。
    """
    snap = JJC.peek()
    if snap:
        snap = dict(snap)
        snap["entries"] = ordered_entries(snap.get("entries") or {})
        # 归档不是当天的 -> 标 stale, 让前端说清楚这是历史数据不是今日实况
        snap["stale"] = bool(snap.get("stale")) or snap.get("day") != sgm_day()
        for key in ("raw_rows", "revisions"):   # 证据留文件里, 不上接口
            snap.pop(key, None)
    names = {s["id"]: s["name"] for s in STORE.list_sessions()}
    return json.dumps({"snapshot": snap, "versions": VERSIONS.all(),
                       "session_names": names}, ensure_ascii=False)


def _save_daily(data: dict) -> None:
    DAILY_PATH.parent.mkdir(parents=True, exist_ok=True)
    tmp = DAILY_PATH.with_suffix(".json.tmp")
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False)
    tmp.replace(DAILY_PATH)


# 允许的来源网段: 本机回环 / 内网三段 / Tailscale (CGNAT, 注意是 /10) / 链路本地
ALLOWED_NETS = [ipaddress.ip_network(n) for n in (
    "127.0.0.0/8", "::1/128", "10.0.0.0/8", "172.16.0.0/12",
    "192.168.0.0/16", "100.64.0.0/10", "169.254.0.0/16",
)]


def _host_ok(host: str) -> bool:
    """Host/Origin 校验: 放行 localhost / IP 字面量 / 无点局域网主机名 / .local·.lan·.ts.net;
    公网域名拒绝 —— 把攻击者域名解析到 127.0.0.1 的 DNS rebinding 在这里被拦下。

    .ts.net 是 Tailscale MagicDNS 的固定后缀 (2026-09-17 加入):
    它只有 tailnet 成员能解析、也只有 tailnet 内可达, 威胁模型与 .local/.lan 同一档,
    不属于"公网域名"。没有它, 经 tailscale serve 暴露的端口会被这条白名单 403 挡掉
    —— 见 PF_BOT.md §6.13。
    """
    TAILNET_SUFFIXES = (".local", ".lan", ".ts.net")
    h = (host or "").strip().lower()
    if h.startswith("["):                 # [::1]:<port>
        end = h.find("]")
        h = h[1:end] if end != -1 else h[1:]
    elif ":" in h:                        # host:port
        h = h.rsplit(":", 1)[0]
    if not h or h == "localhost":
        return True
    try:
        ipaddress.ip_address(h)
        return True
    except ValueError:
        return "." not in h or h.endswith(TAILNET_SUFFIXES)

_HTML = (STATIC_DIR / "webui.html").read_text(encoding="utf-8")


# ---------- MuMu 启停 (WebUI 按钮后端) ----------
# 启动/关模拟器都是**秒级阻塞** (拉起要等 start_finished, 关机要等状态回落),
# 放在 HTTP handler 里会把整个 ThreadingHTTPServer 的该连接卡住, 所以一律丢后台线程,
# 进度只通过 STATE.log 回传到运行日志。幂等: 同一动作未完成时重复点击直接返回。
_MUMU_BUSY = {"op": None}
_MUMU_LOCK = threading.Lock()


def _mumu_run(op: str, fn) -> bool:
    with _MUMU_LOCK:
        if _MUMU_BUSY["op"]:
            STATE.log(f"MuMu 操作进行中 ({_MUMU_BUSY['op']}), 忽略本次 {op}", "warn")
            return False
        _MUMU_BUSY["op"] = op

    def _wrap():
        try:
            fn()
        except Exception as e:  # noqa: BLE001
            STATE.log(f"MuMu {op} 异常: {e}", "err")
        finally:
            with _MUMU_LOCK:
                _MUMU_BUSY["op"] = None
    threading.Thread(target=_wrap, daemon=True, name=f"mumu-{op}").start()
    return True


def _mumu_do_launch(with_game: bool = False) -> None:
    """拉起模拟器; with_game=True 则在就绪后顺手拉起 Skullgirls。"""
    if mumu_is_running():
        STATE.log("MuMu 已在运行", "info")
    else:
        STATE.log("正在启动 MuMu ...", "warn")
        if not mumu_start(timeout=120):
            STATE.log("MuMu 启动失败: MuMuManager/MuMuNxMain 不可用或超时", "err")
            return
        STATE.log("MuMu 已就绪", "warn")
    if with_game:
        _mumu_do_game()


def _mumu_do_game() -> None:
    if not mumu_is_running():
        STATE.log("MuMu 未运行, 先启动模拟器 ...", "warn")
        if not mumu_start(timeout=120):
            STATE.log("MuMu 启动失败, 放弃拉起游戏", "err")
            return
    # 2026-09-27 实测: mumu_start() 报就绪 ≠ adb 里已经有这台设备 —— MuMu 自己的 connect
    # 可能还没跑完, 或 adb server 刚被回收重建, 此时 monkey 直接回
    # "device '127.0.0.1:16384' not found" (当天 01:02 连点两次, 日志里只留下这两条 err)。
    # 所以拉起游戏前必须自己补一次 connect; 仍说没设备就等一拍再试一次 (幂等)。
    device_missing = ("not found", "offline", "no devices", "unauthorized")
    for attempt in (1, 2):
        _mumu_do_connect()
        ok, out = mumu_launch_game()
        if ok:
            STATE.log(f"已发出 Skullgirls 启动指令 ({GAME_PKG})", "warn")
            return
        if attempt == 1 and any(s in out.lower() for s in device_missing):
            STATE.log(f"adb 设备表里还没有模拟器, 补连后重试一次: {out}", "warn")
            time.sleep(3)
            continue
        STATE.log(f"启动 Skullgirls 失败: {out or 'monkey 未返回 Events injected: 1'}", "err")
        return


def _mumu_do_connect() -> None:
    """补一次 adb connect。幂等 —— 已连上时 adb 回 'already connected'。"""
    if adb_connect():
        return
    STATE.log("adb connect 未成功 (模拟器可能刚重启, 稍后重试)", "warn")


def _mumu_do_launch_and_connect() -> None:
    """「开始」时模拟器没开: 拉起它并补 adb connect, 之后 run() 循环会自己跑起来。"""
    if not mumu_start(timeout=120):
        STATE.log("MuMu 启动失败, bot 会在连接时报错 (可在 WebUI 重试)", "err")
        return
    STATE.log("MuMu 已就绪, 补一次 adb connect", "warn")
    _mumu_do_connect()


def _mumu_do_shutdown(by_user_click: bool = True) -> None:
    if not mumu_is_running():
        STATE.log("MuMu 未在运行, 无需关闭", "info")
        return
    if STATE.running:
        STATE.running = False    # 先停 bot, 否则 adb 断开会刷一片异常
        STATE.status = "IDLE"
        STATE.log("关闭 MuMu 前先结束场次", "warn")
    STATE.log("正在关闭 MuMu ...", "warn")
    if mumu_shutdown():
        STATE.log("MuMu 已关闭", "warn")
        if by_user_click:
            # 关掉模拟器 = 这个 WebUI 之后什么都干不了, 必须说清"怎么回来"。
            # 2026-09-22 实测教训: 早上误点「关机」后连点三次「开始」全无反应,
            # 用户以为按钮坏了 —— 其实只是模拟器没了。
            STATE.log("提示: 之后点「开始」会先自动拉起模拟器; 也可点「启动 MuMu」", "warn")
    else:
        STATE.log("关闭 MuMu 失败: MuMuManager 不可用或未响应", "err")


def _apply_session(sess: dict) -> None:
    """把场次配置同步进运行状态 (开始/仅选择/修改当前场次后调用)。"""
    STATE.pf_rule = dict(sess["rule"]) if sess.get("rule") else None
    STATE.score_target = clean_target(sess.get("score_target"))
    STATE.rest_every = clean_rest(sess.get("rest_every"))
    STATE.rest_minutes = clean_rest(sess.get("rest_minutes"))
    STATE.energy_cost = clean_energy(sess.get("energy_cost"))


class _Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):  # 静默访问日志
        pass

    # ---- 访问门卫: 不合法请求一律 40x 并记入运行日志 ----
    def _reject(self, code: int, msg: str) -> None:
        self.close_connection = True
        STATE.log(f"已拒绝 {self.command} {self.path} <- {self.client_address[0]} ({msg})", "warn")
        self._send(code, "text/plain; charset=utf-8", msg.encode("utf-8"))

    def _gate(self, is_post: bool) -> bool:
        try:
            ip = ipaddress.ip_address(self.client_address[0])
        except ValueError:
            ip = None
        if ip is None or not any(ip in net for net in ALLOWED_NETS):
            self._reject(403, "来源网段不允许")
            return False
        if not _host_ok(self.headers.get("Host", "")):
            self._reject(403, "Host 头不允许")
            return False
        if is_post:
            origin = self.headers.get("Origin")
            if origin is not None:
                ohost = ""
                try:
                    ohost = urlsplit(origin).hostname or ""
                except ValueError:
                    pass
                if not ohost or not _host_ok(ohost):
                    self._reject(403, "跨站 Origin 不允许")
                    return False
            ctype = (self.headers.get("Content-Type") or "").split(";")[0].strip().lower()
            if ctype != "application/json":
                self._reject(415, "POST 仅接受 application/json")
                return False
        return True

    # 未定义的写方法一律拒绝; OPTIONS 非 2xx 也让浏览器自行拦下跨站预检
    def _method_na(self):
        self._reject(405, "方法不允许")
    do_PUT = do_PATCH = do_DELETE = do_HEAD = do_OPTIONS = _method_na

    def _send(self, code, ctype, body):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        if ctype.startswith("image/"):
            self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if not self._gate(is_post=False):
            return
        parts = urlsplit(self.path)
        path, qs = parts.path, parse_qs(parts.query)
        if path == "/":
            self._send(200, "text/html; charset=utf-8", _HTML.encode("utf-8"))
        elif path == "/api/state":
            shot_time = ""
            with STATE._lock:
                shot_time = getattr(STATE, "shot_time", "")
            sess = STORE.get(STORE.session_id or "")
            body = json.dumps(
                {
                    # 身份标签: 本机 87xx 段挤了一堆别的服务, 光看"端口有响应"
                    # 会把别的进程认成 bot。调用方必须校验这个字段才算数。
                    "svc": SVC_ID,
                    "status": STATE.status,
                    "step": STATE.step,
                    "fight_no": STATE.fight_no,
                    "score": STATE.score,
                    "streak": STATE.streak,
                    "score_target": STATE.score_target,
                    "energy_cost": STATE.energy_cost,
                    "pf_rule": STATE.pf_rule,
                    "sess_rest_every": (STORE.get(STORE.session_id or "") or {}).get("rest_every") or 0,
                    "sess_rest_minutes": (STORE.get(STORE.session_id or "") or {}).get("rest_minutes") or 0,
                    "filter_favorite": STATE.filter_favorite,
                    "close_on_goal": bool(STATE.close_mumu_on_goal),
                    "rest_every": STATE.rest_every,
                    "rest_minutes": STATE.rest_minutes,
                    "rest_until": STATE.rest_until,
                    "session_id": STORE.session_id,
                    "session_name": sess["name"] if sess else None,
                    "log_total": STATE.log_total(),
                    "logs": STATE.dump_logs()[-300:],
                    "shot_ver": STATE.shot_ver,
                    "shot_time": shot_time,
                },
                ensure_ascii=False,
            ).encode("utf-8")
            self._send(200, "application/json", body)
        elif path == "/api/summary":
            # 轻量统计 (小组件用): 每分钟收益 / 上一场收益 / 总分与到目标分的预计秒数
            sid = STORE.session_id or "default"
            pts = [p for p in STORE.history_by.get(sid, []) if p.get("score") is not None]
            last_delta = (pts[-1]["score"] - pts[-2]["score"]) if len(pts) >= 2 else None
            active_sec, gain = 0, 0
            for i in range(1, len(pts)):
                dt = pts[i]["ts"] - pts[i - 1]["ts"]
                if dt < 0 or dt > 180:      # 与 WebUI 图表口径一致: 只算活跃时段
                    continue
                active_sec += dt
                gain += pts[i]["score"] - pts[i - 1]["score"]
            per_min = (gain / (active_sec / 60)) if active_sec > 30 else None
            score_now = pts[-1]["score"] if pts else None
            target = STATE.score_target or 150_000_000
            eta_sec = None
            if per_min and per_min > 0 and score_now is not None and score_now < target:
                eta_sec = round((target - score_now) / per_min * 60)
            body = json.dumps({"per_min": per_min, "last_delta": last_delta,
                               "score": score_now, "target": target, "eta_sec": eta_sec},
                              ensure_ascii=False).encode("utf-8")
            self._send(200, "application/json", body)
        elif path == "/api/mumu":
            # 模拟器状态。前端每 4s 轮询, 所以这里必须**绝不抛异常** —— 一次未捕获的
            # 异常会把整屏 traceback 打给用户 (2026-09-22 实测: MuMuManager 调用超时
            # 返回 stdout=None, json.loads 抛 TypeError)。mumu_is_running 已在
            # pf_env 里兜底, 这里再包一层防"未来新增字段"重蹈覆辙。
            try:
                running = mumu_is_running()
            except Exception:  # noqa: BLE001
                running = False
            body = json.dumps({
                "running": running,
                "busy": _MUMU_BUSY["op"],
                "close_on_goal": bool(STATE.close_mumu_on_goal),
                "game_pkg": GAME_PKG,
            }, ensure_ascii=False).encode("utf-8")
            self._send(200, "application/json", body)
        elif path == "/api/sessions":
            body = json.dumps(
                {"sessions": STORE.list_sessions(), "active": STORE.session_id,
                 "running": bool(STATE.running)},
                ensure_ascii=False).encode("utf-8")
            self._send(200, "application/json", body)
        elif path == "/api/history":
            ids = [s for s in (qs.get("sessions") or [""])[0].split(",") if s]
            if not ids:
                ids = [STORE.session_id or "default"]
            body = json.dumps({"series": STORE.series(ids), "active": STORE.session_id},
                              ensure_ascii=False).encode("utf-8")
            self._send(200, "application/json", body)
        elif path == "/api/daily":
            body = json.dumps({"data": _load_daily(), "saved": DAILY_PATH.is_file()},
                              ensure_ascii=False).encode("utf-8")
            self._send(200, "application/json", body)
        elif path == "/api/jjc":
            self._send(200, "application/json", _jjc_payload().encode("utf-8"))
        elif path.startswith("/static/"):
            try:
                fp = (STATIC_DIR / path[len("/static/"):]).resolve()
                fp.relative_to(STATIC_DIR.resolve())
            except (ValueError, OSError):
                fp = None
            if fp and fp.is_file():
                ctype = mimetypes.guess_type(str(fp))[0] or "application/octet-stream"
                self._send(200, ctype, fp.read_bytes())
            else:
                self._send(404, "text/plain", b"not found")
        elif path.startswith("/sgm/"):
            rel = Path(path[len("/sgm/"):])
            try:
                fp = (SGM_DIR / rel).resolve()
                fp.relative_to(SGM_DIR.resolve())
            except (ValueError, OSError):
                fp = None
            if fp and fp.is_file():
                ctype = mimetypes.guess_type(str(fp))[0] or "application/octet-stream"
                self._send(200, ctype, fp.read_bytes())
            else:
                self._send(404, "text/plain", b"not found")
        elif path.startswith("/shot.jpg"):
            data = b""
            p = STATE.shot_path
            if p:
                try:
                    with open(p, "rb") as f:
                        data = f.read()
                except OSError:
                    pass
            self._send(200, "image/jpeg", data)
        else:
            if path != "/favicon.ico":    # 浏览器自动请求, 不刷日志
                STATE.log(f"未知请求 {self.command} {path} <- {self.client_address[0]}", "warn")
            self._send(404, "text/plain", b"not found")

    def _read_json(self) -> dict:
        length = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(length)
        try:
            text = raw.decode("utf-8", "replace")
        except Exception:  # noqa: BLE001
            text = "{}"
        return json.loads(text or "{}")

    def _json_err(self, code: int, msg: str) -> None:
        self._send(code, "application/json",
                   json.dumps({"error": msg}, ensure_ascii=False).encode("utf-8"))

    def do_POST(self):
        if not self._gate(is_post=True):
            return
        if self.path == "/api/end":
            # 结束场次: 回 IDLE 待命, 进程与 WebUI 保留。进程退出是 /api/stop
            # (pf_schedule/托盘按"等进程退出"依赖它), 语义不动。
            STATE.running = False
            STATE.log("收到 WebUI 结束请求, 本场次结束, 回到待命", "warn")
            self._send(200, "application/json", b'{"ok":true}')
        elif self.path == "/api/stop":
            STATE.running = False
            STATE.quit = True
            STATE.log("收到 WebUI 停止请求, 主循环即将退出", "warn")
            self._send(200, "application/json", b'{"ok":true}')
        elif self.path == "/api/mumu/launch":
            _mumu_run("launch", lambda: _mumu_do_launch(False))
            self._send(200, "application/json", b'{"ok":true}')
        elif self.path == "/api/mumu/game":
            # 一键直达: 模拟器没开就先开, 就绪后拉起 Skullgirls
            _mumu_run("game", lambda: _mumu_do_launch(True))
            self._send(200, "application/json", b'{"ok":true}')
        elif self.path == "/api/mumu/shutdown":
            _mumu_run("shutdown", _mumu_do_shutdown)
            self._send(200, "application/json", b'{"ok":true}')
        elif self.path == "/api/start":
            try:
                data = self._read_json()
                sess = STORE.set_session(str(data.get("session_id") or ""))
            except (ValueError, KeyError, json.JSONDecodeError):
                self._json_err(400, "需要有效的 session_id")
                return
            _apply_session(sess)
            # 「开始」必须自足: 模拟器没开就先开, adb 断了就补连。
            # 2026-09-22 教训 —— 原先只打一条日志提醒"请先点启动 MuMu", 用户点「开始」
            # 什么都不会发生 (提示只躺在日志里), 连点三次都"没反应"。
            # 拉起模拟器是秒级阻塞, 放后台线程, 立即返回 starting_mumu 让前端给进度。
            starting = False
            try:
                if not mumu_is_running():
                    starting = True
                    STATE.log("MuMu 未运行, 先拉起模拟器 (就绪后自动开跑)", "warn")
                    _mumu_run("start-bot", _mumu_do_launch_and_connect)
                else:
                    _mumu_run("adb-connect", _mumu_do_connect)
            except Exception as e:  # noqa: BLE001
                STATE.log(f"启动前环境检查异常: {e}", "err")
            STATE.running = True
            STATE.log(f"收到 WebUI 开始请求: 场次「{sess['name']}」", "warn")
            self._send(200, "application/json",
                       json.dumps({"ok": True, "starting_mumu": starting},
                                  ensure_ascii=False).encode("utf-8"))
        elif self.path == "/api/sessions/select":
            # 仅绑定当前场次不开始; 之后可在主页改规则/上界/休息
            try:
                data = self._read_json()
                sid = str(data.get("id") or "")
            except json.JSONDecodeError as e:
                self._json_err(400, str(e))
                return
            sess = STORE.get(sid)
            if not sess:
                self._json_err(404, "场次不存在")
                return
            if STATE.running:
                self._json_err(409, "运行中不允许切换场次")
                return
            STORE.set_session(sid)
            _apply_session(sess)
            STATE.log(f"已选择场次「{sess['name']}」(未开始)", "warn")
            self._send(200, "application/json", b'{"ok":true}')
        elif self.path == "/api/sessions/create":
            try:
                data = self._read_json()
            except json.JSONDecodeError as e:
                self._json_err(400, str(e))
                return
            name = str(data.get("name") or "").strip()
            if not name:
                self._json_err(400, "场次名称不能为空")
                return
            sess = STORE.create(name, data.get("rule"),
                                data.get("rest_every") or 0,
                                data.get("rest_minutes") or 0,
                                data.get("score_target"),
                                data.get("energy_cost"))
            STATE.log(f"新建场次「{name}」")
            self._send(200, "application/json",
                       json.dumps({"ok": True, "id": sess["id"]}).encode())
        elif self.path == "/api/sessions/update":
            try:
                data = self._read_json()
            except json.JSONDecodeError as e:
                self._json_err(400, str(e))
                return
            sid = str(data.get("id") or "")
            sess = STORE.get(sid)
            if not sess:
                self._json_err(404, "场次不存在")
                return
            if sid == STORE.session_id and STATE.running:
                self._json_err(409, "运行中不允许修改当前场次")
                return
            name = str(data.get("name") or "").strip() or None
            rule = data["rule"] if "rule" in data else UNSET
            rest_e = data["rest_every"] if "rest_every" in data else UNSET
            rest_m = data["rest_minutes"] if "rest_minutes" in data else UNSET
            tgt = data["score_target"] if "score_target" in data else UNSET
            ec = data["energy_cost"] if "energy_cost" in data else UNSET
            try:
                STORE.update(sid, name=name, rule=rule,
                             rest_every=rest_e, rest_minutes=rest_m,
                             score_target=tgt, energy_cost=ec)
            except KeyError:
                self._json_err(404, "场次不存在")
                return
            if sid == STORE.session_id:
                _apply_session(sess)
            STATE.log(f"场次「{sess['name']}」已更新")
            self._send(200, "application/json", b'{"ok":true}')
        elif self.path == "/api/sessions/child":
            # 周期性分类的每一期: 建带日期的子场次, 继承父场次规则/上界/休息
            try:
                data = self._read_json()
                pid = str(data.get("id") or "")
            except json.JSONDecodeError as e:
                self._json_err(400, str(e))
                return
            try:
                sess = STORE.create_child(pid, data.get("name"))
            except KeyError:
                self._json_err(404, "父场次不存在")
                return
            STATE.log(f"新建子场次「{sess['name']}」(父: {pid})")
            self._send(200, "application/json",
                       json.dumps({"ok": True, "id": sess["id"]}).encode())
        elif self.path == "/api/sessions/delete":
            try:
                data = self._read_json()
            except json.JSONDecodeError as e:
                self._json_err(400, str(e))
                return
            sid = str(data.get("id") or "")
            sess = STORE.get(sid)
            if not sess:
                self._json_err(404, "场次不存在")
                return
            if sid == "default":
                self._json_err(400, "Default 场次收纳历史数据, 不可删除")
                return
            if sid == STORE.session_id and STATE.running:
                self._json_err(409, "运行中不允许删除当前场次")
                return
            STORE.delete(sid)
            if STORE.session_id is None:
                STATE.pf_rule = None
            STATE.log(f"场次「{sess['name']}」已删除", "warn")
            self._send(200, "application/json", b'{"ok":true}')
        elif self.path == "/api/settings":
            try:
                data = self._read_json()
                if "filter_favorite" in data:
                    STATE.filter_favorite = bool(data["filter_favorite"])
                if "close_mumu_on_goal" in data:
                    STATE.close_mumu_on_goal = bool(data["close_mumu_on_goal"])
                # 能量门槛/目标总分/休息已随场次, 由 /api/sessions/update 维护
                rule_desc = f"{STATE.pf_rule['type']}={STATE.pf_rule['value']}" if STATE.pf_rule else "无"
                rest_desc = (f"每 {STATE.rest_every} 场休 {STATE.rest_minutes} 分钟"
                             if STATE.rest_every > 0 and STATE.rest_minutes > 0 else "不启用")
                STATE.log(f"设置已更新: 能量门槛={STATE.energy_cost}, 喜爱筛选={STATE.filter_favorite}, "
                          f"规则={rule_desc}, 休息={rest_desc}", "warn")
                self._send(200, "application/json", b'{"ok":true}')
            except (ValueError, json.JSONDecodeError) as e:
                self._json_err(400, str(e))
        elif self.path == "/api/jjc/refresh":
            # 唯一允许触网的 JJC 入口 —— 用户显式点「刷新快照」才走
            try:
                self._read_json()
            except (json.JSONDecodeError, Exception):  # noqa: BLE001
                pass
            res = JJC.refresh(log=lambda m, level="info": STATE.log(m, level))
            if res.get("action") == "failed":
                STATE.log(f"JJC 刷新失败: {res.get('error')}", "warn")
            self._send(200, "application/json", _jjc_payload().encode("utf-8"))
        elif self.path == "/api/daily":
            try:
                data = self._read_json()
            except json.JSONDecodeError as e:
                self._json_err(400, str(e))
                return
            if not isinstance(data, dict):
                self._json_err(400, "需要 JSON 对象")
                return
            cur = _load_daily()
            clean = {}
            queue = data.get("queue", cur.get("queue"))
            if isinstance(queue, list) and all(isinstance(x, str) for x in queue):
                clean["queue"] = queue[:64]
            pool = data.get("pool", cur.get("pool"))
            if isinstance(pool, dict):
                clean["pool"] = {k: [x for x in v if isinstance(x, str)][:64]
                                 for k, v in pool.items()
                                 if isinstance(k, str) and isinstance(v, list)}
            names = data.get("names", cur.get("names"))
            if isinstance(names, dict):
                clean["names"] = {k: v for k, v in names.items()
                                  if isinstance(k, str) and isinstance(v, str)}
            _save_daily(clean)
            self._send(200, "application/json", b'{"ok":true}')
        else:
            self.close_connection = True   # 请求体未读, 断开连接保持协议干净
            STATE.log(f"未知接口 {self.command} {self.path} <- {self.client_address[0]}", "warn")
            self._send(404, "text/plain", b"not found")


class _PFHTTPServer(ThreadingHTTPServer):
    """关掉地址复用。

    基类默认 allow_reuse_address=True, 在 Linux 上只影响 TIME_WAIT, 但**在 Windows 上
    等于允许第二个进程绑定同一个端口** —— 实测第二个 pf_bot 能悄悄起来, 两个实例同时
    操作模拟器 (§8-12 的"残留进程双实例"坑)。关掉它才是真锁。
    """
    allow_reuse_address = False
    daemon_threads = True


_PORT_WAIT_S = 10.0   # 启动时等老进程让出端口的秒数 (重生接管 / 「停止」后正在退出的窗口)


def _port_owner(port: int) -> str:
    """探测端口占用者: 'self'=另一个 pf_bot / 'other'=别的服务 / ''=空闲。"""
    import socket

    with closing(socket.socket()) as probe:
        probe.settimeout(1.0)
        try:
            probe.connect(("127.0.0.1", port))
        except OSError:
            return ""
    try:                       # 有人占 -> 问它是不是本服务
        with urllib.request.urlopen(
                f"http://127.0.0.1:{port}/api/state", timeout=2) as r:
            body = json.loads(r.read().decode("utf-8") or "{}")
        return "self" if isinstance(body, dict) and body.get("svc") == SVC_ID else "other"
    except Exception:  # noqa: BLE001
        return "other"


def start_webui(port: int = None) -> ThreadingHTTPServer:
    """启动 WebUI。端口默认取 pf_env.WEBUI_PORT (本机由 config.json 覆盖)。

    端口被占就**直接抛错**, 不悄悄换一个, 也**不允许第二个实例**: 换端口会让写死 URL
    的调用方全部失联; 双实例会两个人同时点同一个模拟器 (见 §8-12)。宁可启动失败让人
    看见, 也不装作成功。

    唯一让步: 老进程退出时端口会多占一两秒 (重生接管 / 点「停止」), 所以先等
    `_PORT_WAIT_S` 秒; 一直占着才拒绝。绑是独占的 (allow_reuse_address=False), 即便
    双方同时等到"空闲"也只有一个能绑上, 另一个在 OSError 上被拦 —— 不会出现双实例。
    """
    port = int(port or WEBUI_PORT)
    deadline = time.time() + _PORT_WAIT_S
    while True:
        owner = _port_owner(port)
        if owner != "self":
            break
        if time.time() >= deadline:
            raise RuntimeError(
                f"端口 {port} 上已经有一个 pf_bot 在跑, 拒绝启动第二个实例 —— "
                f"两个实例会同时向模拟器注入点击 (见 PF_BOT.md §8-12)。"
                f"先 Taskkill 掉旧进程, 或去 http://127.0.0.1:{port}/ 点「停止」。"
                f"若那是僵死进程 (点「停止」/重生后进程不退、日志不再更新, 端口还被占着), "
                f"用 netstat -ano | findstr {port} 找到 pid 后 taskkill /PID <pid> /F。")
        time.sleep(0.5)
    if owner == "other":
        raise RuntimeError(
            f"端口 {port} 被别的服务占用 (不是 pf_bot)。 "
            f"本机 87xx 段很挤, 在 config.json 里加 \"webui_port\" 换一个空闲端口。")
    try:
        server = _PFHTTPServer(("0.0.0.0", port), _Handler)
    except OSError as e:
        raise RuntimeError(f"绑定端口 {port} 失败: {e}") from e
    t = threading.Thread(target=server.serve_forever, daemon=True)
    t.start()
    STATE.log(f"WebUI 已锁定端口 {port}")
    return server
