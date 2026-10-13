"""仓库扫描的 WebUI 接口契约 (2026-10-14)。

覆盖 `GET /api/roster`(进度快照 + 落盘回落) 与 `POST /api/roster/scan`(置位/去重/门卫)。
扫描本体跑在**主循环**里(设备独占), 这里只钉住"前端能看到什么、什么情况下会被拒" ——
纯 HTTP + 内存状态, 不连设备、不起 bot。

跑法: python -m pytest tests/test_roster_webui.py -q
"""
import json
import urllib.error
import urllib.request
from pathlib import Path

import pytest

TOOLS = Path(__file__).resolve().parent.parent / "tools"
import sys  # noqa: E402

sys.path.insert(0, str(TOOLS))
import pf_webui as W                                    # noqa: E402
from pf_env import STATE                                # noqa: E402

PORT = 8799          # pf_bot 自己占 8790; 这里挑个邻居端口, 不干扰在跑的实例


def _req(method, path, body=None, ctype="application/json"):
    base = f"http://127.0.0.1:{PORT}"
    data = None if method == "GET" else json.dumps(body or {}).encode()
    headers = {} if data is None else {"Content-Type": ctype}
    req = urllib.request.Request(base + path, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=5) as r:
            return r.status, json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:                       # _reject 的错误体不都是 JSON (415 就是纯文本)
            return e.code, json.loads(raw)
        except json.JSONDecodeError:
            return e.code, {"raw": raw}


@pytest.fixture(scope="module")
def srv():
    server = W.start_webui(port=PORT)
    yield
    server.shutdown()
    server.server_close()


@pytest.fixture(autouse=True)
def clean_roster(srv):
    """每个用例前复位进度容器与请求位 (STATE.running 用完还原, 别污染别的测试)。"""
    was_running = STATE.running
    with W._ROSTER_LOCK:
        W._ROSTER.update(state="idle", phase="", drags=0, frames=0, total=0,
                         log=[], result=None, error=None, started=0.0, finished=0.0)
    STATE.roster_scan_requested = False
    STATE.running = False
    yield
    STATE.running = was_running
    STATE.roster_scan_requested = False


def test_snapshot_shape_and_disk_fallback(srv):
    """冷启动: 内存没有结果时应回落到落盘的 roster.json (进程重启后仍看得到)。"""
    code, d = _req("GET", "/api/roster")
    assert code == 200
    assert set(("state", "phase", "drags", "frames", "total", "log", "result",
                "error", "elapsed", "saved")) <= set(d)
    if Path(W.ROSTER_PATH).is_file():
        assert d["state"] == "done" and d["saved"] is True
        assert isinstance(d["result"]["count"], int)
    else:
        assert d["state"] == "idle" and d["result"] is None


def test_scan_request_sets_flag_and_queues(srv):
    code, d = _req("POST", "/api/roster/scan")
    assert code == 200 and d.get("ok") is True
    assert STATE.roster_scan_requested is True
    _, snap = _req("GET", "/api/roster")
    assert snap["state"] == "queued"


def test_duplicate_request_is_rejected(srv):
    """同一时刻只允许一次 —— 第二次点必须被拒, 不能排成两趟 (会白拖两遍)。"""
    assert _req("POST", "/api/roster/scan")[0] == 200
    code, d = _req("POST", "/api/roster/scan")
    assert code == 409 and "跑" in d["error"]


def test_rejected_while_session_running(srv):
    """场次运行中不给扫 (会跟战斗抢设备), 且**不许**置位留下悬空请求。"""
    STATE.running = True
    code, d = _req("POST", "/api/roster/scan")
    assert code == 409 and "运行中" in d["error"]
    assert STATE.roster_scan_requested is False


def test_progress_reporting(srv):
    W.roster_begin()
    W.roster_line("[锚定] 首帧 14 卡")
    W.roster_step({"phase": "锚定", "n": 0, "frames": 14, "total": 14})
    W.roster_step({"phase": "扫", "n": 1, "frames": 14, "new": 6, "dup": 8,
                   "conflict": 0, "total": 20, "shift": 648})
    _, d = _req("GET", "/api/roster")
    assert d["state"] == "running"
    assert (d["phase"], d["drags"], d["total"]) == ("扫", 1, 20)
    assert d["log"] and "首帧" in d["log"][0]


def test_finish_success_and_error(srv):
    W.roster_begin()
    W.roster_finish({"count": 255, "unmapped": 0, "min_gap": 163, "max_gap": 169,
                     "by_char": {"Filia": 17}, "dup_names": {"SCRUB": 3},
                     "conflicts": [], "cards": []})
    _, d = _req("GET", "/api/roster")
    assert d["state"] == "done" and d["result"]["count"] == 255 and d["saved"] is False

    W.roster_begin()
    W.roster_finish(error="当前屏幕没有角色网格")
    _, d = _req("GET", "/api/roster")
    assert d["state"] == "error" and "角色网格" in d["error"]


def test_post_still_requires_json(srv):
    """门卫没被新路由绕过 (415 纯文本错误体)。"""
    assert _req("POST", "/api/roster/scan", ctype="text/plain")[0] == 415
