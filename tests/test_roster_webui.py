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


# ---------- 收藏统计 + 炫耀页 (2026-10-14) ----------

def test_collection_shape_or_empty(srv):
    """有落盘结果 -> 聚合+评分; 没有 -> ok:false 且带引导文案 (不 500)。"""
    code, d = _req("GET", "/api/roster/collection")
    assert code == 200
    if not Path(W.ROSTER_PATH).is_file():
        assert d["ok"] is False and "扫描" in d["error"]
        return
    assert d["ok"] is True
    agg, sc = d["agg"], d["score"]
    assert agg["total_cards"] > 0 and agg["variant_kinds"] > 0
    assert sum(agg["by_tier"].values()) == agg["variant_kinds"]      # 分桶不丢
    assert set(sc) >= {"total", "grade", "grade_title", "completeness",
                       "rarity", "coverage", "chars_covered"}
    assert 0 <= sc["total"] <= 100 and sc["grade"] in "SABCDE"
    v = agg["variants"][0]
    assert {"id", "fandom", "char", "element", "tier", "count", "sa_title",
            "sa_official"} <= set(v)


def test_collection_matches_direct_recompute(srv):
    """服务端快照必须等于直接调 pf_collection 的结果 (聚合口径不被路由层改写)。"""
    if not Path(W.ROSTER_PATH).is_file():
        return
    import pf_collection as C
    raw = json.loads(Path(W.ROSTER_PATH).read_text(encoding="utf-8"))
    agg = C.aggregate(raw.get("cards") or [])
    W._collection_file["mtime"] = None     # 强制重算, 别吃别的用例的缓存
    code, d = _req("GET", "/api/roster/collection")
    assert code == 200 and d["ok"]
    assert d["agg"]["variant_kinds"] == agg["variant_kinds"]
    assert d["agg"]["total_cards"] == agg["total_cards"]
    assert d["score"] == C.score(agg)


def test_export_showcase_html(srv):
    """炫耀页: 单文件 HTML + 内嵌数据; ?download=1 带附件头; 无数据 404。"""
    req = urllib.request.Request(f"http://127.0.0.1:{PORT}/api/roster/export")
    try:
        with urllib.request.urlopen(req, timeout=5) as r:
            body = r.read().decode("utf-8")
            assert r.status == 200
            assert "text/html" in r.headers["Content-Type"]
            assert "Content-Disposition" not in r.headers
    except urllib.error.HTTPError as e:
        assert not Path(W.ROSTER_PATH).is_file() and e.code == 404
        return
    assert "Skullgirls Mobile 收藏图鉴" in body
    assert "const DATA=" in body
    # 数据必须已转义 "</", 否则文案里出现 </script> 会打断脚本
    import re
    m = re.search(r"const DATA=(\{.*?\});\n", body, re.S)
    assert m and json.loads(m.group(1).replace("<\\/", "</"))["total_cards"] > 0

    req2 = urllib.request.Request(
        f"http://127.0.0.1:{PORT}/api/roster/export?download=1")
    with urllib.request.urlopen(req2, timeout=5) as r:
        assert "attachment" in r.headers.get("Content-Disposition", "")
