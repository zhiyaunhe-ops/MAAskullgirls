package com.zhiyaunhe.sgmbot;

import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 调试三通道之文件通道 (四原则之四: adb 可调试)。
 *   <Paths.logDir()>app.log     — 时间戳日志 (adb pull / tail)
 *   <Paths.logDir()>state.json  — 状态机快照 (当前链/阶段/计数/最近动作)
 * adb 侧: `adb shell cat .../app.log | tail` 或 pull; Logcat 同步镜像一份。
 *
 * 目录走 Paths (分区存储: /sdcard 根目录未授"所有文件访问"时写不进去,
 * 会退化到 App 私有目录) —— 别再写死 /sdcard/sgm_settle。
 */
public final class SgmLog {
    private static final SimpleDateFormat TS = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US);
    private static final Object LOCK = new Object();
    private static File dir;
    private static File logFile;

    public static void init() {
        dir = new File(Paths.logDir());
        if (!dir.exists()) dir.mkdirs();
        logFile = new File(dir, "app.log");
    }

    public static void i(String tag, String msg) {
        String line = TS.format(new Date()) + " [" + tag + "] " + msg;
        android.util.Log.i("sgmbot", msg);
        synchronized (LOCK) {
            try {
                if (logFile == null) init();
                FileWriter w = new FileWriter(logFile, true);
                w.write(line + "\n");
                w.close();
                rotateIfBig();
            } catch (Exception ignored) { }
        }
    }

    /**
     * **带图日志** —— 存一张缩小 JPEG 并在行尾挂引用, 形如
     * {@code 12:46:33 [pf] 选对手: 卡2 倍率最大 = 2.0 | 图=shots/2026-10-17/124633_512_pf.jpg}。
     *
     * <p>2026-10-17 用户: 「log 里也带图吧, 控制大小在 2G 以内方便调试」。图片口径与清理
     * 见 {@link Shots}。存图失败时**退回纯文本**, 绝不让"记日志"这件事把主流程带崩。</p>
     */
    public static void i(String tag, String msg, android.graphics.Bitmap bmp) {
        String ref = null;
        try { ref = Shots.save(bmp, tag); } catch (Throwable ignored) { }
        i(tag, ref == null ? msg : msg + " | 图=" + ref);
    }

    /**
     * app.log 过大就轮转 (app.log → app.log.1, 旧的 .1 直接丢)。
     *
     * <p>⚠️ 只靠"图片有 cap"是不够的: 日志行本身在长跑下也会涨 (每轮几十行)。
     * 32 MB 够放几天, 而 adb pull 一个 32 MB 文件也就几秒。</p>
     */
    private static void rotateIfBig() {
        if (logFile == null) return;
        if (logFile.length() < LOG_MAX) return;
        File old = new File(logFile.getParentFile(), "app.log.1");
        if (old.exists()) old.delete();
        if (!logFile.renameTo(old)) {
            /* rename 失败 (被占/跨挂载) ⇒ 退化成截断, 宁可丢旧日志也别写爆盘 */
            try {
                FileWriter w = new FileWriter(logFile, false);
                w.write(TS.format(new Date()) + " [log] app.log 轮转失败, 已截断\n");
                w.close();
            } catch (Exception ignored) { }
        }
    }

    /** 单文件日志上限 (32 MB) —— 超过就轮转到 app.log.1。 */
    private static final long LOG_MAX = 32L * 1024 * 1024;

    /** 状态机快照 — 覆盖写, 每次动作/阶段切换后调用; PC 侧轮询此文件即知当前在干嘛 */
    public static void state(JSONObject snapshot) {
        synchronized (LOCK) {
            try {
                File f = new File(dir, "state.json");
                FileWriter w = new FileWriter(f, false);
                w.write(snapshot.toString());
                w.close();
            } catch (Exception ignored) { }
        }
    }
}
