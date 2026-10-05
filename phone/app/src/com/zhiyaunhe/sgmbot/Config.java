package com.zhiyaunhe.sgmbot;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

/**
 * 配置层 — 引擎零硬编码 (四原则之一: 可配置)。
 *
 * 优先级: /sdcard/sgm_settle/config.json (adb push 即改即生效, 无需重装)
 *        > assets/config.json (APK 内置默认)。
 * read() 每次重新读文件 (调用方按需频率刷新, 小文件 <1ms); 文件不存在/损坏回落默认。
 *
 * config 结构 (与 autojs 版常量一一对应, 示例见 assets/config.json):
 * {
 *   "thresholds": {"min_sim":0.72, "ev_play":0.78, "target":0.75, "spd":0.95, "x":0.8, "srv":0.75},
 *   "timing": {"battle_ms":800, "result_ms":350, "tap_delay_ms":1200, "stall_sec":30,
 *              "launch_timeout_s":300, "hub_timeout_s":120},
 *   "daily_cap": 0,
 *   "target_card": "ev_target",
 *   "nav": {...},                    // 链路步骤表 (可拓展: 新活动=新配置)
 *   "boss_anchor": {"color":-8839615, "tol":50, "roi":[640,40,640,500], "offset":[-72,2]}
 * }
 */
public final class Config {
    public static final String OVERRIDE = "/sdcard/sgm_settle/config.json";
    private final Context ctx;
    private volatile JSONObject cur;

    public Config(Context ctx) {
        this.ctx = ctx;
        this.cur = defaults();
    }

    public JSONObject defaults() {
        try {
            InputStream in = ctx.getAssets().open("config.json");
            JSONObject o = load(in);
            in.close();
            return o;
        } catch (Exception e) {
            return new JSONObject();   // 资产缺失: 引擎用代码内 fallback 常量
        }
    }

    /** 每次调用重新读 override 文件 — 热更新点 (adb push 后 POST /reload 或下帧生效) */
    public JSONObject read() {
        try {
            File f = new File(OVERRIDE);
            if (f.exists() && f.length() > 0) {
                cur = merge(defaults(), load(new FileInputStream(f)));
            } else {
                cur = defaults();
            }
        } catch (Exception ignored) { }
        return cur;
    }

    public JSONObject current() { return cur; }

    static JSONObject merge(JSONObject base, JSONObject over) {
        try {
            JSONObject out = new JSONObject(base.toString());
            java.util.Iterator<String> it = over.keys();
            while (it.hasNext()) {
                String k = it.next();
                Object v = over.get(k);
                if (v instanceof JSONObject && out.has(k) && out.get(k) instanceof JSONObject) {
                    out.put(k, merge(out.getJSONObject(k), (JSONObject) v));
                } else {
                    out.put(k, v);
                }
            }
            return out;
        } catch (JSONException e) {
            return base;
        }
    }

    static JSONObject load(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, "UTF-8"));
        in.close();
        return new JSONObject(sb.toString());
    }
}
