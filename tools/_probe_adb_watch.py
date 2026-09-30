"""adb server 观测探针 —— 抓"谁在换 5037 adb server"的现行 (2026-09-28)。

要回答的问题: 20:54:54 / 00:28:06 两次掉线时刻各冒出一个新的 session 0 adb.exe
(session 0 + 服务账户 = MuMu 远控服务那一系), 但**父链证明不了**——中间那几个 adb
client 进程当场就死了。停服务观察是"排除法", 本探针是"抓现行": 只旁观, 等下次换代
发生时把新 adb 的 PID/父链/监听者/当时的 bot 日志一起记下来。

铁律: **不调用任何 adb 命令**。adb 客户端连不上 server 时会自己拉起一个 server
(这正是 MAA 在做的), 那既掩盖症状又制造换代 —— 所以探针只用:
  ① 每 5s 进程快照 (kernel32 Toolhelp32, 纯 stdlib, 便宜到能常驻);
  ② 5037/5038 的 TCP connect 探测 (connect+close, 不产生 adb 进程) 看 server 生死;
  ③ 事件时刻一次 PowerShell CIM 查询: adb.exe 的 SessionId/创建时间/命令行/路径、
     MuMu 远控服务状态、5037/5038 监听者 PID;
  ④ 进程环缓冲 (最近 12 次快照 ≈ 1 分钟): 事件时回溯父链, 父进程即使已死也能认出名字;
  ⑤ 事件时刻 GET /api/state 取 bot 状态与最近 4 行日志, 与"截图连续失败"对齐。

用法 (任意 python 都行; 只用 stdlib + pf_env):
  python tools/_probe_adb_watch.py                 # 前台跑, Ctrl-C 停
  python tools/_probe_adb_watch.py --detach        # 后台跑 (WMI 脱离终端, 默认 24h)
  python tools/_probe_adb_watch.py --stop          # 停后台实例
  python tools/_probe_adb_watch.py --report        # 打印摘要 (事件清单)

产物 (debug/ 已在 .gitignore):
  debug/pf/adb_watch.jsonl   事件 + 心跳 (心跳每 ~200s 一条, 24h ≈ 450 行)
  debug/pf/adb_watch.log     后台实例的 stdout/stderr
  debug/pf/adb_watch.pid     运行中的 pid (重复启动会被拒)
  debug/pf/adb_watch.stop    --stop 写的停止哨兵, 主循环每轮检查
"""
from __future__ import annotations

import argparse
import ctypes
import json
import os
import socket
import subprocess
import sys
import time
import urllib.request
from collections import deque
from ctypes import wintypes
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from pf_env import PROJECT_ROOT, SUBPROC_TEXT, WEBUI_PORT  # noqa: E402  与 bot 同一套路径/端口口径

WATCH_DIR = PROJECT_ROOT / "debug" / "pf"
JSONL = WATCH_DIR / "adb_watch.jsonl"
PIDFILE = WATCH_DIR / "adb_watch.pid"
STOPFILE = WATCH_DIR / "adb_watch.stop"
LOGFILE = WATCH_DIR / "adb_watch.log"

PORTS = (5037, 5038)      # 5037=模拟器 adb server (bot 用), 5038=WorkBuddy 手机工具
SCAN_S = 5.0              # 进程快照间隔
RING = 12                 # 环缓冲快照数 (12×5s ≈ 1 分钟)
HEARTBEAT_EVERY = 40      # 每 40 次扫描写一条心跳 (≈200s)
TARGET = "adb.exe"


# ---------- 进程快照 (Toolhelp32, 免 PowerShell) ----------

class _PE32W(ctypes.Structure):
    _fields_ = [("dwSize", wintypes.DWORD),
                ("cntUsage", wintypes.DWORD),
                ("th32ProcessID", wintypes.DWORD),
                ("th32DefaultHeapID", ctypes.c_void_p),   # ULONG_PTR, 别用 c_ulong (x64 对齐)
                ("th32ModuleID", wintypes.DWORD),
                ("cntThreads", wintypes.DWORD),
                ("th32ParentProcessID", wintypes.DWORD),
                ("pcPriClassBase", ctypes.c_long),
                ("dwFlags", wintypes.DWORD),
                ("szExeFile", wintypes.WCHAR * 260)]

