import com.zhiyaunhe.sgmbot.Vision;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ZBPC（有界部分相关 / Bounded Partial Correlation）跳过率与收益实测。
 *
 * <h3>要回答的问题</h3>
 * 调研结论说 BPC 族是"算法侧最对口、而且精确"的路（ZBPC 论文给 1.31–3.91x、跳过 &gt;72%）。
 * 但**论文实测的模板/窗比 n/N ≈ 0.12–0.21，我们的紧 ROI 是 0.32–0.45**（btn_rois 甚至
 * 只有 17x17 个位置）⇒ 跳过率未测过，必须先自测再决定做不做。本工具就是那一步。
 *
 * <h3>原理（与生产 nccAt 的关系）</h3>
 * 生产 nccAt 已经用积分图把窗口均值/方差降成 O(1)，只剩**一趟** Σf·t。ZBPC 把这一趟
 * 按**行**切开：累完前 k 行后，用 Cauchy–Schwarz 给"剩下行还能贡献多少":
 *
 * <pre>
 *   ZNCC 分子 = A_S + A_R
 *   A_S  = Σ_S (f-μf)(t-μt)            // 已累出的部分
 *   |A_R| ≤ sqrt( Vf_R · Vt_R )        // R = 还没累的行  (Cauchy–Schwarz)
 *   Vf_R = Σ_R (f-μf)²  ← R 是**矩形** ⇒ 积分图 O(1) 拿 Σf、Σf²
 *   Vt_R = Σ_R (t-μt)²  ← 与位置无关, 预先按行做后缀和
 * </pre>
 *
 * ⚠️⚠️ **部分集上不能套用全集的恒等式** —— 这是本工具第一个版本踩过的坑:
 * 全集有 {@code Σ_all(f-μf)(t-μt) = Σft - μf·Σt}，但它**只因 Σ_all(f-μf)=0 才成立**；
 * 部分集 Σ_S(f-μf) ≠ 0，必须写全四项:
 * <pre>
 *   A_S = Σ_S ft - μf·Σ_S t - μt·Σ_S f + n_S·μf·μt
 * </pre>
 * 漏掉交叉项 ⇒ "上界"不再是上界 ⇒ 静默剪掉真正的 argmax。所以 E1/E3 都带一列
 * **剪枝复核**：把每个被剪位置用全累加回算真分数，必须严格小于最终峰值。
 * 只比 argmax 是不够的 —— 那次 28k 个被剪位置里只有 1 个违规，靠复核列才定位到。
 *
 * 于是每个位置每行只需 O(1) 的额外开销就能算出一个**严格上界**；上界够低就整位置丢弃。
 *
 * <h3>四个变体（决定做不做的关键就在它们的差异上）</h3>
 * <ul>
 *   <li><b>P1 全累加</b>：复刻生产 nccAt，无剪枝 —— 基准。</li>
 *   <li><b>P2 精确剪枝</b>：剪枝目标 = **当前最优分**（论文口径）。被丢的位置只是"不可能
 *       超过当前最优" ⇒ **argmax 与峰值分逐位不变**，判定图那列分数不退化。</li>
 *   <li><b>P2s 精确 + 预热种子</b>：先用 step=8 粗扫一趟把"当前最优"垫高（Wei &amp; Lai 2010
 *       的初始阈值估计），让剪枝从第一个位置就咬得住。种子位置的分数是真算的、且是主网格的
 *       子集 ⇒ 仍然精确。</li>
 *   <li><b>P2t 阈值剪枝</b>：剪枝目标 = 判定阈值 th。**判定精确**（被丢的位置严格低于 th，
 *       不可能丢命中），但**未命中时把峰值报成下界** —— 这会削弱 `[观]` 日志里
 *       "0.69 卡住 vs 0.31 无关" 的诊断能力。⚠️ 拿它必须连这个代价一起接受。</li>
 * </ul>
 *
 * ⚠️ 通道口径按默认 `matcher.chan = blue` 复刻（`px[i] &amp; 0xFF`）。换了 chan 要同步改
 * {@link #cvOf}，否则与生产分数对不上（A 段会立刻报出来）。
 *
 * 用法（仓库根）：
 *   javac -cp &lt;android.jar&gt; -d &lt;out&gt; phone/app/src/com/zhiyaunhe/sgmbot/Vision.java \
 *         phone/app/tools/ZbpcProbe.java
 *   java  -cp &lt;out&gt;;&lt;android.jar&gt; ZbpcProbe [素材目录] [模板目录]
 * 等价性不符退出码 1。
 */
public class ZbpcProbe {

    /* ==================== 复刻生产的通道 / 积分图口径 ==================== */

    /** blue 通道（默认 matcher.chan），与 Vision.Frame.cv() 同口径 */
    static int[] cvOf(Vision.Frame f) {
        int[] c = new int[f.px.length];
        for (int i = 0; i < c.length; i++) c[i] = f.px[i] & 0xFF;
        return c;
    }

    /** 与 Vision.Sat 同一套：Σf 用 int、Σf² 用 long */
    static final class Sat {
        final int[] s;
        final long[] s2;
        final int stride;

        Sat(int[] v, int w, int h) {
            stride = w + 1;
            int n = stride * (h + 1);
            s = new int[n];
            s2 = new long[n];
            for (int y = 0; y < h; y++) {
                long rowSum = 0, rowSq = 0;
                int cur = (y + 1) * stride, pre = y * stride, base = y * w;
                for (int x = 0; x < w; x++) {
                    int p = v[base + x];
                    rowSum += p;
                    rowSq += (long) p * p;
                    s[cur + x + 1] = s[pre + x + 1] + (int) rowSum;
                    s2[cur + x + 1] = s2[pre + x + 1] + rowSq;
                }
            }
        }
    }

    /** 一次搜索的预计算上下文（对应生产的 Vision.Match，外加 ZBPC 需要的两个行的前后缀） */
    static final class Ctx {
        final int[] fv, tv;
        final Sat sat;
        final int fw, fh, tw, th;
        long tSum;
        final double tVar, tSd, tMean, inv;
        final double[] tPrefSum;   // k: Σ_{rows<k} t
        final double[] tSufVar;    // k: Σ_{rows>=k} (t-μt)²   ← 与位置无关, 预处理一次

        Ctx(Vision.Frame f, Vision.Frame t) {
            fw = f.w; fh = f.h; tw = t.w; th = t.h;
            fv = cvOf(f);
            tv = cvOf(t);
            sat = new Sat(fv, f.w, f.h);
            inv = 1.0 / (tw * th);
            long s = 0;
            for (int v : tv) s += v;
            tSum = s;
            double mu = s * inv, var = 0;
            for (int v : tv) { double d = v - mu; var += d * d; }
            tVar = var;
            tSd = Math.sqrt(var);
            tMean = mu;

            tPrefSum = new double[th + 1];
            double[] rowVar = new double[th];
            for (int y = 0; y < th; y++) {
                double rs = 0, rv = 0;
                for (int x = 0; x < tw; x++) {
                    int v = tv[y * tw + x];
                    rs += v;
                    double d = v - mu;
                    rv += d * d;
                }
                tPrefSum[y + 1] = tPrefSum[y] + rs;
                rowVar[y] = rv;
            }
            tSufVar = new double[th + 1];
            for (int y = th - 1; y >= 0; y--) tSufVar[y] = tSufVar[y + 1] + rowVar[y];
        }
    }

    /* ==================== 评分 ==================== */

    /** 复刻生产 Vision.nccAt（SAT 出 μ/fVar，一趟 Σf·t，无剪枝） */
    static double nccFull(Ctx c, int ox, int oy) {
        final Sat S = c.sat;
        final int W = S.stride, tw = c.tw, th = c.th;
        final int x1 = ox + tw, y1 = oy + th;
        long sum = (long) S.s[y1 * W + x1] - S.s[oy * W + x1]
                 - S.s[y1 * W + ox] + S.s[oy * W + ox];
        long sq = S.s2[y1 * W + x1] - S.s2[oy * W + x1]
                - S.s2[y1 * W + ox] + S.s2[oy * W + ox];
        double fMean = sum * c.inv;
        double fVar = sq - sum * fMean;
        if (fVar < 0) fVar = 0;
        double den = Math.sqrt(fVar) * c.tSd;
        if (den < 1e-6) return 0;
        long num = 0;
        final int[] fv = c.fv, tv = c.tv;
        int ti = 0;
        for (int y = oy; y < y1; y++) {
            int row = y * c.fw + ox;
            for (int x = 0; x < tw; x++) num += fv[row + x] * tv[ti++];
        }
        return (num - fMean * c.tSum) / den;
    }

    /**
     * ZBPC：逐行累加 + 有界剪枝。
     * @param target 剪枝目标（**分数尺度**）。P2 = 当前最优分；P2t = 判定阈值。
     *               target &lt; -1 表示"还没有可信的最优分" ⇒ 不剪枝（论文里第一个位置必然全算）。
     * @param st     统计（可为 null）
     * @return 分数；被剪枝时返回 {@link Double#NEGATIVE_INFINITY}（调用方不应更新 best）
     */
    static double nccZbpc(Ctx c, int ox, int oy, double target, Stat st) {
        final Sat S = c.sat;
        final int W = S.stride, tw = c.tw, th = c.th;
        final int x1 = ox + tw, y1 = oy + th;
        long sum = (long) S.s[y1 * W + x1] - S.s[oy * W + x1]
                 - S.s[y1 * W + ox] + S.s[oy * W + ox];
        long sq = S.s2[y1 * W + x1] - S.s2[oy * W + x1]
                - S.s2[y1 * W + ox] + S.s2[oy * W + ox];
        double fMean = sum * c.inv;
        double fVar = sq - sum * fMean;
        if (fVar < 0) fVar = 0;
        double den = Math.sqrt(fVar) * c.tSd;
        if (den < 1e-6) return 0;                       // 与生产同口径：平窗直接 0, 不剪枝

        final boolean prune = target > -1.0;
        final double limit = target * den;              // 换算到"分子尺度", 之后不再除
        final int[] fv = c.fv, tv = c.tv;
        long num = 0;
        int ti = 0;
        for (int k = 1; k <= th; k++) {
            final int row = (oy + k - 1) * c.fw + ox;
            for (int x = 0; x < tw; x++) num += fv[row + x] * tv[ti++];
            if (st != null) st.rowsRun++;
            if (!prune || k == th) continue;
            if (st != null) st.bands++;

            /* 已累部分的中心化分子。
             * ⚠️ 部分集 S 上**不能**套用全集的恒等式:
             *      Σ_all(f-μf)(t-μt) = Σft - μf·Σt          ← 只因 Σ_all(f-μf)=0 才成立
             *    而部分集 Σ_S(f-μf) ≠ 0 ⇒ 两个交叉项必须补回来:
             *      A_S = Σ_S ft - μf·Σ_S t - μt·Σ_S f + n_S·μf·μt
             * 漏掉后两项会让"上界"不再是上界, 直接剪掉真正的 argmax。
             * (2026-10-14 由 E1 的「剪枝复核」列抓到: 28k 个被剪位置里有 1 个真分数
             *  高于最终峰值。) */
            final int fSumS = S.s[(oy + k) * W + x1] - S.s[oy * W + x1]
                            - S.s[(oy + k) * W + ox] + S.s[oy * W + ox];
            final double aS = num - fMean * c.tPrefSum[k] - c.tMean * fSumS
                            + (double) (k * tw) * fMean * c.tMean;
            final double slack = limit - aS;
            if (slack <= 0) continue;                   // 上界 ≥ aS ≥ limit ⇒ 剪不掉
            /* 剩余行矩形 (oy+k .. oy+th-1) 的 (f-μf)² 之和 —— 注意用的是**全局** μf */
            final int ry = oy + k, nR = tw * (th - k);
            long sumR = (long) S.s[y1 * W + x1] - S.s[ry * W + x1]
                      - S.s[y1 * W + ox] + S.s[ry * W + ox];
            long sqR = S.s2[y1 * W + x1] - S.s2[ry * W + x1]
                     - S.s2[y1 * W + ox] + S.s2[ry * W + ox];
            double vfR = sqR - 2.0 * fMean * sumR + (double) nR * fMean * fMean;
            if (vfR < 0) vfR = 0;
            final double vtR = c.tSufVar[k];
            /* 严格 < ：绑定时不剪, 免得把"恰好等于最优/阈值"的位置丢掉 */
            if (vfR * vtR < slack * slack) {
                if (st != null) {
                    st.pruned++;
                    if (st.prunedList != null) st.prunedList.add(new int[]{ox, oy});
                }
                return Double.NEGATIVE_INFINITY;
            }
        }
        if (st != null) st.rowsFull++;
        return (num - fMean * c.tSum) / den;
    }

    static final class Stat {
        long pos, pruned, rowsRun, rowsFull, bands;
        long hitPos;               // 命中 (>=th) 发生在第几个位置（生产口径下"扫了多久"的真指标）
        /** 非 null 时记录每个被剪位置的坐标 —— 供"剪枝复核"用全累加回算真分数 */
        List<int[]> prunedList;

        double skippedPct() { return pos == 0 ? 0 : 100.0 * pruned / pos; }
        /** 平均实际处理的行占比 —— 比"跳过率"更贴近真实成本 */
        double rowsPct() { return pos == 0 ? 0 : 100.0 * rowsRun / (pos * (double) rowsTotal); }
        long rowsTotal;

        void reset(int th) {
            pos = pruned = rowsRun = rowsFull = bands = 0;
            rowsTotal = th;
            if (prunedList != null) prunedList.clear();
        }
    }

    /**
     * 剪枝规则的**直接**复核：把每个被剪位置用全累加算出真分数，必须严格小于最终峰值。
     * 严格小于 ⇒ 既不能改变峰值、也不可能并列改变 argmax（剪枝用的是严格 &lt;）。
     * @return {违例数, 最大超出量}
     */
    static double[] verifyPrune(Ctx c, Stat st, double finalBest) {
        long bad = 0;
        double worst = 0;
        if (st.prunedList == null) return new double[]{0, 0};
        for (int[] p : st.prunedList) {
            double truth = nccFull(c, p[0], p[1]);
            if (truth >= finalBest) { bad++; worst = Math.max(worst, truth - finalBest); }
        }
        return new double[]{bad, worst};
    }

    /* ==================== 搜索驱动 ==================== */

    static final int MODE_FULL = 0, MODE_EXACT = 1, MODE_TH = 2;

    /** 防死代码消除：MODE_BANDS 只为量"界检查"本身的开销，结果必须被观察到 */
    static double SINK;

    /**
     * 只做逐行累加 + 界运算, **从不剪枝** —— 用来把"界检查的固定开销"单独量出来。
     * 收益 = 省下的乘法 - 这份开销；模板越窄, 这份开销占比越高。
     */
    static double nccBands(Ctx c, int ox, int oy, Stat st) {
        final Sat S = c.sat;
        final int W = S.stride, tw = c.tw, th = c.th;
        final int x1 = ox + tw, y1 = oy + th;
        long sum = (long) S.s[y1 * W + x1] - S.s[oy * W + x1]
                 - S.s[y1 * W + ox] + S.s[oy * W + ox];
        long sq = S.s2[y1 * W + x1] - S.s2[oy * W + x1]
                - S.s2[y1 * W + ox] + S.s2[oy * W + ox];
        double fMean = sum * c.inv;
        double fVar = sq - sum * fMean;
        if (fVar < 0) fVar = 0;
        double den = Math.sqrt(fVar) * c.tSd;
        if (den < 1e-6) return 0;
        final int[] fv = c.fv, tv = c.tv;
        long num = 0;
        int ti = 0;
        for (int k = 1; k <= th; k++) {
            final int row = (oy + k - 1) * c.fw + ox;
            for (int x = 0; x < tw; x++) num += fv[row + x] * tv[ti++];
            if (st != null) st.rowsRun++;
            if (k == th) continue;
            final int fSumS = S.s[(oy + k) * W + x1] - S.s[oy * W + x1]
                            - S.s[(oy + k) * W + ox] + S.s[oy * W + ox];
            final double aS = num - fMean * c.tPrefSum[k] - c.tMean * fSumS
                            + (double) (k * tw) * fMean * c.tMean;
            final int ry = oy + k, nR = tw * (th - k);
            long sumR = (long) S.s[y1 * W + x1] - S.s[ry * W + x1]
                      - S.s[y1 * W + ox] + S.s[ry * W + ox];
            long sqR = S.s2[y1 * W + x1] - S.s2[ry * W + x1]
                     - S.s2[y1 * W + ox] + S.s2[ry * W + ox];
            double vfR = sqR - 2.0 * fMean * sumR + (double) nR * fMean * fMean;
            if (vfR < 0) vfR = 0;
            SINK += vfR * c.tSufVar[k] - aS;
        }
        if (st != null) st.rowsFull++;
        return (num - fMean * c.tSum) / den;
    }

    /** 扫描 ROI（步距 step）。返回 {bx, by, best} */
    static double[] scan(Ctx c, int[] roi, int step, int mode, double th, Stat st) {
        int rx = 0, ry = 0, rw = c.fw, rh = c.fh;
        if (roi != null) { rx = roi[0]; ry = roi[1]; rw = roi[2]; rh = roi[3]; }
        if (st != null) st.reset(c.th);
        double best = -2;
        int bx = rx, by = ry;
        for (int oy = ry; oy <= ry + rh - c.th; oy += step)
            for (int ox = rx; ox <= rx + rw - c.tw; ox += step) {
                if (st != null) st.pos++;
                double v;
                if (mode == MODE_FULL) {
                    v = nccFull(c, ox, oy);
                } else if (mode == MODE_EXACT) {
                    v = nccZbpc(c, ox, oy, best, st);
                } else {
                    v = nccZbpc(c, ox, oy, Math.max(th, best), st);
                }
                if (v > best) { best = v; bx = ox; by = oy; }
            }
        return new double[]{bx, by, best};
    }

    /** P2s：先用 seedStep 粗扫垫高"当前最优"，再正式扫（种子位置是主网格子集 ⇒ 仍然精确） */
    static double[] scanSeeded(Ctx c, int[] roi, int step, int seedStep, Stat st) {
        double[] seed = scan(c, roi, seedStep, MODE_FULL, 0, null);
        int rx = 0, ry = 0, rw = c.fw, rh = c.fh;
        if (roi != null) { rx = roi[0]; ry = roi[1]; rw = roi[2]; rh = roi[3]; }
        if (st != null) st.reset(c.th);
        double best = seed[2];
        int bx = (int) seed[0], by = (int) seed[1];
        for (int oy = ry; oy <= ry + rh - c.th; oy += step)
            for (int ox = rx; ox <= rx + rw - c.tw; ox += step) {
                if (st != null) st.pos++;
                double v = nccZbpc(c, ox, oy, best, st);
                if (v > best) { best = v; bx = ox; by = oy; }
            }
        return new double[]{bx, by, best};
    }

    /**
     * **生产口径**：命中 (score &gt;= th) 立即返回 —— 生产的 fullSearch 就是这么做的。
     * 这一点对结论很关键：模板"在场"时生产本就在第一个命中处提前退出, 扫描量本来就小,
     * 剪枝帮不上多少；真正贵的是"不在场"时那一趟全扫, 而那正是剪枝最弱的情形。
     */
    static double[] scanEarly(Ctx c, int[] roi, int step, int mode, double th, Stat st) {
        int rx = 0, ry = 0, rw = c.fw, rh = c.fh;
        if (roi != null) { rx = roi[0]; ry = roi[1]; rw = roi[2]; rh = roi[3]; }
        if (st != null) st.reset(c.th);
        double best = -2;
        int bx = rx, by = ry;
        long n = 0;
        for (int oy = ry; oy <= ry + rh - c.th; oy += step)
            for (int ox = rx; ox <= rx + rw - c.tw; ox += step) {
                n++;
                if (st != null) st.pos++;
                double v = (mode == MODE_FULL)
                        ? nccFull(c, ox, oy)
                        : nccZbpc(c, ox, oy, mode == MODE_TH ? Math.max(th, best) : best, st);
                if (v > best) { best = v; bx = ox; by = oy; }
                if (v >= th) {
                    if (st != null) st.hitPos = n;
                    return new double[]{bx, by, best};
                }
            }
        return new double[]{bx, by, best};
    }

    static long positions(int[] roi, int fw, int fh, int tw, int th, int step) {
        int rx = 0, ry = 0, rw = fw, rh = fh;
        if (roi != null) { rx = roi[0]; ry = roi[1]; rw = roi[2]; rh = roi[3]; }
        if (rw < tw || rh < th) return 0;
        return (long) ((rw - tw) / step + 1) * ((rh - th) / step + 1);
    }

    /* ==================== 素材 ==================== */

    static Vision.Frame load(String p) throws Exception {
        BufferedImage im = ImageIO.read(new File(p));
        if (im == null) throw new Exception("无法解码 " + p);
        int w = im.getWidth(), h = im.getHeight();
        int[] px = new int[w * h];
        im.getRGB(0, 0, w, h, px, 0, w);
        return new Vision.Frame(px, w, h);
    }

    static double best(int n, Runnable r) {
        double b = Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            long t0 = System.nanoTime();
            r.run();
            b = Math.min(b, (System.nanoTime() - t0) / 1e6);
        }
        return b;
    }

    /* ==================== 主流程 ==================== */

    public static void main(String[] args) throws Exception {
        String src = args.length > 0 ? args[0] : "D:/Downloads/Telegram Desktop/";
        String tplDir = args.length > 1 ? args[1] : "phone/app/assets/templates/";
        if (!src.endsWith("/")) src += "/";
        if (!tplDir.endsWith("/")) tplDir += "/";

        Map<String, Vision.Frame> tpl = new LinkedHashMap<>();
        for (String n : new String[]{"victory", "defeat", "btn_rematch", "btn_continue", "btn_quit",
                "srv_ok", "srv_retry", "ok_btn", "scene_x", "modal_x", "vs_fight"}) {
            try { tpl.put(n, load(tplDir + n + ".png")); } catch (Exception e) { }
        }
        Map<String, Vision.Frame> frames = new LinkedHashMap<>();
        for (String t : new String[]{"17-34-39", "17-34-49", "17-34-56", "17-35-00"}) {
            try { frames.put(t, load(src + "photo_2026-09-05_" + t + ".jpg")); } catch (Exception e) { }
        }
        if (frames.isEmpty()) { System.out.println("[跳过] 无素材帧"); System.exit(2); }
        System.out.println("帧数 = " + frames.size() + "  模板 = " + tpl.keySet());

        int[] titleR = {400, 20, 480, 100};
        int[] okR = {480, 300, 320, 180};
        int[] sceneR = {600, 0, 560, 180};
        int[] modalR = {480, 0, 480, 280};
        int[] vsR = {1020, 0, 260, 130};
        int[][] btnR = {{265, 478, 220, 68}, {530, 478, 220, 68}, {793, 478, 220, 68}};

        /* ---- 直接全分辨率调用点（生产不走金字塔的那些 = ZBPC 的真正战场） ---- */
        List<Object[]> direct = new ArrayList<>();
        for (String fn : frames.keySet()) {
            Vision.Frame f = frames.get(fn);
            direct.add(new Object[]{fn + "/victory", f, "victory", titleR, 0.72});
            direct.add(new Object[]{fn + "/defeat", f, "defeat", titleR, 0.72});
            direct.add(new Object[]{fn + "/ok_btn", f, "ok_btn", okR, 0.80});
            direct.add(new Object[]{fn + "/scene_x", f, "scene_x", sceneR, 0.80});
            direct.add(new Object[]{fn + "/modal_x", f, "modal_x", modalR, 0.90});
            direct.add(new Object[]{fn + "/vs_fight", f, "vs_fight", vsR, 0.75});
            direct.add(new Object[]{fn + "/rematch@0", f, "btn_rematch", btnR[0], 0.72});
            direct.add(new Object[]{fn + "/continue@0", f, "btn_continue", btnR[0], 0.72});
        }

        int bad = 0;

        /* ---- E0. 自检：复刻口径必须与生产 Vision.scoreAt 逐点同分 ----
         * 若这一关不过, 后面的跳过率/加速比全部无意义 —— 那说明复刻的通道抽取或积分图
         * 与生产不一致, 而不是 ZBPC 有多快。 */
        System.out.println("\n== E0. 自检: 复刻 nccFull vs 生产 Vision.scoreAt (逐点) ==");
        int[] chk = {400, 20, 480, 100};
        for (String fn : frames.keySet()) {
            Vision.Frame f = frames.get(fn);
            for (String tn : new String[]{"victory", "defeat"}) {
                Vision.Frame t = tpl.get(tn);
                if (t == null) continue;
                Ctx c = new Ctx(f, t);
                int n = 0;
                double worst = 0;
                for (int oy = chk[1]; oy <= chk[1] + chk[3] - t.h; oy++)
                    for (int ox = chk[0]; ox <= chk[0] + chk[2] - t.w; ox++) {
                        double a = nccFull(c, ox, oy);
                        double b = Vision.scoreAt(f, t, ox, oy);
                        double d = Math.abs(a - b);
                        if (d > worst) worst = d;
                        n++;
                    }
                boolean ok = worst <= 1e-9;
                if (!ok) bad++;
                System.out.printf("  %-16s %6d 点  最大|Δ| = %.3e  %s%n",
                        fn + "/" + tn, n, worst, ok ? "OK" : "**不一致**");
            }
        }

        /* ---- 预热 ----
         * ⚠️ nccZbpc / scanSeeded 的**首次**调用才触发 JIT 编译, 不预热会让头几个用例
         * 测到解释执行的耗时。实测证据 (2026-10-14): 同一用例 17-34-39/victory 在 E1 报
         * 0.73x、在 E2 报 2.56x —— 被预热过的后者才是真的。min-of-N 挡不住这个, 因为
         * 前几轮全在编译。 */
        {
            Object[] w0 = direct.get(0);
            final Ctx wc = new Ctx((Vision.Frame) w0[1], tpl.get((String) w0[2]));
            final int[] wroi = (int[]) w0[3];
            for (int i = 0; i < 5; i++) {
                scan(wc, wroi, 2, MODE_FULL, 0, null);
                scan(wc, wroi, 2, MODE_EXACT, 0, null);
                scan(wc, wroi, 2, MODE_TH, 0.8, null);
                scanSeeded(wc, wroi, 2, 8, null);
            }
            System.out.println("\n[预热] 四种扫描各跑 5 遍后才开始计时");
        }

        /* ---- E1: 直接全分辨率调用点（生产不走金字塔的那些 = ZBPC 的真正战场） ---- */
        System.out.println("\n== E1. 直接全分辨率调用点：ZBPC(精确剪枝) vs 全累加 ==");
        System.out.printf("%-22s %7s %8s %6s %9s %9s %7s %7s %8s %s%n",
                "用例", "位置数", "模板面积", "n/N", "P1 ms", "P2 ms", "加速", "跳过%", "均行%", "精确?/剪枝复核");
        double t1sum = 0, t2sum = 0;
        for (Object[] cs : direct) {
            Vision.Frame f = (Vision.Frame) cs[1];
            Vision.Frame t = tpl.get((String) cs[2]);
            if (t == null) continue;
            int[] roi = (int[]) cs[3];
            final Ctx c = new Ctx(f, t);
            long pos = positions(roi, c.fw, c.fh, c.tw, c.th, 2);
            if (pos == 0) continue;
            final Stat st = new Stat();
            st.prunedList = new ArrayList<>();
            final double[] r1 = new double[3], r2 = new double[3];
            double m1 = best(3, () -> { double[] r = scan(c, roi, 2, MODE_FULL, 0, null);
                                        r1[0] = r[0]; r1[1] = r[1]; r1[2] = r[2]; });
            double m2 = best(3, () -> { double[] r = scan(c, roi, 2, MODE_EXACT, 0, st);
                                        r2[0] = r[0]; r2[1] = r[1]; r2[2] = r[2]; });
            boolean exact = r1[0] == r2[0] && r1[1] == r2[1] && Math.abs(r1[2] - r2[2]) <= 1e-9;
            /* 剪枝复核: 每个被剪位置的**真分数**必须严格低于最终峰值 */
            double[] vf = verifyPrune(c, st, r2[2]);
            boolean sound = vf[0] == 0;
            if (!exact || !sound) bad++;
            double nOverN = (double) (c.tw * c.th) / (roi == null ? c.fw * (c.fh) : roi[2] * roi[3]);
            t1sum += m1; t2sum += m2;
            System.out.printf("%-22s %7d %8d %6.2f %9.2f %9.2f %6.2fx %6.1f%% %7.1f%% %s%n",
                    cs[0], pos, c.tw * c.th, nOverN, m1, m2, m1 / m2,
                    st.skippedPct(), st.rowsPct(),
                    (exact ? "OK" : "**argmax不一致**") + "/"
                            + (sound ? "剪" + st.prunedList.size() + "个全合规"
                                     : "**" + (long) vf[0] + "个被剪却更高(超" + String.format("%.2e", vf[1]) + ")**"));
        }
        System.out.printf("%-22s %32s %9.1f %9.1f %6.2fx%n", "合计", "", t1sum, t2sum, t1sum / t2sum);

        /* ---- 变体对比（同一批调用点） ---- */
        System.out.println("\n== E2. 四个变体的收益对比（同一批直接调用点） ==");
        System.out.printf("%-22s %8s %8s %8s %8s %7s %7s %7s %12s %s%n",
                "用例", "P1 全加", "P2 精确", "P2s 种子", "P2t 阈值",
                "跳过(精)", "跳过(种)", "跳过(阈)", "P2t峰值(真)", "在场?");
        double s1 = 0, s2 = 0, s2s = 0, s2t = 0;
        /* 按"模板是否真在画面里"分组 —— 这是判生死的切分：剪枝强度取决于"当前最优分"
         * 能垫多高，而在场/不在场正好是它的两个极端（稳态下绝大多数调用点都不在场）。 */
        double ab1 = 0, ab2 = 0, ab2s = 0, ab2t = 0, abPeakR = 0, abPeakT = 0;
        long abN = 0;
        double pr1 = 0, pr2 = 0, pr2s = 0, pr2t = 0;
        long prN = 0;
        for (Object[] cs : direct) {
            Vision.Frame f = (Vision.Frame) cs[1];
            Vision.Frame t = tpl.get((String) cs[2]);
            if (t == null) continue;
            final int[] roi = (int[]) cs[3];
            final double th = (Double) cs[4];
            final Ctx c = new Ctx(f, t);
            if (positions(roi, c.fw, c.fh, c.tw, c.th, 2) == 0) continue;
            final Stat ste = new Stat(), sts = new Stat(), stt = new Stat();
            final double[] r1 = new double[3], re = new double[3], rs = new double[3], rt = new double[3];
            double m1 = best(3, () -> { double[] r = scan(c, roi, 2, MODE_FULL, 0, null);
                                        r1[0] = r[0]; r1[1] = r[1]; r1[2] = r[2]; });
            double m2 = best(3, () -> { double[] r = scan(c, roi, 2, MODE_EXACT, 0, ste);
                                        re[0] = r[0]; re[1] = r[1]; re[2] = r[2]; });
            double m2s = best(2, () -> { double[] r = scanSeeded(c, roi, 2, 8, sts);
                                         rs[0] = r[0]; rs[1] = r[1]; rs[2] = r[2]; });
            double m2t = best(2, () -> { double[] r = scan(c, roi, 2, MODE_TH, th, stt);
                                         rt[0] = r[0]; rt[1] = r[1]; rt[2] = r[2]; });
            boolean seedExact = re[0] == rs[0] && re[1] == rs[1] && Math.abs(re[2] - rs[2]) <= 1e-9;
            boolean thDecisionOk = (re[2] >= th) == (rt[2] >= th);
            if (!seedExact || !thDecisionOk) bad++;
            s1 += m1; s2 += m2; s2s += m2s; s2t += m2t;
            boolean present = r1[2] >= th;
            if (present) { pr1 += m1; pr2 += m2; pr2s += m2s; pr2t += m2t; prN++; }
            else { ab1 += m1; ab2 += m2; ab2s += m2s; ab2t += m2t; abN++;
                   abPeakR += rt[2]; abPeakT += r1[2]; }
            System.out.printf("%-22s %8.2f %8.2f %8.2f %8.2f %6.1f%% %6.1f%% %6.1f%% %12s %s%n",
                    cs[0], m1, m2, m2s, m2t, ste.skippedPct(), sts.skippedPct(), stt.skippedPct(),
                    String.format("%.3f(%.3f)", rt[2], r1[2]),
                    (present ? "在场" : "不在")
                            + (seedExact ? "" : " **种子不一致**") + (thDecisionOk ? "" : " **判定不同**"));
        }
        System.out.printf("%-22s %8.1f %8.1f %8.1f %8.1f%n", "合计", s1, s2, s2s, s2t);
        System.out.printf("%-22s %8s %7.2fx %7.2fx %7.2fx%n", "相对 P1 加速", "", s1 / s2, s1 / s2s, s1 / s2t);

        System.out.println("\n== E2b. 按「模板是否在场」分组 —— 判生死的切分 ==");
        System.out.printf("%-14s %6s %9s %9s %9s %9s %8s %8s %8s%n",
                "组", "例数", "P1 全加", "P2 精确", "P2s 种子", "P2t 阈值", "P2加速", "P2s加速", "P2t加速");
        if (abN > 0)
            System.out.printf("%-14s %6d %9.1f %9.1f %9.1f %9.1f %7.2fx %7.2fx %7.2fx%n",
                    "不在场(稳态)", abN, ab1, ab2, ab2s, ab2t, ab1 / ab2, ab1 / ab2s, ab1 / ab2t);
        if (prN > 0)
            System.out.printf("%-14s %6d %9.1f %9.1f %9.1f %9.1f %7.2fx %7.2fx %7.2fx%n",
                    "在场", prN, pr1, pr2, pr2s, pr2t, pr1 / pr2, pr1 / pr2s, pr1 / pr2t);
        System.out.printf("%-14s %6d %9.1f %9.1f %9.1f %9.1f %7.2fx %7.2fx %7.2fx%n",
                "整体", abN + prN, s1, s2, s2s, s2t, s1 / s2, s1 / s2s, s1 / s2t);
        if (abN > 0)
            System.out.printf("P2t 在「不在场」时的代价: 峰值 真值均值 %.3f → 报告值均值 %.3f"
                    + "（判定不受影响, 受影响的是 [观] 日志里\"差多少没过阈值\"那个数）%n",
                    abPeakT / abN, abPeakR / abN);

        /* ---- 走金字塔的那几个：在 0.25x 粗搜帧上测（生产里 ZBPC 只能落在这里） ---- */
        System.out.println("\n== E3. 金字塔粗搜阶段（0.25x）上的 ZBPC —— 全屏模板真正的落点 ==");
        System.out.printf("%-22s %7s %8s %9s %9s %7s %8s %s%n",
                "用例", "位置数", "模板面积", "P1 ms", "P2 ms", "加速", "跳过%", "精确?/剪枝复核");
        double p1 = 0, p2 = 0;
        for (String fn : frames.keySet()) {
            Vision.Frame f4 = frames.get(fn).scale(0.25);
            for (String tn : new String[]{"srv_ok", "srv_retry"}) {
                Vision.Frame t = tpl.get(tn);
                if (t == null) continue;
                Vision.Frame t4 = t.scale(0.25);
                if (t4.w < 4 || t4.h < 4) continue;
                final Ctx c = new Ctx(f4, t4);
                long pos = positions(null, c.fw, c.fh, c.tw, c.th, 2);
                if (pos == 0) continue;
                final Stat st = new Stat();
                st.prunedList = new ArrayList<>();
                final double[] r1 = new double[3], r2 = new double[3];
                double m1 = best(3, () -> { double[] r = scan(c, null, 2, MODE_FULL, 0, null); r1[2] = r[2]; });
                double m2 = best(3, () -> { double[] r = scan(c, null, 2, MODE_EXACT, 0, st); r2[2] = r[2]; });
                boolean exact = Math.abs(r1[2] - r2[2]) <= 1e-9;
                double[] vf = verifyPrune(c, st, r2[2]);
                boolean sound = vf[0] == 0;
                if (!exact || !sound) bad++;
                p1 += m1; p2 += m2;
                System.out.printf("%-22s %7d %8d %9.1f %9.1f %6.2fx %7.1f%% %s%n",
                        fn + "/" + tn, pos, c.tw * c.th, m1, m2, m1 / m2, st.skippedPct(),
                        (exact ? "OK" : "**峰值不一致**") + "/"
                                + (sound ? "剪" + st.prunedList.size() + "个全合规"
                                         : "**" + (long) vf[0] + "个被剪却更高**"));
            }
        }
        if (p1 > 0) System.out.printf("%-22s %32s %9.1f %9.1f %6.2fx%n", "合计", "", p1, p2, p1 / p2);

        /* ---- E4. 开销拆解：界检查本身值多少钱（决定"模板多窄就不划算"） ---- */
        System.out.println("\n== E4. 开销拆解 —— 模板宽度决定成败 ==");
        System.out.printf("%-18s %6s %8s %10s %11s %9s %12s %9s %8s%n",
                "用例", "模板宽", "位置数", "P1 全加", "P1+界不剪", "P2 剪枝",
                "每位置界开销", "少算乘法", "净收益");
        Object[][] rep = {
                {"modal_x", "modal_x", modalR}, {"scene_x", "scene_x", sceneR},
                {"vs_fight", "vs_fight", vsR}, {"ok_btn", "ok_btn", okR},
                {"victory", "victory", titleR},
        };
        Vision.Frame fr = frames.values().iterator().next();
        double[] netW = new double[rep.length];
        int[] widW = new int[rep.length];
        for (int i = 0; i < rep.length; i++) {
            Vision.Frame t = tpl.get((String) rep[i][1]);
            if (t == null) continue;
            final int[] roi = (int[]) rep[i][2];
            final Ctx c = new Ctx(fr, t);
            final long pos = positions(roi, c.fw, c.fh, c.tw, c.th, 2);
            if (pos == 0) continue;
            final Stat sp = new Stat();
            double m1 = best(3, () -> {
                for (int oy = roi[1]; oy <= roi[1] + roi[3] - c.th; oy += 2)
                    for (int ox = roi[0]; ox <= roi[0] + roi[2] - c.tw; ox += 2) nccFull(c, ox, oy);
            });
            double mn = best(3, () -> {
                for (int oy = roi[1]; oy <= roi[1] + roi[3] - c.th; oy += 2)
                    for (int ox = roi[0]; ox <= roi[0] + roi[2] - c.tw; ox += 2) nccBands(c, ox, oy, null);
            });
            double m2 = best(3, () -> scan(c, roi, 2, MODE_EXACT, 0, sp));
            double ovhNs = (mn - m1) * 1e6 / pos;
            netW[i] = m1 / m2;
            widW[i] = c.tw;
            System.out.printf("%-18s %6d %8d %10.2f %11.2f %9.2f %10.1f ns %7.1f%% %7.2fx%n",
                    rep[i][0], c.tw, pos, m1, mn, m2, ovhNs, 100 - sp.rowsPct(), m1 / m2);
        }
        System.out.printf("  (SINK=%.3e 仅用于防死代码消除)%n", SINK / 1e18);
        for (int i = 0; i < rep.length; i++)
            if (widW[i] > 0)
                System.out.printf("  → 模板宽 %3d px: 界开销 %s, 净收益 %.2fx%n",
                        widW[i], netW[i] >= 1.0 ? "被剪枝收益盖过" : "**盖过剪枝收益（反亏）**", netW[i]);

        /* ---- E5. 生产口径（命中即返回）下的真实收益 ---- */
        System.out.println("\n== E5. 生产口径（命中即返回）—— 与生产 fullSearch 同行为 ==");
        System.out.printf("%-22s %8s %9s %9s %8s %9s %8s %s%n",
                "用例", "命中位置", "P1 全加", "P2 精确", "加速", "P2t 阈值", "P2t加速", "在场?");
        double g1 = 0, g2 = 0, g2t = 0;
        double h1 = 0, h2 = 0, h2t = 0;
        long hN = 0;
        for (Object[] cs : direct) {
            Vision.Frame f = (Vision.Frame) cs[1];
            Vision.Frame t = tpl.get((String) cs[2]);
            if (t == null) continue;
            final int[] roi = (int[]) cs[3];
            final double th = (Double) cs[4];
            final Ctx c = new Ctx(f, t);
            if (positions(roi, c.fw, c.fh, c.tw, c.th, 2) == 0) continue;
            final Stat q1 = new Stat(), q2 = new Stat(), qt = new Stat();
            final double[] a = new double[3], b = new double[3], e = new double[3];
            double m1 = best(3, () -> { double[] r = scanEarly(c, roi, 2, MODE_FULL, th, q1);
                                        a[0] = r[0]; a[1] = r[1]; a[2] = r[2]; });
            double m2 = best(3, () -> { double[] r = scanEarly(c, roi, 2, MODE_EXACT, th, q2);
                                        b[0] = r[0]; b[1] = r[1]; b[2] = r[2]; });
            double m2t = best(3, () -> { double[] r = scanEarly(c, roi, 2, MODE_TH, th, qt);
                                         e[0] = r[0]; e[1] = r[1]; e[2] = r[2]; });
            boolean hitSame = (a[2] >= th) == (b[2] >= th) && (a[2] >= th) == (e[2] >= th);
            if (!hitSame) bad++;
            g1 += m1; g2 += m2; g2t += m2t;
            if (!(a[2] >= th)) { h1 += m1; h2 += m2; h2t += m2t; hN++; }
            System.out.printf("%-22s %8s %9.2f %9.2f %7.2fx %9.2f %7.2fx %s%n",
                    cs[0], q1.hitPos == 0 ? "未命中" : String.valueOf(q1.hitPos),
                    m1, m2, m1 / m2, m2t, m1 / m2t, (a[2] >= th ? "在场" : "不在"));
        }
        System.out.printf("%-22s %25s %9.1f %9.1f %7.2fx %9.1f %7.2fx%n",
                "合计", "", g1, g2, g1 / g2, g2t, g1 / g2t);
        if (hN > 0)
            System.out.printf("%-22s %25s %9.1f %9.1f %7.2fx %9.1f %7.2fx%n",
                    "稳态(不在场)小计", "", h1, h2, h1 / h2, h2t, h1 / h2t);

        System.out.println(bad == 0
                ? "\n[ok] 全部变体与基准逐位一致（跳过率数据可用）"
                : "\n[!!] " + bad + " 项不一致 —— 跳过率数据不可信，先修再判");
        System.exit(bad == 0 ? 0 : 1);
    }
}
