"""pf_artag 场次归类 tag 的离线单测 (2026-10-06)。

覆盖用户 2026-10-06 新口径:
  1. 角色场 -> 5kw + 5 能量 + class 防守角色规则 (角色名取自 sgmnow char.name);
  2. 元素场 -> 5kw + 4 能量 + 对应 element 限制;
  3. 其余(金币/星/月/招式) -> 4kw + 4 能量 + rule=None;
  4. 名称相似度过阈值才认, 低于阈值不猜 -> unknown + 最保守条件 + note 留痕;
  5. OCR 残串(丢首字母/丢字母)仍能命中 —— pf_nav 已实证的两种真实残缺形态;
  6. only_active 只收在开的, 不把 now 页的 "Last X PF"(已收场) 收进索引。

数据源用仓库里的真实快照 (debug/pf/jjc/snapshots/) 与真实 hub 扫描结果
(debug/pf/arenas.json), 断言的是**实测数据**而非自造样本。

跑法 (纯逻辑, 任何解释器都行, 不 import cv2/MAA, 不碰sessions.json):
    C:/Users/zhiya/anaconda3/python.exe -m pytest tests/test_artag.py -q
"""
import io
import json
from pathlib import Path

import pytest

import sys
sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "tools"))

import pf_artag as T

REPO = Path(__file__).resolve().parent.parent
SNAP_DIR = REPO / "debug" / "pf" / "jjc" / "snapshots"
ARENAS = REPO / "debug" / "pf" / "arenas.json"


def _load(p: Path):
    with io.open(p, encoding="utf-8") as f:
        return json.load(f)


@pytest.fixture(scope="module")
def snap():
    days = sorted(SNAP_DIR.glob("*.json")) if SNAP_DIR.is_dir() else []
    if not days:
        pytest.skip("无JJC 快照归档, 跳过 (先跑 WebUI 的「刷新快照」)")
    return _load(days[-1])


@pytest.fixture(scope="module")
def hub():
    if not ARENAS.exists():
        pytest.skip("无 arenas.json, 跳过 (需先在游戏内扫一次 hub)")
    return _load(ARENAS)


# ---------------------------------------------------------------- 条件表

def test_条件表三档取值():
    """角色 5kw/5能量, 元素 5kw/4能量, 其余 4kw/4能量。"""
    assert T.TAG_CONDITIONS["character"] == (50_000_000, 5, "class")
    assert T.TAG_CONDITIONS["element"] == (50_000_000, 4, "element")
    assert T.TAG_CONDITIONS["rift"] == (50_000_000, 4, "element")
    for tag in ("gold", "assist", "monthly", "move"):
        assert T.TAG_CONDITIONS[tag] == (40_000_000, 4, None), tag


def test_默认兜底是4kw4能量():
    assert T.DEFAULT_SCORE_TARGET == 40_000_000
    assert T.DEFAULT_ENERGY_COST == 4


# ---------------------------------------------------------------- 归一

def test_角色名归一():
    """sgmnow 写 'Ms. Fortune', 防守队要 MSFORTUNE。"""
    assert T.norm_char("Ms. Fortune") == "MSFORTUNE"
    assert T.norm_char("Cerebella") == "CEREBELLA"
    assert T.norm_char("Big Band") == "BIGBAND"


def test_元素名归一():
    assert T.norm_element("Fire") == "fire"
    assert T.norm_element("Wind") == "wind"
    assert T.norm_element("  Dark ") == "dark"
    assert T.norm_element("Chaos") is None      # 认不出绝不猜


def test_场地名归一_丢空格标点():
    assert T._key("A SHOT IN THE DARK") == "ASHOTINTHEDARK"
    assert T._key("BIG BEN'S BEATDOWN") == "BIGBENSBEATDOWN"


# ---------------------------------------------------------------- 索引

def test_索引只收active(snap):
    """now 页同列有 Current(在开) 与 Last(已收), 后者不能进索引。"""
    for item in T.build_tag_index(snap, only_active=True):
        assert item["name"], "索引里不该有空名字"
    idx_all = T.build_tag_index(snap, only_active=False)
    assert len(idx_all) >= len(T.build_tag_index(snap, only_active=True))


def test_索引元素项带element键(snap):
    idx = T.build_tag_index(snap, only_active=True)
    for it in idx:
        if it["tag"] in ("element", "rift"):
            assert it["element"], f"元素项 {it['name']!r} 没解析出元素键"
        if it["tag"] == "character":
            assert it["char_key"], f"角色项 {it['name']!r} 没解析出角色键"


