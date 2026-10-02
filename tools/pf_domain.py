"""Pure PF configuration normalization and score sampling rules.

This module has no runtime initialization, filesystem, or emulator dependencies.
Names remain available through pf_store for existing callers.
"""

UNSET = object()   # update() 中区分「未传 rule」与「rule=None」


def clean_rest(v) -> int:
    """rest 字段清洗: 非负整数, 非法/缺失回 0 (=不启用)。"""
    try:
        return max(0, int(v))
    except (TypeError, ValueError):
        return 0


def clean_target(v):
    """分数上界清洗: 空值 -> None (=不限), 否则非负整数。"""
    if v is None or v == "":
        return None
    try:
        return max(0, int(v)) or None
    except (TypeError, ValueError):
        return None


def clean_rule(rule):
    """校验规则结构, 合法返回 {"type","value"}, 否则 None。"""
    if not isinstance(rule, dict):
        return None
    t, v = rule.get("type"), rule.get("value")
    if t in ("element", "class") and v:
        return {"type": t, "value": str(v)}
    return None


def clean_energy(v) -> int:
    """能量门槛清洗: 1-10, 非法/缺失回默认 4。"""
    try:
        return max(1, min(10, int(v)))
    except (TypeError, ValueError):
        return 4


def clean_scene(v):
    """场地绑定清洗: 去空白, 空串/缺失回 None (=不绑定, 就打散打)。"""
    if v is None:
        return None
    s = str(v).strip()
    return s or None


class ScoreTracker:
    """总分采样基线: 判定是否采样/算 delta, 换场次自动重置 (pf_bot 调 on_score)。"""

    def __init__(self) -> None:
        self._reset(None)

    def _reset(self, sid) -> None:
        self.sid = sid
        self.score_last = None    # 最近一次记录的总分
        self.last_fight = 0       # 上次采样时的场次号

    def ensure(self, sid) -> bool:
        """场次变化时重置基线 (暂停后续跑同场次不重置); 返回是否重置过。"""
        if sid == self.sid:
            return False
        self._reset(sid)
        return True

    def on_score(self, score: int, fight: int):
        """返回采样事件: {'event':'fight','delta':int|None} / {'event':'drift'} / None。"""
        prev = self.score_last
        if fight != self.last_fight:
            self.last_fight = fight
            self.score_last = score
            return {"event": "fight", "delta": (score - prev) if prev is not None else None}
        if score != self.score_last:
            self.score_last = score
            return {"event": "drift"}
        return None
