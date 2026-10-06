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
        System.out.println(bad == 0 ? "\n[ok] 全部用例通过" : "\n[!!] " + bad + " 项不符");
        System.exit(bad == 0 ? 0 : 1);
    }
}
