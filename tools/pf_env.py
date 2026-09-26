"""PF bot 公共环境：CRT 预载、MuMu 连接参数、WebUI 共享状态。"""
import json
import os
import subprocess
import sys
import threading
import time
from collections import deque
from pathlib import Path

from pf_native import preload_msvcrt  # compatibility export for existing callers

PROJECT_ROOT = Path(__file__).resolve().parent.parent
CONFIG_PATH = PROJECT_ROOT / "config.json"
GAME_PKG = "com.autumn.skullgirls"   # Skullgirls Mobile 包名 (adb monkey 拉起用)

# ⚠️ 本机子进程输出的解码口径 —— 一律 UTF-8 且 errors="replace"。
#
# 为什么不能用默认值 (2026-09-24 实测): `subprocess.run(text=True)` 在**不写
# encoding=** 时用 locale.getpreferredencoding()，而 启动PF.bat 是从 cmd.exe
# 起的、locale 是 **cp936(GBK)**。MuMuManager 输出的 JSON 里带设备名
# "MuMu安卓设备"（UTF-8: ...e8 ae be e5 a4 87...），第 **448** 字节 0xa4 是
# 「设」的续字节 —— GBK 见到无法配对的 0xa4 立刻抛:
#     UnicodeDecodeError: 'gbk' codec can't decode byte 0xa4 in position 448
#
# 要命的是这个异常**捕不住**: 它发生在 subprocess 内部的 _readerthread 里，
# Popen.__init__ 不 join 该线程、_communicate 只等 process.wait()，
# 所以调用方 (甚至 pf_webui 的 try/except) 完全看不到，只在 stderr 打一整屏
# traceback。而 /api/mumu 是**每 4 秒轮询一次**的接口 ⇒ 刷屏。
# 唯一可靠解法 = 从源头指定编码，别依赖 locale。
SUBPROC_TEXT = {"encoding": "utf-8", "errors": "replace"}


# ---------- 独立进程拉起 (脱离调用方的作业对象) ----------

def spawn_detached(cmdline: str, cwd: str, log_path: str = None,
                   env_lines: tuple = (), timeout: float = 30) -> int:
    """经 WMI `Win32_Process.Create` 拉起**完全脱离调用方作业对象**的进程, 返回 pid。

    为什么必须走 WMI (2026-09-26 凌晨事故实锤): agent 宿主 (workbuddy 自动化/部分
    终端) 用 Windows 作业对象管理进程树且**禁止 breakaway**
    (`CREATE_BREAKAWAY_FROM_JOB` 报 WinError 5), 普通 Popen 的子进程会留在宿主
    作业对象里 —— 宿主"任务结束"收尾清树时, 把孤儿 pf_bot 整树 TerminateProcess
    (01:07:14 无声死亡, 无 traceback/WER/休眠事件)。WMI 的进程由 WmiPrvSE 服务
    代生, 天然不在任何调用方的作业对象里, 宿主死活都与它无关。

    实现: 命令行交给 `cmd.exe /c` 执行 —— env_lines 用 `set "K=V"` 注入环境
    (不依赖调用方 env), log_path 用 `>>` 追加重定向 stdout+stderr; 窗口用
    `Win32_ProcessStartup.ShowWindow=0` 隐藏。用 powershell 5.1 的 `[wmiclass]`
    加速器 (pwsh 7 已移除该加速器, 别换)。就绪判定交给调用方轮询 (HTTP/状态),
    这里只保证"创建成功", 拿不到进程句柄 —— 要强杀请按端口反查 pid。

    失败抛 RuntimeError (带 WMI 返回码/PowerShell stderr), 由调用方决定回退策略。
    """
    parts = list(env_lines) + ['cd /d "%s"' % cwd, cmdline]
    if log_path:
        parts[-1] = "%s >> \"%s\" 2>&1" % (cmdline, log_path)
    inner = " && ".join(parts)
    ps = (
        "$sw=([wmiclass]'Win32_ProcessStartup').CreateInstance();$sw.ShowWindow=0;"
        "$cmd='%s';"
        "$r=([wmiclass]'Win32_Process').Create($cmd, '%s', $sw);"
        # ⚠️ [wmiclass] 旧式 API 的返回属性是 ReturnValue (不是 Invoke-CimMethod 的
        # ReturnCode); 取不存在属性得 $null, $null -ne 0 恒真 → 假失败 (2026-09-26 实测)。
        "if($r.ReturnValue -ne 0){Write-Error ('WMI Create failed: ' + $r.ReturnValue);"
        " exit $r.ReturnValue};"
        "Write-Output $r.ProcessId"
        % (("cmd.exe /c " + inner).replace("'", "''"), cwd.replace("'", "''"))
    )
    p = subprocess.run(
        ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", ps],
        capture_output=True, timeout=timeout, **SUBPROC_TEXT)
    out = (p.stdout or "").strip()
    if p.returncode != 0 or not out.isdigit():
        raise RuntimeError(
            "WMI spawn 失败 (rc=%s): %s" % (p.returncode, (p.stderr or p.stdout or "").strip()[:200]))
    return int(out)


