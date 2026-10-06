package com.zhiyaunhe.sgmbot;

import android.graphics.Bitmap;

/**
 * 纯 Java NCC 模板匹配 — 与 autojs images.findImage / PC match.py 同口径:
 *   零均值归一化互相关 (对亮度偏移免疫, settle 半透明按钮实测必需), 阈值 MIN_SIM=0.72 起。
 * 帧先缩放到 WORK_H=576 再匹配 (与模板裁剪基准一致), 全部在 int[] 像素上跑。
 *
 * 性能预算: 1280x576 帧 + ≤80x80 模板 + 限 ROI ≈ 30-120ms (Pixel 级 SoC 实测口径),
 * 复用 autojs 版的小 ROI 策略 (三槽/标题区/居中卡区) 即可满足 350-800ms 轮询。
 */
public final class Vision {

    /** 模板基准高 (素材源 1280x576), 帧先缩放到此高度再匹配 */
    public static final int WORK_H = 576;

    /** 缩放帧到 WORK_H 高, 返回 ARGB 像素 + 尺寸 */
    public static Frame toWork(Bitmap b) {
        int h = WORK_H;
        int w = Math.round(b.getWidth() * (float) h / b.getHeight());
        Bitmap s = Bitmap.createScaledBitmap(b, w, h, true);
        int[] px = new int[w * h];
        s.getPixels(px, 0, w, 0, 0, w, h);
        if (s != b) s.recycle();
        return new Frame(px, w, h);
    }

    /** NCC 找模板: 返回命中左上角 (work 坐标), 未命中/越界返回 null */
    public static int[] find(Frame frame, Frame tpl, int[] roi, double th) {
        int rx = 0, ry = 0, rw = frame.w, rh = frame.h;
        if (roi != null && roi.length == 4) {
            rx = Math.max(0, roi[0]); ry = Math.max(0, roi[1]);
            rw = Math.min(frame.w - rx, roi[2]); rh = Math.min(frame.h - ry, roi[3]);
        }
        if (rw < tpl.w || rh < tpl.h) return null;
        double inv = 1.0 / (tpl.w * tpl.h);
        double tMean = 0;
        // ⚠️ v & 0xFF = ARGB 低字节 = **蓝通道**, 不是灰度! 别"纠正"成灰度 ——
        // 真机结算页实测 (tools/VisionProbe.java, 2026-10-07): REMATCH 橙按钮跨帧
        // 蓝通道 NCC 0.95+, 灰度只有 0.65~0.70 < 0.72 会漏识别; 反例两侧都干净。
        // 与 Python match.py 的 to_gray 不是一口径, 是**故意的**。
        for (int v : tpl.px) tMean += v & 0xFF;
        tMean *= inv;
        double tVar = 0;
        for (int v : tpl.px) { double d = (v & 0xFF) - tMean; tVar += d * d; }
        if (tVar < 1e-6) return null;

        for (int oy = ry; oy <= ry + rh - tpl.h; oy += 2) {       // 步距 2, 命中后细化
            for (int ox = rx; ox <= rx + rw - tpl.w; ox += 2) {
                double ncc = nccAt(frame.px, frame.w, ox, oy, tpl, tMean, Math.sqrt(tVar), inv);
                if (ncc >= th) {
                    return refine(frame, tpl, roi, th, ox, oy);
                }
            }
        }
        return null;
    }

    /** 步距 2 粗命中后, 在 ±2 邻域步距 1 精定位 */
    private static int[] refine(Frame f, Frame t, int[] roi, double th, int ox, int oy) {
        int bx = ox, by = oy; double bs = 0;
        double tMean = mean(t), tSd = sd(t);
        for (int y = Math.max(0, oy - 2); y <= oy + 2; y++)
            for (int x = Math.max(0, ox - 2); x <= ox + 2; x++) {
                double s = nccAt(f.px, f.w, x, y, t, tMean, tSd, 1.0 / (t.w * t.h));
                if (s > bs) { bs = s; bx = x; by = y; }
            }
        return bs >= th ? new int[]{bx, by} : null;
    }

    private static double nccAt(int[] f, int fw, int ox, int oy, Frame t,
                                double tMean, double tSd, double inv) {
        double fMean = 0;
        for (int y = 0; y < t.h; y++) {
            int row = (oy + y) * fw + ox;
            for (int x = 0; x < t.w; x++) fMean += f[row + x] & 0xFF;
        }
        fMean *= inv;
        double num = 0, fVar = 0;
        for (int y = 0; y < t.h; y++) {
            int row = (oy + y) * fw + ox;
            for (int x = 0; x < t.w; x++) {
                double d = (f[row + x] & 0xFF) - fMean;
                fVar += d * d;
                num += d * (((t.px[y * t.w + x] & 0xFF)) - tMean);
            }
        }
        double den = Math.sqrt(fVar) * tSd;
        return den < 1e-6 ? 0 : num / den;
    }

    static double mean(Frame t) {
        double m = 0;
        for (int v : t.px) m += v & 0xFF;
        return m / (t.w * t.h);
    }

    static double sd(Frame t) {
        double m = mean(t), v = 0;
        for (int v2 : t.px) { double d = (v2 & 0xFF) - m; v += d * d; }
        return Math.sqrt(v);
    }

    /** 框内采样均值 (色锚点/脑子亮度用), box=[x,y,w,h], 步距 4 内缩 4 — 同 autojs sampleMean */
    public interface Chan { double of(int argb); }

    public static double sampleMean(Frame f, int[] box, Chan chan) {
        double tot = 0; int n = 0;
        for (int y = 4; y < box[3] - 4; y += 4)
            for (int x = 4; x < box[2] - 4; x += 4) {
                tot += chan.of(f.px[(box[1] + y) * f.w + box[0] + x]); n++;
            }
        return n == 0 ? 0 : tot / n;
    }

    public static final class Frame {
        public final int[] px; public final int w, h;
        public Frame(int[] px, int w, int h) { this.px = px; this.w = w; this.h = h; }
    }
}
