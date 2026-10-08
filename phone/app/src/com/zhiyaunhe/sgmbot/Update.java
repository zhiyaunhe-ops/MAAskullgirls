package com.zhiyaunhe.sgmbot;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * GitHub 拉取更新 — 四原则之「可更新」: 换模板/改配置不必重装 APK, 装新 APK 不必连电脑。
 *
 * 数据源: repo 的 **latest release** 资产 (CI 每次 push 自动发, 见
 * .github/workflows/build-apk.yml):
 *   bundle.zip   = templates/*.png + config.json   → 热更 (不重启引擎, 下轮生效)
 *   sgmbot.apk   = 整包                            → 需 Shizuku 执行 pm install -r
 *
 * 版本判据用资产的 **updated_at** (每次上传必变, 比 tag 可靠 —— CI 是固定 tag "latest"
 * 反复 --clobber 覆盖同名资产, tag 恒定不变, 只有 updated_at 会动)。
 * 本地水位记在 config 的 meta 段: meta.bundle_version / meta.apk_version。
 *
 * config 段 (assets/config.json → update):
 *   {"repo":"owner/name", "bundle_asset":"bundle.zip", "apk_asset":"sgmbot.apk",
 *    "auto_check_h":12, "allow_apk":true}
 *
 * ⚠️ APK 自更新要求**已授所有文件访问**: pm install 由 Shizuku(shell uid) 执行,
 *    shell 读不到 App 私有目录 (/sdcard/Android/data/<pkg>/files) ⇒ 未授时跳过 APK,
 *    只热更 bundle (bundle 是 App 自己读, 私有目录照样能用)。
 * 入口: 主页按钮 / 广播 CMD?action=update / HTTP GET /update 与 POST /update?apk=1。
 */
public final class Update {
    private static final String API = "https://api.github.com/repos/%s/releases/latest";
    private static final int TIMEOUT = 15000;
    private static final String T = "更新";

    /** 上次自动检查的时刻 (进程内; 服务是长驻的, 不写盘免得每次开跑都改 config) */
    private static volatile long lastCheckMs = 0;
    /** 最近一次检查/拉取的一句话结果 — 主页直接回显, 不用翻 /log (2026-10-08) */
    private static volatile String lastResult = "";

    private Update() { }

    /** 给 UI 用的一句话摘要 (空 = 还没查过) */
    public static String lastResult() { return lastResult; }

    /** 人类可读的一句话 (主页状态区显示) */
    private static String brief(JSONObject r, boolean applied) {
        if (r == null) return "结果为空";
        String pre = applied ? "拉取: " : "检查: ";
        if (!r.optBoolean("ok")) return pre + r.optString("msg", "失败");
        boolean nb = r.optBoolean("need_bundle"), na = r.optBoolean("need_apk");
        if (applied) {
            int n = r.optInt("bundle_files", -1);
            JSONObject apk = r.optJSONObject("apk");
            return pre + "bundle=" + (n < 0 ? (nb ? "失败" : "已最新") : n + " 文件")
                    + " apk=" + (apk == null ? (na ? "失败" : "已最新")
                            : (apk.optBoolean("ok") ? "已安装" : apk.optString("msg", "跳过")));
        }
        return pre + "bundle" + (nb ? "有更新" : "已最新")
                + " apk" + (na ? "有更新" : "已最新")
                + " (tag=" + r.optString("tag", "-") + ")";
    }

    /* ---------------- 检查 ---------------- */

    /**
     * 查 latest release, 与本地 meta 水位比对。
     * 返回 {ok, tag, published_at, need_bundle, need_apk, ver_bundle, ver_apk,
     *       bundle:{name,url,size,updated_at}, apk:{...}, cur:{bundle,apk}}
     */
    public static JSONObject check(Config cfg) {
        JSONObject u = sec(cfg);
        String repo = u.optString("repo", "");
        if (repo.length() == 0) return remember(err("update.repo 未配 — config.json 里填 \"owner/name\""), false);
        JSONObject rel = fetchJson(String.format(API, repo));
        if (rel == null) return remember(err("取 release 失败 (无网络 / 仓库不存在 / 还没有 release)"), false);
        if (rel.has("message") && !rel.has("assets"))
            return remember(err("GitHub: " + rel.optString("message")), false);

        JSONArray as = rel.optJSONArray("assets");
        JSONObject b = pick(as, u.optString("bundle_asset", "bundle.zip"));
        JSONObject a = pick(as, u.optString("apk_asset", "sgmbot.apk"));
        JSONObject m = meta(cfg);
        String curB = m.optString("bundle_version", "0");
        String curA = m.optString("apk_version", "");
        String vB = b == null ? "" : b.optString("updated_at", "");
        String vA = a == null ? "" : a.optString("updated_at", "");

        try {
            return remember(new JSONObject()
                    .put("ok", true)
                    .put("tag", rel.optString("tag_name", ""))
                    .put("published_at", rel.optString("published_at", ""))
                    .put("repo", repo)
                    .put("bundle", b == null ? JSONObject.NULL : b)
                    .put("apk", a == null ? JSONObject.NULL : a)
                    .put("cur_bundle", curB).put("cur_apk", curA)
                    .put("ver_bundle", vB).put("ver_apk", vA)
                    .put("need_bundle", b != null && vB.length() > 0 && !vB.equals(curB))
                    .put("need_apk", a != null && vA.length() > 0 && !vA.equals(curA))
                    .put("apk_ok", Paths.legacy()), false);     // 未授所有文件访问 ⇒ shell 读不到, 装不了
        } catch (Exception e) {
            return remember(err("组装结果失败: " + e), false);
        }
    }

