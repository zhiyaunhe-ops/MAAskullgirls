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
 * 来源优先级: Paths.tplDir() (热更: 重裁模板不必发版; 默认是 /sdcard/sgm_settle/templates/,
 *            未授"所有文件访问"时退到 App 私有目录)
 *           > assets/templates/<name>.png (APK 内置默认, 从 phone/autojs/templates 同步)。
 * 缺模板返回 null — 调用方按「没模板不盲点」降级处理 (同 autojs 版 try/catch 语义)。
 *
 * ⚠️ 模板是 576 基准裁的, **不缩放**; 只有帧才缩放到 work 基准 (Vision.toWork)。
 * ⚠️ aapt2 默认会 crunch PNG (重编码) 掉分风险 ⇒ build-ci.sh 用 --no-crunch。
 */
public final class TplStore {
    private final Context ctx;
    private final Map<String, Vision.Frame> cache = new HashMap<>();
    /** 缩放版模板缓存 (key = name@千分比) — rel/fit 分辨率模式下按屏幕比例复用 */
    private final Map<String, Vision.Frame> scaled = new HashMap<>();

    public TplStore(Context ctx) { this.ctx = ctx; }

    /** 取模板 (缓存); 缺失返回 null */
    public Vision.Frame get(String name) {
        Vision.Frame f = cache.get(name);
        if (f != null) return f;
        f = load(name);
        if (f != null) cache.put(name, f);
        return f;
    }

    /**
     * 按倍数取模板 (rel/fit 模式下画面元素尺寸变了, 模板要同比例缩放才匹配得上)。
     * s≈1 直接返回原模板 (零开销, abs/center 模式走这条)。
     */
    public Vision.Frame getScaled(String name, double s) {
        return getScaled(name, s, s);
    }

    public Vision.Frame getScaled(String name, double sx, double sy) {
        if (sx <= 0 || sy <= 0) return get(name);
        if (Math.abs(sx - 1.0) < 0.01 && Math.abs(sy - 1.0) < 0.01) return get(name);
        String k = name + "@" + Math.round(sx * 1000) + "x" + Math.round(sy * 1000);
        Vision.Frame f = scaled.get(k);
        if (f != null) return f;
        Vision.Frame base = get(name);
        if (base == null) return null;
        f = base.scaleXY(sx, sy);
        scaled.put(k, f);
        return f;
    }

    public boolean has(String name) { return get(name) != null; }

    /** 外部模板热更后调用 (config reload 时一并清) */
    public void clear() { cache.clear(); scaled.clear(); }

    private Vision.Frame load(String name) {
        Vision.Frame f = fromFile(Paths.tplDir() + name + ".png");
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