def _load_config() -> dict:
    """本机参数 (adb 路径/端口等), gitignored, 不随仓库分发。"""
    try:
        with open(CONFIG_PATH, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, json.JSONDecodeError):
        return {}


CONFIG = _load_config()
MUMU_ADB_PATH = CONFIG.get("adb_path") or ""
MUMU_ADDRESS = CONFIG.get("address") or "127.0.0.1:16384"

# WebUI 端口的唯一来源。本机同网段被别的服务大量占用 (实测 8787/8788/8791 都被抢),
# 所以 8787 让位给它们, 本服务锁定 8790; 再撞可在 config.json 里用 "webui_port" 覆盖。
WEBUI_PORT = int(CONFIG.get("webui_port") or 8790)


def resolve_adb():
    """返回 (adb_path, address)。

    优先级: config.json > MAA 自动探测 (匹配同端口设备)。找不到 adb 返回 (None, address)。
    """
    addr = MUMU_ADDRESS
    if MUMU_ADB_PATH:
        return MUMU_ADB_PATH, addr
    try:
        preload_msvcrt()
        from maa.toolkit import Toolkit

        for d in Toolkit.find_adb_devices():
            if d.address == addr:
                return d.adb_path, addr
    except Exception:  # noqa: BLE001
        pass
    return None, addr


# ---------- MuMu 设备控制 (启停/拉游戏) ----------
# 唯一入口: 所有"开/关模拟器"的调用都必须走这里, 不要在别处另写一套 subprocess。
# 铁律 (2026-09 实测): 关模拟器**只能**用 `MuMuManager control -v 0 shutdown`。
#   强杀 MuMuNxMain.exe 会被 MuMuNxService 以 --from-oem 立刻拉回;
#   且该实例上还挂着用户另一个 MAA(明日方舟) 的自启, 强杀会连带打断。

def mumu_paths(adb_path: str = None) -> tuple[str | None, str | None, str]:
    """(MuMuManager.exe, MuMuNxMain.exe, address)。adb_path 为空则回落到 resolve_adb()。"""
    adb, addr = (adb_path, MUMU_ADDRESS) if adb_path else resolve_adb()
    if not adb:
        return None, None, addr
    base = Path(adb)
    mgr = base.with_name("MuMuManager.exe")
    nx = base.with_name("MuMuNxMain.exe")
    return (str(mgr) if mgr.is_file() else None,
            str(nx) if nx.is_file() else None,
            addr)


