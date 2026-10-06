"""场次归类 tag: sgmnow 官方分类 -> 每类跑多少 (纯函数, 零 I/O)。

2026-10-06 用户口径 (A 级): **取消父子场次**, 改成按 tag 归类取条件。
原先靠 arena_rules.json 的 parent_id -> STORE.create_child() 继承
score_target/energy_cost/rule, 父子一断就无处取值; 本模块把「这一类跑多少」
变成一张纯表, 谁需要自己查。

★ 为什么不再靠 arena_rules.json 猜类别:
  那张表的kind 是「读场地名猜」的 (依据等级 B/C: 'BLOOD SPORT' 看名字像角色场、
  'TRIAL BY FIRE' 看名字像火元素场), 且带B/C 级的人工结论。
  sgmnow (Krazete 的 SGM Score Cutoffs 表 now 页, 经jjc_store.py 抓取归档) 直接
  按官方口径给出每类当日场次**名与是否开放** —— 角色场就叫 Ms. Fortune、元素场就叫
  Fire。**官方分类优先于人工猜名字。** arena_rules.json 退化为纯 OCR 纠错字库。

数据流:
    jjc_store 快照 (entries: {key: {kind, name, active, scope}})
        ↓  build_tag_index()      建 tag -> 场地名 索引 (只收 active 的)
    hub 扫出的 arenas [{idx, title, score}]
        ↓  classify_arena()        名称相似度比对过阈值 -> 定tag
    条件表 (score_target, energy_cost, rule)          assign_conditions()  按 tag 取值

本模块刻意**不import pf_store / pf_vision / pf_nav**: 它是纯决策逻辑, 要能被
单测直接跑, 也不能因为被bot 进程 import 就带上I/O 副作用 (pf_store import即
重写 sessions.json)。

铁律(承 PF_BOT/arena_rules.json, 2026-09-16/20用户口径, 不可放宽):
  - **只有元素场限定队伍元素**。角色/金币/星/月/招式场 rule 一律 None。
  - 角色场的 class 规则只约束**防守队**, 出战队不受限 (2026-09-29 用户口径);
    pf_bot.judge_rule() 对 type=class 直接 return True, 不影响出战队筛选。
    ⚠️ 绝不能给非元素场填 rule={"type":"class"} —— 那是另一个含义 (见下)。
    角色场的 class 值取自 sgmnow 的 char.name (官方当期角色), 不再人工映射。
"""
from __future__ import annotations

import difflib
import re

# ---------------------------------------------------------------- 条件表

# score_target / energy_cost 口径 (用户 2026-10-06 确认, 覆盖 2026-09-19 的旧值:
# 旧口径是 角色周场 8kw / 光元素场 6kw, 本表为新口径为准):
#   角色场 5kw + 5 能量 · 元素场 5kw + 4 能量 + 对应元素限制 · 其余一律 4kw + 4 能量
DEFAULT_SCORE_TARGET = 40_000_000     # 4kw —— 未归类场次的兜底
DEFAULT_ENERGY_COST = 4# 其余场次统一 4 能量

CHAR_SCORE_TARGET = 50_000_000        # 5kw
CHAR_ENERGY_COST = 5
ELEM_SCORE_TARGET = 50_000_000        # 5kw (与角色场同量级, 用户 2026-10-06 口径)
ELEM_ENERGY_COST = 4

# tag -> (score_target, energy_cost, 是否需要附加规则)
#   need_rule=True 的两类才产出 rule: 角色场挂 class(防守角色), 元素场挂 element。
#   其余一律 None —— 见文件头铁律。
TAG_CONDITIONS = {
    "character": (CHAR_SCORE_TARGET, CHAR_ENERGY_COST, "class"),
    "element":   (ELEM_SCORE_TARGET, ELEM_ENERGY_COST, "element"),
    "rift":      (ELEM_SCORE_TARGET, ELEM_ENERGY_COST, "element"),
    "gold":      (DEFAULT_SCORE_TARGET, DEFAULT_ENERGY_COST, None),
    "move":      (DEFAULT_SCORE_TARGET, DEFAULT_ENERGY_COST, None),
    "assist":    (DEFAULT_SCORE_TARGET, DEFAULT_ENERGY_COST, None),
    "monthly":   (DEFAULT_SCORE_TARGET, DEFAULT_ENERGY_COST, None),
}

# 未知/未开放类别的 tag —— 归类失败落到这里, 取最保守条件(不猜元素)
UNKNOWN_TAG = "unknown"

# 名称匹配阈值 (用户 2026-10-06: 按相似度, 超过阈值才认同一场次)。
# 0.75 的来历: pf_nav._kw_hit 已实证 OCR 会丢首字母
# ('RUNESANDZEROS' -> 'UNESANDZEROS', 2026-10-02) 与丢字母
# ('NFINITYAND'/'IGBENS', 2026-09-22/25), 这类残串与原名的ratio 落在 0.8~0.95;
# 而不同场次之间 ('NIGHT' vs 'MIGHT') 明显更低。0.75 留了余量又不跨场。
MATCH_THRESHOLD = 0.75

