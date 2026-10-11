package com.zhiyaunhe.sgmbot;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import java.io.File;

/**
 * 第二条安装路 —— adb (Shizuku `pm install`) 走不通时, 把 APK **交给系统安装器**。
 *
 * 为什么必须有这条路 (2026-10-11 用户要求: 「只要安装失败就走第二条路, 直接用系统的
 * 文件管理把那个安装给打开, 直接就安了」):
 *   第一条路的前提有三条, 任一条不成立就寸步难行 ——
 *     ① Shizuku 服务在跑并已绑定; ② shell(shell uid) 读得到 APK 所在目录;
 *     ③ 不撞版本降级。
 *   这三条**全是机器状态**, 不是"装个新版本"本身该有的门槛。结果就是最气人的那种故障:
 *   `sgmbot.apk` 已经好端端躺在手机里了, 界面却说"装不上", 而用户其实只需要**点一下
 *   系统弹出的安装界面**。
 *   这条路不需要 Shizuku、不需要「所有文件访问」、不需要 adb, 只要:
 *     · 一个 content:// 读权限 (见 {@link ApkProvider} —— 不能再用 file://, API 24 起被禁)
 *     · 一次「安装未知应用」授权 (API 26+; {@link #canInstall})
 *
 * ⚠️ 本类的语义边界: **只负责把安装界面弹出来**。装不装成是用户在系统界面里决定的,
 *   所以 {@link R#launched} 只表示"界面弹出去了", **绝不表示装上了**。
 *   真正的判据是 {@code Update.resolvePending()}: 比对**本包自己的 lastUpdateTime** ——
 *   让执行方自己说, 不用我们推断 (同 Paths/安装授权那条教训)。
 *
 * ⚠️ 后台启动限制 (Android 10+): App 在后台时 startActivity 会被系统静默拦掉。
 *   所以这条路**只在用户主动点「拉取更新」时才有意义** —— 自动检查永远不带装 APK
 *   ({@code autoCheck(ctx, cfg, false)}), 不会撞上这条限制。判断依据写进 msg, 出错时
 *   用户至少知道"回 App 里再点一次"。
 */
public final class ApkInstall {

    /** 一次兜底尝试的**事实**, 三个布尔 + 一句人话 —— 不含任何猜测 */
    public static final class R {
        /** 安装界面已经弹出去了 (≠ 装上了) */
        public boolean launched;
        /** 缺「安装未知应用」授权, 已把用户送到那个设置页 */
        public boolean needPerm;
        /** 一句话原因 (总有值; UI 直接显示这一句) */
        public String msg = "";
    }

    private ApkInstall() { }

    /**
     * API 26+ 才有「安装未知应用」这道闸; 更低版本系统直接允许。
     * 问不出来时按**没授权**处理 —— 宁可多走一步授权引导, 也不要"点了没反应"。
     */
    public static boolean canInstall(Context c) {
        if (Build.VERSION.SDK_INT < 26) return true;
        try {
            return c.getPackageManager().canRequestPackageInstalls();
        } catch (Throwable t) {
            SgmLog.i("更新", "问「安装未知应用」权限失败: " + t);
            return false;
        }
    }

    /**
     * 把系统安装界面弹到 APK 上。
     * @return 结果对象 (msg 总有值, 调用方直接拿去显示)
     */
    public static R open(Context ctx, File apk) {
        R r = new R();
        if (ctx == null) {
            r.msg = "没有 Context — 打不开安装界面";
            return r;
        }
        if (!ApkFile.looksComplete(apk)) {
            r.msg = ApkFile.incompleteReason(apk);
            SgmLog.i("更新", "兜底放弃: " + r.msg);
            return r;
        }

        /* ① 先过「安装未知应用」那道闸。不过就送人去开 —— 直接 startActivity 安装界面
         *    会被系统弹一个更绕的"去设置"流程, 不如我们主动送, 并且把话说明白。 */
        if (!canInstall(ctx)) {
            r.needPerm = true;
            r.msg = "装 APK 还缺「安装未知应用」授权 — 已跳到设置页, 打开开关后回来再点一次「拉取更新」";
            Intent s = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + pkg(ctx)));
            s.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                ctx.startActivity(s);
                SgmLog.i("更新", "缺「安装未知应用」— 已跳设置页");
            } catch (Throwable t) {
                SgmLog.i("更新", "跳设置页失败 (可自己到 应用信息→安装未知应用 里开): " + t);
            }
            return r;
        }

        /* ② 正常路径: content:// + APK 的 MIME。系统安装器按 mime 认领这个 intent。 */
        Uri uri = Uri.parse(ApkFile.uriFor(apk.getName()));
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, ApkFile.MIME_APK);
        /* FLAG_GRANT_READ_URI_PERMISSION 是**这条路的命门**: 安装器与我们是两个 uid,
         * 没有这一句它读不到 content:// (报的错还是"解析包时出现问题", 完全指不到权限上)。 */
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        r.launched = fire(ctx, i);
        if (!r.launched) {
            /* 有的 ROM 只认老动作, 再试一次 —— 两条都试过了才敢说"系统里没有能装 APK 的界面"。
             * ⚠️ 别把两次的结果写成 `if (!fire(a)) fire(b);` —— 那样 fire 的返回值没落到
             *    r.launched 上, 后面那句 `if (r.launched)` 恒为假 ⇒ 明明弹出来了也报"装不了"。 */
            Intent j = new Intent(Intent.ACTION_INSTALL_PACKAGE);
            j.setData(uri);
            j.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            j.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true);
            r.launched = fire(ctx, j);
        }

        if (r.launched) {
            r.msg = "adb 那条路不通 → 已弹出系统安装界面, 点「安装」就行 (装完回来自动认账)";
            SgmLog.i("更新", "兜底: 已打开系统安装器 " + apk.getName()
                    + " (" + (apk.length() / 1024) + "KB)");
        } else {
            r.msg = "装不了: 系统里没有能处理 APK 的界面 — 可手动装 " + apk.getAbsolutePath();
            SgmLog.i("更新", r.msg);
        }
        return r;
    }

    /** startActivity 包一层: 失败只记日志并把 launched 置回 false (两条 intent 用的是同一份判定) */
    private static boolean fire(Context ctx, Intent i) {
        try {
            ctx.startActivity(i);
            SgmLog.i("更新", "已发 " + i.getAction());
            return true;
        } catch (Throwable t) {
            SgmLog.i("更新", "startActivity(" + i.getAction() + ") 失败: "
                    + t.getClass().getSimpleName() + " " + t.getMessage());
            return false;
        }
    }

    private static String pkg(Context c) {
        try {
            return c.getPackageName();
        } catch (Throwable t) {
            return "com.zhiyaunhe.sgmbot";
        }
    }
}
