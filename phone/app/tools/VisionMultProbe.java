import com.zhiyaunhe.sgmbot.Vision;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;

/**
 * 倍率模板匹配塌分复现 / 修复验证 (2026-10-17)。
 *
 * <p>塌分根因: {@code findScored(..., th=0, ...)} 内部是 **命中早退**, th=0 被解释成
 * "返回 ROI 里第一个相关性为正的补丁" ⇒ 拿到的分数与真峰值无关。三条独立证据钉住它:
 *   ① {@link Vision#scoreAt} 单点 = cv2 gtruth (证明 NCC/SAT/通道抽取都对)
 *   ② th=0 的结果与 cv2 无关 (证明是搜索策略不是算法)
 *   ③ 自贴图(th=0)拿不到 1.0000, 关掉早退就能拿到
 *
 * <p>用法:
 * <pre>
 *   java VisionMultProbe                                   # 单帧诊断 (内置路径)
 *   java VisionMultProbe <全帧.png> <模板.png>
 *   java VisionMultProbe --sweep <dump目录> <模板目录>       # 全量扫描 + 计时
 * </pre>
 */
public class VisionMultProbe {

    /** 三张候选卡的火框 ROI (2026-10-17 真机标定, 与 Pf.FIRE_SEARCH 一致) */
    static final int[][] FIRE = {{1180, 30, 100, 120}, {1180, 206, 100, 120}, {1180, 382, 100, 120}};

    static final String[] MULT_NAMES = {"mult_1", "mult_15", "mult_2", "mult_25",
            "mult_3", "mult_35", "mult_4", "mult_5"};
    static final double[] MULT_VALS = {1, 1.5, 2, 2.5, 3, 3.5, 4, 5};

    public static void main(String[] a) throws Exception {
        if (a.length > 0 && a[0].equals("--sweep")) {
            sweep(a.length > 1 ? a[1] : "debug/pf/dump_pull4",
                  a.length > 2 ? a[2] : "phone/app/assets/templates/pf");
            return;
        }
        single(a);
    }

    // ---------------- 单帧诊断 ----------------
    static void single(String[] a) throws Exception {
        String frameP = a.length > 0 ? a[0]
                : "debug/pf/dump_pull4/opp_002938_447_full_0001.png";
        String tplP = a.length > 1 ? a[1]
                : "phone/app/assets/templates/pf/mult_15.png";
        Vision.Frame f = load(frameP);
        Vision.Frame t = load(tplP);
        System.out.printf("frame %dx%d tpl %dx%d%n", f.w, f.h, t.w, t.h);

        int px = 1206, py = 221;                      // cv2 给的真峰值位置
        System.out.printf("scoreAt(%d,%d)     = %.4f   (cv2 gtruth 0.9311)%n",
                px, py, Vision.scoreAt(f, t, px, py));
        System.out.printf("naiveNcc(%d,%d)    = %.4f   (朴素两趟, 不走 SAT)%n",
                px, py, naiveNcc(f, t, px, py));

        int mn = 255, mx = 0, aMn = 255, aMx = 0;     // template alpha 必须全 255, 否则
        long s = 0;                                    // Android 预乘 alpha 会让像素值变
        for (int p : t.px) {
            int b = p & 0xFF, al = (p >>> 24) & 0xFF;
            mn = Math.min(mn, b); mx = Math.max(mx, b);
            aMn = Math.min(aMn, al); aMx = Math.max(aMx, al);
            s += b;
        }
        System.out.printf("tpl blue min=%d max=%d mean=%.2f | alpha min=%d max=%d%n",
                mn, mx, s / (double) t.px.length, aMn, aMx);

        int[][] rois = {FIRE[1], {0, 0, 1280, 576}};
        for (int[] roi : rois) {
            System.out.printf("roi[%d,%d,%d,%d]%n", roi[0], roi[1], roi[2], roi[3]);
            System.out.printf("  未修 (th=0, 早退)      = %s%n",
                    fmt(rawOld(f, t, roi)));
            System.out.printf("  SCAN_ALL step=2        = %s%n",
                    fmt(Vision.findScored(f, t, roi, Vision.SCAN_ALL, -1, 0, 2)));
            System.out.printf("  SCAN_ALL step=1        = %s   <- 采用%n",
                    fmt(Vision.findScored(f, t, roi, Vision.SCAN_ALL, -1, 0, 1)));
        }

        /* 自贴图: 贴完必须 **new 新 Frame** —— Frame 按 EPOCH 缓存 cv/sat,
         * 原地改 px 再复用会拿到旧积分图 (第一版就栽在这, 误判成内核 bug)。 */
        int qx = 1210, qy = 230;
        int[] p2 = new int[f.px.length];
        System.arraycopy(f.px, 0, p2, 0, p2.length);
        for (int y = 0; y < t.h; y++)
            System.arraycopy(t.px, y * t.w, p2, (qy + y) * f.w + qx, t.w);
        Vision.Frame f2 = new Vision.Frame(p2, f.w, f.h);
        System.out.printf("self-paste @(%d,%d) 未修  = %s%n", qx, qy,
                fmt(rawOld(f2, t, FIRE[1])));
        System.out.printf("self-paste @(%d,%d) 修复后 = %s   (期望 1.0000)%n", qx, qy,
                fmt(Vision.findScored(f2, t, FIRE[1], Vision.SCAN_ALL, -1, 0, 1)));
    }