# sgmnow 角色名 -> 游戏内角色标识。与 webui.js 的 CHARACTER_ICON_FILES 同一份
# 白名单, 但那边是给图标文件名的; 这里要的是 pf_bot 防守队用的角色键。
# ⚠️ sgmnow 写 "Ms. Fortune", 游戏内标识是 MSFORTUNE —— 归一只去空格/点/横线。
_NOISE = re.compile(r"[^A-Z0-9]")

# sgmnow 元素名 -> rule.value。与 pf_nav/pf_bot 用的元素键一致(小写)。
ELEMENT_KEYS = {"fire": "fire", "water": "water", "wind": "wind",
                "light": "light", "dark": "dark", "neutral": "neutral"}

#「在场但本期无具体场名」的哨兵。源表在星PF/招式PF 这类行写的是 "Active PF" ——
# **不是** jjc_store.INACTIVE_SENTELS 里的 "inactive"(那是"没开"), 所以抓取层
# 收下了它, name="Active"。若直接进索引, hub 上一张叫 ACTIVE 的卡都会被归到
# 星场/招式场(2026-09-26 快照实测), 而 hub 上根本不存在这种场。
# ⚠️ 键必须**大写**: _is_placeholder 比的是 _key() 的输出(恒大写)。2026-10-06
# 首版这里写成小写, 判据对每个值都返回 False —— 护栏静默失效, 靠单测抓出来。
NON_ARENA_NAMES = {"ACTIVE", "ACTIVEPF", "INACTIVE", "NONE", "NA", "LOADING"}


def _is_placeholder(name: str) -> bool:
    """该名字是不是"占位符"而非真场名(见 NON_ARENA_NAMES)。"""
    return _key(name) in NON_ARENA_NAMES


def _key(name: str) -> str:
    """名称归一: 只留字母数字大写。OCR 残串与官方名的比较都走这个。"""
    return _NOISE.sub("", str(name or "").upper())


def norm_char(name: str) -> str:
    """sgmnow 角色名 -> 防守队角色键: 'Ms. Fortune' -> 'MSFORTUNE'。"""
    return _key(name)


def norm_element(name: str) -> str | None:
    """sgmnow 元素名 -> rule.value; 认不出的返回 None(绝不猜元素)。"""
    return ELEMENT_KEYS.get(str(name or "").strip().lower())


# ---------------------------------------------------------------- tag 索引

def _kind_by_key() -> dict:
    """entry key -> kind。

    ⚠️ 必须从 jjc_store.ENTRIES 反查, 不能读 entry 里的 "kind":
    归档快照的 entries 只存 payload 那7 个字段 (key/title/raw_name/name/
    active/scope/volatile —— 见 jjc_store._commit), **kind 没有落盘**。
    早先直接读 e["kind"] 的后果是全部落unknown (2026-10-06 实测: rift/char/holi
    三项 tag 皆unknown), 且因为快照里没这个键、不报错, 静默错到底。
    这里显式导入 jjc_store —— 它只import标准库+路径常量, 不触网、不写盘,
    import 它是安全的 (与 pf_store 不同)。
    """
    from jjc_store import ENTRIES
    return {k: kind for k, _cn, _p, kind in ENTRIES}


def build_tag_index(snapshot: dict, *, only_active: bool = True) -> list:
    """JJC 快照 -> [{tag, kind, name, char_key, element, title, scope}]。

    snapshot 就是 jjc_store 归档的一个 {day, entries:{key:{...}}} —— 直接吃
    快照文件, 不重新抓取(抓取是jjc_store 的活, 且会触网)。

    only_active=True 时只收 active 的条目: sgmnow 的 now 页同一行同时给
    "Current X PF"(在开) 与"Last X PF"(上一场, 已收), 收进来会把 hub 上**没有**
    的场次也标成 tag, 污染比对结果。

    ⚠️ rift 与 elem 同为元素类 (都出 element 规则) —— rift 是裂缝元素, 同样
    限定队伍元素, 故归一到 element 取值; 但 tag 保留各自的 kind 以便日志可读。
    """
    out = []
    kinds = _kind_by_key()
    entries = (snapshot or {}).get("entries") or {}
    for e in entries.values():
        if not isinstance(e, dict):
            continue
        if only_active and not e.get("active"):
            continue
        name = e.get("name")
        if not name:                      # 未开放/计算中的条目 name 为 None
            continue
        if _is_placeholder(name):         # "Active PF" 之类占位符, 不是场名
            continue
        # key 认不出 -> kind 落unknown 而不是猜一个 (错 tag 会套错条件)
        kind = kinds.get(e.get("key"), "unknown")
        out.append({
            "tag": kind,
            "kind": kind,
            "key": e.get("key"),
            "name": name,
            "title": e.get("title") or "",
            "scope": e.get("scope") or "unknown",
            "char_key": norm_char(name) if kind == "character" else None,
            "element": norm_element(name) if kind in ("element", "rift") else None,
        })
    return out


# ---------------------------------------------------------------- 比对

