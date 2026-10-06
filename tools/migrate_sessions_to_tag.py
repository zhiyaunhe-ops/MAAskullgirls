"""sessions.json 一次性迁移: 父子继承 -> tag 取值 (2026-10-06 用户口径)。

背景: 场次原先靠 parent_id -> STORE.create_child() 继承 score_target/
energy_cost/rule; 用户 2026-10-06 口径改为「不搞父子, 只按 tag 归类取条件」,
取值表在 tools/pf_artag.py (条件表 JSON, 见 conditions_of)。本脚本把**已有场次**
的三个字段按tag 重算一遍, 使存量与新口径一致。

⚠️ 为什么必须停bot 才能跑:
  pf_storage.ScoreStore.record() 每次采样都 sess["score"]=... 然后
  _save_sessions() **重写整个 sessions.json** (pf_storage.py:277-280)。
  bot 在跑时本脚本的写入会被下一场战斗直接覆盖 —— 迁移静默失效。
  ⇒ 本脚本先探测 8790 的 /api/state, 活着就拒绝跑(除非 --force)。

判定 tag 的依据(按可信度从高到低, 迁移记录里逐条标注):
  1. **现有 rule 字段** —— 非null 的 rule 是实测/人工确认过的硬约束, 最可信:
     rule.type=='element' -> 该元素场, 元素值沿用原 rule.value(不从名字重推);
     rule.type=='class'   -> 角色场(防守角色约束), 角色名沿用原 rule.value。
  2. **场次名里的类别词** —— '金币场'/'角色周场'/'角色月场'/'元素月场'/
     'SEEING STARS' 等, 是人工建场时自己起的名, 属 A 级用户口径。
  3. **energy_cost 推断** —— ec=5 且无规则 = 角色场(角色场是唯一 5 能量档)。
     **这条是推断不是依据**, 迁移记录里标 '推断'。
  ⚠️ 判不出的一律 tag=unknown 取最保守条件(4kw/4能量/无规则), 不猜。

**月场例外**: score_target=None(无上限) 是**既有正确值**, 不是漏填 ——
  pf_bot 判据`if score_target is not None and val>=target`, None=不限量,
  而 sessions.json 里两类月场 tgt 本来就全是 None(实测 188,331,229/
  191,962,012)。故月场**不套4kw**, 保住 None。

parent 字段: **保留不动**(用户 2026-10-06 口径)。它只作历史留档, 新建场次
不再走 create_child; 拆掉它不可逆, 且会丢「当时挂在哪类下」的信息。

跑法 (必须 anaconda python; 且**先停 bot**):
    C:/Users/zhiya/anaconda3/python.exe tools/migrate_sessions_to_tag.py --dry-run
    C:/Users/zhiya/anaconda3/python.exe tools/migrate_sessions_to_tag.py --apply
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import sys
import time
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[0]))

import pf_artag as T

PROJECT_ROOT = Path(__file__).resolve().parents[1]
SESSIONS = PROJECT_ROOT / "debug" / "pf" / "sessions.json"
WEBUI_PORT = 8790

# 场次名 -> tag 的类别词 (人工起名时的自述, A 级用户口径)。
# ⚠️ 顺序即优先级: 先匹配的赢。'元素月场' 必须排在 '月场' 之前,
#    否则会被后者先吃掉(角色月场/元素月场都是月场, 但条件不同)。
NAME_TAG_RULES = [
    (re.compile(r"元素月场", re.I),      "monthly_element"),
    (re.compile(r"角色月场", re.I),      "monthly_character"),
    (re.compile(r"月场", re.I),          "monthly_element"),   # 兜底: 名字只写月场
    (re.compile(r"元素场", re.I),        "element"),
    (re.compile(r"角色周场|角色场", re.I), "character"),
    (re.compile(r"金币场|medici|shakedown", re.I), "gold"),
    (re.compile(r"seeing\s*stars|星", re.I),      "assist"),
    (re.compile(r"smym|招式", re.I),              "move"),
]


def _rule_of(s: dict):
    """现有 rule; 非法结构回 None(与 pf_domain.clean_rule 同口径)。"""
    r = s.get("rule")
    return r if isinstance(r, dict) and r.get("type") and r.get("value") else None


def classify_session(s: dict) -> dict:
    """场次 -> {tag, element, char, basis}。判不出tag=unknown。

    basis 是**依据说明**, 逐条写清凭什么这么分—— 事后翻记录能区分
    「实测确认」和「我推断的」。
    """
    name = s.get("name") or ""
    rule = _rule_of(s)

    # ① 月场人工补丁 —— **必须最先判**(2026-10-06 实测踩到)。
    # 月场身份比元素规则**更具体**: 元素月场既有 element 规则(限定元素),
    # 又属于 monthly_element(无上限)。若先判 rule, 第二轮跑时
    # COSTUMEPARTY 已带 rule=element:dark, 会被降级判成普通 element,
    # 标签从 monthly_element 退成 element。
    # 反过来 monthly_manual.json 只补 sgmnow 缺的东西, 不会误判非月场。
    mspec = T._manual_match(name, T._load_manual())
    if mspec is not None:
        return {"tag": mspec.get("tag") or T.UNKNOWN_TAG,
                "element": T.norm_element(mspec.get("element")),
                "char": None,
                "basis": (f"monthly_manual.json 人工配置 [{mspec.get('_name')!r}]"
                          " (月场类别+元素 sgmnow 给不了, A级用户口径)")}

    # ② 现有 rule (非月场里最可信): 非null rule 是确认过的硬约束
    if rule:
        if rule["type"] == "element":
            elem = T.norm_element(rule["value"]) or str(rule["value"]).lower()
            return {"tag": "element", "element": elem, "char": None,
                    "basis": f"现有 rule=element:{elem} (实测确认, 元素值沿用原值不从名字重推)"}
        if rule["type"] == "class":
            # ⚠️ **原样保留 value, 不做归一**(2026-10-06 实测踩到):
            # pf_bot 拿 rule["value"] 去查 CHARACTER_CHIPS (pf_bot.py:112-119),
            # 那张表的键是**带空格的官方原名** —— "Cerebella" / "Ms. Fortune"。
            # 转成大写/去空格后 get() 返回 None, 代码走 else 分支只打一句
            # 「没有筛选芯片, 跳过」, **防守角色约束静默失效**。
            # 归一化只用于**比较**(tag 判定), 写回必须用原值。
            return {"tag": "character", "element": None,
                    "char": str(rule["value"]),
                    "basis": f"现有 rule=class:{rule['value']} (实测确认防守角色, 原值保留不改写)"}

    # ③ 场次名里的类别词
    for rx, tag in NAME_TAG_RULES:
        if rx.search(name):
            return {"tag": tag, "element": None, "char": None,
                    "basis": f"场次名含类别词 {rx.pattern!r} (人工起名, A级口径)"}

    # ③ ec=5 且无规则 -> 推断为角色场 (⚠️ 推断, 非依据)
    ec = s.get("energy_cost")
    try:
        ec = int(ec)
    except (TypeError, ValueError):
        ec = None
    if ec == 5:
        return {"tag": "character", "element": None, "char": None,
                "basis": "⚠️ 推断: 无 rule 且 energy_cost=5, 角色场是唯一 5 能量档"}

    return {"tag": T.UNKNOWN_TAG, "element": None, "char": None,
            "basis": "⚠️ 判不出: 无 rule / 名字无类别词 / ec 非 5 -> 取最保守条件"}


def conditions_for(tag: str, element, char) -> dict:
    """tag -> {score_target, energy_cost, rule}。未知 tag 走最保守兜底。"""
    tgt, energy, rule_kind = T.conditions_of(tag)
    rule = None
    if rule_kind == "element" and element:
        rule = {"type": "element", "value": element}
    elif rule_kind == "class" and char:
        rule = {"type": "class", "value": char}
    return {"score_target": tgt, "energy_cost": energy, "rule": rule}


def _fmt(v) -> str:
    return "无上限" if v is None else f"{v:,}" if isinstance(v, int) else str(v)


def plan(data: dict, *, skip_ids: set) -> list:
    """算出每个场次的 before/after, 不改任何东西。"""
    out = []
    for s in data.get("sessions") or []:
        sid = s.get("id")
        c = classify_session(s)
        new = conditions_for(c["tag"], c["element"], c["char"])
        before = {"score_target": s.get("score_target"),
                  "energy_cost": s.get("energy_cost"),
                  "rule": s.get("rule")}
        out.append({
            "id": sid, "name": s.get("name"), "tag": c["tag"],
            "basis": c["basis"], "before": before, "after": new,
            "skip": sid in skip_ids,
            # 「需要处理」= 条件有变化**或 tag 与本次判定不一致**。
            # ⚠️ 只按条件判会漏: 2026-10-06 第二轮 COSTUMEPARTY 的三个条件
            # 已是正确值, 但 tag 因分支优先级 bug 退成了 element ——
            # 条件判重看不出这种「值对、标签错」的情况。
            "changed": (before["score_target"] != new["score_target"]
                        or before["energy_cost"] != new["energy_cost"]
                        or before["rule"] != new["rule"]
                        or s.get("tag") != c["tag"]),
        })
    return out


def bot_state():
    """(在跑?, session_id, queue)。身份看 svc 字段, 不只看端口通不通。"""
    try:
        with urllib.request.urlopen(
                f"http://127.0.0.1:{WEBUI_PORT}/api/state", timeout=3) as r:
            d = json.loads(r.read().decode("utf-8"))
        if d.get("svc") != "sgm-pf-bot":
            return False, None, []
        return True, d.get("session_id"), [q.get("id") for q in (d.get("queue") or [])]
    except Exception:  # noqa: BLE001
        return False, None, []


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="sessions.json -> tag 取值迁移")
    ap.add_argument("--dry-run", action="store_true", help="只打印, 不写盘")
    ap.add_argument("--apply", action="store_true", help="实际写入")
    ap.add_argument("--force", action="store_true", help="bot 在跑也强行写(危险)")
    args = ap.parse_args(argv)
    if not (args.dry_run or args.apply):
        ap.print_help()
        return 2

    if not SESSIONS.exists():
        print(f"找不到 {SESSIONS}")
        return 1
    with open(SESSIONS, encoding="utf-8") as f:
        data = json.load(f)

    running, active, queue = bot_state()
    skip = set()
    if running:
        if active:
            skip.add(active)
        skip.update(x for x in queue if x)
    if running and not args.force and args.apply:
        print("bot 正在运行 —— pf_store 每次采样都会重写整个 sessions.json, "
              "本次写入会被下一场战斗直接覆盖。\n"
              "请先停 bot (WebUI「结束服务」或菜单), 或加 --force(不推荐)。")
        return 1

    plan_rows = plan(data, skip_ids=skip)

    # ---- 打印 ----
    print(f"共 {len(plan_rows)} 个场次"
          + (f"; 跳过 {len([r for r in plan_rows if r['skip']])} 个(正在跑/队列里)"
             if skip else ""))
    print(f"bot: {'运行中' if running else '未运行'}\n")
    for r in plan_rows:
        mark = "SKIP" if r["skip"] else ("    " if r["changed"] else "同值")
        b, a = r["before"], r["after"]
        br = (f"{b['rule']['type']}:{b['rule']['value']}"
              if isinstance(b["rule"], dict) else str(b["rule"]))
        ar = (f"{a['rule']['type']}:{a['rule']['value']}"
              if isinstance(a["rule"], dict) else str(a["rule"]))
        print(f"[{mark}] {r['name'][:26]:<26} -> tag={r['tag']:<18}")
        print(f"          tgt {_fmt(b['score_target']):>10} -> {_fmt(a['score_target']):<10}"
              f"  ec {b['energy_cost']} -> {a['energy_cost']}"
              f"  rule {br[:26]} -> {ar[:26]}")
        print(f"          依据: {r['basis']}")

    if args.dry_run:
        chg = [r for r in plan_rows if r["changed"] and not r["skip"]]
        print(f"\ndry-run: 将改{len(chg)} 个, 跳过 {len([r for r in plan_rows if r['skip']])} 个, "
              f"共 {len(plan_rows)} 个。--apply 才写盘。")
        return 0

    # ---- 写入 ----
    chg = [r for r in plan_rows if r["changed"] and not r["skip"]]
    if not chg:
        print("\n没有需要改的场次。")
        return 0
    bak = SESSIONS.with_suffix(f".json.bak-{time.strftime('%Y%m%d-%H%M%S')}")
    shutil.copy2(SESSIONS, bak)
    print(f"\n已备份 -> {bak.name}")

    by_id = {r["id"]: r for r in chg}
    tagged = 0
    for s in data.get("sessions") or []:
        # ⚠️ **tag 要给全部场次写**, 不只是有条件变化的。
        # tag 是场次的**属性**(它属于哪一类), 与三个条件当前是否为新值无关 ——
        # 首版只给chg 里的写, 导致 7 个「条件本来就对」的场次没有 tag,
        # 事后无法按tag 检索/核对。2026-10-06 实测漏了7 个。
        r = by_id.get(s.get("id"))
        c = classify_session(s)
        s["tag"] = c["tag"]
        s["tag_basis"] = c["basis"]
        tagged += 1
        if not r:
            continue
        # parent 字段**保留不动**(用户 2026-10-06: 只是不再用, 不删历史)
        s["score_target"] = r["after"]["score_target"]
        s["energy_cost"] = r["after"]["energy_cost"]
        s["rule"] = r["after"]["rule"]
    data["tag_migrated_at"] = time.strftime("%Y-%m-%d %H:%M:%S")

    tmp = SESSIONS.with_suffix(".json.tmp")
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=1)
    os.replace(tmp, SESSIONS)
    print(f"已写入 {len(chg)} 个场次; 跳过 {len([r for r in plan_rows if r['skip']])} 个"
          f"(正在跑/队列里, 避免它们被 bot 的采样写入覆盖)。")
    print("parent 字段全部保留; tag 字段为新增。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
