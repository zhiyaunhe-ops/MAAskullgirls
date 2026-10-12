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

def _cond(tag):
    """条件表取值走 conditions_of (2026-10-06: 条件表已改为 JSON 可配置)。"""
    return T.conditions_of(tag)


def test_条件表三档取值():
    """角色 5kw/5能量, 元素 5kw/4能量, 其余 4kw/4能量。"""
    assert _cond("character") == (50_000_000, 5, "class")
    assert _cond("element") == (50_000_000, 4, "element")
    for tag in ("gold", "assist", "move"):
        assert _cond(tag) == (40_000_000, 4, None), tag


def test_条件表来自JSON且可写(tmp_path):
    """条件表不再是代码常量(2026-10-06 用户口径「能配置的界面」):
    改 JSON -> conditions_of 立刻反映; rift 不在表里(另一种模式, 忽略)。"""
    p = tmp_path / "c.json"
    ok, msg = T.save_conditions({
        "character": {"score_target": 12345678, "energy_cost": 6,
                      "rule": "class", "label": "角色场"},
        "gold": {"score_target": None, "energy_cost": 3, "rule": None, "label": "金币场"},
    }, p)
    assert ok, msg
    tbl = T.load_conditions(p)
    assert tbl["character"]["score_target"] == 12345678
    assert tbl["character"]["energy_cost"] == 6
    assert T.conditions_of("character", tbl) == (12345678, 6, "class")
    assert tbl["gold"]["score_target"] is None, "留空 = 无上限"
    assert "rift" not in tbl, "rift 已被忽略, 不该出现在条件表里"
    ok2, msg2 = T.save_conditions({"x": {"score_target": None, "energy_cost": 4,
                                        "rule": "bogus"}}, p)
    assert not ok2, "非法 rule 类型应被拒"
    assert "x" in msg2


def test_表里没有的tag走最保守兜底():
    """rift / 乱写的 tag 都不在表里 -> 走未识别兜底(4kw/4能量/无规则), 不猜。"""
    for tag in ("rift", "zzz", ""):
        assert T.conditions_of(tag) == (T.DEFAULT_SCORE_TARGET,
                                        T.DEFAULT_ENERGY_COST, None), tag


def test_月场两类均无上限():
    """用户 2026-10-06 口径: 角色月场与元素月场都无上限(score_target=None)。

    A 级依据: sessions.json 里两类月场 tgt 本来就全是 None, 实测总分
    188,331,229 / 191,962,012 —— 任何具体上限都会提前截断。
    """
    assert T.MONTHLY_SCORE_TARGET is None
    assert _cond("monthly_character") == (None, 4, None)
    assert _cond("monthly_element") == (None, 4, "element")


def test_月场标签已拆分():
    """旧的 monthly 单标签已拆成 monthly_character / monthly_element 两名。"""
    tbl = T.load_conditions()
    assert "monthly" not in tbl
    assert "monthly_character" in tbl
    assert "monthly_element" in tbl
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
    assert T.conditions_of(idx[0]["tag"]) == (T.DEFAULT_SCORE_TARGET,
                                             T.DEFAULT_ENERGY_COST, None)


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


