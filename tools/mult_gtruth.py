"""离线对照: cv2 算 NCC 峰值, 与 Vision.findScored 的输出对齐。

用法: python tools/mult_gtruth.py <frame.png> <tpl.png> [roi x y w h]
输出: 模板/帧 ROI 的通道统计 + cv2 TM_CCOEFF_NORMED 峰值(全帧 & ROI) + 手工 NCC
"""
import sys
import numpy as np
import cv2


def load_blue(path):
    """按 Vision 口径取通道: 默认 blue = BGR 的第 0 通道; 灰度 = Rec601 加权"""
    im = cv2.imread(path, cv2.IMREAD_UNCHANGED)
    assert im is not None, path
    if im.ndim == 2:
        return im.astype(np.float64), None
    if im.shape[2] == 4:
        im = im[:, :, :3]
    return im[:, :, 0].astype(np.float64), im[:, :, :].astype(np.float64)


def naive_ncc(f, t, ox, oy):
    w = f[oy:oy + t.shape[0], ox:ox + t.shape[1]].astype(np.float64)
    fm = w.mean()
    tm = t.mean()
    num = ((w - fm) * (t - tm)).sum()
    den = np.sqrt(((w - fm) ** 2).sum() * ((t - tm) ** 2).sum())
    return float(num / den) if den > 1e-6 else 0.0


def main():
    fp = sys.argv[1] if len(sys.argv) > 1 else "debug/pf/dump_pull4/opp_002938_447_full_0001.png"
    tp = sys.argv[2] if len(sys.argv) > 2 else "phone/app/assets/templates/pf/mult_15.png"
    fb, _ = load_blue(fp)
    tb, _ = load_blue(tp)
    print("frame blue %dx%d  mean=%.2f sd=%.2f" % (fb.shape[1], fb.shape[0], fb.mean(), fb.std()))
    print("tpl   blue %dx%d  mean=%.2f sd=%.2f min=%d max=%d"
          % (tb.shape[1], tb.shape[0], tb.mean(), tb.std(), tb.min(), tb.max()))

    def peak(roi):
        x, y, w, h = roi
        sub = fb[y:y + h, x:x + w] if w else fb
        if sub.shape[0] < tb.shape[0] or sub.shape[1] < tb.shape[1]:
            print("roi too small", roi)
            return
        r = cv2.matchTemplate(sub.astype(np.float32), tb.astype(np.float32),
                              cv2.TM_CCOEFF_NORMED)
        _, mx, _, loc = cv2.minMaxLoc(r)
        gx, gy = x + loc[0], y + loc[1]
        print("roi[%d,%d,%d,%d] cv2 max=%.4f @(%d,%d)  naive@ peak=%.4f"
              % (x, y, w, h, mx, gx, gy, naive_ncc(fb, tb, gx, gy)))

    peak((0, 0, fb.shape[1], fb.shape[0]))
    if len(sys.argv) > 6:
        peak(tuple(int(v) for v in sys.argv[3:7]))
    else:
        peak((1180, 206, 100, 120))

    # 模板所在 ROI 的统计
    x, y = 1180, 206
    sub = fb[y:y + 120, x:x + 100]
    print("roi region mean=%.2f sd=%.2f" % (sub.mean(), sub.std()))


if __name__ == "__main__":
    main()