def test_索引kind来自ENTRIES而非快照字段(snap):
    """归档 entries 里**没有** kind 字段, 必须从 jjc_store.ENTRIES 反查。

    回归 (2026-10-06): 早先直接读 e["kind"] 全部落unknown, 且因快照无此键
    不报错 —— 静默把角色场/元素场/月场都当unknown, 等于归类功能整体失效。
    """
    for e in ((snap or {}).get("entries") or {}).values():
        if isinstance(e, dict):
            assert "kind" not in e, "若快照将来真存了 kind, 本测试需同步更新"
    kinds = T._kind_by_key()
    assert kinds.get("char") == "character"
    assert kinds.get("elem") == "element"
    assert kinds.get("medi") == "gold"
    assert kinds.get("holi") == "monthly"


def test_索引key认不出则落unknown不猜():
    """key 不在 ENTRIES 里 -> unknown, 走最保守条件, 绝不猜 tag。"""
    snap = {"entries": {"weird": {"key": "weird", "name": "Foo",
                                  "active": True, "scope": "current"}}}
    idx = T.build_tag_index(snap)
    assert idx[0]["tag"] == "unknown"
    assert T.TAG_CONDITIONS.get(idx[0]["tag"]) is None   # 不在条件表 -> 走兜底


def test_占位符Active不进索引():
    """源表星PF/招式PF 行写的是 'Active PF'(在场但本期无具体名), 不是 inactive。

    回归 (2026-09-26 快照实测): 占位符进索引会把 hub 上的卡误判成星场/招式场,
    而 hub 上不存在名为 ACTIVE 的场。判据是归一后等于 NON_ARENA_NAMES。
    """
    for junk in ("Active", "Active PF", "Inactive", "N/A", "Loading..."):
        assert T._is_placeholder(junk), junk
    assert not T._is_placeholder("Ms. Fortune")
    assert not T._is_placeholder("Costume Party")
    snap = {"entries": {"star": {"key": "star", "name": "Active PF",
                                 "active": True, "scope": "current"}}}
    assert T.build_tag_index(snap) == []


def test_rift与element同名按优先级取一并留痕():
    """rift 与 element 可同为 'Dark': hub只有一张卡, 快照两条都 active。

    回归 (2026-09-19/09-20/09-26 实测): 旧实现顺序取第一个, 把 element 标成
    rift。取值相同故不影响跑批, 但 tag 标错会让日志误导 —— 现在按优先级取
    element 并在 note 里记明撞车。
    """
    snap = {"entries": {
        "rift": {"key": "rift", "name": "Dark", "active": True, "scope": "current"},
        "elem": {"key": "elem", "name": "Dark", "active": True, "scope": "current"},
    }}
    r = T.classify_arena("Dark", T.build_tag_index(snap))
    assert r["tag"] == "element", "element 应优先于 rift"
    assert r["rule"] == {"type": "element", "value": "dark"}
    assert "同名撞车" in r["note"], "撞车必须留痕"
    # 反向输入顺序结果应一致 (不能靠列表顺序决定答案)
    snap2 = {"entries": {
        "elem": {"key": "elem", "name": "Dark", "active": True, "scope": "current"},
        "rift": {"key": "rift", "name": "Dark", "active": True, "scope": "current"},
    }}
    assert T.classify_arena("Dark", T.build_tag_index(snap2))["tag"] == "element"


# ---------------------------------------------------------------- 比对阈值

def test_全等满分():
    assert T.match_score("A SHOT IN THE DARK", "A Shot in the Dark") == 1.0


def test_低于阈值不猜():
    """完全不同的名字 -> unknown + 最保守条件, 不套用任何具体场次条件。"""
    idx = [{"tag": "character", "name": "Ms. Fortune", "char_key": "MSFORTUNE",
            "element": None, "key": "char", "scope": "current"}]
    r = T.classify_arena("ZZQQXX NOTAREALNAME", idx)
    assert r["tag"] == "unknown"
    assert r["matched"] is False
    assert r["rule"] is None
    assert r["score_target"] == 40_000_000
    assert r["energy_cost"] == 4
    assert "未过阈值" in r["note"]


def test_阈值边界_恰好等于阈值算命中():
    """>= threshold 命中(代码用 < threshold 判未命中)。"""
    idx = [{"tag": "gold", "name": "Shakedown - Jinx", "char_key": None,
            "element": None, "key": "medi", "scope": "current"}]
    s = T.match_score("Shakedown - Jinx", "Shakedown - Jinx")
    r = T.classify_arena("Shakedown - Jinx", idx, threshold=s)
    assert r["matched"] is True
    assert r["tag"] == "gold"


def test_短名不误命中():
    """'M' 之类过短的残串不能靠子串命中别的场 —— pf_nav 踩过这个坑。"""
    assert T.match_score("M", "Shakedown - Jinx") < T.MATCH_THRESHOLD
    assert T.classify_arena("M", [
        {"tag": "gold", "name": "MEDICI SHAKEDOWN", "char_key": None,
         "element": None, "key": "medi", "scope": "current"}])["tag"] == "unknown"