    /** 复现修复前的行为: th=0 ⇒ 早退 (等价于打补丁前的 fullSearch) */
    static double[] rawOld(Vision.Frame f, Vision.Frame t, int[] roi) {
        int rx = Math.max(0, roi[0]), ry = Math.max(0, roi[1]);
        int rw = Math.min(f.w - rx, roi[2]), rh = Math.min(f.h - ry, roi[3]);
        if (rw < t.w || rh < t.h) return null;
        double best = -2, bx = rx, by = ry;
        for (int oy = ry; oy <= ry + rh - t.h; oy += 2)
            for (int ox = rx; ox <= rx + rw - t.w; ox += 2) {
                double s = Vision.scoreAt(f, t, ox, oy);
                if (s > best) { best = s; bx = ox; by = oy; }
                if (s >= 0) return new double[]{bx, by, best};   // ← 早退
            }
        return new double[]{bx, by, best};
    }

    // ---------------- 全量扫描 ----------------
    static void sweep(String dirP, String tplDir) throws Exception {
        File dir = new File(dirP);
        File[] fs = dir.listFiles((d, n) -> n.endsWith("_full_0001.png")
                || n.contains("_full_"));
        if (fs == null || fs.length == 0) { System.out.println("no frames in " + dirP); return; }
        Arrays.sort(fs);
        Vision.Frame[] tpl = new Vision.Frame[MULT_NAMES.length];
        int have = 0;
        for (int i = 0; i < MULT_NAMES.length; i++) {
            File p = new File(tplDir, MULT_NAMES[i] + ".png");
            if (!p.isFile()) continue;
            tpl[i] = load(p.getAbsolutePath());
            have++;
        }
        System.out.printf("frames=%d  templates=%d%n", fs.length, have);

        long t0 = System.currentTimeMillis();
        int ok = 0, nan = 0;
        for (File fp : fs) {
            Vision.Frame f = load(fp.getAbsolutePath());
            if (f.w != 1280 || f.h != 576) continue;
            StringBuilder sb = new StringBuilder();
            sb.append(fp.getName()).append("  ");
            for (int c = 0; c < FIRE.length; c++) {
                double best = 0; int bi = -1;
                for (int i = 0; i < tpl.length; i++) {
                    if (tpl[i] == null) continue;
                    double[] r = Vision.findScored(f, tpl[i], FIRE[c],
                            Vision.SCAN_ALL, -1, 0, 1);
                    if (r != null && r[2] > best) { best = r[2]; bi = i; }
                }
                if (best >= 0.70) { ok++; sb.append(String.format("卡%d:%sx%.4f  ", c + 1,
                        MULT_VALS[bi], best)); }
                else { nan++; sb.append(String.format("卡%d:?%.4f  ", c + 1, best)); }
            }
            System.out.println(sb);
        }
        long ms = System.currentTimeMillis() - t0;
        System.out.printf("done: 可读 %d / 不可读 %d, 总耗时 %d ms (%.1f ms/帧, 每帧 %d 次 ROI x %d 模板)%n",
                ok, nan, ms, ms / (double) fs.length, FIRE.length, have);
    }

    /** 朴素两趟 NCC (blue = 低字节, 与 Vision 默认口径一致) —— 判定是 SAT 错还是想法错 */
    static double naiveNcc(Vision.Frame f, Vision.Frame t, int ox, int oy) {
        int n = t.w * t.h;
        double[] tv = new double[n];
        for (int i = 0; i < n; i++) tv[i] = t.px[i] & 0xFF;
        double ts = 0, fs = 0;
        for (double v : tv) ts += v;
        for (int j = 0; j < t.h; j++)
            for (int i = 0; i < t.w; i++) fs += f.px[(oy + j) * f.w + ox + i] & 0xFF;
        double fm = fs / n, tm = ts / n;
        double num = 0, vf = 0, vt = 0;
        for (int j = 0; j < t.h; j++)
            for (int i = 0; i < t.w; i++) {
                double x = (f.px[(oy + j) * f.w + ox + i] & 0xFF) - fm;
                double y = tv[j * t.w + i] - tm;
                num += x * y; vf += x * x; vt += y * y;
            }
        double den = Math.sqrt(vf) * Math.sqrt(vt);
        return den < 1e-6 ? 0 : num / den;
    }

    static String fmt(double[] r) {
        return r == null ? "null"
                : String.format("x=%d y=%d score=%.4f", (int) r[0], (int) r[1], r[2]);
    }

    static Vision.Frame load(String p) throws Exception {
        BufferedImage im = ImageIO.read(new File(p));
        int w = im.getWidth(), h = im.getHeight();
        int[] px = new int[w * h];
        im.getRGB(0, 0, w, h, px, 0, w);
        return new Vision.Frame(px, w, h);
    }
}
