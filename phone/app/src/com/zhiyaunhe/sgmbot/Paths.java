package com.zhiyaunhe.sgmbot;

import android.content.Context;
import android.os.Build;
import android.os.Environment;

import java.io.File;

/**
 * 外部存储根 — 一处定死, 其余模块 (截屏/计数/模板/配置/日志) 全部走这里。
 *
 * 为什么不能直接写死 /sdcard/sgm_settle:
 *   Android 11+ 分区存储 — 没存储权限的 App 读不到 /sdcard 根目录, 连自己让 shell
 *   写的 frame.png 也读不到 (FUSE 拒绝, 不是 POSIX 权限问题, chmod 不管用)。
 *   实测: screencap 成功 exit=0, 文件在, 但 App 侧 decodeFile 恒 null。
 *
 * 两级:
 *   1) 已授「所有文件访问」(MANAGE_EXTERNAL_STORAGE) → /sdcard/sgm_settle
 *      (adb push 改配置/换模板最顺手, 原设计口径)
 *   2) 未授 → App 私有外部目录 /sdcard/Android/data/<pkg>/files
 *      (无需任何权限即可读写, 功能不残; 代价是 adb push 要写到这个长路径)
 *
 * init() 在 App.onCreate 里调 (SgmLog.init 之前 — 日志目录也归这里管)。
 */
public final class Paths {
    public static final String LEGACY = "/sdcard/sgm_settle";

    private static String root = LEGACY;   // init 前的兜底值
    private static boolean legacy;         // 是否走的 /sdcard/sgm_settle

    /** 幂等; 只在根真的变了才记一条 (主页 1s 轮询会反复调, 授权后不必重启 App) */
    public static void init(Context c) {
        File e = c.getExternalFilesDir(null);
        String priv = e != null ? e.getAbsolutePath()
                : "/sdcard/Android/data/com.zhiyaunhe.sgmbot/files";
        boolean m = Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager();
        String r = m ? LEGACY : priv;
        if (r.equals(root)) return;
        legacy = m;
        root = r;
        SgmLog.i("path", "外部根=" + root
                + (legacy ? " (所有文件访问已授)" : " (私有目录 — 未授所有文件访问)"));
    }

    public static String root() { return root; }
    /** 是否用的 /sdcard/sgm_settle (adb push 那里才生效) */
    public static boolean legacy() { return legacy; }

    public static String frame() { return root + "/frame.png"; }
    public static String store() { return root + "/store.json"; }
    public static String config() { return root + "/config.json"; }
    public static String tplDir() { return root + "/templates/"; }
    public static String logDir() { return root + "/logs/"; }
    /** GitHub 热更包落点 (bundle.zip / sgmbot.apk) — 见 Update */
    public static String updateDir() { return root + "/update/"; }
}
