package com.zhiyaunhe.sgmbot;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * 使用指引 — 用户原话: "shizuku 给下载链接, 开发者给开启流程"。
 *
 * 两件事分开写, 因为受众不同:
 *   A. **下载链接** — 给拿到手机的人, 点一下就把 Shizuku 装上 (走 config.shizuku,
 *      官方 release 直链; 链接可配 ⇒ 官方换版本不用改 APK)。
 *   B. **开启流程** — 给"开发者/帮人装机的那个", 讲清每步在系统里的哪个位置、
 *      为什么要这么做、哪一步最容易卡住 (无线调试配对码 / 免 root 自启的坑)。
 *
 * 下面是「没反应怎么办」速查表 —— 这是"点了没用"类问题的第一落点, 全部对着
 * 真机踩过的现象写 (不是泛泛的 FAQ)。每一项都给"判据 + 处理"。
 */
public class HelpActivity extends Activity {
    private TextView status;
    private final Handler h = new Handler(Looper.getMainLooper());
    private volatile String latest = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        View[] pg = Ui.page(this);
        View content = pg[1];
        Ui.begin((android.view.ViewGroup) content);

        Ui.add(this, Ui.title(this, "使用指引 / Shizuku 开启流程"));
        status = Ui.statusBar(this);
        Ui.add(this, status);

        /* ---------- A. 下载 ---------- */
        Ui.add(this, Ui.section(this, "A. 装 Shizuku (先做这步)"));
        Ui.add(this, Ui.hint(this,
                "Shizuku 是「借 shell 权限」的通道 —— 本 App 靠它截屏 + 模拟点击, "
                + "不需要 root, 也不需要录屏弹窗。\n"
                + "手机没装 Shizuku 的话, 下面三个按钮任选一个。"));

        final JSONObject sh = sec("shizuku");
        final String dl = sh.optString("download_url",
                "https://github.com/RikkaApps/Shizuku/releases/latest");
        final String home = sh.optString("homepage", "https://shizuku.rikka.app/");
        final String play = sh.optString("play_url",
                "https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api");

        Ui.button(this, "① 下载 Shizuku (GitHub 官方 release 页)",
                "打开 GitHub release 页, 下最新的 shizuku-vX.apk", status,
                () -> Ui.openUrl(this, status, dl, "Shizuku 下载页"));
        Ui.button(this, "② 官网 / 使用说明 (shizuku.rikka.app)",
                "打开 Shizuku 官网 (有各机型说明)", status,
                () -> Ui.openUrl(this, status, home, "Shizuku 官网"));
        Ui.button(this, "③ Google Play 安装",
                "打开 Play 商店 (有 Play 的机器最省事)", status,
                () -> Ui.openUrl(this, status, play, "Google Play"));

        Ui.add(this, Ui.hint(this, "直链: " + dl));
        Ui.button(this, "复制直链到剪贴板 (没浏览器时用)",
                "已复制下载直链", status, () -> Ui.copy(this, status, dl));

        /* 顺手查一下最新版本号 — 纯提示, 查不到不影响下载 */
        Ui.button(this, "查最新版本号 (联网, 可选)", "联网查 Shizuku 最新 release …", status, () -> {
            status.setText("› 查询中… (无网络会失败, 不影响下载)");
            new Thread(() -> {
                final String v = fetchLatestTag(sh.optString("release_api", ""));
                h.post(() -> {
                    latest = v;
                    if (v.length() == 0) {
                        status.setText("✗ 查不到 (无网络 / GitHub 不可达) — 直接点上面下载按钮即可");
                        status.setTextColor(Ui.WARN);
                    } else {
                        status.setText("› Shizuku 最新版: " + v + " — 下载页里选同名 apk");
                        status.setTextColor(Ui.OK);
                    }
                });
            }, "shizuku-ver").start();
        });

        /* ---------- B. 开启流程 ---------- */
        Ui.add(this, Ui.section(this, "B. 开启流程 (关键: 无线调试免 root 启动)"));
        Ui.add(this, Ui.hint(this,
                "Shizuku 每次重启手机后都要重新启动一次服务 (Android 11+ 无需电脑, "
                + "用「无线调试」自己就能启)。下面 6 步按顺序做, 大约 2 分钟。"));

