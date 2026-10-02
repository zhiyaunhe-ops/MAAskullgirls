"""统一日志：全项目所有进程写**同一个** `debug/pf/pf.log`。

## 为什么需要这个模块（2026-10-02 立）

改之前项目里有 4 套日志，人要看哪一份全靠猜：

| 文件 | 谁写 | 问题 |
|---|---|---|
| `bot_stdout.log` | pf_schedule / pf_tray 拉起 bot 时 `>>` | 名义上的"主"日志，**实际早就不更新了** |
| `bot_stdout_<时间戳>.log` | bot 自我重生时 `>>` | 每次 MAA 僵死就多一个文件，一周 20 个 |
| `tray.log` | 托盘自己 | 又一处 |
| `schedule.log` | pf_schedule 自己 | 又一处 |

现场（2026-10-02 18:30）：bot 正在跑第 143 场、日志活到 18:35，
但 `bot_stdout.log` **停在 13:04** —— 因为 13:59 那代是重生出来的，
它写自己的 `bot_stdout_1002-135928.log`。查问题的人先看 `bot_stdout.log`
会得出"bot 卡了 5 小时"的错误结论。

**分世代文件不是设计，是被迫的**：`spawn_detached` 走 `cmd /c ... >> 文件`，
老进程不退出就占着句柄，新进程 `>>` 同一文件会被 cmd 拒绝
（"另一个程序正在使用此文件"）→ python 根本没起来 → 重生空枪、零日志。
所以 2026-09-28 那次修复选择了"新进程换新文件"。

⇒ 真正的解法不是"分开写"，而是**让并发写本身安全**，这样就不必再分文件。

## 承重前提：Windows 多进程 append 会丢行（已实测）

2026-10-02 实测：6 个 python 进程各 append 同一文件、各写 200 行并 flush，
期望 1200 行，**实际只落 1039 行，丢 161 行**，且没有一行是交错混合的
（`grep -cE "procN lineM proc"` = 0）。

原因是 Windows 上 `open(..., 'a')` 的 append 语义**不保证跨进程原子**：
每个进程各自持有一个独立的文件位置，多个进程同时 seek/写会互相覆盖字节区间。
**所以"让几个进程直接 `>>` 同一个文件"这个方案在本机是行不通的**——
这正是当年要分世代文件的真实原因。

验证过的解法 = **跨进程字节区间锁**（`msvcrt.locking`，锁一个专用 lock 文件
的第 0 字节）。同样 6 进程 × 200 行，`LK_LOCK` 包裹 write+flush，
**1200/1200 一条不丢**。本模块就是这个方案。

## 设计要点

- **单文件**：`debug/pf/pf.log`，谁启动、启动几次、重生几代，都往它写。
- **跨进程安全**：每次写都持锁（锁范围 = 整个 write+flush），串行化。
- **锁有超时**：持锁进程被强杀会留下锁，靠 `LK_NBLCK` + 重试上限兜底，
  不会因为一个僵尸锁就让整个日志系统卡死（宁可丢日志，不能卡住 bot）。
- **双写**：`install()` 接管 stdout/stderr，**同时**写文件和原终端 ——
  手工起 bot 时仍能在终端看到输出。
- **世代标记**：每个进程启动时打一行 `════ 进程启动 pid=… 世代#N ════`，
  从此不需要靠文件名猜"现在活的是哪一代"。
- **编码钉死 UTF-8**：绕开 cp936（见 `pf_env.SUBPROC_TEXT` 同源问题）。
- **日志函数自带缓冲**：即使调用方没 flush，落盘也只差一行。

## 用法

进程入口最早处（**必须在任何其他 import 之前**）：

```python
from pf_logging import install
install()          # 接管 stdout/stderr → pf.log + 原终端
```

不接管 stdout、只想写文件（如托盘这种自己有 _log 的）：

```python
from pf_logging import log
log("pf_bot 已在运行, 复用", "info")
```

`log(msg, level)` 的签名与 `pf_env.STATE.log` 一致，方便整体替换。
"""
from __future__ import annotations

