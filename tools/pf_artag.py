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
    jjc_store 快照 (entries: {key: {name, active, scope}})
        ↓  build_tag_index()      建 tag -> 场地名 索引 (只收 active; 月场除外)
    月场人工补丁 tools/data/monthly_manual.json (类别 + 元素名, 带有效期)
        ↓  _manual_match()        名字命中 -> 直接定 monthly_character/element
    hub 扫出的 arenas [{idx, title, score}]
        ↓  classify_arena()        名称相似度比对过阈值 -> 定tag
    条件表 (score_target, energy_cost, rule)          按 tag 取值

★ 月场为什么必须人工补 (2026-10-06 用户口径):
  sgmnow 的 Monthly PF 只有**一个** holi 条目、只给名字(实测 14 天快照: holi
  恒为 name+active、无类别字段), 既不区分「角色月场/元素月场」也不给元素名。
  而游戏里月场有两类, 且**两类都无上限**(用户 2026-10-06 口径: score_target
  =None, 打到手动停)。类别与元素只能人工给 => monthly_manual.json。
  补丁带 valid_until, 过期即失效 —— 不让某月的手工配置在次月悄悄继续生效。

本模块刻意**不import pf_store / pf_vision / pf_nav**: 它是纯决策逻辑, 要能被
单测直接跑, 也不能因为被bot 进程 import 就带上I/O 副作用 (pf_store import即
重写 sessions.json)。

铁律(承 PF_BOT/arena_rules.json, 2026-09-16/20用户口径, 不可放宽):
  - **只有元素场限定队伍元素**。角色/金币/星/招式场 rule 一律 None;
    **元素月场例外** —— 它就是元素场, 同样挂 element 限制(2026-10-06 口径)。
  - 角色场的 class 规则只约束**防守队**, 出战队不受限 (2026-09-29 用户口径);
    pf_bot.judge_rule() 对 type=class 直接 return True, 不影响出战队筛选。
    ⚠️ 绝不能给非元素场填 rule={"type":"class"} —— 那是另一个含义 (见下)。
    角色场的 class 值取自 sgmnow 的 char.name (官方当期角色), 不再人工映射。