def mumu_info(adb_path: str = None, timeout: float = 15) -> dict:
    """`MuMuManager info -v 0` 的 JSON。取不到返回 {} (含 MuMuManager 不存在)。

    ⚠️ 2026-09-22 实测崩溃根因: `subprocess.run(capture_output=True, timeout=...)`
    在**自己超时时会先杀掉子进程, 再抛 TimeoutExpired, 而 `p.stdout` 是 None**
    (调用超时/被杀的进程没有输出)。`json.loads(None)` 抛的是 **TypeError**,
    不在原先捕获的 (OSError, SubprocessError, JSONDecodeError, ValueError) 里,
    于是异常一路冒到 WebUI 的 do_GET, 把请求线程打出一整屏 traceback。

    触发场景不是理论: MuMuManager 是 RPC 客户端, 模拟器正在启停/被别的进程独占时
    会卡住 >15s; 而 `/api/mumu` 是**每 4 秒轮询一次**的接口, 于是刷屏。
    两道防线都补上: ①stdout 空值直接判失败; ②兜底捕 Exception。
    """
    mgr, _, _ = mumu_paths(adb_path)
    if not mgr:
        return {}
    try:
        p = subprocess.run([mgr, "info", "-v", "0"],
                           capture_output=True, text=True, timeout=timeout,
                           **SUBPROC_TEXT)
        if not p.stdout:                      # 超时/被杀 -> None; 空串也无从解析
            return {}
        data = json.loads(p.stdout)
        return data if isinstance(data, dict) else {}
    except Exception:  # noqa: BLE001
        # 这里刻意捕 Exception 而不是列举: 这是"给界面看设备状态"的旁路,
        # 失败只该退化成"状态未知", 任何解析类意外都不该冒到 HTTP 线程。
        return {}


def mumu_state(adb_path: str = None) -> str:
    """设备状态串: start_finished / stopped / unknown。"""
    info = mumu_info(adb_path)
    if not info:
        return "unknown"
    for key in ("player_state", "state"):
        if info.get(key):
            return str(info[key])
    # MuMu 12.0 的 `info -v 0` 字段集随运行态变化: 未启动时只有布尔位
    # (is_android_started / is_process_started), 没有 player_state。两个口径都认。
    if "is_android_started" in info:
        return "start_finished" if info.get("is_android_started") else "stopped"
    return "unknown"


def mumu_is_running(adb_path: str = None) -> bool:
    """设备是否已就绪。"""
    return mumu_state(adb_path) == "start_finished"


def mumu_start(adb_path: str = None, timeout: float = 120) -> bool:
    """未启动则拉起 MuMuNxMain, 轮询到 start_finished。返回是否就绪。"""
    if mumu_is_running(adb_path):
        return True
    _, nx, _ = mumu_paths(adb_path)
    if not nx:
        return False
    # 模拟器必须脱离调用方的作业对象: 2026-09-26 凌晨 workbuddy 收尾清树把 MuMu
    # 一起带走了, 而该实例上还挂着用户另一个 MAA(明日方舟) 自启, 被连带打断代价更大。
    try:
        spawn_detached('"%s" -v 0' % nx, str(Path(nx).parent))
    except (RuntimeError, OSError, subprocess.SubprocessError):
        # WMI 通道不可用 (powershell 被拦等) 才退回普通 Popen —— 行为等同旧版,
        # 代价是调用方作业对象收尾时 MuMu 会陪葬。
        subprocess.Popen([nx, "-v", "0"], cwd=str(Path(nx).parent))
    deadline = time.time() + timeout
    while time.time() < deadline:
        time.sleep(2)
        if mumu_is_running(adb_path):
            time.sleep(2)      # adbd 比 start_finished 晚一点起来, 留个沉降窗口
            return True
    return False


def mumu_shutdown(adb_path: str = None, timeout: float = 30) -> bool:
    """优雅关闭模拟器 (MuMuManager control shutdown)。返回是否已脱离 start_finished。"""
    mgr, _, _ = mumu_paths(adb_path)
    if not mgr:
        return False
    try:
        subprocess.run([mgr, "control", "-v", "0", "shutdown"],
                       capture_output=True, text=True, timeout=timeout,
                       **SUBPROC_TEXT)
    except (OSError, subprocess.SubprocessError):
        return False
    deadline = time.time() + timeout
    while time.time() < deadline:
        time.sleep(2)
        if not mumu_is_running(adb_path):
            return True
    return not mumu_is_running(adb_path)


