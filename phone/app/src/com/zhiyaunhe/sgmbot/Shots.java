package com.zhiyaunhe.sgmbot;

import android.graphics.Bitmap;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 「带图日志」的图片仓库 —— 日志行里挂一张缩小 JPEG, 总量封顶 (默认 2 GiB) 滚动清理。
 *
 * <h3>为什么要有它 (2026-10-17 用户)</h3>
 * 「你可以把截图关掉吧, log 里也带图吧, 控制大小在 2G 以内方便调试」。
 * 原来的截图是 {@link PfDump}: **每个选对手页**存 1 张整帧 + 3 张火框裁剪 (PNG),
 * 一小时能堆几百 MB 而且跟日志是两套东西 —— 想对某条日志看当时画面, 还得按时间戳去
 * 目录里翻。现在改成: **日志行自带图片引用**, 只在关键事件存 (界面切换/判据失败/选对手
 * 结果/看门狗…), 存的是缩小 JPEG (省一个数量级), 老图按总量自动删。
 *
 * <h3>口径</h3>
 * <ul>
 *   <li>路径: {@code <root>/logs/shots/YYYY-MM-DD/HHmmss_SSS_<tag>.jpg} ——
 *       天目录 + 时间戳文件名的字典序 = 时间序, 清理时直接按名删。</li>
 *   <li>尺寸: 缩到宽 {@code log.shots_w} (默认 640, 高按比例) → 640x288,
 *       JPEG 质量 {@code log.shots_quality} (默认 60) ≈ 20~35 KB/张。</li>
 *   <li>总量: {@code log.shots_max_gb} (默认 2 GiB)。超过就**从最旧的删**, 删到 90%
 *       以下停手 (留余量, 免得每存一张就删一张)。</li>
 * </ul>
 *
 * ⚠️ 统计是**懒初始化 + 增量维护**: 第一次用时扫一遍目录拿总量, 之后每存一张加一次;
 *    外部 (adb/别的进程) 删过文件就会偏大 —— 那时候总量偏大只会**多删一点**, 不会写爆盘。
 */
final class Shots {

    private static volatile boolean on = true;
    private static volatile long capBytes = 2L * 1024 * 1024 * 1024;   // 2 GiB
    private static volatile int quality = 60;
    private static volatile int width = 640;
    /** 已用字节; -1 = 还没统计过 */
    /** 已用字节; -1 = 还没统计过。⚠️ volatile: bot 线程写, HTTP 线程读 (状态页)。 */
    private static volatile long used = -1;
    private static final Object LOCK = new Object();
    private static final SimpleDateFormat DAY = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    private static final SimpleDateFormat HMS = new SimpleDateFormat("HHmmss_SSS", Locale.US);

    private Shots() { }

    /** 按 config 的 log 段装载 (缺省值见字段)。 */
    static void configure(JSONObject c) {
        JSONObject l = c == null ? null : c.optJSONObject("log");
        on = l == null || l.optBoolean("shots_on", true);
        double gb = l == null ? 2.0 : l.optDouble("shots_max_gb", 2.0);
        if (gb <= 0.01) gb = 0.01;                       // 别让人不小心写成 0 把功能关死
        // ⚠️ 下限 0.01 GB (10 MB) 不是为了给日常用, 是给**自检**用的:
        //    DebugHttp 的 /shots_flood 连存几百张, 几十秒就能看到"封顶→清理"整条链路,
        //   不然为了验证一次清理得先存 50 MB。
        capBytes = (long) (gb * 1024L * 1024L * 1024L);
        quality = l == null ? 60 : Math.max(20, Math.min(90, l.optInt("shots_quality", 60)));
        width = l == null ? 640 : Math.max(240, Math.min(1280, l.optInt("shots_w", 640)));
    }

    static boolean enabled() { return on; }

