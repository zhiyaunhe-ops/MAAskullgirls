package com.zhiyaunhe.sgmbot;

import android.graphics.Bitmap;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 临时截图 hook (2026-10-16) —— 「选队界面一个一个采集」用的样本落盘器。
 *
 * <h3>为什么要有它</h3>
 * 选对手页的倍率徽章 (x1 / x1.5 / x2 / x2.5 / x3 …) 现在只有 {@code mult_25} 一个模板,
 * 其余档位全走「缺模板自动跳过」的兜底 ⇒ 倍率比较逻辑基本没生效。补模板需要各档位的
 * 真机样本, 而哪一档会出现纯看随机 —— 只能让 bot 跑的时候**顺手把每一帧选对手页存下来**,
 * 事后离线聚类 + OCR 标注档位, 再裁成 {@code mult_<档位>.png}。
 *
 * <h3>开关 (三处汇合, 优先级: HTTP 覆盖 config)</h3>
 * <ul>
 *   <li>config.json {@code "pf": {"dump_opp": true}} —— 跟着 {@link PfTeam#configure} 读</li>
 *   <li>HTTP {@code GET /dump?on=1|0} —— 运行中随时切 (WebUI 的采集开关就是它)</li>
 * </ul>
 *
 * <h3>落什么</h3>
 * 每次选对手: 整帧 work PNG (1280x576, 无损, 事后想裁哪都能裁) + 3 张火框 ROI 裁剪
 * (免得每次都手动对坐标)。存 {@code <root>/dump/pf/opp_<时间戳>_<tag>.png}。
 *
 * <p>⚠️ 上限 {@link #MAX} 张, 顶了就静默停 —— 这是临时 hook, 不能把磁盘写爆;
 * 拉走样本后把目录删掉即可 ({@code adb shell rm -rf /sdcard/sgm_settle/dump})。
 */
public final class PfDump {
    private PfDump() { }

    /** 最多落多少组样本 (一组 = 1 整帧 + 3 裁剪 ≈ 1MB)。400 组 ≈ 400 场, 足够聚类。 */
    public static final int MAX = 400;

    private static volatile boolean on;
    private static volatile int count;

    public static boolean enabled() { return on; }
    public static int count() { return count; }
    public static String dir() { return Paths.dumpDir() + "pf/"; }

    public static void setEnabled(boolean b) {
        if (on == b) return;
        on = b;
        SgmLog.i("dump", (b ? "开启" : "关闭") + "选对手页采集 -> " + dir()
                + " (已落 " + count + " 组, 上限 " + MAX + ")");
    }

    /**
     * 选对手页整帧 + 火框 ROI 裁剪。关着 / 顶了上限时零开销直接返回
     * (不截屏不落盘 —— bot 主循环不该为它多付一帧)。
     */
    public static void oppPage(Vision.Frame work) {
        if (!on || work == null) return;
        int c;
        synchronized (PfDump.class) {
            if (count >= MAX) return;
            count++;
            c = count;
        }
        String ts = stamp();
        save(work, ts, "full" + pad(c));
        /* 火框 ROI 裁剪 —— 与 PfTeam.readMult 用的是同一组坐标, 裁出来的就是判据看到的视野。 */
        for (int i = 0; i < 3 && i < Pf.FIRE_SEARCH.length; i++) {
            int[] r = Pf.FIRE_SEARCH[i];
            save(crop(work, r), ts, "fire" + (i + 1) + pad(c));
        }
        if (c == 1 || c % 25 == 0)
            SgmLog.i("dump", "已采集 " + c + "/" + MAX + " 组 -> " + dir());
    }

    private static String stamp() {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("HHmmss_SSS");
        return f.format(new java.util.Date());
    }

    private static String pad(int c) {
        StringBuilder sb = new StringBuilder("_");
        String s = Integer.toString(c);
        for (int i = s.length(); i < 4; i++) sb.append('0');
        return sb.append(s).toString();
    }

    private static Vision.Frame crop(Vision.Frame f, int[] r) {
        int x = Math.max(0, r[0]), y = Math.max(0, r[1]);
        int w = Math.min(f.w - x, r[2]), h = Math.min(f.h - y, r[3]);
        if (w <= 0 || h <= 0) return f;      // 越界兜底: 整帧将就 (不该发生)
        int[] px = new int[w * h];
        for (int row = 0; row < h; row++) {
            System.arraycopy(f.px, (y + row) * f.w + x, px, row * w, w);
        }
        return new Vision.Frame(px, w, h);
    }

    private static void save(Vision.Frame f, String ts, String tag) {
        try {
            File d = new File(dir());
            if (!d.exists()) d.mkdirs();
            Bitmap b = Bitmap.createBitmap(f.px, f.w, f.h, Bitmap.Config.ARGB_8888);
            FileOutputStream os = new FileOutputStream(new File(d, "opp_" + ts + "_" + tag + ".png"));
            b.compress(Bitmap.CompressFormat.PNG, 100, os);
            os.close();
            b.recycle();
        } catch (Exception e) {
            SgmLog.i("dump", "落盘失败 (" + tag + "): " + e);
        }
    }
}
