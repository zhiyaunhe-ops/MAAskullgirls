"""JJC (Prize Fight) 日程数据层 + 场次×规则版本账本。

数据源: Krazete <https://krazete.github.io/sgmnow> 背后的 SGM Score Cutoffs 表
        now 页 A 列 (gviz CSV 导出)。该表由 Apps Script 在 SGM 每日 reset 时强制刷新,
        所以它是「当天台上开的到底是谁」的权威来源。

两件事:

1. 每日快照 ( ``debug/pf/jjc/snapshots/<YYYY-MM-DD>.json`` )
   按 **SGM reset 日** 归档 —— reset 在 Pacific 10:00, 折合亚洲时间凌晨 1-2 点,
   因此不能用本机日历日期, 否则凌晨跑的会被算进前一天。
   快照里保留 **原始 A 列** 作为证据, 任何 parse 结论都能回查到原始单元格。

2. 场次×规则版本账本 ( ``debug/pf/jjc/versions.json`` )
   pf_store 每次变更场次的**配置** (名字/规则/上界/能量/休息) 时记一笔。
   只有内容指纹变化才写记录 —— 重复保存同一个值不产生噪声版本。
   每条带时间戳 + 当天 JJC 快照引用, 使「某天把某场的规则改成了什么、当天台上是
   谁」可追溯。score 是运行状态, 不是配置, 不入版本。

已知坑:
  - now 页行位置**不固定**: gviz `range=a:a` 会跳过空单元格, Krazete 又随时改版,
    所以一律用标签正则定位, 绝不写死行号 (线上 sgmnow/index.js 亦如此)。
  - 表里有 `Loading...` / `Inactive` 两类哨兵值: 前者是公式没算完的瞬时态, 退避
    重试即可; 后者是「本类 PF 当前没开」的正常回答, 不是错误。
  - `SMYM PF:` 标签不带 Current/Last 前缀, 开没开只能看它下面那个数字旗标。
"""
import csv
import hashlib
import io
import json
import re
import threading
import time
import urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
JJ_DIR = PROJECT_ROOT / "debug" / "pf" / "jjc"
SNAP_DIR = JJ_DIR / "snapshots"
VERSIONS_PATH = JJ_DIR / "versions.json"
LATEST_PATH = JJ_DIR / "latest.json"

SHEET_ID = "1hpmUc__uYo0-tq10tampy7CDIfALn6N5_sMELTBlTOs"
SHEET_PAGE = "now"
SOURCE_URL = (f"https://docs.google.com/spreadsheets/d/{SHEET_ID}"
              f"/gviz/tq?sheet={SHEET_PAGE}&tqx=out:csv&range=A1:A90")
SITE_URL = "https://krazete.github.io/sgmnow/"

UA = "MAAskullgirls/1.0 (+jjc_store)"
FETCH_TIMEOUT = 20
VOLATILE_RETRY = 3          # 抓到 Loading... 时的重试次数
VOLATILE_SENTINELS = {"loading...", "loading", "thinking..."}
INACTIVE_SENTINELS = {"inactive", "none", "n/a", "-"}

# (key, 中文名, 标签正则, 语义分类) —— 顺序即展示顺序, 与 sgmnow 面板一致
ENTRIES = [
    ("rift", "裂缝元素", r"Rift Element:", "rift"),
    ("char", "角色 PF", r"Character PF:", "character"),
    ("elem", "元素 PF", r"Elemental PF:", "element"),
    ("medi", "Medici PF（金币）", r"Medici PF:", "gold"),
    ("smym", "SMYM PF（招式）", r"SMYM PF:", "move"),
    ("star", "Seeing Stars PF（星）", r"Seeing Stars PF:", "assist"),
    ("holi", "月常 PF", r"Monthly PF:", "monthly"),
]
_DAILY_LABEL = "Current Daily Events:"
_RESET_HOUR = 10            # SGM 每日 reset = Pacific 10:00

# 版本账本追踪的配置字段 (score 等非配置字段不在此列)
CFG_FIELDS = ("name", "rule", "parent", "energy_cost",
              "score_target", "rest_every", "rest_minutes")


