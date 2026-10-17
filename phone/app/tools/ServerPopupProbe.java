package com.zhiyaunhe.sgmbot;

import com.zhiyaunhe.sgmbot.Vision;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;

/**
 * 服务器错误弹窗判据 (PfScene.serverPopup) 的正/反例验证 (2026-10-17)。
 *
 * <p>为什么要单独验: 这条判据是**纯色块**, 而它要拦的是"盖在任意界面之上的全局遮罩" ——
 * 误报 = 在正常界面上乱点 RETRY (最坏情况把一场战斗点掉), 漏报 = 又卡 6 小时。
 * 所以必须跑**真代码** (不是重写一份 Python), 反例要用真实 dump 帧。
 *
 * <p>用法: java ServerPopupProbe <正例.png> <反例目录...>
 */
public class ServerPopupProbe {
    public static void main(String[] a) throws Exception {
        String pos = a.length > 0 ? a[0] : "debug/tmp/now_popup.jpg";
        Vision.Frame f = load(pos);
        boolean hit = PfScene.serverPopup(f);
        int[] at = PfScene.serverRetryAt();
        System.out.printf("正例 %s -> serverPopup=%s  retryAt=(%d,%d)%n",
                new File(pos).getName(), hit, at[0], at[1]);
        if (!hit) { System.out.println("!! 正例漏报"); return; }

        int fp = 0, total = 0;
        for (int i = 1; i < a.length; i++) {
            File d = new File(a[i]);
            File[] fs = d.isDirectory() ? d.listFiles((x, n) -> n.endsWith(".png")
                    || n.endsWith(".jpg")) : new File[]{d};
            if (fs == null) continue;
            Arrays.sort(fs);
            for (File x : fs) {
                Vision.Frame v = load(x.getAbsolutePath());
                if (v == null || v.w != 1280 || v.h != 576) continue;
                total++;
                if (PfScene.serverPopup(v)) {
                    fp++;
                    System.out.println("!! 误报 " + x.getName());
                }
            }
        }
        System.out.printf("反例 %d 张, 误报 %d 张%n", total, fp);
    }

    static Vision.Frame load(String p) {
        try {
            BufferedImage im = ImageIO.read(new File(p));
            if (im == null) return null;
            int w = im.getWidth(), h = im.getHeight();
            int[] px = new int[w * h];
            im.getRGB(0, 0, w, h, px, 0, w);
            return new Vision.Frame(px, w, h);
        } catch (Exception e) { return null; }
    }
}
