"""pf_schedule 接入 tag 归类的离线测试 (2026-10-06)。

覆盖「不搞父子, 只搞归类 tag」接入后的三条关键行为:
  1. **先更新场次再做比对** —— JJC.today() 必须在 classify之前被调用;
     缺这一步就会拿昨天的快照比今天的场地, 白跑一天。
  2. **条件按 tag 取, 不继承** —— 建出来的场次 score_target/energy_cost/rule
     全部来自 pf_artag.TAG_CONDITIONS, 与任何父场次无关。
  3. **判不出类别仍建场次**(取最保守条件) —— 不因认不出类别就无场次可跑。

全程不触网、不起MuMu/游戏: JJC 与pf_store 都在测试里换成桩。
跑法 (需 anaconda python —— pf_schedule 延迟 import pf_store):
    C:/Users/zhiya/anaconda3/python.exe -m pytest tests/test_schedule_tag.py -q
"""
import sys
import types
from pathlib import Path

import pytest

REPO = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO / "tools"))

import pf_artag as T          # noqa: E402
import pf_schedule as S# noqa: E402

RUNNING_SID = "s_running"


@pytest.fixture(autouse=True)
def stub_store(monkeypatch, tmp_path):
    """把 pf_store.STORE 换成内存桩 + 隔离的 jjc 版本账本。

    真实 STORE import 即重写 debug/pf/sessions.json (pf_storage._load ->
    _save_sessions), 跑测试绝不能碰它。
    """
    import jjc_store

    class FakeStore:
        def __init__(self):
            self.sessions = []
            self.children = []
            self._n = 0

        def _id(self):
            self._n += 1
            return f"s_test{self._n}"

        def get(self, sid):
            return next((s for s in self.sessions if s["id"] == sid), None)

        def create_tagged(self, name, rule, *, tag=None, tag_basis=None,
                          rest_every=0, rest_minutes=0, score_target=None,
                          energy_cost=4, scene=None):
            sess = {"id": self._id(), "name": name, "rule": rule,
                    "tag": tag, "tag_basis": tag_basis,
                    "score_target": score_target, "energy_cost": energy_cost,
                    "rest_every": rest_every, "rest_minutes": rest_minutes,
                    "scene": scene, "created": 0.0}
            self.sessions.append(sess)
            return sess

        def create_child(self, parent_sid, name=None):
            p = self.get(parent_sid)
            if not p:
                raise KeyError(parent_sid)
            self.children.append(parent_sid)
            c = self.create_tagged(f"{p['name']} 子", p.get("rule"))
            return c

    fake = FakeStore()
    pkg = types.ModuleType("pf_store")
    pkg.STORE = fake
    monkeypatch.setitem(sys.modules, "pf_store", pkg)
    # 隔离版本账本: 不写真实 versions.json
    monkeypatch.setattr(jjc_store, "VERSIONS", types.SimpleNamespace(
        record=lambda *a, **k: None, mark_deleted=lambda *a, **k: None,
        ensure_baseline=lambda *a, **k: None, all=lambda: {}))
    return fake


def _stub_jjc(monkeypatch, snap, calls):
    """把 jjc_store.JJC 换成可记录调用次数的桩。"""
    import jjc_store

    class FakeJJC:
        @staticmethod
        def today(log=None):
            calls.append("today")
            return snap

        @staticmethod
        def refresh(log=None):
            calls.append("refresh")
            return snap or {"action": "failed"}

    monkeypatch.setattr(jjc_store, "JJC", FakeJJC)
    return FakeJJC


def _stub_scene(monkeypatch):
    """桩掉 act_run_pf 延迟 import 的 pf_scene / pf_env。

    ⚠️ 这两个模块是**函数内延迟 import**的, 不在 pf_schedule 命名空间里 ——
    monkeypatch.setattr(S, "ensure_mumu", ...) 会 AttributeError,
    只能往 sys.modules 里塞整个模块桩。
    """
    import types as _t
    scene = _t.SimpleNamespace(launch_game=lambda: None, goto_pf=lambda: None,
                               center=lambda kw: None)
    mod = _t.ModuleType("pf_scene")
    mod.PfScene = lambda: scene
    mod.ensure_mumu = lambda *a, **k: None
    monkeypatch.setitem(sys.modules, "pf_scene", mod)
    env = _t.ModuleType("pf_env")
    env.resolve_adb = lambda: ("adb", 0)
    monkeypatch.setitem(sys.modules, "pf_env", env)
    return scene


