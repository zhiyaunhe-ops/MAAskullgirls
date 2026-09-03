"""PF 数据层: 场次管理 + 计分历史存储 (sessions.json / score_log.csv)。

与操作逻辑 (pf_bot) 解耦: pf_bot 结算时调用 record/append_csv,
WebUI 通过 list_sessions/create/update/delete/series 读写。
CSV 列: time,fight_no,score,delta[,streak][,session]; 按列位置解析
(旧文件表头缺 streak 列、旧行缺 session 列 -> 统一归 Default 场次)。
"""
import csv
import json
import threading
import time
from pathlib import Path

from pf_env import STATE

PROJECT_ROOT = Path(__file__).resolve().parent.parent
DATA_DIR = PROJECT_ROOT / "debug" / "pf"
SESSIONS_PATH = DATA_DIR / "sessions.json"
SCORE_CSV = DATA_DIR / "score_log.csv"
HISTORY_CAP = 3000

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


class ScoreStore:
    """场次 + 计分历史 (pf_bot 主线程与 WebUI 线程共享)。"""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self.sessions: list = []       # [{id,name,rule,created}]
        self.history_by: dict = {}     # {session_id: [{ts,score,streak,fight}]}
        self.session_id = None         # 当前/最近一次运行的场次
        self._load()

    # ---------- 场次 ----------
    def _load(self) -> None:
        try:
            with open(SESSIONS_PATH, encoding="utf-8") as f:
                data = json.load(f)
            if isinstance(data.get("sessions"), list):
                self.sessions = [s for s in data["sessions"]
                                 if isinstance(s, dict) and s.get("id")]
        except (OSError, json.JSONDecodeError, AttributeError):
            pass
        if not any(s.get("id") == "default" for s in self.sessions):
            # 历史 CSV 无 session 列的旧行统一归 Default (无规则)
            self.sessions.append({"id": "default", "name": "Default",
                                  "rule": None, "created": time.time()})
        self._save_sessions()
        self._load_history()
        # 老数据倒推: 场次记录缺总分时, 从该场次最后一个采样回填
        changed = False
        for s in self.sessions:
            pts = self.history_by.get(s["id"], [])
            if pts and s.get("score") != pts[-1].get("score"):
                s["score"] = pts[-1].get("score")
                changed = True
        if changed:
            self._save_sessions()

    def _save_sessions(self) -> None:
        DATA_DIR.mkdir(parents=True, exist_ok=True)
        tmp = SESSIONS_PATH.with_suffix(".json.tmp")
        try:
            with open(tmp, "w", encoding="utf-8") as f:
                json.dump({"sessions": self.sessions}, f, ensure_ascii=False, indent=1)
            tmp.replace(SESSIONS_PATH)
        except OSError:
            pass

    def get(self, sid: str):
        return next((s for s in self.sessions if s.get("id") == sid), None)

    def list_sessions(self) -> list:
        """带数据量/最近活动的场次列表 (WebUI 展示用)。"""
        out = []
        for s in self.sessions:
            pts = self.history_by.get(s["id"], [])
            out.append({"id": s["id"], "name": s["name"], "rule": s.get("rule"),
                        "parent": s.get("parent"),
                        "score": s.get("score"),
                        "score_target": s.get("score_target"),
                        "rest_every": s.get("rest_every") or 0,
                        "rest_minutes": s.get("rest_minutes") or 0,
                        "created": s.get("created"), "count": len(pts),
                        "last_ts": pts[-1]["ts"] if pts else None})
        return out

    def create(self, name: str, rule, rest_every=0, rest_minutes=0,
               score_target=None) -> dict:
        sess = {"id": "s%d" % int(time.time() * 1000), "name": name,
                "rule": clean_rule(rule), "created": time.time(),
                "rest_every": clean_rest(rest_every),
                "rest_minutes": clean_rest(rest_minutes),
                "score_target": clean_target(score_target)}
        with self._lock:
            self.sessions.append(sess)
            self._save_sessions()
        return sess

    def create_child(self, parent_sid: str, name=None) -> dict:
        """建子场次 (周期性分类的每一期): 继承父场次规则/上界/休息, 默认名=父名+日期。"""
        p = self.get(parent_sid)
        if not p:
            raise KeyError(parent_sid)
        base = (name or "").strip() or f"{p['name']} {time.strftime('%m-%d')}"
        name, n = base, 2
        existing = {s.get("name") for s in self.sessions}
        while name in existing:
            name = f"{base}-{n}"
            n += 1
        sess = {"id": "s%d" % int(time.time() * 1000), "name": name,
                "parent": p["id"], "rule": p.get("rule"), "created": time.time(),
                "rest_every": p.get("rest_every") or 0,
                "rest_minutes": p.get("rest_minutes") or 0,
                "score_target": p.get("score_target")}
        with self._lock:
            self.sessions.append(sess)
            self._save_sessions()
        return sess

    def update(self, sid: str, name=None, rule=UNSET,
               rest_every=UNSET, rest_minutes=UNSET, score_target=UNSET):
        sess = self.get(sid)
        if not sess:
            raise KeyError(sid)
        if name:
            sess["name"] = name
        if rule is not UNSET:
            sess["rule"] = clean_rule(rule)
        if rest_every is not UNSET:
            sess["rest_every"] = clean_rest(rest_every)
        if rest_minutes is not UNSET:
            sess["rest_minutes"] = clean_rest(rest_minutes)
        if score_target is not UNSET:
            sess["score_target"] = clean_target(score_target)
        with self._lock:
            self._save_sessions()
        return sess

    def delete(self, sid: str) -> None:
        with self._lock:
            self.sessions = [s for s in self.sessions if s.get("id") != sid]
            self.history_by.pop(sid, None)
            if self.session_id == sid:
                self.session_id = None
            self._save_sessions()

    def set_session(self, sid: str):
        """绑定当前运行场次 (开始前调用), 返回场次 dict。"""
        sess = self.get(sid)
        if not sess:
            raise KeyError(sid)
        self.session_id = sid
        return sess

    # ---------- 计分 ----------
    def record(self, sid: str, score: int, streak, fight: int) -> None:
        with self._lock:
            pts = self.history_by.setdefault(sid, [])
            pts.append({"ts": time.time(), "score": score,
                        "streak": streak, "fight": fight})
            if len(pts) > HISTORY_CAP:
                del pts[: len(pts) - HISTORY_CAP]
            sess = self.get(sid)          # 场次随采样滚动记录最新总分
            if sess is not None and sess.get("score") != score:
                sess["score"] = score
                self._save_sessions()

    def append_csv(self, sid: str, score: int, delta: int, streak, fight: int) -> None:
        DATA_DIR.mkdir(parents=True, exist_ok=True)
        new = not SCORE_CSV.exists()
        with open(SCORE_CSV, "a", newline="", encoding="utf-8") as f:
            w = csv.writer(f)
            if new:
                w.writerow(["time", "fight_no", "score", "delta", "streak", "session"])
            w.writerow([time.strftime("%Y-%m-%d %H:%M:%S"), fight, score, delta, streak, sid])

    def series(self, sids: list) -> list:
        """多场次曲线数据 [{id,name,rule,points}]。"""
        out = []
        for sid in sids:
            s = self.get(sid)
            if s:
                out.append({"id": sid, "name": s["name"], "rule": s.get("rule"),
                            "points": list(self.history_by.get(sid, []))})
        return out

    def _load_history(self) -> None:
        """score_log.csv -> history_by。按列位置解析, 兼容旧 4/5 列行与过时表头。"""
        if not SCORE_CSV.exists():
            return
        loaded = 0
        try:
            with open(SCORE_CSV, newline="", encoding="utf-8") as f:
                for row in csv.reader(f):
                    if len(row) < 4 or row[0] == "time":
                        continue
                    try:
                        ts = time.mktime(time.strptime(row[0], "%Y-%m-%d %H:%M:%S"))
                        sid = row[5] if len(row) > 5 and row[5] else "default"
                        pts = self.history_by.setdefault(sid, [])
                        pts.append({"ts": ts, "score": int(row[2]),
                                    "streak": int(row[4]) if len(row) > 4 and row[4] else None,
                                    "fight": int(row[1])})
                        loaded += 1
                    except ValueError:
                        continue
        except OSError:
            return
        if loaded:
            STATE.log(f"载入历史计分 {loaded} 条（{len(self.history_by)} 个场次）")


STORE = ScoreStore()


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
