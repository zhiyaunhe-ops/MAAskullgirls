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
        return findScored(frame, tpl, roi, th, -1);
    }

    /**
     * 同 findScored, 但**本次调用**用指定的金字塔成本门槛 (pyrCost &lt; 0 = 用全局
     * matcher.pyramid.cost)。
     *
     * 为什么需要它: 全局门槛只能一刀切。实测 (tools/VisionBench, 2026-10-14)
     * "模板大面积 x ROI 只比模板大一点"的组合**走金字塔是负收益** —— 粗搜省下的
     * 被精修 (PYR_TOP x (2*rad+1)^2 x 模板面积) 吃掉, 反而更慢。而
     * "ROI 是模板 2 倍以上"的组合 (ok_btn/scene_x/srv_ok) 走金字塔快 2~40 倍。
     * ⇒ 门槛必须能按调用点给, 不能只靠全局值。
     */
    public static double[] findScored(Frame frame, Frame tpl, int[] roi, double th, double pyrCost) {
        return findScored(frame, tpl, roi, th, pyrCost, -1);
    }

    /**
     * 再加一层覆盖: 本次调用的金字塔**层数** (pyrLevel &lt; 0 = 用全局 matcher.pyramid.level)。
     * 0 = 关 (强制全搜)。
     *
     * ⚠️ 层数是**召回的主开关**, 比 cost 危险得多 —— 降采样把装饰字体的笔画糊掉,
     *    粗搜分数会掉到粗阈值以下 ⇒ 直接**丢检** (tools/VisionBench 实测: 0.25x 下
     *    模板真在画面里, 峰值也只有 0.17, 全搜是 1.0000)。所以调用点要哪一层,
     *    必须拿真帧量过命中分再定, 不能拍脑袋。
     */
    public static double[] findScored(Frame frame, Frame tpl, int[] roi, double th,
                                      double pyrCost, int pyrLevel) {
        double cost = pyrCost < 0 ? PYR_COST : pyrCost;
        int lvl = pyrLevel < 0 ? PYR_LEVEL : pyrLevel;
        int rx = 0, ry = 0, rw = frame.w, rh = frame.h;
        if (roi != null && roi.length == 4) {
            rx = Math.max(0, roi[0]); ry = Math.max(0, roi[1]);
            rw = Math.min(frame.w - rx, roi[2]); rh = Math.min(frame.h - ry, roi[3]);
        }
        if (rw < tpl.w || rh < tpl.h) return null;

        /* 成本够高才走金字塔 —— 有 ROI 的小图全搜本来就 100~300ms, 不必绕一圈 */
        if (lvl > 0) {
            long nx = (rw - tpl.w) / STEP + 1, ny = (rh - tpl.h) / STEP + 1;
            if (nx * ny * (double) tpl.w * tpl.h > cost) {
                double[] r = pyrSearch(frame, tpl, rx, ry, rw, rh, th, lvl);
                if (r != null) return r;            // null = 模板太小不适用 → 回退全搜
            }
        }
        return fullSearch(frame, tpl, rx, ry, rw, rh, th);
    }

    /** 金字塔粗搜 → 原分辨率精修。返回 null 表示"不适用"(模板降采样后太小), 调用方回退全搜。 */
    private static double[] pyrSearch(Frame frame, Frame tpl, int rx, int ry, int rw, int rh,
                                      double th, int lvl) {
        double s = 1.0 / (1 << lvl);                          // 0.25 (lvl=2) / 0.5 (lvl=1)
        Frame t2 = tpl.scale(s);
        if (t2.w < 4 || t2.h < 4) return null;
        Frame f2 = frame.scaleXY(s, s);                       // 整帧降采样 (面积平均)
        int rx2 = (int) Math.round(rx * s), ry2 = (int) Math.round(ry * s);
        int rw2 = Math.max(0, (int) Math.round(rw * s)), rh2 = Math.max(0, (int) Math.round(rh * s));
        rw2 = Math.min(f2.w - rx2, rw2); rh2 = Math.min(f2.h - ry2, rh2);
        if (rw2 < t2.w || rh2 < t2.h) return null;            // 降采样后 ROI 装不下 → 不适用

        Match m2 = new Match(f2, t2);
        if (m2.tVar < 1e-6) return null;

        /* 粗搜: 不放宽到 th 以上就提前退出, 要收齐候选; 阈值放宽 (降采样必然掉分) */
        double th2 = Math.max(PYR_FLOOR, th - PYR_RELAX);
        java.util.List<double[]> cands = new java.util.ArrayList<>();
        double best2 = -2, bx2 = rx2, by2 = ry2;
        for (int oy = ry2; oy <= ry2 + rh2 - t2.h; oy += STEP)
            for (int ox = rx2; ox <= rx2 + rw2 - t2.w; ox += STEP) {
                double ncc = nccAt(m2, ox, oy);
                if (ncc > best2) { best2 = ncc; bx2 = ox; by2 = oy; }
                if (ncc >= th2) cands.add(new double[]{ox, oy, ncc});
            }

        /* 候选按分数降序, 只精修前 PYR_TOP 个 */
        cands.sort((a, b) -> Double.compare(b[2], a[2]));
        Match m = new Match(frame, tpl);         // ⚠️ 精修用原帧, 与粗搜的 f2 是两张图
        int rad = Math.max(REFINE, (int) Math.ceil(1.0 / s) + 1);   // 粗搜定位误差 ±1/s 像素
        double best = -2; int bx = rx, by = ry;
        int n = Math.min(cands.size(), PYR_TOP);
        for (int i = 0; i < n; i++) {
            double[] c = cands.get(i);
            int ox = clamp(rx + (int) Math.round(c[0] / s), rx, rx + rw - tpl.w);
            int oy = clamp(ry + (int) Math.round(c[1] / s), ry, ry + rh - tpl.h);
            double[] r = refineBest(m, ox, oy, rad);
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
        Match m = new Match(frame, tpl);        // 模板统计 + 两侧通道缓存 + 帧积分图, 一次做好
        if (m.tVar < 1e-6) return null;

        double best = -2, bx = rx, by = ry;
        for (int oy = ry; oy <= ry + rh - tpl.h; oy += STEP) {   // 步距 STEP, 命中后细化
            for (int ox = rx; ox <= rx + rw - tpl.w; ox += STEP) {
                double ncc = nccAt(m, ox, oy);
                if (ncc > best) { best = ncc; bx = ox; by = oy; }
                if (ncc >= th) {
                    int[] rf = refine(m, th, ox, oy);
                    if (rf != null) {
                        double s = nccAt(m, rf[0], rf[1]);
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
        double[] rf = refineBest(m, (int) bx, (int) by, REFINE);
        if (rf != null && rf[2] > best) { best = rf[2]; bx = (int) rf[0]; by = (int) rf[1]; }
        return new double[]{bx, by, best};      // 未过阈值: 把峰值带回去
    }

    /** 单点 NCC 分数 (穷举峰值用 — 离线探针与状态页共用同一份口径) */
    public static double scoreAt(Frame f, Frame t, int ox, int oy) {
        if (ox < 0 || oy < 0 || ox + t.w > f.w || oy + t.h > f.h) return -2;
        Match m = new Match(f, t);
        if (m.tVar < 1e-6) return 0;
        return nccAt(m, ox, oy);
    }

    /** 步距 STEP 粗命中后, 在 ±REFINE 邻域步距 1 精定位 (过阈值才返回) */
    private static int[] refine(Match m, double th, int ox, int oy) {
        double[] b = refineBest(m, ox, oy, REFINE);
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
    private static double[] refineBest(Match m, int ox, int oy, int rad) {
        final Frame f = m.f, t = m.t;
        if (f.w < t.w || f.h < t.h) return null;
        int x0 = Math.max(0, ox - rad), x1 = Math.min(f.w - t.w, ox + rad);
        int y0 = Math.max(0, oy - rad), y1 = Math.min(f.h - t.h, oy + rad);
        if (x1 < x0 || y1 < y0) return null;
        double bs = -2; int bx = ox, by = oy;
        for (int y = y0; y <= y1; y++)
            for (int x = x0; x <= x1; x++) {
                double s = nccAt(m, x, y);
                if (s > bs) { bs = s; bx = x; by = y; }
            }
        return new double[]{bx, by, bs};
    }

    /**
     * 帧的积分图 (SAT) — nccAt 靠它把"窗口均值/方差"降成 **O(1)**。
     *
     * 旧实现每个候选位置要把模板面积**遍历两趟**: ①Σf 求 μ ②同时算 Σ(f-μ)² 与
     * Σ(f-μ)(t-μt)。而
     *     Σ(f-μ)²      ≡ Σf² - (Σf)²/n
     *     Σ(f-μ)(t-μt) ≡ Σf·t - μ·Σt
     * 于是预计算 Σf / Σf² 的积分图后, 前两件变 O(1), 只剩 Σf·t 一趟 ——
     * **数学上完全等价 (不是近似), 只快不变分** (等价性由 tools/VisionBench 逐点断言)。
     *
     * ⚠️ 按帧持有、口径 (EPOCH) 变了自动重建, **不做全局/跨帧复用**: 常规结算循环与
     *    fast 观察线程可能并发调 Vision, 共享可变缓冲会串数据 (旧实现全用局部变量,
     *    所以是线程安全的 —— 别把这条性质破坏掉)。
     * 内存: int[(w+1)(h+1)] + long[(w+1)(h+1)] ≈ 8.9 MB @1280x576, 每帧一份;
     *    帧至少 1 秒一张 ⇒ 可接受。
     */
    private static final class Sat {
        final int[] s;        // Σf  (最大 1280*576*255 = 1.88e8 < 2^31 ⇒ int 够, 省一半内存)
        final long[] s2;      // Σf²
        final int stride;     // = w + 1

        Sat(Frame f) {
            stride = f.w + 1;
            int n = stride * (f.h + 1);
            s = new int[n];
            s2 = new long[n];
            final int[] v = f.cv();                          // 已抽好的通道, 不再逐像素调 val()
            for (int y = 0; y < f.h; y++) {
                long rowSum = 0, rowSq = 0;                  // 本行前缀
                int cur = (y + 1) * stride, pre = y * stride, base = y * f.w;
                for (int x = 0; x < f.w; x++) {
                    int p = v[base + x];
                    rowSum += p;
                    rowSq += (long) p * p;
                    s[cur + x + 1] = s[pre + x + 1] + (int) rowSum;
                    s2[cur + x + 1] = s2[pre + x + 1] + rowSq;
                }
            }
        }
    }

    /**
     * 一次搜索的预计算上下文 —— nccAt 只吃它, 免得一堆参数散着传。
     * 帧侧: 积分图 (μ/fVar 的 O(1) 来源) + 通道缓存; 模板侧: 通道缓存 + Σt / tVar / tSd。
     *
     * ⚠️ 模板统计 (Σt / tVar) 与两侧通道抽取**每次搜索只做一遍** —— 旧写法把它们放在
     *    nccAt 里逐位置重算 (每位置一趟 O(面积) 的模板遍历)。
     */
    private static final class Match {
        final Frame f, t;
        final Sat sat;
        final int[] fv, tv;
        final int fw;
        final double tSum, tVar, tSd, inv;

        Match(Frame f, Frame t) {
            this.f = f;
            this.t = t;
            this.fw = f.w;
            sat = f.sat();
            fv = f.cv();
            tv = t.cv();
            inv = 1.0 / (t.w * t.h);
            long s = 0;
            for (int v : tv) s += v;
            tSum = s;
            double m = s * inv, var = 0;
            for (int v : tv) { double d = v - m; var += d * d; }
            tVar = var;
            tSd = Math.sqrt(var);
        }
    }

    /**
     * 单点 NCC。μ 与 fVar 走积分图 O(1), 只剩一趟 Σf·t ⇒ 与旧"两趟"实现数学等价。
     * @param tSum 模板像素和 Σt (调用方算一次即可, 别每点重算)
     */
    private static double nccAt(Match m, int ox, int oy) {
        final Sat S = m.sat;
        final int W = S.stride, tw = m.t.w, th = m.t.h;
        final int x1 = ox + tw, y1 = oy + th;
        long sum = (long) S.s[y1 * W + x1] - S.s[oy * W + x1]
                 - S.s[y1 * W + ox] + S.s[oy * W + ox];
        long sq = S.s2[y1 * W + x1] - S.s2[oy * W + x1]
                - S.s2[y1 * W + ox] + S.s2[oy * W + ox];
        double fMean = sum * m.inv;
        double fVar = sq - sum * fMean;                  // ≡ Σf² - (Σf)²/n
        if (fVar < 0) fVar = 0;                          // 平坦块上 Σf² 的舍入可能微负 → 夹住, 避免 sqrt(NaN)
        double den = Math.sqrt(fVar) * m.tSd;
        if (den < 1e-6) return 0;
        /* Σf·t: 纯 int 数组乘加 —— 整数精确, 单次乘积 ≤ 255*255 不会溢出,
         * 累加用 long (最大 58800*65025 = 3.8e9 > 2³¹)。 */
        long num = 0;
        final int[] fv = m.fv, tv = m.tv;
        int ti = 0;
        for (int y = oy; y < y1; y++) {
            int row = y * m.fw + ox;
            for (int x = 0; x < tw; x++) num += fv[row + x] * tv[ti++];
        }
        return (num - fMean * m.tSum) / den;             // Σ(f-μf)(t-μt) = Σf·t - μf·Σt
    }

    static double sum(Frame t) {
        long m = 0;
        for (int c : t.cv()) m += c;
        return m;
    }

    static double mean(Frame t) {
        return sum(t) / (t.w * t.h);
    }

    static double sd(Frame t) {
        double m = mean(t), v = 0;
        for (int c : t.cv()) { double d = c - m; v += d * d; }
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
        private int[] cv;                     // 通道值缓存 (0..255) — 见 cv()
        private int cvEpoch = -1;
        private Sat sat;
        private int satEpoch = -1;

        public Frame(int[] px, int w, int h) { this.px = px; this.w = w; this.h = h; }

        /**
         * **通道值缓存** (0..255, 已按 matcher.chan 抽好) —— 内循环提速的关键。
         *
         * 旧写法在最内层调 {@code val(int)}: 每像素一次方法调用 + shift/and (+ 灰度分支),
         * 而且是 double 乘加。而**同一个像素在一次搜索里要被读上万次** (模板 19600 像素 x
         * 上千个位置), 模板更是每次搜索都重用 ⇒ 预抽一遍稳赚。抽完之后 nccAt 的内循环
         * 是纯 int 数组乘加 —— 整数运算**精确**, 连 FP 舍入都没有了。
         *
         * 代价: int[w*h] = 2.9 MB @1280x576, 每帧一份; 口径 (EPOCH) 变了整批失效重建。
         */
        int[] cv() {
            int[] c = cv;
            if (c == null || cvEpoch != EPOCH) {
                int n = px.length;
                c = new int[n];
                if (!GRAY) {
                    int sh = SHIFT;
                    for (int i = 0; i < n; i++) c[i] = (px[i] >>> sh) & 0xFF;
                } else {
                    for (int i = 0; i < n; i++) {
                        int p = px[i];
                        c[i] = (((p >> 16) & 0xFF) * 77 + ((p >> 8) & 0xFF) * 151
                                + (p & 0xFF) * 28) >> 8;               // Rec601
                    }
                }
                cv = c; cvEpoch = EPOCH;
            }
            return c;
        }

        /** 单像素通道值 —— 非热点路径/探针用; 内循环一律走 cv() 拿数组 */
        int val(int i) { return cv()[i]; }

        /** 本帧的积分图 (lazy; 通道口径 EPOCH 变了自动重建) */
        private Sat sat() {
            Sat s = sat;
            if (s == null || satEpoch != EPOCH) { s = new Sat(this); sat = s; satEpoch = EPOCH; }
            return s;
        }

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

    }
}
