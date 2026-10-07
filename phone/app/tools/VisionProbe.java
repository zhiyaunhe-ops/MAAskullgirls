import com.zhiyaunhe.sgmbot.Roi;
import com.zhiyaunhe.sgmbot.Vision;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Vision NCC 口径的离线回归验证 — 不碰 android.graphics, 纯 JVM 跑 (JDK8 即可)。
 *
 * 用 make_templates.py 的原始素材 (手机真机 1280x576 结算页截图) 回答两个问题:
 *   1. 取通道口径: Vision 用 v & 0xFF = ARGB 低字节 = **蓝通道**, 不是灰度。
 *      本工具用真机数据证明蓝通道是对的 (REMATCH 橙按钮跨帧 0.95+), 灰度口径
 *      (Rec601, Python match.py 的 to_gray) 会漏识别 (0.65~0.70 < 0.72) ——
 *      所以别把 & 0xFF "纠正"成灰度。两种口径同表输出, 结论一目了然。
 *   2. 端到端: find()(步距2+refine) 在 0.72 下命中位正确 (按钮在槽内居中)。
 *
 * 用法 (仓库根):
 *   javac -cp <android.jar> -d <out> phone/app/src/com/zhiyaunhe/sgmbot/Vision.java \
 *         phone/app/tools/VisionProbe.java
 *   java  -cp <out>:<android.jar> VisionProbe [素材目录] [模板目录]
 * 默认素材 D:/Downloads/Telegram Desktop (make_templates.py 的 SRC),
 * 默认模板 phone/app/assets/templates。素材缺了就跳过对应用例 (工具不炸)。
 * 用例全对退出码 0, 有错 1 — 可挂 CI。
 */
public class VisionProbe {
    static int[] load(String p, int[] wh) throws Exception {
        BufferedImage im = ImageIO.read(new File(p));
        if (im == null) throw new Exception("无法解码 " + p);
        int w = im.getWidth(), h = im.getHeight();
        int[] px = new int[w * h];
        im.getRGB(0, 0, w, h, px, 0, w);
        wh[0] = w; wh[1] = h;
        return px;
    }

