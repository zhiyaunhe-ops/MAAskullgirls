"""PF bot 公共环境：CRT 预载、MuMu 连接参数、WebUI 共享状态。"""
import ctypes
import json
import os
import sys
import threading
import time
from collections import deque
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
CONFIG_PATH = PROJECT_ROOT / "config.json"


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


def resolve_adb():
    """返回 (adb_path, address)。

    优先级: config.json > MAA 自动探测 (匹配同端口设备)。找不到 adb 返回 (None, address)。
    """
    addr = MUMU_ADDRESS
    if MUMU_ADB_PATH:
        return MUMU_ADB_PATH, addr
    try:
        from maa.toolkit import Toolkit

        for d in Toolkit.find_adb_devices():
            if d.address == addr:
                return d.adb_path, addr
    except Exception:  # noqa: BLE001
        pass
    return None, addr


def preload_msvcrt() -> None:
    """先加载 System32 新版 VC 运行库，避免 anaconda 旧 CRT 导致 MAA DLL 初始化失败。

    必须在 import cv2/maa 之前调用，详见 connect_mumu.py。
    """
    sys32 = os.path.join(os.environ.get("SystemRoot", r"C:\Windows"), "System32")
    for name in ("msvcp140.dll", "vcruntime140.dll", "vcruntime140_1.dll"):
        path = os.path.join(sys32, name)
        if os.path.exists(path):
            ctypes.WinDLL(path)


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