class SourceError(Exception):
    """数据源不可用 / 抓到瞬时态。"""


# ------------------------------------------------------------------ 工具

def _atomic_write(path: Path, obj) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + ".tmp")
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(obj, f, ensure_ascii=False, indent=1)
    tmp.replace(path)


def fingerprint(obj) -> str:
    """内容指纹: 判定「这次改动有没有真的改东西」的根据。"""
    blob = json.dumps(obj, sort_keys=True, ensure_ascii=False,
                      separators=(",", ":"))
    return hashlib.sha1(blob.encode("utf-8")).hexdigest()[:12]


def _pacific_tzinfo():
    """America/Los_Angeles。Windows 常缺 tzdata, 拿不到返回 None。"""
    try:
        from zoneinfo import ZoneInfo
        return ZoneInfo("America/Los_Angeles")
    except Exception:  # noqa: BLE001
        return None


def _us_pacific_offset(utc_naive: datetime) -> timedelta:
    """zoneinfo 不可用时的兜底: 手算美国夏令时 (2007 年后规则)。

    DST 起于 3 月第二个周日 02:00 本地, 止于 11 月第一个周日 02:00 本地。
    """
    def nth_sunday(year: int, month: int, n: int) -> datetime:
        d = datetime(year, month, 1)
        d += timedelta(days=(6 - d.weekday()) % 7)
        return d + timedelta(weeks=n - 1)

    if utc_naive.year <= 2007:
        return timedelta(hours=-8)
    start = nth_sunday(utc_naive.year, 3, 2) + timedelta(hours=2)
    end = nth_sunday(utc_naive.year, 11, 1) + timedelta(hours=2)
    dst = start <= utc_naive < end
    return timedelta(hours=-7 if dst else -8)


def sgm_day(ts: float = None) -> str:
    """返回 ts 所属的 SGM 游戏日 (YYYY-MM-DD)。

    reset 之后算新的一天, 所以把 Pacific 墙上时间往前推 _RESET_HOUR 再取日期。
    """
    when = datetime.fromtimestamp(ts if ts is not None else time.time(),
                                  timezone.utc)
    tz = _pacific_tzinfo()
    if tz is not None:
        try:
            local = when.astimezone(tz)
        except Exception:  # noqa: BLE001
            local = when + _us_pacific_offset(when.replace(tzinfo=None))
    else:
        local = when + _us_pacific_offset(when.replace(tzinfo=None))
    return (local - timedelta(hours=_RESET_HOUR)).strftime("%Y-%m-%d")


# ------------------------------------------------------------ 抓取 / 解析

def fetch_rows() -> list:
    """拉取 now 页 A 列, 返回字符串列表 (下标 0 = 第 1 行)。

    注意 `Loading...` 可能**长期存在**于表里的杂单元格 (实测常驻在裂缝旗标下方),
    它不是瞬时态, 也不在任何名字位 —— 所以这里不做 volatile 判定, 交给 parse。
    """
    req = urllib.request.Request(SOURCE_URL, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=FETCH_TIMEOUT) as resp:
        body = resp.read().decode("utf-8-sig", errors="replace")
    if body.lstrip().startswith("<"):
        raise SourceError("数据源返回 HTML 而非 CSV (表格权限变了?)")
    rows = [r[0] if r else "" for r in csv.reader(io.StringIO(body))]
    if not rows:
        raise SourceError("数据源为空")
    return rows


def _locate(rows: list, pattern: str) -> int:
    rx = re.compile(pattern, re.I)
    for i, v in enumerate(rows):
        if rx.search(v or ""):
            return i
    return -1


