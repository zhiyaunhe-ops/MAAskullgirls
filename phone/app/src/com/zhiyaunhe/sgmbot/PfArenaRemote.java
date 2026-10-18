package com.zhiyaunhe.sgmbot;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;

/**
 * 线上场次表拉取 —— 把「场次表」从 APK 里解耦出来。
 *
 * <h3>为什么要它</h3>
 * <ul>
 *   <li>场次会变（官方每月上新月场、下架旧场），而 {@code assets/pf_arena.json} 只有重装
 *       APK 才会更新；更糟的是 {@code adb install -r} 是增量安装，assets 经常根本没换
 *       （见 MEMORY「真机移植三大坑」①）。</li>
 *   <li>sgmnow 只给「当期名字」，不给类别/限制；本站（GitHub Pages）给的是带来源分级的完整目录。</li>
 * </ul>
 *
 * <h3>三级兜底（顺序不能反）</h3>
 * <ol>
 *   <li><b>线上缓存</b> {@code <root>/pf_arena_remote.json} —— 上次拉成功的那份；</li>
 *   <li><b>APK 内置</b> {@code assets/pf_arena.json} —— 离线/首次启动时的唯一来源；</li>
 *   <li>两者都读不了 ⇒ 场次表为空，{@link PfArenaTable} 退回「按卡位保守口径」（4kw / 4 钉）。</li>
 * </ol>
 *
 * <h3>⚠️ 写缓存前必须校验</h3>
 * 机场/酒店 Wi-Fi 的登录页、GitHub 502 页面都会回 200 + 一段 HTML。若无脑落盘，
 * 下一次启动就读到一份坏表，而且**表现是"场次全不认识"**（看起来像识别坏了）。
 * 所以只在 {@link #valid} 通过时才写，并且写 {@code .part} 再改名（断电不半截）。
 *
 * <h3>网络</h3>
 * 走 {@link HttpURLConnection}（与 {@link Update} 同套路，不引第三方库）；
 * 带 {@code If-None-Match}，304 就只更新时间戳，不重写文件。
 */
final class PfArenaRemote {

    static final String DEFAULT_URL = "https://zhiyaunhe-ops.github.io/data/arenas.json";
    private static final String T = "场次表";
    private static final int CONNECT_TIMEOUT = 8000;
    private static final int READ_TIMEOUT = 15000;
    /** 自动拉取的间隔：一天一次（场次按周/月变，没必要更频繁） */
    private static final long AUTO_INTERVAL_MS = 24 * 3600 * 1000L;
    /** 低于这个条数就当坏包 —— 正常目录 40+ 条；被截断的文件通常只剩几条 */
    private static final int MIN_ARENAS = 10;

    private static volatile String lastResult = "";
    private static volatile boolean lastOk = false;

    private PfArenaRemote() { }

    /* ---------------- 路径 / 状态 ---------------- */

    static File cacheFile() {
        return new File(Paths.root(), "pf_arena_remote.json");
    }

    private static File metaFile() {
        return new File(Paths.root(), "pf_arena_remote.meta.json");
    }

