"""LinkDead → 重生 / 彻底退出 的子进程回归 (2026-09-28)。

覆盖 2026-09-28 现场修的三件事:
  1. 重生成功时老进程**硬退出** (os._exit, 不陪 MAA 销毁一起卡死), 新进程拿独立世代日志;
  2. 重生用尽 / 受限时也是硬退出 (code 3) —— 不留僵尸占端口占日志;
  3. 退出看门狗在 MAA 销毁卡住时兜底强制退出; start_webui 会等老进程让出端口。

为什么走子进程: 这些路径的义务就是"进程必须真退出", os._exit 会带走测试进程本身,
只能在外面看退出码与耗时。子进程里 pf_store/jjc_store 换成桩, 真实仓库数据一个字节
都不动 (autouse fixture 核对 mtime)。

跑法 (必须 anaconda python —— pf_bot 要 cv2/MAA; 且 bot 停着, 否则它的正常写入会撞
mtime 断言):
    C:/Users/zhiya/anaconda3/python.exe -m pytest tests/test_respawn_exit.py -q
"""
import json
import os
import re
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import pytest

REPO = Path(__file__).resolve().parent.parent
LIVE_FILES = ("sessions.json", "score_log.csv", "respawn.json", "daily.json")


def _runtime_ok(probe: str) -> bool:
    """真跑一次 import 探针: 只有带 cv2/MAA 的解释器 (anaconda) 才跑得动本文件。

    探针刻意不 import pf_store —— import 它会重写 sessions.json。
    """
    try:
        p = subprocess.run([sys.executable, "-c", probe], cwd=str(REPO),
                           capture_output=True, text=True, timeout=180)
        return p.returncode == 0
    except Exception:  # noqa: BLE001
        return False


if not _runtime_ok("import sys; sys.path.insert(0, 'tools'); import pf_env; "
                   "pf_env.preload_msvcrt(); import cv2, maa"):
    pytest.skip("需要带 cv2/MAA 的解释器 (anaconda python)", allow_module_level=True)


@pytest.fixture(autouse=True)
def _no_live_data_touched():
    """本文件只许写 tmp_path; 真实仓库数据动一下就报错。"""
    paths = [REPO / "debug" / "pf" / n for n in LIVE_FILES]
    before = {p: (p.stat().st_mtime_ns, p.stat().st_size) for p in paths if p.exists()}
    yield
    for p, snap in before.items():
        now = (p.stat().st_mtime_ns, p.stat().st_size)
        assert now == snap, f"测试改动了真实数据 {p}"


CHILD_CODE = r'''
import json, os, sys, time, types
from pathlib import Path

repo = Path(os.environ["SGM_TEST_REPO"])
tmp = Path(os.environ["SGM_TEST_TMP"])
sys.path.insert(0, str(repo / "tools"))

# 隔离真实数据: pf_store / jjc_store 换成桩 (pf_webui 也会 import 它们)
class _StubStore:
    session_id = None
    def get(self, sid): return {}
    def set_session(self, sid): return None
    def sessions(self): return []
    def __getattr__(self, name):
        raise AttributeError(name)

_store = types.ModuleType("pf_store")
_store.STORE = _StubStore()
sys.modules["pf_store"] = _store

_jjc = types.ModuleType("jjc_store")
_jjc.VERSIONS = type("V", (), {"all": staticmethod(lambda: [])})
_jjc.JJC = None
_jjc.ordered_entries = lambda *a, **k: []
_jjc.sgm_day = lambda *a, **k: None
sys.modules["jjc_store"] = _jjc

import pf_bot
from pf_env import STATE

pf_bot.start_webui = lambda *a, **k: None          # 测试不绑 8790

if os.environ["SGM_TEST_MODE"] == "watchdog":
    pf_bot._arm_exit_watchdog(0.5)
    time.sleep(120)                                # 假装主线程卡在 MAA 销毁里
    print("REACHED-AFTER-WATCHDOG", flush=True)
    raise SystemExit(9)

def _stub_spawn(cmdline, cwd, log_path=None, env_lines=(), timeout=30):
    (tmp / "spawn.json").write_text(json.dumps(
        {"cmdline": cmdline, "cwd": cwd, "log_path": log_path,
         "env_lines": list(env_lines)}), encoding="utf-8")
    return 4242

pf_bot.spawn_detached = _stub_spawn
pf_bot.PfBot._RESPAWN_MARKER = tmp / "respawn.json"
pf_bot.PfBot._RESPAWN_LOG_DIR = tmp

bot = pf_bot.PfBot()
bot.tracker.ensure = lambda sid: False             # 不碰场次库
def _boom():
    raise pf_bot.LinkDead("stub: MAA 内部作业僵死")
bot.step = _boom
STATE.running = True
bot.run()                                          # 走 LinkDead 分支: 重生或彻底退出
print("REACHED-AFTER-RUN", flush=True)             # 不该出现
raise SystemExit(9)
'''


