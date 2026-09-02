"""PF 界面视觉分析（纯 cv2 + 几何常量，可离线用截图测试）。

屏幕均为 1280x720 横屏。所有 ROI 为 (x0, y0, x1, y1)。

能量判据（用户确认）：卡底黄色闪电数量 = 当前能量，>= ENERGY_COST 即可出战。
金色属性卡的铭牌/底色偏黄，故钉芯用严格高亮黄掩码 (R>=240, G>=200, B<=105)
+ 列占比游程计数，对底色免疫。
"""
from __future__ import annotations

import re

import cv2
import numpy as np

# 可调参数：出战一场需要的能量（黄钉数）
ENERGY_COST = 4

# ---------------- 对手选择页 ----------------
# 三张对手卡：战力在卡左上，火框倍率在卡右上
OPPONENT_CARDS = [
    {
        "name": "card1",
        "click": (1000, 235),
        "power_roi": (800, 166, 884, 198),
        "fire_roi": (1136, 156, 1206, 222),
    },
    {
        "name": "card2",
        "click": (1000, 412),
        "power_roi": (775, 342, 860, 374),
        "fire_roi": (1136, 332, 1206, 398),
    },
    {
        "name": "card3",
        "click": (1000, 590),
        "power_roi": (798, 514, 900, 554),
        "fire_roi": (1136, 508, 1206, 574),
    },
]

# 对手选择页左侧面板的总分数值（金色数字 "4,398,061"）
SCORE_ROI = (132, 336, 275, 370)
# 同面板的连胜层数（绿色数字 "14"），两位数右对齐在 x≈334
STREAK_ROI = (280, 200, 352, 238)

# ---------------- 编队页 ----------------
# 三个出战槽：卡面呈扇形（铭牌中心 [130,355,557]，用作拖拽落点），
# 但钉条是独立等距层：起点 x=65，层间距 192，钉距 13.5
SLOT_DROP_X = [130, 355, 557]
SLOT_PIP_CENTERS = [132 + 192 * i for i in range(3)]
SLOT_BAND = (349, 369)      # 钉条行带
SLOT_HALF = 75              # 窗口半宽

# 底部候选横列：卡距 198px，卡1钉条中心 x≈116，钉条行带 y≈679-701
ROSTER_CENTERS = [(116 + 198 * i, 565) for i in range(6)]
ROSTER_BAND = (679, 701)
ROSTER_HALF = 66

# ---------------- 颜色掩码 ----------------
def _mask_red(img: np.ndarray) -> np.ndarray:
    """火焰/红色元素的红色掩码（含橙红）。"""
    b, g, r = img[:, :, 0].astype(int), img[:, :, 1].astype(int), img[:, :, 2].astype(int)
    return ((r >= 150) & (r - g >= 60) & (r - b >= 60)).astype(np.uint8)


def _mask_strict_yellow(img: np.ndarray) -> np.ndarray:
    """高亮黄钉芯掩码。金卡淡黄底 (231,231,132) 不通过，钉芯 (255,219,66) 通过。"""
    b, g, r = img[:, :, 0].astype(int), img[:, :, 1].astype(int), img[:, :, 2].astype(int)
    return ((r >= 240) & (g >= 200) & (b <= 105)).astype(np.float32)


def red_pixel_ratio(img: np.ndarray, roi: tuple) -> float:
    x0, y0, x1, y1 = roi
    crop = img[y0:y1, x0:x1]
    if crop.size == 0:
        return 0.0
    return float(_mask_red(crop).sum()) / crop[:, :, 0].size


def has_fire_box(img: np.ndarray, fire_roi: tuple, threshold: float = 0.04) -> bool:
    """对手卡右上角是否存在带火的倍率框（离线实测: 火卡 0.17-0.26, 无火 0.03）。"""
    return red_pixel_ratio(img, fire_roi) >= threshold


def _count_runs(frac: np.ndarray, th: float, run_min: int, run_max: int) -> int:
    n, inrun, w = 0, False, 0
    for v in frac:
        if v >= th:
            inrun, w = True, w + 1
        else:
            if inrun and run_min <= w <= run_max:
                n += 1
            inrun, w = False, 0
    if inrun and run_min <= w <= run_max:
        n += 1
    return n


def count_yellow_bolts(
    img: np.ndarray,
    band_y: tuple,
    center_x: int,
    half: int,
    th: float = 0.08,
    run_min: int = 2,
    run_max: int = 9,
) -> int:
    """统计 (center_x ± half) 窗口内 band_y 行带的黄钉数量。"""
    x0 = max(0, center_x - half)
    x1 = min(img.shape[1], center_x + half)
    band = _mask_strict_yellow(img[band_y[0]:band_y[1], x0:x1])
    frac = band.mean(axis=0)
    if frac.max() < 0.12:
        return 0
    return _count_runs(frac, th, run_min, run_max)


