"""量测每张 dump 帧里红色倍率徽章的精确 bbox (右列), 验证 FIRE_SEARCH 带区是否够宽。

用法: python tools/mult_badge_pos.py <dump目录>
"""
import glob
import os
import sys

import cv2
import numpy as np

ROI_X, ROI_W = 1150, 130          # 右列 (徽章右缘贴 1280)
RED = ((0, 60, 90), (90, 160, 255))   # BGR: 徽章红底 (实测取样后放宽)


def badge_boxes(path):
    im = cv2.imread(path)
    if im is None:
        return []
    col = im[:, ROI_X:ROI_X + ROI_W]
    b, g, r = col[:, :, 0].astype(int), col[:, :, 1].astype(int), col[:, :, 2].astype(int)
    mask = ((r > RED[0][2]) & (r <= RED[1][2]) & (g < RED[1][1]) & (b < RED[0][0] + 100)
            & (r - g > 60) & (r - b > 40)).astype(np.uint8)
    n, lab, stats, _ = cv2.connectedComponentsWithStats(mask, 8)
    out = []
    for i in range(1, n):
        x, y, w, h, area = stats[i]
        if area < 120 or w < 24 or h < 20:
            continue
        out.append((x + ROI_X, y, w, h, area))
    return sorted(out, key=lambda t: t[1])


def main():
    d = sys.argv[1] if len(sys.argv) > 1 else "debug/pf/dump_pull4"
    fs = sorted(glob.glob(os.path.join(d, "*_full_*.png")))
    print("带区 (work): 卡1 y30-150  卡2 y206-326  卡3 y382-502  (预期中心 88/264/440)")
    for p in fs:
        boxes = badge_boxes(p)
        s = "  ".join("x%d y%d %dx%d a%d" % b for b in boxes)
        print(os.path.basename(p), "->", s if s else "(无)")


if __name__ == "__main__":
    main()
