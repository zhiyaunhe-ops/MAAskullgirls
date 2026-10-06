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
    for tag in ("gold", "assist", "move"):
        assert T.TAG_CONDITIONS[tag] == (40_000_000, 4, None), tag


def test_月场两类均无上限():
    """用户 2026-10-06 口径: 角色月场与元素月场都无上限(score_target=None)。

    A 级依据: sessions.json 里两类月场 tgt 本来就全是 None, 实测总分
    188,331,229 / 191,962,012 —— 任何具体上限都会提前截断。
    """
    assert T.MONTHLY_SCORE_TARGET is None
    assert T.TAG_CONDITIONS["monthly_character"] == (None, 4, None)
    assert T.TAG_CONDITIONS["monthly_element"] == (None, 4, "element")


def test_月场标签已拆分():
    """旧的 monthly 单标签已拆成 monthly_character / monthly_element 两名。"""
    assert "monthly" not in T.TAG_CONDITIONS
    assert "monthly_character" in T.TAG_CONDITIONS
    assert "monthly_element" in T.TAG_CONDITIONS
    # 优先级表也要跟着更新, 否则 _pick() 会因index 越界而崩
    for tag in ("monthly_character", "monthly_element"):
        assert tag in T._TAG_PRIORITY


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
    r = T.classify_arena("Dark", T.build_tag_index(snap), manual={})
    assert r["tag"] == "element", "element 应优先于 rift"
    assert r["rule"] == {"type": "element", "value": "dark"}
    assert "同名撞车" in r["note"], "撞车必须留痕"
    # 反向输入顺序结果应一致 (不能靠列表顺序决定答案)
    snap2 = {"entries": {
        "elem": {"key": "elem", "name": "Dark", "active": True, "scope": "current"},
        "rift": {"key": "rift", "name": "Dark", "active": True, "scope": "current"},
    }}
    assert T.classify_arena("Dark", T.build_tag_index(snap2), manual={})["tag"] == "element"


# ---------------------------------------------------------------- 比对阈值

def test_全等满分():
    assert T.match_score("A SHOT IN THE DARK", "A Shot in the Dark") == 1.0


def test_低于阈值不猜():
    """完全不同的名字 -> unknown + 最保守条件, 不套用任何具体场次条件。"""
    idx = [{"tag": "character", "name": "Ms. Fortune", "char_key": "MSFORTUNE",
            "element": None, "key": "char", "scope": "current"}]
    r = T.classify_arena("ZZQQXX NOTAREALNAME", idx, manual={})
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
    r = T.classify_arena("Shakedown - Jinx", idx, threshold=s, manual={})
    assert r["matched"] is True
    assert r["tag"] == "gold"


def test_短名不误命中():
    """'M' 之类过短的残串不能靠子串命中别的场 —— pf_nav 踩过这个坑。"""
    assert T.match_score("M", "Shakedown - Jinx") < T.MATCH_THRESHOLD
    assert T.classify_arena("M", [
        {"tag": "gold", "name": "MEDICI SHAKEDOWN", "char_key": None,
         "element": None, "key": "medi", "scope": "current"}], manual={})["tag"] == "unknown"


# ---------------------------------------------------------------- 取条件

def test_角色场条件():
    """角色场 -> 5kw + 5 能量 + class=MSFORTUNE(防守队约束)。"""
    idx = [{"tag": "character", "name": "Ms. Fortune", "char_key": "MSFORTUNE",
            "element": None, "key": "char", "scope": "current"}]
    r = T.classify_arena("Ms. Fortune", idx, manual={})
    assert r["tag"] == "character"
    assert r["score_target"] == 50_000_000
    assert r["energy_cost"] == 5
    assert r["rule"] == {"type": "class", "value": "MSFORTUNE"}


def test_元素场条件():
    """元素场 -> 5kw + 4 能量 + element=对应元素。"""
    idx = [{"tag": "element", "name": "Fire", "char_key": None,
            "element": "fire", "key": "elem", "scope": "current"}]
    r = T.classify_arena("Fire", idx, manual={})
    assert r["tag"] == "element"
    assert r["score_target"] == 50_000_000
    assert r["energy_cost"] == 4
    assert r["rule"] == {"type": "element", "value": "fire"}


