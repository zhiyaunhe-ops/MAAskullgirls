import com.zhiyaunhe.sgmbot.Vision;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Vision 匹配的两件事, 用**真机结算页素材**在纯 JVM 上回答 (不碰 android.graphics):
 *
 *   A. 等价性 — 积分图 (SAT) 版 nccAt 与旧"两趟"实现**逐点同分**吗?
 *      旧式: ①遍历模板面积求 Σf → μ  ②再遍历一趟算 Σ(f-μ)² 与 Σ(f-μ)(t-μt)。
 *      新式: Σf/Σf² 预计算成积分图 ⇒ μ 与 fVar 变 O(1), 只剩 Σf·t 一趟。
 *      数学上是恒等变形 (Σ(f-μ)² = Σf²-(Σf)²/n), **不是近似** ⇒ 分数只该差舍入。
 *      本工具对每个候选位置逐一比对, 输出最大绝对差 (判据 1e-9)。
 *
 *   B. 自贴图 — 把模板原样贴进帧, 分数必须是 1.000 (归一化互相关的定义)。
 *      这是"分子分母都没写错"的最短证伪路径。
 *
 *   C. 计时 A/B — 同样的 (帧, 模板, ROI, 阈值) 下, 旧式全搜 vs 新式全搜的墙钟。
 *
 *   D. 金字塔判定 — matcher.pyramid.cost 是按调用点给好还是全局一刀切好?
 *      强制走金字塔 (cost=0) vs 强制全搜 (cost=1e12), 比**时间**和**峰值分**。
 *      峰值分就是召回代理: 分数掉到阈值下 = 那个调用点不能开金字塔。
 *
 * 用法 (仓库根):
 *   javac -cp <android.jar> -d <out> phone/app/src/com/zhiyaunhe/sgmbot/Vision.java \
 *         phone/app/tools/VisionBench.java
 *   java  -cp <out>:<android.jar> VisionBench [素材目录] [模板目录]
 * 素材缺失时跳过对应用例 (工具不炸)。等价性/自贴图不符退出码 1。
 */
public class VisionBench {

    /* ---------- 旧式实现 (照抄改动前的 Vision, 用来做 A/B 基准) ---------- */

    static double legacyNcc(Vision.Frame f, int ox, int oy, Vision.Frame t,
                            double tMean, double tSd, double inv) {
        double fMean = 0;
        for (int y = 0; y < t.h; y++) {
            int row = (oy + y) * f.w + ox;
            for (int x = 0; x < t.w; x++) fMean += f.px[row + x] & 0xFF;
        }
        fMean *= inv;
        double num = 0, fVar = 0;
        for (int y = 0; y < t.h; y++) {
            int row = (oy + y) * f.w + ox;
            int trow = y * t.w;
            for (int x = 0; x < t.w; x++) {
                double d = (f.px[row + x] & 0xFF) - fMean;
                fVar += d * d;
                num += d * ((t.px[trow + x] & 0xFF) - tMean);
            }
        }
        double den = Math.sqrt(fVar) * tSd;
        return den < 1e-6 ? 0 : num / den;
    }

