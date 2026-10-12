"""轮询 bot 当前场次分数, 到阈值即退 0 (无人值守验证任务用).

用法:
    python tools/poll_score.py [目标分] [分钟数] [--url URL]
        目标分  默认 1000000 (100w)
        分钟数  默认 8 (单轮上限; 不够就再跑一轮, 别把一轮拖到工具超时)
        --url   默认 http://127.0.0.1:8790/api/state (测试可指到别处)

退出码: 0 = 到阈值; 2 = 超时未到 (bot 仍在跑, **不要停**); 1 = 用法错误。
设计成「到点就退、不到就超时退」而不是常驻: 调用方 (定时任务) 自己决定
要不要再轮一轮, 避免一个命令挂死把设备锁到白天。
"""
from __future__ import annotations

import json
import sys
import time
import urllib.request

DEFAULT_URL = "http://127.0.0.1:8790/api/state"
DEFAULT_TARGET = 1_000_000
DEFAULT_MINUTES = 8


def _fetch(url: str):
    # 本机回环也要绕代理: 环境里挂着 HTTP_PROXY 时 urllib 会把它当外网地址。
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(url, timeout=5) as r:
        return json.load(r)


def poll(target: int, minutes: int, url: str, out=print, interval: int = 60,
         err_sleep: int = 30) -> int:
    deadline = time.time() + minutes * 60
    while time.time() < deadline:
        try:
            d = _fetch(url)
        except Exception as e:  # noqa: BLE001 轮询工具不该因一次网络抖动就崩
            out(f"[{time.strftime('%H:%M:%S')}] 取不到 /api/state: {e}")
            time.sleep(err_sleep)
            continue
        sc = d.get("score") or 0
        out(f"[{time.strftime('%H:%M:%S')}] score={sc} "
            f"status={d.get('status')} step={d.get('step')} svc={d.get('svc')}")
        if sc >= target:
            out(f"REACHED: {sc} >= {target}")
            return 0
        time.sleep(interval)
    out("TIMEOUT: 未到阈值 (bot 仍在跑, 不要停)")
    return 2


def main(argv) -> int:
    args = [a for a in argv[1:] if not a.startswith("--")]
    url = DEFAULT_URL
    if "--url" in argv:
        url = argv[argv.index("--url") + 1]
    if len(args) > 2:
        print(__doc__)
        return 1
    target = int(args[0]) if args else DEFAULT_TARGET
    minutes = float(args[1]) if len(args) > 1 else DEFAULT_MINUTES
    return poll(target, minutes, url)


if __name__ == "__main__":
    sys.exit(main(sys.argv))