_k32 = ctypes.WinDLL("kernel32", use_last_error=True)
_k32.CreateToolhelp32Snapshot.restype = wintypes.HANDLE
_k32.CreateToolhelp32Snapshot.argtypes = (wintypes.DWORD, wintypes.DWORD)
_k32.Process32FirstW.argtypes = (wintypes.HANDLE, ctypes.POINTER(_PE32W))
_k32.Process32NextW.argtypes = (wintypes.HANDLE, ctypes.POINTER(_PE32W))
_k32.CloseHandle.argtypes = (wintypes.HANDLE,)
_INVALID = ctypes.c_void_p(-1).value
_TH32CS_SNAPPROCESS = 0x2


def snapshot() -> dict:
    """{pid: (exe, ppid)} —— 全量进程, 5s 一次也够便宜。"""
    h = _k32.CreateToolhelp32Snapshot(_TH32CS_SNAPPROCESS, 0)
    if h == _INVALID:
        return {}
    out = {}
    entry = _PE32W()
    entry.dwSize = ctypes.sizeof(_PE32W)
    try:
        ok = _k32.Process32FirstW(h, ctypes.byref(entry))
        while ok:
            out[int(entry.th32ProcessID)] = (entry.szExeFile, int(entry.th32ParentProcessID))
            ok = _k32.Process32NextW(h, ctypes.byref(entry))
    finally:
        _k32.CloseHandle(h)
    return out


def tcp_alive(port: int) -> str:
    """connect+close: 只问端口有没有人听, 不产生 adb 进程 (不触发自动拉起 server)。"""
    s = socket.socket()
    s.settimeout(1.0)
    try:
        s.connect(("127.0.0.1", port))
        return "open"
    except OSError:
        return "closed"
    finally:
        s.close()


def listeners() -> dict:
    """{port: [持有 LISTEN 的 pid]} —— 解析 netstat -ano, 每轮都取 (便宜, 不碰 adb)。

    CIM 那套只在事件时刻跑; 监听者归属要**每一轮都有**, 否则看不出 server 换代的时间线。
    """
    out = {p: [] for p in PORTS}
    try:
        r = subprocess.run(["netstat", "-ano", "-p", "TCP"],
                           capture_output=True, timeout=10, **SUBPROC_TEXT)
    except Exception:  # noqa: BLE001  netstat 缺失/超时都不该掀掉观测
        return out
    for line in (r.stdout or "").splitlines():
        parts = line.split()
        if len(parts) < 5 or parts[-2].upper() != "LISTENING":
            continue
        for port in PORTS:
            if parts[1].endswith(":%d" % port):
                try:
                    out[port].append(int(parts[-1]))
                except ValueError:
                    pass
    return out


# ---------- 事件时刻的取证 ----------

_PS_QUERY = r'''
[Console]::InputEncoding=[Console]::OutputEncoding=[Text.UTF8Encoding]::new()
$adb = @(Get-CimInstance Win32_Process -Filter "Name='adb.exe'" |
         Select-Object ProcessId,ParentProcessId,SessionId,CreationDate,CommandLine,ExecutablePath)
$svc = @(Get-CimInstance Win32_Service |
         Where-Object { $_.Name -match 'MuMuRemote|MuMuNx' } |
         Select-Object Name,State,ProcessId)
$lis = @{}
foreach ($p in 5037,5038) {
  $lis["$p"] = @(Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue |
                  Select-Object -ExpandProperty OwningProcess)
}
[pscustomobject]@{ adb=$adb; services=$svc; listeners=$lis } | ConvertTo-Json -Depth 6 -Compress
'''