    /** 旧式全搜 (步距 STEP + 过阈值即精定位 + 未过阈值也精修峰值) —— 与改动前逐行一致 */
    static double[] legacyFullSearch(Vision.Frame f, Vision.Frame t, int[] roi, double th) {
        int rx = 0, ry = 0, rw = f.w, rh = f.h;
        if (roi != null && roi.length == 4) {
            rx = Math.max(0, roi[0]); ry = Math.max(0, roi[1]);
            rw = Math.min(f.w - rx, roi[2]); rh = Math.min(f.h - ry, roi[3]);
        }
        if (rw < t.w || rh < t.h) return null;
        final int STEP = 2, REFINE = 2;
        double inv = 1.0 / (t.w * t.h);
        double tMean = 0;
        for (int i = 0; i < t.px.length; i++) tMean += t.px[i] & 0xFF;
        tMean *= inv;
        double tVar = 0;
        for (int i = 0; i < t.px.length; i++) { double d = (t.px[i] & 0xFF) - tMean; tVar += d * d; }
        if (tVar < 1e-6) return null;
        double tSd = Math.sqrt(tVar);

        double best = -2, bx = rx, by = ry;
        for (int oy = ry; oy <= ry + rh - t.h; oy += STEP)
            for (int ox = rx; ox <= rx + rw - t.w; ox += STEP) {
                double ncc = legacyNcc(f, ox, oy, t, tMean, tSd, inv);
                if (ncc > best) { best = ncc; bx = ox; by = oy; }
                if (ncc >= th) {
                    double[] rf = legacyRefine(f, t, tMean, tSd, inv, ox, oy, REFINE);
                    double s = legacyNcc(f, (int) rf[0], (int) rf[1], t, tMean, tSd, inv);
                    return new double[]{rf[0], rf[1], Math.max(s, ncc)};
                }
            }
        double[] rf = legacyRefine(f, t, tMean, tSd, inv, (int) bx, (int) by, REFINE);
        if (rf[2] > best) { best = rf[2]; bx = rf[0]; by = rf[1]; }
        return new double[]{bx, by, best};
    }

    static double[] legacyRefine(Vision.Frame f, Vision.Frame t, double tMean, double tSd,
                                 double inv, int ox, int oy, int rad) {
        int x0 = Math.max(0, ox - rad), x1 = Math.min(f.w - t.w, ox + rad);
        int y0 = Math.max(0, oy - rad), y1 = Math.min(f.h - t.h, oy + rad);
        double bs = -2; int bx = ox, by = oy;
        for (int y = y0; y <= y1; y++)
            for (int x = x0; x <= x1; x++) {
                double s = legacyNcc(f, x, y, t, tMean, tSd, inv);
                if (s > bs) { bs = s; bx = x; by = y; }
            }
        return new double[]{bx, by, bs};
    }

    /* ---------------------------- 工具 ---------------------------- */

    static Map<String, Vision.Frame> tpl = new LinkedHashMap<>();

    static Vision.Frame load(String p) throws Exception {
        BufferedImage im = ImageIO.read(new File(p));
        if (im == null) throw new Exception("无法解码 " + p);
        int w = im.getWidth(), h = im.getHeight();
        int[] px = new int[w * h];
        im.getRGB(0, 0, w, h, px, 0, w);
        return new Vision.Frame(px, w, h);
    }

    static double ms(Runnable r) {
        long t0 = System.nanoTime();
        r.run();
        return (System.nanoTime() - t0) / 1e6;
    }

    /** 跑 n 次取**最快** (JIT 预热 + 排除 GC/调度毛刺) */
    static double best(int n, Runnable r) {
        double b = Double.MAX_VALUE;
        for (int i = 0; i < n; i++) b = Math.min(b, ms(r));
        return b;
    }

    /**
     * 改全局 `matcher.step` —— 走 `Vision.configure()`，与生产同一条路。
     * ⚠️ 每改一次 EPOCH 前进 ⇒ 各 Frame 的通道缓存/积分图在**下一次用到时**重建，
     *    所以必须靠 `best()` 取最快一次把这笔一次性开销滤掉。
     * 需要 org.json 在 classpath 上（`configure` 的参数类型）。
     */
    static void setStep(int s) {
        try {
            org.json.JSONObject p = new org.json.JSONObject();
            p.put("level", 2).put("cost", 2.0e8).put("relax", 0.30)
             .put("floor", 0.50).put("top", 16);
            org.json.JSONObject m = new org.json.JSONObject();
            m.put("chan", "blue").put("step", s).put("refine", 2).put("pyramid", p);
            org.json.JSONObject c = new org.json.JSONObject();
            c.put("matcher", m);
            Vision.configure(c);
        } catch (Exception e) {
            throw new RuntimeException("setStep(" + s + ") 失败: " + e, e);
        }
    }