def mumu_launch_game(adb_path: str = None, pkg: str = GAME_PKG) -> tuple[bool, str]:
    """adb monkey 拉起游戏。返回 (是否成功, 原始输出/错误)。"""
    adb, addr = (adb_path, MUMU_ADDRESS) if adb_path else resolve_adb()
    if not adb:
        return False, "未找到 adb (config.json 配 adb_path 或先启动模拟器)"
    try:
        p = subprocess.run([adb, "-s", addr, "shell", "monkey", "-p", pkg,
                            "-c", "android.intent.category.LAUNCHER", "1"],
                           capture_output=True, text=True, timeout=30,
                           **SUBPROC_TEXT)
    except (OSError, subprocess.SubprocessError) as e:
        return False, str(e)
    out = (p.stdout or "") + (p.stderr or "")
    return ("Events injected: 1" in out, out.strip())


def adb_connect(adb_path: str = None, timeout: float = 30) -> bool:
    """确保 adb 连上模拟器 (adb connect <address>)。

    为什么需要: pf_bot 的 `controller.post_connection()` 是 MAA 内部重连, **不会**
    自建 TCP 连接。bot 停在 IDLE 不动时, adb 与模拟器之间的连接会因模拟器重启 /
    adb server 被回收而失效; 此时点「开始」只会撞一串
    `AdbControlUnitMgr::connect failed`(实测 08:39 那屏), 而用户看到的只是"没反应"。
    所以 /api/start 之前先补一次 connect —— 幂等, 已连上就秒回。
    """
    adb, addr = (adb_path, MUMU_ADDRESS) if adb_path else resolve_adb()
    if not adb:
        return False
    try:
        p = subprocess.run([adb, "connect", addr],
                           capture_output=True, text=True, timeout=timeout,
                           **SUBPROC_TEXT)
    except Exception:  # noqa: BLE001
        return False
    out = ((p.stdout or "") + (p.stderr or "")).lower()
    return "connected to" in out or "already connected" in out


class BotState:
    """线程安全的机器人状态，WebUI 与主循环共享。"""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._logs: deque = deque(maxlen=400)
        self._total = 0               # 累计日志条数（单调, 前端判新日志用）
        self.status = "IDLE"          # IDLE / RUNNING / STOPPED / ERROR
        self.step = "-"               # 当前阶段
        self.fight_no = 0             # 第几场
        self.score = None             # PF 总分（最近一次读到的）
        self.streak = None            # 连胜层数
        self.score_target = None      # 总分上限, 达到即自动暂停 (None=不限)
        self.energy_cost = 4          # 出战能量门槛 (可从 WebUI 调)
        self.pf_rule = None           # 当前场次绑定的规则 {"type","value"} / None
        self.filter_favorite = True   # 筛选时是否保留 喜爱(爱心) 芯片
        self.rest_every = 0           # 连续 N 场后休息 (0=不启用)
        self.rest_minutes = 0         # 休息时长(分钟)
        self.rest_until = 0           # 休息截止时间戳
        self.shot_ver = 0             # 截图版本号（前端据此刷新图片）
        self.shot_path = None
        self.running = False          # 暂停开关（WebUI 可置 False, 可恢复）
        self.quit = False             # 硬停止开关: 置 True 后主循环退出、进程结束
        # 达标收尾: 总分达到 score_target 时自动关闭 MuMu (省电/释放机器)。
        # 默认 True (用户 2026-09-26 改口径: 凌晨无人值守跑完就该关机, 别空烧;
        # 不想关的场合在 WebUI 取消勾选「达标关模拟器」即可, /api/settings 可覆盖)。
        self.close_mumu_on_goal = True

    def log(self, msg: str, level: str = "info") -> None:
        stamp = time.strftime("%H:%M:%S")
        with self._lock:
            self._logs.append((stamp, level, msg))
            self._total += 1
        print(f"[{stamp}][{level}] {msg}", flush=True)

    def dump_logs(self) -> list:
        with self._lock:
            return list(self._logs)

    def log_total(self) -> int:
        with self._lock:
            return self._total

    def set_step(self, step: str) -> None:
        self.step = step
        self.log(f"—— {step} ——", "step")

    def push_shot(self, path: Path) -> None:
        with self._lock:
            self.shot_path = str(path)
            self.shot_time = time.strftime("%H:%M:%S")
            self.shot_ver += 1


