"""验证「按字高归一化 + 7 档模板」的判别力 (2026-10-17)。

用 tools/pf_mult_band.py 聚类得到的人工标签跑混淆矩阵:
  样本 → 火焰框 → 白字 bbox → 归一化窗口 → 与 7 个 band 模板比 → argmax
输出: 命中率 / 每个样本的 winner 分与 runner-up 分 (看"领先幅度"够不够设门限)

用法: python tools/pf_mult_band_eval.py <dump目录> <band模板目录>
"""
import glob
import os
import sys

import cv2
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from pf_mult_band import FIRE, BW, BH, WIN_X, WIN_Y, flame_box   # noqa: E402

# 人工标签 (来自 band_sheet.png 的簇 → 档位; 见 tools/pf_mult_band.py 输出)
CLUSTER_TIER = {0: 2.0, 1: 1.5, 2: 1.5, 3: 1.5, 4: 2.5, 5: 3.0, 6: 3.5,
                7: 3.0, 8: 4.5, 9: 4.0, 10: 2.5, 11: 1.5, 12: 3.5}
# 每档取"簇最大"的那个当模板
TIER_SRC = {1.5: 1, 2.0: 0, 2.5: 4, 3.0: 5, 3.5: 6, 4.0: 9, 4.5: 8}


def norm_crop(im, fbox):
    fx, fy, fw, fh = fbox
    x0, y0 = max(0, fx), max(0, fy)
    x1, y1 = min(im.shape[1], fx + fw), min(im.shape[0], fy + fh)
    if x1 - x0 < 8 or y1 - y0 < 8:
        return None
    sub = im[y0:y1, x0:x1]
    b, g, r = sub[:, :, 0].astype(int), sub[:, :, 1].astype(int), sub[:, :, 2].astype(int)
    white = ((r > 150) & (g > 150) & (b > 150)).astype(np.uint8)
    ys, xs = np.nonzero(white)
    if len(xs) < 30:
        return None
    tx0, tx1, ty0, ty1 = xs.min(), xs.max(), ys.min(), ys.max()
    th_, tw_ = ty1 - ty0 + 1, tx1 - tx0 + 1
    if th_ < 8 or tw_ < 8:
        return None
    cx, cy = x0 + (tx0 + tx1) / 2.0, y0 + (ty0 + ty1) / 2.0
    c = cv2.getRectSubPix(im, (max(4, int(tw_ * WIN_X)), max(4, int(th_ * WIN_Y))), (cx, cy))
    return cv2.resize(c, (BW, BH), interpolation=cv2.INTER_AREA)


def score(tpl, img):
    """模板 48x27 在 48x27 的图上比 → 单点; 这里给四周留 4px 黑边做平移容错"""
    pad = 4
    big = cv2.copyMakeBorder(img, pad, pad, pad, pad, cv2.BORDER_REPLICATE)
    r = cv2.matchTemplate(big[:, :, 0].astype(np.float32), tpl[:, :, 0].astype(np.float32),
                          cv2.TM_CCOEFF_NORMED)
    return float(r.max())


def main():
    d = sys.argv[1] if len(sys.argv) > 1 else "debug/pf/dump_pull6/pf"
    td = sys.argv[2] if len(sys.argv) > 2 else "debug/tmp/mult_band"
    tpls = {}
    for tier, k in TIER_SRC.items():
        p = os.path.join(td, "band_c%d.png" % k)
        t = cv2.imread(p)
        if t is None:
            print("缺模板", p)
            continue
        tpls[tier] = t
    print("档位模板:", sorted(tpls))

    conf = {}
    ok = bad = skip = 0
    margins = []
    cases = []
    for p in sorted(glob.glob(os.path.join(d, "*_full_*.png"))):
        im = cv2.imread(p)
        if im is None or im.shape[0] != 576:
            continue
        for pos, band in enumerate(FIRE):
            fb = flame_box(im, band)
            if fb is None:
                continue
            img = norm_crop(im, fb)
            if img is None:
                skip += 1
                continue
            s = sorted(((score(t, img), tier) for tier, t in tpls.items()), reverse=True)
            win, tier = s[0]
            second = s[1][0] if len(s) > 1 else 0
            # 真值: 用 63 帧前的聚类标签不对齐 → 这里不打真值, 只统计"自洽性"
            margins.append(win - second)
            cases.append((os.path.basename(p), pos + 1, tier, win, second))
            conf[tier] = conf.get(tier, 0) + 1
    print("可评样本 %d (丢弃 %d)" % (len(cases), skip))
    print("命中分布:", {k: v for k, v in sorted(conf.items())})
    m = np.array(margins)
    print("winner 分: 中位 %.3f  p10 %.3f  最低 %.3f" % (np.median(m * 0 + [c[3] for c in cases]),
                                                     np.percentile([c[3] for c in cases], 10),
                                                     min(c[3] for c in cases)))
    print("领先幅度: 中位 %.3f  p10 %.3f  最低 %.3f" % (np.median(m), np.percentile(m, 10), m.min()))
    weak = [c for c in cases if c[3] < 0.85 or (c[3] - c[4]) < 0.08]
    print("弱样本 %d 个 (前 12):" % len(weak))
    for c in weak[:12]:
        print("   %s 卡%d -> x%s  win=%.3f second=%.3f" % c)


if __name__ == "__main__":
    main()