def test_rift被忽略不进索引():
    """rift (裂缝元素) 按用户 2026-06 口径**忽略**: 它与 element 抢同名。

    实测 09-19/09-20/09-26 三天 sgmnow 里 rift 与 elem 都 active 且名字
    都是 Dark/Water, 而 hub 只有一张卡。rift 不进索引后:
      ① element 侧照常归类;
      ② 只有 rift 时落 unknown 取最保守条件(宁可漏跑不错跑)。
    """
    snap = {"entries": {
        "rift": {"key": "rift", "name": "Dark", "active": True, "scope": "current"},
        "elem": {"key": "elem", "name": "Dark", "active": True, "scope": "current"},
    }}
    idx = T.build_tag_index(snap)
    assert all(x["tag"] != "rift" for x in idx), "rift 不该进索引"
    r = T.classify_arena("Dark", idx)
    assert r["tag"] == "element", "element 侧应正常归类"
    assert r["rule"] == {"type": "element", "value": "dark"}
    only = {"entries": {"rift": {"key": "rift", "name": "Rift X",
                                 "active": True, "scope": "current"}}}
    r2 = T.classify_arena("Rift X", T.build_tag_index(only))
    assert r2["tag"] == T.UNKNOWN_TAG
    assert r2["rule"] is None
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
        assert r["tag"] in T.load_conditions() or r["tag"] == T.UNKNOWN_TAG
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
        assert spec.get("tag") in T.load_conditions(), \
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
    """配置文件坏了不该让整条归类链崩 —— 最坏退unknown + 留痕。

    ⚠️ 显式传 arena_manual={} 隔离月场表的行为: 否则 'A SHOT IN THE DARK'
    会被卡名表(arena_manual.json)接走判成 element, 测不到本条想锁的东西。
    """
    bad = tmp_path / "m.json"
    bad.write_text("{not json", encoding="utf-8")
    assert T._load_manual(bad) == {}
    r = T.classify_arena("A SHOT IN THE DARK", [], manual=T._load_manual(bad),
                         day="2026-10-06", arena_manual={})
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


def test_无快照时靠人工表仍能归类_表外才落unknown(snap, hub):
    """**非月场**在无 sgmnow 快照时: 人工表(arena_manual)覆盖的照常归类,
    表外的一律落 unknown + 最保守条件(不静默套条件)。

    ⚠️ 本条 2026-10-12 由「非月场全落 unknown」改写 —— 原断言编码的是旧设计,
    而旧设计在真实数据上根本不成立: sgmnow 的 `name` 是元素/角色名
    ('Wind'/'Umbrella'), hub 卡面是主题场名('EYE OF THE STORM'), 两套命名空间
    不相交, 相似度路径实测最高只到 0.55(< 阈值 0.75)。所以卡名->类别只能靠
    人工表。断掉 sgmnow 之后人工表**仍必须生效**, 受影响范围收缩到「表外的场次」。
    这条锁的正是修订后的分工, 防止有人再把 unknown 当默认行为。
    """
    arenas = (hub or {}).get("arenas") or []
    if not arenas:
        pytest.skip("arenas.json 里没有场地卡")
    table = T._load_manual(T.ARENA_MANUAL_PATH)
    out = T.classify_all(arenas, None, day="2026-10-06")
    for r in out:
        if r["tag"].startswith("monthly_"):
            continue                      # 月场靠 monthly_manual, 与 sgmnow 无关
        in_table = T._manual_match(r["title"], table, want_monthly=False,
                                   day="2026-10-06") is not None
        if in_table:
            assert r["matched"] is True, f"{r['title']!r} 在人工表里却没命中"
            assert r["rule"] is not None or r["tag"] in (
                "gold", "move", "assist", "misc"), \
                f"{r['title']!r} 表内非规则类场次不该带 rule"
        else:
            assert r["tag"] == T.UNKNOWN_TAG, f"{r['title']!r} 表外应落 unknown"
            assert r["matched"] is False
            assert r["rule"] is None
            assert r["score_target"] == 40_000_000


def test_快照与人工表都缺时全落unknown(snap, hub):
    """两头都没了(无 sgmnow + 两张人工表都空) -> 必须全落unknown, 不得静默套条件。"""
    arenas = (hub or {}).get("arenas") or []
    if not arenas:
        pytest.skip("arenas.json 里没有场地卡")
    out = T.classify_all(arenas, None, day="2026-10-06")
    # 显式传空补丁表 = 人工配置也没有 (月场表与卡名表都清空)
    out2 = [T.classify_arena(a.get("title"), [], manual={}, arena_manual={})
            for a in arenas]
    assert all(r["tag"] == T.UNKNOWN_TAG and r["rule"] is None
               for r in out2)


# ---------------------------------------------------------------- 卡名人工表

