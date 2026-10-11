package com.zhiyaunhe.sgmbot;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 只读地把自己下载的 APK 交给**系统安装器** (给 {@link ApkInstall} 用)。
 *
 * 为什么非有不可: Android 7 (API 24) 起把 `file://` 交给别的 App 会直接抛
 *   FileUriExposedException —— 必须走 `content://` + 一次性读权限。而 APK 落在
 *   App 自己的目录里 (见 {@link Paths}), 安装器是另一个 uid 读不到 ⇒ 没有 provider
 *   就只能回到"所有文件访问 + shell"那条**我们正要兜底**的路上, 死循环。
 *
 * 为什么不是照抄 FileProvider: 本项目不引 AndroidX (见 phone/app/README「为什么走
 *   pfwidget 同款构建链」—— 无 Gradle, 只有 aapt2 + javac + apkbuild), 而
 *   `androidx.core.content.FileProvider` 要整包依赖。这里需要的功能只有三件:
 *   `openFile` / `getType` / 名字与大小, 自己写反而更短更可控。
 *
 * ⚠️ 安全边界 (这个 provider 一旦注册就一直挂在系统里, 必须当成对外接口看):
 *   ① 只认 {@link ApkFile#resolve} 过的**裸文件名** —— 带 `/`、`\`、`..` 的一律拒;
 *   ② 只在 `Paths.updateDir()` 里找, 规范化后必须仍在该目录内;
 *   ③ 只允许 `"r"` 模式, insert/delete/update 全是空实现。
 *   任何一条放松, 它就变成"任意文件读取器"。
 */
public final class ApkProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        return ApkFile.MIME_APK;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("只读 provider (mode=" + mode + ")");
        File f = file(uri);
        if (f == null) throw new FileNotFoundException("名字不合法: " + uri);
        if (!f.isFile()) throw new FileNotFoundException("文件不存在: " + f.getAbsolutePath());
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    /**
     * 安装器会查 DISPLAY_NAME / SIZE (用来显示"正在安装 xxx")。
     * 查不到就返回 null —— 安装流程不依赖它, 不该为此报错。
     */
    @Override
    public Cursor query(Uri uri, String[] projection, String sel, String[] args, String sort) {
        File f = file(uri);
        if (f == null || !f.isFile()) return null;
        MatrixCursor c = new MatrixCursor(new String[]{
                OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        c.addRow(new Object[]{f.getName(), Long.valueOf(f.length())});
        return c;
    }

    @Override
    public Uri insert(Uri uri, ContentValues v) {
        return null;
    }

    @Override
    public int delete(Uri uri, String s, String[] a) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues v, String s, String[] a) {
        return 0;
    }

    /**
     * Uri → 文件。**每次都重算 Paths.init**: provider 是在 Application.onCreate
     * 之前被系统创建的, 而"所有文件访问"的授权状态可能在那之后才变 (用户去设置页开了
     * 权限) ⇒ 用创建时的旧根就会指到错的目录去 (症状: "文件不存在", 而文件明明在)。
     */
    private File file(Uri uri) {
        try {
            Paths.init(getContext());
        } catch (Throwable ignored) {
        }
        String name = uri == null ? null : uri.getLastPathSegment();
        return ApkFile.resolve(new File(Paths.updateDir()), name);
    }
}
