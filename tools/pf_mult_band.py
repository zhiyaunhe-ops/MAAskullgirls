"""实验: 倍率识别改用「火焰内数字带」而不是整块徽章 (2026-10-17)。

动机 (实测):
- 整块 60x66 模板里带了**对手卡面美术**, 每个对手都不一样 ⇒ 同档位在同一卡位会分裂成
  多个簇, 跨对手掉到 0.5~0.65 (这就是当初 3 张卡里 2 张读不出的真因)。
- 数字带只含「白字 + 红底」, 背景被构造性排除; 再按火焰 bbox 归一化尺寸 ⇒ 卡位透视
  (±13%) 与动画缩放一起消掉 ⇒ **每档一个模板就够**, 不需要按卡位各出一套。

用法: python tools/pf_mult_band.py <dump目录> [输出目录]
产物: band_sheet.png (人工认档) + band_c<k>.png (簇代表)
"""
import glob
import os
import sys

import cv2
import numpy as np

FIRE = [(1180, 30, 100, 120), (1180, 206, 100, 120), (1180, 382, 100, 120)]
BW, BH = 48, 27            # 归一化后的窗口尺寸
# 窗口 = 文字宽/高 × 系数 (留出红底与边缘, 让"x1"这种窄档也带足够上下文)
WIN_X, WIN_Y = 1.9, 1.9
TH = 0.90


def flame_box(im, band):
    x0, y0, w, h = band
    sub = im[y0:y0 + h, x0:x0 + w]
    b, g, r = sub[:, :, 0].astype(int), sub[:, :, 1].astype(int), sub[:, :, 2].astype(int)
    mask = ((r - g > 60) & (r - b > 40) & (r > 90) & (g < 80) & (b < 90)).astype(np.uint8)
    n, _, st, _ = cv2.connectedComponentsWithStats(mask, 8)
    best = None
    for i in range(1, n):
        x, y, ww, hh, a = st[i]
        if a < 600 or ww < 24 or ww > 55 or hh < 18 or hh > 70:
            continue
        if best is None or a > best[4]:
            best = (x + x0, y + y0, ww, hh, a)
    return None if best is None else best[:4]


def band_crop(im, fbox):
    """以**白字 bbox** 为基准归一化: 窗口尺寸按字高定, 再把字高缩到恒定。
    这样卡位透视 (±13%)、入场动画缩放、以及档位文字宽度差异全都被消掉 —
    "x1" 会显得小、居中, "x4.5" 填得满, 但**字高一致**, 同档位跨卡位应该聚到一簇。"""
    fx, fy, fw, fh = fbox
    x0, y0 = max(0, fx), max(0, fy)
    x1, y1 = min(im.shape[1], fx + fw), min(im.shape[0], fy + fh)
    if x1 - x0 < 8 or y1 - y0 < 8:
        return None
    sub = im[y0:y1, x0:x1]
    b, g, r = sub[:, :, 0].astype(int), sub[:, :, 1].astype(int), sub[:, :, 2].astype(int)
    white = ((r > 150) & (g > 150) & (b > 150)).astype(np.uint8)
    ys, xs = np.nonzero(white)
    if len(xs) < 30:                     # 字太小/没字 (动画帧糊掉) → 丢弃
        return None
    tx0, tx1, ty0, ty1 = xs.min(), xs.max(), ys.min(), ys.max()
    th_ = ty1 - ty0 + 1
    tw_ = tx1 - tx0 + 1
    if th_ < 8 or tw_ < 8:               # 不成比例 (大概是把边框认成字了)
        return None
    cx, cy = x0 + (tx0 + tx1) / 2.0, y0 + (ty0 + ty1) / 2.0
    win_w, win_h = tw_ * WIN_X, th_ * WIN_Y
    c = cv2.getRectSubPix(im, (max(4, int(win_w)), max(4, int(win_h))), (cx, cy))
    return cv2.resize(c, (BW, BH), interpolation=cv2.INTER_AREA)


def ncc(a, b):
    r = cv2.matchTemplate(a[:, :, 0].astype(np.float32), b[:, :, 0].astype(np.float32),
                          cv2.TM_CCOEFF_NORMED)
    return float(r[0, 0])


def main():
    d = sys.argv[1] if len(sys.argv) > 1 else "debug/pf/dump_pull6/pf"
    out = sys.argv[2] if len(sys.argv) > 2 else "debug/tmp/mult_band"
    os.makedirs(out, exist_ok=True)
    files = sorted(glob.glob(os.path.join(d, "*_full_*.png")))
    samples = []          # (band_img, 帧名, 卡位)
    for p in files:
        im = cv2.imread(p)
        if im is None or im.shape[0] != 576:
            continue
        for pos, band in enumerate(FIRE):
            fb = flame_box(im, band)
            if fb is None:
                continue
            c = band_crop(im, fb)
            if c is None:
                continue
            samples.append((c, os.path.basename(p), pos))
    print("数字带样本", len(samples))

    clusters = []
    for c, f, pos in samples:
        for cl in clusters:
            if ncc(cl["rep"], c) >= TH:
                cl["n"] += 1
                cl["pos"].add(pos)
                break
        else:
            clusters.append({"rep": c, "n": 1, "pos": {pos}, "name": f})
    clusters.sort(key=lambda t: -t["n"])
    for k, cl in enumerate(clusters):
        cv2.imwrite(os.path.join(out, "band_c%d.png" % k), cl["rep"])
        print("band_c%d n=%-3d 卡位=%s  源=%s" % (k, cl["n"], sorted(cl["pos"]), cl["name"]))

    # contact sheet: 每簇放大 4x 并排
    tiles = []
    for k, cl in enumerate(clusters):
        img = cv2.resize(cl["rep"], (BW * 4, BH * 4), interpolation=cv2.INTER_NEAREST)
        img = cv2.copyMakeBorder(img, 2, 2, 2, 2, cv2.BORDER_CONSTANT, value=(90, 90, 90))
        label = np.full((img.shape[0], 150, 3), 30, np.uint8)
        cv2.putText(label, "c%d n=%d" % (k, cl["n"]), (4, 30), 0, 0.6, (0, 255, 255), 2)
        cv2.putText(label, "pos%s" % "".join(str(p + 1) for p in sorted(cl["pos"])),
                    (4, 58), 0, 0.5, (160, 220, 255), 1)
        tiles.append(np.hstack([label, img]))
    if tiles:
        cv2.imwrite(os.path.join(out, "band_sheet.png"), np.vstack(tiles))
    print("产物在", out)


if __name__ == "__main__":
    main()