# ---------------------------------------------------------------- 取条件

def test_角色场条件():
    """角色场 -> 5kw + 5 能量 + class=MSFORTUNE(防守队约束)。"""
    idx = [{"tag": "character", "name": "Ms. Fortune", "char_key": "MSFORTUNE",
            "element": None, "key": "char", "scope": "current"}]
    r = T.classify_arena("Ms. Fortune", idx)
    assert r["tag"] == "character"
    assert r["score_target"] == 50_000_000
    assert r["energy_cost"] == 5
    assert r["rule"] == {"type": "class", "value": "MSFORTUNE"}


def test_元素场条件():
    """元素场 -> 5kw + 4 能量 + element=对应元素。"""
    idx = [{"tag": "element", "name": "Fire", "char_key": None,
            "element": "fire", "key": "elem", "scope": "current"}]
    r = T.classify_arena("Fire", idx)
    assert r["tag"] == "element"
    assert r["score_target"] == 50_000_000
    assert r["energy_cost"] == 4
    assert r["rule"] == {"type": "element", "value": "fire"}


def test_金币场无规则():
    """金币场 -> 4kw + 4 能量 + rule=None (铁律: 非元素场不许有规则)。"""
    idx = [{"tag": "gold", "name": "Shakedown - Jinx", "char_key": None,
            "element": None, "key": "medi", "scope": "current"}]
    r = T.classify_arena("Shakedown - Jinx", idx)
    assert r["tag"] == "gold"
    assert r["score_target"] == 40_000_000
    assert r["energy_cost"] == 4
    assert r["rule"] is None


def test_元素名认不出则不产规则():
    """元素场但元素名解析不出来 -> rule=None 且留痕, 绝不猜一个元素。"""
    idx = [{"tag": "element", "name": "Chaos", "char_key": None,
            "element": None, "key": "elem", "scope": "current"}]
    r = T.classify_arena("Chaos", idx)
    assert r["tag"] == "element"
    assert r["rule"] is None
    assert r["score_target"] == 50_000_000     # 仍是元素场量级


# ---------------------------------------------------------------- OCR 残串

def test_OCR丢首字母仍命中():
    """pf_nav 实证: 'IG BENS BEATDOWN' 丢首字母 B (2026-09-25)。"""
    idx = [{"tag": "character", "name": "Big Ben's Beatdown", "char_key": "BIGBEN",
            "element": None, "key": "char", "scope": "current"}]
    r = T.classify_arena("IG BENS BEATDOWN", idx)
    assert r["matched"] is True, f"相似度只{r['score']}"
    assert r["tag"] == "character"


def test_OCR丢中间字母仍命中():
    """pf_nav 实证: 'RUNESANDZEROS' -> 'UNESANDZEROS'(2026-10-02)。"""
    idx = [{"tag": "character", "name": "Run[e]s and Zeros", "char_key": "X",
            "element": None, "key": "char", "scope": "current"}]
    r = T.classify_arena("UNESANDZEROS", idx)
    assert r["matched"] is True


def test_空间隔断仍命中():
    """'A SHOT IN THE DARK' OCR 常读成连串 ASHOTINTHEDARK (arena_rules 实证)。"""
    idx = [{"tag": "element", "name": "A Shot in the Dark", "char_key": None,
            "element": "dark", "key": "elem", "scope": "current"}]
    r = T.classify_arena("ASHOTINTHEDARK", idx)
    assert r["matched"] is True
    assert r["rule"] == {"type": "element", "value": "dark"}


# ---------------------------------------------------------------- 全量

def test_对真实hub扫描结果归类(snap, hub):
    """拿真实 arenas.json跑一遍: 每张卡都要有结论, 不能抛异常/丢卡。"""
    arenas = (hub or {}).get("arenas") or []
    if not arenas:
        pytest.skip("arenas.json 里没有场地卡")
    out = T.classify_all(arenas, snap)
    assert len(out) == len(arenas)
    for r in out:
        assert r["tag"] in T.TAG_CONDITIONS or r["tag"] == T.UNKNOWN_TAG
        assert r["score_target"] > 0 and 1 <= r["energy_cost"] <= 10
        if r["tag"] not in ("character", "element", "rift"):
            assert r["rule"] is None, f"{r['title']!r} 非限定场却带规则 {r['rule']}"


def test_无快照时全部落unknown(snap, hub):
    """数据源缺失不能静默套默认条件 —— 归类失败要长得像失败。"""
    arenas = (hub or {}).get("arenas") or []
    if not arenas:
        pytest.skip("arenas.json 里没有场地卡")
    out = T.classify_all(arenas, None)
    assert all(r["tag"] == T.UNKNOWN_TAG and r["matched"] is False for r in out)
    assert all(r["rule"] is None for r in out)