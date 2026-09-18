"""弹窗清理探针: 只跑「大厅前剥掉模态弹窗栈」这一步。

不启动游戏、不进 PF hub、不扫场、不碰 bot。用途:
  - wait_hall() 报 "150s 未回到大厅" 时, 定位到底是哪个弹窗没被识别;
  - 新增弹窗模板 / 调整 ROI 后, 单独验证识别效果;
  - 打印每个候选模板的**匹配分数**, wait_hall() 只报坐标不报分数。

用法 (anaconda python, 仓库根目录):
  python tools/_probe_popup.py            # 默认: 只拍一帧, 打分数表, 不点击
  python tools/_probe_popup.py --tap      # 真的逐个点 X, 直到大厅菱形出现
  python tools/_probe_popup.py --tap --max 6
  python tools/_probe_popup.py --tap --launch   # 先冷启动游戏再等大厅
  python tools/_probe_popup.py --skip-mumu      # 跳过 MuMu 状态检查(已在跑时)

退出码: 0=已到大厅 / 2=未到大厅 / 3=环境起不来
注意: pf_bot 运行中不要跑 (会跟 bot 抢点击)。
"""
from __future__ import annotations

import sys
import time
from pathlib import Path

import cv2

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

from pf_env import resolve_adb            # noqa: E402
from pf_scene import (                    # noqa: E402
    HOME_BTN, ROI_MODAL_X, ROI_RESULT, ROI_SCENE_X, ROI_TOPRIGHT,
    TPL_CONTINUE, TPL_HALL_PRIZE, TPL_MODAL_X, TPL_RESULT_CONTINUE,
    TPL_SCENE_X, PfScene, ensure_mumu, log,
)

TEMPLATE_DIR = ROOT / "assets" / "resource" / "base" / "image"

# 复用的弹窗处理链 (顺序 = 优先级), 与 pf_scene.wait_hall() 保持一致。
# ROI 传 (0,0,0,0) 表示全屏搜。
CHAIN = [
    ("模态弹窗X (每日奖励/通行证)", TPL_MODAL_X, ROI_MODAL_X, 0.90),
    ("促销弹窗X (BACK TO SCHOOL 等)", TPL_SCENE_X, ROI_SCENE_X, 0.80),
    ("结算 CONTINUE (居中)", TPL_RESULT_CONTINUE, ROI_RESULT, 0.70),
    ("结算 CONTINUE (右上)", TPL_CONTINUE, ROI_TOPRIGHT, 0.70),
    ("大厅 PRIZE FIGHTS 菱形", TPL_HALL_PRIZE, (0, 0, 0, 0), 0.72),
]

HALL_LABEL = "大厅 PRIZE FIGHTS 菱形"
WAIT_TIMEOUT = 150.0     # 与 pf_scene.HALL_TIMEOUT 对齐
MAX_POPUPS = 6

_tpl_cache: dict[str, object] = {}


def load_tpl(name: str):
    """按 MAA 资源路径 (如 'pf/hall_prize_fights.png') 读模板。"""
    if name not in _tpl_cache:
        path = TEMPLATE_DIR / name
        tpl = cv2.imread(str(path))
        if tpl is None:
            raise FileNotFoundError(f"模板读不到: {path}")
        _tpl_cache[name] = tpl
    return _tpl_cache[name]


def score_tpl(img, name: str, roi: tuple) -> tuple[float, tuple[int, int]]:
    """返回 (最高匹配分, 该点中心坐标)。roi=(0,0,0,0) 时全屏。"""
    tpl = load_tpl(name)
    x0, y0, x1, y1 = roi
    if (x0, y0, x1, y1) == (0, 0, 0, 0):
        x0, y0 = 0, 0
        y1, x1 = img.shape[:2]
    sub = img[y0:y1, x0:x1]
    res = cv2.matchTemplate(sub, tpl, cv2.TM_CCOEFF_NORMED)
    _, mx, _, ml = cv2.minMaxLoc(res)
    cx = ml[0] + x0 + tpl.shape[1] // 2
    cy = ml[1] + y0 + tpl.shape[0] // 2
    return float(mx), (cx, cy)


