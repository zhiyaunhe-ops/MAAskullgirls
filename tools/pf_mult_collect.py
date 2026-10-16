# -*- coding: utf-8 -*-
"""倍率徽章离线采集/聚类 — 补齐 mult_* 模板的离线半成品工具 (2026-10-16)。

输入: debug/pf/collect_remote/*.jpg (collect_remote_frames.py 落的 /screencap 帧,
      已是 work 1280x576) 或任意 work 基准帧目录。
流程:
  1) 用 pf_refresh_btn 模板筛出「选对手页」帧 (REFRESH 只在那页存在);
  2) 在三张对手卡的火框搜索区 (与 App Pf.FIRE_SEARCH 同坐标) 找红色连通域,
     以其中心裁 60x66 (与 mult_25.png 同版式);
  3) 按徽章下沿数字带聚类 (同倍率长得一模一样) — 数字带比整徽章区分度高
     (整徽章 NCC 下 x1.5/x2.5 只差一个字, 相似度虚高);
  4) 输出每簇代表帧拼图 (contact sheet) + 每簇样本目录, 人工 (或看图) 标档位后
     用 --emit 把指定簇导成 assets/templates/pf/mult_<v>.png。

用法:
  python tools/pf_mult_collect.py                     # 聚类 + 出拼图
  python tools/pf_mult_collect.py --emit 3:2.5 0:1    # 簇号:倍率值, 导出模板
"""
import argparse
import glob
import os

import cv2
import numpy as np

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TPL = os.path.join(ROOT, "phone", "app", "assets", "templates", "pf")
DEF_IN = os.path.join(ROOT, "debug", "pf", "collect_remote")
DEF_OUT = os.path.join(ROOT, "debug", "pf", "mult_clusters")

# 与 App Pf.FIRE_SEARCH 完全一致 (work [x,y,w,h])
# ⚠️ 2026-10-16 真机实测重标: 徽章锚在各卡右上角、**右缘锚定** (x≈1214-1258),
# 旧值 (R(1100,…) → x1008-1101) 整个搜不到 — 与 ROI_REFRESH 同一批右缘元素,
# 套 PC 居中换算全偏。这里改按**右列全高**搜索 (含 CONTINUE 按钮这种大块干扰,
# 用 w<=70 过滤掉), 反正后面还有聚类+人工核对兜底。
FIRE_SEARCH = [(1100, 0, 180, 576)]
BADGE_MAX_W = 70
# 选对手页 REFRESH 判据 ROI: 真机实测模板命中 (844,26) 尺寸 164x53 → 右缘锚定,
# 旧值 R(700,15,960,115)=[688,12,208,80] 连模板都放不下 (x 844+164=1008 > 896)。
ROI_REFRESH = (820, 0, 280, 110)
BADGE_W, BADGE_H = 60, 66          # 与 mult_25.png 同尺寸
# 数字带: 徽章下部 (x2.5 白字所在), 聚类只比这一条
BAND_Y0, BAND_Y1 = 0.50, 0.95


def is_red(bgr):
    """App Pf.isRed 的 numpy 版 (r>=150, r-g>=60, r-b>=60)。"""
    b = bgr[:, :, 0].astype(np.int16)
    g = bgr[:, :, 1].astype(np.int16)
    r = bgr[:, :, 2].astype(np.int16)
    return (r >= 150) & (r - g >= 60) & (r - b >= 60)


def gray(img):
    return cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)


def best_match(frame_gray, tpl, roi):
    x, y, w, h = roi
    H, W = frame_gray.shape
    w = min(w, W - x)
    h = min(h, H - y)
    if w < tpl.shape[1] or h < tpl.shape[0]:
        return -1.0
    crop = frame_gray[y:y + h, x:x + w]
    res = cv2.matchTemplate(crop, tpl, cv2.TM_CCOEFF_NORMED)
    return float(res.max())


def load_refresh_tpl():
    t = cv2.imread(os.path.join(TPL, "pf_refresh_btn.png"), cv2.IMREAD_GRAYSCALE)
    if t is None:
        raise SystemExit("找不到 pf_refresh_btn 模板")
    return t