    /** 记下最近结果供 UI 回显 */
    private static JSONObject remember(JSONObject r, boolean applied) {
        try { lastResult = brief(r, applied); } catch (Throwable ignored) { }
        return r;
    }

    /* ---------------- 应用 ---------------- */

    /**
     * 拉更新并落地。bundle 总是做; APK 仅在 withApk && update.allow_apk 时。
     * 返回 {ok, bundle_files, apk:{...} , reloaded}
     */
    public static JSONObject apply(Context ctx, Config cfg, boolean withApk) {
        JSONObject st = check(cfg);
        if (!st.optBoolean("ok")) { lastResult = brief(st, true); return st; }
        JSONObject out = new JSONObject();
        try {
            out.put("ok", true).put("tag", st.optString("tag"));
        } catch (Exception ignored) { }

        /* 1) bundle: 模板 + 配置, 热更 (引擎下轮即生效, 不必停) */
        if (st.optBoolean("need_bundle")) {
            JSONObject b = st.optJSONObject("bundle");
            File dir = new File(Paths.updateDir());
            if (!dir.exists() && !dir.mkdirs()) SgmLog.i(T, "建目录失败 " + dir);
            File zip = new File(dir, "bundle.zip");
            if (b != null && down(b.optString("url"), zip)) {
                int n = unzip(zip, new File(Paths.root()));
                putMeta(cfg, "bundle_version", st.optString("ver_bundle"));
                SgmLog.i(T, "bundle 已应用 " + n + " 个文件 → " + Paths.root());
                try { out.put("bundle_files", n); } catch (Exception ignored) { }
            } else {
                SgmLog.i(T, "bundle 下载失败 — 保持旧模板");
                try { out.put("bundle_err", "download"); } catch (Exception ignored) { }
            }
        } else {
            SgmLog.i(T, "bundle 已是最新 (" + st.optString("cur_bundle") + ")");
        }

        /* 2) APK: 走 Shizuku pm install -r */
        JSONObject u = sec(cfg);
        boolean allow = u.optBoolean("allow_apk", true);
        if (withApk && !allow) {
            SgmLog.i(T, "update.allow_apk=false — 跳过 APK (只热更 bundle)");
        } else if (withApk && st.optBoolean("need_apk")) {
            JSONObject a = st.optJSONObject("apk");
            File dir = new File(Paths.updateDir());
            if (!dir.exists() && !dir.mkdirs()) SgmLog.i(T, "建目录失败 " + dir);
            File apk = new File(dir, "sgmbot.apk");
            if (a != null && down(a.optString("url"), apk)) {
                try { out.put("apk", installApk(cfg, apk, st.optString("ver_apk"))); }
                catch (Exception ignored) { }
            } else {
                SgmLog.i(T, "APK 下载失败");
            }
        } else if (withApk) {
            SgmLog.i(T, "APK 已是最新 (" + st.optString("cur_apk") + ")");
        }

        /* 3) 让引擎重新读 config / 清模板缓存 */
        BotService.trigger("reload");
        try { out.put("reloaded", true); } catch (Exception ignored) { }
        return remember(out, true);
    }