def parse(rows: list, strict: bool = True) -> dict:
    """A 列 -> 结构化日程。搞不定就抛 SourceError, 不返回半成品。

    strict=True 时, 名字位出现 `Loading...` 视为公式未落地 -> 抛错让上层重试;
    strict=False 则把它记成 name=None + volatile 标记 (宁可空着, 不猜一个值)。
    """
    if not rows:
        raise SourceError("空行集")
    last_edit = (rows[0] or "").strip()

    daily_events = []
    idx = _locate(rows, re.escape(_DAILY_LABEL))
    if idx >= 0 and idx + 1 < len(rows):
        if (rows[idx + 1] or "").strip().lower() in VOLATILE_SENTINELS:
            if strict:
                raise SourceError("Daily Events 仍在计算")
            daily_events = []
        else:
            daily_events = [x.strip() for x in (rows[idx + 1] or "").split(";")
                            if x.strip()]

    entries, missing, volatile = {}, [], []
    for key, cn, pattern, kind in ENTRIES:
        i = _locate(rows, pattern)
        if i < 0 or i + 2 >= len(rows):
            missing.append(cn)
            continue
        title = (rows[i] or "").strip()
        raw_name = (rows[i + 1] or "").strip()
        try:
            flag = float((rows[i + 2] or "").strip())
        except ValueError:
            flag = None
        lower = raw_name.lower()
        is_volatile = lower in VOLATILE_SENTINELS
        if is_volatile and strict:
            raise SourceError(f"{cn} 仍在计算 ({raw_name})")
        if is_volatile or lower in INACTIVE_SENTINELS or not raw_name:
            name, active = None, (bool(flag) if flag is not None else False)
        else:
            name = raw_name
            active = True if flag is None else bool(flag)
        if is_volatile:
            volatile.append(cn)
        low = title.lower()
        scope = "current" if "current" in low else "last" if "last" in low else "unknown"
        entries[key] = {"key": key, "label_cn": cn, "kind": kind,
                        "title": title, "raw_name": raw_name, "name": name,
                        "active": active, "scope": scope, "row": i + 1,
                        "volatile": is_volatile}
    if missing:
        raise SourceError("now 页缺少条目: " + "、".join(missing))

    payload = {"daily_events": daily_events,
               "entries": {k: {f: v[f] for f in
                               ("key", "title", "raw_name", "name",
                                "active", "scope", "volatile")}
                           for k, v in entries.items()}}
    return {"last_edit": last_edit, "payload": payload,
            "fp": fingerprint(payload), "raw_rows": rows,
            "volatile": volatile}


def ordered_entries(entries: dict) -> list:
    """按 ENTRIES 定义补齐并排序, 前端不必再管缺省。"""
    out = []
    for key, cn, _p, kind in ENTRIES:
        e = dict(entries.get(key) or {})
        e.setdefault("key", key)
        e.setdefault("label_cn", cn)
        e.setdefault("kind", kind)
        e.setdefault("title", cn)
        e.setdefault("name", None)
        e.setdefault("raw_name", "")
        e.setdefault("active", False)
        e.setdefault("volatile", False)
        e.setdefault("scope", "unknown")
        out.append(e)
    return out


# ---------------------------------------------------------------- 每日快照