def extract(in_dir):
    """返回 [(frame_path, card_idx, badge_bgr 60x66)]。"""
    refresh = load_refresh_tpl()
    out = []
    paths = sorted(glob.glob(os.path.join(in_dir, "*.jpg")) + glob.glob(os.path.join(in_dir, "*.png")))
    n_sel = 0
    for p in paths:
        img = cv2.imread(p)
        # 只吃横屏 work 帧 (App 自己在前台时 /screencap 会给竖屏窄帧)
        if img is None or img.shape[0] != 576 or img.shape[1] != 1280:
            continue
        g = gray(img)
        if best_match(g, refresh, ROI_REFRESH) < 0.60:
            continue
        n_sel += 1
        for ci, (rx, ry, rw, rh) in enumerate(FIRE_SEARCH):
            sub = img[ry:ry + rh, rx:rx + rw]
            mask = is_red(sub).astype(np.uint8)
            n, lab, stats, _ = cv2.connectedComponentsWithStats(mask, connectivity=8)
            cand = []
            for i in range(1, n):
                area = int(stats[i, cv2.CC_STAT_AREA])
                w0 = int(stats[i, cv2.CC_STAT_WIDTH])
                # 徽章火苗 ~44 宽; CONTINUE 等大块右缘元素 w>70 直接排除
                if area >= 150 and w0 <= BADGE_MAX_W:
                    cand.append(i)
            cand.sort(key=lambda i: -int(stats[i, cv2.CC_STAT_AREA]))
            for rank, i in enumerate(cand[:3]):    # 每帧最多取 3 个候选 (3 张卡)
                x0 = int(stats[i, cv2.CC_STAT_LEFT]) + rx
                y0 = int(stats[i, cv2.CC_STAT_TOP]) + ry
                w0 = int(stats[i, cv2.CC_STAT_WIDTH])
                h0 = int(stats[i, cv2.CC_STAT_HEIGHT])
                cx, cy = x0 + w0 // 2, y0 + h0 // 2
                # 60x66 居中裁 (与 mult_25 版式一致), 越界补黑
                bx0, by0 = cx - BADGE_W // 2, cy - BADGE_H // 2
                badge = np.zeros((BADGE_H, BADGE_W, 3), np.uint8)
                sx0, sy0 = max(0, bx0), max(0, by0)
                sx1, sy1 = min(img.shape[1], bx0 + BADGE_W), min(img.shape[0], by0 + BADGE_H)
                if sx1 > sx0 and sy1 > sy0:
                    badge[sy0 - by0:sy1 - by0, sx0 - bx0:sx1 - bx0] = img[sy0:sy1, sx0:sx1]
                out.append((os.path.basename(p), ci, badge))
    print("帧 %d 张, 选对手页 %d 张, 提取徽章 %d 个" % (len(paths), n_sel, len(out)))
    return out


def band_feat(badge):
    """数字带灰度特征 (聚类用): 徽章下部 50%~95% 高。"""
    g = gray(badge).astype(np.float32)
    b = g[int(BADGE_H * BAND_Y0):int(BADGE_H * BAND_Y1), :]
    b = b - b.mean()
    n = np.linalg.norm(b)
    return b / n if n > 0 else b


def cluster(badges, th=0.86):
    """按数字带 NCC 贪心聚类。返回 [(代表, [样本...])], 按样本数降序。"""
    clusters = []   # [feat, [badge,...]]
    for _, _, b in badges:
        f = band_feat(b)
        hit = None
        for c in clusters:
            s = float((c[0] * f).sum())
            if s >= th:
                hit = c
                break
        if hit is None:
            clusters.append([f, [b]])
        else:
            hit[1].append(b)
    clusters.sort(key=lambda c: -len(c[1]))
    return clusters


def contact(clusters, path, cols=6):
    """每簇 1 代表 + 计数, 拼一张总览图。"""
    rows = (len(clusters) + cols - 1) // cols
    cell_w, cell_h, cap = BADGE_W, BADGE_H + 18, 12
    sheet = np.full((rows * (cell_h + cap) + 4, cols * (cell_w + 6) + 4, 3), 24, np.uint8)
    for i, c in enumerate(clusters):
        r, k = divmod(i, cols)
        x = 4 + k * (cell_w + 6)
        y = 4 + r * (cell_h + cap)
        sheet[y:y + BADGE_H, x:x + BADGE_W] = c[1][0]
        cv2.putText(sheet, "#%d n=%d" % (i, len(c[1])), (x, y + BADGE_H + 13),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.38, (200, 220, 255), 1, cv2.LINE_AA)
    cv2.imwrite(path, sheet)
    print("拼图 ->", path)


def emit(clusters, pairs):
    """--emit 簇号:倍率值 → 覆盖 assets mult_<值>.png (值 1/1.5/2/2.5/3/4 → 1/15/2/25/3/4)。"""
    name = {"1": "1", "1.5": "15", "2": "2", "2.5": "25", "3": "3",
            "3.5": "35", "4": "4", "5": "5"}   # 3.5 真机实见 (PC 候选表没有)
    for pair in pairs:
        idx, val = pair.split(":", 1)
        idx = int(idx)
        if idx >= len(clusters):
            print("簇 %d 不存在 (共 %d 簇)" % (idx, len(clusters)))
            continue
        fn = "mult_%s.png" % name[val]
        dst = os.path.join(TPL, fn)
        cv2.imwrite(dst, clusters[idx][1][0])
        print("簇 #%d (%d 样本) → %s" % (idx, len(clusters[idx][1]), dst))
        # 同步手机外部模板 (adb push 由调用方做; 这里只落 repo)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--in", dest="src", default=DEF_IN)
    ap.add_argument("--out", dest="out", default=DEF_OUT)
    ap.add_argument("--th", type=float, default=0.86)
    ap.add_argument("--emit", nargs="*", default=None, help="簇号:倍率值 ...")
    a = ap.parse_args()

    badges = extract(a.src)
    if not badges:
        return
    clusters = cluster(badges, a.th)
    os.makedirs(a.out, exist_ok=True)
    for i, c in enumerate(clusters):
        d = os.path.join(a.out, "c%02d" % i)
        os.makedirs(d, exist_ok=True)
        for j, b in enumerate(c[1]):
            cv2.imwrite(os.path.join(d, "%03d.png" % j), b)
    print("共 %d 簇 (阈值 %.2f)" % (len(clusters), a.th))
    contact(clusters, os.path.join(a.out, "contact.png"))
    if a.emit:
        emit(clusters, a.emit)


if __name__ == "__main__":
    main()