def match_score(a: str, b: str) -> float:
    """两个场地名的相似度 0.0~1.0 (先归一再比)。"""
    ka, kb = _key(a), _key(b)
    if not ka or not kb:
        return 0.0
    if ka == kb:
        return 1.0
    return difflib.SequenceMatcher(None, ka, kb).ratio()


# 同名撞车时的 tag 优先级 (2026-10-06 实测: rift 与 element 可同为 "Dark"/"Water",
# hub 上只有一张该名的卡, 但快照里两条都 active —— 同名时按此序取一个, 并在
# note 里记明撞车, 不让日志看起来像"本来就只有一个元素场")。
#取值上两者本就相同(都是 5kw/4能量/element 规则), 所以这是可读性问题而非跑批问题。
_TAG_PRIORITY = ("character", "element", "rift", "gold", "monthly",
                 "assist", "move", "unknown")


def _pick(cands: list) -> tuple:
    """同分候选里挑一个 tag: 按 _TAG_PRIORITY, 返回 (选中项, 撞车列表)。"""
    ranked = sorted(cands, key=lambda c: _TAG_PRIORITY.index(c["tag"])
                    if c["tag"] in _TAG_PRIORITY else len(_TAG_PRIORITY))
    return ranked[0], ranked[1:]


def classify_arena(title: str, index: list,
                   threshold: float = MATCH_THRESHOLD) -> dict:
    """hub 扫出的一个场地名 -> tag 与取值条件。

    返回 {tag, matched, name, score, rule, score_target, energy_cost, note}。
    **未过阈值不猜**: tag 落unknown、score_target/energy_cost 取最保守的
    4kw/4 能量、rule=None, 并在 note 里说清「最高相似度是多少、跟谁像」——
    宁可漏跑不错跑, 这是 pf_scene 立卡牌判据的同一条原则。

    同分(多个候选 ratio 相同)按_tag_PRIORITY 取一个并留痕, **不做二次猜测**:
    相似度判据不足以区分两个长名相近的场次, 硬挑一个等于伪造依据 —— 但完全
    同名(rit/element 同为 Dark)不是相似度不足, 而是源表两条指同一张卡, 按
    优先级取一是唯一合理解。
    """
    best_score, cands = 0.0, []
    for item in index or []:
        s = match_score(title, item["name"])
        if not cands or s > best_score:
            best_score, cands = s, [item]
        elif s == best_score:
            cands.append(item)
    if not cands or best_score < threshold:
        near = (f"最高相似度 {best_score:.2f} (vs {cands[0]['name']!r})"
                if cands else "索引为空")
        return {"tag": UNKNOWN_TAG, "matched": False, "name": None, "score": 0.0,
                "rule": None, "score_target": DEFAULT_SCORE_TARGET,
                "energy_cost": DEFAULT_ENERGY_COST,
                "note": f"未过阈值({threshold}): {near}"}
    hit, collisions = _pick(cands)
    tgt, energy, rule_kind = TAG_CONDITIONS.get(hit["tag"],
                                                (DEFAULT_SCORE_TARGET,
                                                 DEFAULT_ENERGY_COST, None))
    rule = None
    if rule_kind == "element":
        # 元素场必须带对应元素限制 (用户 2026-09-20 口径: 对应元素放左1)。
        # sgmnow 元素名认不出 -> 不猜, 退回无规则并留痕(否则等于放弃了唯一的
        # 硬约束, 而错绑比不绑更糟: 会去筛一个根本不需要筛的队伍)。
        rule = ({"type": "element", "value": hit["element"]}
                if hit["element"] else None)
    elif rule_kind == "class":
        # 角色场: class 只约束防守队 (2026-09-29 口径), 值取自 sgmnow 当期角色。
        rule = ({"type": "class", "value": hit["char_key"]}
                if hit["char_key"] else None)
    note = f"tag={hit['tag']} <- sgmnow {hit['key']}({hit['name']!r}) 相似度 {best_score:.2f}"
    if collisions:
        note += " | 同名撞车(按优先级取一): " + ", ".join(
            f"{c['tag']}({c['key']}={c['name']!r})" for c in collisions)
    return {"tag": hit["tag"], "matched": True, "name": hit["name"],
            "score": round(best_score, 4), "rule": rule,
            "score_target": tgt, "energy_cost": energy, "note": note}


def classify_all(arenas: list, snapshot: dict, *,
                 threshold: float = MATCH_THRESHOLD,
                 only_active: bool = True) -> list:
    """hub 扫出的全部场地 [{idx,title,score}] -> 逐个归类, 顺序不变。

    snapshot 为 None/无匹配时全部落 unknown —— 不因数据源缺失就静默套用
    默认条件当成已归类, 那会让「归类失败」在日志里长得像「归类成功」。
    """
    index = build_tag_index(snapshot, only_active=only_active)
    out = []
    for a in arenas or []:
        r = classify_arena(a.get("title"), index, threshold)
        r["idx"] = a.get("idx")
        r["title"] = a.get("title")
        r["hub_score"] = a.get("score")
        out.append(r)
    return out