def cim_enrich() -> dict:
    """一次 PowerShell 取证。失败不致命: 记下错误继续观测。"""
    try:
        p = subprocess.run(["pwsh", "-NoProfile", "-Command", _PS_QUERY],
                           capture_output=True, timeout=40, **SUBPROC_TEXT)
    except Exception as e:  # noqa: BLE001  pwsh 缺失/超时都不该掀掉观测
        return {"error": f"{type(e).__name__}: {e}"[:120]}
    out = (p.stdout or "").strip()
    if not out:
        return {"error": f"rc={p.returncode} {(p.stderr or '').strip()[:120]}"}
    try:
        return json.loads(out)
    except json.JSONDecodeError as e:
        return {"error": f"json: {e} out={out[:120]}"}


def bot_state() -> dict:
    """GET /api/state —— 只读, 且是本仓库认可的 bot 状态口径 (见 PF_BOT §9)。"""
    try:
        with urllib.request.urlopen(
                f"http://127.0.0.1:{WEBUI_PORT}/api/state", timeout=3) as r:
            d = json.loads(r.read().decode("utf-8") or "{}")
        if not isinstance(d, dict) or d.get("svc") != "sgm-pf-bot":
            return {"other_service": True}
        return {"status": d.get("status"), "step": d.get("step"),
                "logs": [f"{t} {m}" for t, _lv, m in (d.get("logs") or [])[-4:]]}
    except Exception as e:  # noqa: BLE001
        return {"down": True, "err": type(e).__name__}


def ancestors(ring: deque, pid: int, depth: int = 5) -> list:
    """回溯父链。父进程可能在新 adb 出现后几秒内就死 —— 所以每级都在环里找最近一次见到。"""
    chain, seen, cur = [], {pid}, pid
    for _ in range(depth):
        ppid = next((snap[cur][1] for snap in reversed(ring) if cur in snap), None)
        if not ppid or ppid in seen or ppid <= 4:      # ≤4 是 System/Idle, 到顶
            break
        seen.add(ppid)
        name = next((snap[ppid][0] for snap in reversed(ring) if ppid in snap), "?")
        chain.append({"pid": ppid, "name": name, "alive": ppid in ring[-1]})
        cur = ppid
    return chain


# ---------- 记录与主循环 ----------

def _bot_brief(bot: dict) -> str:
    """bot 那一格的短标签: 状态 / down / other-service / —。"""
    return str(bot.get("status") or ("down" if bot.get("down") else
                                     "other-service" if bot.get("other_service") else "—"))


def _emit(rec: dict) -> None:
    WATCH_DIR.mkdir(parents=True, exist_ok=True)
    with open(JSONL, "a", encoding="utf-8") as f:
        f.write(json.dumps(rec, ensure_ascii=False) + "\n")
    bits = [rec.get("event", "?")]
    if rec.get("adb_pids"):
        bits.append("adb=" + ",".join(str(p) for p in rec["adb_pids"]))
    if rec.get("added") or rec.get("gone"):
        bits.append(f"+{rec.get('added')} -{rec.get('gone')}")
    if rec.get("tcp"):
        bits.append("tcp=" + " ".join(f"{k}:{v}" for k, v in rec["tcp"].items()))
    if rec.get("listeners") is not None:
        bits.append("5037监听=" + ",".join(str(x) for x in (rec["listeners"].get(5037) or [])))
    if rec.get("bot"):
        bits.append("bot=" + _bot_brief(rec["bot"]))
    if rec.get("ancestors"):
        bits.append("父链 " + str(rec.get("focus_pid")) + " <- "
                    + " <- ".join(f"{a['name']}({a['pid']}{'' if a['alive'] else '已退'})"
                                  for a in rec["ancestors"]))
    print(f"[{rec['ts']}] " + "  ".join(bits), flush=True)


def _record(kind: str, ring: deque, adb: dict, tcp: dict, lis: dict,
            focus=None, extra: dict = None, forensics: bool = True) -> None:
    """写一条事件。focus=要回溯父链的 pid (默认最靠前的 adb)。"""
    if focus not in adb:
        focus = sorted(adb)[0] if adb else None
    rec = {"ts": time.strftime("%Y-%m-%dT%H:%M:%S"), "event": kind,
           "adb_pids": sorted(adb),
           "tcp": tcp,
           "listeners": lis,
           "focus_pid": focus,
           "ancestors": ancestors(ring, focus) if focus else [],
           "bot": bot_state()}
    if forensics:                    # 心跳不做重取证, 免得文件里全是重复的进程表
        rec["files"] = cim_enrich()
    rec.update(extra or {})
    _emit(rec)