    /** Rec601 灰度 (cv2 BGR2GRAY 同权重), 打进蓝字节 → Vision 的 &0xFF 读到的就是灰度 */
    static int[] toGrayPx(int[] px) {
        int[] out = new int[px.length];
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
            int y = (r * 77 + g * 151 + b * 28) >> 8;
            out[i] = 0xFF000000 | y;
        }
        return out;
    }

    /** ROI 内穷举最高 NCC (步距1, 拿"真峰值", 不走 find 的步距2+refine) */
    static double peak(Vision.Frame f, Vision.Frame t, int[] roi) {
        double best = -2;
        int rx = Math.max(0, roi[0]), ry = Math.max(0, roi[1]);
        int rw = Math.min(f.w - rx, roi[2]), rh = Math.min(f.h - ry, roi[3]);
        if (rw < t.w || rh < t.h) return best;
        double inv = 1.0 / (t.w * t.h);
        double tm = 0;
        for (int v : t.px) tm += v & 0xFF;
        tm *= inv;
        double tv = 0;
        for (int v : t.px) { double d = (v & 0xFF) - tm; tv += d * d; }
        double tsd = Math.sqrt(tv);
        for (int oy = ry; oy <= ry + rh - t.h; oy++)
            for (int ox = rx; ox <= rx + rw - t.w; ox++) {
                double fm = 0;
                for (int y = 0; y < t.h; y++) {
                    int row = (oy + y) * f.w + ox;
                    for (int x = 0; x < t.w; x++) fm += f.px[row + x] & 0xFF;
                }
                fm *= inv;
                double num = 0, fv = 0;
                for (int y = 0; y < t.h; y++) {
                    int row = (oy + y) * f.w + ox;
                    for (int x = 0; x < t.w; x++) {
                        double d = (f.px[row + x] & 0xFF) - fm;
                        fv += d * d;
                        num += d * ((t.px[y * t.w + x] & 0xFF) - tm);
                    }
                }
                double den = Math.sqrt(fv) * tsd;
                double s = den < 1e-6 ? 0 : num / den;
                if (s > best) best = s;
            }
        return best;
    }

    static final double TH = 0.72;   // 与 config thresholds.min_sim 同步改

    /* ==================== 多分辨率 ROI 映射回归 ====================
     *
     * 真机只有 20:9 一张素材, 别的比例只能**合成**: 按"设备实际怎么排版"把
     * 1280x576 基准帧重排成目标宽度, 再用 Roi 的映射公式反推 ROI、缩放模板去匹配。
     *   center → 元素尺寸不变, 中心裁/补 (两侧黑边或裁掉边缘)
     *   rel    → 整帧横向拉伸铺满
     *   fit    → 内容保 20:9 居中, 其余黑边 (letterbox/pillarbox, 最可能的真实排版)
     *
     * 自洽性 (对角线) 过了 = 映射公式没写错; 但**哪一种是这台机器的真实排版,
     * 离线证明不了** —— 要拿一张该分辨率的真机截图, 看哪个模式命中 (日志会打
     * "屏=WxH 档=… mode=…")。所以这张表是"公式对不对"的回归, 不是"设备用哪个"的答案。
     */
    static Vision.Frame synth(Vision.Frame f, int W, String kind) {
        int H = f.h;
        double kx = 1, ky = 1, ox = 0, oy = 0;
        if ("center".equals(kind)) {
            ox = (W - f.w) / 2.0;                       // 元素尺寸不变, 只平移 (多退少补黑边)
        } else if ("rel".equals(kind)) {
            kx = W / (double) f.w;                      // 横向拉伸铺满
        } else if ("fit".equals(kind)) {
            kx = ky = Roi.fitScale(W, H);               // 内容保 20:9 居中
            ox = Roi.fitOffX(W, H); oy = Roi.fitOffY(W, H);
        }
        int[] out = new int[W * H];
        for (int i = 0; i < out.length; i++) out[i] = 0xFF000000;   // 黑底 (= 游戏的黑边)
        for (int y = 0; y < H; y++) {
            double a0 = (y - oy) / ky, a1 = (y + 1 - oy) / ky;
            int y0 = (int) Math.floor(a0), y1 = (int) Math.ceil(a1) - 1;
            for (int x = 0; x < W; x++) {
                double b0 = (x - ox) / kx, b1 = (x + 1 - ox) / kx;
                int x0 = (int) Math.floor(b0), x1 = (int) Math.ceil(b1) - 1;
                if (x1 < 0 || x0 >= f.w || y1 < 0 || y0 >= f.h) continue;      // 落在黑边区
                int cx0 = Math.max(0, x0), cx1 = Math.min(f.w - 1, x1);
                int cy0 = Math.max(0, y0), cy1 = Math.min(f.h - 1, y1);
                long r = 0, g = 0, b = 0, n = 0;
                for (int sy = cy0; sy <= cy1; sy++)
                    for (int sx = cx0; sx <= cx1; sx++) {
                        int p = f.px[sy * f.w + sx];
                        r += (p >> 16) & 0xFF; g += (p >> 8) & 0xFF; b += p & 0xFF; n++;
                    }
                out[y * W + x] = 0xFF000000
                        | ((int) (r / n) << 16) | ((int) (g / n) << 8) | (int) (b / n);
            }
        }
        return new Vision.Frame(out, W, H);
    }

    /** 用 mode 的映射 + 缩放模板在 frame 里找 tpl; 返回 {峰值, 是否命中且在 ROI 内} */
    static double[] tryMode(Vision.Frame f, Vision.Frame t0, int[] roi, int W, String mode) {
        int[] r = Roi.resolveMode(mode, roi, W, f.h);
        double sx = Roi.scaleXOf(mode, W, f.h), sy = Roi.scaleYOf(mode, W, f.h);
        Vision.Frame t = t0.scaleXY(sx, sy);
        if (t.w > r[2] || t.h > r[3]) return new double[]{-2, 0};  // 缩放后 ROI 装不下模板
        double[] sc = Vision.findScored(f, t, r, TH);
        int[] hit = sc != null && sc[2] >= TH ? new int[]{(int) sc[0], (int) sc[1]} : null;
        boolean in = hit != null && hit[0] >= r[0] && hit[0] <= r[0] + r[2] - t.w
                && hit[1] >= r[1] && hit[1] <= r[1] + r[3] - t.h;
        return new double[]{sc == null ? -2 : sc[2], in ? 1 : 0};
    }

    public static void main(String[] args) throws Exception {
        String src = args.length > 0 ? args[0] : "D:/Downloads/Telegram Desktop/";
        String tplDir = args.length > 1 ? args[1] : "phone/app/assets/templates/";
        String[][] frames = {
                {"win", src + "photo_2026-09-05_17-34-56.jpg"},    // VICTORY 结算页 (REMATCH 中槽)
                {"lose", src + "photo_2026-09-05_17-34-49.jpg"},   // DEFEAT 结算页 (REMATCH 右槽)
                {"reward2", src + "photo_2026-09-05_17-35-00.jpg"}, // 奖励页 2 卡 (CONTINUE 中槽)
                {"reward3", src + "photo_2026-09-05_17-34-39.jpg"}, // 奖励页 3 卡 (CONTINUE 右槽)
        };
        // (帧, 模板, ROI, 应命中) — ROI 与裁剪原点来自 make_templates.py + settle_bot.js
        Object[][] cases = {
                {"win", "victory", new int[]{400, 20, 480, 100}, Boolean.TRUE},
                {"win", "btn_rematch", new int[]{530, 478, 220, 68}, Boolean.TRUE},
                {"lose", "defeat", new int[]{400, 20, 480, 100}, Boolean.TRUE},
                {"lose", "btn_rematch", new int[]{793, 478, 220, 68}, Boolean.TRUE},
                {"reward2", "victory", new int[]{400, 20, 480, 100}, Boolean.TRUE},
                {"reward2", "btn_continue", new int[]{530, 478, 220, 68}, Boolean.TRUE},
                {"reward3", "btn_continue", new int[]{793, 478, 220, 68}, Boolean.TRUE},
                {"win", "btn_continue", new int[]{530, 478, 220, 68}, Boolean.FALSE},
                {"lose", "victory", new int[]{400, 20, 480, 100}, Boolean.FALSE},
        };
        int[] wh = new int[2];
        Map<String, Vision.Frame> frB = new HashMap<>();
        Map<String, Vision.Frame> frG = new HashMap<>();
        for (String[] f : frames) {
            if (!new File(f[1]).exists()) {
                System.out.println("[跳过] 缺素材 " + f[1]);
                continue;
            }
            int[] px = load(f[1], wh);
            frB.put(f[0], new Vision.Frame(px, wh[0], wh[1]));
            frG.put(f[0], new Vision.Frame(toGrayPx(px), wh[0], wh[1]));
        }
        Map<String, Vision.Frame> tpl = new HashMap<>();
        for (String n : new String[]{"victory", "defeat", "btn_rematch", "btn_continue"}) {
            int[] px = load(tplDir + n + ".png", wh);
            tpl.put(n, new Vision.Frame(px, wh[0], wh[1]));
        }
        System.out.printf("%-9s %-13s %-5s | %7s | %7s | %s%n",
                "frame", "tpl", "期望", "gray", "blue", "判定(0.72)");
        int bad = 0;
        for (Object[] c : cases) {
            String fn = (String) c[0], tn = (String) c[1];
            if (!frB.containsKey(fn)) continue;
            int[] roi = (int[]) c[2];
            boolean expect = (Boolean) c[3];
            double g = peak(frG.get(fn), tpl.get(tn), roi);
            double b = peak(frB.get(fn), tpl.get(tn), roi);
            boolean gOk = (g >= TH) == expect, bOk = (b >= TH) == expect;
            // 回归判据只看 blue 列 (Vision 现行口径); gray 列是口径对照 — 它在
            // REMATCH 上漏识别 (0.65~0.70) 正是"别改成灰度"的证据, 不算失败
            if (!bOk) bad++;
            String verd = !bOk ? "blue错!" : gOk ? "OK" : "OK (gray会漏:" + String.format("%.3f", g) + ")";
            System.out.printf("%-9s %-13s %-5s | %7.3f | %7.3f | %s%n",
                    fn, tn, expect ? "HIT" : "MISS", g, b, verd);
        }
        System.out.println("\n== Vision.find 端到端 (blue 口径, th=" + TH + ") ==");
        for (Object[] c : cases) {
            if (!(Boolean) c[3]) continue;                  // 反例只考 peak
            String fn = (String) c[0], tn = (String) c[1];
            if (!frB.containsKey(fn)) continue;
            int[] roi = (int[]) c[2];
            int[] hit = Vision.find(frB.get(fn), tpl.get(tn), roi, TH);
            boolean in = hit != null
                    && hit[0] >= roi[0] && hit[0] <= roi[0] + roi[2] - tpl.get(tn).w
                    && hit[1] >= roi[1] && hit[1] <= roi[1] + roi[3] - tpl.get(tn).h;
            if (!in) bad++;
            System.out.println(fn + "/" + tn + " → "
                    + (hit == null ? "null (漏识别!)" : hit[0] + "," + hit[1] + (in ? " 在槽内" : " 越界!")));
        }
        /* ---- 多分辨率 ---- */
        int[] widths = {768, 1024, 1280, 1344};
        String[] wname = {"4:3 平板", "16:9 模拟器", "20:9 真机(基准)", "21:9 带鱼"};
        String[] modes = {"center", "rel", "fit", "abs"};
        // 三个"必须命中"的用例 (帧, 模板, 基准 ROI)
        Object[][] mc = {
                {"lose", "btn_rematch", new int[]{793, 478, 220, 68}},
                {"win", "btn_rematch", new int[]{530, 478, 220, 68}},
                {"lose", "defeat", new int[]{400, 20, 480, 100}},
        };
        System.out.println("\n== 多分辨率: 自洽性 (合成方式 = 映射模式, 应 3/3 命中) ==");
        System.out.printf("%-16s %-7s | %s%n", "设备", "work宽", "center  rel   fit    abs");
        for (int i = 0; i < widths.length; i++) {
            int W = widths[i];
            StringBuilder row = new StringBuilder();
            for (String mode : modes) {
                int ok = 0, tot = 0;
                StringBuilder miss = new StringBuilder();
                for (Object[] c : mc) {
                    if (!frB.containsKey((String) c[0])) continue;
                    tot++;
                    Vision.Frame sf = synth(frB.get((String) c[0]), W, mode);
                    double[] rr = tryMode(sf, tpl.get((String) c[1]), (int[]) c[2], W, mode);
                    if (rr[1] > 0) ok++;
                    else miss.append(' ').append(c[0]).append('/').append(c[1]).append('(').append(String.format("%.2f", rr[0])).append(')');
                }
                // abs 只在基准宽有意义 (别的宽度本来就是它要解决的病), 不判失败
                boolean req = tot > 0 && !("abs".equals(mode) && W != 1280);
                if (req && ok != tot) {
                    bad++;
                    System.out.println("   [!!] " + wname[i] + " mode=" + mode + " 未命中:"
                            + miss + " (合成=" + mode + ")");
                }
                row.append(String.format("%-7s", ok + "/" + tot + (req && ok != tot ? "✗" : " ")));
            }
            System.out.printf("%-16s %-7d | %s%n", wname[i], W, row);
        }
        System.out.println("\n== 多分辨率: 交叉对照 (设备按 fit/letterbox 排版, 各模式能否救回) ==");
        System.out.printf("%-16s %-7s | %s%n", "设备", "work宽", "center  rel   fit    abs");
        for (int i = 0; i < widths.length; i++) {
            int W = widths[i];
            StringBuilder row = new StringBuilder();
            for (String mode : modes) {
                int ok = 0, tot = 0;
                for (Object[] c : mc) {
                    if (!frB.containsKey((String) c[0])) continue;
                    tot++;
                    Vision.Frame sf = synth(frB.get((String) c[0]), W, "fit");
                    if (tryMode(sf, tpl.get((String) c[1]), (int[]) c[2], W, mode)[1] > 0) ok++;
                }
                row.append(String.format("%-7s", ok + "/" + tot));
            }
            System.out.printf("%-16s %-7d | %s%n", wname[i], W, row);
        }
        System.out.println("(fit 是最可能的真实排版: 游戏保 20:9 出图, 别的比例屏留黑边)");

        System.out.println(bad == 0 ? "\n[ok] 全部用例通过" : "\n[!!] " + bad + " 项不符");
        System.exit(bad == 0 ? 0 : 1);
    }
}
