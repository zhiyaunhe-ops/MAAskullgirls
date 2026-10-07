package com.zhiyaunhe.sgmbot;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * ROI 解析 — 多分辨率适配。
 *
 * 素材基准是 1280x576 (20:9 横屏), 但设备比例五花八门: 16:9 平板/模拟器、
 * 21:9 带鱼屏、4:3 老平板。work 帧统一缩放到高 576 (见 Vision.WORK_H),
 * 变的是**宽度** (work_w = 576 * 屏幕宽高比)。四种映射:
 *
 *   abs    : 原样像素 —— 旧行为; 窄屏上右侧槽位会越界, 宽屏上按钮整体偏左
 *   center : 尺寸不变, ROI 中心相对**画面中心**的偏移不变 —— 按钮居中分布的 UI
 *            (结算三槽就是) 通吃任意宽度; 20:9 下与 abs 完全等价 (无回归)
 *   rel    : 整屏横向拉伸 (x*w/1280, w*w/1280) —— UI 铺满且随宽度等比拉伸时用
 *   fit    : **letterbox 感知** —— 游戏按 20:9 出图, 在别的比例屏上留黑边居中,
 *            内容区是居中的 20:9 矩形, 按内容宽/1280 等比缩放 (x/y/宽/高全缩放)
 *   profile: 按 work 宽高比落档, 用档里指定的 mode (screen.profiles)
 *
 * ⚠️ rel/fit 会把**元素尺寸**也改了 ⇒ 模板必须同比例缩放再匹配, 否则 NCC 掉分。
 *    所以 Roi 同时给 scale(cfg,w,h): abs/center=1.0, rel=w/1280, fit=内容宽/1280;
 *    SettleLoop 用 TplStore.getScaled(name, scale) 取模板。
 *
 * 默认: screen.profiles 非空时自动按宽高比选档 (真机 20:9 → abs 保持现状,
 * 16:9/21:9/4:3 → center); 否则用 screen.roi_mode。
 *
 * ⚠️ 哪种模式对应哪台设备**要实机一张截图才能定** (离线探针只能证明映射数学自洽,
 *    证不了"这台机器是哪种排版")。tools/VisionProbe 的多分辨率段就是干这个的。
 */
public final class Roi {
    public static final int BASE_W = 1280;
    /** 基准宽高比 (1280/576) — fit 模式据此定位黑边里的内容区 */
    public static final double BASE_AR = BASE_W / (double) Vision.WORK_H;

    private Roi() { }

    /** 当前 work 尺寸下生效的模式名 (含 profile 命中) — 日志/状态页显示用 */
    public static String modeOf(JSONObject cfg, int w, int h) {
        String m = opt(cfg, "screen", "roi_mode", "abs");
        String p = profileMode(cfg, w, h);
        return p != null ? p : m;
    }

    /** 命中的分辨率档名; 没档/没命中返回 "" */
    public static String profileOf(JSONObject cfg, int w, int h) {
        JSONObject sc = cfg == null ? null : cfg.optJSONObject("screen");
        if (sc == null) return "";
        JSONArray ps = sc.optJSONArray("profiles");
        if (ps == null) return "";
        double ar = h > 0 ? w / (double) h : 0;
        for (int i = 0; i < ps.length(); i++) {
            JSONObject p = ps.optJSONObject(i);
            if (p == null) continue;
            double lo = p.optDouble("ar_min", -1), hi = p.optDouble("ar_max", -1);
            if (ar >= lo && ar < hi) return p.optString("name", "profile" + i);
        }
        return "";
    }

    private static String profileMode(JSONObject cfg, int w, int h) {
        JSONObject sc = cfg == null ? null : cfg.optJSONObject("screen");
        if (sc == null) return null;
        JSONArray ps = sc.optJSONArray("profiles");
        if (ps == null) return null;
        double ar = h > 0 ? w / (double) h : 0;
        for (int i = 0; i < ps.length(); i++) {
            JSONObject p = ps.optJSONObject(i);
            if (p == null) continue;
            double lo = p.optDouble("ar_min", -1), hi = p.optDouble("ar_max", -1);
            if (ar >= lo && ar < hi) return p.optString("roi_mode", "abs");
        }
        return null;
    }

    /** 解析一个 ROI 到当前 work 坐标系; 越界部分 clamp 进画面 */
    public static int[] resolve(JSONObject cfg, int[] roi, int w, int h) {
        return resolveMode(modeOf(cfg, w, h), roi, w, h);
    }

