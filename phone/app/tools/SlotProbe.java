import com.zhiyaunhe.sgmbot.Vision;
import com.zhiyaunhe.sgmbot.WorkPoint;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;

/**
 * 结算页按钮槽位打分 — **真机现场实验**工具 (纯 JVM, 不碰 android.graphics)。
 *
 * 为什么需要它 (2026-10-11 用户报障):
 *   「在有 rematch 的时候点到了 continue, 那就回大地图了」
 *
 *   SettleLoop.phase1 的判定是两步:
 *     rm = findSlot(work, btn_rematch, 三槽, th);
 *     if (rm 过阈值) 点 REMATCH;  else  ct = findSlot(work, btn_continue, 三槽, th);
 *                                       if (ct 过阈值) 点 CONTINUE;  // ← 点了就回大地图
 *
 *   而 findSlot **按槽序返回第一个过阈值的槽**, 不是全局最高分;
 *   btn_rematch / btn_continue / btn_quit 又是**同尺寸同形状**的圆角按钮 (188x36),
 *   只有颜色与文字不同 ⇒ NCC 跨按钮会给虚高分, 谁先过阈值谁被点。
 *
 *   由此,**"点错 CONTINUE"只有两条可能**, 且只有真机帧上的真实分数能区分:
 *     ① btn_rematch 在真身的 REMATCH 上没到阈值 (漏识别) ⇒ 回落去点 CONTINUE;
 *     ② 别的槽 (左=FAVORITE / 右=CONTINUE) 被 btn_rematch 抢先过阈值 ⇒ 点错位置。
 *   离线素材只有 20:9 一张 (2026-09-05), 无法回答"这台机器现在是什么分数" ——
 *   所以本工具要对 **adb screencap 抓的真机帧**跑, 不拿老图下结论。
 *
 * 输出三样:
 *   1. 模板 × 三槽 的 NCC 峰值矩阵 (步距 1 穷举, 拿真峰值, 不走 find 的步距2+refine);
 *   2. 每个模板在**整帧**上的最佳位置 (判断"ROI 框错了"还是"模板本身不匹配");
 *   3. 按 SettleLoop.phase1 的真实顺序**模拟判定**, 直接说明"代码会点哪个"。
 *   另把三个槽裁成 PNG 落盘 —— "槽里到底是哪个按钮"一眼可见。
 *   最后 (2026-10-11 加) 报**快速连点落点体检**: 落点坐标 + 是否落在 HUD 挡块里 +
 *   每个模板正对该点的分数。量的是 {@link WorkPoint#pick} 的返回值, 与实机同一个类。
 *
 * 用法 (仓库根):
 *   javac -encoding UTF-8 -nowarn -cp <android.jar> -d <out> \
 *         phone/app/src/com/zhiyaunhe/sgmbot/Vision.java \
 *         phone/app/src/com/zhiyaunhe/sgmbot/WorkPoint.java phone/app/tools/SlotProbe.java
 *   java -Dfile.encoding=UTF-8 -cp <out>;<android.jar> SlotProbe <帧.png|jpg> [模板目录] [th] [norot]
 *
 * 帧支持任意分辨率与朝向: 竖图先转正 (默认转, 传 norot 关掉), 再按高 576 缩放
 * —— 与 Vision.toWork 同一步 (真机 screencap 是 3200x1440, 必须缩)。
 */
public class SlotProbe {

    /** 与 config.btn_rois 同步 (槽序 = 左 中 右) */
    static final int[][] SLOTS = {
            {265, 478, 220, 68},
            {530, 478, 220, 68},
            {793, 478, 220, 68},
    };
    static final String[] SLOT_NAME = {"左", "中", "右"};

