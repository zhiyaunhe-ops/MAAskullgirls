# -*- coding: utf-8 -*-
"""远程采集器: 轮询手机调试口 /screencap, 落盘所有帧, 离线再筛选对手页。

用法: python tools/collect_remote_frames.py [分钟] [输出目录]
默认 20 分钟, 输出到 debug/pf/collect_remote/。
每帧存 JPEG (与 /screencap 一致, work 1280x576)。文件名带毫秒时间戳。
"""
import os
import sys
import time
import urllib.request

PORT = int(os.environ.get("PF_HTTP_PORT", "18791"))
BASE = "http://127.0.0.1:%d" % PORT

mins = float(sys.argv[1]) if len(sys.argv) > 1 else 20
out = sys.argv[2] if len(sys.argv) > 2 else os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "debug", "pf", "collect_remote")
os.makedirs(out, exist_ok=True)

deadline = time.time() + mins * 60
n = 0
fail = 0
while time.time() < deadline:
    t0 = time.time()
    try:
        with urllib.request.urlopen(BASE + "/screencap", timeout=5) as r:
            data = r.read()
        if len(data) > 5000:  # "capt fail" 文本太小, 跳过
            n += 1
            name = time.strftime("%H%M%S") + "_%03d.jpg" % (int((t0 % 1) * 1000))
            with open(os.path.join(out, name), "wb") as f:
                f.write(data)
        else:
            fail += 1
    except Exception:
        fail += 1
    # 保持 ~1.5s 节奏
    time.sleep(max(0.2, 1.5 - (time.time() - t0)))

print("saved=%d fail=%d -> %s" % (n, fail, out))