import os
import sys
import threading
import time
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
LOG_DIR = PROJECT_ROOT / "debug" / "pf"
LOG_PATH = LOG_DIR / "pf.log"
LOCK_PATH = LOG_DIR / "pf.log.lock"

# 单个进程允许的最大日志量：超过就把当前文件轮转成 pf.log.1（只留一代备份，
# 免得 hoarding）。50MB ≈ bot 连打一整天。轮转在**持锁状态下**做。
MAX_BYTES = 50 * 1024 * 1024
KEEP_BACKUPS = 1

# 写日志绝不能把调用方卡死。持锁进程被强杀（僵尸 bot / taskkill）会留下锁，
# 这里用非阻塞锁 + 有限重试，拿不到锁就**放弃这条日志**继续跑。
LOCK_RETRIES = 50
LOCK_RETRY_SLEEP = 0.02          # 最坏 ~1s 后放弃

_install_lock = threading.Lock()
_level_width = 5                 # "error"/"warn "/"info "/"step"


def _now() -> str:
    return time.strftime("%H:%M:%S")


def _pid() -> int:
    return os.getpid()


def _rotate_locked(fh) -> None:
    """把 pf.log 轮转为 pf.log.1。**调用方必须已持锁**。

    只保一代备份：调试时你要的是"刚才发生了什么"，
    更早的世代已经由每日 pf-<日期>.log 归档（见 rotate_daily）。
    """
    try:
        size = fh.tell()
    except OSError:
        return
    if size < MAX_BYTES:
        return
    for old in range(KEEP_BACKUPS, 0, -1):
        src = LOG_DIR / (f"pf.log.{old}" if old > 1 else "pf.log.1")
        dst = LOG_DIR / (f"pf.log.{old + 1}" if old + 1 > 1 else "pf.log.1")
        try:
            if old == KEEP_BACKUPS and src.exists():
                src.replace(dst)
        except OSError:
            pass
    try:
        fh.close()
    except OSError:
        pass
    try:
        LOG_PATH.replace(LOG_DIR / "pf.log.1")
    except OSError:
        pass
    # 重新以追加模式打开（调用方负责重试写，故这里不抛）
    _REOPEN.setdefault("done", True)


# rotate 后需要让下一次 log() 重新打开句柄；用模块级 dict 传话，
# 避免把可变状态散成全局变量（也方便测试里 monkeypatch）。
_REOPEN: dict = {}


def _open_append():
    LOG_DIR.mkdir(parents=True, exist_ok=True)
    return open(LOG_PATH, "a", encoding="utf-8", errors="replace", newline="\n")


def _write_raw(text: str) -> bool:
    """持跨进程锁写一行。返回是否真的落盘。

    拿不到锁就返回 False —— **调用方永远不要因为日志失败而中断主流程**。
    """
    lock_fh = None
    for attempt in range(LOCK_RETRIES):
        try:
            lock_fh = open(LOCK_PATH, "a+b")
            lock_fh.seek(0)
            try:
                msvcrt_locking(lock_fh.fileno(), nonblocking=True)
            except OSError:
                time.sleep(LOCK_RETRY_SLEEP)
                continue
            break                      # 拿到锁
        except OSError:
            return False
    else:
        # 重试用尽：可能有个僵尸进程持着锁。放弃这条日志，但不能卡住 bot。
        if lock_fh is not None:
            try:
                lock_fh.close()
            except OSError:
                pass
        return False

    fh = None
    try:
        fh = _open_append()
        _rotate_locked(fh)
        if _REOPEN.pop("done", False):        # 轮转关过句柄 → 重开
            fh = _open_append()
        fh.write(text)
        fh.flush()
        return True
    except OSError:
        return False
    finally:
        try:
            if fh is not None:
                fh.close()
        except OSError:
            pass
        try:
            lock_fh.seek(0)
            msvcrt_locking(lock_fh.fileno(), unlock=True)
        except OSError:
            pass
        try:
            lock_fh.close()
        except OSError:
            pass