class JJCStore:
    """每日快照仓库: 一个 SGM 游戏日一个文件, 内含当日所有 revision 的证据链。"""

    def __init__(self) -> None:
        self._lock = threading.Lock()

    # ---- 读 ----
    def days(self) -> list:
        if not SNAP_DIR.is_dir():
            return []
        return sorted(p.stem for p in SNAP_DIR.glob("*.json"))

    def load(self, day: str):
        try:
            with open(SNAP_DIR / f"{day}.json", encoding="utf-8") as f:
                return json.load(f)
        except (OSError, json.JSONDecodeError):
            return None

    def latest(self):
        try:
            with open(LATEST_PATH, encoding="utf-8") as f:
                snap = self.load(json.load(f).get("day"))
            if snap:
                return snap
        except (OSError, json.JSONDecodeError, AttributeError):
            pass
        for day in reversed(self.days()):
            snap = self.load(day)
            if snap:
                return snap
        return None

    # ---- 写 ----
    def refresh(self, log=None) -> dict:
        """抓一次源并落盘。同一天重复调用不会多出归档, 只追加 revision。

        名字位抓到 `Loading...` 退避重试 VOLATILE_RETRY 次; 仍不行则放行一次
        宽恕解析 (该条目留空并标 volatile), 不让一个卡住的单元格拖死整条链路。
        """
        parsed = None
        last = "抓取失败"
        for attempt in range(VOLATILE_RETRY):
            try:
                rows = fetch_rows()
                parsed = parse(rows, strict=True)
                break
            except SourceError as e:
                if "仍在计算" not in str(e) and "Loading" not in str(e):
                    last = str(e)
                    break
                last = str(e)
                if attempt + 1 < VOLATILE_RETRY:
                    time.sleep(2)
        if parsed is None:
            try:
                parsed = parse(fetch_rows(), strict=False)
            except (SourceError, Exception) as e:  # noqa: BLE001
                if log:
                    log(f"JJC 抓取失败: {last or e}", "warn")
                return {"action": "failed", "error": str(last or e)}
        if parsed.get("volatile") and log:
            log(f"JJC 条目源数据仍在计算, 已留空: "
                f"{'、'.join(parsed['volatile'])}", "warn")
        return self._commit(parsed, log)

    def _commit(self, parsed: dict, log=None) -> dict:
        day, ts = sgm_day(), time.time()
        stamp = {"ts": ts, "time": time.strftime("%Y-%m-%d %H:%M:%S"),
                 "fp": parsed["fp"], "last_edit": parsed["last_edit"]}
        prev = self.load(day)
        if prev is None:
            snap = {"day": day, "created": ts, "updated": ts,
                    "revision": 1, "fetches": 1,
                    "fp": parsed["fp"],
                    "source": {"site": SITE_URL, "sheet_id": SHEET_ID,
                               "page": SHEET_PAGE, "url": SOURCE_URL,
                               "last_edit": parsed["last_edit"]},
                    "daily_events": parsed["payload"]["daily_events"],
                    "entries": parsed["payload"]["entries"],
                    "raw_rows": parsed["raw_rows"],
                    "revisions": [stamp]}
            action = "new"
        else:
            action = "unchanged" if prev.get("fp") == parsed["fp"] else "changed"
            # revision 只在内容变化时递增; fetches 记录抓取次数 (含无变化)
            prev["fetches"] = int(prev.get("fetches", prev.get("revision", 1))) + 1
            if action == "changed":
                prev["revision"] = int(prev.get("revision", 1)) + 1
            prev.setdefault("revisions", []).append(stamp)
            prev.update({"updated": ts, "fp": parsed["fp"],
                         "source": {"site": SITE_URL, "sheet_id": SHEET_ID,
                                    "page": SHEET_PAGE, "url": SOURCE_URL,
                                    "last_edit": parsed["last_edit"]},
                         "daily_events": parsed["payload"]["daily_events"],
                         "entries": parsed["payload"]["entries"],
                         "raw_rows": parsed["raw_rows"]})
            snap = prev
        snap.setdefault("revision", 1)
        snap.setdefault("fetches", 1)

        with self._lock:
            _atomic_write(SNAP_DIR / f"{day}.json", snap)
            _atomic_write(LATEST_PATH, {"day": day, "fp": parsed["fp"],
                                        "updated": ts})
        if log:
            live = [f"{e['label_cn']}={e['name']}" for e in
                    ordered_entries(snap.get("entries") or {}) if e.get("active")]
            log(f"JJC 快照 {day} [{action}]: " + (" / ".join(live) or "无开放场次"))
        return {"day": day, "action": action, "snapshot": snap}

    def today(self, log=None) -> dict:
        """当天快照; 没有就现抓。抓失败退回最近一次归档并标 stale。"""
        snap = self.load(sgm_day())
        if snap:
            snap["stale"] = False
            return snap
        res = self.refresh(log)
        if res.get("snapshot"):
            res["snapshot"]["stale"] = False
            return res["snapshot"]
        old = self.latest()
        if old:
            old["stale"] = True
            return old
        return {"day": sgm_day(), "entries": {}, "daily_events": [],
                "stale": True, "missing": True}

    def peek(self):
        """只读地拿当前或最近一次快照 —— 不触网。

        写版本记录时用它做「当天台上是谁」的依据: WebUI 每次拖动滑块都会走这里,
        绝不能顺便发一个网络请求。
        """
        return self.load(sgm_day()) or self.latest()

    def brief(self, snap: dict) -> dict:
        """给版本账本用的轻量指纹桥 —— 版本记录里只存这个, 不复制整份快照。"""
        out = {"day": snap.get("day"), "fp": snap.get("fp"),
               "stale": bool(snap.get("stale"))}
        for k, e in (snap.get("entries") or {}).items():
            out[k] = (e or {}).get("name")
        out["daily"] = snap.get("daily_events") or []
        return out


