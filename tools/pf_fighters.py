"""卡名 -> 角色类型 (基础角色) —— 数据源 tools/data/variants.json (sgmnow 侧导出)。

2026-10-13 用户口径: 角色仓库 (COLLECTION / FIGHTERS) 里 **卡名可重复、角色框多、
坐标不固定** —— 与其在小字号名条上硬读角色本名, 不如读**卡名 (装饰大字)** 再映射到
角色类型: 闭集 306 条能把 OCR 的抖动纠回来, 实测比名条更稳。

实测 (3 屏 x 14 张完整卡 = 42 格, MAA 绑定真机帧):
  卡名精确命中本表 42/42 = 100%; 卡名 -> 角色类型 与名条直读冲突 0 次
  (2 次不一致是名条自己读错 'FIUA'/'SQUGLY', 不是卡名侧)。
  几个我手抄时会写错的: 官方是 'Djinn Frizz' / 'Ultraviolent' / 'Pickle Baller'。
表本身自检: 306 条归一后**无重名**、无空卡名、306/306 自命中; 负样本
  ('FALL OF THE DARK' / 'ZZZZZZ' / 'Eye of the Storm') 全部 miss 不误吸。

⚠️ 输出的是**显示口径**角色名 (见 CHAR_DISPLAY), 不是 json 里的内部标识 —— 两套混用
   会让 pf_bot.CHARACTER_CHIPS 静默 miss。

本模块刻意**不 import 其它 tools 模块** (同 pf_domain/pf_artag 的取舍): 纯查表,
无 I/O 副作用 —— json 懒加载 + 进程内缓存, import 本模块不会碰设备。
"""
from __future__ import annotations

import difflib
import json
from pathlib import Path

VARIANTS_PATH = Path(__file__).resolve().parent / "data" / "variants.json"

# 归一化口径与 pf_artag 一致: 只留字母数字, 丢空格/点/横线/撇号。
# ⚠️ 例: 表里写 'Ms. Fortune'/'Robo-Fortune', 游戏内标识是 MSFORTUNE/ROBOFORTUNE。
_FUZZY_CUTOFF = 0.75

# ---- 角色名口径 (2026-10-13) ----------------------------------------------------
# variants.json 的 `name` 形如 'Char_MsFortune_D_V3_Name' —— 第 2 段是**游戏内部标识**
# (无空格无符号: MsFortune / BigBand / BlackDahlia / RoboFortune)。项目里角色名另有
# **一套显示口径**: pf_bot.CHARACTER_CHIPS 的键用官方写法 ('Ms. Fortune' / 'Big Band')。
# ⚠️⚠️ 两套不能混: 直接把内部标识丢进 CHARACTER_CHIPS/CLASS_CHIPS 会**静默 miss**。
#    实测 12 个 chips 已覆盖的角色里有 3 个对不上 (MsFortune / BigBand / BlackDahlia);
#    RoboFortune 同理, 只是它本来就还没有 chip。本模块一律输出**显示口径**,
#    需要内部标识时用 internal_of_base()。
# 18 个角色全给 (缺 chips 的 6 个 = Parasoul/Peacock/Valentine/Squigly/Robo-Fortune/
# Umbrella, 与 pf_bot.CLASS_CHIPS 只 12 格的事实一致)。
CHAR_DISPLAY = {
    "an": "Annie", "bb": "Big Band", "bd": "Black Dahlia", "be": "Beowulf",
    "ce": "Cerebella", "do": "Double", "el": "Eliza", "fi": "Filia",
    "fu": "Fukua", "ma": "Marie", "mf": "Ms. Fortune", "pa": "Parasoul",
    "pe": "Peacock", "pw": "Painwheel", "rf": "Robo-Fortune", "sq": "Squigly",
    "um": "Umbrella", "va": "Valentine",
}

_cache: dict | None = None


def _norm(s: str) -> str:
    return "".join(ch for ch in (s or "").upper() if ch.isalnum())


