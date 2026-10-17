"""每帧 x 每带区 x 每模板的 cv2 NCC 明细 (ground truth 口径), 排查错分/低分。

用法: python tools/mult_detail.py <dump目录> <模板目录>
"""
import glob
import os
import sys

import cv2
import numpy as np

FIRE = [(1180, 30, 100, 120), (1180, 206, 100, 120), (1180, 382, 100, 120)]
NAMES = ["mult_1", "mult_15", "mult_2", "mult_25", "mult_3", "mult_35", "mult_4", "mult_5"]
VALS = ["x1", "x1.5", "x2", "x2.5", "x3", "x3.5", "x4", "x5"]


def main():
    d = sys.argv[1] if len(sys.argv) > 1 else "debug/pf/dump_pull4"
    td = sys.argv[2] if len(sys.argv) > 2 else "phone/app/assets/templates/pf"
    tpls = []
    for n in NAMES:
        p = os.path.join(td, n + ".png")
        tpls.append(cv2.imread(p, cv2.IMREAD_UNCHANGED)[:, :, 0].astype(np.float32)
                    if os.path.isfile(p) else None)
    for p in sorted(glob.glob(os.path.join(d, "*_full_*.png"))):
        im = cv2.imread(p)[:, :, 0].astype(np.float32)
        print(os.path.basename(p)[:18])
        for c, (x, y, w, h) in enumerate(FIRE):
            sub = im[y:y + h, x:x + w]
            scores = []
            for i, t in enumerate(tpls):
                if t is None or t.shape[0] > h or t.shape[1] > w:
                    scores.append("  --  ")
                    continue
                r = cv2.matchTemplate(sub, t, cv2.TM_CCOEFF_NORMED)
                _, mx, _, loc = cv2.minMaxLoc(r)
                scores.append("%s=%.3f@%d,%d" % (VALS[i], mx, loc[0] + x, loc[1] + y))
            print("  卡%d  %s" % (c + 1, "  ".join(scores)))


if __name__ == "__main__":
    main()