def _child(mode: str, tmp: Path, timeout: float = 60):
    env = dict(os.environ, PYTHONUTF8="1", PYTHONIOENCODING="utf-8",
               SGM_TEST_MODE=mode, SGM_TEST_TMP=str(tmp), SGM_TEST_REPO=str(REPO))
    env.pop("SGM_PF_RESUME", None)
    started = time.time()
    try:
        # cwd 落在 tmp: 万一有相对路径的杂散写入也不碰仓库
        proc = subprocess.run([sys.executable, "-c", CHILD_CODE], cwd=str(tmp), env=env,
                              capture_output=True, text=True, encoding="utf-8",
                              errors="replace", timeout=timeout)
    except subprocess.TimeoutExpired as e:
        tail = e.stdout if isinstance(e.stdout, str) else (e.stdout or b"").decode(
            "utf-8", "replace")
        pytest.fail(f"子进程 {mode} 超时 {timeout}s 没退出 —— 正是僵尸进程的症状: "
                    f"{tail[-800:]}")
    return proc, time.time() - started


def test_respawn_hard_exits_and_spawns_next_generation(tmp_path):
    """重生成功: 老进程立刻硬退出 (不等 MAA 销毁), 新进程带 RESUME + 独立世代日志。"""
    proc, secs = _child("respawn", tmp_path)
    assert proc.returncode == 0, proc.stdout + proc.stderr
    assert "REACHED-AFTER-RUN" not in proc.stdout
    assert secs < 30, f"老进程 {secs:.1f}s 才退出 —— 没走硬退出?"

    spawn = json.loads((tmp_path / "spawn.json").read_text(encoding="utf-8"))
    assert "tools\\pf_bot.py" in spawn["cmdline"]
    gen = Path(spawn["log_path"]).name
    assert re.fullmatch(r"bot_stdout_\d{8}-\d{6}\.log", gen), gen        # 独立世代日志
    assert any('SGM_PF_RESUME=1' in line for line in spawn["env_lines"])

    marker = json.loads((tmp_path / "respawn.json").read_text(encoding="utf-8"))
    assert marker["day"] == time.strftime("%Y-%m-%d") and marker["count"] == 1
    assert "自我重生 (今日第1/20次)" in proc.stdout
    assert "本进程退出, 由新进程接管" in proc.stdout

def test_respawn_exhausted_exits_hard_without_spawning(tmp_path):
    """用尽 20 次: 不再拉起新进程, 直接彻底退出 (code 3)。"""
    (tmp_path / "respawn.json").write_text(json.dumps(
        {"day": time.strftime("%Y-%m-%d"), "count": 20, "last": time.time() - 3600}),
        encoding="utf-8")
    proc, secs = _child("exhausted", tmp_path)
    assert proc.returncode == 3, proc.stdout + proc.stderr
    assert secs < 30
    assert "重生已用尽 (今日 20/20 次)" in proc.stdout
    assert "彻底退出" in proc.stdout
    assert not (tmp_path / "spawn.json").exists()
    assert "REACHED-AFTER-RUN" not in proc.stdout