    /** pm install -r; 2>&1 是因为 pm 的结果在新版上走 stderr, 只抓 stdout 会看到空输出 */
    private static JSONObject installApk(Config cfg, File apk, String ver) {
        JSONObject r = new JSONObject();
        try { r.put("path", apk.getAbsolutePath()); } catch (Exception ignored) { }
        if (!Paths.legacy()) {
            String msg = "未授「所有文件访问」— shell 读不到 " + apk.getParent()
                    + ", 跳过 APK (bundle 已可热更)";
            SgmLog.i(T, msg);
            try { r.put("ok", false).put("msg", msg); } catch (Exception ignored) { }
            return r;
        }
        if (BotService.sh() == null || !ShizukuCtl.ready()) {
            String msg = "Shizuku 未就绪 — 装不了 APK (bundle 不受影响)";
            SgmLog.i(T, msg);
            try { r.put("ok", false).put("msg", msg); } catch (Exception ignored) { }
            return r;
        }
        String out = ShizukuCtl.exec("pm install -r \"" + apk.getAbsolutePath() + "\" 2>&1");
        boolean ok = out != null && out.indexOf("Success") >= 0;
        SgmLog.i(T, "pm install " + (ok ? "成功" : "失败") + " — " + (out == null ? "" : out.trim()));
        if (ok) putMeta(cfg, "apk_version", ver);
        try {
            r.put("ok", ok).put("out", out == null ? "" : out.trim());
        } catch (Exception ignored) { }
        return r;
    }

    /* ---------------- 自动检查 (开跑时顺手, 不阻塞) ---------------- */

    /** update.auto_check_h>0 时, 每 N 小时在后台查一次 (有更新只记日志, 不自动装 APK) */
    public static void autoCheck(final Context ctx, final Config cfg, final boolean withApk) {
        JSONObject u = sec(cfg);
        int h = u.optInt("auto_check_h", 12);
        if (h <= 0) return;
        long now = System.currentTimeMillis();
        if (lastCheckMs != 0 && now - lastCheckMs < h * 3600000L) return;
        lastCheckMs = now;
        new Thread(() -> {
            try {
                JSONObject st = check(cfg);
                if (!st.optBoolean("ok")) {
                    SgmLog.i(T, "自动检查: " + st.optString("msg"));
                    return;
                }
                boolean nb = st.optBoolean("need_bundle"), na = st.optBoolean("need_apk");
                if (!nb && !na) { SgmLog.i(T, "自动检查: 已是最新"); return; }
                SgmLog.i(T, "发现更新 bundle=" + nb + " apk=" + na
                        + " (tag=" + st.optString("tag") + ") — 应用 bundle"
                        + (withApk ? "+apk" : ""));
                apply(ctx, cfg, withApk);
            } catch (Throwable t) {
                SgmLog.i(T, "自动检查异常: " + t);
            }
        }, "upd-check").start();
    }

    /* ---------------- 网络 / 文件 ---------------- */