# ---------------------------------------------------------------- 版本账本

class VersionLedger:
    """场次 × 规则 的版本账本 (debug/pf/jjc/versions.json)。"""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self.data = {"versions": {}}
        self._load()

    def _load(self) -> None:
        try:
            with open(VERSIONS_PATH, encoding="utf-8") as f:
                raw = json.load(f)
            if isinstance(raw.get("versions"), dict):
                self.data = {"versions": raw["versions"]}
        except (OSError, json.JSONDecodeError, AttributeError):
            pass

    def _save(self) -> None:
        _atomic_write(VERSIONS_PATH, self.data)

    @staticmethod
    def cfg_of(sess: dict) -> dict:
        return {k: sess.get(k) for k in CFG_FIELDS}

    def list(self, sid: str) -> list:
        return list(self.data["versions"].get(sid) or [])

    def head(self, sid: str):
        hist = self.list(sid)
        return hist[-1] if hist else None

    def record(self, sid: str, after: dict, before: dict = None, *,
               op: str, jjc=None) -> dict:
        """记一笔。fp 与上一版相同则不写 (返回 None), 避免重复保存刷版本号。"""
        cfg = self.cfg_of(after)
        fp = fingerprint(cfg)
        with self._lock:
            hist = self.data["versions"].setdefault(sid, [])
            prev = hist[-1] if hist else None
            if prev and prev.get("fp") == fp and prev.get("op") != "delete":
                return None
            if isinstance(before, dict):
                before_cfg = self.cfg_of(before)
                changed = [k for k in CFG_FIELDS
                           if before_cfg.get(k) != cfg.get(k)]
            elif op == "import":
                changed = ["<补建基线>"]
            else:
                changed = list(CFG_FIELDS)
            entry = {"v": (prev["v"] + 1) if prev else 1,
                     "ts": time.time(),
                     "time": time.strftime("%Y-%m-%d %H:%M:%S"),
                     "op": op, "fp": fp, "session": (after or {}).get("name"),
                     "cfg": cfg, "prev_fp": prev["fp"] if prev else None,
                     "changed": changed or ["<无字段变化>"], "jjc": jjc or None}
            hist.append(entry)
            self._save()
            return entry

    def mark_deleted(self, sid: str, cfg: dict, jjc=None) -> dict:
        with self._lock:
            hist = self.data["versions"].setdefault(sid, [])
            prev = hist[-1] if hist else None
            entry = {"v": (prev["v"] + 1) if prev else 1,
                     "ts": time.time(),
                     "time": time.strftime("%Y-%m-%d %H:%M:%S"),
                     "op": "delete", "fp": "<deleted>",
                     "session": (cfg or {}).get("name"),
                     "cfg": self.cfg_of(cfg) if isinstance(cfg, dict) else None,
                     "prev_fp": prev["fp"] if prev else None,
                     "changed": ["<删除>"], "jjc": jjc or None}
            hist.append(entry)
            self._save()
            return entry

    def ensure_baseline(self, sess: dict) -> dict:
        """给未纳入版本管理的老场次补一条 v1 基线 (幂等)。"""
        sid = (sess or {}).get("id")
        if not sid or self.list(sid):
            return None
        return self.record(sid, sess, None, op="import",
                           jjc=self._jjc_ref())

    def _jjc_ref(self):
        snap = JJC.peek()
        return JJC.brief(snap) if snap else None

    def all(self) -> dict:
        return self.data["versions"]


JJC = JJCStore()
VERSIONS = VersionLedger()


# ---------------------------------------------------------------- CLI

def _human_state(s: str) -> str:
    return "开放" if s else "未开"


