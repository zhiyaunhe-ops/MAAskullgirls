"""统一日志 pf.log 的并发安全与降级回归 (2026-10-02)。

## 为什么这个文件存在

统一日志的**全部价值**建立在一条假设上：多个进程（bot / 托盘 / 调度器 / 重生代）
可以安全地往同一个 `debug/pf/pf.log` 追加而不丢行。这不是想当然 —— 2026-10-02
实测：6 个 python 进程各 append 同一文件、各写 200 行并 flush，期望 1200 行，
**实际只落 1039 行，丢 161 行**（且没有一行交错混合，说明是覆盖丢失而非撕裂）。

Windows 上 `open(..., 'a')` 的 append 语义不保证跨进程原子：每个进程持有独立的
文件位置，并发 seek/写会互相覆盖字节区间。**所以「让几个进程直接 >> 同一个文件」
在本机根本行不通** —— 这也正是改造前项目被迫「每代进程一个日志文件」的真实原因
（表现为：bot 正在跑第 143 场，而 `bot_stdout.log` 停在 5 小时前，因为当前这代
写的是 `bot_stdout_<时间戳>.log`）。

解法是 `msvcrt.locking` 跨进程行锁，实测同样 6 进程 1200 行零丢行。
本文件把这个结论**钉成回归测试**，防止有人日后「简化」掉锁。

跑法:
    C:/Users/zhiya/anaconda3/python.exe -m pytest tests/test_pf_logging.py -q
"""
import subprocess
import sys
import time
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
PY = r"C:\Users\zhiya\anaconda3\python.exe"

# 子进程体: 装统一日志, 打 N 行。**必须逐行 flush** —— 这是被测行为的一部分:
# 真实场景(bot 战斗循环)就是 flush 一行落一行, 不缓冲。
CHILD = r"""
import sys, time
sys.path.insert(0, %(tools)r)
import pf_logging as L
L.LOG_PATH = L.Path(%(log)r)
L.LOG_DIR = L.LOG_PATH.parent
L.LOCK_PATH = L.LOG_PATH.with_suffix(L.LOG_PATH.suffix + '.lock')
L.install()
# argv 在 -c 模式下是 ['-c', ...], 故 gen=argv[1], n=argv[2]
gen, n = sys.argv[1], int(sys.argv[2])
for i in range(n):
    print('gen%%s line%%s' %% (gen, i), flush=True)
    time.sleep(0.001)
"""


def _spawn_writers(tmp_path: Path, procs: int = 6, lines: int = 150):
    """同时起 procs 个进程各写 lines 行, 返回日志文件路径。"""
    tools = str(REPO / "tools")
    log = tmp_path / "pf.log"
    log.parent.mkdir(parents=True, exist_ok=True)
    running = [
        subprocess.Popen(
            [sys.executable, "-c", CHILD % {"tools": tools, "log": str(log)},
             str(l), str(lines)],
            stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        for l in range(procs)
    ]
    for p in running:
        _, err = p.communicate(timeout=120)
        assert p.returncode == 0, err.decode("utf-8", "replace")
    return log


def test_concurrent_writers_lose_no_lines(tmp_path):
    """6 进程 × 150 行 = 900 行, 一条都不能丢。

    这是统一日志的承重测试。改前 6×200 只落 1039/1200 —— 若哪天有人把
    pf_logging 的锁去掉, 这个断言会立刻炸。
    """
    log = _spawn_writers(tmp_path, procs=6, lines=150)
    text = log.read_text(encoding="utf-8", errors="replace")
    # 过滤掉 install() 打的世代标记, 只数业务行
    lines = [ln for ln in text.splitlines()
             if ln.strip() and "进程启动" not in ln]
    assert len(lines) == 6 * 150, f"丢了行: 期望 900, 实际 {len(lines)}"
    # 也不能有"撕裂行"(两个进程的输出混在一行) —— 那说明没持锁就写
    for ln in lines:
        assert ln.count("gen") == 1, f"撕裂行: {ln!r}"
    # 每个进程的行都在, 且序号完整 (丢行往往表现为某进程缺中间几行)
    for gen in range(6):
        got = sorted(int(ln.rsplit("line", 1)[1]) for ln in lines if ln.startswith(f"gen{gen} "))
        assert got == list(range(150)), f"gen{gen} 不完整: {len(got)}/150"


def test_every_writer_leaves_start_marker(tmp_path):
    """每个进程启动都留世代标记 —— 单文件时代替"看文件名猜哪一代活着"。"""
    log = _spawn_writers(tmp_path, procs=3, lines=5)
    text = log.read_text(encoding="utf-8", errors="replace")
    assert text.count("════ 进程启动") == 3, text[:400]
    for pid in {ln.split("pid=")[1].split()[0] for ln in text.splitlines()
                if "进程启动" in ln}:
        assert pid.isdigit()


def test_zombie_lock_does_not_block_caller(tmp_path):
    """有僵尸进程持锁时, log() 必须在有界时间内放弃返回, 绝不能把 bot 卡死。

    宁可丢一条日志，也不能让日志系统成为 bot 的停机原因 —— 这是 2026-10-02
    设计时的硬取舍（LOCK_RETRIES 50 × 0.02s ≈ 1s 上限）。
    """
    code = r"""
import sys, time
sys.path.insert(0, %(tools)r)
import pf_logging as L, msvcrt
L.LOG_PATH = L.Path(%(log)r); L.LOG_DIR = L.LOG_PATH.parent
L.LOCK_PATH = L.LOG_PATH.with_suffix('.log.lock')
L.LOG_PATH.parent.mkdir(parents=True, exist_ok=True)
k = open(L.LOCK_PATH, 'a+b'); k.seek(0)
msvcrt.locking(k.fileno(), msvcrt.LK_LOCK, 1)      # 模拟僵尸持锁
t = time.time(); L.log('僵尸锁下的一行', 'warn'); dt = time.time() - t
print('ELAPSED_OK' if dt < 5 else 'ELAPSED_BAD', '%%.2f' %% dt)
"""
    p = subprocess.run(
        [sys.executable, "-c", code % {"tools": str(REPO / "tools"),
                                       "log": str(tmp_path / "pf.log")}],
        capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=60)
    assert "ELAPSED_OK" in p.stdout, p.stdout + p.stderr


def test_unwritable_target_never_raises(tmp_path):
    """日志落不下去时不得抛异常 —— 日志失败绝不能带崩业务线程。"""
    code = r"""
import sys
sys.path.insert(0, %(tools)r)
import pf_logging as L
L.LOG_PATH = L.Path('Z:/definitely-not-here-xyz/pf.log')
L.LOG_DIR = L.LOG_PATH.parent
L.LOCK_PATH = L.LOG_PATH.with_suffix('.lock')
L.log('目录不可写时的一行', 'err')
print('NO_RAISE')
"""
    p = subprocess.run(
        [sys.executable, "-c", code % {"tools": str(REPO / "tools")}],
        capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=60)
    assert "NO_RAISE" in p.stdout, p.stdout + p.stderr


def test_tee_still_writes_to_original_stream(capsys):
    """install() 是 tee 不是 sink: 手工前台跑 bot 时终端仍要看得到输出。"""
    sys.path.insert(0, str(REPO / "tools"))
    import pf_logging as L
    L.LOG_DIR = REPO / "debug" / "pf"          # 复用真实目录, 不额外造文件
    tee = L._Tee(sys.stdout, "stdout")
    tee.write("tee- passthrough\n")
    assert "tee- passthrough" in capsys.readouterr().out