def test_respawn_throttled_exits_hard_with_reason(tmp_path):
    """间隔不足 5 分钟: 同样彻底退出, 且日志说明原因 (不再无声无息)。"""
    (tmp_path / "respawn.json").write_text(json.dumps(
        {"day": time.strftime("%Y-%m-%d"), "count": 1, "last": time.time()}),
        encoding="utf-8")
    proc, _ = _child("throttled", tmp_path)
    assert proc.returncode == 3, proc.stdout + proc.stderr
    assert "重生受限" in proc.stdout
    assert not (tmp_path / "spawn.json").exists()


def test_exit_watchdog_forces_exit_when_shutdown_hangs(tmp_path):
    """主线程卡住 (模拟 MAA 销毁死等) 时, 看门狗必须把进程收掉。"""
    proc, secs = _child("watchdog", tmp_path, timeout=30)
    assert proc.returncode == 0, proc.stdout + proc.stderr
    assert secs < 15, f"看门狗没生效: {secs:.1f}s"
    assert "退出超时" in proc.stdout
    assert "REACHED-AFTER-WATCHDOG" not in proc.stdout


# ---------- start_webui: 端口交接 (重型 import, 需先装隔离桩) ----------

@pytest.fixture(scope="module")
def pf_webui_mod():
    """在隔离 store 的前提下导入 pf_webui (裸 import 会重写 sessions.json)。"""
    import types
    sys.path.insert(0, str(REPO / "tools"))
    if "pf_store" not in sys.modules:
        stub = types.ModuleType("pf_store")
        stub.STORE = type("S", (), {"session_id": None, "get": lambda self, s: {},
                                    "sessions": lambda self: []})()
        sys.modules["pf_store"] = stub
    if "jjc_store" not in sys.modules:
        stub = types.ModuleType("jjc_store")
        stub.VERSIONS = type("V", (), {"all": staticmethod(lambda: [])})
        stub.JJC, stub.ordered_entries, stub.sgm_day = None, (lambda *a, **k: []), (lambda *a, **k: None)
        sys.modules["jjc_store"] = stub
    import pf_webui
    return pf_webui


def _fake_bot_server():
    """冒充一个正在跑的 pf_bot: /api/state 回 svc=sgm-pf-bot。"""
    class _H(BaseHTTPRequestHandler):
        def do_GET(self):  # noqa: N802
            body = json.dumps({"svc": "sgm-pf-bot", "status": "ERROR"}).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *a):  # noqa: A003
            pass

    srv = ThreadingHTTPServer(("127.0.0.1", 0), _H)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv, srv.server_address[1]


def test_start_webui_refuses_while_another_bot_holds_port(pf_webui_mod, monkeypatch):
    """真有第二个实例在跑: 等超时后仍然拒绝, 并把僵尸排查写进报错。"""
    monkeypatch.setattr(pf_webui_mod, "_PORT_WAIT_S", 1.0)
    srv, port = _fake_bot_server()
    try:
        with pytest.raises(RuntimeError) as err:
            pf_webui_mod.start_webui(port)
        assert "拒绝启动第二个实例" in str(err.value)
        assert "僵死" in str(err.value)          # 僵尸排查提示
    finally:
        srv.shutdown()
        srv.server_close()


def test_start_webui_waits_for_departing_process(pf_webui_mod, monkeypatch):
    """老进程正在退出 (重生/停止): 等它让出端口后必须能绑上。"""
    monkeypatch.setattr(pf_webui_mod, "_PORT_WAIT_S", 10.0)
    srv, port = _fake_bot_server()
    threading.Timer(1.0, lambda: (srv.shutdown(), srv.server_close())).start()
    web = pf_webui_mod.start_webui(port)
    try:
        assert web.server_address[1] == port
    finally:
        web.shutdown()
        web.server_close()