    /**
     * 存一张缩小 JPEG。
     *
     * @param b   当前帧 (实机尺寸)
     * @param tag 短标签 (进文件名, 只用 [a-z0-9_] —— 日志里天目录/时间戳已经把定位说清了)
     * @return 相对 {@code logs/} 的路径 (如 {@code shots/2026-10-17/124633_512_scene.jpg});
     *         关闭或失败返回 null (调用方照常打纯文本日志)
     */
    static String save(Bitmap b, String tag) {
        if (!on || b == null) return null;
        try {
            int w = width, h = Math.max(1, Math.round(b.getHeight() * (float) w / b.getWidth()));
            Bitmap s = Bitmap.createScaledBitmap(b, w, h, true);
            synchronized (LOCK) {
                File day = new File(new File(Paths.shotsDir()), DAY.format(new Date()));
                if (!day.isDirectory() && !day.mkdirs()) return null;
                String name = HMS.format(new Date()) + "_" + safe(tag) + ".jpg";
                File out = new File(day, name);
                FileOutputStream fo = new FileOutputStream(out);
                s.compress(Bitmap.CompressFormat.JPEG, quality, fo);
                fo.close();
                if (s != b) s.recycle();
                if (used < 0) used = sizeOf(new File(Paths.shotsDir()));
                used += out.length();
                if (used > capBytes) pruneLocked();
                return "shots/" + day.getName() + "/" + name;
            }
        } catch (Throwable t) {
            return null;                                  // 存图失败绝不影响主流程
        }
    }

    /** 删最旧的, 直到降到 cap 的 90%。 */
    private static void pruneLocked() {
        File root = new File(Paths.shotsDir());
        File[] days = root.listFiles();
        if (days == null) return;
        Arrays.sort(days);                                // 天目录名 yyyy-MM-dd ⇒ 字典序即时间序
        long limit = (long) (capBytes * 0.9);
        long cur = used;
        for (File d : days) {
            if (cur <= limit) break;
            File[] fs = d.listFiles();
            if (fs == null) { d.delete(); continue; }
            Arrays.sort(fs);                              // HHmmss_SSS 前缀 ⇒ 时间序
            for (File f : fs) {
                if (cur <= limit) break;
                long len = f.length();
                if (f.delete()) cur -= len;
            }
            File[] left = d.listFiles();
            if (left != null && left.length == 0) d.delete();
        }
        used = cur;
        /* ⚠️ 一律用 MB 报: 上限可以是 0.01 GB (自检用), "%.1f GB" 会显示成 0.0 误导人。 */
        SgmLog.i("shot", String.format(Locale.US,
                "调试图片超上限, 已清到 %.0f MB (上限 %.0f MB)",
                used / 1048576.0, capBytes / 1048576.0));
    }

    /** 递归求和 (只用于懒初始化那一次)。 */
    private static long sizeOf(File dir) {
        long sum = 0;
        File[] fs = dir.listFiles();
        if (fs == null) return 0;
        List<File> stack = new ArrayList<>();
        stack.addAll(Arrays.asList(fs));
        while (!stack.isEmpty()) {
            File f = stack.remove(stack.size() - 1);
            if (f.isDirectory()) {
                File[] sub = f.listFiles();
                if (sub != null) stack.addAll(Arrays.asList(sub));
            } else {
                sum += f.length();
            }
        }
        return sum;
    }

    /** 真机当前占用 (给 WebUI/监控页显示用)。 */
    static long usedBytes() {
        synchronized (LOCK) {
            if (used < 0) used = sizeOf(new File(Paths.shotsDir()));
            return used;
        }
    }

    private static String safe(String tag) {
        StringBuilder sb = new StringBuilder();
        for (char c : (tag == null ? "s" : tag).toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_') sb.append(c);
            else if (c >= 'A' && c <= 'Z') sb.append((char) (c + 32));
        }
        return sb.length() == 0 ? "s" : (sb.length() > 20 ? sb.substring(0, 20) : sb.toString());
    }
}