def run(minutes: float, interval: float) -> int:
    WATCH_DIR.mkdir(parents=True, exist_ok=True)
    alive = _pidfile_alive()
    if alive is not None:
        print(f"已有实例在跑 (pid={alive}), 拒绝重复启动; 要停先 --stop")
        return 2
    PIDFILE.write_text(str(os.getpid()), encoding="utf-8")
    ring = deque(maxlen=RING)
    snap = snapshot()
    ring.append(snap)
    adb = {pid: v for pid, v in snap.items() if v[0].lower() == TARGET}
    tcp = {p: tcp_alive(p) for p in PORTS}
    _record("start", ring, adb, tcp, listeners(),
            extra={"pid": os.getpid(), "interval": interval, "minutes": minutes,
                   "argv": " ".join(sys.argv[1:])})
    # prev_adb = 观察集: 在场进程 + 待确认消失的 (缺席次数放 pending)。每轮**全量替换** ——
    # 新 pid 必须进集合, 否则下一轮又把它报一次 added (2026-09-28 首版就是这个 bug,
    # 21:27:32 之后每 10s 一条假 change)。
    prev_adb = dict(adb)
    prev_tcp = dict(tcp)
    pending = {}                 # pid -> 连续缺席次数 (连续两次才认"消失")
    samples = 0
    deadline = time.time() + minutes * 60 if minutes > 0 else None
    try:
        while True:
            time.sleep(interval)
            if STOPFILE.exists():
                STOPFILE.unlink(missing_ok=True)
                print("收到停止哨兵, 退出", flush=True)
                break
            if deadline and time.time() >= deadline:
                print(f"到点 ({minutes:g} 分钟), 退出", flush=True)
                break
            snap = snapshot()
            ring.append(snap)
            samples += 1
            adb = {pid: v for pid, v in snap.items() if v[0].lower() == TARGET}
            tcp = {p: tcp_alive(p) for p in PORTS}
            lis = listeners()
            added = sorted(set(adb) - set(prev_adb) - set(pending))
            for pid in set(prev_adb) - set(adb):
                pending[pid] = pending.get(pid, 0) + 1
            for pid in list(pending):
                if pid in adb:                         # 又出现了, 撤销缺席
                    pending.pop(pid)
            gone = sorted(p for p, n in pending.items() if n >= 2)
            tcp_broke = [p for p in PORTS if prev_tcp[p] == "open" and tcp[p] == "closed"]
            tcp_back = [p for p in PORTS if prev_tcp[p] == "closed" and tcp[p] == "open"]
            if added or gone or tcp_broke or tcp_back:
                _record("change", ring, adb, tcp, lis, focus=(added[0] if added else None),
                        extra={"added": added, "gone": gone, "tcp_broke": tcp_broke,
                               "tcp_back": tcp_back, "sample": samples,
                               "gone_detail": {str(p): prev_adb.get(p) for p in gone}})
            elif samples % HEARTBEAT_EVERY == 0:
                _record("heartbeat", ring, adb, tcp, lis, extra={"samples": samples},
                        forensics=False)
            for pid in gone:                           # 报过的不再重复报
                pending.pop(pid, None)
            prev_adb = dict(adb)
            for pid in pending:
                prev_adb.setdefault(pid, ("adb.exe", 0))
            prev_tcp = dict(tcp)
    except KeyboardInterrupt:
        print("Ctrl-C, 退出", flush=True)
    finally:
        try:
            if PIDFILE.read_text(encoding="utf-8").strip() == str(os.getpid()):
                PIDFILE.unlink()
        except OSError:
            pass
    return 0


def _pidfile_alive():
    """返回在跑的 watcher pid, 没有则 None。"""
    try:
        pid = int(PIDFILE.read_text(encoding="utf-8").strip())
    except (OSError, ValueError):
        return None
    if pid == os.getpid():
        return None
    if snapshot().get(pid, ("", 0))[0].lower().startswith("python"):
        return pid
    return None


# ---------- 命令行 ----------

