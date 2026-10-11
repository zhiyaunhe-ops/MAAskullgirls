package com.zhiyaunhe.sgmbot;

import android.graphics.Bitmap;

import org.json.JSONObject;

/**
 * 纯 Java NCC 模板匹配 — 与 autojs images.findImage / PC match.py 同为
 * 零均值归一化互相关 (对亮度偏移免疫, settle 半透明按钮实测必需)。
 * 帧先缩放到 WORK_H 高再匹配 (与模板裁剪基准一致), 全部在 int[] 像素上跑。
 *
 * ⚠️ 通道口径是**配置项** (matcher.chan), 默认 blue:
 *    ARGB int 的低字节 = 蓝通道。真机结算页实测 (tools/VisionProbe.java, 2026-10-07):
 *    REMATCH 橙按钮跨帧蓝通道 0.95+, 而灰度口径只有 0.65~0.70 < 0.72 会漏识别。
 *    所以默认不是灰度 —— 别按 match.py "纠正"成 gray, 除非重新标定过阈值。
 *
 * 性能预算: 1280x576 帧 + ≤80x80 模板 + 限 ROI ≈ 30-120ms;
 * matcher.step (粗搜步距) / matcher.refine (精定位邻域) 可配, 卡就调大 step。
 */
public final class Vision {

    /** 模板基准高 (素材源 1280x576), 帧先缩放到此高度再匹配 */
    public static final int WORK_H = 576;

    /* ---- 可配置口径 (每轮由 Config 装载) ---- */
    private static volatile int SHIFT = 0;        // blue=0 green=8 red=16
    private static volatile boolean GRAY = false;
    private static volatile int EPOCH = 0;        // 口径变更 → Frame 缓存失效
    private static volatile int STEP = 2;
    private static volatile int REFINE = 2;

    /* ---- 金字塔粗搜 ----
     * 目的: 无 ROI 的全屏搜在纯 Java 逐点 NCC 下不可行 —— 离线实测 (2026-10-11)
     *      srv_ok.png (420x140) 全搜 1280x576 **32.7 秒**、srv_retry (138x46) 5.7 秒。
     *      JS 版同样全屏搜却毫秒级, 因为它走 native OpenCV; 移植到纯 Java 后
     *      照抄"全屏搜"参数 = 每次调用卡几十秒 ⇒ 表现就是"弹窗逻辑等于没做"。
     * 做法: 先降采样粗搜定位候选, 再回原分辨率精修取分 (分数与全搜一致, 只省时间)。
     *      ⚠️ 降采样会掉分 (srv_ok 0.25x 仅保 0.658), 所以粗搜阈值必须放宽,
     *         最终判定仍用**原分辨率**分数 —— 只可能漏检, 不可能误检。
     */
    private static volatile int PYR_LEVEL = 2;          // 0=关; 1=0.5x; 2=0.25x
    private static volatile double PYR_COST = 2.0e8;    // 成本(位置数x模板面积)超过它才启用
    private static volatile double PYR_RELAX = 0.30;    // 粗搜阈值 = th - relax (下限 PYR_FLOOR)
    private static volatile double PYR_FLOOR = 0.50;    // 粗搜阈值下限
    private static volatile int PYR_TOP = 16;           // 保留候选数

    /** 按 config.matcher 装载: chan / step / refine / pyramid */
    public static void configure(JSONObject cfg) {
        JSONObject m = cfg == null ? null : cfg.optJSONObject("matcher");
        String chan = m == null ? "blue" : m.optString("chan", "blue");
        GRAY = "gray".equals(chan) || "luma".equals(chan) || "gray601".equals(chan);
        if (GRAY) SHIFT = 0;
        else if ("green".equals(chan) || "g".equals(chan)) SHIFT = 8;
        else if ("red".equals(chan) || "r".equals(chan)) SHIFT = 16;
        else SHIFT = 0;                            // blue (默认)
        STEP = m == null ? 2 : clamp(m.optInt("step", 2), 1, 4);
        REFINE = m == null ? 2 : clamp(m.optInt("refine", 2), 0, 4);
        JSONObject p = m == null ? null : m.optJSONObject("pyramid");
        PYR_LEVEL = p == null ? 2 : clamp(p.optInt("level", 2), 0, 2);
        PYR_COST = p == null ? 2.0e8 : p.optDouble("cost", 2.0e8);
        PYR_RELAX = p == null ? 0.30 : p.optDouble("relax", 0.30);
        PYR_FLOOR = p == null ? 0.50 : p.optDouble("floor", 0.50);
        PYR_TOP = p == null ? 16 : Math.max(1, p.optInt("top", 16));
        EPOCH++;
    }

