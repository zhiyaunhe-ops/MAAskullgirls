"""角色**名条**模板匹配探针: 判别余量 / 跨帧稳定性 (纯离线, 不连设备)。

2026-10-13 实测结论 (用户要求"做读取角色名称模板的测试"):
  · 角色名条 (卡面下方彩色条 + 副名小字) **OCR 已经读得很好**: 名条紧裁后 rec-only
    在被模态压暗的帧上也是 6/6 (CEREBELLA/BEOWULF/MS. FORTUNE...)。
  · 模板匹配同样可行: 同帧 6x6 判别自得分 1.000, 正确项与次高最小余量 0.335;
    同版式跨帧 5/6 (真命中最低 0.701), 闭集外控制 (BLACK DAHLIA) 最高仅 0.311
    -> 阈值 0.6 处有 0.39 的干净间隔。**变体卡不同也能命中** (BLITZ&GLAMOUR vs
    HARLEQUIN 都是 CEREBELLA, 0.966/0.984) —— 因为它读的是角色本名, 与变体无关。
  · 所以对"读角色名"这件事模板匹配**不比 OCR 准**, 它真正多出来的是**闭集判定**:
    能把"不在库里的角色"判成低分, 而 OCR 无论如何都会硬读出一个串。
  · 两个必须避开的坑 (本探针已按修正后写法实现):
    ① **不要用内容紧裁做尺度归一** —— 名条两侧《》装饰会把紧裁框撑高, 两帧装饰有无
       不同 -> 归一后文字尺度差 36% -> 相关度直接掉到 0。改用**固定几何窗**。
    ② **不要用固定灰度阈值** —— 模态弹窗会把整屏压暗 (stuck_1013 的白字灰度 <150),
       位图全空 -> 相关度恒为 0。改用**灰度归一化相关** (去均值+归一, 对整体明暗不敏感)。
  · 模板必须**按 UI 版式**各建一套: 防守队编辑器与战斗编队编辑器的卡片尺寸不同,
    同一角色的名条框也不同 (_def_now vs _arena2 实测)。

用法:
  # 同帧判别矩阵 (一帧内建模板再互相判别, 看余量)
  python tools/_probe_name_template.py matrix <图> [--band 0,650,1280,682]
  # 同版式跨帧判别 (模板图 -> 候选图), 并排打印 rec-only OCR 作对照
  python tools/_probe_name_template.py cross <模板图> <候选图> [--band ...]
"""
import argparse
import sys

import cv2
import numpy as np

sys.path.insert(0, "D:/Downloads/MAA_again/tools")
from _probe_ocr_offline import DetLocator, RecOCR, load  # noqa: E402

WIN = (120, 22)          # 固定几何窗 (宽, 高): 只包名条本体, 不含整卡
DEFAULT_BAND = (0, 650, 1280, 682)


def parse_band(s):
    x0, y0, x1, y1 = (int(v) for v in s.split(","))
    return (x0, y0, x1, y1)


def gray_win(img, cx, cy):
    c = img[cy - WIN[1] // 2:cy + WIN[1] // 2, cx - WIN[0] // 2:cx + WIN[0] // 2]
    if c.shape[:2] != (WIN[1], WIN[0]):
        return None
    return cv2.cvtColor(c, cv2.COLOR_BGR2GRAY)


def plates(img, det, ocr, band):
    """det 找名条行框 -> 以框心开固定几何窗 -> 灰度 (不做二值, 见模块头坑②)。"""
    out = []
    for (bx0, by0, bx1, by1), txt in det.read(ocr, img, band):
        g = gray_win(img, (bx0 + bx1) // 2, (by0 + by1) // 2)
        if g is not None:
            out.append((txt, g))
    return out


def ncc(t, c):
    """灰度归一化相关 (对整体明暗/增益不敏感)。"""
    t = cv2.resize(t, WIN[::-1]).astype(np.float32)
    c = cv2.resize(c, WIN[::-1]).astype(np.float32)
    t, c = t - t.mean(), c - c.mean()
    d = np.sqrt((t * t).sum() * (c * c).sum())
    return float((t * c).sum() / d) if d else 0.0


def key(s):
    return s.replace(" ", "").upper()


def dedupe(items):
    out, seen = [], set()
    for txt, g in items:
        k = key(txt)
        if len(k) >= 4 and k not in seen:
            seen.add(k)
            out.append((k, g))
    return out


def main(argv):
    ap = argparse.ArgumentParser(description="名条模板匹配探针 (离线)")
    ap.add_argument("mode", choices=["matrix", "cross"])
    ap.add_argument("images", nargs="+")
    ap.add_argument("--band", default="0,650,1280,682")
    a = ap.parse_args(argv)
    band = parse_band(a.band)
    ocr, det = RecOCR(), DetLocator()

    src = dedupe(plates(load(a.images[0]), det, ocr, band))
    print(f"模板 ({a.images[0]}): " + ", ".join(k for k, _ in src))

    if a.mode == "matrix":
        print(f"\n=== 同帧判别矩阵 (灰度归一化相关, 窗 {WIN}) ===")
        print("            " + "".join(f"{k[:9]:>11s}" for k, _ in src))
        hit, marg = 0, []
        for i, (k, g) in enumerate(src):
            row = [ncc(t, g) for _, t in src]
            b = int(np.argmax(row))
            hit += b == i
            s = sorted(row, reverse=True)
            marg.append(s[0] - s[1])
            print(f"  {k:10s}" + "".join(f"{v:11.3f}" for v in row) +
                  ("  OK" if b == i else f"  MISS->{src[b][0]}"))
        print(f"  -> {hit}/{len(src)}; 正确-次高最小余量 {min(marg):.3f}")
        return 0

    dst = plates(load(a.images[1]), det, ocr, band)
    print(f"\n=== 跨帧判别 ({a.images[0]} -> {a.images[1]}) ===")
    ok = 0
    for txt, g in dst:
        sc = [(k, ncc(t, g)) for k, t in src]
        sc.sort(key=lambda kv: -kv[1])
        top, val = sc[0]
        verdict = "命中" if val >= 0.6 else "闭集外/低分"
        ok += val >= 0.6
        in_tpl = "  (模板里有同名)" if key(txt) in dict(src) else ""
        print(f"  {txt!r:16s} rec-only={key(txt):14s} 最好={top:14s}({val:.3f}) "
              f"次高={sc[1][0]}({sc[1][1]:.3f})  {verdict}{in_tpl}")
    print(f"\n  >=0.6 判命中: {ok}/{len(dst)}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