def test_金币场无规则():
    """金币场 -> 4kw + 4 能量 + rule=None (铁律: 非元素场不许有规则)。"""
    idx = [{"tag": "gold", "name": "Shakedown - Jinx", "char_key": None,
            "element": None, "key": "medi", "scope": "current"}]
    r = T.classify_arena("Shakedown - Jinx", idx, manual={})
    assert r["tag"] == "gold"
    assert r["score_target"] == 40_000_000
    assert r["energy_cost"] == 4
    assert r["rule"] is None


def test_元素名认不出则不产规则():
    """元素场但元素名解析不出来 -> rule=None 且留痕, 绝不猜一个元素。"""
    idx = [{"tag": "element", "name": "Chaos", "char_key": None,
            "element": None, "key": "elem", "scope": "current"}]
    r = T.classify_arena("Chaos", idx, manual={})
    assert r["tag"] == "element"
    assert r["rule"] is None
    assert r["score_target"] == 50_000_000     # 仍是元素场量级


# ---------------------------------------------------------------- OCR 残串

def test_OCR丢首字母仍命中():
    """pf_nav 实证: 'IG BENS BEATDOWN' 丢首字母 B (2026-09-25)。"""
    idx = [{"tag": "character", "name": "Big Ben's Beatdown", "char_key": "BIGBEN",
            "element": None, "key": "char", "scope": "current"}]
    r = T.classify_arena("IG BENS BEATDOWN", idx, manual={})
    assert r["matched"] is True, f"相似度只{r['score']}"
    assert r["tag"] == "character"


def test_OCR丢中间字母仍命中():
    """pf_nav 实证: 'RUNESANDZEROS' -> 'UNESANDZEROS'(2026-10-02)。"""
    idx = [{"tag": "character", "name": "Run[e]s and Zeros", "char_key": "X",
            "element": None, "key": "char", "scope": "current"}]
    r = T.classify_arena("UNESANDZEROS", idx, manual={})
    assert r["matched"] is True


def test_空间隔断仍命中():
    """'A SHOT IN THE DARK' OCR 常读成连串 ASHOTINTHEDARK (arena_rules 实证)。"""
    idx = [{"tag": "element", "name": "A Shot in the Dark", "char_key": None,
            "element": "dark", "key": "elem", "scope": "current"}]
    r = T.classify_arena("ASHOTINTHEDARK", idx, manual={})
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
        assert r["energy_cost"] >= 1 and r["energy_cost"] <= 10
        if r["tag"] not in ("character", "element", "rift", "monthly_element"):
            assert r["rule"] is None, f"{r['title']!r} 非限定场却带规则 {r['rule']}"


# ---------------------------------------------------------------- 月场人工补丁

def test_月场不在sgmnow索引里():
    """holi 只有名字、没类别, 进索引只会产出没法再分的 monthly -> 必须剔出。"""
    snap = {"day": "2026-10-05", "entries": {
        "holi": {"key": "holi", "name": "Costume Party",
                 "active": True, "scope": "current"}}}
    assert T.build_tag_index(snap) == []


def test_月场人工表三类条目结构合规():
    """人工表是纯手写的 JSON, 得校验 tag 都在条件表里(否则取值静默退兜底)。"""
    m = T._load_manual()
    assert m, "monthly_manual.json 读不出来或为空"
    for k, spec in m.items():
        assert spec.get("monthly") is True, f"{k} 缺 monthly=true"
        assert spec.get("tag") in T.TAG_CONDITIONS, \
            f"{k} 的 tag={spec.get('tag')!r} 不在条件表里"


def test_元素月场本月是暗():
    """用户 2026-10-06 口径: 本月(COSTUMEPARTY)元素月场 =暗。"""
    m = T._load_manual()
    r = T.classify_arena("COSTUMEPARTY", [], manual=m, day="2026-10-06")
    assert r["tag"] == "monthly_element"
    assert r["rule"] == {"type": "element", "value": "dark"}
    assert r["score_target"] is None, "月场无上限"
    assert r["energy_cost"] == 4
    assert "人工配置" in r["note"], "必须标出是人工来的, 不是推断的"


