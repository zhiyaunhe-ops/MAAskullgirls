package com.zhiyaunhe.sgmbot;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * 模板库 — 结算/逃生/导航用的模板 PNG, 解码成 {@link Vision.Frame} (灰度 NCC 输入)。
 *
 * 来源优先级: /sdcard/sgm_settle/templates/<name>.png (热更: 重裁模板不必发版)
 *           > assets/templates/<name>.png (APK 内置默认, 从 phone/autojs/templates 同步)。
 * 缺模板返回 null — 调用方按「没模板不盲点」降级处理 (同 autojs 版 try/catch 语义)。
 *
 * ⚠️ 模板是 576 基准裁的, **不缩放**; 只有帧才缩放到 work 基准 (Vision.toWork)。
 * ⚠️ aapt2 默认会 crunch PNG (重编码) 掉分风险 ⇒ build-ci.sh 用 --no-crunch。
 */
public final class TplStore {
    public static final String EXT_DIR = "/sdcard/sgm_settle/templates/";

    private final Context ctx;
    private final Map<String, Vision.Frame> cache = new HashMap<>();

    public TplStore(Context ctx) { this.ctx = ctx; }

    /** 取模板 (缓存); 缺失返回 null */
    public Vision.Frame get(String name) {
        Vision.Frame f = cache.get(name);
        if (f != null) return f;
        f = load(name);
        if (f != null) cache.put(name, f);
        return f;
    }

    public boolean has(String name) { return get(name) != null; }

    /** 外部模板热更后调用 (config reload 时一并清) */
    public void clear() { cache.clear(); }

    private Vision.Frame load(String name) {
        Vision.Frame f = fromFile(EXT_DIR + name + ".png");
        if (f != null) return f;
        try {
            InputStream in = ctx.getAssets().open("templates/" + name + ".png");
            f = decode(in);
            in.close();
        } catch (Exception ignored) { }
        return f;
    }

    private static Vision.Frame fromFile(String path) {
        try {
            File fp = new File(path);
            if (!fp.exists() || fp.length() == 0) return null;
            FileInputStream in = new FileInputStream(fp);
            Vision.Frame f = decode(in);
            in.close();
            return f;
        } catch (Exception e) {
            return null;
        }
    }

    private static Vision.Frame decode(InputStream in) {
        Bitmap b = BitmapFactory.decodeStream(in);
        if (b == null) return null;
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        b.recycle();
        return new Vision.Frame(px, w, h);
    }
}