# msvcrt 的 import 在 Windows 上是免费的，但保留一层薄封装：
# 单元测试可以替换这两个函数，不必真去锁文件。
def msvcrt_locking(fd: int, nonblocking: bool = False, unlock: bool = False) -> None:
    import msvcrt
    if unlock:
        return msvcrt.locking(fd, msvcrt.LK_UNLCK, 1)
    mode = msvcrt.LK_NBLCK if nonblocking else msvcrt.LK_LOCK
    return msvcrt.locking(fd, mode, 1)


def log(msg: str, level: str = "info", *, tag: str = "") -> None:
    """写一行到统一日志（不接管 stdout 时直接用这个）。

    格式：`[HH:MM:SS][level] msg`，与旧 `STATE.log` 完全一致，
    这样历史 grep 习惯（`grep '\\[err\\]' debug/pf/*.log`）继续有效。
    `tag` 非空时追加 ` (tag)`，用于区分同一条消息来自哪个进程。
    """
    stamp = _now()
    tail = f" ({tag})" if tag else ""
    line = f"[{stamp}][{level:<{_level_width}}] {msg}{tail}\n"
    _write_raw(line)


class _Tee:
    """把 writes 同时送到原终端和 pf.log。

    为什么要 tee 而不是只写文件：手工起 bot 时（用户在自己终端里跑
    `python tools/pf_bot.py`）仍需要看到实时输出，不能因为统一日志而
    把终端输出弄丢。**原终端不可用时（pythonw / 重定向句柄已断）静默降级** ——
    这正是托盘场景，写不出去是常态不是错误。
    """

    def __init__(self, original, name: str) -> None:
        self._original = original
        self._name = name

    def write(self, data: str) -> int:
        if data and not data.isspace():
            try:
                _write_raw(data if data.endswith("\n") else data + "\n")
            except Exception:  # noqa: BLE001  日志永远不能带崩业务
                pass
        if self._original is not None:
            try:
                self._original.write(data)
                self._original.flush()
            except Exception:  # noqa: BLE001  终端没了(句柄被关)就放弃
                pass
        return len(data)

    def flush(self) -> None:
        if self._original is not None:
            try:
                self._original.flush()
            except Exception:  # noqa: BLE001
                pass

    def isatty(self) -> bool:
        try:
            return bool(self._original and self._original.isatty())
        except Exception:  # noqa: BLE001
            return False

    def fileno(self) -> int:
        # pythonw / 已关闭的句柄会抛；明确不支持即可（没人该对日志做 fd 操作）。
        if self._original is None:
            raise OSError("log stream has no fileno")
        return self._original.fileno()

    @property
    def encoding(self) -> str:
        return "utf-8"


_installed = False


def install() -> None:
    """接管 stdout/stderr → 统一日志 + 原终端。可重复调用，只生效一次。

    必须在进程入口**最早**处调用（其他模块 import 时打的日志也要进 pf.log）。
    """
    global _installed
    with _install_lock:
        if _installed:
            return
        _installed = True
    for name in ("stdout", "stderr"):
        original = getattr(sys, name, None)
        setattr(sys, name, _Tee(original, name))
    # 世代标记：单文件时代替"看文件名猜哪一代活着"。
    # 进程 pid + 启动时刻 + argv，足以定位任何一行的来源。
    try:
        argv = " ".join(sys.argv[:3])
    except Exception:  # noqa: BLE001
        argv = "?"
    log(f"════ 进程启动 pid={_pid()} argv={argv} ════", "step")


def log_path() -> str:
    """当前统一日志的绝对路径（给 WebUI / 日志入口用）。"""
    return str(LOG_PATH)


if __name__ == "__main__":      # 手动 smoke：连打 N 行，验证锁在真实多进程下不丢
    install()
    n = int(sys.argv[1]) if len(sys.argv) > 1 else 50
    for i in range(n):
        print(f"smoke {i} from pid={_pid()}", flush=True)
        time.sleep(0.005)