def test_角色月场无规则无上限():
    """A CLASS OF ONE'S OWN = 角色月场: rule=None + 无上限。"""
    m = T._load_manual()
    r = T.classify_arena("A CLASS OF ONE'S OWN", [], manual=m, day="2026-09-15")
    assert r["tag"] == "monthly_character"
    assert r["rule"] is None
    assert r["score_target"] is None
    assert r["energy_cost"] == 4


def test_元素月场_上月_挂wind():
    """9 月那条是 AGAINST THE WIND = wind (用户 2026-09-16 口径, 有场次实证)。"""
    m = T._load_manual()
    r = T.classify_arena("AGAINST THE WIND", [], manual=m, day="2026-09-20")
    assert r["tag"] == "monthly_element"
    assert r["rule"] == {"type": "element", "value": "wind"}
    assert r["score_target"] is None


def test_补丁过期即失效不静默沿用():
    """valid_until 是防呆: 3 月的配置不该在 4 月悄悄生效。"""
    m = T._load_manual()
    # 9 月那条已标valid_until=2026-10-01
    assert T.classify_arena("A CLASS OF ONE'S OWN", [], manual=m,
                            day="2026-10-06")["tag"] == T.UNKNOWN_TAG
    assert T.classify_arena("AGAINST THE WIND", [], manual=m,
                            day="2026-10-06")["tag"] == T.UNKNOWN_TAG
    # 未过期时正常
    assert T.classify_arena("A CLASS OF ONE'S OWN", [], manual=m,
                            day="2026-09-20")["tag"] == "monthly_character"


def test_补丁有效期边界_到期当天即失效():
    """valid_until 当天就不再有效(闭区间开区间: day >= until 即失效)。"""
    m = T._load_manual()
    spec = m[T._key("COSTUMEPARTY")]
    assert spec["valid_from"] == "2026-10-01"
    assert T._manual_valid(spec, "2026-10-01") is True
    assert T._manual_valid(spec, "2026-10-31") is True
    assert T._manual_valid(spec, "2026-11-01") is False
    assert T._manual_valid(spec, "2026-09-30") is False


def test_月场补丁走名字互为子串_承OCR残串实证():
    """'COSTUMEPARTY' OCR 常读成无空格, 人工表匹配也要吃这一形态。"""
    m = T._load_manual()
    r = T.classify_arena("Costume Party", [], manual=m, day="2026-10-06")
    assert r["tag"] == "monthly_element"
    r2 = T.classify_arena("COSTUMEPARTY", [], manual=m, day="2026-10-06")
    assert r2["tag"] == "monthly_element"


def test_补丁表坏了不崩且退最保守(tmp_path):
    """配置文件坏了不该让整条归类链崩 —— 最坏退unknown + 留痕。"""
    bad = tmp_path / "m.json"
    bad.write_text("{not json", encoding="utf-8")
    assert T._load_manual(bad) == {}
    r = T.classify_arena("A SHOT IN THE DARK", [], manual=T._load_manual(bad),
                         day="2026-10-06")
    assert r["tag"] == T.UNKNOWN_TAG
    assert r["score_target"] == 40_000_000


def test_人工表缺元素名则不挂规则并留痕():
    """元素月场但没填元素 -> rule=None 且note 里告警, 绝不猜一个元素。"""
    manual = {"M": {"_name": "M", "monthly": True, "tag": "monthly_element",
                    "element": None, "valid_from": "2026-10-01",
                    "valid_until": "2026-11-01"}}
    r = T.classify_arena("M", [], manual=manual, day="2026-10-06")
    assert r["tag"] == "monthly_element"
    assert r["rule"] is None
    assert r["score_target"] is None
    assert "缺元素名" in r["note"]


def test_中文名归一后不可区分_必须靠match字段():
    """回归 (2026-10-06 迁移实测): _key() 只留 [A-Z0-9], 中文被滤光。

    '202609元素月场' 与 '202609角色月场' 归一后**都是 '202609'** ——
    早先拿 _name 建索引, 结果**角色月场被判成元素月场**并挂上 wind 规则。
    ⇒ 含中文的本地名必须显式写进 match 列表。
    """
    assert T._key("202609元素月场") == T._key("202609角色月场") == "202609"
    m = T._load_manual()
    # 角色月场不能被元素月场那条接走
    r = T.classify_arena("202609角色月场", [], manual=m, day="2026-09-26")
    assert r["tag"] != "monthly_element", "角色月场被误判成元素月场"
    assert r["rule"] is None, f"角色月场不该挂 element 规则, 实={r['rule']}"
    assert r["score_target"] is None
    # 元素月场才挂 wind
    r2 = T.classify_arena("202609元素月场", [], manual=m, day="2026-09-26")
    assert r2["tag"] == "monthly_element"
    assert r2["rule"] == {"type": "element", "value": "wind"}


