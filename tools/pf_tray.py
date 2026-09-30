"""PF Bot 托盘遥控器 (Windows) — 免 cmd 黑窗的常驻入口。

定位: **纯遥控器**。托盘自己**不跑 bot 逻辑**, 只做两件事:
  ① 轮询 `/api/state` 把 bot 状态画到图标/tooltip 上;
  ② 需要操作时, 优先走既有的 HTTP 接口 (等价于替你点 WebUI 按钮)。
唯一例外是进程生命周期 —— `/api/stop` 是让 bot **自己退出**
(`STATE.quit = True`), 所以「启动/重启/结束服务」必须由托盘直接管进程。

用法:
    pythonw tools/pf_tray.py        # 推荐: 无黑窗常驻
    python tools/pf_tray.py         # 调试: 有控制台, 可看 traceback

依赖: pystray + Pillow (anaconda 已带)。

⚠️ 编码: 本文件所有子进程调用**显式钉死 UTF-8** (见 `_spawn_bot` 的 env)。
2026-09-24 事故: 本机 locale 是 cp936(GBK), 而 bot 的状态查询要解码含中文设备名
("MuMu安卓设备") 的 JSON ⇒ `UnicodeDecodeError: 'gbk' codec can't decode byte 0xa4
in position 448`。该异常发生在 subprocess 内部读线程里, 调用方 try/except
**捕不住**, 只能从源头指定编码。这里从进程创建那一刻就把 LANG 钉成 UTF-8,
让 bot 侧即便漏了某处 encoding= 也不会踩同一个坑。
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
import threading
import time
import traceback
import urllib.error
import urllib.request
import webbrowser

BASE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(BASE)
sys.path.insert(0, BASE)

from pystray import Icon, Menu, MenuItem  # noqa: E402
from PIL import Image, ImageDraw  # noqa: E402

WEBUI_PORT = 8790
API = "http://127.0.0.1:%d" % WEBUI_PORT
BOT_LOG = os.path.join(ROOT, "debug", "pf", "bot_stdout.log")
TRAY_LOG = os.path.join(ROOT, "debug", "pf", "tray.log")

# 与 pf_schedule 用同一个解释器口径 (anaconda 才有 cv2/maa)
PY = r"C:\Users\zhiya\anaconda3\python.exe"
if not os.path.exists(PY):
    PY = sys.executable

_bot_proc: subprocess.Popen | None = None
_proc_lock = threading.Lock()
_last_state: dict = {}
_busy = ""          # 正在执行的操作名, 非空时图标右上角加"忙"标记


# ---------------- 日志 ----------------

def _log(msg: str) -> None:
    try:
        os.makedirs(os.path.dirname(TRAY_LOG), exist_ok=True)
        with open(TRAY_LOG, "a", encoding="utf-8") as f:
            f.write("[%s] %s\n" % (time.strftime("%Y-%m-%d %H:%M:%S"), msg))
    except OSError:
        pass


def _excepthook(t, v, tb) -> None:
    """托盘不能像 bat 那样把 traceback 打在没人看的黑窗里。"""
    try:
        os.makedirs(os.path.dirname(TRAY_LOG), exist_ok=True)
        with open(TRAY_LOG, "a", encoding="utf-8") as f:
            f.write("".join(traceback.format_exception(t, v, tb)) + "\n")
    except OSError:
        pass


sys.excepthook = _excepthook


# ---------------- HTTP (一律超时, 绝不阻塞 UI 线程) ----------------

def _http(path: str, data: dict | None = None, timeout: float = 5.0) -> dict | None:
    url = API + path
    try:
        if data is None:
            req = urllib.request.Request(url)
        else:
            body = json.dumps(data, ensure_ascii=False).encode("utf-8")
            req = urllib.request.Request(url, data=body, method="POST")
            req.add_header("Content-Type", "application/json")
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.loads(r.read().decode("utf-8", "replace"))
    except (urllib.error.URLError, OSError, ValueError, json.JSONDecodeError):
        return None


def _bot_alive() -> bool:
    """8790 有响应且身份是本项目 (8787/8788/8791 是别的服务, 不能误判)。"""
    st = _http("/api/state", timeout=2.0)
    return bool(st and st.get("svc") == "sgm-pf-bot")


# ---------------- 进程生命周期 ----------------

def _spawn_bot() -> None:
    """后台起 pf_bot.py。日志追加到 bot_stdout.log (与 pf_schedule 行为一致)。

    ⚠️ 这段的 env 是本文件存在的核心理由之一: 显式 UTF-8, 不继承 cp936。
    """
    global _bot_proc
    with _proc_lock:
        if _bot_alive():
            _log("pf_bot 已在运行, 复用")
            return
        os.makedirs(os.path.dirname(BOT_LOG), exist_ok=True)
        env = dict(os.environ)
        env["PYTHONUTF8"] = "1"            # Python UTF-8 mode
        env["PYTHONIOENCODING"] = "utf-8"  # stdout/stderr 强制 UTF-8
        env["PYTHONUNBUFFERED"] = "1"      # 日志实时落盘, 不卡在缓冲区
        flags = subprocess.CREATE_NO_WINDOW | subprocess.CREATE_NEW_PROCESS_GROUP
        pid = None
        try:
            # 脱离托盘进程的作业对象: 托盘退出/被关时 bot 不陪葬
            flags |= subprocess.CREATE_BREAKAWAY_FROM_JOB
            lf = open(BOT_LOG, "ab")
            _bot_proc = subprocess.Popen([PY, "tools/pf_bot.py"], cwd=ROOT,
                                         env=env, stdout=lf, stderr=subprocess.STDOUT,
                                         close_fds=True, creationflags=flags)
            pid = _bot_proc.pid
        except OSError as e:
            # breakaway 被宿主作业对象拒绝时, 普通 Popen 会让 bot 留在宿主作业对象里,
            # 宿主收尾清树时被整树带走 (2026-09-26 workbuddy 事故同款死法)。改经 WMI
            # 由 WmiPrvSE 代生, 彻底独立 —— 手上没句柄, 强杀走端口反查 (本就不依赖它)。
            _log("breakaway 不被允许 (%s), 改用 WMI 独立进程拉起" % e)
            from pf_env import spawn_detached
            pid = spawn_detached(
                '"%s" tools/pf_bot.py' % PY, ROOT, BOT_LOG,
                env_lines=('set "PYTHONUTF8=1"', 'set "PYTHONIOENCODING=utf-8"',
                           'set "PYTHONUNBUFFERED=1"'))
            _bot_proc = None
        _log("已启动 pf_bot (pid=%s)" % pid)
        for _ in range(60):
            if _bot_alive():
                _log("pf_bot 就绪")
                return
            time.sleep(1)
        _log("pf_bot 60s 未就绪 (看 %s)" % BOT_LOG)


def _kill_bot() -> bool:
    """结束 bot 进程。先走优雅的 /api/stop, 不行再按 pid 强杀。"""
    global _bot_proc
    st = _http("/api/state", timeout=3.0)
    if st:
        _http("/api/stop", {})            # 让主循环自己收尾 (会存分/写 CSV)
        for _ in range(10):
            if not _bot_alive():
                _log("bot 已按 /api/stop 优雅退出")
                return True
            time.sleep(1)
        _log("优雅退出超时, 转强杀")
    # 强杀: 按端口反查 pid, 不依赖 _bot_proc (托盘可能是后起的, 手上没句柄)
    pids = _pids_on_port(WEBUI_PORT)
    if not pids:
        _log("端口 %d 上无监听进程, 视为已停" % WEBUI_PORT)
        return True
    for pid in pids:
        try:
            subprocess.run(["taskkill", "/F", "/PID", str(pid)],
                           capture_output=True, timeout=10,
                           creationflags=subprocess.CREATE_NO_WINDOW)
            _log("已强杀 pid=%d" % pid)
        except (OSError, subprocess.SubprocessError) as e:
            _log("强杀 pid=%d 失败: %s" % (pid, e))
    time.sleep(1.5)
    return not _bot_alive()


def _pids_on_port(port: int) -> list[int]:
    """谁在监听 port。用 psutil (wmic 被安全策略拉黑, 不可用)。"""
    try:
        import psutil
    except ImportError:
        return []
    out = []
    try:
        for c in psutil.net_connections(kind="tcp"):
            if c.laddr and c.laddr.port == port and c.status == psutil.CONN_LISTEN:
                if c.pid:
                    out.append(c.pid)
    except (psutil.AccessDenied, OSError):
        pass
    return out


# ---------------- 菜单动作 (都在后台线程跑, 不卡托盘) ----------------

def _bg(fn, name: str):
    """把动作包成 pystray 回调: 后台线程执行 + 置忙碌标记 + 兜底落盘。

    ⚠️ 内层必须收 `(icon, item)`: pystray 对所有回调统一传这两个参数。
    """
    def run(icon=None, item=None):
        global _busy
        _busy = name
        _refresh_icon()
        try:
            fn(icon, item)          # 透传: act_quit 等需要真 icon (2026-09-27 修, 原先 icon=None 必炸)
        except Exception:  # noqa: BLE001
            _excepthook(*sys.exc_info())
        finally:
            _busy = ""
            _refresh_icon()
    return run


def _notify(msg: str) -> None:
    """Windows 气泡通知 (pystray → Shell_NotifyIcon; Win10/11 显示为 toast)。

    只能锦上添花, 不许反过来带崩托盘: 图标没起来/后端不支持时静默放弃。
    """
    try:
        if _icon is not None:
            _icon.notify(msg, "SGM PF")
    except Exception:  # noqa: BLE001
        pass


def act_start(icon=None, item=None) -> None:
    """启动服务 = **只起进程不开跑** (用户 2026-09-30 口径: 启动≠开跑)。

    起进程 + 等就绪 + 把初始化失败的真实原因翻出来 (HTTP 应答 ≠ 初始化成功:
    MAA 连不上模拟器时 bot 会在 setup 阶段退出, 2026-09-27 实测"就绪→无可用
    场次"误导)。开跑走 act_run 或 WebUI「开始」。
    """
    if not _bot_alive():
        _spawn_bot()
    if not _bot_alive():
        _log("启动失败: bot 未就绪")
        _notify("服务启动失败: bot 未就绪, 看 debug\\pf\\tray.log")
        return
    cur = _http("/api/state", timeout=3.0) or {}
    if cur.get("status") == "ERROR":
        _log("bot 初始化失败: %s" % (cur.get("step") or "未知原因, 看 bot_stdout.log"))
        _notify("bot 初始化失败: %s" % (cur.get("step") or "未知原因"))
        return
    sid = cur.get("session_id") or ""
    if not sid:
        s = _http("/api/sessions", timeout=3.0) or {}
        sid = (s.get("active") or "")
    _log("服务已启动, 未开跑 (当前场次: %s); 开跑用「开跑当前场次」或 WebUI「开始」"
         % (sid or "未选"))
    _notify("服务已启动（未开跑）· 当前场次: %s" % (sid or "未选"))


def act_run(icon=None, item=None) -> None:
    """开跑当前选中场次 (服务没起就先起; 已在跑则只报告)。"""
    if not _bot_alive():
        _spawn_bot()
        if not _bot_alive():
            _log("启动失败: bot 未就绪")
            return
    cur = _http("/api/state", timeout=3.0) or {}
    if cur.get("status") == "ERROR":
        _log("bot 初始化失败: %s" % (cur.get("step") or "未知原因, 看 bot_stdout.log"))
        return
    if cur.get("status") == "RUNNING":
        _log("已在跑 (fight=%s), 不重复开跑" % cur.get("fight_no"))
        return
    sid = cur.get("session_id") or ""
    if not sid:
        s = _http("/api/sessions", timeout=3.0) or {}
        sid = (s.get("active") or "")
    if sid:
        _http("/api/start", {"session_id": sid}, timeout=20)
        _log("已 /api/start (session=%s)" % sid)
    else:
        _log("无可用场次, 去 WebUI 选")


def act_stop(icon=None, item=None) -> None:
    _kill_bot()


def act_restart(icon=None, item=None) -> None:
    _kill_bot()
    time.sleep(0.5)
    _spawn_bot()


def act_open(icon=None, item=None) -> None:
    webbrowser.open(API)


def act_mumu_launch(icon=None, item=None) -> None:
    _http("/api/mumu/launch", {}, timeout=10)


def act_mumu_game(icon=None, item=None) -> None:
    _http("/api/mumu/game", {}, timeout=10)


def act_mumu_shutdown(icon=None, item=None) -> None:
    _http("/api/mumu/shutdown", {}, timeout=10)


def act_quit(icon=None, item=None) -> None:
    """退出托盘。bot 按设计**继续在跑** (纯遥控器, 退出≠停服)。"""
    _log("托盘退出 (bot 不受影响)")
    icon.stop()


# ---------------- 图标 ----------------

def _make_icon(color=(63, 127, 224, 255), busy=False) -> Image.Image:
    """状态色圆角方块 + 中间一个「场」字形的简笔标记。

    颜色即状态: 蓝=运行中, 灰=空闲, 橙=忙碌, 红=错误, 深灰=服务未启动。
    """
    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle((2, 2, 62, 62), radius=14, fill=color)
    # 中性标记 (避免依赖字体渲染中文)
    d.line([(20, 22), (44, 22)], fill="white", width=6)     # 顶横
    d.line([(32, 22), (32, 44)], fill="white", width=6)     # 中竖
    d.line([(22, 44), (42, 44)], fill="white", width=6)     # 底横
    if busy:
        d.ellipse((46, 2, 62, 18), fill=(255, 176, 32, 255), outline="white", width=2)
    return img


def _status_color(st: dict) -> tuple:
    if _busy:
        return (255, 176, 32, 255)
    if not st:
        return (110, 110, 118, 255)
    s = st.get("status", "")
    if s == "RUNNING":
        return (63, 127, 224, 255)
    if s == "ERROR":
        return (214, 69, 65, 255)
    return (140, 148, 160, 255)


_icon: Icon | None = None


def _refresh_icon() -> None:
    if _icon is None:
        return
    try:
        _icon.icon = _make_icon(_status_color(_last_state), busy=bool(_busy))
        _icon.title = _tooltip()
        _icon.update_menu()
    except Exception:  # noqa: BLE001
        pass


def _tooltip() -> str:
    st = _last_state
    if not st:
        return "SGM PF — 服务未启动"
    line = "SGM PF — %s" % st.get("status", "?")
    if st.get("status") == "RUNNING":
        line += "\n场次 %s" % st.get("session_name", "")
        line += "\n第 %s 场 · %s 分 · 连胜 %s" % (
            st.get("fight_no", "?"), _fmt(st.get("score")), st.get("streak", "?"))
        line += "\n%s" % st.get("step", "")
    if _busy:
        line += "\n[%s...]" % _busy
    return line


def _fmt(n) -> str:
    try:
        return "{:,}".format(int(n))
    except (TypeError, ValueError):
        return str(n)


def _state_head(item=None) -> str:
    """菜单顶部那行不可点的状态文字。

    ⚠️ 必须收一个参数: pystray 是**调用** text/callable 求值的 (`descriptor.text`
    属性会执行 `self._text(self)`), 零参函数会炸:
        TypeError: _state_head() takes 0 positional arguments but 1 was given
    菜单是在 `_mark_ready()` 里构建的 ⇒ 托盘会在启动瞬间就挂掉, 且因为 pythonw
    没有控制台, 只能靠 excepthook 落盘才发现 (2026-09-24 实测)。
    """
    st = _last_state
    if _busy:
        return "%s..." % _busy
    if not st:
        return "服务未启动"
    s = st.get("status", "?")
    if s == "RUNNING":
        return "运行中 · 第%s场 · %s分 · 连胜%s" % (
            st.get("fight_no", "?"), _fmt(st.get("score")), st.get("streak", "?"))
    return {"IDLE": "待命 (未开始)", "ERROR": "出错了", "STOPPED": "已停止"}.get(s, s)


def _poll_loop() -> None:
    """前台轮询: 2s 一次拿状态, 驱动图标/菜单。bot 不在时也每秒刷新(等它起来)。"""
    global _last_state
    while True:
        st = _http("/api/state", timeout=3.0)
        _last_state = st if (st and st.get("svc") == "sgm-pf-bot") else {}
        _refresh_icon()
        time.sleep(2.0)


# ---------------- main ----------------

def main() -> int:
    global _icon
    os.makedirs(os.path.dirname(BOT_LOG), exist_ok=True)
    _log("托盘启动 (pid=%d)" % os.getpid())

    menu = Menu(
        MenuItem(_state_head, None, enabled=False),
        Menu.SEPARATOR,
        MenuItem("打开 WebUI", _bg(act_open, "打开页面"), default=True),
        Menu.SEPARATOR,
        MenuItem("启动服务 (不开跑)", _bg(act_start, "启动中")),
        MenuItem("开跑当前场次", _bg(act_run, "开跑")),
        MenuItem("重启服务", _bg(act_restart, "重启中")),
        MenuItem("结束服务", _bg(act_stop, "停止中")),
        Menu.SEPARATOR,
        MenuItem("启动模拟器", _bg(act_mumu_launch, "开模拟器")),
        MenuItem("启动游戏", _bg(act_mumu_game, "开游戏")),
        MenuItem("关闭模拟器", _bg(act_mumu_shutdown, "关模拟器")),
        Menu.SEPARATOR,
        MenuItem("退出托盘 (服务继续跑)", _bg(act_quit, "退出")),
    )

    _icon = Icon("sgm-pf", _make_icon(), "SGM PF", menu)
    threading.Thread(target=_poll_loop, daemon=True, name="poll").start()

    def _auto_start() -> None:
        # 托盘启动即默认启动服务 (用户 2026-09-30 口径), 仍**不开跑** —— 场次
        # 留给「开跑当前场次」/WebUI「开始」。睡 1s 等 icon.run() 先把图标挂上,
        # act_start 末尾的气泡通知才有落点; 起进程本身要好几秒, 不差这 1s。
        time.sleep(1.0)
        try:
            act_start()
        except Exception:  # noqa: BLE001
            _log("自动启动服务异常: %s" % traceback.format_exc()[-200:])

    threading.Thread(target=_auto_start, daemon=True, name="autostart").start()
    try:
        _icon.run()
    except Exception:  # noqa: BLE001
        _excepthook(*sys.exc_info())
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