    private static JSONObject meta() {
        try {
            File f = metaFile();
            if (!f.exists() || f.length() == 0) return new JSONObject();
            InputStream in = new FileInputStream(f);
            String s = readAll(in, 8192);
            in.close();
            return new JSONObject(s);
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    static String url(Context c) {
        try {
            JSONObject all = BotService.cfgOr(c).read();
            JSONObject pf = all == null ? null : all.optJSONObject("pf");
            String u = pf == null ? null : pf.optString("arena_url", "");
            return u == null || u.isEmpty() ? DEFAULT_URL : u;
        } catch (Throwable t) {
            return DEFAULT_URL;
        }
    }

    /** 有没有可用的线上缓存。 */
    static boolean hasCache() {
        JSONObject r = readCache();
        return r != null && valid(r);
    }

    static int cachedCount() {
        JSONObject r = readCache();
        if (r == null) return 0;
        JSONObject a = r.optJSONObject("arenas");
        return a == null ? 0 : a.length();
    }

    static long lastFetchMs() {
        return meta().optLong("at", 0L);
    }

    /** UI 一行话：「线上 44 条 · 10-18 14:02」 / 「内置表（未拉过）」 */
    static String status() {
        if (!hasCache()) return "内置表（未拉过线上）";
        SimpleDateFormat f = new SimpleDateFormat("MM-dd HH:mm", Locale.US);
        return "线上 " + cachedCount() + " 条 · " + f.format(new Date(lastFetchMs()));
    }

    static String lastResult() { return lastResult; }

    static boolean lastOk() { return lastOk; }

    /* ---------------- 拉取 ---------------- */

    /**
     * 同步拉一次（**不要在主线程调**）。成功就把内容落到 {@link #cacheFile()}。
     *
     * @return {ok, notModified, count, msg, url}
     */
    static JSONObject sync(Context c) {
        JSONObject out = new JSONObject();
        String u = url(c);
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(u).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "sgmbot-arena");
            conn.setRequestProperty("Accept", "application/json");
            String etag = meta().optString("etag", "");
            if (!etag.isEmpty() && hasCache()) conn.setRequestProperty("If-None-Match", etag);

            int code = conn.getResponseCode();
            if (code == 304) {
                touch();
                out.put("ok", true).put("notModified", true).put("url", u)
                   .put("count", cachedCount());
                remember(true, "304 未变更");
                return out;
            }
            if (code != 200) {
                out.put("ok", false).put("msg", "HTTP " + code).put("url", u);
                remember(false, "HTTP " + code);
                return out;
            }
            String body = readAll(conn.getInputStream(), 1 << 20);
            JSONObject root;
            try {
                root = new JSONObject(body);
            } catch (Exception e) {
                /* 200 + 非 JSON = 八成是门户登录页/错误页 */
                out.put("ok", false).put("msg", "返回的不是 JSON（可能被网络门户拦了）").put("url", u);
                remember(false, "非 JSON");
                return out;
            }
            if (!valid(root)) {
                out.put("ok", false).put("msg", "结构不对或条数过少，已拒绝写入").put("url", u);
                remember(false, "结构校验失败");
                return out;
            }
            writeCache(body);
            writeMeta(conn.getHeaderField("ETag"), root);
            int n = root.optJSONObject("arenas") == null ? 0 : root.optJSONObject("arenas").length();
            out.put("ok", true).put("notModified", false).put("count", n).put("url", u);
            SgmLog.i(T, "拉取成功 " + n + " 条 → " + cacheFile().getName());
            remember(true, n + " 条已更新");
            return out;
        } catch (Throwable t) {
            SgmLog.i(T, "拉取失败: " + t);
            remember(false, "网络失败: " + t.getClass().getSimpleName());
            try { out.put("ok", false).put("msg", "网络失败: " + t).put("url", u); } catch (Exception ignored) { }
            return out;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 后台拉取；完成后在主线程回调（用于刷新 UI）。 */
    static void syncAsync(final Context ctx, final Runnable after) {
        new Thread(() -> {
            sync(ctx);
            if (after != null) {
                try {
                    ((android.app.Activity) ctx).runOnUiThread(after);
                } catch (Throwable t) {
                    try { after.run(); } catch (Throwable ignored) { }
                }
            }
        }, "arena-sync").start();
    }

    /**
     * 启动时顺手拉一次（24h 节流）。**失败不提示、不影响任何流程** —— 场次表有内置兜底，
     * 后台拉不到只是继续用旧表，没必要打断用户。
     */
    static void autoSync(final Context ctx) {
        long at = lastFetchMs();
        if (at > 0 && System.currentTimeMillis() - at < AUTO_INTERVAL_MS) return;
        new Thread(() -> {
            JSONObject r = sync(ctx);
            if (r.optBoolean("ok") && !r.optBoolean("notModified")) {
                PfArenaTable.reload(ctx);          // 表变了要立刻生效（下轮选场就用新条件）
            }
        }, "arena-auto").start();
    }

    /* ---------------- 校验 / 落盘 ---------------- */

    /**
     * 结构校验 —— 只认「能真的拿来用」的包。
     * 必查：arenas 是对象、条数够、每条有 name（name 是 PfArenaTable 的显示键）。
     */
    static boolean valid(JSONObject root) {
        if (root == null) return false;
        if (root.optInt("schema", 0) < 2) return false;
        JSONObject a = root.optJSONObject("arenas");
        if (a == null || a.length() < MIN_ARENAS) return false;
        int named = 0;
        for (Iterator<String> it = a.keys(); it.hasNext(); ) {
            JSONObject e = a.optJSONObject(it.next());
            if (e != null && !e.optString("name", "").isEmpty()) named++;
        }
        return named >= MIN_ARENAS;
    }

    /** 上次拉成功的原始 JSON（坏文件/不存在都返回 null）。给 {@link PfArenaTable} 选源用。 */
    static JSONObject cached() {
        return readCache();
    }

    private static JSONObject readCache() {
        try {
            File f = cacheFile();
            if (!f.exists() || f.length() == 0) return null;
            InputStream in = new FileInputStream(f);
            String s = readAll(in, 1 << 20);
            in.close();
            return new JSONObject(s);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void writeCache(String body) throws Exception {
        File f = cacheFile();
        File tmp = new File(f.getAbsolutePath() + ".part");
        FileOutputStream os = new FileOutputStream(tmp);
        os.write(body.getBytes("UTF-8"));
        os.close();
        if (f.exists() && !f.delete()) SgmLog.i(T, "旧缓存删不掉 " + f);
        if (!tmp.renameTo(f)) throw new Exception("改名失败 " + tmp);
    }

    private static void writeMeta(String etag, JSONObject root) {
        try {
            JSONObject m = new JSONObject()
                    .put("etag", etag == null ? "" : etag)
                    .put("at", System.currentTimeMillis())
                    .put("count", root.optJSONObject("arenas") == null
                            ? 0 : root.optJSONObject("arenas").length())
                    .put("updated_at", root.optString("updated_at", ""))
                    .put("url", root.optString("source", ""));
            FileOutputStream os = new FileOutputStream(metaFile());
            os.write(m.toString(2).getBytes("UTF-8"));
            os.close();
        } catch (Throwable t) {
            SgmLog.i(T, "写 meta 失败: " + t);
        }
    }

    /** 304 也推进时间戳，否则「24h 节流」会退化成每次启动都请求。 */
    private static void touch() {
        try {
            JSONObject m = meta();
            m.put("at", System.currentTimeMillis());
            FileOutputStream os = new FileOutputStream(metaFile());
            os.write(m.toString(2).getBytes("UTF-8"));
            os.close();
        } catch (Throwable ignored) { }
    }

    private static void remember(boolean ok, String msg) {
        lastOk = ok;
        lastResult = msg;
    }

    private static String readAll(InputStream in, int max) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0 && bo.size() < max) bo.write(buf, 0, n);
        return new String(bo.toByteArray(), "UTF-8");
    }
}