def _print_snap(snap: dict) -> None:
    if not snap:
        print("(无快照)")
        return
    print(f"游戏日 {snap.get('day')}  rev{snap.get('revision')}  "
          f"fp={snap.get('fp')}  stale={snap.get('stale', False)}")
    if (snap.get("source") or {}).get("last_edit"):
        print(f"  源更新于 {snap['source']['last_edit']}")
    de = snap.get("daily_events") or []
    if de:
        print(f"  Daily Events: {' / '.join(de)}")
    for e in ordered_entries(snap.get("entries") or {}):
        print(f"  [{_human_state(e.get('active'))}] {e['label_cn']:<20}"
              f"{e.get('name') or '—'}   ({e.get('title','')})")


def main(argv: list = None) -> int:
    import argparse

    ap = argparse.ArgumentParser(description="SGM JJC 日程快照 / 版本账本")
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("fetch", help="立即抓一次并落盘")
    sub.add_parser("show", help="显示当天快照 (没有就抓)")
    p_days = sub.add_parser("days", help="列出已有快照日")
    p_days.add_argument("--limit", type=int, default=20)
    p_diff = sub.add_parser("diff", help="对比两个游戏日")
    p_diff.add_argument("a")
    p_diff.add_argument("b", nargs="?", default=None)
    p_ver = sub.add_parser("versions", help="查看场次的规则版本历史")
    p_ver.add_argument("session", nargs="?", default=None)
    args = ap.parse_args(argv)

    def _log(m, level="info"):
        print(f"[fetch][{level}]", m)

    if args.cmd == "fetch":
        res = JJC.refresh(log=_log)
        print(f"action={res.get('action')} day={res.get('day')} "
              f"{res.get('error') or ''}")
        if res.get("snapshot"):
            _print_snap(res["snapshot"])
        return 0

    if args.cmd == "show":
        _print_snap(JJC.today(log=_log))
        return 0

    if args.cmd == "days":
        days = JJC.days()
        if not days:
            print("(无快照, 先跑 jjc_store.py fetch)")
            return 0
        for d in days[-args.limit:]:
            s = JJC.load(d) or {}
            print(f"{d}  rev{s.get('revision')}  fp={s.get('fp')}")
        return 0

    if args.cmd == "diff":
        a = JJC.load(args.a)
        b = JJC.load(args.b) if args.b else JJC.latest()
        if not a or not b:
            print("缺少快照:", args.a if not a else args.b)
            return 1
        print(f"{a.get('day')} -> {b.get('day')}")
        if (a.get("daily_events") or []) != (b.get("daily_events") or []):
            print("  Daily Events: "
                  f"{' / '.join(a.get('daily_events') or []) or '—'}  ->  "
                  f"{' / '.join(b.get('daily_events') or []) or '—'}")
        for ea, eb in zip(ordered_entries(a.get("entries") or {}),
                          ordered_entries(b.get("entries") or {})):
            if (ea.get("name"), ea.get("active")) != (eb.get("name"), eb.get("active")):
                print(f"  {ea['label_cn']:<20}{ea.get('name') or '—'}"
                      f" ({_human_state(ea.get('active'))})  ->  "
                      f"{eb.get('name') or '—'}"
                      f" ({_human_state(eb.get('active'))})")
        return 0

    if args.cmd == "versions":
        data = VERSIONS.all()
        if not data:
            print("(版本账本为空)")
            return 0
        for sid, hist in data.items():
            if args.session and args.session != sid:
                continue
            head = hist[-1]
            print(f"# {sid}  {head.get('session') or '?'}  ({len(hist)} 版)")
            for v in hist:
                cfg, jjc = v.get("cfg") or {}, v.get("jjc") or {}
                print(f"  v{v['v']:<3} {v['time']}  {v['op']:<7} "
                      f"fp={v.get('fp')}  改={','.join(v.get('changed') or [])}")
                print(f"        规则={cfg.get('rule')}  名={cfg.get('name')}  "
                      f"上界={cfg.get('score_target')}  能量={cfg.get('energy_cost')}  "
                      f"休={cfg.get('rest_every')}x{cfg.get('rest_minutes')}")
                if jjc:
                    print(f"        当日JJC day={jjc.get('day')}  "
                          f"角色={jjc.get('char')}  元素={jjc.get('elem')}")
        return 0
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