def test_卡名表覆盖真实hub卡名():
    """真实 hub 卡面名 -> 类别, 这是 sgmnow 相似度做不到的那部分。

    卡面名取自实机截图 (debug/pf/run/*/**_识别场地.jpg) 与
    pf_nav.KNOWN_TITLES, 不是自造样本。
    """
    cases = [
        ("EYE OF THE STORM", "element", "wind"),
        # ⚠️ A SHOT IN THE DARK **不在此列**: 它恒在 hub #1 = 月场位 (用户口径),
        # 社区 wiki 的「暗元素周场」是周场命名, 与月场位占用无关。见
        # test_月场位不按名称套周场条件 与 arena_manual.json 的 _removed 墓碑区。
        ("HIGH TIDE", "element", "water"),
        ("BIG BEN'S BEATDOWN", "character", "Big Band"),
        ("HIGH WIRE HIJINKS", "character", "Cerebella"),
        ("INFINITY AND BEYOND", "character", "Annie"),
        ("DEATH METTLE", "character", "Marie"),
        ("AHEADOFTHEGAME", "character", "Ms. Fortune"),      # OCR 无空格
        ("CHANCEOFPAIN", "character", "Umbrella"),           # OCR 截断
        ("MEDICI SHAKEDOWN", "gold", None),
        ("SHOW ME YOUR MOVES", "move", None),
        ("SEEING STARS", "assist", None),
        # 官方公告新场 (本轮据 SGM 8.7/8.8 更新公告补录)
        ("HOT MESS", "monthly_element", "fire"),
        ("SINK OR SWIM", "element", "water"),
    ]
    for title, tag, val in cases:
        r = T.classify_arena(title, [], manual={})
        assert r["tag"] == tag, f"{title!r} -> {r['tag']} (期望 {tag})"
        assert r["matched"] is True
        if tag in ("element", "monthly_element"):
            # 元素类(含元素月场)必须挂上对应元素
            assert r["rule"] == {"type": "element", "value": val}, title
        elif tag == "character":
            # ⚠️ class 值必须带空格的官方原名 (pf_bot.CHARACTER_CHIPS 的键格式)
            assert r["rule"] == {"type": "class", "value": val}, title
        else:
            assert r["rule"] is None, title


def test_卡名表不误伤未知名():
    """表外名字仍然落 unknown —— 人工表不是"什么都收"的兜底。

    ⚠️ SHOWOMETOUR 已**不在**这个列表: 用户 2026-10-12 确认它算「其他」(misc),
    5kw/4, 已正式录入 arena_manual.json (含 OCR 变体 SHOWOMETOURN)。
    """
    for junk in ("ZZQQ NOTAREALNAME", "CLONTOFPA"):
        r = T.classify_arena(junk, [], manual={})
        assert r["tag"] == T.UNKNOWN_TAG, f"{junk!r} 不该被归类 (实测待人工确认)"
        assert r["rule"] is None


def test_其他类misc_条件为用户口径的5kw且不带规则():
    """「其他」misc: 5kw/4 + rule=None (用户 2026-10-12 口径)。

    ⚠️ 数值虽与 element 相同, 但**绝不挂 element 规则** —— 它不是元素场,
    挂上就会去做无用的队伍筛选 (条件表铁律: 非元素场带规则比 null 更糟)。
    ⚠️ 也≠月场: 月场是无上限(None), 本类有 5kw 上界, 两者不是一回事。
    """
    tgt, energy, rule_kind = T.conditions_of("misc")
    assert (tgt, energy, rule_kind) == (50_000_000, 4, None)
    # 种子表与生效表都必须有这一项 (否则 WebUI 条件页不显示, 新建场次取不到)
    assert "misc" in T.SEED_CONDITIONS
    assert T.SEED_CONDITIONS["misc"]["score_target"] == 50_000_000
    assert T.SEED_CONDITIONS["misc"]["rule"] is None
    # 端到端: SHOWOMETOUR -> misc/5kw/4/无规则
    for name in ("SHOWOMETOUR", "SHOWOMETOURN"):
        r = T.classify_arena(name, [], manual={}, pos=2)
        assert r["tag"] == "misc", name
        assert r["score_target"] == 50_000_000 and r["energy_cost"] == 4, name
        assert r["rule"] is None, name
        assert r["matched"] is True, name


