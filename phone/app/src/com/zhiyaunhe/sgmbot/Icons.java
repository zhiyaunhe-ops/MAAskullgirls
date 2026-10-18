package com.zhiyaunhe.sgmbot;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * 设置页/弹层用的**游戏内小图标** —— 元素图标、奖牌、爱心、星芒、黄钉。
 *
 * <h3>这些图是哪来的</h3>
 * 全部从**真机筛选面板** ({@code debug/k70_probe/filter_panel.png}, 2400x1080) 裁出来,
 * 背景键控成透明, 不是自己画的: 元素行 = 火/水/风/光/暗/无, 第一行 = 铜/银/金/钻/心/星芒。
 * 之所以要真图: 元素名 (fire/water/…) 对玩家没意义, 玩家认的是**游戏里那个符号**。
 *
 * <h3>加载顺序</h3>
 * {@link Paths#iconDir()} (外置, 可热更) &gt; {@code assets/icons/<name>.png}。
 * ⚠️ 外置优先是为了绕开「增量安装后 APK 内 assets 读不到」那个老坑
 * (见 MEMORY: 重装后 TemplateStore 只能走 /sdcard/sgm_settle/templates/)。
 * 读不到就返回 null —— 调用方一律当"没有图标"处理, **不允许因此崩或空白行**。
 */
final class Icons {

    /** 元素 → 图标名。neutral 是"无属性"(白色菱形)。 */
    private static final String[] ELEMENTS = {"fire", "water", "wind", "light", "dark", "neutral"};

    private static final Map<String, Bitmap> CACHE = new HashMap<>();

    private Icons() { }

    /** 按名字取图标 (缓存)。缺失返回 null。 */
    static synchronized Bitmap get(Context ctx, String name) {
        if (name == null || name.isEmpty()) return null;
        Bitmap b = CACHE.get(name);
        if (b != null) return b;
        b = fromFile(Paths.iconDir() + name + ".png");
        if (b == null) b = fromAsset(ctx, "icons/" + name + ".png");
        if (b != null) CACHE.put(name, b);
        return b;
    }

    /** 清缓存 (换图标后 reload 用)。 */
    static synchronized void clear() { CACHE.clear(); }

    /**
     * 规则/场次用的小图标。
     *
     * @param ruleType  element / class (PfTeam.ruleType 的口径)
     * @param ruleValue 元素名或角色显示名
     * @return 元素规则给元素图标, 角色规则给**首字母徽章** (游戏里没有"通用角色"图标,
     *         硬拿某个角色头像会误导), 认不出返回 null
     */
    static Bitmap forRule(Context ctx, String ruleType, String ruleValue) {
        if ("element".equals(ruleType)) return elem(ctx, ruleValue);
        if ("class".equals(ruleType)) return badge(ctx, ruleValue);
        return null;
    }

    /** "element:water" / "class:Filia" → 图标; 认不出 null。 */
    static Bitmap forRuleText(Context ctx, String ruleText) {
        if (ruleText == null) return null;
        int c = ruleText.indexOf(':');
        if (c <= 0) return null;
        return forRule(ctx, ruleText.substring(0, c), ruleText.substring(c + 1));
    }

    /** 元素图标 ("fire" ... "neutral")。 */
    static Bitmap elem(Context ctx, String element) {
        if (element == null) return null;
        for (String e : ELEMENTS) if (e.equals(element)) return get(ctx, "elem_" + e);
        return null;
    }

    /** 金币场 = 金牌。 */
    static Bitmap gold(Context ctx) { return get(ctx, "medal_gold"); }

    /** 收藏 = 红心。 */
    static Bitmap heart(Context ctx) { return get(ctx, "heart"); }

    /** 星芒 (月场/杂项兜底)。 */
    static Bitmap sparkle(Context ctx) { return get(ctx, "sparkle"); }

    /**
     * 角色首字母徽章 —— 游戏里**没有**通用"角色"图标, 所以用游戏的金色圆底 + 首字母,
     * 既认得出是"某个角色", 又能一眼区分是谁。
     */
    static Bitmap badge(Context ctx, String text) {
        if (text == null || text.isEmpty()) return null;
        String key = "badge:" + text;
        synchronized (Icons.class) {
            Bitmap c = CACHE.get(key);
            if (c != null) return c;
        }
        int n = 96;
        Bitmap b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        /* 深色圆底 + 金环, 跟游戏头像框一个调子 */
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF2B2A3D);
        cv.drawCircle(n / 2f, n / 2f, n / 2f - 2, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(6);
        p.setColor(0xFFD9A62E);
        cv.drawCircle(n / 2f, n / 2f, n / 2f - 5, p);
        /* 首字符 (角色名可能是 "Ms. Fortune" 这种, 取第一个字母) */
        String ch = text.trim().substring(0, 1).toUpperCase(java.util.Locale.US);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFFF2E3B8);
        p.setTextSize(n * 0.52f);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics fm = p.getFontMetrics();
        cv.drawText(ch, n / 2f, n / 2f - (fm.ascent + fm.descent) / 2f, p);
        synchronized (Icons.class) { CACHE.put(key, b); }
        return b;
    }

    /**
     * 把图标缩到 dp 见方做成 Drawable (给 Button 的 compound drawable 用)。
     *
     * @param dp 目标边长 (dp)
     */
    static Drawable drawable(Context ctx, Bitmap b, int dp) {
        if (b == null) return null;
        int px = (int) (dp * ctx.getResources().getDisplayMetrics().density);
        Bitmap s = Bitmap.createScaledBitmap(b, px, px, true);
        Drawable d = new BitmapDrawable(ctx.getResources(), s);
        d.setBounds(0, 0, px, px);
        return d;
    }

    /* ---------------- 载入 ---------------- */

    private static Bitmap fromFile(String path) {
        try {
            File f = new File(path);
            if (!f.exists()) return null;
            FileInputStream in = new FileInputStream(f);
            Bitmap b = BitmapFactory.decodeStream(in);
            in.close();
            return b;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Bitmap fromAsset(Context ctx, String path) {
        if (ctx == null) return null;
        try {
            InputStream in = ctx.getAssets().open(path);
            Bitmap b = BitmapFactory.decodeStream(in);
            in.close();
            return b;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 调试: 现在能读到哪几个图标 (日志里打一行, 判"图标全丢"还是"只丢某几个")。 */
    static String debugList(Context ctx) {
        String[] names = {"elem_fire", "elem_water", "elem_wind", "elem_light", "elem_dark",
                "elem_neutral", "medal_gold", "heart", "sparkle"};
        StringBuilder sb = new StringBuilder();
        for (String n : names) sb.append(n).append(get(ctx, n) == null ? "✗ " : "✓ ");
        return sb.toString().trim();
    }
}
