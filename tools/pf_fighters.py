"""卡名 -> 角色类型 (基础角色) —— 数据源 tools/data/variants.json (sgmnow 侧导出)。

2026-10-13 用户口径: 角色仓库 (COLLECTION / FIGHTERS) 里 **卡名可重复、角色框多、
坐标不固定** —— 与其在小字号名条上硬读角色本名, 不如读**卡名 (装饰大字)** 再映射到
角色类型: 闭集 306 条能把 OCR 的抖动纠回来, 实测比名条更稳。

实测 (3 屏 x 14 张完整卡 = 42 格, MAA 绑定真机帧):
  卡名精确命中本表 42/42 = 100%; 卡名 -> 角色类型 与名条直读冲突 0 次
  (2 次不一致是名条自己读错 'FIUA'/'SQUGLY', 不是卡名侧)。
  几个我手抄时会写错的: 官方是 'Djinn Frizz' / 'Ultraviolent' / 'Pickle Baller'。

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

_cache: dict | None = None


def _norm(s: str) -> str:
    return "".join(ch for ch in (s or "").upper() if ch.isalnum())


def _load() -> dict:
    """懒加载并缓存 variants.json -> 三张索引。

    返回 {'by_name': {归一化卡名: (base, element, 官方卡名)},
          'base_char': {base: 角色名}, 'size': 条目数}
    """
    global _cache
    if _cache is None:
        raw = json.loads(VARIANTS_PATH.read_text(encoding="utf-8"))
        by_name, base_char = {}, {}
        for entry in raw.values():
            base = entry.get("base")
            # name 形如 'Char_Eliza_D_V4_Name' -> 第 2 段就是角色名 (18 个 base 全唯一)
            parts = (entry.get("name") or "").split("_")
            if len(parts) > 1:
                base_char[base] = parts[1]
            fandom = (entry.get("fandom") or "").strip()
            if fandom:
                by_name[_norm(fandom)] = (base, entry.get("element"), fandom)
        _cache = {"by_name": by_name, "base_char": base_char, "size": len(raw)}
    return _cache


def loaded_size() -> int:
    """表内条目数 (自检用)。"""
    return _load()["size"]


def char_of_base(base: str) -> str | None:
    """基础角色码 -> 角色名: 'fi' -> 'Filia', 'mf' -> 'MsFortune'。"""
    return _load()["base_char"].get(base)


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
    return base, idx["base_char"].get(base), element, how


def char_of(card_name: str, cutoff: float = _FUZZY_CUTOFF) -> str | None:
    """卡名 -> 角色名 (角色类型); 认不出的返回 None (绝不硬猜)。"""
    return resolve(card_name, cutoff)[1]


def base_of(card_name: str, cutoff: float = _FUZZY_CUTOFF) -> str | None:
    """卡名 -> 基础角色码 ('fi'/'mf'...); 认不出的返回 None。"""
    return resolve(card_name, cutoff)[0] or None


def element_of(card_name: str, cutoff: float = _FUZZY_CUTOFF) -> int | None:
    """卡名 -> 元素**整数 id** (variants.json 原值)。

    ⚠️ 整数 -> 元素名 的口径 (dark/light/fire/water/wind 的排序) **未核对**, 所以
    这里只透传原值, 不在这层做映射 —— 需要名字时对着表核一次再补。
    """
    return resolve(card_name, cutoff)[2]