def read_slot_energy(img: np.ndarray) -> list[int]:
    """三个出战槽的黄钉数。"""
    return [
        count_yellow_bolts(img, SLOT_BAND, cx, SLOT_HALF, run_max=8)
        for cx in SLOT_PIP_CENTERS
    ]


def read_roster_energy(img: np.ndarray) -> list[int]:
    """六个可见候选位的黄钉数。"""
    return [count_yellow_bolts(img, ROSTER_BAND, cx, ROSTER_HALF) for cx, _ in ROSTER_CENTERS]


def slot_usable(img: np.ndarray, index: int) -> bool:
    return read_slot_energy(img)[index] >= ENERGY_COST


def roster_usable(img: np.ndarray) -> list[bool]:
    """六个候选位是否满足出战能量。"""
    return [n >= ENERGY_COST for n in read_roster_energy(img)]


# ---------------- 数字解析 ----------------
def parse_power(text: str) -> float | None:
    """'28.1k' / '9,713' / '41.2k 3N' / '34.C' -> 数值（正则提取第一个数字段）。失败返回 None。"""
    if not text:
        return None
    m = re.search(r"(\d{1,3}(?:,\d{3})+|\d+(?:\.\d+)?)\s*([kKmM])?", text)
    if not m:
        return None
    try:
        val = float(m.group(1).replace(",", ""))
    except ValueError:
        return None
    unit = (m.group(2) or "").lower()
    if unit == "k":
        val *= 1000
    elif unit == "m":
        val *= 1_000_000
    return val


def parse_mult(text: str) -> float | None:
    """'x1.5' / 'X2' / 'x1.5y' -> 1.5（正则提取数字段）。失败返回 None。"""
    if not text:
        return None
    m = re.search(r"(\d+(?:\.\d+)?)", text)
    if not m:
        return None
    try:
        return float(m.group(1))
    except ValueError:
        return None


# ---------------- 火框徽章动态定位 ----------------
# 徽章位置随卡片宽窄浮动（2号卡更宽更靠左），固定 ROI 会切掉数字，
# 必须在卡右上区域找最大红色连通域。三个搜索窗覆盖各自卡的所有浮动位置。
FIRE_SEARCH = [(1100, 138, 1216, 215), (1100, 318, 1216, 400), (1100, 493, 1216, 570)]


def find_fire_badge(img: np.ndarray, search: tuple) -> tuple | None:
    """卡右上角火焰倍率徽章的 bbox (x0,y0,x1,y1)，无火返回 None。

    徽章紧贴卡片右上角；候选红色连通域须满足 角部约束（右缘接近窗右、
    顶部接近窗顶），以排除卡内红色元素的头像/装饰。
    """
    x0, y0, x1, y1 = search
    m = _mask_red(img[y0:y1, x0:x1])
    n, lab, stats, _ = cv2.connectedComponentsWithStats(m, 8)
    best, area = 0, 0
    for i in range(1, n):
        a = stats[i, cv2.CC_STAT_AREA]
        if a < 400 or a <= area:
            continue
        sx, sy, sw, _sh = stats[i, 0], stats[i, 1], stats[i, 2], stats[i, 3]
        right, top = x0 + sx + sw, y0 + sy
        if right >= x1 - 60 and top <= y0 + 60:  # 角部约束
            area, best = a, i
    if not best:
        return None
    sx, sy, sw, sh = stats[best, 0], stats[best, 1], stats[best, 2], stats[best, 3]
    return (x0 + sx - 2, y0 + sy - 2, x0 + sx + sw + 2, y0 + sy + sh + 2)


# ---------------- 调试标定 ----------------
def annotate_calibration(img_path: str, out_path: str, page: str) -> None:
    """把 ROI 画到截图上，人工核对几何常量。page: 'opponent' | 'team'"""
    img = cv2.imread(img_path)
    if page == "opponent":
        for card in OPPONENT_CARDS:
            for key, color in (("power_roi", (0, 255, 255)), ("fire_roi", (0, 0, 255))):
                x0, y0, x1, y1 = card[key]
                cv2.rectangle(img, (x0, y0), (x1, y1), color, 2)
            cv2.circle(img, card["click"], 6, (0, 255, 0), -1)
    else:
        for cx in SLOT_PIP_CENTERS:
            cv2.rectangle(img, (cx - SLOT_HALF, SLOT_BAND[0]), (cx + SLOT_HALF, SLOT_BAND[1]), (0, 0, 255), 2)
        for (cx, _), (y0, y1) in zip(ROSTER_CENTERS, [ROSTER_BAND] * 6):
            cv2.rectangle(img, (cx - ROSTER_HALF, y0), (cx + ROSTER_HALF, y1), (0, 0, 255), 2)
            cv2.circle(img, (cx, 565), 6, (0, 255, 0), -1)
    cv2.imwrite(out_path, img)