def test_match列表可一对多():
    """一个补丁条目可对应多个本地自起名(元素月场有 3 个场次名)。"""
    m = T._load_manual()
    for nm in ("202609元素月场", "202609元素月场 09-06", "202609元素月场 09-06 10-02"):
        r = T.classify_arena(nm, [], manual=m, day="2026-09-26")
        assert r["tag"] == "monthly_element", nm
        assert r["rule"] == {"type": "element", "value": "wind"}, nm
    # 补丁过期(10月)后: 人工表不再命中, 但**中文类别词仍能认出这是月场**
    # —— 这是设计使然: 类别词是 A 级口径(名字自己写着「元素月场」),
    # 与人工表的有效期无关。但元素值拿不到了, 必须 rule=None 且留痕,
    # 绝不能沿用 9 月的 wind(那是上个月的元素)。
    r = T.classify_arena("202609元素月场", [], manual=m, day="2026-10-06")
    assert r["tag"] == "monthly_element"
    assert r["rule"] is None, "过期后不得沿用上月元素"
    assert r["score_target"] is None, "月场仍是无上限"
    assert "元素" in r["note"], "必须留痕说明元素缺失"


def test_纯英文官方名走_name回退():
    """无 match 字段的条目(纯英文官方名)仍按 _name 匹配。"""
    m = T._load_manual()
    r = T.classify_arena("COSTUMEPARTY", [], manual=m, day="2026-10-06")
    assert r["tag"] == "monthly_element"
    assert r["rule"] == {"type": "element", "value": "dark"}


def test_非月场不受补丁影响():
    """人工表只按名字覆盖, 不该把角色场/元素场也带偏。"""
    idx = [{"tag": "character", "name": "Ms. Fortune", "char_key": "MSFORTUNE",
            "element": None, "key": "char", "scope": "current"}]
    r = T.classify_arena("Ms. Fortune", idx, manual=T._load_manual(),
                         day="2026-10-06")
    assert r["tag"] == "character"
    assert r["score_target"] == 50_000_000
    assert r["rule"] == {"type": "class", "value": "MSFORTUNE"}


def test_无快照时非月场全落unknown(snap, hub):
    """**非月场**在无 sgmnow 快照时全部落 unknown, 且不带任何规则。

    月场是例外且**这是设计使然**: 月场类别来自人工补丁表(monthly_manual.json),
    本就不依赖 sgmnow —— sgmnow 的 holi 条目只给名字、给不出类别与元素。
    所以断掉 sgmnow 之后, 月场仍能正确归类, 其余场次必须落 unknown。
    这条测试锁的正是这个分工: 人工配置独立生效, 官方源缺失只影响非月场。
    """
    arenas = (hub or {}).get("arenas") or []
    if not arenas:
        pytest.skip("arenas.json 里没有场地卡")
    out = T.classify_all(arenas, None)
    day = (hub or {}).get("day")
    for r in out:
        if r["tag"].startswith("monthly_"):
            continue                      # 月场靠人工表, 与 sgmnow 无关
        assert r["tag"] == T.UNKNOWN_TAG, f"{r['title']!r} 应落 unknown"
        assert r["matched"] is False
        assert r["rule"] is None
        assert r["score_target"] == 40_000_000


def test_快照与人工表都缺时全落unknown(snap, hub):
    """两头都没了(无 sgmnow + 无补丁表) -> 必须全落unknown, 不得静默套条件。"""
    arenas = (hub or {}).get("arenas") or []
    if not arenas:
        pytest.skip("arenas.json 里没有场地卡")
    out = T.classify_all(arenas, None, day="2026-10-06")
    # 显式传空补丁表 = 人工配置也没有
    out2 = [T.classify_arena(a.get("title"), [], manual={})
            for a in arenas]
    assert all(r["tag"] == T.UNKNOWN_TAG and r["rule"] is None
               for r in out2)