def _adb_digest(rec: dict) -> list:
    """把 CIM 取证压成人能读的几行: 监听者归属 + 每个 adb 的 session/创建时间/命令行。"""
    f = rec.get("files") or {}
    if not f or f.get("error"):
        return [f"取证: {f.get('error', '无')}"] if f else []
    out = ["监听 " + " ".join(f"{p}={','.join(str(x) for x in v) or '—'}"
                              for p, v in (rec.get("listeners") or {}).items())]
    for a in (f.get("adb") or []):
        cmd = str(a.get("CommandLine") or "?").replace("\r", " ").replace("\n", " ")
        out.append(f"adb {a.get('ProcessId')} session={a.get('SessionId')} "
                   f"created={str(a.get('CreationDate'))[:19]} cmd={cmd[:72]}")
    return out


def report() -> int:
    if not JSONL.exists():
        print(f"没有数据 ({JSONL})")
        return 1
    rows = []
    for line in JSONL.read_text(encoding="utf-8", errors="replace").splitlines():
        try:
            rows.append(json.loads(line))
        except json.JSONDecodeError:
            continue
    kinds = {}
    for r in rows:
        kinds[r.get("event", "?")] = kinds.get(r.get("event", "?"), 0) + 1
    print("共 %d 条: %s" % (len(rows), ", ".join(f"{k}×{v}" for k, v in kinds.items())))
    print("事件清单 (最新 15 条 start/change):")
    for r in [x for x in rows if x.get("event") in ("change", "start")][-15:]:
        bot = r.get("bot") or {}
        chain = " <- ".join(f"{a['name']}({a['pid']}{'' if a['alive'] else '已退'})"
                            for a in (r.get("ancestors") or []))
        print(f"  {r['ts']} {r['event']:6s} adb={r.get('adb_pids')} tcp={r.get('tcp')} "
              f"bot={_bot_brief(bot)}")
        if r.get("added") or r.get("gone") or r.get("tcp_broke") or r.get("tcp_back"):
            print(f"      +{r.get('added')} -{r.get('gone')} "
                  f"tcp_broke={r.get('tcp_broke')} tcp_back={r.get('tcp_back')}")
        if chain:
            print(f"      父链 {r.get('focus_pid')} <- {chain}")
        for line in _adb_digest(r):
            print(f"      {line}")
        for line in (bot.get("logs") or [])[-2:]:
            print(f"      bot日志: {line}")
    return 0


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="adb server 观测探针 (只旁观, 不调 adb)")
    ap.add_argument("--minutes", type=float, default=1440.0,
                    help="跑多久 (默认 1440=24h; 0=不限)")
    ap.add_argument("--interval", type=float, default=SCAN_S, help="进程快照间隔秒 (默认 5)")
    ap.add_argument("--detach", action="store_true", help="后台跑 (WMI 脱离终端)")
    ap.add_argument("--stop", action="store_true", help="停后台实例")
    ap.add_argument("--report", action="store_true", help="打印摘要")
    a = ap.parse_args(argv)

    if a.report:
        return report()
    if a.stop:
        STOPFILE.parent.mkdir(parents=True, exist_ok=True)
        STOPFILE.write_text("stop", encoding="utf-8")
        print(f"已写停止哨兵; 在跑实例 pid={_pidfile_alive()}")
        return 0
    if a.detach:
        alive = _pidfile_alive()
        if alive is not None:
            print(f"已在跑 (pid={alive})")
            return 2
        from pf_env import spawn_detached
        pid = spawn_detached(
            '"%s" tools\\_probe_adb_watch.py --minutes %g --interval %g'
            % (sys.executable, a.minutes, a.interval),
            str(PROJECT_ROOT), log_path=str(LOGFILE),
            env_lines=('set "PYTHONUTF8=1"', 'set "PYTHONIOENCODING=utf-8"'))
        print(f"后台已启动 pid={pid}; 数据 {JSONL}; 控制台日志 {LOGFILE}")
        print("停止: python tools/_probe_adb_watch.py --stop")
        return 0
    return run(a.minutes, a.interval)


if __name__ == "__main__":
    sys.exit(main())