STATE = BotState()


# ---------- debug 目录体积控制 (2026-09-03) ----------

IMG_CAP_MB = 150   # debug/pf/run 全部截图总量上限
LOG_CAP_MB = 50    # 全部 .log 总量上限 (maafw + bot_stdout)
CLEAN_INTERVAL_S = 600


def cleanup_debug(protected_dir: Path = None) -> str:
    """超限则删旧: 图片按运行目录从旧到新整删(保护当前目录, 仍超则删目录内最旧帧);
    日志只删最旧的 maafw.bak.* (活动中的 maafw.log/stdout 由 MAA 自轮转接手)。
    一次遍历建索引、删除时递减, 不做全量重扫。返回摘要文本。"""
    summary = ""
    img_root = PROJECT_ROOT / "debug" / "pf" / "run"
    if img_root.is_dir():
        dirs = sorted(d for d in img_root.iterdir() if d.is_dir())
        protected = protected_dir.resolve() if protected_dir else (
            dirs[-1] if dirs else None)
        sizes = {}   # dir -> {file: size} (一次遍历)
        for d in dirs:
            files = {}
            try:
                for f in d.iterdir():
                    try:
                        if f.is_file():
                            files[f] = f.stat().st_size
                    except OSError:
                        pass
            except OSError:
                pass
            sizes[d] = files
        total = sum(sum(v.values()) for v in sizes.values())
        limit = IMG_CAP_MB * 1024 * 1024
        removed_dirs = removed_frames = 0
        while total > limit and len(sizes) > 1:
            victim = dirs.pop(0)
            if victim == protected:
                dirs.append(victim)   # 环形保护: 只剩自己时停
                break
            total -= sum(sizes.pop(victim).values())
            _rmtree(victim)
            removed_dirs += 1
        # 仍超限: 当前目录内从最旧帧删起 (长跑单目录可超 150MB)
        if protected in sizes and total > limit:
            for frame in sorted(sizes[protected]):
                if total <= limit:
                    break
                try:
                    frame.unlink()
                    total -= sizes[protected].pop(frame)
                    removed_frames += 1
                except OSError:
                    break
        summary = f"图片→{total // 1048576}MB"
        if removed_dirs:
            summary += f", 删旧目录×{removed_dirs}"
        if removed_frames:
            summary += f", 删旧帧×{removed_frames}"

    logs = sorted((PROJECT_ROOT / "debug").rglob("*.log"))
    removed_logs = 0
    limit = LOG_CAP_MB * 1024 * 1024
    total = 0
    for f in logs:
        try:
            total += f.stat().st_size
        except OSError:
            pass
    for f in logs:   # 名称序 = maafw.bak 时间序, 最旧在前; 跳过活动日志(maafw.log/stdout)
        if total <= limit:
            break
        if f.name == "maafw.log" or f.name == "bot_stdout.log":
            continue
        try:
            size = f.stat().st_size
            f.unlink()
            total -= size
            removed_logs += 1
        except OSError:
            continue
    if removed_logs:
        summary += f", 删旧日志×{removed_logs}"
    return f"debug清理: {summary}, 日志→{total // 1048576}MB"


def _rmtree(path: Path) -> None:
    import shutil
    shutil.rmtree(path, ignore_errors=True)


def start_debug_cleaner(protected_dir: Path = None, log=None):
    """启动即清一次, 之后每 10 分钟一次 (守护线程)。"""
    def _loop():
        while True:
            try:
                msg = cleanup_debug(protected_dir)
                if log and ("删" in msg):
                    log(msg)
            except Exception:  # noqa: BLE001
                pass
            time.sleep(CLEAN_INTERVAL_S)
    threading.Thread(target=_loop, daemon=True).start()
