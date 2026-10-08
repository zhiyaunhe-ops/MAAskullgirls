package com.zhiyaunhe.sgmbot;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import rikka.shizuku.Shizuku;

/**
 * 主页: 权限引导 + 服务/悬浮条控制 + 更新 + 入口导航。一次性设置都在这里完成。
 *
 * 2026-10-08 改造 (用户四条反馈):
 *   ① **不能滚动** → 全部内容进 ScrollView (原来 10 个按钮直接堆在 LinearLayout,
 *      小屏/横屏下最后几个被挤出屏幕且无滚动容器)。见 Ui.page()。
 *   ② **点了没反应** → 每个按钮都过 Ui.act/Ui.gate: 点击瞬时弹提示说"要做什么",
 *      前置不满足时弹**人话原因**(并尽量把你送到对应设置页), 不再静默丢弃。见 Ui.gate。
 *   ③ **悬浮窗要能单独启动** → 新增「单独显示悬浮窗 / 隐藏悬浮窗」, 不依赖服务。
 *   ④ **Shizuku 下载 + 开启流程** → 新增「使用指引」入口 (HelpActivity)。
 *   另加「每日战绩」入口 (StatsActivity, GitHub 风格热力图)。
 *
 * 状态区不再每秒整体重刷 (会打断滚动): 状态是独立 TextView, 每秒只更新它的文字;
 * 反馈行 (§) 是另一行, 点按钮/出错时写它, 不被状态刷新覆盖。
 */
public class MainActivity extends Activity {
    private TextView status;
    private TextView feed;          // 点击反馈行 (只由 Ui.act/gate 写)
    private final Handler h = new Handler(Looper.getMainLooper());

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (ShizukuCtl.serverUp() && ShizukuCtl.granted() && !ShizukuCtl.ready()) {
                ShizukuCtl.tryBind(MainActivity.this);   // 授权弹窗点完允许后, 轮询自动接上
            }
            Paths.init(MainActivity.this);   // 授权"所有文件访问"回来后自动换根, 不必重启
            status.setText(statusText());
            h.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        /* ⚠️ 第一件事 = 给未捕获异常找落盘处。pythonw/无控制台那套教训同理:
         *    这里的"没有控制台"= 用户看不到 logcat, 闪退就只剩一句"打开就退"。
         *    挂上 defaultUncaughtExceptionHandler, 把栈写进 app.log 再走默认处理。 */
        installCrashLog();

        View[] pg = Ui.page(this);
        LinearLayout root = (LinearLayout) pg[1];
        Ui.begin(root);

        Ui.add(this, Ui.title(this, "SGM挂机"));
        Ui.add(this, Ui.hint(this,
                "Shizuku 借 shell 权限做截屏 + 点击, 免 root / 免无障碍 / 无录屏弹窗。\n"
                + "第一次用: 先看「使用指引」把 Shizuku 装好开起来, 再回来点 ① ② ③。"));
        Ui.button(this, "❓ 使用指引 / Shizuku 下载 + 开启流程",
                "打开使用指引 (含 Shizuku 下载直链与 6 步开启流程)", feed,
                () -> startActivity(new Intent(this, HelpActivity.class)));

        Ui.add(this, Ui.statusBar(this), 0);   // 状态区 (下面 Ui.begin 已绑好容器)

        status = Ui.text(this, "", Ui.TXT, 13f);
        status.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        status.setBackgroundColor(0xFFEEF1F6);
        status.setLineSpacing(0f, 1.15f);
        Ui.add(this, status);

        feed = Ui.text(this, "› 就绪 — 点任意按钮都会有提示", Ui.SUB, 12.5f);
        feed.setPadding(Ui.dp(this, 4), Ui.dp(this, 2), 0, Ui.dp(this, 8));
        Ui.add(this, feed);

        /* ---------------- 一次性授权 ---------------- */
        Ui.add(this, Ui.section(this, "一次性授权"));

        Ui.button(this, "① 授权 Shizuku (弹框点「允许」)",
                "请求 Shizuku 授权 — 没弹框说明 Shizuku 服务没在跑", feed,
                Ui.gate(this, feed, Ui.NEED_SHIZUKU_SERVER, () -> connectShizuku()));

