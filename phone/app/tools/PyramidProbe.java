import com.zhiyaunhe.sgmbot.Vision;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Random;
import org.json.JSONObject;

/**
 * 金字塔粗搜的可行性回归 —— 纯 JVM (JDK8), 不碰 android.graphics。
 *
 * 回答两个问题 (2026-10-11 为"srv_ok 全屏搜 32.7 秒导致弹窗逻辑等于没做"而建):
 *   1. **降采样保分**: 把模板原样贴进帧 (= 真命中, 1x 必为 1.000), 看 0.25x 粗搜
 *      还剩多少分 —— 剩得太少 ⇒ 粗搜阈值必须放宽, 否则真命中会被筛掉。
 *   2. **粗搜耗时**: 未命中 (无弹窗 = 常态) 时 0.25x 扫全场要多久。
 *
 * 用法 (仓库根或 phone/app 均可, 参数是模板目录):
 *   javac -cp <android.jar> -d <out> phone/app/src/.../Vision.java phone/app/tools/PyramidProbe.java
 *   java  -cp <out>:<json> PyramidProbe [模板目录]
 * 默认模板目录 phone/app/assets/templates。
 *
 * 判据:
 *   - 1x 命中分必须 ≈ 1.000 (贴图应满分; 低于它说明粗搜网格/精定位有问题)
 *   - 0.25x 命中分必须 >= 0.50 (= Vision.PYR_FLOOR), 否则粗搜会漏检真弹窗
 * 全过退出码 0, 有错 1。
 */
public class PyramidProbe {

    static int pass = 0, fail = 0;

    static void ck(String name, boolean ok, Object got) {
        if (ok) { pass++; System.out.println("  ok   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "  got=" + got); }
    }

    static Vision.Frame load(String p) throws Exception {
        BufferedImage im = ImageIO.read(new File(p));
        int w = im.getWidth(), h = im.getHeight();
        int[] px = new int[w * h];
        im.getRGB(0, 0, w, h, px, 0, w);
        return new Vision.Frame(px, w, h);
    }

    /** 噪声帧: 不命中场景的替身 (真实游戏画面峰值远低于阈值) */
    static Vision.Frame synth(int w, int h, long seed) {
        Random r = new Random(seed);
        int[] px = new int[w * h];
        for (int i = 0; i < px.length; i++) {
            int v = r.nextInt(200), g = (v * 7) % 200, b = (v * 13) % 200;
            px[i] = 0xFF000000 | (v << 16) | (g << 8) | b;
        }
        return new Vision.Frame(px, w, h);
    }

    /** 把 tpl 原样贴进 base 的 (x,y) —— 完全相同的图案, 原分辨率 NCC 应为 1.000 */
    static Vision.Frame embed(Vision.Frame base, Vision.Frame tpl, int x, int y) {
        int[] px = base.px.clone();
        for (int j = 0; j < tpl.h; j++)
            for (int i = 0; i < tpl.w; i++)
                px[(y + j) * base.w + (x + i)] = tpl.px[j * tpl.w + i];
        return new Vision.Frame(px, base.w, base.h);
    }

    static double peak(Vision.Frame f, Vision.Frame t) {
        double[] r = Vision.findScored(f, t, null, 0.75);
        return r == null ? -9 : r[2];
    }

    static double ms(Runnable r) {
        long t0 = System.nanoTime();
        r.run();
        return (System.nanoTime() - t0) / 1e6;
    }

    public static void main(String[] args) throws Exception {
        String dir = args.length > 0 ? args[0] : "phone/app/assets/templates/";
        if (!dir.endsWith("/")) dir += "/";
        Vision.configure(new JSONObject("{}"));

        /* 只测 escape 链里"全屏搜"的那几个 —— 有 ROI 的小模板本来就便宜, 不走金字塔 */
        String[] names = {"srv_ok", "srv_retry"};
        Vision.Frame base = synth(1280, 576, 11);

        System.out.println("== 降采样保分 (贴图进帧 = 真命中) ==");
        for (String n : names) {
            File f = new File(dir + n + ".png");
            if (!f.exists()) { System.out.println("  [skip] 缺 " + f); continue; }
            Vision.Frame t = load(dir + n + ".png");
            Vision.Frame hit = embed(base, t, (1280 - t.w) / 2, (576 - t.h) / 2);

            double p1 = peak(hit, t);                       // 走金字塔 → 粗搜+精修
            double p4 = peak(hit.scale(0.25), t.scale(0.25)); // 纯粗搜 (无精修) = 最坏情况
            ck(n + ": 1x hit = 1.000", p1 >= 0.999, p1);
            ck(n + ": 0.25x hit >= floor(0.50)", p4 >= 0.50, String.format("%.3f", p4));
        }

        System.out.println("== 未命中耗时 (无弹窗 = 常态) ==");
        for (String n : names) {
            File f = new File(dir + n + ".png");
            if (!f.exists()) continue;
            final Vision.Frame t = load(dir + n + ".png");
            final Vision.Frame miss = synth(1280, 576, 5);
            double m = ms(() -> peak(miss, t));
            System.out.println(String.format("  %-10s %.0f ms", n, m));
            ck(n + ": 未命中 < 1500ms", m < 1500, String.format("%.0f ms", m));
        }

        System.out.println("== " + pass + " passed, " + fail + " failed ==");
        System.exit(fail == 0 ? 0 : 1);
    }
}