    /** 纯函数版 (离线探针 tools/VisionProbe 直接调, 不经 JSONObject) */
    public static int[] resolveMode(String mode, int[] roi, int w, int h) {
        if (roi == null || roi.length < 4 || w <= 0 || h <= 0) return roi;
        int x = roi[0], y = roi[1], rw = roi[2], rh = roi[3];
        if ("fit".equals(mode)) {
            double s = fitScale(w, h);
            x = fitOffX(w, h) + (int) Math.round(x * s);
            y = fitOffY(w, h) + (int) Math.round(y * s);
            rw = Math.max(1, (int) Math.round(rw * s));
            rh = Math.max(1, (int) Math.round(rh * s));
        } else if ("rel".equals(mode)) {
            x = Math.round(x * w / (float) BASE_W);
            rw = Math.round(rw * w / (float) BASE_W);
            y = Math.round(y * h / (float) Vision.WORK_H);
            rh = Math.round(rh * h / (float) Vision.WORK_H);
        } else if ("center".equals(mode)) {
            // 中心偏移不变: (roi 中心 - 基准中心) 的偏移平移到新画面中心
            int off = (roi[0] + roi[2] / 2) - BASE_W / 2;
            x = w / 2 + off - rw / 2;
        }
        if (rw < 1) rw = 1;
        if (rh < 1) rh = 1;
        if (x < 0) x = 0;
        if (y < 0) y = 0;
        if (x > w - 1) x = w - 1;
        if (y > h - 1) y = h - 1;
        if (x + rw > w) rw = w - x;
        if (y + rh > h) rh = h - y;
        return new int[]{x, y, Math.max(1, rw), Math.max(1, rh)};
    }

    /** work 坐标点 (如 first_tap) 的同款映射 */
    public static float[] resolvePoint(JSONObject cfg, float[] p, int w, int h) {
        return resolvePointMode(modeOf(cfg, w, h), p, w, h);
    }

    /** 纯函数版点映射 */
    public static float[] resolvePointMode(String mode, float[] p, int w, int h) {
        if (p == null || p.length < 2 || w <= 0 || h <= 0) return p;
        if ("rel".equals(mode)) {
            return new float[]{p[0] * w / (float) BASE_W, p[1] * h / (float) Vision.WORK_H};
        }
        if ("center".equals(mode)) {
            return new float[]{w / 2f + (p[0] - BASE_W / 2f), p[1]};
        }
        if ("fit".equals(mode)) {
            double s = fitScale(w, h);
            return new float[]{fitOffX(w, h) + (float) (p[0] * s),
                    fitOffY(w, h) + (float) (p[1] * s)};
        }
        return p;
    }

    /* ---- letterbox (fit) 几何: 内容区 = 画面内居中的 20:9 矩形 ---- */

    /** 内容区宽/高 (work 坐标) */
    private static double contentW(int w, int h) {
        return w / (double) h >= BASE_AR ? h * BASE_AR : w;
    }

    private static double contentH(int w, int h) {
        return w / (double) h >= BASE_AR ? h : w / BASE_AR;
    }

    /** 内容区缩放系数 (相对基准 1280 宽) — 模板要按它一起缩放 */
    public static double fitScale(int w, int h) {
        return contentW(w, h) / BASE_W;
    }

    public static int fitOffX(int w, int h) {
        return (int) Math.round((w - contentW(w, h)) / 2);
    }

    public static int fitOffY(int w, int h) {
        return (int) Math.round((h - contentH(w, h)) / 2);
    }

    /** 当前模式下模板的缩放倍数 (abs/center 不改尺寸 ⇒ 1.0; 日志/状态显示用) */
    public static double scale(JSONObject cfg, int w, int h) {
        return scaleXOf(modeOf(cfg, w, h), w, h);
    }

    /**
     * 模板缩放倍数 — ⚠️ **各向异性**: rel 模式是"横向拉伸铺满" (x 缩放 w/1280,
     * y 不变), 若把模板等比缩放反而配不上 (离线探针实测 16:9 下 2/3 失败)。
     * fit 是等比 (内容保 20:9), abs/center 都不改尺寸。
     */
    public static double scaleXOf(String mode, int w, int h) {
        if ("rel".equals(mode)) return w / (double) BASE_W;
        if ("fit".equals(mode)) return fitScale(w, h);
        return 1.0;
    }

    public static double scaleYOf(String mode, int w, int h) {
        if ("rel".equals(mode)) return h / (double) Vision.WORK_H;
        if ("fit".equals(mode)) return fitScale(w, h);
        return 1.0;
    }

    public static double scaleX(JSONObject cfg, int w, int h) {
        return scaleXOf(modeOf(cfg, w, h), w, h);
    }

    public static double scaleY(JSONObject cfg, int w, int h) {
        return scaleYOf(modeOf(cfg, w, h), w, h);
    }

    private static String opt(JSONObject cfg, String sec, String key, String dflt) {
        JSONObject s = cfg == null ? null : cfg.optJSONObject(sec);
        return s == null ? dflt : s.optString(key, dflt);
    }
}
