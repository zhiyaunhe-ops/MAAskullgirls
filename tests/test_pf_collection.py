"""pf_collection 聚合/评分/导出 与 pf_roster 自适应间隔自检 (2026-10-14)。

纯函数测试: 不连设备、不依赖真实 roster.json (数据全用合成卡表)。
跑法: python -m pytest tests/test_pf_collection.py -q
"""
import json
import re
import sys
from pathlib import Path

TOOLS = Path(__file__).resolve().parent.parent / "tools"
sys.path.insert(0, str(TOOLS))

import pf_collection as C                                     # noqa: E402
import pf_fighters as F                                       # noqa: E402
import pf_roster as R                                         # noqa: E402


def _card(name):
    return {"row": "row1", "gx": 0, "name": name, "char": None,
            "base": None, "strip": ""}


# ---------- variant_of ----------

def test_variant_of_exact_and_fuzzy():
    e, how = F.variant_of("DJINN FRIZZ")          # OCR 大写也能精确命中 (归一化)
    assert how == "exact" and e["fandom"] == "Djinn Frizz"
    assert e["tier"] == 3 and e["base"] == "fi"
    e2, how2 = F.variant_of("IYHOT")              # OCR 丢字母 -> fuzzy 纠回
    assert how2.startswith("fuzzy") and e2["fandom"] == "Icy Hot"
    assert F.variant_of("FALL OF THE DARK")[0] is None    # 场次卡不是变体, 不硬猜


def test_variant_of_never_guesses_garbage():
    assert F.variant_of("ZZZZZZ")[0] is None
    assert F.variant_of("")[1] == "empty"


# ---------- sa_title / 文案分层 ----------

def test_sa_title_official_vs_derived():
    texts = C._load_texts()
    official = [e for e in F._load()["entry_by_name"].values()
                if e.get("sa", {}).get("title") in texts["en"]]
    assert official, "文案表应至少覆盖部分 SA"
    e = official[0]
    t, flag = C.sa_title(e)
    assert flag is True and t == texts["en"][e["sa"]["title"]].strip()
    # 官方缺失 -> 从 key 驼峰分词推导, 不抛错
    for e2 in F._load()["entry_by_name"].values():
        if e2.get("sa", {}).get("title") not in texts["en"]:
            t2, flag2 = C.sa_title(e2)
            assert flag2 is False and t2 and "Char_" not in t2
            return


def test_fill_desc_substitutes_tier3_values():
    desc, feat = "Deal {0}% damage for {1} seconds", {
        "tiers": [{"level": 1, "values": [50.0, 7.0]},
                  {"level": 3, "values": [100.0, 7.0]}]}
    assert C._fill_desc(desc, feat) == "Deal 100.0% damage for 7.0 seconds"
    # 填不了 (占位符比值多) -> 原样返回, 不抛
    assert C._fill_desc("{0} {1} {2}", {"tiers": [{"values": [1.0]}]}) == "{0} {1} {2}"


# ---------- aggregate ----------

def test_aggregate_counts_duplicates_per_variant():
    cards = [_card("NUNSENSE"), _card("NUNSENSE"), _card("NUNSENSE"),
             _card("DJINN FRIZZ"), _card("IYHOT")]     # OCR 噪声也要并回同一变体
    agg = C.aggregate(cards)
    assert agg["total_cards"] == 5 and agg["variant_kinds"] == 3
    by = {v["fandom"]: v for v in agg["variants"]}
    assert by["Nunsense"]["count"] == 3                 # 同名卡是真副本, 必须计数
    assert by["Icy Hot"]["count"] == 1
    assert agg["unmapped"] == []


def test_aggregate_unmapped_keeps_names():
    agg = C.aggregate([_card("FALL OF THE DARK"), _card("ZZZZ")])
    assert agg["variant_kinds"] == 0 and agg["unmapped"] == ["FALL OF THE DARK", "ZZZZ"]


def test_aggregate_tier_buckets_partition():
    agg = C.aggregate([_card(n) for n in ("DJINN FRIZZ", "REGALLY BLONDE", "SCRUB")])
    assert sum(agg["by_tier"].values()) == 3
    tiers = {v["fandom"]: v["tier"] for v in agg["variants"]}
    assert tiers == {"Djinn Frizz": 3, "Regally Blonde": 2, "Scrub": 0}


# ---------- score ----------

def test_score_known_small_collection():
    agg = C.aggregate([_card("DJINN FRIZZ")])          # 1 变体 / 1 角色
    sc = C.score(agg)
    assert sc["chars_covered"] == 1
    assert sc["completeness"] == round(100 / F.loaded_size(), 1)
    assert sc["coverage"] == round(100 / len(F.CHAR_DISPLAY), 1)
    assert sc["total"] <= 20                            # 一张卡只能拿最末档
    assert sc["grade"] == "E"


def test_score_full_diamond_catalog_ceil():
    """全图鉴 306 变体 -> 满分 100 / S (评分口径的分母自洽性)。"""
    idx = F._load()
    agg = C.aggregate([_card(e["fandom"]) for e in idx["entry_by_name"].values()])
    sc = C.score(agg)
    assert sc["total"] == 100.0 and sc["grade"] == "S"


# ---------- export_html ----------

def test_export_html_embeds_escaped_json():
    agg = C.aggregate([_card("DJINN FRIZZ"), _card("NUNSENSE")])
    html = C.export_html(agg, C.score(agg))
    m = re.search(r"const DATA=(\{.*?\});\n", html, re.S)
    assert m
    data = json.loads(m.group(1).replace("<\\/", "</"))
    assert data["variant_kinds"] == 2
    assert "__DATA__" not in html and "__GRADE__" not in html   # 占位符全被替换


# ---------- pf_roster 自适应间隔自检 ----------

def _summary_with_gaps(gaps):
    """构造一个 Store, 让某行的 gx 序列产生指定相邻间隔。"""
    st = R.Store()
    for i, g in enumerate(gaps):
        st.cards.append({"row": "row1", "gx": i * g, "cx": 0, "name": f"N{i}",
                         "char": None, "base": None, "strip": ""})
    return st.summary()


def test_gap_check_adapts_to_wider_pitch():
    """游戏内筛选后若出现更大的列距, 不得误报 miss (旧固定阈值会)。"""
    s = _summary_with_gaps([320] * 9)
    assert s["miss_gaps"] == [] and s["dup_gaps"] == []


def test_gap_check_still_catches_dup_when_median_collapses():
    """去重真坏掉 (一串 gap≈0) 时中位数会跟着塌 —— 必须仍能报 dup。"""
    s = _summary_with_gaps([0, 0, 0, 0, 0])
    assert len(s["dup_gaps"]) == 4 and s["miss_gaps"] == []
