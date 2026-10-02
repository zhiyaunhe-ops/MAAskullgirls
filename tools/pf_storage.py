"""PF session/history persistence with explicit runtime dependencies.

Importing this module does not construct a store or touch storage. Construction
loads/migrates the supplied directory; callers also supply a version ledger,
logger, and read-only JJC reference provider. JSON/CSV formats remain unchanged.
"""
import csv
import json
import threading
import time
from pathlib import Path

from pf_domain import (UNSET, clean_energy, clean_rest, clean_rule,
                       clean_scene, clean_target)

HISTORY_CAP = 3000


class ScoreStore:
    """场次 + 计分历史 (pf_bot 主线程与 WebUI 线程共享)。"""

    def __init__(self, *, data_dir, logger, version_ledger, jjc_ref) -> None:
        """Load storage using explicitly supplied paths and collaborators.

        Construction still loads and migrates storage. The supplied ledger owns
        baseline imports; jjc_ref supplies ordinary change records.
        """
        self.data_dir = Path(data_dir)
        self.sessions_path = self.data_dir / "sessions.json"
        self.score_csv = self.data_dir / "score_log.csv"
        self._log = logger
        self._versions = version_ledger
        self._jjc_ref = jjc_ref
        self._lock = threading.Lock()
        self.sessions: list = []       # [{id,name,rule,created}]
        self.history_by: dict = {}     # {session_id: [{ts,score,streak,fight}]}
        self.session_id = None         # 当前/最近一次运行的场次
        self.queue: list = []          # 接力队列 (有序 session_id, 打完一场自动接下一场)
        self._load()

    # ---------- 场次 ----------
    def _load(self) -> None:
        active = None
        try:
            with open(self.sessions_path, encoding="utf-8") as f:
                data = json.load(f)
            if isinstance(data.get("sessions"), list):
                self.sessions = [s for s in data["sessions"]
                                 if isinstance(s, dict) and s.get("id")]
            active = data.get("active")          # 上次运行的场次指针 (2026-09-27 起持久化)
            q = data.get("queue")
            if isinstance(q, list):
                self.queue = [str(x) for x in q if isinstance(x, str)]
        except (OSError, json.JSONDecodeError, AttributeError):
            pass
        if not any(s.get("id") == "default" for s in self.sessions):
            # 历史 CSV 无 session 列的旧行统一归 Default (无规则)
            self.sessions.append({"id": "default", "name": "Default",
                                  "rule": None, "created": time.time()})
        if active and any(s.get("id") == active for s in self.sessions):
            self.session_id = active             # 重启后托盘/WebUI 还能"接着上次跑"
        ids = {s.get("id") for s in self.sessions}
        self.queue = [x for x in self.queue if x in ids]   # 队列里已删的场次顺手清掉
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
        # 老场次补 v1 基线: 版本账本对历史数据同样成立, 不然后建的场次无源头
        for s in list(self.sessions):
            try:
                self._versions.ensure_baseline(s)
            except Exception:  # noqa: BLE001 版本记账失败不能拖垮主流程
                pass

    def _save_sessions(self) -> None:
        self.data_dir.mkdir(parents=True, exist_ok=True)
        tmp = self.sessions_path.with_suffix(".json.tmp")
        try:
            with open(tmp, "w", encoding="utf-8") as f:
                json.dump({"sessions": self.sessions, "active": self.session_id,
                           "queue": self.queue},
                          f, ensure_ascii=False, indent=1)
            tmp.replace(self.sessions_path)
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
                        "scene": s.get("scene"),
                        "score": s.get("score"),
                        "score_target": s.get("score_target"),
                        "energy_cost": clean_energy(s.get("energy_cost")),
                        "rest_every": s.get("rest_every") or 0,
                        "rest_minutes": s.get("rest_minutes") or 0,
                        "created": s.get("created"), "count": len(pts),
                        "last_ts": pts[-1]["ts"] if pts else None})
        return out

    def create(self, name: str, rule, rest_every=0, rest_minutes=0,
               score_target=None, energy_cost=4, scene=None) -> dict:
        sess = {"id": "s%d" % int(time.time() * 1000), "name": name,
                "rule": clean_rule(rule), "created": time.time(),
                "rest_every": clean_rest(rest_every),
                "rest_minutes": clean_rest(rest_minutes),
                "score_target": clean_target(score_target),
                "energy_cost": clean_energy(energy_cost),
                "scene": clean_scene(scene)}
        with self._lock:
            self.sessions.append(sess)
            self._save_sessions()
        self._ver(sess, op="create")
        return sess

    def create_child(self, parent_sid: str, name=None) -> dict:
        """建子场次 (周期性分类的每一期): 继承父场次规则/上界/能量/休息, 默认名=父名+日期。"""
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
                "score_target": p.get("score_target"),
                "energy_cost": clean_energy(p.get("energy_cost")),
                "scene": p.get("scene")}
        with self._lock:
            self.sessions.append(sess)
            self._save_sessions()
        self._ver(sess, op="child", before=p)
        return sess

    def _ver(self, sess: dict, op: str, before: dict = None):
        """写一笔配置版本。记账失败只告警, 不阻断主流程。"""
        try:
            return self._versions.record(sess.get("id"), sess, before, op=op,
                                   jjc=self._jjc_ref())
        except Exception as e:  # noqa: BLE001
            self._log(f"版本记账失败: {e}", "warn")
            return None

    def update(self, sid: str, name=None, rule=UNSET,
               rest_every=UNSET, rest_minutes=UNSET, score_target=UNSET,
               energy_cost=UNSET, scene=UNSET):
        sess = self.get(sid)
        if not sess:
            raise KeyError(sid)
        before = dict(sess)          # 变更前的完整快照, 用于算 diff
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
        if energy_cost is not UNSET:
            sess["energy_cost"] = clean_energy(energy_cost)
        if scene is not UNSET:
            sess["scene"] = clean_scene(scene)
        with self._lock:
            self._save_sessions()
        self._ver(sess, op="update", before=before)
        return sess

    def delete(self, sid: str) -> None:
        sess = self.get(sid)
        if sess:
            try:
                self._versions.mark_deleted(sid, sess, self._jjc_ref())
            except Exception as e:  # noqa: BLE001
                self._log(f"版本记账失败: {e}", "warn")
        with self._lock:
            self.sessions = [s for s in self.sessions if s.get("id") != sid]
            self.history_by.pop(sid, None)
            if self.session_id == sid:
                self.session_id = None
            self.queue = [x for x in self.queue if x != sid]
            self._save_sessions()

    def set_session(self, sid: str):
        """绑定当前运行场次 (开始前调用), 返回场次 dict。指针落盘供重启后恢复。"""
        sess = self.get(sid)
        if not sess:
            raise KeyError(sid)
        self.session_id = sid
        self._save_sessions()
        return sess

    # ---------- 接力队列 ----------
    def queue_list(self) -> list:
        """当前接力队列 (session_id 有序列表)。"""
        return list(self.queue)

    def queue_named(self) -> list:
        """接力队列带场次名 (WebUI 展示用): [{id,name}]。"""
        out = []
        for sid in self.queue:
            s = self.get(sid)
            if s:
                out.append({"id": sid, "name": s["name"]})
        return out

    def queue_set(self, sids: list) -> list:
        """整体重设接力队列 (前端管理完整顺序): 去重、丢掉不存在的场次。"""
        seen, clean = set(), []
        for x in sids or []:
            x = str(x)
            if x in seen or not self.get(x):
                continue
            seen.add(x)
            clean.append(x)
        with self._lock:
            self.queue = clean
            self._save_sessions()
        return list(self.queue)

    def queue_pop(self):
        """弹出队首场次 id (空队列返回 None), 供打完一场后自动接续。"""
        with self._lock:
            if not self.queue:
                return None
            sid = self.queue.pop(0)
            self._save_sessions()
        return sid

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
        self.data_dir.mkdir(parents=True, exist_ok=True)
        new = not self.score_csv.exists()
        with open(self.score_csv, "a", newline="", encoding="utf-8") as f:
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
        if not self.score_csv.exists():
            return
        loaded = 0
        try:
            with open(self.score_csv, newline="", encoding="utf-8") as f:
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
            self._log(f"载入历史计分 {loaded} 条（{len(self.history_by)} 个场次）")