        step("1", "装好 Shizuku, 打开它",
                "第一次打开会看到「通过无线调试启动」和「通过电脑启动」两个选项。\n"
                + "选 **通过无线调试启动** (不用连电脑)。");
        step("2", "开发者选项 → 无线调试 (开开关)",
                "系统设置 → 关于手机 → 连点「版本号」7 次 → 开启开发者模式。\n"
                + "回到 设置 → 系统 → 开发者选项 → 找到「无线调试」并打开。\n"
                + "⚠️ 只在开发者选项里开 USB 调试**不够** —— 必须是「无线调试」这一项。");
        step("3", "配对 (要配对码, 这是最容易卡的一步)",
                "在「无线调试」页面里点 **使用配对码配对设备** → 会弹出一个 6 位配对码\n"
                + "和一个端口号。回到 Shizuku 点「配对」, 输入那 6 位码。\n"
                + "⚠️ 配对码弹窗一关就失效, 两个页面之间快速来回; 配对成功后这条就完成了,\n"
                + "   以后重启手机只需「启动」, 不用再配对。");
        step("4", "Shizuku 里点「启动」",
                "配对完成后回到 Shizuku 主界面点「启动」。\n"
                + "看到「Shizuku 正在运行」即成功 —— 通知栏常驻一条 Shizuku 通知属正常。");
        step("5", "回到本 App, 点「① 授权 Shizuku」",
                "主页第一颗按钮。会弹一个 Shizuku 授权框 → 点「允许」。\n"
                + "⚠️ 弹框不出现通常是 Shizuku 服务没在跑 (回第 4 步), 不是本 App 的问题。");
        step("6", "另外两项权限 (悬浮窗 / 所有文件访问)",
                "**悬浮窗**: 主页「② 授权悬浮窗」→ 打开「显示在应用上层」开关。\n"
                + "   没有它就没有悬浮条, 但引擎照跑 (可从 App 里控制)。\n"
                + "**所有文件访问**: 主页「③ 授权所有文件访问」→ 打开开关。\n"
                + "   没有它 App 会退到私有目录, 功能不残; 但 adb push 改配置和"
                + "「拉取更新装 APK」会受限。");

        Ui.add(this, Ui.section(this, "C. 重启手机后怎么办"));
        Ui.add(this, Ui.hint(this,
                "Shizuku 服务**不随开机自启** (这是它的设计, 不是 bug)。重启后:\n"
                + "  ① 打开 Shizuku App → 点「启动」(已配对过, 一步即可)\n"
                + "  ② 回到本 App → 主页会显示「Shizuku 服务: 在线」\n"
                + "  ③ 直接点「启动服务」即可, 不用重新授权\n"
                + "Android 13+ 且连着可信 WLAN 时, Shizuku 可开启自动启动, 会更省事。"));

        /* ---------- D. 排障 ---------- */
        Ui.add(this, Ui.section(this, "D. 「点了没反应」速查表"));
        Ui.add(this, Ui.hint(this, "上面按钮和下面这张表覆盖了实际踩过的绝大多数问题。"));

        trouble("Shizuku 服务: 未连接", "Shizuku App 里没点「启动」, 或服务被系统清了。",
                "打开 Shizuku 点「启动」→ 回本页刷新。");
        trouble("Shizuku 授权: 未授", "没弹过授权框, 或之前点了「拒绝」。",
                "点主页「① 授权 Shizuku」; 若没弹框 → 说明服务没跑 (看上一行)。");
        trouble("执行通道: 未绑定", "授权了但通道还没接上 (绑定要一两秒)。",
                "等 2~3 秒; 仍不接上就杀进程重开 App (绑定的 ServiceConnection 会重来)。");
        trouble("截屏失败 (日志刷这个)", "① 没授「所有文件访问」→ 已自动退私有目录, 一般能跑; "
                        + "② 游戏还没起来/黑屏; ③ 系统弹了权限弹窗挡住画面。",
                "先看主图能不能出 (判定路径图页有实时状态); 一直失败就去授「所有文件访问」。");
        trouble("点「拉取更新」报装不上 APK", "pm install 由 Shizuku 的 shell 执行, "
                        + "读不到 App 私有目录。",
                "授「所有文件访问」后重试。bundle 热更不受影响, 模板/配置照样能更新。");
        trouble("悬浮条不见了", "① 没授悬浮窗权限; ② 拖到屏幕外了 (会被 clamp 拉回); "
                        + "③ 点了「结束服务」把窗口一起收掉了。",
                "主页点「单独显示悬浮窗」重新挂; 拖丢了下拉通知栏切下前台会自动拉回屏内。");
        trouble("按钮点了完全不弹提示", "页面 JS/UI 整体没起来 (极罕见)。",
                "退出重进本页; 或看 logs/app.log 的 [ui] 行。");