    private static JSONObject fetchJson(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(TIMEOUT);
            c.setReadTimeout(TIMEOUT);
            c.setRequestProperty("Accept", "application/vnd.github+json");
            c.setRequestProperty("User-Agent", "sgmbot");
            int code = c.getResponseCode();
            if (code != 200) {
                SgmLog.i(T, "HTTP " + code + " — " + url);
                return null;
            }
            return new JSONObject(readAll(c.getInputStream(), 262144));
        } catch (Exception e) {
            SgmLog.i(T, "取 " + url + " 失败: " + e);
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static boolean down(String url, File dst) {
        if (url == null || url.length() == 0) return false;
        HttpURLConnection c = null;
        try {
            File tmp = new File(dst.getAbsolutePath() + ".part");
            if (tmp.exists()) tmp.delete();
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(TIMEOUT);
            c.setReadTimeout(60000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("Accept", "application/octet-stream");
            c.setRequestProperty("User-Agent", "sgmbot");
            int code = c.getResponseCode();
            if (code != 200) { SgmLog.i(T, "下载 HTTP " + code); return false; }
            InputStream in = c.getInputStream();
            OutputStream os = new FileOutputStream(tmp);
            byte[] buf = new byte[32768];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) { os.write(buf, 0, n); total += n; }
            os.close();
            in.close();
            if (dst.exists()) dst.delete();
            if (!tmp.renameTo(dst)) { SgmLog.i(T, "改名失败 " + tmp); return false; }
            SgmLog.i(T, "下载 " + dst.getName() + " " + (total / 1024) + "KB");
            return true;
        } catch (Exception e) {
            SgmLog.i(T, "下载失败: " + e);
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /**
     * 解包 bundle.zip → root。
     * 只取两类: templates/** → Paths.tplDir(); config.json → **只补新键** (不覆盖本地手改);
     * config_patch.json → 强制覆盖 (仓库侧想改阈值时用这个)。
     */
    private static int unzip(File zip, File root) {
        int n = 0;
        ZipInputStream z = null;
        try {
            z = new ZipInputStream(new FileInputStream(zip));
            ZipEntry e;
            byte[] buf = new byte[32768];
            while ((e = z.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String name = e.getName();
                int ti = name.indexOf("templates/");
                File dst;
                boolean patch = false;
                if (ti >= 0) {
                    dst = new File(Paths.tplDir(), name.substring(ti + "templates/".length()));
                } else if (name.endsWith("config_patch.json")) {
                    dst = new File(Paths.config()); patch = true;
                } else if (name.endsWith("config.json")) {
                    dst = new File(Paths.config());
                } else {
                    continue;                     // 其余一律不落地 (安全)
                }
                // zip-slip: 目标必须仍在预期目录内
                String can = dst.getCanonicalPath();
                String base = (ti >= 0 ? new File(Paths.tplDir()) : root).getCanonicalPath();
                if (!can.startsWith(base)) { SgmLog.i(T, "跳过越界条目 " + name); continue; }
                File p = dst.getParentFile();
                if (p != null && !p.exists()) p.mkdirs();

                if (dst.getName().endsWith(".json")) {
                    String inc = readAll(z, 262144);
                    JSONObject cur = loadOrEmpty(dst);      // 本地 override 优先
                    JSONObject merged = patch ? Config.merge(cur, new JSONObject(inc))
                            : mergeNew(cur, new JSONObject(inc));
                    write(dst, merged.toString(2));
                } else {
                    FileOutputStream os = new FileOutputStream(dst);
                    int k;
                    while ((k = z.read(buf)) > 0) os.write(buf, 0, k);
                    os.close();
                }
                n++;
            }
        } catch (Exception ex) {
            SgmLog.i(T, "解包失败: " + ex);
        } finally {
            if (z != null) try { z.close(); } catch (Exception ignored) { }
        }
        return n;
    }

    /** 递归合并: **只补 incoming 里有而 cur 里没有的键** (本地手改优先, 新键自动补上) */
    static JSONObject mergeNew(JSONObject cur, JSONObject inc) {
        try {
            JSONObject out = new JSONObject(cur.toString());
            java.util.Iterator<String> it = inc.keys();
            while (it.hasNext()) {
                String k = it.next();
                if (out.has(k)) {
                    Object a = out.get(k), b = inc.get(k);
                    if (a instanceof JSONObject && b instanceof JSONObject)
                        out.put(k, mergeNew((JSONObject) a, (JSONObject) b));
                    continue;                     // 已有 → 保留本地
                }
                out.put(k, inc.get(k));
            }
            return out;
        } catch (Exception e) {
            return cur;
        }
    }

    /* ---------------- config meta 水位 ---------------- */

    static JSONObject sec(Config cfg) {
        JSONObject c = cfg == null ? null : cfg.read();
        return c == null ? new JSONObject() : c.optJSONObject("update");
    }

    private static JSONObject meta(Config cfg) {
        JSONObject c = cfg == null ? null : cfg.read();
        JSONObject m = c == null ? null : c.optJSONObject("meta");
        return m == null ? new JSONObject() : m;
    }

    private static void putMeta(Config cfg, String key, String val) {
        try {
            JSONObject ov = readOverride();
            JSONObject m = ov.optJSONObject("meta");
            if (m == null) { m = new JSONObject(); ov.put("meta", m); }
            m.put(key, val);
            write(new File(Paths.config()), ov.toString(2));
        } catch (Exception e) {
            SgmLog.i(T, "写 meta 失败: " + e);
        }
    }

    private static JSONObject readOverride() {
        return loadOrEmpty(new File(Paths.config()));
    }

    private static JSONObject loadOrEmpty(File f) {
        try {
            if (f.exists() && f.length() > 0) return Config.load(new FileInputStream(f));
        } catch (Exception ignored) { }
        return new JSONObject();
    }

    /* ---------------- 小工具 ---------------- */

    private static JSONObject pick(JSONArray as, String name) {
        if (as == null) return null;
        for (int i = 0; i < as.length(); i++) {
            JSONObject a = as.optJSONObject(i);
            if (a != null && name.equals(a.optString("name"))) return a;
        }
        return null;
    }

    private static JSONObject err(String msg) {
        try { return new JSONObject().put("ok", false).put("msg", msg); }
        catch (Exception e) { return new JSONObject(); }
    }

    private static String readAll(InputStream in, int max) throws Exception {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0 && bo.size() < max) bo.write(buf, 0, n);
        return new String(bo.toByteArray(), "UTF-8");
    }

    private static void write(File f, String s) throws Exception {
        File p = f.getParentFile();
        if (p != null && !p.exists()) p.mkdirs();
        FileOutputStream os = new FileOutputStream(f);
        os.write(s.getBytes("UTF-8"));
        os.close();
    }
}