"""
from __future__ import annotations

import difflib
import json
import re
from pathlib import Path

# ---------------------------------------------------------------- 条件表

# score_target / energy_cost 口径 (用户 2026-10-06 确认, 覆盖 2026-09-19 的旧值:
# 旧口径是角色周场 8kw / 光元素场 6kw, 本表为新口径为准):
#   角色场 5kw + 5 能量 · 元素场 5kw + 4 能量 + 对应元素限制 · 其余一律 4kw + 4 能量
#   **月场两类(角色月场/元素月场)均无上限** (score_target=None, 打到手动停)
# ⚠️ None 不是"漏填": pf_bot 的判定是 `if score_target is not None and
#   val >= score_target` (pf_bot.py:571), None 直接跳过上界判定 = 不限量。
#   A 级依据: sessions.json 里两类月场的 tgt 本来就全是 None, 实测总分
#   188,331,229 / 191,962,012 (1.5~1.9 亿量级), 任何具体上限都会提前截断。
DEFAULT_SCORE_TARGET = 40_000_000     # 4kw —— 未归类场次的兜底
DEFAULT_ENERGY_COST = 4# 其余场次统一 4 能量

CHAR_SCORE_TARGET = 50_000_000        # 5kw
CHAR_ENERGY_COST = 5
ELEM_SCORE_TARGET = 50_000_000        # 5kw (与角色场同量级, 用户 2026-10-06 口径)
ELEM_ENERGY_COST = 4
MONTHLY_SCORE_TARGET = None# 月场无上限
MONTHLY_ENERGY_COST = 4

# tag -> (score_target, energy_cost, 是否需要附加规则)
#   need_rule=True 的三类才产出 rule: 角色场挂 class(防守角色), 元素场挂 element。
#   其余一律 None —— 见文件头铁律。
TAG_CONDITIONS = {
    "character":        (CHAR_SCORE_TARGET, CHAR_ENERGY_COST, "class"),
    "element":          (ELEM_SCORE_TARGET, ELEM_ENERGY_COST, "element"),
    "rift":             (ELEM_SCORE_TARGET, ELEM_ENERGY_COST, "element"),
    # 月场两类: 无上限; 元素月场挂 element 限制(与元素场同一条铁律), 角色月场不挂。
    "monthly_character": (MONTHLY_SCORE_TARGET, MONTHLY_ENERGY_COST, None),
    "monthly_element":   (MONTHLY_SCORE_TARGET, MONTHLY_ENERGY_COST, "element"),
    "gold":             (DEFAULT_SCORE_TARGET, DEFAULT_ENERGY_COST, None),
    "move":             (DEFAULT_SCORE_TARGET, DEFAULT_ENERGY_COST, None),
    "assist":           (DEFAULT_SCORE_TARGET, DEFAULT_ENERGY_COST, None),
}

# 未知/未开放类别的 tag —— 归类失败落到这里, 取最保守条件(不猜元素)
UNKNOWN_TAG = "unknown"

# 名称匹配阈值 (用户 2026-10-06: 按相似度, 超过阈值才认同一场次)。
# 0.75 的来历: pf_nav._kw_hit 已实证 OCR 会丢首字母
# ('RUNESANDZEROS' -> 'UNESANDZEROS', 2026-10-02) 与丢字母
# ('NFINITYAND'/'IGBENS', 2026-09-22/25), 这类残串与原名的ratio 落在 0.8~0.95;
# 而不同场次之间 ('NIGHT' vs 'MIGHT') 明显更低。0.75 留了余量又不跨场。
MATCH_THRESHOLD = 0.75

# 子串匹配的长度下限: 较短一方至少这么多字母才允许子串命中。
# 承 pf_nav._kw_hit 的既有门槛(「<4 字母只认全等」)—— 2026-10-06 实测,
# 月场补丁若无此门槛, 1 字母残串 'M' 会因是 'COSTUMEPARTY' 的子串而被
# 判成元素月场。相似度路径本身不吃短串, 但补丁路径吃, 两处门槛必须一致,
# 否则等于给相似度那道防线开了个后门。
MIN_SUBSTR_LEN = 4

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


# ------------------------------------------------------------ 月场人工补丁表

# sgmnow 的 Monthly PF 只有**一个** holi 条目, 只给名字 —— 既不区分「角色月场」
# 还是「元素月场」, 也不给元素名(实测 14 天快照: holi 恒为名字+active, 无类别)。
# 月场恰好每月一次 ⇒ 这类信息只能人工补, 见 tools/data/monthly_manual.json。
# ⚠️ 读不存在的键/坏JSON 一律当"没有补丁", 不抛 —— 本模块是纯决策逻辑,
# 配置文件坏了不该让整个归类链崩(最坏退化成 unknown + 最保守条件, 有痕可查)。
MANUAL_PATH = Path(__file__).resolve().parent / "data" / "monthly_manual.json"


def _load_manual(path=None) -> dict:
    """读月场人工补丁表 -> {归一key: spec}。读不了返回空表。"""
    try:
        with open(path or MANUAL_PATH, encoding="utf-8") as f:
            raw = json.load(f)
    except (OSError, json.JSONDecodeError, AttributeError):
        return {}
    out = {}
    for name, spec in (raw.get("arenas") or {}).items():
        if isinstance(spec, dict) and name:
            out[_key(name)] = dict(spec, _name=name)
    return out


def _manual_valid(spec: dict, day: str = None) -> bool:
    """该补丁在 day(游戏日 YYYY-MM-DD)是否有效。缺 day=无法判有效期 -> 认有效
    (调用方通常已确认快照新鲜度; 而宁可用一条人工配置, 也不要把用户明确
    给的口径判成无效)。"""
    if not day:
        return True
    frm = spec.get("valid_from")
    until = spec.get("valid_until")
    if frm and day < frm:
        return False
    if until and day >= until:
        return False
    return True


def _manual_match(name: str, manual: dict, day: str = None) -> dict | None:
    """场地名 -> 命中的月场补丁 spec; 没命中返回 None。

    匹配: monthly=True 的条目, 名字双向子串命中(承 pf_nav OCR 残串实证:
    'ASHOTINTHEDARK'/'IGBENSBEATDOWN'), 且在有效期内。

    ⚠️ **子串必须有长度下限** (2026-10-06 单测抓出): 短 OCR 残串 'M' 是
    'COSTUMEPARTY' 的子串, 无下限就会把一个 1 字母残串判成元素月场。
    pf_nav 早就为此定过「<4 字母只认全等」, 这里沿用同一条门槛 ——
    否则相似度路径有防护、补丁路径没有, 等于从后门绕过了既有防线。
    """
    k = _key(name)
    if not k:
        return None
    best = None
    for spec in (manual or {}).values():
        if not spec.get("monthly") or not _manual_valid(spec, day):
            continue
        mk = _key(spec.get("_name"))
        if not mk:
            continue
        if mk == k:
            hit = True
        elif (mk in k) or (k in mk):
            # 确实是子串关系后, 再要求**较短的一方**够长(>=4):
            # 'ASHOTINTHEDARK' vs 'A SHOT IN THE DARK' 短方 14 -> 放行;
            # 'M' vs 'COSTUMEPARTY' 短方 1 -> 拒绝。
            # ⚠️ 顺序不能反: 先比长度再判子串会把「两个长名字」一律放行,
            #   哪怕它们毫无包含关系(2026-10-06 实测踩过)。
            hit = min(len(mk), len(k)) >= MIN_SUBSTR_LEN
        else:
            hit = False
        if hit and (best is None or len(mk) > len(_key(best.get("_name")))):
            best = spec          # 多个命中取名字最长的(最具体那个)
    return best


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
        # 月场**不进索引**: sgmnow 的 holi 只有名字, 没有"角色月场还是元素月场"
        # 也没有元素名 —— 归类交由 monthly_manual.json 人工补(build 时就会被
        # 覆盖), 否则只能给出 monthly 这一无法再分的 tag。
        if kind == "monthly":
            continue
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
_TAG_PRIORITY = ("character", "element", "rift", "gold",
                 "monthly_character", "monthly_element",
                 "assist", "move", "unknown")


def _pick(cands: list) -> tuple:
    """同分候选里挑一个 tag: 按 _TAG_PRIORITY, 返回 (选中项, 撞车列表)。"""
    ranked = sorted(cands, key=lambda c: _TAG_PRIORITY.index(c["tag"])
                    if c["tag"] in _TAG_PRIORITY else len(_TAG_PRIORITY))
    return ranked[0], ranked[1:]


def _from_manual(title: str, spec: dict) -> dict:
    """月场人工补丁 -> 与 classify_arena 同形状的结果。

    与相似度路径的差别只有两点: tag/元素来自人工表而非推断, 且note 里写明
    「人工配置」—— 事后翻日志时能立刻分清哪些归类是查出来的、哪些是口述的。
    """
    tag = spec.get("tag") or UNKNOWN_TAG
    tgt, energy, rule_kind = TAG_CONDITIONS.get(
        tag, (DEFAULT_SCORE_TARGET, DEFAULT_ENERGY_COST, None))
    rule = None
    if rule_kind == "element":
        elem = norm_element(spec.get("element"))
        # 元素月场必须给元素名 (用户 2026-10-20 口径: 本月是暗)。给不出 ->
        # 不猜元素, rule=None 并留痕。
        rule = {"type": "element", "value": elem} if elem else None
    note = (f"tag={tag} <- 人工配置monthly_manual.json[{spec.get('_name')!r}]"
            f" (sgmnow 不给月场类别/元素; 依据={spec.get('依据','?')})")
    if rule_kind == "element" and not rule:
        note += " |⚠️ 人工表缺元素名, 未挂 element 限制"
    return {"tag": tag, "matched": True, "name": spec.get("_name"),
            "score": 1.0, "rule": rule, "score_target": tgt,
            "energy_cost": energy, "note": note}


def classify_arena(title: str, index: list,
                   threshold: float = MATCH_THRESHOLD,
                   manual: dict = None, day: str = None) -> dict:
    """hub 扫出的一个场地名 -> tag 与取值条件。

    返回 {tag, matched, name, score, rule, score_target, energy_cost, note}。
    **未过阈值不猜**: tag 落unknown、score_target/energy_cost 取最保守的
    4kw/4 能量、rule=None, 并在 note 里说清「最高相似度是多少、跟谁像」——
    宁可漏跑不错跑, 这是 pf_scene 立卡牌判据的同一条原则。

    同分(多个候选 ratio 相同)按_tag_PRIORITY 取一个并留痕, **不做二次猜测**:
    相似度判据不足以区分两个长名相近的场次, 硬挑一个等于伪造依据 —— 但完全
    同名(rit/element 同为 Dark)不是相似度不足, 而是源表两条指同一张卡, 按
    优先级取一是唯一合理解。

    manual = monthly_manual.json 的人工补丁(默认自动读盘)。**月场补丁优先于
    相似度判定**: 人工配置是明确依据, 名字匹配只是猜测 —— 且月场名字
    ('Costume Party') 根本不含元素线索, 走相似度只会得到 monthly 这种没法再分
    的 tag。补丁表只按名字覆盖, 不影响非月场归类。
    """
    # ① 月场人工补丁 (sgmnow 缺的那部分: 类别 + 元素名)
    if manual is None:
        manual = _load_manual()
    mspec = _manual_match(title, manual, day)
    if mspec is not None:
        return _from_manual(title, mspec)

    # ② sgmnow 索引 + 名称相似度
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
                 only_active: bool = True, day: str = None) -> list:
    """hub 扫出的全部场地 [{idx,title,score}] -> 逐个归类, 顺序不变。

    snapshot 为 None/无匹配时全部落 unknown —— 不因数据源缺失就静默套用
    默认条件当成已归类, 那会让「归类失败」在日志里长得像「归类成功」。

    day = 游戏日(YYYY-MM-DD), 用于判人工补丁有效期。缺省时快照里的 day
    自动取(snapshot 本身带 day 字段); 都没有则补丁一律认有效。
    """
    index = build_tag_index(snapshot, only_active=only_active)
    if day is None:
        day = (snapshot or {}).get("day")
    out = []
    for a in arenas or []:
        r = classify_arena(a.get("title"), index, threshold, day=day)
        r["idx"] = a.get("idx")
        r["title"] = a.get("title")
        r["hub_score"] = a.get("score")
        out.append(r)
    return out