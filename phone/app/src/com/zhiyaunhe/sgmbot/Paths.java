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
    private static boolean inited;         // 首次 init 也要落一条日志 (见下)

    /**
     * 幂等; **状态每次都重算**, 日志只在真的变了才记一条
     * (主页 1s 轮询会反复调, 授权后不必重启 App)。
     *
     * ⚠️⚠️ 绝不能写成「状态没变就 return」—— 原来是
     *     `if (r.equals(root)) return;` + 后面才 `legacy = m;`,
     *     而 `root` 的静态初值**恰好就是 LEGACY**、`legacy` 的初值是 false。
     *     于是最平常的那条路径上就直接返回了:
     *
     *       冷启动 + 早就授权 ⇒ r == LEGACY == root ⇒ return ⇒ **legacy 永远是 false**
     *
     *     后果 = `Paths.root()` 是对的 (/sdcard/sgm_settle, 文件确实写在那儿),
     *     但 `Paths.legacy()` 恒为 false ⇒ 自更新走 APK 那条路时立刻报
     *     「未授「所有文件访问」— shell 读不到 …」, **明明授过权也永远装不上**。
     *
     *     2026-10-11 用户报障「我早就授权了所有文件访问, 为啥他还自己安装不了, 说 shell 读不到」。
     *     实测三个场景 (tools/PathsProbe.py): 冷启动已授权 = 复现; 跑着才授权 = 正常
     *     (r 从 priv 变成 LEGACY, 不相等 ⇒ 赋值执行了)。所以只有「先授权、之后 App 被
     *     重启过」的用户会踩 —— 而长期用户全都是这样。
     *
     *     教训: 用「新旧值相等」当「无需赋值」的判据时, 必须确认**初值不等于任一目标值**,
     *     否则第一跳就是错误状态。赋值和打日志是两件事, 别合并。
     */
    public static void init(Context c) {
        File e = c.getExternalFilesDir(null);
        String priv = e != null ? e.getAbsolutePath()
                : "/sdcard/Android/data/com.zhiyaunhe.sgmbot/files";
        boolean m = Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager();
        String r = m ? LEGACY : priv;
        boolean changed = !inited || m != legacy || !r.equals(root);
        inited = true;
        legacy = m;
        root = r;
        if (changed)
            SgmLog.i("path", "外部根=" + root
                    + (legacy ? " (所有文件访问已授)" : " (私有目录 — 未授所有文件访问)"));
    }

    public static String root() { return root; }
    /** 是否用的 /sdcard/sgm_settle (adb push 那里才生效) */
    public static boolean legacy() { return legacy; }

    public static String frame() { return root + "/frame.png"; }
    /** 临时采集落点 (dump/): 截图 hook 类的调试样本, 手动删或脚本拉走, 不进清理范围。 */
    public static String dumpDir() { return root + "/dump/"; }
    public static String store() { return root + "/store.json"; }
    public static String config() { return root + "/config.json"; }
    public static String tplDir() { return root + "/templates/"; }
    public static String logDir() { return root + "/logs/"; }
    /** GitHub 热更包落点 (bundle.zip / sgmbot.apk) — 见 Update */
    public static String updateDir() { return root + "/update/"; }
}
