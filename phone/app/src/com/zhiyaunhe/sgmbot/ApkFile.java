package com.zhiyaunhe.sgmbot;

import java.io.File;

/**
 * APK「交给系统安装器」这件事的**全部常量与路径规则** —— 纯 java.util + File,
 * 不 import 任何 android。
 *
 * 为什么单独一层 (同 {@link GraphLayout} / {@link WorkPoint} 的做法):
 *   这条路上有三处必须一致的东西 —— {@link ApkInstall} 拼的 content:// 串、
 *   {@link ApkProvider} 认的 authority 与文件名解析、探针断言的口径。
 *   各写一份的下场是"探针说没事、实机打不开"。
 *
 * ⚠️ 路径安全: 只认**裸文件名**。这个 provider 是给系统安装器读文件的, 一旦允许
 *    `../` 或绝对路径, 它就成了"任意文件读取器" —— 而 provider 一旦注册就一直在。
 *   {@link #resolve} 因此同时拒 `/`、`\`、`..`, 且只在 update 目录里找。
 */
public final class ApkFile {

    /** provider authority —— 与 AndroidManifest 里 <provider> 的 authorities 必须一致 */
    public static final String AUTHORITY = "com.zhiyaunhe.sgmbot.apkfile";

    /** 系统安装器认的 MIME; 用它去 ACTION_VIEW 才能落到安装界面 */
    public static final String MIME_APK = "application/vnd.android.package-archive";

    /** 下载落点文件名 (与 config.update.apk_asset 的资产名一致) */
    public static final String NAME = "sgmbot.apk";

    /**
     * 小于这个大小一定不是个完整 APK。
     * 兜底那条路是把文件**交给别的进程**读, 半截文件在安装器里报的错
     * ("解析包时出现问题") 完全指不到下载失败上 —— 所以在门口就拦掉。
     */
    public static final int MIN_BYTES = 100 * 1024;

    private ApkFile() { }

    /**
     * content:// 串 (给 Intent 用)。
     * 只放文件名, 不放路径 —— 路径由 provider 自己按 "update 目录 + 名字" 拼。
     */
    public static String uriFor(String name) {
        return "content://" + AUTHORITY + "/" + name;
    }

    /**
     * 把 Uri 的末段解析成文件。
     * @return null = 名字不合法 (含路径分隔符/上级引用/空) 或不在 update 目录里
     */
    public static File resolve(File updateDir, String name) {
        if (updateDir == null || name == null) return null;
        String n = name.trim();
        if (n.length() == 0) return null;
        if (n.indexOf('/') >= 0 || n.indexOf('\\') >= 0) return null;   // 不许带路径
        if (n.indexOf("..") >= 0) return null;                          // 不许往上走
        File f = new File(updateDir, n);
        /* 双保险: 规范化之后必须仍在 updateDir 里面 (符号链接/奇怪名字兜底) */
        try {
            String can = f.getCanonicalPath();
            String base = updateDir.getCanonicalPath();
            if (!can.startsWith(base + File.separator)) return null;
        } catch (Exception e) {
            return null;
        }
        return f;
    }

    /** 文件像不像一个下全了的 APK (存在 + 够大) */
    public static boolean looksComplete(File f) {
        try {
            return f != null && f.isFile() && f.length() >= MIN_BYTES;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 「不够完整」时的一句话原因 (给用户看的, 要能指到"重下一次") */
    public static String incompleteReason(File f) {
        if (f == null) return "没有 APK 文件";
        if (!f.exists()) return "APK 不在 " + f.getAbsolutePath() + " — 重下一次";
        long n;
        try {
            n = f.length();
        } catch (Throwable t) {
            n = -1;
        }
        return "APK 只下了 " + (n / 1024) + "KB (不足 " + (MIN_BYTES / 1024)
                + "KB) — 多半是下载断了, 再点一次「拉取更新」";
    }
}
