"""分阶段探针: 定位 run_new_pf 链路卡在哪一步 (只建 PfScene, 不做任何点击)。

用法: python tools/_probe_chain.py
"""
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

t0 = time.time()


def mark(label):
    print(f"[{time.time() - t0:6.1f}s] --- {label} ---", flush=True)


mark("开始")
from pf_env import resolve_adb, WEBUI_PORT  # noqa: E402

mark(f"pf_env OK (WEBUI_PORT={WEBUI_PORT})")
adb, addr = resolve_adb()
print("  adb =", adb, "addr =", addr, flush=True)

from pf_schedule import bot_alive  # noqa: E402

mark(f"bot_alive = {bot_alive()}")

from pf_scene import PfScene, ensure_mumu, TPL_HALL_PRIZE, ROI_HUB_PLAY  # noqa: E402

mark("pf_scene 导入 OK")

ensure_mumu(adb)
mark("ensure_mumu 完成")

scene = PfScene()
mark("PfScene 构建 OK (含 bot.setup)")

img = scene.snap("probe")
print("  截图 shape =", None if img is None else getattr(img, "shape", "?"), flush=True)
mark("snap OK")

box = scene.bot.match_tpl(scene.snap(), TPL_HALL_PRIZE, (0, 0, 0, 0), th=0.72)
print("  大厅菱形 =", box, flush=True)
hit = scene.bot.match_tpl(scene.snap(), "pf/hub_play.png", ROI_HUB_PLAY, th=0.7)
print("  PF hub PLAY =", hit, flush=True)
mark("模板匹配 OK —— 当前屏幕状态已读出")