    public static void main(String[] args) throws Exception {
        String src = args.length > 0 ? args[0] : "D:/Downloads/Telegram Desktop/";
        String tplDir = args.length > 1 ? args[1] : "phone/app/assets/templates/";
        if (!src.endsWith("/")) src += "/";
        if (!tplDir.endsWith("/")) tplDir += "/";

        int bad = 0;
        Vision.Frame fWin = null, fLose = null;
        Vision.Frame f39 = null, f00 = null, f08 = null;
        try { fWin = load(src + "photo_2026-09-05_17-34-56.jpg"); } catch (Exception e) { }
        try { fLose = load(src + "photo_2026-09-05_17-34-49.jpg"); } catch (Exception e) { }
        /* 供 E 段的多帧复核：四张结算页 + 一张非结算页（17-35-08 只有 39KB，多半是别种画面） */
        try { f39 = load(src + "photo_2026-09-05_17-34-39.jpg"); } catch (Exception e) { }
        try { f00 = load(src + "photo_2026-09-05_17-35-00.jpg"); } catch (Exception e) { }
        try { f08 = load(src + "photo_2026-09-05_17-35-08.jpg"); } catch (Exception e) { }
        if (fWin == null) { System.out.println("[跳过] 缺素材 win 帧"); }
        else System.out.println("win 帧 = " + fWin.w + "x" + fWin.h);
        for (String n : new String[]{"victory", "defeat", "btn_rematch", "btn_continue", "srv_ok",
                "srv_retry", "ok_btn", "scene_x", "modal_x", "vs_fight"}) {
            try { tpl.put(n, load(tplDir + n + ".png")); } catch (Exception e) { }
        }

        /* ===== A. 等价性: SAT 版 vs 旧式, 逐点 ===== */
        System.out.println("\n== A. 等价性 (SAT 版 nccAt vs 旧式两趟实现, 逐点比对) ==");
        System.out.printf("%-34s %10s %12s %s%n", "用例", "点数", "最大|Δ|", "判定");
        int[] titleR = {400, 20, 480, 100};
        for (Vision.Frame f : new Vision.Frame[]{fWin, fLose}) {
            if (f == null) continue;
            String fn = f == fWin ? "win" : "lose";
            for (String tn : new String[]{"victory", "defeat"}) {
                Vision.Frame t = tpl.get(tn);
                if (t == null) continue;
                double inv = 1.0 / (t.w * t.h), ts = 0;
                for (int i = 0; i < t.px.length; i++) ts += t.px[i] & 0xFF;
                double tm = ts * inv, tv = 0;
                for (int i = 0; i < t.px.length; i++) { double d = (t.px[i] & 0xFF) - tm; tv += d * d; }
                double tsd = Math.sqrt(tv);
                int n = 0; double worst = 0; double wOx = 0, wOy = 0;
                for (int oy = titleR[1]; oy <= titleR[1] + titleR[3] - t.h; oy++)
                    for (int ox = titleR[0]; ox <= titleR[0] + titleR[2] - t.w; ox++) {
                        double a = legacyNcc(f, ox, oy, t, tm, tsd, inv);
                        double b = Vision.scoreAt(f, t, ox, oy);
                        double d = Math.abs(a - b);
                        if (d > worst) { worst = d; wOx = ox; wOy = oy; }
                        n++;
                    }
                boolean ok = worst <= 1e-9;
                if (!ok) bad++;
                System.out.printf("%-34s %10d %12.3e %s%n",
                        fn + "/" + tn + " (逐点, 步距1)", n, worst,
                        ok ? "OK" : "差!@" + (int) wOx + "," + (int) wOy);
            }
        }

        /* ===== B. 自贴图 = 1.000 ===== */
        System.out.println("\n== B. 自贴图 (模板原样贴进帧 ⇒ 分数必须 1.000) ==");
        for (String tn : new String[]{"victory", "defeat", "btn_rematch", "ok_btn", "srv_ok"}) {
            Vision.Frame t = tpl.get(tn);
            if (t == null) continue;
            int W = 640, H = 576, ox = 97, oy = 143;
            int[] px = new int[W * H];
            java.util.Arrays.fill(px, 0xFF202020);
            for (int y = 0; y < t.h; y++)
                for (int x = 0; x < t.w; x++) px[(oy + y) * W + ox + x] = t.px[y * t.w + x];
            double s = Vision.scoreAt(new Vision.Frame(px, W, H), t, ox, oy);
            boolean ok = Math.abs(s - 1.0) <= 1e-9;
            if (!ok) bad++;
            System.out.printf("  %-12s → %.12f  %s%n", tn, s, ok ? "OK" : "差!");
        }

        /* ===== C/D. 真实调用点的计时与金字塔判定 =====
         * (帧, 模板, ROI, 阈值) —— ROI/阈值照抄 assets/config.json
         * 期望命中列: 这些用例里模板确实在画面的那个位置 (金字塔召回的真实考验) */
        Object[][] work = {
                {"win", fWin, "defeat", new int[]{400, 20, 480, 100}, 0.72, Boolean.FALSE},
                {"lose", fLose, "victory", new int[]{400, 20, 480, 100}, 0.72, Boolean.FALSE},
                {"win", fWin, "victory", new int[]{400, 20, 480, 100}, 0.72, Boolean.TRUE},
                {"lose", fLose, "defeat", new int[]{400, 20, 480, 100}, 0.72, Boolean.TRUE},
                {"win", fWin, "ok_btn", new int[]{480, 300, 320, 180}, 0.80, Boolean.FALSE},
                {"win", fWin, "scene_x", new int[]{600, 0, 560, 180}, 0.80, Boolean.FALSE},
                {"win", fWin, "modal_x", new int[]{480, 0, 480, 280}, 0.90, Boolean.FALSE},
                {"win", fWin, "vs_fight", new int[]{1020, 0, 260, 130}, 0.75, Boolean.FALSE},
                {"win", fWin, "btn_rematch", new int[]{265, 478, 220, 68}, 0.72, Boolean.FALSE},
                {"win", fWin, "btn_rematch", new int[]{530, 478, 220, 68}, 0.72, Boolean.TRUE},
                {"lose", fLose, "btn_rematch", new int[]{793, 478, 220, 68}, 0.72, Boolean.TRUE},
        };

        System.out.println("\n== C. 全搜计时 A/B (旧式两趟 vs 积分图), 同输入同阈值 ==");
        System.out.printf("%-30s %11s %11s %8s %11s %9s%n",
                "用例", "旧式 ms", "积分图 ms", "加速", "峰值分", "命中?");
        double totOld = 0, totNew = 0;
        for (Object[] c : work) {
            Vision.Frame f = (Vision.Frame) c[1];
            Vision.Frame t = tpl.get((String) c[2]);
            if (f == null || t == null) continue;
            final int[] roi = (int[]) c[3];
            final double th = (Double) c[4];
            final Vision.Frame ff = f, tt = t;
            double o = best(3, () -> legacyFullSearch(ff, tt, roi, th));
            // 强制全搜 (cost=1e12) 才是与旧式对等的口径
            double[] nr = new double[1];
            double n = best(3, () -> {
                double[] r = Vision.findScored(ff, tt, roi, th, 1e12);
                nr[0] = r == null ? -2 : r[2];
            });
            totOld += o; totNew += n;
            System.out.printf("%-30s %11.1f %11.1f %7.2fx %11.4f %9s%n",
                    c[0] + "/" + c[2] + " roi" + java.util.Arrays.toString(roi), o, n, o / n, nr[0],
                    nr[0] >= th ? (Boolean.TRUE.equals(c[5]) ? "OK" : "误检!") : "未过");
        }
        System.out.printf("%-30s %11.1f %11.1f %7.2fx%n", "合计", totOld, totNew, totOld / totNew);

        System.out.println("\n== D. 金字塔判定: 层数 0(全搜) / 1(0.5x) / 2(0.25x) ==");
        System.out.printf("%-28s %9s %9s %9s %9s %9s %9s %s%n",
                "用例", "L0 ms", "L1 ms", "L2 ms", "L0 分", "L1 分", "L2 分", "召回判定");
        for (Object[] c : work) {
            Vision.Frame f = (Vision.Frame) c[1];
            Vision.Frame t = tpl.get((String) c[2]);
            if (f == null || t == null) continue;
            final int[] roi = (int[]) c[3];
            final double th = (Double) c[4];
            final Vision.Frame ff = f, tt = t;
            double[] sc = new double[3];
            double[] tm = new double[3];
            for (int L = 0; L < 3; L++) {
                final int lvl = L;
                final double cost = L == 0 ? 1e12 : 0;   // L0 = 强制全搜; L1/L2 = 强制走金字塔
                tm[L] = best(3, () -> {
                    double[] r = Vision.findScored(ff, tt, roi, th, cost, lvl);
                    sc[lvl] = r == null ? -2 : r[2];
                });
            }
            boolean exp = Boolean.TRUE.equals(c[5]);
            StringBuilder v = new StringBuilder();
            for (int L = 1; L <= 2; L++) {
                boolean hitL = sc[L] >= th;
                v.append(" L").append(L).append('=')
                 .append(hitL == exp ? "同" : (exp ? "**丢检**" : "误检"))
                 .append('(').append(String.format("%.2f", tm[0] / tm[L])).append("x)");
            }
            System.out.printf("%-28s %9.1f %9.1f %9.1f %9.4f %9.4f %9.4f %s%n",
                    c[0] + "/" + c[2], tm[0], tm[1], tm[2], sc[0], sc[1], sc[2], v);
        }

        /* srv_ok 全屏搜的旧式成本太大 (离线 32 秒), 只报金字塔侧 */
        if (fWin != null && tpl.containsKey("srv_ok")) {
            final Vision.Frame ff = fWin;
            final Vision.Frame tt = tpl.get("srv_ok");
            double t1 = best(2, () -> Vision.findScored(ff, tt, null, 0.75, 0, 1));
            double t2 = best(2, () -> Vision.findScored(ff, tt, null, 0.75, 0, 2));
            long pos = ((1280 - tt.w) / 2 + 1) * ((576 - tt.h) / 2 + 1);
            System.out.printf("  srv_ok 全屏: 全搜估算 %.0f ms (%.2e 位置x面积), L1 %.1f ms, L2 %.1f ms%n",
                    pos * (double) tt.w * tt.h / 1.7e8 * 1000, pos * (double) tt.w * tt.h, t1, t2);
        }

        System.out.println(bad == 0 ? "\n[ok] 等价性/自贴图全部通过" : "\n[!!] " + bad + " 项不符");

        /* ===== E. matcher.step 扫描 (2026-10-14) =====
         * step 是**唯一还没量过的全局旋钮**：搜索位置数 ∝ 1/step² ⇒ 2→3 少算约 2.2 倍，
         * 而它只改"粗搜网格疏密"，不改任何判定口径。两个风险必须同批量：
         *   ① 粗网格变稀 ⇒ 粗峰值可能掉到阈值之下（`srv_retry` 那种"差一点"的模板最危险，
         *      代码里那段"未过阈值也精修峰值"的注释就是为它写的）；
         *   ② 位置数变少会**改变金字塔判据**（cost = 位置数×面积）⇒ 有的调用点可能从
         *      "走金字塔"翻成"全搜"，反而更慢。
         * ⚠️ 大字计场 / FIGHT 追踪 / 大保底 / 结算三槽走 `SettleLoop.findExact()`（FULL_COST
         *    + FULL_LEVEL=0）**钉死全搜**，不受 ② 影响；只有 escape 链里 roi=null 的
         *    `srv_ok`/`srv_retry` 受全局 `pyramid.cost` 管。本段用的是 win 帧。
         */
        System.out.println("\n== E. matcher.step 扫描：峰值分 / 耗时（★n = 命中判定与 step2 不同）==");
        int[] titleR0 = {400, 20, 480, 100};
        int[] btn0 = {265, 478, 220, 68};
        int[] okR0 = {480, 300, 320, 180};
        int[] sceneR0 = {600, 0, 560, 180};
        int[] modalR0 = {480, 0, 480, 280};
        int[] vsR0 = {1020, 0, 260, 130};
        int[] homeR0 = {0, 0, 180, 70};
        Object[][] sweep = {
                {"victory@title", "victory", titleR0, 0.72},
                {"defeat@title", "defeat", titleR0, 0.72},
                {"rematch@slot0", "btn_rematch", btn0, 0.72},
                {"continue@slot0", "btn_continue", btn0, 0.72},
                {"ok_btn", "ok_btn", okR0, 0.80},
                {"scene_x", "scene_x", sceneR0, 0.80},
                {"modal_x", "modal_x", modalR0, 0.90},
                {"vs_fight@roi", "vs_fight", vsR0, 0.75},
                {"home@map_check", "home", homeR0, 0.85},
                {"srv_ok@full", "srv_ok", null, 0.75},
                {"srv_retry@full", "srv_retry", null, 0.75},
        };
        final int[] stepList = {1, 2, 3, 4};
        double[] tot = new double[stepList.length];
        int stepBad = 0;
        double dev3 = 0, dev4 = 0;
        int nSites = 0;
        Vision.Frame[] swFrames = {fWin, fLose, f39, f00, f08};
        for (int fi = 0; fi < swFrames.length; fi++) {
            final Vision.Frame fSw = swFrames[fi];
            if (fSw == null) continue;
            boolean detail = fi == 0;
            if (detail) {
                System.out.printf("%-17s", "用例");
                for (int s : stepList) System.out.printf(" %12s", "step" + s);
                System.out.println();
            }
            for (Object[] cs : sweep) {
                final Vision.Frame t = tpl.get((String) cs[1]);
                if (t == null) continue;
                final int[] roi = (int[]) cs[2];
                final double th = (Double) cs[3];
                StringBuilder line = new StringBuilder();
                Boolean hitRef = null;
                boolean rowChanged = false;
                double pkRef = -2;
                for (int i = 0; i < stepList.length; i++) {
                    final int s = stepList[i];
                    setStep(s);
                    final double[] r = new double[3];
                    double m = best(3, () -> {
                        double[] x = Vision.findScored(fSw, t, roi, th);
                        r[0] = x == null ? -2 : x[2];
                    });
                    tot[i] += m;
                    boolean hit = r[0] >= th;
                    if (hitRef == null) hitRef = hit;
                    if (s == 2) pkRef = r[0];
                    if (s == 3 && Math.abs(r[0] - pkRef) > dev3) dev3 = Math.abs(r[0] - pkRef);
                    if (s == 4 && Math.abs(r[0] - pkRef) > dev4) dev4 = Math.abs(r[0] - pkRef);
                    if (s >= 3 && hit != hitRef) { stepBad++; rowChanged = true; }
                    if (detail) line.append(String.format(" %6.4f/%5.1f", r[0], m));
                }
                nSites++;
                if (detail) System.out.printf("%-17s%s   %s%n", cs[0], line,
                        rowChanged ? "★判定变了" : "");
            }
        }
        System.out.printf("%-17s", "耗时合计 ms");
        for (double v : tot) System.out.printf(" %12.1f", v);
        System.out.println();
        System.out.printf("%-17s", "相对 step2");
        for (double v : tot) System.out.printf(" %11.2fx", tot[1] / v);
        System.out.println();
        System.out.printf("  跨 %d 帧 x %d 调用点：step3 峰值最大偏移 %.4f，step4 %.4f，判定变化 %d 处%n",
                swFrames.length, nSites / Math.max(1, swFrames.length), dev3, dev4, stepBad);
        System.out.println(stepBad == 0
                ? "  → step 3/4 的命中判定与 step2 **完全一致**"
                : "  → **有 " + stepBad + " 处判定在 step≥3 变了** —— 那些调用点不能升 step");

        System.exit(bad == 0 ? 0 : 1);
    }
}