    /** 当前生效口径 (状态页/日志显示用) */
    public static String modeText() {
        return (GRAY ? "gray" : SHIFT == 8 ? "green" : SHIFT == 16 ? "red" : "blue")
                + "/step" + STEP + "/refine" + REFINE
                + (PYR_LEVEL > 0 ? "/pyr" + (1 << PYR_LEVEL) + "x" : "/pyr-off");
    }

    private static int clamp(int v, int lo, int hi) { return v < lo ? lo : v > hi ? hi : v; }

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
        double[] r = findScored(frame, tpl, roi, th);
        return r != null && r[2] >= th ? new int[]{(int) r[0], (int) r[1]} : null;
    }

    /**
     * 同 find, 但**一定带回搜索过程中的最高分** —— 判定图要看"差多少没过阈值"
     * (0.69 卡住和 0.31 毫无相关性是两回事, 只有分数能区分)。
     * @return {x, y, best}; 未命中时 best < th (越界/尺寸不足返回 null)
     */
    public static double[] findScored(Frame frame, Frame tpl, int[] roi, double th) {
        int rx = 0, ry = 0, rw = frame.w, rh = frame.h;
        if (roi != null && roi.length == 4) {
            rx = Math.max(0, roi[0]); ry = Math.max(0, roi[1]);
            rw = Math.min(frame.w - rx, roi[2]); rh = Math.min(frame.h - ry, roi[3]);
        }
        if (rw < tpl.w || rh < tpl.h) return null;

        /* 成本够高才走金字塔 —— 有 ROI 的小图全搜本来就 100~300ms, 不必绕一圈 */
        if (PYR_LEVEL > 0) {
            long nx = (rw - tpl.w) / STEP + 1, ny = (rh - tpl.h) / STEP + 1;
            if (nx * ny * (double) tpl.w * tpl.h > PYR_COST) {
                double[] r = pyrSearch(frame, tpl, rx, ry, rw, rh, th);
                if (r != null) return r;            // null = 模板太小不适用 → 回退全搜
            }
        }
        return fullSearch(frame, tpl, rx, ry, rw, rh, th);
    }

    /** 金字塔粗搜 → 原分辨率精修。返回 null 表示"不适用"(模板降采样后太小), 调用方回退全搜。 */
    private static double[] pyrSearch(Frame frame, Frame tpl, int rx, int ry, int rw, int rh,
                                      double th) {
        double s = 1.0 / (1 << PYR_LEVEL);                    // 0.25
        Frame t2 = tpl.scale(s);
        if (t2.w < 4 || t2.h < 4) return null;
        Frame f2 = frame.scaleXY(s, s);                       // 整帧降采样 (面积平均)
        int rx2 = (int) Math.round(rx * s), ry2 = (int) Math.round(ry * s);
        int rw2 = Math.max(0, (int) Math.round(rw * s)), rh2 = Math.max(0, (int) Math.round(rh * s));
        rw2 = Math.min(f2.w - rx2, rw2); rh2 = Math.min(f2.h - ry2, rh2);
        if (rw2 < t2.w || rh2 < t2.h) return null;            // 降采样后 ROI 装不下 → 不适用

        double inv2 = 1.0 / (t2.w * t2.h);
        double m2 = 0;
        for (int i = 0; i < t2.px.length; i++) m2 += t2.val(i);
        m2 *= inv2;
        double v2 = 0;
        for (int i = 0; i < t2.px.length; i++) { double d = t2.val(i) - m2; v2 += d * d; }
        if (v2 < 1e-6) return null;
        double sd2 = Math.sqrt(v2);

        /* 粗搜: 不放宽到 th 以上就提前退出, 要收齐候选; 阈值放宽 (降采样必然掉分) */
        double th2 = Math.max(PYR_FLOOR, th - PYR_RELAX);
        java.util.List<double[]> cands = new java.util.ArrayList<>();
        double best2 = -2, bx2 = rx2, by2 = ry2;
        for (int oy = ry2; oy <= ry2 + rh2 - t2.h; oy += STEP)
            for (int ox = rx2; ox <= rx2 + rw2 - t2.w; ox += STEP) {
                double ncc = nccAt(f2, ox, oy, t2, m2, sd2, inv2);
                if (ncc > best2) { best2 = ncc; bx2 = ox; by2 = oy; }
                if (ncc >= th2) cands.add(new double[]{ox, oy, ncc});
            }

        /* 候选按分数降序, 只精修前 PYR_TOP 个 */
        cands.sort((a, b) -> Double.compare(b[2], a[2]));
        double tm = mean(tpl), tsd = sd(tpl), inv = 1.0 / (tpl.w * tpl.h);
        int rad = Math.max(REFINE, (int) Math.ceil(1.0 / s) + 1);   // 粗搜定位误差 ±1/s 像素
        double best = -2; int bx = rx, by = ry;
        int n = Math.min(cands.size(), PYR_TOP);
        for (int i = 0; i < n; i++) {
            double[] c = cands.get(i);
            int ox = clamp(rx + (int) Math.round(c[0] / s), rx, rx + rw - tpl.w);
            int oy = clamp(ry + (int) Math.round(c[1] / s), ry, ry + rh - tpl.h);
            double[] r = refineBest(frame, tpl, ox, oy, tm, tsd, inv, rad);
            if (r != null && r[2] > best) { best = r[2]; bx = (int) r[0]; by = (int) r[1]; }
        }
        if (n == 0) {                                          // 无候选: 带回粗搜峰值 (判定图看"差多少")
            bx = rx + (int) Math.round(bx2 / s);
            by = ry + (int) Math.round(by2 / s);
            best = best2;
        }
        return new double[]{bx, by, best};
    }

    /** 原分辨率全搜 (STEP 粗搜 + 峰值精定位) */
    private static double[] fullSearch(Frame frame, Frame tpl, int rx, int ry, int rw, int rh,
                                       double th) {
        double inv = 1.0 / (tpl.w * tpl.h);
        double tMean = 0;
        for (int i = 0; i < tpl.px.length; i++) tMean += tpl.val(i);
        tMean *= inv;
        double tVar = 0;
        for (int i = 0; i < tpl.px.length; i++) {
            double d = tpl.val(i) - tMean;
            tVar += d * d;
        }
        if (tVar < 1e-6) return null;
        double tSd = Math.sqrt(tVar);

        double best = -2, bx = rx, by = ry;
        for (int oy = ry; oy <= ry + rh - tpl.h; oy += STEP) {   // 步距 STEP, 命中后细化
            for (int ox = rx; ox <= rx + rw - tpl.w; ox += STEP) {
                double ncc = nccAt(frame, ox, oy, tpl, tMean, tSd, inv);
                if (ncc > best) { best = ncc; bx = ox; by = oy; }
                if (ncc >= th) {
                    int[] rf = refine(frame, tpl, th, ox, oy);
                    if (rf != null) {
                        double s = nccAt(frame, rf[0], rf[1], tpl, tMean, tSd, inv);
                        return new double[]{rf[0], rf[1], Math.max(s, ncc)};
                    }
                    return new double[]{ox, oy, ncc};
                }
            }
        }
        /* ⚠️ 未过阈值也要对峰值做一次精定位 —— 粗搜步距 STEP 与真实峰值可能差 1~2 像素。
           离线实测 (2026-10-11): srv_retry.png 原样贴进帧, 1x 全搜只有 0.642 < 0.75,
           因为真位置落在奇数格, 而旧实现**只在 ncc>=th 时**才精定位 ⇒ 这种高频模板
           永远进不了阈值门 (死锁: 差一点 → 不精修 → 够不着阈值)。精定位后回到 1.000。 */
        double[] rf = refineBest(frame, tpl, (int) bx, (int) by, tMean, tSd, inv, REFINE);
        if (rf != null && rf[2] > best) { best = rf[2]; bx = (int) rf[0]; by = (int) rf[1]; }
        return new double[]{bx, by, best};      // 未过阈值: 把峰值带回去
    }

    /** 单点 NCC 分数 (穷举峰值用 — 离线探针与状态页共用同一份口径) */
    public static double scoreAt(Frame f, Frame t, int ox, int oy) {
        if (ox < 0 || oy < 0 || ox + t.w > f.w || oy + t.h > f.h) return -2;
        double inv = 1.0 / (t.w * t.h);
        double tm = mean(t), tsd = sd(t);
        if (tsd < 1e-6) return 0;
        return nccAt(f, ox, oy, t, tm, tsd, inv);
    }

    /** 步距 STEP 粗命中后, 在 ±REFINE 邻域步距 1 精定位 (过阈值才返回) */
    private static int[] refine(Frame f, Frame t, double th, int ox, int oy) {
        double[] b = refineBest(f, t, ox, oy, mean(t), sd(t), 1.0 / (t.w * t.h), REFINE);
        return b != null && b[2] >= th ? new int[]{(int) b[0], (int) b[1]} : null;
    }

    /**
     * 精定位 (不过阈值也返回最高分): 在 (ox,oy) ±REFINE 邻域步距 1 找 NCC 峰值。
     *
     * ⚠️ 必须 clamp 到帧内 —— 旧版 refine 只 clamp 了下界 (`Math.max(0, ...)`),
     *    oy 是合法粗搜位置时 oy+REFINE+t.h 仍可能越出帧底 ⇒ nccAt 读到数组外
     *    ⇒ ArrayIndexOutOfBoundsException 冒到 BotService 的 catch, **整个结算循环终止**。
     *
     * @return {x, y, score}; 帧比模板小(无合法位置)返回 null
     */
    private static double[] refineBest(Frame f, Frame t, int ox, int oy,
                                       double tMean, double tSd, double inv, int rad) {
        if (f.w < t.w || f.h < t.h) return null;
        int x0 = Math.max(0, ox - rad), x1 = Math.min(f.w - t.w, ox + rad);
        int y0 = Math.max(0, oy - rad), y1 = Math.min(f.h - t.h, oy + rad);
        if (x1 < x0 || y1 < y0) return null;
        double bs = -2; int bx = ox, by = oy;
        for (int y = y0; y <= y1; y++)
            for (int x = x0; x <= x1; x++) {
                double s = nccAt(f, x, y, t, tMean, tSd, inv);
                if (s > bs) { bs = s; bx = x; by = y; }
            }
        return new double[]{bx, by, bs};
    }

    private static double nccAt(Frame f, int ox, int oy, Frame t,
                                double tMean, double tSd, double inv) {
        double fMean = 0;
        for (int y = 0; y < t.h; y++) {
            int row = (oy + y) * f.w + ox;
            for (int x = 0; x < t.w; x++) fMean += f.val(row + x);
        }
        fMean *= inv;
        double num = 0, fVar = 0;
        for (int y = 0; y < t.h; y++) {
            int row = (oy + y) * f.w + ox;
            int trow = y * t.w;
            for (int x = 0; x < t.w; x++) {
                double d = f.val(row + x) - fMean;
                fVar += d * d;
                num += d * (t.val(trow + x) - tMean);
            }
        }
        double den = Math.sqrt(fVar) * tSd;
        return den < 1e-6 ? 0 : num / den;
    }

    static double mean(Frame t) {
        double m = 0;
        for (int i = 0; i < t.px.length; i++) m += t.val(i);
        return m / (t.w * t.h);
    }

    static double sd(Frame t) {
        double m = mean(t), v = 0;
        for (int i = 0; i < t.px.length; i++) { double d = t.val(i) - m; v += d * d; }
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

    /** 帧/模板像素容器; ch 是按当前口径缓存的通道值 (口径变了自动重算) */
    public static final class Frame {
        public final int[] px; public final int w, h;
        private int[] ch;
        private int chEpoch = -1;

        public Frame(int[] px, int w, int h) { this.px = px; this.w = w; this.h = h; }

        /**
         * 按 s 倍缩放 (最近邻) — rel/fit 模式下 ROI 里的元素尺寸变了, 模板必须同比例缩
         * 放才匹配得上 (不缩放会掉分, 实测 0.8 倍尺度差就能把 0.95 拉到阈值下)。
         */
        public Frame scale(double s) { return scaleXY(s, s); }

        /**
         * 各向异性缩放 (rel 模式: 画面横向拉伸、纵向不变 ⇒ 模板也得这样缩)。
         *
         * ⚠️ 缩小走**面积平均**而不是最近邻: 最近邻点采样会把高频直接抽没, 实测
         *    4:3 (0.6 倍) 下 REMATCH 峰值从 1.00 掉到 0.65 < 0.72 直接漏识别
         *    (tools/VisionProbe 多分辨率段)。放大时源像素不足, 退化为最近邻。
         */
        public Frame scaleXY(double sx, double sy) {
            if (sx <= 0 || sy <= 0) return this;
            if (Math.abs(sx - 1.0) < 0.01 && Math.abs(sy - 1.0) < 0.01) return this;
            int nw = Math.max(1, (int) Math.round(w * sx));
            int nh = Math.max(1, (int) Math.round(h * sy));
            int[] out = new int[nw * nh];
            for (int y = 0; y < nh; y++) {
                double fy0 = y / sy, fy1 = (y + 1) / sy;
                int y0 = (int) Math.floor(fy0);
                int y1 = Math.min(h - 1, Math.max(y0, (int) Math.ceil(fy1) - 1));
                for (int x = 0; x < nw; x++) {
                    double fx0 = x / sx, fx1 = (x + 1) / sx;
                    int x0 = (int) Math.floor(fx0);
                    int x1 = Math.min(w - 1, Math.max(x0, (int) Math.ceil(fx1) - 1));
                    if (x1 <= x0 && y1 <= y0) {                 // 放大: 无源像素可平均
                        out[y * nw + x] = px[Math.min(h - 1, y0) * w + Math.min(w - 1, x0)];
                        continue;
                    }
                    long r = 0, g = 0, b = 0, n = 0;
                    for (int syi = y0; syi <= y1; syi++)
                        for (int sxi = x0; sxi <= x1; sxi++) {
                            int p = px[syi * w + sxi];
                            r += (p >> 16) & 0xFF; g += (p >> 8) & 0xFF; b += p & 0xFF; n++;
                        }
                    out[y * nw + x] = 0xFF000000
                            | ((int) (r / n) << 16) | ((int) (g / n) << 8) | (int) (b / n);
                }
            }
            return new Frame(out, nw, nh);
        }

        /** 匹配用的单通道值 (0..255) */
        int val(int i) {
            if (!GRAY) return (px[i] >>> SHIFT) & 0xFF;
            if (ch == null || chEpoch != EPOCH) {
                int[] g = new int[px.length];
                for (int k = 0; k < px.length; k++) {
                    int p = px[k];
                    int r = (p >> 16) & 0xFF, gg = (p >> 8) & 0xFF, b = p & 0xFF;
                    g[k] = (r * 77 + gg * 151 + b * 28) >> 8;      // Rec601
                }
                ch = g; chEpoch = EPOCH;
            }
            return ch[i];
        }
    }
}