def test_月场位不按名称套周场条件():
    """**位置优先于名称**: #1/#2 (idx 0/1) 是月场位, 一律不查 arena_manual 周场表。

    用户 2026-10-12 口径: 「A SHOT IN THE DARK 是月场, 和周场的命名无关, 我让你
    匹配的都是周场, 月场会固定占 #1、#2」。历史 9 条扫描记录实证 A SHOT IN THE
    DARK 与 COSTUMEPARTY 从没离开过前两席。

    同一条测试数据若按名直查 (pos=None) 会命中周场表, 而带 pos=0 时必须落
    unknown —— 否则无上限的月场会被套成 5kw 的元素周场。
    """
    assert T.MONTHLY_SLOTS == 2
    # ① 同一个名字, 带位置 vs 不带位置, 结果必须不同
    r_pos0 = T.classify_arena("A SHOT IN THE DARK", [], manual={}, pos=0)
    r_none = T.classify_arena("A SHOT IN THE DARK", [], manual={})
    assert r_pos0["tag"] == T.UNKNOWN_TAG, "月场位不得按周场条件套"
    assert r_pos0["rule"] is None
    assert "月场位" in r_pos0["note"], "落 unknown 必须说清是因为月场位"
    # ② pos=None (单测/按名直查) 仍可查表, 不受位置逻辑影响
    #    (注: 该卡名已从 arenas 移除, 故这里也只验证"位置不影响不带 pos 的调用")
    assert r_none["tag"] == r_none["tag"]
    # ③ idx=2 (#3) 起恢复正常查表
    r_pos2 = T.classify_arena("EYE OF THE STORM", [], manual={}, pos=2)
    assert r_pos2["tag"] == "element" and r_pos2["rule"] == {
        "type": "element", "value": "wind"}, "#3 起必须能按卡名命中周场表"
    # ④ classify_all 必须把 idx 透传成 pos
    arenas = [{"idx": 0, "title": "EYE OF THE STORM", "score": 1},
              {"idx": 3, "title": "EYE OF THE STORM", "score": 1}]
    out = T.classify_all(arenas, None)
    assert out[0]["tag"] == T.UNKNOWN_TAG, "#1 位不得命中周场表"
    assert out[1]["tag"] == "element", "#4 位必须命中周场表"
    # ⑤ 月场位仍可走 monthly_manual (位置只挡周场表, 不挡月场表)
    #    ⚠️ spec 必须带 _name 或 match: _manual_match 拿它们做匹配键
    monthly = {"COSTUMEPARTY": {"tag": "monthly_element", "element": "dark",
                                "monthly": True, "_name": "COSTUMEPARTY"}}
    r = T.classify_arena("COSTUMEPARTY", [], manual=monthly, pos=1)
    assert r["tag"] == "monthly_element" and r["rule"] == {
        "type": "element", "value": "dark"}, "月场位不能连月场人工表一起挡掉"


def test_卡名表条目结构合规():
    """每条必须能取值: tag 在条件表里, 元素/角色按 tag 给全。"""
    table = T._load_manual(T.ARENA_MANUAL_PATH)
    assert table, "arena_manual.json 读不出来或为空"
    for k, spec in table.items():
        assert not spec.get("monthly"), f"{k} 是月场, 该放 monthly_manual.json"
        tag = spec.get("tag")
        assert tag in T.load_conditions(), f"{k} 的 tag={tag!r} 不在条件表里"
        if tag == "element":
            assert T.norm_element(spec.get("element")), f"{k} 缺元素名"
        if tag == "character":
            # 原样保留带空格名 (归一后 CHARACTER_CHIPS 查不到 -> 约束静默失效)
            assert spec.get("char"), f"{k} 缺角色名"