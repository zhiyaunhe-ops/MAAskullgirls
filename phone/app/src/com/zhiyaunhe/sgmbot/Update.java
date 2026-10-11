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
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicInteger;
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
 * ⚠️ APK 自更新要求下载目录**对 shell 可读**: pm install 由 Shizuku(shell uid) 执行,
 *    shell 读不到 App 私有目录 (/sdcard/Android/data/<pkg>/files) ⇒ 只热更 bundle
 *    (bundle 是 App 自己读, 私有目录照样能用)。
 *    判据是**让 shell 实测 `test -r`**, 不是看 App 侧"授没授权"的旗标 —— 旗标曾因
 *    Paths.init 的提前返回而恒为 false, 导致早有授权的机器被误报"shell 读不到"
 *    (2026-10-11 报障, 见 installApk 注释)。
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

    /* ---------------- 进度 / 结果 (UI 直接读; 全部只在进程内, 不写盘) ----------------
     * ⚠️ 为什么加这一组 (2026-10-11 用户报障「没有进度 / 没有提示 / 版本号不变」):
     *    原来 UI 是 `postDelayed(showUpdateResult, 2500/6000)` —— **只读一眼**。
     *    国内直连 api.github.com(查询) 与 objects.githubusercontent.com(下载资产) 都不快,
     *    那一拍结果还没回来 ⇒ 显示「还没拿到结果」, 而且**之后再也不刷新**。
     *    加上下载全程没有任何进度上报, 用户看到的就是"点了没反应"。
     *    这里把"在不在跑 / 跑到哪 / 结果级别"变成可读的状态, 由 UI 轮询。 */

    /** 在跑的重入计数 (>0 ⇒ busy)。check 会内嵌在 apply 里, 所以用计数而不是布尔 */
    private static final AtomicInteger BUSY = new AtomicInteger();
    /** 当前阶段的人话 (查询/下载/解包/安装) */
    private static volatile String stage = "";
    /** 0~100; -1 = 该阶段没有确定进度 (查询/解包/安装都算"不确定") */
    private static volatile int pct = -1;
    /** 最近一轮结果的原始 JSON —— UI 判成败用这个, 别再拿 brief 文案猜 */
    private static volatile JSONObject lastJson = null;
    /** 最近一轮结果的级别 (默认 0 = LV_OK; 常量声明在下方) */
    private static volatile int lastLevel = 0;
    /** 最近一次网络失败的具体原因 (HTTP 码 / 异常), 让"取 release 失败"不再是黑盒 */
    private static volatile String netErr = "";

    public static final int LV_OK = 0;      // 全成
    public static final int LV_WARN = 1;    // 有部分被跳过/失败 (如 APK 装不了) —— 黄, 不是绿
    public static final int LV_ERR = 2;     // 整体失败

    /** 是否正在跑 (UI 轮询进度用) */
    public static boolean busy() { return BUSY.get() > 0; }
    /** 当前阶段人话 */
    public static String stage() { return stage; }
    /** 0~100; -1 = 不确定 */
    public static int pct() { return pct; }
    /** 最近一轮结果级别: {@link #LV_OK} / {@link #LV_WARN} / {@link #LV_ERR} */
    public static int lastLevel() { return lastLevel; }
    /** 最近一轮结果原始 JSON (null = 还没跑过) */
    public static JSONObject lastJson() { return lastJson; }

    private static void prog(String s, int p) { stage = s; pct = p; }

    private Update() { }

    /** 给 UI 用的一句话摘要 (空 = 还没查过) */
    public static String lastResult() { return lastResult; }

    /**
     * ISO-8601 (UTC, GitHub 资产用的格式) → 本地 "MM-dd HH:mm"。
     * ⚠️ 必须转本地时区: 直接显 UTC 会比北京晚 8 小时, 用户刚更新完看到"10:16"
     *    会以为"根本没更新到现在"。
     */
    public static String fmtStamp(String iso) {
        if (iso == null) return "";
        if (iso.length() < 19) return iso;
        try {
            SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            in.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date d = in.parse(iso.substring(0, 19));
            return new SimpleDateFormat("MM-dd HH:mm", Locale.US).format(d);
        } catch (Exception e) {
            return iso;
        }
    }

    /**
     * 人类可读的一句话 (主页状态区 / 反馈行显示)。
     *
     * ⚠️ 检查结果里**不显示 tag** (原为 "(tag=latest)"): CI 是固定 tag 反复 --clobber
     *    覆盖同名资产, tag 恒定不变 ⇒ 用户看到"版本号还是 latest"以为没更新
     *    (2026-10-11 报障)。真正会变的是资产的 updated_at, 所以改成显示它的本地时间。
     */
    private static String brief(JSONObject r, boolean applied) {
        if (r == null) return "结果为空";
        String pre = applied ? "拉取: " : "检查: ";
        if (!r.optBoolean("ok")) return pre + r.optString("msg", "失败");
        boolean nb = r.optBoolean("need_bundle"), na = r.optBoolean("need_apk");
        if (applied) {
            int n = r.optInt("bundle_files", -1);
            JSONObject apk = r.optJSONObject("apk");
            return pre + "bundle " + (r.has("bundle_err") ? "下载失败"
                            : (n < 0 ? (nb ? "失败" : "已最新") : n + " 文件已更新"))
                    + "; apk " + (apk == null ? (na ? "失败" : "已最新")
                            : (apk.optBoolean("ok") ? "已安装" : apk.optString("msg", "跳过")));
        }
        return pre
                + "bundle " + (nb ? "有更新 " : "已最新 ") + stamp(r, "bundle")
                + "; apk " + (na ? "有更新 " : "已最新 ") + stamp(r, "apk");
    }

    /** 远端资产的更新时间 (本地时区短串) —— 这才是"版本"的真实信号 */
    private static String stamp(JSONObject r, String which) {
        JSONObject a = r.optJSONObject(which);
        if (a == null) return "(无此资产)";
        String s = fmtStamp(a.optString("updated_at", ""));
        return s.length() == 0 ? "" : "(" + s + ")";
    }

    /**
     * 结果级别 —— 决定 UI 用 ✓ / ! / ✗ 哪个记号。
     *
     * ⚠️ 原来 UI 是在 brief **文案里找"失败"两个字**判成败
     *    (MainActivity.showUpdateResult 旧实现):
     *      boolean ok = !r.startsWith("检查: update.repo") && !r.contains("失败") && !r.contains("超时");
     *    而 "拉取: update.repo 未配 — config.json 里填 \"owner/name\"" 整句里
     *    **没有"失败"也没有"超时"** ⇒ 判成成功 ⇒ **错误被显示成绿色 ✓**
     *    (2026-10-11 用户报障「没有什么提示」的很大一部分: 绿色✓配一句看不懂的话)。
     *    改由这里给出结构化级别, UI 不再猜字符串。
     */
    private static int levelOf(JSONObject r, boolean applied) {
        if (r == null || !r.optBoolean("ok")) return LV_ERR;
        if (!applied) return LV_OK;                      // 纯检查能走到这 = 没出错
        JSONObject apk = r.optJSONObject("apk");
        boolean partBad = r.has("bundle_err") || (apk != null && !apk.optBoolean("ok"));
        return partBad ? LV_WARN : LV_OK;                // 有部分被跳过 ⇒ 黄, 不是绿
    }

    /** 记下最近结果供 UI 回显 (结构化级别 + 原始 JSON + 一句话) */
    private static JSONObject remember(JSONObject r, boolean applied) {
        lastJson = r;
        lastLevel = levelOf(r, applied);
        try { lastResult = brief(r, applied); } catch (Throwable ignored) { }
        return r;
    }

    /* ---------------- 检查 ---------------- */

    /**
     * 查 latest release, 与本地 meta 水位比对。
     * 返回 {ok, tag, published_at, need_bundle, need_apk, ver_bundle, ver_apk,
     *       bundle:{name,url,size,updated_at}, apk:{...}, cur:{bundle,apk}}
     *
     * 外面这层只管 busy 计数 (UI 轮询进度用); 逻辑在 check0。
     */
    public static JSONObject check(Config cfg) {
        BUSY.incrementAndGet();
        try {
            return check0(cfg);
        } finally {
            BUSY.decrementAndGet();
        }
    }

    private static JSONObject check0(Config cfg) {
        prog("查询 GitHub release…", -1);
        netErr = "";
        JSONObject u = sec(cfg);
        String repo = u.optString("repo", "");
        if (repo.length() == 0)
            return remember(err("update.repo 未配 — config.json 的 update.repo 填 \"owner/name\""
                    + (cfg == null ? " (配置层没拿到)" : "")), false);
        JSONObject rel = fetchJson(String.format(API, repo));
        if (rel == null)
            return remember(err("取 release 失败 — "
                    + (netErr.length() > 0 ? netErr : "无网络")
                    + " (" + repo + ")"), false);
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
                    .put("apk_ok", Paths.legacy()), false);     // 仅"授权状态"供界面读数; 真正的装不装由 installApk 里 shell 实测决定
        } catch (Exception e) {
            return remember(err("组装结果失败: " + e), false);
        }
    }

    /* ---------------- 应用 ---------------- */

    /**
     * 拉更新并落地。bundle 总是做; APK 仅在 withApk && update.allow_apk 时。
     * 返回 {ok, bundle_files, apk:{...} , reloaded}
     */
    public static JSONObject apply(Context ctx, Config cfg, boolean withApk) {
        BUSY.incrementAndGet();
        try {
            return apply0(ctx, cfg, withApk);
        } finally {
            BUSY.decrementAndGet();
        }
    }

    private static JSONObject apply0(Context ctx, Config cfg, boolean withApk) {
        prog("查询远端版本…", -1);
        JSONObject st = check(cfg);
        /* ⚠️ 失败也要过 remember —— 原来这里只写 lastResult, 于是 lastJson/lastLevel
         *    还停在上一次的值 ⇒ UI 会拿旧的"成功"状态配新的错误文案 (2026-10-11 修)。 */
        if (!st.optBoolean("ok")) return remember(st, true);
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
                prog("解包 bundle…", -1);
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
                prog("安装 APK…", -1);
                try { out.put("apk", installApk(cfg, apk, st.optString("ver_apk"))); }
                catch (Exception ignored) { }
            } else {
                SgmLog.i(T, "APK 下载失败");
            }
        } else if (withApk) {
            SgmLog.i(T, "APK 已是最新 (" + st.optString("cur_apk") + ")");
        }

        /* 3) 让引擎重新读 config / 清模板缓存 */
        prog("重载配置…", -1);
        BotService.trigger("reload");
        try { out.put("reloaded", true); } catch (Exception ignored) { }
        return remember(out, true);
    }

    /** pm install -r; 2>&1 是因为 pm 的结果在新版上走 stderr, 只抓 stdout 会看到空输出 */
    private static JSONObject installApk(Config cfg, File apk, String ver) {
        JSONObject r = new JSONObject();
        String path = apk.getAbsolutePath();
        try { r.put("path", path); } catch (Exception ignored) { }
        if (BotService.sh() == null || !ShizukuCtl.ready()) {
            String msg = "Shizuku 未就绪 — 装不了 APK (bundle 不受影响)";
            SgmLog.i(T, msg);
            try { r.put("ok", false).put("msg", msg); } catch (Exception ignored) { }
            return r;
        }
        /* ⚠️⚠️ 判据是**让 shell 自己去读一下这个文件**, 不是看 App 侧的 legacy 旗标。
         *
         *   旗标回答的是"App 认为自己有没有授权", 与"shell 到底读不读得到这个路径"是两件事。
         *   2026-10-11 的报障就是旗标自己错了 (Paths.init 提前返回, 见 Paths 注释):
         *   用户早就授了权, 却被我们一口咬定"未授", APK 直接跳过 —— **状态推断错 ⇒
         *   功能静默关闭**, 而且文案还主动把人往错误方向带。
         *
         *   现在由 shell 用 `test -r` 回一句话, 命令的真假不依赖任何 App 侧推断;
         *   legacy 只用来在失败时补一句"显示已授但实测读不了"这种有信息量的差别。
         *   ⚠️ 用独有标记串 (SGMBOT_READABLE) 而不是解析 `test` 的退出码 —— exec() 只回
         *      stdout, 拿不到 exit status, 靠输出里的固定串才稳。 */
        String probe = ShizukuCtl.exec("test -r \"" + path + "\" && echo SGMBOT_READABLE"
                + " || echo SGMBOT_DENIED");
        boolean canRead = probe != null && probe.indexOf("SGMBOT_READABLE") >= 0;
        if (!canRead) {
            String msg = "shell 读不到 " + path + " (所有文件访问"
                    + (Paths.legacy() ? "显示已授, 但实测读不了 —— 多半是路径/授权已失效"
                                      : "未授")
                    + ") — 跳过 APK, bundle 已可热更";
            SgmLog.i(T, msg + " | probe=" + (probe == null ? "" : probe.trim()));
            try { r.put("ok", false).put("msg", msg); } catch (Exception ignored) { }
            return r;
        }
        String out = ShizukuCtl.exec("pm install -r \"" + path + "\" 2>&1");
        boolean ok = out != null && out.indexOf("Success") >= 0;
        /* ⚠️ 降级被拒是「本地构建包压着」的典型症状: build-local.sh 故意把本地包的
         *    versionCode 抬到 9999 (好让本地包能盖住 CI 包), 反过来 CI 包就永远盖不回
         *    本地包 —— pm 回 INSTALL_FAILED_VERSION_DOWNGRADE, 而**功能看起来是"装不上"**。
         *    先带 -d 再试一次 (平台只对 debuggable 包放行降级, 所以不保证成), 不成也要把
         *    原因 + 出路写进 msg 让 UI 显示得出来, 别让人对着"装不上"干瞪眼。 */
        boolean downgrade = out != null && out.indexOf("VERSION_DOWNGRADE") >= 0;
        if (!ok && downgrade) {
            SgmLog.i(T, "版本降级被拒 — 重试 pm install -r -d");
            out = ShizukuCtl.exec("pm install -r -d \"" + path + "\" 2>&1");
            ok = out != null && out.indexOf("Success") >= 0;
        }
        SgmLog.i(T, "pm install " + (ok ? "成功" : "失败") + " — " + (out == null ? "" : out.trim()));
        if (ok) putMeta(cfg, "apk_version", ver);
        try {
            r.put("ok", ok).put("out", out == null ? "" : out.trim());
            /* 以前这条路径**不写 msg** ⇒ brief() 取默认值"跳过", 明明 pm 报了一堆原因,
             * 界面上只有"apk 跳过"三个字 (用户报障"没有什么提示"的一部分)。 */
            if (!ok) r.put("msg", downgrade
                    ? "机上版本比远端的还新 (本地构建包 versionCode=9999 会一直压住 CI 包)"
                      + " — 卸载重装一次即可"
                    : "pm install 失败 — " + firstLine(out));
        } catch (Exception ignored) { }
        return r;
    }

    /** pm 输出取第一行非空 —— 塞进一句话文案里用的 (完整输出在 out / 日志里) */
    private static String firstLine(String s) {
        if (s == null) return "(无输出)";
        for (String ln : s.split("\\r?\\n")) {
            String t = ln.trim();
            if (t.length() > 0) return t.length() > 120 ? t.substring(0, 120) + "…" : t;
        }
        return "(无输出)";
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
                netErr = "HTTP " + code;
                SgmLog.i(T, "HTTP " + code + " — " + url);
                return null;
            }
            return new JSONObject(readAll(c.getInputStream(), 262144));
        } catch (Exception e) {
            netErr = e.toString();
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
            if (code != 200) {
                netErr = "下载 HTTP " + code;
                SgmLog.i(T, "下载 HTTP " + code);
                return false;
            }
            /* 进度上报 —— 没有它 UI 只能干等 (用户报障"没有进度")。
             * Content-Length 未必有 (chunked/重定向后可能丢), 到时退化成显示已收 KB。 */
            int len = c.getContentLength();
            String nm = dst.getName();
            prog("下载 " + nm, 0);
            InputStream in = c.getInputStream();
            OutputStream os = new FileOutputStream(tmp);
            byte[] buf = new byte[32768];
            int n, lastPct = -1;
            long total = 0;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
                total += n;
                if (len > 0) {
                    int p = (int) (total * 100 / len);
                    if (p != lastPct) { lastPct = p; prog("下载 " + nm + " " + (total / 1024) + "KB", p); }
                } else {
                    prog("下载 " + nm + " " + (total / 1024) + "KB", -1);
                }
            }
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
        JSONObject u = c == null ? null : c.optJSONObject("update");
        /* ⚠️ 不能返回 null: 调用方都是 `sec(cfg).optString(...)`, 一 NPE 就是整条更新链
         *    无声失败 (在 background 线程里, UI 只看到"没反应")。
         *    optJSONObject 在**键不存在**时返回 null, 而这是完全可能的:
         *    用户手改过 config、config_patch.json 覆盖后丢了 update 段、assets 装了旧包。
         *    下面 meta() 一直是这么防御的, sec() 漏了 (2026-10-11 UpdateProbe 首次跑就抓到)。 */
        return u == null ? new JSONObject() : u;
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
