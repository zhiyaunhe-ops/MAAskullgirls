"""实机扫描当前 PF hub 的全部场地卡 (只滑动 + OCR, 不点任何按钮)。

用途: 验证 run_new_pf 里"左右滑动找 score=0 新场"这一环在真机上真的能识别。
会恢复初始居中卡, 不改游戏状态。
"""
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

from pf_env import resolve_adb  # noqa: E402
from pf_scene import PfScene, ensure_mumu  # noqa: E402

t0 = time.time()
ensure_mumu(resolve_adb()[0])
scene = PfScene()
print(f"[{time.time()-t0:.1f}s] PfScene 就绪, 开始扫描", flush=True)

seen = scene.explore()

print("\n===== 扫描结果 =====", flush=True)
for t, s in seen:
    mark = "   <-- score=0 (新场)" if s == 0 else ""
    print(f"  {t or '(未识别)':<26} {s:>14,}{mark}", flush=True)
zeros = [t for t, s in seen if s == 0]
print(f"\n共 {len(seen)} 张场地卡; score=0: {zeros or '无'}", flush=True)
print(f"[{time.time()-t0:.1f}s] 完成", flush=True)