# ---------------------------------------------------------------- 顺序

def test_先更新场次再做比对(monkeypatch, stub_store):
    """JJC.today() 必须在归类之前被调用 —— 缺了就是拿旧快照比新场地。"""
    calls = []
    snap = {"day": "2026-10-06", "stale": False, "revision": 1, "entries": {
        "char": {"key": "char", "name": "Ms. Fortune", "active": True,
                 "scope": "current"}}}
    _stub_jjc(monkeypatch, snap, calls)

    sid, info = S.classify_and_create("Ms. Fortune", {"dry_run": True})

    assert calls == ["today"], f"JJC.today 未被调用, calls={calls}"
    assert info["tag"] == "character", info
    assert sid is None, "dry_run 不应建场次"


def test_快照stale会显式告警(monkeypatch, stub_store, capsys):
    """当日没取到、显示的是历史归档时, 必须留痕 —— 否则拿旧数据当今日实况。"""
    calls = []
    snap = {"day": "2026-10-01", "stale": True, "revision": 3, "entries": {}}
    _stub_jjc(monkeypatch, snap, calls)
    S.classify_and_create("WHATEVER", {"dry_run": True})
    out = capsys.readouterr().out
    assert "归档" in out or "stale" in out.lower(), "stale 快照未留痕"


# ---------------------------------------------------------------- 条件取值

def test_条件按tag取与TAG_CONDITIONS一致(monkeypatch, stub_store):
    """建出来的条件必须等于条件表, 不受任何场次影响。"""
    cases = {
        "Ms. Fortune": ("character", 50_000_000, 5),
        "Fire":        ("element",   50_000_000, 4),
        "Wind":        ("rift",      50_000_000, 4),
    }
    entries = {
        "char": {"key": "char", "name": "Ms. Fortune", "active": True, "scope": "current"},
        "elem": {"key": "elem", "name": "Fire", "active": True, "scope": "current"},
        "rift": {"key": "rift", "name": "Wind", "active": True, "scope": "current"},
    }
    for title, (tag, tgt, ec) in cases.items():
        snap = {"day": "2026-10-06", "stale": False, "revision": 1, "entries": entries}
        _stub_jjc(monkeypatch, snap, [])
        stub_store.sessions.clear()
        sid, info = S.classify_and_create(title, {})
        assert info["tag"] == tag, f"{title}: {info['tag']} != {tag}"
        assert info["score_target"] == tgt, f"{title}: tgt={info['score_target']}"
        assert info["energy_cost"] == ec, f"{title}: ec={info['energy_cost']}"
        # 落盘的场次必须与 info 一致
        sess = stub_store.get(sid)
        assert sess["score_target"] == tgt and sess["energy_cost"] == ec
        assert sess["tag"] == tag
        assert sess["tag_basis"], "tag_basis 必须写入, 否则事后无法追溯判据"


def test_建场次不继承任何父场次(monkeypatch, stub_store):
    """tag 路径下不得调用 create_child (父子已退役)。"""
    _stub_jjc(monkeypatch, {"day": "2026-10-06", "stale": False, "entries": {
        "char": {"key": "char", "name": "Ms. Fortune", "active": True,
                 "scope": "current"}}}, [])
    S.classify_and_create("Ms. Fortune", {})
    assert stub_store.children == [], f"不该建子场次, 实际 {stub_store.children}"
    assert "parent" not in stub_store.sessions[0], "新场次不该带 parent"


def test_元素场挂对应元素限制(monkeypatch, stub_store):
    _stub_jjc(monkeypatch, {"day": "2026-10-06", "stale": False, "entries": {
        "elem": {"key": "elem", "name": "Fire", "active": True, "scope": "current"}}}, [])
    sid, info = S.classify_and_create("Fire", {})
    assert info["rule"] == {"type": "element", "value": "fire"}, info


# ---------------------------------------------------------------- 降级