        Ui.button(this, "② 授权悬浮窗 (显示在应用上层)",
                "", feed, () -> {
                    if (Settings.canDrawOverlays(this)) {
                        Ui.toast(this, "已经授过了 — 要改就去设置页");
                        feed.setTextColor(Ui.OK);
                        feed.setText("✓ 悬浮窗权限已授 — 可点「单独显示悬浮窗」");
                        return;
                    }
                    Ui.toast(this, "去设置页打开「显示在应用上层」开关");
                    feed.setText("› 跳设置页 — 打开「显示在应用上层」后回来");
                    startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName())));
                });

        Ui.button(this, "③ 授权所有文件访问 (adb push / 装 APK 用)",
                "", feed, () -> {
                    if (Build.VERSION.SDK_INT < 30) {
                        Ui.toast(this, "Android 11 以下不需要这项权限");
                        feed.setTextColor(Ui.OK);
                        feed.setText("✓ 本机无需此权限 (Android " + Build.VERSION.RELEASE + ")");
                        return;
                    }
                    if (Environment.isExternalStorageManager()) {
                        Ui.toast(this, "已经授过了");
                        feed.setTextColor(Ui.OK);
                        feed.setText("✓ 所有文件访问已授 — adb push 直接写 " + Paths.LEGACY);
                        return;
                    }
                    Ui.toast(this, "去设置页找「所有文件访问」打开");
                    feed.setText("› 跳设置页 — 找到本 App 打开「所有文件访问」开关");
                    startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:" + getPackageName())));
                });

        /* ---------------- 服务 / 悬浮条 ---------------- */
        Ui.add(this, Ui.section(this, "服务与悬浮条"));
        Ui.add(this, Ui.hint(this,
                "「启动服务」= 起常驻服务并跑结算循环 (需要 Shizuku 就绪)。\n"
                + "「悬浮条」可以「单独」显示, 不需要起服务 —— 先摆好位置, 想跑再点悬浮条上的「开始」。"));

        Ui.button(this, "④ 启动服务 (跑结算循环)",
                "启动服务并开跑结算循环…", feed,
                Ui.gate(this, feed, Ui.NEED_CHANNEL, () ->
                        BotService.start(this, BotService.MODE_SETTLE)));

        Ui.button(this, "停止引擎 (暂停, 悬浮条/服务保留)",
                "已请求停止引擎 — 悬浮条还在, 可再点开始", feed,
                Ui.gate(this, feed, null, () -> BotService.trigger("stop")));

        Ui.button(this, "结束服务 (停引擎 + 收服务/通知/悬浮条)",
                "结束服务 — 引擎停、悬浮条与通知一并收掉", feed,
                Ui.gate(this, feed, null, () -> BotService.trigger("end")));

        /* ---- 悬浮条独立启停 (本轮新增, 关键需求) ---- */
        Ui.button(this, "单独显示悬浮窗 (不需要起服务)",
                "挂载悬浮条 (不依赖服务)…", feed, () -> {
                    String e = OverlayCtl.show(this);
                    if (e == null) {
                        feed.setTextColor(Ui.OK);
                        feed.setText("✓ 悬浮条已显示 — 可拖动/◀折叠; 点「开始」才跑");
                    } else {
                        feed.setTextColor(Ui.ERR);
                        feed.setText("✗ " + e);
                    }
                });

        Ui.button(this, "隐藏悬浮窗 (只收窗口, 不动引擎)",
                "收悬浮条…", feed, () -> {
                    if (!OverlayCtl.isShown()) {
                        Ui.toast(this, "本来就没显示悬浮条");
                        feed.setTextColor(Ui.SUB);
                        feed.setText("· 当前没有悬浮条");
                        return;
                    }
                    OverlayCtl.hide();
                    feed.setTextColor(Ui.OK);
                    feed.setText("✓ 悬浮条已隐藏 (引擎状态不变: "
                            + (BotService.isRunning() ? "运行中" : "停止") + ")");
                });

        /* ---------------- 配置 / 查看 ---------------- */
        Ui.add(this, Ui.section(this, "配置与查看"));

        Ui.button(this, "编辑配置 (扫描间隔 / 运行控制 / 更新源…)",
                "打开配置编辑页", feed,
                () -> startActivity(new Intent(this, ConfigActivity.class)));

        Ui.button(this, "重载配置 (config.json 改动立即生效)",
                "热重载 config.json + 清模板缓存", feed, () -> {
                    BotService.reload();
                    feed.setTextColor(Ui.OK);
                    feed.setText("✓ 已重载 — " + (BotService.cfg() == null
                            ? "配置层未初始化 (服务没起过), 已按默认读" : "配置/模板已刷新"));
                });

        Ui.button(this, "查看判定路径图 (实时: 走到哪一步 / 峰值多少)",
                "打开判定路径图 (本地页面, 需要 App 进程活着)", feed,
                () -> startActivity(new Intent(this, GraphActivity.class)));

        Ui.button(this, "每日战绩 (GitHub 风格热力图)",
                "打开战绩页 — 热力图 / 明细 / 口径说明", feed,
                () -> startActivity(new Intent(this, StatsActivity.class)));

        /* ---------------- 更新 ---------------- */
        Ui.add(this, Ui.section(this, "GitHub 更新"));
        Ui.add(this, Ui.hint(this,
                "bundle (模板 + 配置) 可热更, 不起停引擎; APK 装新包需要 Shizuku + 所有文件访问。"));

        Ui.button(this, "检查更新 (查 GitHub latest release)",
                "检查中… (查 release 资产的 updated_at 与本地水位比对)", feed, () -> {
                    BotService.trigger("check_update");
                    h.postDelayed(this::showUpdateResult, 2500);
                });

        Ui.button(this, "拉取更新 (bundle 热更 + 尝试装 APK)",
                "拉取中… bundle 热更; APK 走 pm install (需要 Shizuku + 所有文件访问)", feed, () -> {
                    BotService.trigger("update");
                    h.postDelayed(this::showUpdateResult, 6000);
                });

        /* ---------------- adb ---------------- */
        Ui.add(this, Ui.section(this, "adb 调试 (开发者)"));
        Ui.add(this, Ui.hint(this,
                "调试服务只绑 127.0.0.1:8791, 对外不可达。\n"
                + "  adb forward tcp:8791 tcp:8791\n"
                + "  http://127.0.0.1:8791/  → 判定路径图\n"
                + "  /status /log /screencap /config /update"));
        Ui.button(this, "复制 adb forward 命令",
                "已复制 adb 命令", feed,
                () -> Ui.copy(this, feed, "adb forward tcp:8791 tcp:8791"));

        Ui.end();
        setContentView(pg[0]);

        /* Shizuku binder 获取走 provider (manifest 已注册): server 通过
         * ShizukuProvider.call(SEND_BINDER) 把 binder 推给本进程 → Shizuku.onBinderReceived
         * → attachApplication → server 回调 bindApplication 置 serverUp/granted。
         *
         * 不用 addBinderReceivedListenerSticky 是因为回调需 Activity 存活; 这里改由
         * 1s 轮询驱动: serverUp && granted && 未绑定 → tryBind。
         * (注: 早期版本 uninstall sticky 是因为触碰 Shizuku 类就 NoClassDefFoundError —
         *  那是本机 classpath 漏编 moe.shizuku.server.* 桩所致, 已随 libs/aidl-src 编入,
         *  不是 HyperOS 的限制。此处保留轮询仅为生命周期简单。) */
        h.postDelayed(refresh, 500);
    }

    /* ---------------- 崩溃落盘 ---------------- */

    private static boolean crashHooked;

    /**
     * 未捕获异常写进 app.log。
     * 为什么必须做: 没有控制台时, "装上打开就退"这类故障**一点线索都没有** ——
     * SgmLog 的常规日志在崩溃前就已经写好了, 但异常栈无处可去。挂上 handler 之后
     * 用户可以直接把 logs/app.log 发出来, 不用连电脑抓 logcat。
     */
    private void installCrashLog() {
        if (crashHooked) return;
        crashHooked = true;
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                java.io.StringWriter sw = new java.io.StringWriter();
                e.printStackTrace(new java.io.PrintWriter(sw));
                SgmLog.i("崩溃", "线程 " + t.getName() + " 未捕获异常:\n" + sw);
            } catch (Throwable ignored) { }
            if (prev != null) prev.uncaughtException(t, e);
        });
    }

    /* ---------------- 行为 ---------------- */

    private void connectShizuku() {
        if (!ShizukuCtl.serverUp()) {
            feed.setTextColor(Ui.ERR);
            feed.setText("✗ Shizuku 服务没在跑 — 去「使用指引」看开启流程");
            return;
        }
        if (ShizukuCtl.granted()) {
            ShizukuCtl.tryBind(this);
            feed.setTextColor(Ui.OK);
            feed.setText("✓ 已授权 — 正在接通道, 2~3 秒后状态会变「就绪」");
        } else {
            Shizuku.requestPermission(ShizukuCtl.REQ_PERMISSION);
            feed.setText("› 已发出授权请求 — 弹框里点「允许」");
        }
    }

    private String statusText() {
        StringBuilder sb = new StringBuilder();
        sb.append("Shizuku 服务 ").append(mark(ShizukuCtl.serverUp(), "在线", "未连接")).append('\n');
        sb.append("Shizuku 授权 ").append(mark(ShizukuCtl.granted(), "已授", "未授")).append('\n');
        sb.append("执行通道     ").append(mark(ShizukuCtl.ready(), "就绪 (可截屏/点击)", "未绑定")).append('\n');
        boolean ov = Settings.canDrawOverlays(this);
        sb.append("悬浮窗权限   ").append(mark(ov, "已授", "未授")).append('\n');
        sb.append("悬浮条       ").append(OverlayCtl.isShown()
                ? (OverlayCtl.ownedByService() ? "显示中 (服务)" : "显示中 (单独)")
                : "未显示").append('\n');
        sb.append("所有文件访问 ").append(Paths.legacy() ? "已授" : "未授 (退私有目录)").append('\n');
        sb.append("引擎         ").append(BotService.isRunning()
                ? "运行中 (" + BotService.currentMode() + ")" : "停止").append('\n');
        Store st = Store.load();
        sb.append("战绩         ").append(st.text(0))
                .append(" · 累计 ").append(st.totalRounds()).append(" 场 / ")
                .append(st.activeDays()).append(" 天").append('\n');
        JSONObject m = cfgMeta();
        sb.append("更新源       ").append(m.optString("repo", "(未配)"))
                .append(" bundle=").append(m.optString("bv", "0"));
        String ul = Update.lastResult();
        if (ul.length() > 0) sb.append("\n更新         ").append(ul);
        sb.append("\n外部根       ").append(Paths.root());
        return sb.toString();
    }

    private static String mark(boolean ok, String yes, String no) {
        return ok ? "✓ " + yes : "✗ " + no;
    }

    /** 更新结果回显 — 之前只改一句"检查中…"就没下文了, 用户只能翻 /log 才知道成没成 */
    private void showUpdateResult() {
        h.post(() -> {
            String r = Update.lastResult();
            if (r.length() == 0) {
                feed.setTextColor(Ui.WARN);
                feed.setText("· 还没拿到结果 — 可能是网络慢; 稍后再看或翻 logs/app.log");
            } else {
                boolean ok = !r.startsWith("检查: update.repo")
                        && !r.contains("失败") && !r.contains("超时");
                feed.setTextColor(ok ? Ui.OK : Ui.ERR);
                feed.setText((ok ? "✓ " : "✗ ") + r);
            }
            status.setText(statusText());
        });
    }

    /** 状态页用的更新信息 (repo / 本地 bundle 水位) */
    private JSONObject cfgMeta() {
        try {
            Config c = BotService.cfg();
            JSONObject all = c == null ? null : c.read();
            JSONObject u = all == null ? null : all.optJSONObject("update");
            JSONObject m = all == null ? null : all.optJSONObject("meta");
            return new JSONObject()
                    .put("repo", u == null ? "" : u.optString("repo", ""))
                    .put("bv", m == null ? "0" : m.optString("bundle_version", "0"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        /* 从设置页回来后立刻刷一次, 别等 1s 轮询 */
        Paths.init(this);
        if (status != null) status.setText(statusText());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        h.removeCallbacks(refresh);
    }
}
