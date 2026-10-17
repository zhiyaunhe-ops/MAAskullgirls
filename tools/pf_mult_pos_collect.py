"""按**卡位**采集倍率徽章模板 (2026-10-17)。

背景: 选对手页是**透视扇形** —— 卡 2 正面 1.0，卡 1/卡 3 的徽章小约 13%。
跨卡位套中心位模板只有 0.64，而按卡位裁的模板跨帧 0.9748 ⇒ 必须按卡位分别出模板。

用法:
  python tools/pf_mult_pos_collect.py <dump目录> [输出目录]
  产物: debug/tmp/mult_pos/c1_sheet.png / c2_sheet.png / c3_sheet.png  (人工认档位用)
        debug/tmp/mult_pos/cluster_c<pos>_<k>.png                        (簇代表)
"""
import glob
import os
import sys

import cv2
import numpy as np

# 三个带区 (work 坐标, 与 Pf.FIRE_SEARCH 一致)
FIRE = [(1180, 30, 100, 120), (1180, 206, 100, 120), (1180, 382, 100, 120)]
CW, CH = 60, 66          # 与中心位模板同几何
TH_CLUSTER = 0.90        # 同档位同一卡位的跨帧 NCC (实测 0.97+, 不同档位 0.4~0.7)


def red_blob(im, band):
    """带区内找红色徽章块, 返回 (x, y, w, h, area) 或 None"""
    x0, y0, w, h = band
    sub = im[y0:y0 + h, x0:x0 + w]
    b, g, r = sub[:, :, 0].astype(int), sub[:, :, 1].astype(int), sub[:, :, 2].astype(int)
    # 徽章是**纯红** (实测 BGR≈(8,8,209), 绿通道近 0); 右上 CONTINUE 是**橙色**
    # (绿通道 ~100), 只判 r-g/b 会把它当成徽章 —— 卡1 的带区正好压在它上面。
    mask = ((r - g > 60) & (r - b > 40) & (r > 90) & (g < 80) & (b < 90)).astype(np.uint8)
    n, _, st, _ = cv2.connectedComponentsWithStats(mask, 8)
    best = None
    for i in range(1, n):
        x, y, ww, hh, a = st[i]
        # 徽章宽 39~45 (中心位 45, 侧位 39); CONTINUE 被带区裁掉左边后仍有 63 宽
        # ⇒ 宽度上限 55 就能把它们分开 (只判颜色分不开: CONTINUE 的暗红描边也够红)。
        if a < 600 or ww < 24 or ww > 55 or hh < 18 or hh > 70:
            continue
        if best is None or a > best[4]:
            best = (x + x0, y + y0, ww, hh, a)
    return best


def crop_badge(im, blob):
    """以红块左上角为锚, 裁固定 60x66 (偏移取中心位模板的实测几何)"""
    x, y = blob[0] - 8, blob[1] - 6
    if x < 0 or y < 0 or x + CW > im.shape[1] or y + CH > im.shape[0]:
        return None
    return im[y:y + CH, x:x + CW]


def ncc(a, b):
    ra = cv2.matchTemplate(a[:, :, 0].astype(np.float32),
                           b[:, :, 0].astype(np.float32), cv2.TM_CCOEFF_NORMED)
    return float(ra[0, 0])


def main():
    d = sys.argv[1] if len(sys.argv) > 1 else "debug/pf/dump_pull6/pf"
    out = sys.argv[2] if len(sys.argv) > 2 else "debug/tmp/mult_pos"
    os.makedirs(out, exist_ok=True)
    files = sorted(glob.glob(os.path.join(d, "*_full_*.png")))
    print("帧数", len(files))

    # clusters[pos] = [(代表图, [成员]), ...]
    clusters = {0: [], 1: [], 2: []}
    stats = {0: 0, 1: 0, 2: 0}
    for p in files:
        im = cv2.imread(p)
        if im is None or im.shape[0] != 576 or im.shape[1] != 1280:
            continue
        for pos, band in enumerate(FIRE):
            blob = red_blob(im, band)
            if blob is None:
                continue
            c = crop_badge(im, blob)
            if c is None:
                continue
            stats[pos] += 1
            hit = None
            for rep, members in clusters[pos]:
                if ncc(rep, c) >= TH_CLUSTER:
                    hit = members
                    break
            if hit is None:
                clusters[pos].append((c, [os.path.basename(p)]))
            else:
                hit.append(os.path.basename(p))

    for pos in (0, 1, 2):
        cl = clusters[pos]
        cl.sort(key=lambda t: -len(t[1]))
        print("卡%d: 徽章样本 %d, 簇 %d  ->  %s" % (
            pos + 1, stats[pos], len(cl), [len(m) for _, m in cl]))
        for k, (rep, members) in enumerate(cl):
            cv2.imwrite(os.path.join(out, "cluster_c%d_%d.png" % (pos + 1, k)), rep)

        if not cl:
            continue
        # contact sheet: 每簇一行, 最多 6 个成员
        tiles = []
        for k, (rep, members) in enumerate(cl):
            row = []
            for f in members[:6]:
                im = cv2.imread(os.path.join(d, f))
                blob = red_blob(im, FIRE[pos])
                c = crop_badge(im, blob) if blob else None
                row.append(c if c is not None else np.zeros((CH, CW, 3), np.uint8))
            while len(row) < 6:
                row.append(np.zeros((CH, CW, 3), np.uint8))
            strip = np.hstack([cv2.copyMakeBorder(t, 1, 1, 1, 1, cv2.BORDER_CONSTANT,
                                                  value=(90, 90, 90)) for t in row])
            label = np.full((CH + 2, 90, 3), 30, np.uint8)
            cv2.putText(label, "c%d-%d n=%d" % (pos + 1, k, len(members)), (4, 36),
                        0, 0.55, (0, 255, 255), 2)
            tiles.append(np.hstack([label, strip]))
        sheet = np.vstack(tiles)
        sheet = cv2.resize(sheet, (sheet.shape[1] * 2, sheet.shape[0] * 2),
                           interpolation=cv2.INTER_NEAREST)
        cv2.imwrite(os.path.join(out, "c%d_sheet.png" % (pos + 1)), sheet)
    print("产物在", out)


if __name__ == "__main__":
    main()