def _load() -> dict:
    """懒加载并缓存 variants.json -> 三张索引。

    返回 {'by_name': {归一化卡名: (base, element, 官方卡名)},
          'base_internal': {base: 游戏内部标识}, 'size': 条目数}
    """
    global _cache
    if _cache is None:
        raw = json.loads(VARIANTS_PATH.read_text(encoding="utf-8"))
        by_name, base_internal = {}, {}
        for entry in raw.values():
            base = entry.get("base")
            # name 形如 'Char_Eliza_D_V4_Name' -> 第 2 段就是角色内部标识 (18 个 base 全唯一)
            parts = (entry.get("name") or "").split("_")
            if len(parts) > 1:
                base_internal[base] = parts[1]
            fandom = (entry.get("fandom") or "").strip()
            if fandom:
                by_name[_norm(fandom)] = (base, entry.get("element"), fandom)
        _cache = {"by_name": by_name, "base_internal": base_internal, "size": len(raw)}
    return _cache


def loaded_size() -> int:
    """表内条目数 (自检用)。"""
    return _load()["size"]


def char_of_base(base: str) -> str | None:
    """基础角色码 -> **显示口径**角色名: 'fi' -> 'Filia', 'mf' -> 'Ms. Fortune'。

    表里没有的 base (未来新增角色) 回落内部标识, **不猜** —— 但要注意回落值不符合
    pf_bot.CHARACTER_CHIPS 的显示口径, 用之前先补进 CHAR_DISPLAY。
    """
    if base in CHAR_DISPLAY:
        return CHAR_DISPLAY[base]
    return _load()["base_internal"].get(base)


def internal_of_base(base: str) -> str | None:
    """基础角色码 -> 游戏内部标识 ('mf' -> 'MsFortune')。"""
    return _load()["base_internal"].get(base)


def resolve(card_name: str, cutoff: float = _FUZZY_CUTOFF) -> tuple[str, str | None, str | None, str]:
    """卡名 -> (base, 角色名, 元素id, 命中方式)。

    命中方式: 'exact' / 'fuzzy:0.87' / 'miss:0.42'。**不做猜测** —— 低于 cutoff 时
    base/角色名 返回 None, 由调用方决定是留痕还是人工核对 (同 _match_known 的取舍)。
    """
    idx = _load()
    n = _norm(card_name)
    if not n:
        return "", None, None, "empty"
    hit = idx["by_name"].get(n)
    how = "exact"
    if hit is None:
        best, score = None, 0.0
        for key, val in idx["by_name"].items():
            r = difflib.SequenceMatcher(None, n, key).ratio()
            if r > score:
                best, score = val, r
        if score < cutoff:
            return "", None, None, f"miss:{score:.2f}"
        hit, how = best, f"fuzzy:{score:.2f}"
    base, element, _ = hit
    return base, char_of_base(base), element, how


def char_of(card_name: str, cutoff: float = _FUZZY_CUTOFF) -> str | None:
    """卡名 -> 显示口径角色名 (角色类型); 认不出的返回 None (绝不硬猜)。"""
    return resolve(card_name, cutoff)[1]


def base_of(card_name: str, cutoff: float = _FUZZY_CUTOFF) -> str | None:
    """卡名 -> 基础角色码 ('fi'/'mf'...); 认不出的返回 None。"""
    return resolve(card_name, cutoff)[0] or None


def element_of(card_name: str, cutoff: float = _FUZZY_CUTOFF) -> int | None:
    """卡名 -> 元素**整数 id** (variants.json 原值, 取值 0..5)。

    ⚠️ 整数 -> 元素名 的口径 **未核实**, 所以这里只透传原值, 不在这层做映射。
    已观察到的线索 (2026-10-13, **不足以定案**):
      - 取值 6 个 (0..5), 与 pf_bot.ELEMENT_CHIPS 的 6 个名字
        (fire/water/wind/light/dark/neutral) 数量吻合;
      - element=0 只 17 条, 分散在 13 个角色上, name 都是 `Char_<X>_D/G/S_Vn`
        ——像"元素未定/未上架"的变体, 猜测 0=neutral;
      - 4 与 "明显偏暗" 的卡对得上: 'Dark Lord'(Draccy/Eliza)=4,
        'Ultraviolent'(Peacock, 深色调)=4; 2 与 'Regally Blonde'(Parasoul)=2。
    要定案得拿一条**独立地面真值** (卡面元素图标/边框色或官方卡表) 对一遍, 单靠颜色
    印象不算证据 —— 在那之前不要在别处 encode 这个顺序。
    """
    return resolve(card_name, cutoff)[2]
