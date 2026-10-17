package com.zhiyaunhe.sgmbot;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * 数字带取带 + 匹配的**真代码**验证 (2026-10-17)。
 *
 * <p>为什么不用 Python 复刻: 本会话已经栽过一次 —— 用 Python 复刻判据验证"通过"，
 * 但 App 内真代码有别的 bug (PfScene 那次、Vision th=0 那次)。所以这里直接调
 * {@link PfTeam#findFireBadge} / {@link PfTeam#bandWindow} / {@link Vision#findScored},
 * 并把每个卡位取出来的带子**存成 PNG** 肉眼核对。</p>
 *
 * <p>用法: java com.zhiyaunhe.sgmbot.BandProbe &lt;帧.png&gt; [模板目录] [输出目录]</p>
 */
public class BandProbe {

    public static void main(String[] a) throws Exception {
        String frameP = a.length > 0 ? a[0] : "debug/pf/dump_pull6/pf/opp_056113_085_full_0095.png";
        String tplDir = a.length > 1 ? a[1] : "phone/app/assets/templates/pf";
        String outDir = a.length > 2 ? a[2] : "debug/tmp/band_probe";
        new File(outDir).mkdirs();

        Vision.Frame f = load(frameP);
        System.out.printf("frame %dx%d%n", f.w, f.h);

        String[] names = {"band_15", "band_15b", "band_15c", "band_15d",
                "band_2", "band_25", "band_25b",
                "band_3", "band_3b", "band_35", "band_35b", "band_4", "band_45"};
        double[] vals = {1.5, 1.5, 1.5, 1.5, 2, 2.5, 2.5, 3, 3, 3.5, 3.5, 4, 4.5};

        for (int pos = 0; pos < 3; pos++) {
            int[] roi = Pf.FIRE_SEARCH[pos];
            int[] fb = PfTeam.findFireBadge(f, roi);
            System.out.printf("卡%d 火焰框=%s%n", pos + 1,
                    fb == null ? "null" : String.format("[%d,%d..%d,%d] %dx%d",
                            fb[0], fb[1], fb[2], fb[3], fb[2] - fb[0], fb[3] - fb[1]));
            if (fb == null) continue;
            Vision.Frame band = PfTeam.bandWindow(f, fb);
            if (band == null) { System.out.println("   取带失败 (白字太小?)"); continue; }
            System.out.printf("   归一带 %dx%d%n", band.w, band.h);
            save(band, outDir + "/band_p" + (pos + 1) + ".png", 1);
            Vision.Frame padded = PfTeam.pad(band, 3);
            java.util.LinkedHashMap<Double, Double> byTier = new java.util.LinkedHashMap<>();
            for (int i = 0; i < names.length; i++) {
                Vision.Frame t = load(tplDir + "/" + names[i] + ".png");
                double[] r = Vision.findScored(padded, t, null, Vision.SCAN_ALL, -1, 0, 1);
                double s = r == null ? -1 : r[2];
                Double prev = byTier.get(vals[i]);
                if (prev == null || s > prev) byTier.put(vals[i], s);
            }
            /* 与 App 同口径: 按档合并后取最高/次高 (App 的领先幅度门就是拿这两个比) */
            double b1 = 0, b2 = 0, v1 = 0, v2 = 0;
            for (java.util.Map.Entry<Double, Double> e : byTier.entrySet()) {
                double s = e.getValue();
                if (s > b1) { b2 = b1; v2 = v1; b1 = s; v1 = e.getKey(); }
                else if (s > b2) { b2 = s; v2 = e.getKey(); }
            }
            StringBuilder sb = new StringBuilder();
            for (java.util.Map.Entry<Double, Double> e : byTier.entrySet())
                sb.append("x").append(e.getKey()).append("=")
                  .append(String.format("%.3f", e.getValue())).append(' ');
            System.out.printf("   [档] %s%n", sb);
            System.out.printf("   => 最高 x%.1f=%.3f  次高 x%.1f=%.3f  领先=%.3f%n",
                    v1, b1, v2, b2, b1 - b2);
        }
    }

    /** 放大 s 倍存 PNG (看清字形) */
    static void save(Vision.Frame f, String path, int s) throws Exception {
        BufferedImage im = new BufferedImage(f.w * s, f.h * s, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < f.h * s; y++)
            for (int x = 0; x < f.w * s; x++)
                im.setRGB(x, y, f.px[(y / s) * f.w + (x / s)]);
        ImageIO.write(im, "png", new File(path));
    }

    static Vision.Frame load(String p) throws Exception {
        BufferedImage im = ImageIO.read(new File(p));
        int w = im.getWidth(), h = im.getHeight();
        int[] px = new int[w * h];
        im.getRGB(0, 0, w, h, px, 0, w);
        return new Vision.Frame(px, w, h);
    }
}
