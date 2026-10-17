"""把每帧右列三个带区的徽章裁出来拼一张 contact sheet, 直接看缺哪些档位。

输出: debug/tmp/mult_sheet.png  (每行一帧, 列 = 卡1/卡2/卡3, 放大 2x)
"""
import glob
import os
import sys

import cv2
import numpy as np

FIRE = [(1180, 30), (1180, 206), (1180, 382)]
W, H = 100, 120
PAD = 6


def main():
    d = sys.argv[1] if len(sys.argv) > 1 else "debug/pf/dump_pull4"
    fs = sorted(glob.glob(os.path.join(d, "*_full_*.png")))
    rows = []
    for p in fs:
        im = cv2.imread(p)
        if im is None:
            continue
        row = []
        for (x, y) in FIRE:
            crop = im[y:y + H, x:x + W]
            row.append(cv2.copyMakeBorder(crop, 2, 2, 2, 2, cv2.BORDER_CONSTANT,
                                          value=(60, 60, 60)))
        rows.append((os.path.basename(p)[:17], row))
    if not rows:
        print("no frames")
        return
    cw = (W + 4) * 3 + PAD * 4
    ch = len(rows) * (H + 22) + PAD
    sheet = np.full((ch, cw, 3), 30, np.uint8)
    for i, (name, row) in enumerate(rows):
        y0 = PAD + i * (H + 22)
        cv2.putText(sheet, name, (PAD, y0 + 14), cv2.FONT_HERSHEY_SIMPLEX,
                    0.45, (200, 200, 200), 1)
        for j, c in enumerate(row):
            x0 = PAD + j * (W + 4 + PAD)
            sheet[y0 + 18:y0 + 18 + H + 4, x0:x0 + W + 4] = c
    out = "debug/tmp/mult_sheet.png"
    cv2.imwrite(out, sheet)
    print("wrote", out, sheet.shape)


if __name__ == "__main__":
    main()
