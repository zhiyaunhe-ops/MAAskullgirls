"""把 PF hub 居中卡的难度切到 Diamond (2026-10-09 P0 的无人值守兜底).

⚠️ 2026-10-12 起已接进主链: `pf_schedule.act_run_new_pf` 对 tag 属于
`pf_artag.DIAMOND_TAGS` 的场次, 在建场后、起 bot 前自动调 `ensure_diamond()`。
本文件仍可单独跑 (见下), 但**不再需要人工执行**。

前提: 游戏已开且停在 PF hub 目标卡 —— 即
  pf_schedule.py --action run_new_pf --params {"no_start": true}
的产物 (建场后停在目标卡, 不点 PLAY!)。

为什么必须在做: hub 卡默认 BRONZE, bot 直接点 PLAY! → FIGHT! 会弹
「locked into the Bronze difficulty」→ 点 CONTINUE 后该 PF **永久锁 Bronze
(不可逆)**。全链没有切难度步骤 (2026-10-09 用户指出), 本脚本在建场后、
开跑前把档位切到 Diamond。

机制 (2026-10-09 实机探针, debug/pf/_probe_before.png / _probe_diamond.png):
难度行是可点切换器, 点右箭头一次 +1 档 (Bronze→Silver→Gold→Diamond),
到顶只显示左箭头 (右箭头消失)。截图 1100x605 是 1280x720 的缩放,
坐标已换算。Diamond 判定 = 难度行 OCR 归一后含 "DIAM" (BRONZE/SILVER/GOLD
均不含该前缀, OCR 残串也兜得住)。

退出码: 0 = 已是/已是 Diamond; 1 = 4 次读取均未确认 Diamond (留档截图
debug/pf/run/difficulty_fail_*.png, 此时**不要开跑**)。
"""

from __future__ import annotations

import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from pf_env import resolve_adb  # noqa: E402
from pf_scene import PfScene, ensure_mumu, log  # noqa: E402

# 难度行文字 ROI (x0,y0,x1,y1, 1280x720): 文字 BRONZE/DIAMOND 居中同位
DIFF_ROI = (540, 358, 720, 402)
# 右箭头 ➤ 中心 (Bronze 态); 与 PLAY! (y≈458) 相距 70px+, 不会误触
ARROW = (720, 385)
MAX_TAPS = 3  # Bronze→Diamond 最多 3 次


def _norm(s: str) -> str:
    return "".join(ch for ch in s.upper() if ch.isalnum())


def ensure_diamond(scene, max_taps: int = MAX_TAPS) -> bool:
    """把当前**居中卡**的难度切到 Diamond。返回 True = OCR 已确认 Diamond。

    调用方负责保证: 游戏开着、PF hub 已打开、目标卡已居中 (pick_new_arena 的
    产物)。本函数不碰导航 —— 居中卡不对时点右箭头升的是**别的卡**的档。

    失败 (读不到 DIAMOND) 时返回 False 并留档 difficulty_fail 截图; 调用方
    **此时不要起 bot** —— 见模块 docstring: 锁错档不可逆。
    """
    last = ""
    for i in range(max_taps + 1):
        last = _norm(scene.ocr(DIFF_ROI))
        log(f"难度行 OCR[{i}]: {last!r}")
        if "DIAM" in last:
            scene.snap("difficulty_diamond")
            log(f"OK: 难度已确认 Diamond (OCR={last!r}, 点击 {i} 次)")
            return True
        if i == max_taps:
            break
        scene.tap(*ARROW)
        time.sleep(1.2)
    scene.snap("difficulty_fail")
    log(f"FAIL: {max_taps + 1} 次读取均未出现 DIAMOND, 最后 OCR={last!r}", "err")
    return False


def main() -> int:
    ensure_mumu(resolve_adb()[0])
    scene = PfScene()
    return 0 if ensure_diamond(scene) else 1


if __name__ == "__main__":
    raise SystemExit(main())
