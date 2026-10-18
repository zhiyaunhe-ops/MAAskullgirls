#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把本仓库的场次表 + 轮换规律导出成对外发布的 JSON（sgm-pf 数据集）。

产物（写到 --out 目录）：
  data/arenas.json    场次目录：名字 → 类别 / 限制 / 本项目条件（APK 直接吃这张）
  data/schedule.json  未来 N 周的排期（由轮换公式算出，不是手抄）

依据分级（⚠️ 2026-10-18 用户口径：**本机场次记录只能做一般性验证，不能当权威** ——
因为场次识别链路是最近才开发的，误识别/漏识别都可能出现）：
  A 官方公告   hub.skullgirlsmobile.com/updates/*（唯一 A 级事实源）
  A 官方论坛   forum.skullgirlsmobile.com/threads/prize-fight-calendar.22233（角色九对 + 元素顺序）
  B 社区 wiki  skullgirlsmobilewiki.miraheze.org/wiki/Prize_Fight（时长 / 限制 / 修饰符）
  C 本项目实测 debug/pf/arenas.json、sessions.json、arena_rules.json 的「依据」字段
               ⇒ **只作参考性交叉校验，不作权威**（识别链路较新，且可能受 hub 残留卡干扰）
  D 语义推定   场地名直述（只在无其它来源时使用，必须标 D 并留在「待核实」清单）

⚠️ 轮换公式的依据（2026-10-18 复核）：锚点 2024-06-03(周一) = Double/Squigly + Water，
   见论坛日历帖；角色对 9 周一循环、元素 5 周一循环（lcm=45 周）。
   下面 5 个日期是**参考性校验**（C 级，不是权威）：
   09-17 Marie / 09-21 Annie / 09-24 Big Band / 09-28 Cerebella / 10-13 Filia —— 与公式一致。
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import sys

# ---------------------------------------------------------------- 轮换常量

ANCHOR = dt.date(2024, 6, 3)          # 周一；论坛日历帖首个有元素数据的周
PAIRS = [
    ("Double", "Squigly"),
    ("Valentine", "Fukua"),
    ("Eliza", "Marie"),
    ("Annie", "Big Band"),
    ("Cerebella", "Robo-Fortune"),
    ("Ms. Fortune", "Umbrella"),
    ("Filia", "Parasoul"),
    ("Beowulf", "Peacock"),
    ("Painwheel", "Black Dahlia"),
]
ELEMENTS = ["water", "fire", "wind", "light", "dark"]

# 元素 → 周场卡名（wiki 元素场表 + 本机 hub 实测）
ELEM_ARENA = {
    "water": "HIGH TIDE",
    "fire": "RING OF FIRE",
    "wind": "EYE OF THE STORM",
    "light": "A SHINING EXAMPLE",
    "dark": "A SHOT IN THE DARK",
}
ELEM_MODIFIER = {
    "water": "PERMAFROST",
    "fire": "FLASH POINT",
    "wind": "GONE WITH THE WIND",
    "light": "EMISSION SPECTRUM",
    "dark": "NYCTOPHOBIA",
}
ELEM_ZH = {"water": "水", "fire": "火", "wind": "风", "light": "光", "dark": "暗"}

GAME_TZ = "America/Los_Angeles"
GAME_DAY_ROLLOVER_HOUR = 10           # PF / 游戏日 10:00 PT 换日（8.9.1 公告 + JJC 口径）

# 场次窗口（wiki『Prize Fight』）
WINDOW = {
    "character": {"type": "weekly-slot", "days": 3,
                  "desc": "半周：周一–周三 或 周四–周六（周日空档）"},
    "element": {"type": "weekend", "days": 2, "desc": "周六–周日"},
    "gold": {"type": "weekly", "days": 2, "desc": "周三开，持续 1–2 天"},
    "move": {"type": "biweekly", "days": 2, "desc": "周六–周日，隔周开"},
    "assist": {"type": "weekly", "days": 2, "desc": "周五–周六"},
    "monthly": {"type": "monthly", "days": 30, "desc": "每月 1 日开，整月"},
    "misc": {"type": "unknown", "days": 0, "desc": "未确认"},
    "unknown": {"type": "unknown", "days": 0, "desc": "未确认"},
}

TAG_KIND_ZH = {
    "character": "角色场",
    "element": "元素场",
    "gold": "金币场",
    "move": "招式场",
    "assist": "星助手场",
    "misc": "其他场",
    "monthly_character": "月场·角色",
    "monthly_element": "月场·元素",
    "monthly_misc": "月场·其他",
    "unknown": "未识别",
}

# 本项目条件（A 级：本机 sessions.json / 用户口径）
PROJ = {
    "character": {"energy": 5, "score_target": 50000000, "rule_kind": "class"},
    "element": {"energy": 4, "score_target": 50000000, "rule_kind": "element"},
    "misc": {"energy": 4, "score_target": 50000000, "rule_kind": None},
    "gold": {"energy": 4, "score_target": 40000000, "rule_kind": None},
    "move": {"energy": 4, "score_target": 40000000, "rule_kind": None},
    "assist": {"energy": 4, "score_target": 50000000, "rule_kind": None},
    "unknown": {"energy": 4, "score_target": 40000000, "rule_kind": None},
}

# 官方限制（wiki 原文口径）。只有明确写过「需要某元素」的才填，其余一律「无」。
OFFICIAL_FREE = {"restriction": "无", "applies_to": None, "note": "官方未设出战角色/元素限制"}


def tier(basis: str) -> str:
    """把「依据」人话映射成分级字母（A 官方 / U 用户口径 / B 社区 / C 本机参考 / D 推定）。"""
    s = basis or ""
    if "官方" in s and ("公告" in s or "论坛" in s or "更新" in s):
        return "A"
    if "用户" in s:
        return "U"
    if "wiki" in s.lower() or "社区" in s:
        return "B"
    if any(w in s for w in ("实机", "实测", "快照", "sessions", "截图", "hub 扫")):
        return "C"
    return "D"


def clean_basis(basis: str) -> str:
    """去掉旧表里手写的等级前缀（"A 官方/社区: " / "⚠️ 待确认..."），只留事实描述。

    旧表的 A/B/C 是**当时**的判定，与本站分级口径不同（官方公告才算 A，wiki 只算 B），
    直接把旧字母带出去会误导读者 ⇒ 统一剥掉，等级只由 {@link tier} 给。
    """
    s = (basis or "").strip()
    for pre in ("⚠️ 待确认", "⚠️", "★"):
        while s.startswith(pre):
            s = s[len(pre):].strip()
    for grade in ("A", "B", "C", "U"):
        if s.startswith(grade + " "):
            s = s[len(grade) + 1:].strip()
            break
    return s


DISPLAY_FIX = {
    "COSTUMEPARTY": "COSTUME PARTY",
}


def key(name: str) -> str:
    """归一化键：只留 [A-Z0-9]（与 PC _key / 手机 PfArenaTable.key 一致）。"""
    return "".join(ch for ch in (name or "").upper() if ch.isalnum())


def game_day(now: dt.datetime | None = None) -> dt.date:
    """游戏日 = 太平洋时间 - 10 小时之后的日期（10:00 PT 换日）。"""
    now = now or dt.datetime.now(dt.timezone.utc)
    try:
        from zoneinfo import ZoneInfo
        loc = now.astimezone(ZoneInfo(GAME_TZ))
    except Exception:                                   # 无 tzdata 时退到固定偏移（夏令时 -7）
        loc = now.astimezone(dt.timezone(dt.timedelta(hours=-7)))
    return (loc - dt.timedelta(hours=GAME_DAY_ROLLOVER_HOUR)).date()


def week_index(day: dt.date) -> int:
    monday = day - dt.timedelta(days=day.weekday())
    return (monday - ANCHOR).days // 7


def rotation_for(day: dt.date) -> dict:
    """某游戏日的角色半周 + 元素场。"""
    w = week_index(day)
    pair = PAIRS[w % len(PAIRS)]
    elem = ELEMENTS[w % len(ELEMENTS)]
    wd = day.weekday()                                   # 0=周一
    char = pair[0] if wd <= 2 else (pair[1] if wd <= 5 else None)
    return {
        "week": w,
        "monday": (day - dt.timedelta(days=wd)).isoformat(),
        "pair": list(pair),
        "character": char,
        "character_window": "周一–周三" if wd <= 2 else ("周四–周六" if wd <= 5 else "周日空档"),
        "element": elem,
        "element_open": wd >= 5,                          # 周六/周日
        "both_elements": [ELEM_ARENA[e] for e in (elem,)],
    }


# ---------------------------------------------------------------- 目录

def load_json(path: str) -> dict:
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def build_arenas(root: str) -> dict:
    manual = load_json(os.path.join(root, "tools/data/arena_manual.json"))["arenas"]
    monthly = load_json(os.path.join(root, "tools/data/monthly_manual.json"))["arenas"]
    rules = load_json(os.path.join(root, "tools/data/arena_rules.json"))["arenas"]

    out: dict[str, dict] = {}

    def add(entry: dict, *, src: list[str], conf: str, note: str | None = None) -> None:
        k = key(entry["name"])
        if k in out:                                    # 同名后写覆盖前写（月场表优先）
            out[k].update({kk: vv for kk, vv in entry.items() if vv is not None})
            out[k]["src"] = sorted(set(out[k]["src"]) | set(src))
            return
        entry["src"] = src
        entry["conf"] = conf
        if note:
            entry["note"] = note
        out[k] = entry

    def base(name: str, tag: str, element=None, char=None, monthly=False,
             frm=None, until=None) -> dict:
        e = {
            "name": name,
            "tag": tag,
            "element": element,
            "char": char,
            "monthly": monthly,
            "kind": TAG_KIND_ZH.get(tag, tag) + ("·" + ELEM_ZH[element] if element else "")
                    + ("(" + char + ")" if char else ""),
            "window": WINDOW.get("monthly" if monthly else tag, WINDOW["unknown"]),
        }
        if frm:
            e["from"] = frm
        if until:
            e["until"] = until
        return e

    # 1) 周场（元素/角色/金币/招式/星）—— 社区 wiki + 论坛 + 本机实测
    wiki_src = "SGM Wiki『Prize Fight』(2026-10-18 抓取)"
    for name, v in manual.items():
        tag = v.get("tag", "unknown")
        name = DISPLAY_FIX.get(key(name), name)
        e = base(name, tag, v.get("element"), v.get("char"),
                 bool(v.get("monthly")) or tag.startswith("monthly"))
        e["official"] = dict(OFFICIAL_FREE)
        if tag == "element" and v.get("element"):
            e["official"]["modifier"] = ELEM_MODIFIER[v["element"]]
        add(e, src=[wiki_src, clean_basis(v.get("依据", ""))], conf=tier(v.get("依据", "")))

    # 2) 月场（带有效期）
    for name, v in monthly.items():
        tag = v.get("tag", "monthly_misc")
        name = DISPLAY_FIX.get(key(name), name)
        e = base(name, tag, v.get("element"), v.get("char"), True,
                 v.get("valid_from"), v.get("valid_until"))
        e["official"] = dict(OFFICIAL_FREE)
        add(e, src=[clean_basis(v.get("依据", "")), "tools/data/monthly_manual.json"],
            conf=tier(v.get("依据", "")))

    # 3) 只在 arena_rules 里有的场（周场暗元素 / Shakedown 变体 / 存疑场）
    for name, v in rules.items():
        k = key(name)
        if k in out:
            continue
        rule = v.get("rule") or {}
        tag = "element" if rule.get("type") == "element" else "misc"
        e = base(name, tag, rule.get("value"))
        e["kind"] = v.get("kind") or e["kind"]
        e["official"] = dict(OFFICIAL_FREE)
        if tag == "element" and rule.get("value"):
            e["official"]["modifier"] = ELEM_MODIFIER[rule["value"]]
        add(e, src=["tools/data/arena_rules.json", clean_basis(v.get("依据", ""))],
            conf=tier(v.get("依据", "")))

    # 3.5) 官方明文写过的限制（没有写的一律「无」；别把玩家约定冒充成官方限制）
    official_override = {
        "HOTMESS": {
            "restriction": "火元素（出战队与防守队各需至少 1 名火元素斗士）",
            "applies_to": "both",
            "note": "官方 8.7 更新公告：requires a Fire Element fighter on both Attack and Defense",
            "conf": "A",
        },
        "SINKORSWIM": {
            "restriction": "无明文限制",
            "applies_to": None,
            "note": "官方 8.8 公告只说「考验水元素斗士」，未写明强制出战队限制",
            "conf": "A",
        },
        "ASHOTINTHEDARK": {
            "note": "同一名字既是暗元素周场，又可能占 10 月月场位（见 pending 第 1 条）",
        },
    }
    for k, ov in official_override.items():
        if k not in out:
            continue
        e = out[k]
        conf = ov.pop("conf", None)
        merged = {**e.get("official", {}), **{kk: vv for kk, vv in ov.items() if kk != "note"}}
        if "restriction" in ov:
            merged["note"] = "官方明文限制（见 src / note）"
        e["official"] = merged
        if ov.get("note"):
            e["note"] = ov["note"] if not e.get("note") else e["note"] + "；" + ov["note"]
        if conf:
            e["conf"] = conf

    # 4) 条件投影（本项目口径，与 PfArenaTable.Spec 同算法）
    for e in out.values():
        tag = e["tag"]
        p = PROJ.get("character" if tag == "character" else
                     "element" if tag in ("element", "monthly_element") else
                     "misc" if tag in ("misc", "monthly_character", "monthly_misc") else
                     tag, PROJ["unknown"])
        monthly = e.get("monthly") or tag.startswith("monthly")
        rule = None
        if not monthly or tag == "monthly_element":
            if p["rule_kind"] == "element" and e.get("element"):
                rule = "element:" + e["element"]
            elif p["rule_kind"] == "class" and e.get("char"):
                rule = "class:" + e["char"]
        e["project"] = {
            "energy": p["energy"],
            "score_target": None if monthly else p["score_target"],
            "rule": rule,
            "note": ("character 的 class 只约束防守队；元素场按项目约定出战队带 1 个对应元素"
                     if tag.startswith("character") else
                     "元素场限制按项目约定（官方只给敌方增益，不强制我方元素）"
                     if tag == "element" else None),
        }
        if tag.startswith("monthly"):
            e["project"]["score_target"] = None
        if e["project"]["rule"] is None:
            e["project"]["rule_reason"] = "该类场无出战限制"
    return out


# ---------------------------------------------------------------- 排期

def build_schedule(monthlies: dict, weeks: int, today: dt.date) -> dict:
    start = today - dt.timedelta(days=today.weekday())
    rows = []
    for i in range(weeks):
        mon = start + dt.timedelta(days=7 * i)
        rot = rotation_for(mon)
        pair = rot["pair"]
        char_name = {}
        for n, e in CHAR_BY_CHAR.items():
            char_name[n] = e
        rows.append({
            "monday": mon.isoformat(),
            "character": [
                {"from": mon.isoformat(), "to": (mon + dt.timedelta(days=2)).isoformat(),
                 "char": pair[0], "name": char_name.get(pair[0], "")},
                {"from": (mon + dt.timedelta(days=3)).isoformat(),
                 "to": (mon + dt.timedelta(days=5)).isoformat(),
                 "char": pair[1], "name": char_name.get(pair[1], "")},
            ],
            "element": {
                "from": (mon + dt.timedelta(days=5)).isoformat(),
                "to": (mon + dt.timedelta(days=6)).isoformat(),
                "element": rot["element"], "name": ELEM_ARENA[rot["element"]],
            },
            "gold": {"from": (mon + dt.timedelta(days=2)).isoformat(),
                     "to": (mon + dt.timedelta(days=3)).isoformat(),
                     "name": None, "note": "Medici 场，名字按周轮换（顺序未确认）"},
            "assist": {"from": (mon + dt.timedelta(days=4)).isoformat(),
                       "to": (mon + dt.timedelta(days=5)).isoformat(),
                       "name": "SEEING STARS"},
            "move": {"from": (mon + dt.timedelta(days=5)).isoformat(),
                     "to": (mon + dt.timedelta(days=6)).isoformat(),
                     "name": "SHOW ME YOUR MOVES", "biweekly": True,
                     "note": "隔周开，相位待实机确认"},
            "monthly": [{"name": m["name"], "element": m.get("element"),
                         "tag": m["tag"], "until": m.get("until")}
                        for m in monthlies.values()
                        # ⚠️ 必须**两端都写了有效期**才算「当前月场」。
                        #    只写名字不写档期的（HOT MESS / SINK OR SWIM）宁可不出，
                        #    否则会拿 8 月的场地冒充 10 月在跑（假阳性比缺失更糟）。
                        if m.get("from") and m.get("until")
                        and m["from"] <= today.isoformat() < m["until"]],
        })
    return {"generated_for": today.isoformat(), "weeks": rows}


CHAR_BY_CHAR: dict[str, str] = {}


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    ap.add_argument("--out", required=True, help="站点仓库目录（会写 <out>/data/*.json）")
    ap.add_argument("--weeks", type=int, default=6)
    ap.add_argument("--today", help="覆盖游戏日 (YYYY-MM-DD)，用于复核历史")
    a = ap.parse_args(argv)

    today = dt.date.fromisoformat(a.today) if a.today else game_day()
    arenas = build_arenas(a.root)

    for e in arenas.values():
        if e["tag"] == "character" and e.get("char"):
            CHAR_BY_CHAR[e["char"]] = e["name"]

    monthlies = {k: v for k, v in arenas.items() if v.get("monthly")}
    schedule = build_schedule(monthlies, a.weeks, today)

    data_dir = os.path.join(a.out, "data")
    os.makedirs(data_dir, exist_ok=True)

    doc = {
        "schema": 2,
        "updated_at": dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%d"),
        "game_day": today.isoformat(),
        "game_version": "8.11",
        "monthly_slots": 2,
        "source": "https://zhiyaunhe-ops.github.io/data/arenas.json",
        "rotation": {
            "anchor": ANCHOR.isoformat(),
            "pair_order": [list(p) for p in PAIRS],
            "element_order": ELEMENTS,
            "character_window": "周一–周三 / 周四–周六（周日空档，各 3 天）",
            "element_window": "周六–周日（2 天）",
            "checked_against": [
                {"day": "2026-09-17", "char": "Marie"},
                {"day": "2026-09-21", "char": "Annie"},
                {"day": "2026-09-24", "char": "Big Band"},
                {"day": "2026-09-28", "char": "Cerebella"},
                {"day": "2026-10-13", "char": "Filia"},
            ],
            "checked_disclaimer": "以上为本项目 hub 扫描的参考性校验（C 级），"
                                  "不构成权威证据；公式本身来自官方论坛日历帖。",
        },
        "provenance": {
            "A": "官方公告 / 官方论坛",
            "U": "用户口径（本项目内最高，对外仍非官方）",
            "B": "社区 wiki",
            "C": "本项目实测（仅参考：识别链路较新，可能误识别或受 hub 残留卡干扰）",
            "D": "语义推定（仅命名参考，不作约束依据）",
        },
        "pending": [
            {"item": "A SHOT IN THE DARK 是否同时是 10 月元素月场",
             "why": "10-13 hub 扫描它出现在月场位(#1)且分数 243,751,965，"
                    "但同一名字也是暗元素周场；分不清是月场卡还是 9 月残留卡",
             "level": "C"},
            {"item": "SHOW ME YOUR MOVES 的隔周相位",
             "why": "已知隔周开、周六–周日，但哪一周开没有可靠记录",
             "level": "C"},
            {"item": "HOT MESS / SINK OR SWIM 的有效档期",
             "why": "官方公告只给了场地名与限制，没给起止日期 ⇒ 本站不把它们算进「当前开放」，"
                    "需要等下一次官方开放公告或实机确认后再补 from/until",
             "level": "B"},
            {"item": "Medici 场的名字轮换顺序",
             "why": "已知每周三开、5 个场名 + Shakedown 两变体，顺序未确认",
             "level": "B"},
        ],
        "arenas": arenas,
    }
    with open(os.path.join(data_dir, "arenas.json"), "w", encoding="utf-8",
              newline="\n") as f:
        json.dump(doc, f, ensure_ascii=False, indent=1)
    with open(os.path.join(data_dir, "schedule.json"), "w", encoding="utf-8",
              newline="\n") as f:
        json.dump(schedule, f, ensure_ascii=False, indent=1)

    print("arenas=", len(arenas), "weeks=", len(schedule["weeks"]), "game_day=", today)
    for w in schedule["weeks"][:3]:
        c = w["character"]
        print(" ", w["monday"], "|", c[0]["char"], c[0]["name"], "|", c[1]["char"],
              c[1]["name"], "| 元素", w["element"]["element"], w["element"]["name"])
    return 0


if __name__ == "__main__":
    sys.exit(main())