    /** 参与打分的模板: 前三个是"同款按钮", 后两个是大字 (顺带看结算页判得对不对) */
    static final String[] TPLS = {"btn_rematch", "btn_continue", "btn_quit", "victory", "defeat"};

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("用法: java SlotProbe <帧.png|jpg> [模板目录] [th] [norot]");
            return;
        }
        String framePath = args[0];
        String tplDir = args.length > 1 ? args[1] : "phone/app/assets/templates/";
        if (!tplDir.endsWith("/")) tplDir += "/";
        double th = args.length > 2 ? Double.parseDouble(args[2]) : 0.72;
        boolean rot = !(args.length > 3 && "norot".equalsIgnoreCase(args[3]));

        int[] wh = new int[2];
        int[] px = load(framePath, wh);
        int ow = wh[0], oh = wh[1];
        if (rot && oh > ow) {                       // 竖图 (screencap 原始朝向) 转正
            px = rot90(px, ow, oh);
            int t = ow; ow = oh; oh = t;
            System.out.println("(竖图已转正 " + wh[0] + "x" + wh[1] + " → " + ow + "x" + oh + ")");
        }
        Vision.Frame f = toWork(px, ow, oh);
        System.out.println("帧 " + new File(framePath).getName()
                + "  原始 " + wh[0] + "x" + wh[1] + " → work " + f.w + "x" + f.h);
        System.out.println("th = " + th + "  (config thresholds.min_sim)");
        System.out.println("槽位: "
                + SLOT_NAME[0] + "(" + join(SLOTS[0]) + ")  "
                + SLOT_NAME[1] + "(" + join(SLOTS[1]) + ")  "
                + SLOT_NAME[2] + "(" + join(SLOTS[2]) + ")");

        Vision.Frame[] tpl = new Vision.Frame[TPLS.length];
        for (int i = 0; i < TPLS.length; i++) {
            File tf = new File(tplDir + TPLS[i] + ".png");
            if (!tf.exists()) { System.out.println("[跳过] 缺模板 " + tf); continue; }
            int[] twh = new int[2];
            int[] tpx = load(tf.getPath(), twh);
            tpl[i] = new Vision.Frame(tpx, twh[0], twh[1]);
        }

        /* ---- 1. 模板 × 槽 峰值矩阵 (步距 1 真峰值) ---- */
        System.out.println("\n== NCC 峰值 (步距1 穷举; >" + th + " 记为命中) ==");
        System.out.printf("%-14s", "模板\\槽");
        for (int s = 0; s < SLOTS.length; s++) System.out.printf("%12s", SLOT_NAME[s] + "槽");
        System.out.printf("%12s%n", "全帧最佳");
        double[][] mat = new double[TPLS.length][SLOTS.length];
        for (int i = 0; i < TPLS.length; i++) {
            if (tpl[i] == null) continue;
            System.out.printf("%-14s", TPLS[i]);
            for (int s = 0; s < SLOTS.length; s++) {
                mat[i][s] = peak(f, tpl[i], SLOTS[s]);
                System.out.printf("%11.3f%s", mat[i][s], mat[i][s] >= th ? "*" : " ");
            }
            /* 全帧最佳: 区分"ROI 框错了"还是"模板本身配不上" */
            double[] best = peakXY(f, tpl[i]);
            System.out.printf("%11.3f%s", best[2], best[2] >= th ? "*" : " ");
            if (best[2] >= th) System.out.printf(" @(%d,%d)", (int) best[0], (int) best[1]);
            System.out.println();
        }
        System.out.println("(* = 过阈值; 全帧最佳带坐标, 若与某槽中心差很远 ⇒ ROI 框错, 不是模板问题)");

        /* ---- 2. 槽裁图: "槽里到底是哪个按钮" 一眼可见 ---- */
        File outDir = new File(new File(framePath).getParentFile(),
                new File(framePath).getName().replaceAll("\\.[^.]+$", "") + "_slots");
        if (outDir.mkdirs() || outDir.isDirectory()) {
            for (int s = 0; s < SLOTS.length; s++) {
                BufferedImage im = crop(f, SLOTS[s]);
                if (im == null) continue;
                File o = new File(outDir, SLOT_NAME[s] + "_" + SLOTS[s][0] + ".png");
                ImageIO.write(im, "png", o);
            }
            System.out.println("槽裁图 → " + outDir.getPath());
        }

        /* ---- 3. 模拟 SettleLoop.phase1 的真实判定顺序 ---- */
        System.out.println("\n== 模拟 SettleLoop.phase1 (先 REMATCH, 按槽序返回第一个过阈值) ==");
        double[] rm = findSlot(f, tpl[0], th);
        if (rm != null && rm[2] >= th) {
            System.out.println("  → 点 **REMATCH** @ (" + (int) rm[0] + "," + (int) rm[1] + ")  分数 " + fmt(rm[2]));
            System.out.println("    (落点 " + slotOf((int) rm[0], (int) rm[1]) + ")");
        } else {
            System.out.println("  REMATCH 未过阈值 (三槽最高 " + fmt(maxRow(mat, 0)) + ")");
            double[] ct = findSlot(f, tpl[1], th);
            if (ct != null && ct[2] >= th) {
                System.out.println("  → 点 **CONTINUE** @ (" + (int) ct[0] + "," + (int) ct[1] + ")  分数 " + fmt(ct[2]));
                System.out.println("    (落点 " + slotOf((int) ct[0], (int) ct[1]) + ")  ← 点这个就回大地图!");
                System.out.println("    ⇒ 复现用户报障: 画面明明有 REMATCH, 却点了 CONTINUE");
            } else {
                System.out.println("  CONTINUE 也未过阈值 (三槽最高 " + fmt(maxRow(mat, 1)) + ")");
                System.out.println("  → 本轮不点, noHitRun++ (连续 3 帧后回战斗监测)");
            }
        }
        double vs = peak(f, tpl[3], new int[]{400, 20, 480, 100});
        System.out.println("  顺带: victory 在 title_roi 峰值 " + fmt(vs)
                + (vs >= th ? "  (大字判得到)" : "  (<th — 大字也判不到!)"));

        /* ---- 4. 快速连点 (config.fast) 落点体检 ----
         * 落点 = btn_rois 中槽中心, 再绕开 HUD 挡块 (SettleLoop.resolveFastPoint →
         * WorkPoint.pick, **同一个类**, 所以这里报的就是实机要点的那个点)。
         * 把每个模板**正对该点**的 NCC 打出来 —— 盲点的就是这一格, 它是什么按钮一目了然。
         * 边界 1: 战斗帧上这一格应当是空的 (速度泡必须已被绕开)。
         * 边界 2: 结算帧上这一格应当落在中槽按钮上。 */
        List<int[]> avoid = WorkPoint.defaultAvoid();
        float[] fp = WorkPoint.pick(SLOTS[1], avoid, WorkPoint.DEFAULT_GAP);
        int fxpX = (int) fp[0], fxpY = (int) fp[1], fpState = (int) fp[2];
        System.out.println("\n== 快速连点缺省落点 (中槽中心 + 绕开挡块) ==");
        System.out.println("  中槽中心 = (" + (SLOTS[1][0] + SLOTS[1][2] / 2) + ","
                + (SLOTS[1][1] + SLOTS[1][3] / 2) + ")   →  实际落点 = ("
                + fxpX + "," + fxpY + ")  [" + WorkPoint.describe(fpState) + "]");
        System.out.println("  落在槽里?   " + slotOf(fxpX, fxpY));

        /* 4a. 速度泡体检 —— 这是本工具存在的理由 (用户报障「会点到 x3 那里」)。
         * 战斗帧上必须**不命中**这个落点; 命中就成了"又在点速度泡"。 */
        System.out.println("  -- 速度泡体检 (战斗界面的 1x/3x 气泡) --");
        String[] spd = {"battle_spd_1x", "battle_spd_3x"};
        for (String name : spd) {
            File sf = new File(tplDir + name + ".png");
            if (!sf.exists()) { System.out.println("     " + name + " 缺模板, 跳过"); continue; }
            int[] swh = new int[2];
            Vision.Frame st = new Vision.Frame(load(sf.getPath(), swh), swh[0], swh[1]);
            double[] b = peakXY(f, st);
            if (b[2] < th) { System.out.printf("     %-14s 不在画面 (最高 %.3f)%n", name, b[2]); continue; }
            int[] box = {(int) b[0], (int) b[1], st.w, st.h};
            System.out.printf("     %-14s %.3f @ 框 %s%n", name, b[2], join(box));
            for (int[] r : avoid)
                if (r[0] == box[0] && r[1] == box[1])
                    System.out.println("        ↑ 就是挡块 " + join(r) + " (config.fast.avoid)");
            System.out.println("        落点在里面? "
                    + (WorkPoint.inside(box, fxpX, fxpY) ? "**是 — 会点到速度泡!**" : "否 ✓"));
        }

        /* 4b. 结算页: 落点应当落在某个按钮的框里 (战斗中一个都不会命中, 属正常) */
        System.out.println("  -- 落点归属 (整帧最佳按钮框) --");
        for (int i = 0; i < 3; i++) {
            if (tpl[i] == null) continue;
            double[] b = peakXY(f, tpl[i]);
            if (b[2] < th) { System.out.printf("     %-14s 画面里没有 (最高 %.3f)%n", TPLS[i], b[2]); continue; }
            int[] box = {(int) b[0], (int) b[1], tpl[i].w, tpl[i].h};
            System.out.printf("     %-14s %.3f @ 框 %s   落点在里面? %s%n",
                    TPLS[i], b[2], join(box), WorkPoint.inside(box, fxpX, fxpY) ? "**是**" : "否");
        }
    }

    /* ---------------- 同口径原语 ---------------- */

    /** 复刻 SettleLoop.findSlot: 按槽序返回**第一个过阈值的槽** (不是全局最高分!) */
    static double[] findSlot(Vision.Frame f, Vision.Frame t, double th) {
        if (t == null) return null;
        double best = -2, bx = 0, by = 0;
        for (int[] r : SLOTS) {
            double[] sc = Vision.findScored(f, t, r, th);
            if (sc == null) continue;
            if (sc[2] > best) { best = sc[2]; bx = sc[0] + t.w / 2.0; by = sc[1] + t.h / 2.0; }
            if (sc[2] >= th) return new double[]{bx, by, best};
        }
        return best > -2 ? new double[]{bx, by, best} : null;
    }

    /** ROI 内穷举最高 NCC (步距 1 — 拿真峰值, 与 VisionProbe.peak 同一份口径) */
    static double peak(Vision.Frame f, Vision.Frame t, int[] roi) {
        int rx = Math.max(0, roi[0]), ry = Math.max(0, roi[1]);
        int rw = Math.min(f.w - rx, roi[2]), rh = Math.min(f.h - ry, roi[3]);
        if (rw < t.w || rh < t.h) return -2;
        double inv = 1.0 / (t.w * t.h);
        double tm = mean(t), tsd = sdch(t);
        if (tsd < 1e-6) return 0;
        double best = -2;
        for (int oy = ry; oy <= ry + rh - t.h; oy++)
            for (int ox = rx; ox <= rx + rw - t.w; ox++) {
                double s = ncc(f, ox, oy, t, tm, tsd, inv);
                if (s > best) best = s;
            }
        return best;
    }

    /** 整帧穷举最高 NCC + 位置 */
    static double[] peakXY(Vision.Frame f, Vision.Frame t) {
        double inv = 1.0 / (t.w * t.h);
        double tm = mean(t), tsd = sdch(t);
        double best = -2; int bx = 0, by = 0;
        if (tsd < 1e-6 || f.w < t.w || f.h < t.h) return new double[]{0, 0, 0};
        /* 整帧穷举很贵 (1280x576 x 模板面积), 步距 2 —— 这里只要"大致落在哪"用来
         * 判断 ROI 框错没错, 不需要 1 像素精度 (三槽里的分数才走 peak() 的步距 1) */
        for (int oy = 0; oy <= f.h - t.h; oy += 2)
            for (int ox = 0; ox <= f.w - t.w; ox += 2) {
                double s = ncc(f, ox, oy, t, tm, tsd, inv);
                if (s > best) { best = s; bx = ox; by = oy; }
            }
        return new double[]{bx, by, best};
    }

    static double ncc(Vision.Frame f, int ox, int oy, Vision.Frame t,
                      double tMean, double tSd, double inv) {
        double fm = 0;
        for (int y = 0; y < t.h; y++) {
            int row = (oy + y) * f.w + ox;
            for (int x = 0; x < t.w; x++) fm += f.px[row + x] & 0xFF;
        }
        fm *= inv;
        double num = 0, fv = 0;
        for (int y = 0; y < t.h; y++) {
            int row = (oy + y) * f.w + ox;
            int trow = y * t.w;
            for (int x = 0; x < t.w; x++) {
                double d = (f.px[row + x] & 0xFF) - fm;
                fv += d * d;
                num += d * ((t.px[trow + x] & 0xFF) - tMean);
            }
        }
        double den = Math.sqrt(fv) * tSd;
        return den < 1e-6 ? 0 : num / den;
    }

    static double mean(Vision.Frame t) {
        double m = 0;
        for (int p : t.px) m += p & 0xFF;
        return m / (t.w * t.h);
    }

    static double sdch(Vision.Frame t) {
        double m = mean(t), v = 0;
        for (int p : t.px) { double d = (p & 0xFF) - m; v += d * d; }
        return Math.sqrt(v);
    }

    /* ---------------- 图像 ---------------- */

    static int[] load(String p, int[] wh) throws Exception {
        BufferedImage im = ImageIO.read(new File(p));
        if (im == null) throw new Exception("无法解码 " + p);
        int w = im.getWidth(), h = im.getHeight();
        int[] px = new int[w * h];
        im.getRGB(0, 0, w, h, px, 0, w);
        wh[0] = w; wh[1] = h;
        return px;
    }

    static int[] rot90(int[] s, int w, int h) {
        int[] o = new int[w * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) o[x * h + (h - 1 - y)] = s[y * w + x];
        return o;
    }

    /** 缩到高 576 (双线性) — 与 Vision.toWork 同一步; 已经是 576 就直接用 */
    static Vision.Frame toWork(int[] src, int w, int h) {
        if (h == Vision.WORK_H) return new Vision.Frame(src, w, h);
        int nw = Math.max(1, Math.round(w * (float) Vision.WORK_H / h));
        int[] out = new int[nw * Vision.WORK_H];
        float sx = w / (float) nw, sy = h / (float) Vision.WORK_H;
        for (int y = 0; y < Vision.WORK_H; y++) {
            float fy = (y + 0.5f) * sy - 0.5f;
            int y0 = Math.max(0, (int) Math.floor(fy));
            int y1 = Math.min(h - 1, y0 + 1);
            float wy = Math.max(0, fy - y0);
            for (int x = 0; x < nw; x++) {
                float fx = (x + 0.5f) * sx - 0.5f;
                int x0 = Math.max(0, (int) Math.floor(fx));
                int x1 = Math.min(w - 1, x0 + 1);
                float wx = Math.max(0, fx - x0);
                out[y * nw + x] = mix(src[y0 * w + x0], src[y0 * w + x1],
                        src[y1 * w + x0], src[y1 * w + x1], wx, wy);
            }
        }
        return new Vision.Frame(out, nw, Vision.WORK_H);
    }

    static int mix(int a, int b, int c, int d, float wx, float wy) {
        int r = 0;
        for (int sh = 16; sh >= 0; sh -= 8) {
            float t = ((a >> sh) & 0xFF) * (1 - wx) + ((b >> sh) & 0xFF) * wx;
            float u = ((c >> sh) & 0xFF) * (1 - wx) + ((d >> sh) & 0xFF) * wx;
            int v = Math.round(t * (1 - wy) + u * wy);
            r |= Math.min(255, Math.max(0, v)) << sh;
        }
        return 0xFF000000 | r;
    }

    static BufferedImage crop(Vision.Frame f, int[] r) {
        int x = Math.max(0, r[0]), y = Math.max(0, r[1]);
        int w = Math.min(f.w - x, r[2]), h = Math.min(f.h - y, r[3]);
        if (w <= 0 || h <= 0) return null;
        BufferedImage im = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] px = new int[w * h];
        for (int j = 0; j < h; j++)
            System.arraycopy(f.px, (y + j) * f.w + x, px, j * w, w);
        im.setRGB(0, 0, w, h, px, 0, w);
        return im;
    }

    /* ---------------- 小工具 ---------------- */

    static String join(int[] a) { return a[0] + "," + a[1] + "," + a[2] + "," + a[3]; }
    static String fmt(double v) { return String.format("%.3f", v); }
    static double maxRow(double[][] m, int i) {
        double b = -2;
        for (double v : m[i]) if (v > b) b = v;
        return b;
    }
    static int argmax(double[] a) {
        int k = 0;
        for (int i = 1; i < a.length; i++) if (a[i] > a[k]) k = i;
        return k;
    }
    static String slotOf(int x, int y) {
        for (int s = 0; s < SLOTS.length; s++) {
            int[] r = SLOTS[s];
            if (x >= r[0] && x <= r[0] + r[2] && y >= r[1] && y <= r[1] + r[3])
                return SLOT_NAME[s] + "槽(" + join(r) + ")";
        }
        return "槽外(" + x + "," + y + ")";
    }
}