def test_判不出类别仍建场次取最保守条件(monkeypatch, stub_store, capsys):
    """认不出类别**照样建场次**(最保守 4kw/4能量/无规则) —— 不能无场次可跑。"""
    _stub_jjc(monkeypatch, {"day": "2026-10-06", "stale": False, "entries": {}}, [])
    sid, info = S.classify_and_create("ZZQQ UNKNOWN NAME", {})
    assert sid, "判不出类别也必须建场次"
    assert info["tag"] == T.UNKNOWN_TAG
    assert info["score_target"] == T.DEFAULT_SCORE_TARGET
    assert info["energy_cost"] == T.DEFAULT_ENERGY_COST
    assert info["rule"] is None


def test_无数据源也不崩(monkeypatch, stub_store):
    """JJC 抓取失败(返回 None)时照样能建场次 —— 抓取失败不该阻断整条链。"""
    calls = []
    _stub_jjc(monkeypatch, None, calls)
    sid, info = S.classify_and_create("SOMETHING", {})
    assert sid, "无数据源也应建场次"
    assert info["tag"] == T.UNKNOWN_TAG


def test_月场走人工表挂上元素(monkeypatch, stub_store):
    """COSTUMEPARTY: sgmnow 只给名字, 类别+元素靠 monthly_manual.json。"""
    _stub_jjc(monkeypatch, {"day": "2026-10-06", "stale": False, "entries": {
        "holi": {"key": "holi", "name": "Costume Party", "active": True,
                 "scope": "current"}}}, [])
    sid, info = S.classify_and_create("COSTUMEPARTY", {})
    assert info["tag"] == "monthly_element", info
    assert info["rule"] == {"type": "element", "value": "dark"}, info
    assert info["score_target"] is None, "月场无上限"


# ---------------------------------------------------------------- run_pf 兼容

def test_run_pf父场次有tag则按tag建(monkeypatch, stub_store):
    """存量schedule.json 里的 parent_session 任务: 父有tag 就按 tag 建。"""
    _stub_jjc(monkeypatch, {"day": "2026-10-06", "stale": False, "entries": {}}, [])
    _stub_scene(monkeypatch)
    stub_store.sessions.append(
        {"id": "s_p1", "name": "角色周场", "tag": "character", "rule": None,
         "score_target": 50_000_000, "energy_cost": 5})
    started = []
    monkeypatch.setattr(S, "start_bot", lambda sid: started.append(sid))
    monkeypatch.setattr(S, "bot_alive", lambda: False)

    S.act_run_pf({"parent_session": "s_p1"})

    assert stub_store.children == [], "父有tag 时不该走 create_child"
    assert len(stub_store.sessions) == 2
    child = stub_store.sessions[1]
    assert child["tag"] == "character"
    assert child["score_target"] == 50_000_000
    assert child["energy_cost"] == 5
    assert started == [child["id"]], f"应启动新建场次, 实际 {started}"


def test_run_pf父场次无tag才退回继承(monkeypatch, stub_store, capsys):
    """未迁移的父场次(无 tag)退回旧继承, 并告警提示先迁移。"""
    _stub_jjc(monkeypatch, {"day": "2026-10-06", "stale": False, "entries": {}}, [])
    _stub_scene(monkeypatch)
    stub_store.sessions.append(
        {"id": "s_p0", "name": "金币场", "tag": None, "rule": None,
         "score_target": 40_000_000, "energy_cost": 4})
    monkeypatch.setattr(S, "start_bot", lambda sid: None)
    monkeypatch.setattr(S, "bot_alive", lambda: False)

    S.act_run_pf({"parent_session": "s_p0"})
    assert stub_store.children == ["s_p0"], "无tag 的父应退回 create_child"
    out = capsys.readouterr().out
    assert "migrate_sessions_to_tag" in out, "应提示先迁移"


def test_run_pf父场次不存在会报错(monkeypatch, stub_store):
    _stub_jjc(monkeypatch, None, [])
    _stub_scene(monkeypatch)
    monkeypatch.setattr(S, "start_bot", lambda sid: None)
    monkeypatch.setattr(S, "bot_alive", lambda: False)
    with pytest.raises(RuntimeError, match="不存在"):
        S.act_run_pf({"parent_session": "s_nope"})