        Ui.add(this, Ui.section(this, "E. adb 调试入口 (开发者)"));
        Ui.add(this, Ui.hint(this,
                "App 进程内自带一个只绑 127.0.0.1:8791 的调试 HTTP 服务 (对外不可达):\n"
                + "  adb forward tcp:8791 tcp:8791\n"
                + "  http://127.0.0.1:8791/           判定路径图 (节点/边全来自 config.graph)\n"
                + "  http://127.0.0.1:8791/status     引擎状态 JSON\n"
                + "  http://127.0.0.1:8791/log        app.log 全文\n"
                + "  http://127.0.0.1:8791/screencap  当前帧 JPEG\n"
                + "  http://127.0.0.1:8791/config     GET 读 / POST 写配置并热重载\n\n"
                + "adb 广播控制: adb shell am broadcast -a com.zhiyaunhe.sgmbot.CMD --es action start"));

        Ui.add(this, Ui.section(this, "F. 配置在哪"));
        Ui.add(this, Ui.hint(this, "外部根: " + Paths.root() + "\n"
                + (Paths.legacy() ? "(已授所有文件访问 — adb push 直接写这里)"
                                  : "(未授所有文件访问 — 用的 App 私有目录)")
                + "\n  config.json   运行时配置 (也可在 App「编辑配置」页改)\n"
                + "  templates/    NCC 模板素材 (随 bundle 热更)\n"
                + "  logs/         app.log / state.json\n"
                + "  store.json    今日场次 + 按日归档 (热力图数据源)"));
        Ui.button(this, "复制外部根路径",
                "已复制路径", status, () -> Ui.copy(this, status, Paths.root()));

        Ui.end();
        setContentView(pg[0]);
    }

    /* ---------------- 组件 ---------------- */

    private void step(String n, String title, String body) {
        android.widget.LinearLayout c = Ui.card(this);
        android.widget.LinearLayout head = new android.widget.LinearLayout(this);
        head.setOrientation(android.widget.LinearLayout.HORIZONTAL);

        TextView num = Ui.text(this, n, Ui.ACC, 15f);
        num.setPadding(0, 0, Ui.dp(this, 8), 0);
        head.addView(num);
        head.addView(Ui.text(this, title, Ui.TXT, 14.5f));
        c.addView(head);
        TextView b = Ui.text(this, body, Ui.SUB, 12.5f);
        b.setPadding(0, Ui.dp(this, 6), 0, 0);
        c.addView(b);

        Ui.add(this, c);
    }

    private void trouble(String sym, String why, String fix) {
        android.widget.LinearLayout c = Ui.card(this);
        c.addView(Ui.text(this, "✗ " + sym, Ui.ERR, 14f));
        TextView w = Ui.text(this, "原因: " + why, Ui.SUB, 12.5f);
        w.setPadding(0, Ui.dp(this, 4), 0, 0);
        c.addView(w);
        TextView f = Ui.text(this, "处理: " + fix, Ui.OK, 12.5f);
        f.setPadding(0, Ui.dp(this, 4), 0, 0);
        c.addView(f);
        Ui.add(this, c);
    }

    private JSONObject sec(String k) {
        try {
            Config c = BotService.cfg();
            if (c == null) c = new Config(this);
            JSONObject o = c.read().optJSONObject(k);
            return o == null ? new JSONObject() : o;
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    /** 查 Shizuku 最新 tag — 拿不到就返回 "" (纯提示, 不阻塞主流程) */
    private static String fetchLatestTag(String api) {
        if (api == null || api.length() == 0) return "";
        java.net.HttpURLConnection c = null;
        try {
            c = (java.net.HttpURLConnection) new java.net.URL(api).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestProperty("User-Agent", "sgmbot");
            java.io.InputStream in = c.getInputStream();
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            in.close();
            return new JSONObject(new String(bo.toByteArray(), "UTF-8")).optString("tag_name", "");
        } catch (Throwable t) {
            return "";
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
