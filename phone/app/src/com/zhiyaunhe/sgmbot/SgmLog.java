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
            } catch (Exception ignored) { }
        }
    }

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