def report(img) -> tuple[str | None, tuple[int, int] | None]:
    """打一张分数表; 返回第一个达阈值的 (标签, 坐标)。"""
    log("---- 候选模板分数表 (th 为阈值, >= 即命中) ----")
    first: tuple[str, tuple[int, int]] | None = None
    for label, name, roi, th in CHAIN:
        s, c = score_tpl(img, name, roi)
        hit = s >= th
        log(f"  {'HIT ' if hit else '   '} {label:32s} {s:6.4f} / th={th:.2f}  @ {c}  [{name}]")
        if hit and first is None:
            first = (label, c)
    log("---------------------------------------------")
    return first if first else (None, None)


def main() -> int:
    tap = "--tap" in sys.argv
    launch = "--launch" in sys.argv
    skip_mumu = "--skip-mumu" in sys.argv
    max_popups = MAX_POPUPS
    if "--max" in sys.argv:
        max_popups = int(sys.argv[sys.argv.index("--max") + 1])
    timeout = WAIT_TIMEOUT

    # 1) 环境: MuMu 就绪 → adb connect (PfScene 构建时 setup 会连 adb)
    if not skip_mumu:
        try:
            ensure_mumu(resolve_adb()[0])
        except Exception as exc:            # noqa: BLE001
            log(f"MuMu 未就绪, 探针终止: {exc}", "err")
            return 3

    adb_path, addr = resolve_adb()
    log(f"adb = {adb_path}  device = {addr}")

    try:
        scene = PfScene()
    except Exception as exc:                # noqa: BLE001
        log(f"PfScene 构建失败 (adb 连不上?): {exc}", "err")
        return 3

    if launch:
        scene.launch_game()

    # 2) 只识别, 不点击
    img = scene.snap("popup_probe")
    label, center = report(img)
    if not tap:
        if label == HALL_LABEL:
            log("已在大厅, 无需清弹窗")
            return 0
        log(f"dry-run: 会点 [{label}] @ {center}  (加 --tap 才真的点)")
        return 2

    # 3) 真的逐轮点 X, 直到大厅菱形出现
    if label == HALL_LABEL:
        log(f"已在大厅, PRIZE FIGHTS @ {center}")
        return 0

    t0 = time.time()
    closed = 0
    bare_rounds = 0
    while time.time() - t0 < timeout:
        img = scene.snap("popup_probe")
        label, center = report(img)
        if label is None:
            bare_rounds += 1
            if bare_rounds % 5 == 1:
                log(f"没有任何候选命中 ({int(time.time() - t0)}s) —— "
                    f"画面既不是弹窗也不是大厅?", "warn")
                if bare_rounds == 1:
                    log("  兜底: 点顶栏房子回家试试", "warn")
                    scene.tap(*HOME_BTN)
            time.sleep(2.0)
            continue
        bare_rounds = 0
        if label == HALL_LABEL:
            log(f"已到大厅: PRIZE FIGHTS @ {center} (耗时 {time.time() - t0:.1f}s, "
                f"共关 {closed} 个弹窗)")
            return 0
        if closed >= max_popups:
            log(f"已关 {closed} 个弹窗达上限 --max {max_popups}, 停止 (避免误点)", "warn")
            return 2
        log(f"点 X 关闭 [{label}] @ {center}", "warn")
        scene.tap(*center)
        closed += 1
        time.sleep(1.8)

    log(f"{timeout:.0f}s 内未剥完弹窗栈 (共关 {closed} 个), 最后画面见 "
        f"debug/pf/run/ 下最新 frame", "err")
    return 2


if __name__ == "__main__":
    sys.exit(main())
