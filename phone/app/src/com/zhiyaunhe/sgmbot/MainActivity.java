package com.zhiyaunhe.sgmbot;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
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
 * 2026-10-11 瘦身 (用户原话: 「APK 里面还有什么启动服务…这都是在悬浮窗里面可以单独
 * 点的呀, 你就不用在那里再弄一次了」):
 *   **主页不再放任何运行控制** —— 启动服务/停止引擎/结束服务三键全删, 「单独显示 /
 *   隐藏悬浮窗」并成一个切换键。理由: 悬浮窗五键 (开始/导航/暂停/结束/◀) 已经覆盖
 *   全部动作, 主页再摆一套 = 同一个动作两条路, 而两条路的**前置检查是不同的**
 *   (主页 Ui.gate vs 悬浮条 Ui.blockReason) ⇒ 早晚出现「主页点不了、悬浮条能点」。
 *   主页只剩三类东西: **一次性授权**、**把悬浮窗叫出来**、**看/配/更新**。
 *
 * 2026-10-11 同日改名 (用户原话: 「按钮的名字有歧义, 停止应该指的是暂停」):
 *   悬浮条那两颗键改为 **暂停 / 结束** —— 暂停 = 停循环、窗口留着可续; 结束 = 退出挂机、
 *   连窗口一起收。暂停之后「开始」那颗键自己会显示成**「继续」**(BotService.paused())。
 *   ⚠️ 动作名 `stop`/`end`/`start` 没动: 那是 adb 广播与 HTTP /trigger 的接口契约。
 *
 * 状态区不再每秒整体重刷 (会打断滚动): 状态是独立 TextView, 每秒只更新它的文字;
 * 反馈行 (§) 是另一行, 点按钮/出错时写它, 不被状态刷新覆盖。
 */
public class MainActivity extends Activity {
    private TextView status;
    private TextView feed;          // 点击反馈行 (只由 Ui.act/gate 写)
    /** 三选一的模式键 (下标 = 0 长滚 / 1 快速 / 2 PF); 文案随当前模式变, 每秒刷。 */
    private Button[] modeBtn;
    private Button overlayBtn;      // 悬浮窗 启动/隐藏 (文案随窗口状态变, 每秒刷)
    private Button pfBtn;           // PF 挂机 开始/暂停 (文案随运行状态变, 每秒刷)
    /** PF 配置 + PF 操作整块 (仅 PF 模式可见; 2026-10-17 晚用户要求内联展开)。 */
    private LinearLayout pfBox;
    /** PF 配置各值的"当前值"按钮 (文案 = 左标签 + 右当前值, 点了弹选择层)。 */
    private Button btnArena, btnEnergy, btnScore, btnRule, btnFav, btnFabSize, btnArenaSync;
    private final Handler h = new Handler(Looper.getMainLooper());

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (ShizukuCtl.serverUp() && ShizukuCtl.granted() && !ShizukuCtl.ready()) {
                ShizukuCtl.tryBind(MainActivity.this);   // 授权弹窗点完允许后, 轮询自动接上
            }
            Paths.init(MainActivity.this);   // 授权"所有文件访问"回来后自动换根, 不必重启
            status.setText(statusText());
            if (modeBtn != null) {
                for (int i = 0; i < modeBtn.length; i++) {
                    if (modeBtn[i] == null) continue;
                    String lb = modeLabel(i);
                    /* 只在文案真变了才 setText —— 每秒无条件 setText 会把按下的高亮刷掉。 */
                    if (!lb.contentEquals(modeBtn[i].getText())) modeBtn[i].setText(lb);
                }
            }
            /* PF 配置块只在 PF 模式下可见 (模式可能被 WebUI 改, 所以这里也查)。
             * ⚠️ 只改显隐不重建控件 —— 重建要在 Ui.end() 之后造 view, 会崩 (见 buildPfPanel 注)。 */
            if (pfBox != null) {
                int vis = BotService.MODE_APP_PF.equals(BotService.appMode(MainActivity.this))
                        ? View.VISIBLE : View.GONE;
                if (pfBox.getVisibility() != vis) pfBox.setVisibility(vis);
            }
            refreshPfLabels();
            /* 窗口可能被系统摘掉/被悬浮条上的「结束」收掉 ⇒ 文案要跟着变。
             * ⚠️ 只在**文本真的变了**时才 setText —— 每秒无条件 setText 会让按钮一直
             *    重绘 (按下的高亮都会被刷掉, 看着像"点不动")。 */
            if (overlayBtn != null) {
                String lb = overlayLabel();
                if (!lb.contentEquals(overlayBtn.getText())) overlayBtn.setText(lb);
            }
            if (pfBtn != null) {
                String lb = pfLabel();
                if (!lb.contentEquals(pfBtn.getText())) pfBtn.setText(lb);
            }
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
                        feed.setText("✓ 悬浮窗权限已授 — 可点下面「启动悬浮窗」");
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

        /* ---------------- 运行模式 (三选一) ----------------
         * ⚠️ 2026-10-17 用户: 「APP 里面可以切换三种模式吧 —— 日常 Events 的长滚/快速、
         *    PF 的这个屏幕刷的模式。切到 PF 之后悬浮窗应该只有一个开始, 然后 (人自己)
         *    切到 PF 界面再点那个开始」。
         * 所以这里从"常规 ⇄ 快速连点"的**二态开关**改成**三选一**, 并落进 config 的
         * app.mode (BotService.appMode 读它) —— 悬浮窗的按键表也跟着这个值变。
         * 2026-10-17 晚再改版 (用户: 「首先是三种模式保留, 然后启动悬浮窗放在那个下面,
         *   我把名字文字说明都去掉只留这个按钮; 选中 pf 的时候把 pf 的配置全选出来」):
         *   模式段上移到授权之下、悬浮窗按钮之上; 原来那三段长说明 (挂机控制/PF 挂机/
         *   运行模式各一大段 hint) 全删只留按钮; PF 配置改为**内联展开**。 */
        Ui.add(this, Ui.section(this, "运行模式 (三选一)"));
        modeBtn = new Button[3];
        for (int i = 0; i < modeBtn.length; i++) {
            final int k = i;
            modeBtn[i] = Ui.button(this, modeLabel(i), "", feed, () -> setAppMode(k));
        }

        /* ---------------- 启动悬浮窗 ----------------
         * ⚠️ 只此一个按钮: 标题与说明全按用户要求去掉 (2026-10-17)。原来那 8 行 hint
         * 讲的事 (按键随模式变/暂停语义/右下角浮标) 现在由悬浮窗自己呈现, 抄在这里只会
         * 过期 —— 10-11 至 10-17 这段它恰好对错过三次 (键名/模式表/浮标各一次)。 */
        overlayBtn = Ui.button(this, overlayLabel(), "", feed, this::toggleOverlay);

        /* ---------------- PF 配置 + PF 操作 (仅 PF 模式可见) ----------------
         * 用户原话: 「选中 pf 的时候, 把 pf 的配置全选出来, 能量下限, pf 选人限定,
         *   分数上限规则等等」—— 所以这一整块**跟着模式显隐**, 且配置直接内联,
         * 不再跳二级页 (以前藏在「PF 设置」页里, 用户原话:「你现在完全没有地方配置」)。
         *
         * ⚠️ 构建必须在 Ui.begin(pfBox) 作用域内 (Ui.button 内部走 Ui.add); 之后切模式
         *    只改 pfBox 的显隐、**不重建** —— 重建等于在 Ui.end() 之后再造控件, 正是
         *    2026-10-17 上午 PfSettingsActivity 一进去就闪退的那个坑。 */
        pfBox = new LinearLayout(this);
        pfBox.setOrientation(LinearLayout.VERTICAL);
        Ui.add(this, pfBox);
        Ui.begin(pfBox);
        buildPfPanel();
        Ui.end();
        Ui.begin(root);          // ⚠️ 恢复外层容器: 后面的「配置与查看」等段还要往 root 里加

        /* ---------------- 配置 / 查看 ---------------- */
        Ui.add(this, Ui.section(this, "配置与查看"));

        Ui.button(this, "编辑配置 (扫描间隔 / 运行控制 / 更新源…)",
                "打开配置编辑页", feed,
                () -> startActivity(new Intent(this, ConfigActivity.class)));

        Ui.button(this, "重载配置 (config.json 改动立即生效)",
                "热重载 config.json + 清模板缓存", feed, () -> {
                    BotService.reload();
                    feed.setTextColor(Ui.OK);
                    /* ⚠️ 别再写"配置层未初始化 (服务没起过)" —— 配置层现在由 cfgOr 兜底,
                     *    任何时候都读得到。服务没跑时 reload 只作废判定图缓存 (模板缓存
                     *    本来就不存在), 说清"启动时生效"即可。 */
                    feed.setText(BotService.isRunning()
                            ? "✓ 已重载 — 配置/模板已刷新, 下轮生效"
                            : "✓ 已重读配置 — 引擎没在跑, 下次启动按新配置来");
                });

        Ui.button(this, "查看判定路径图 (整个程序的判定树, App 内原生绘制)",
                "打开判定路径图 — 可拖动缩放, 点节点看条件/阈值/实测分数", feed,
                () -> startActivity(new Intent(this, GraphActivity.class)));

        Ui.button(this, "每日战绩 (GitHub 风格热力图)",
                "打开战绩页 — 热力图 / 明细 / 口径说明", feed,
                () -> startActivity(new Intent(this, StatsActivity.class)));

        /* ---------------- 更新 ---------------- */
        Ui.add(this, Ui.section(this, "GitHub 更新"));
        Ui.add(this, Ui.hint(this,
                "bundle (模板 + 配置) 可热更, 不起停引擎。\n"
                + "APK 装新包有两条路: ① 走 Shizuku 的 pm install (全自动, 但要 Shizuku + 所有文件访问);\n"
                + "② ① 走不通时**弹出系统安装界面**, 你点一下「安装」就行 (不需要 Shizuku / adb)。"));

        Ui.button(this, "检查更新 (查 GitHub latest release)",
                "检查中… (查 release 资产的 updated_at 与本地水位比对)", feed, () -> {
                    BotService.trigger("check_update");
                    watchUpdate();
                });

        Ui.button(this, "拉取更新 (bundle 热更 + 尝试装 APK)",
                "拉取中… bundle 热更; APK 先走 pm install, 走不通就弹系统安装界面", feed, () -> {
                    BotService.trigger("update");
                    watchUpdate();
                });

        /* ---------------- adb ---------------- */
        Ui.add(this, Ui.section(this, "adb 调试 (开发者)"));
        Ui.add(this, Ui.hint(this,
                "调试服务只绑 127.0.0.1:8791, 对外不可达 (给 PC 用; 手机上看判定图不必走它)。\n"
                + "  adb forward tcp:8791 tcp:8791\n"
                + "  http://127.0.0.1:8791/  → 判定路径图 (网页版, 与 App 内同数据)\n"
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
        /* 场次表开机顺手拉一次 (24h 节流, 后台, 失败静默) —— 官方上新场次后不必等重装 APK。 */
        PfArenaRemote.autoSync(this);

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

    /** PF 挂机按钮的文案 = 当前该做什么 (没跑 ⇒ 开始; 在跑 ⇒ 暂停)。 */
    private String pfLabel() {
        return BotService.isRunning() ? "⏸ 暂停 PF" : "▶ 开始 PF";
    }

    /**
     * 开始 / 暂停 PF。
     *
     * <p>⚠️ 动作名仍是 {@code start_nav} / {@code stop} —— 那是 adb 广播与 HTTP /trigger
     * 的接口契约 (见 OverlayBar 顶注), 这里只是**多一条路**到同一个动作。
     * 启动走和悬浮窗**同一份**前置检查 (Ui.NEED_CHANNEL), 不另设一套。
     */
    private void togglePf() {
        if (BotService.isRunning()) {
            BotService.trigger("stop");
            feed.setTextColor(Ui.OK);
            feed.setText("› 已暂停 PF — 悬浮窗恢复显示; 再点一次「▶ 开始 PF」继续");
            return;
        }
        startPf(false);
    }

    /**
     * 启动 PF。
     *
     * @param here true = **就地开始**: 跳过起步导航, 假定人已把游戏停在 PF 上 (见
     *             {@link BotService#MODE_NAV_HERE})。选场逻辑没做之前, 这是"打对场次"的唯一办法。
     */
    private void startPf(boolean here) {
        Ui.gate(this, feed, Ui.NEED_CHANNEL, () -> {
            BotService.trigger(here ? "start_here" : "start_nav");
            feed.setTextColor(Ui.OK);
            feed.setText(here
                    ? "› 就地开始 —— 悬浮窗已隐藏; 假定你已停在 PF 上, 不做起步导航"
                    : "› PF 已启动 (自动导航进场) —— 悬浮窗已隐藏, 可随时暂停");
        }).run();
    }

    /** 悬浮窗按钮的文案 = 当前该做什么 (显示中 ⇒ 点了是隐藏) */
    private String overlayLabel() {
        return OverlayCtl.isShown() ? "隐藏悬浮窗 (只收窗口, 不停引擎)"
                                    : "启动悬浮窗 (跑挂机的唯一开关都在这上面)";
    }

    /**
     * 引擎状态的人话 —— 三态, 不是两态。
     * ⚠️ 只有"运行中/停止"两种说法是不够的: 暂停之后那颗键写「继续」, 状态行却写"停止",
     *    两句话对不上, 用户会以为程序真停了 (这正是"名字有歧义"的另一面)。
     */
    private String engineText() {
        if (BotService.isRunning()) return "运行中 (" + BotService.currentMode() + ")";
        return BotService.paused() ? "已暂停 (可继续)" : "空闲";
    }

    /**
     * 悬浮窗 一键启/隐 —— 主页**唯一**与运行有关的东西。
     *
     * <p>它不是一个纯 gate: 已显示时是"隐藏"(不需要任何前置条件), 未显示时才是
     * "启动"(要悬浮窗权限)。所以不能直接套 {@link Ui#gate} —— 得先自己查一次,
     * 缺权限时把人送到设置页 ({@link Ui#offerFix}, 与 gate 走同一条路, 不另写跳转)。
     */
    private void toggleOverlay() {
        if (OverlayCtl.isShown()) {
            OverlayCtl.hide();
            feed.setTextColor(Ui.OK);
            feed.setText("✓ 悬浮条已隐藏 (引擎状态不变: "
                    + (BotService.isRunning() ? "运行中"
                                              : (BotService.paused() ? "已暂停" : "空闲")) + ")");
            overlayBtn.setText(overlayLabel());
            return;
        }
        String why = Ui.blockReason(this, Ui.NEED_OVERLAY);
        if (why != null) {
            Ui.toast(this, why);
            feed.setTextColor(Ui.ERR);
            feed.setText("✗ " + why);
            Ui.offerFix(this, Ui.NEED_OVERLAY);
            return;
        }
        String e = OverlayCtl.show(this);
        if (e == null) {
            feed.setTextColor(Ui.OK);
            feed.setText("✓ 悬浮条已显示 — 点上面的「开始」就跑; 可拖动, ◀ 折叠\n"
                    + "  (不需要先起服务; 位置会记住, 下次从原地恢复)");
        } else {
            feed.setTextColor(Ui.ERR);
            feed.setText("✗ " + e);
        }
        overlayBtn.setText(overlayLabel());
    }

    /* ================= 运行模式 三选一 ================= */

    private static final String[] MODE_KEYS = {
            BotService.MODE_APP_EVENT_LONG, BotService.MODE_APP_EVENT_FAST, BotService.MODE_APP_PF};
    private static final String[] MODE_NAMES = {"Events·长滚", "Events·快速", "PF 刷"};

    /** 当前模式下标 (读的是**合并后**的配置, 不是 override 文件本身)。 */
    private int appModeIdx() {
        String m = BotService.appMode(this);
        for (int i = 0; i < MODE_KEYS.length; i++) if (MODE_KEYS[i].equals(m)) return i;
        return 2;
    }

    private String modeLabel(int i) {
        return (appModeIdx() == i ? "● " : "○ ") + MODE_NAMES[i] + (appModeIdx() == i ? " (当前)" : "");
    }

    /** 切模式: 写 override (app.mode + 随模式联动的 fast.enable) → 热重载 → 悬浮窗按键表跟着换。 */
    private void setAppMode(int i) {
        if (appModeIdx() == i) return;
        /* ⚠️ 顺手把 fast.enable 一起写: "Events·快速"这个模式**就是** fast 开关 ——
         *    留两套状态给同一个意思是本项目反复栽过的坑 (两处判据各自为政)。
         *    之前主页那颗"常规 ⇄ 快速连点"独立开关就是被这个模式键取代的。 */
        java.util.Map<String, Object> kv = new java.util.LinkedHashMap<>();
        kv.put("app.mode", MODE_KEYS[i]);
        if (BotService.MODE_APP_EVENT_FAST.equals(MODE_KEYS[i])) kv.put("fast.enable", true);
        else if (BotService.MODE_APP_EVENT_LONG.equals(MODE_KEYS[i])) kv.put("fast.enable", false);
        String err = ConfigActivity.setOverrides(this, kv);
        if (err != null) {
            feed.setTextColor(Ui.ERR);
            feed.setText("✗ 切换失败: " + err);
            return;
        }
        /* 悬浮窗的按键表是**建窗时**按模式决定的 ⇒ 换了模式要重建才看得到变化
         * (PF 模式只剩「开始/暂停」这件事就发生在这里)。位置由 OverlayCtl 存盘恢复。 */
        if (OverlayCtl.isShown()) {
            OverlayCtl.hide();
            OverlayCtl.show(this);
        }
        feed.setTextColor(Ui.OK);
        feed.setText("✓ 已切到「" + MODE_NAMES[i] + "」"
                + (BotService.isRunning()
                        ? " — 引擎在跑: 到悬浮窗上点「暂停」再点「继续」才生效"
                        : " — 下次点悬浮窗的「开始」就用它"));
        if (modeBtn != null) for (int k = 0; k < modeBtn.length; k++) {
            if (modeBtn[k] != null) modeBtn[k].setText(modeLabel(k));
        }
        /* PF 配置块跟着模式显隐 (每秒 tick 也会查, 这里是点了立刻生效, 不用等一秒) */
        if (pfBox != null) {
            pfBox.setVisibility(BotService.MODE_APP_PF.equals(BotService.appMode(this))
                    ? View.VISIBLE : View.GONE);
        }
        refreshPfLabels();
    }

    /* ================= PF 配置 (主页内联, 仅 PF 模式可见) =================
     * 2026-10-17 晚用户: 「选中 pf 的时候, 把 pf 的配置全选出来, 能量下限, pf 选人限定,
     * 分数上限规则等等」。
     * 形态: 每项一个按钮, 文案 = 「标签: 当前值」, 点了弹单选层。比一堆 EditText 省地方,
     * 也不会出现"填了 4kw 存成 40000 这种单位车祸"。
     * ⚠️ 全部走 ConfigActivity.setOverrides (多键一次写 + 热重载), 与别处同一个口子。 */

    /** 建 PF 配置 + PF 操作两块 (必须在 Ui.begin(pfBox) 作用域内调用)。 */
    private void buildPfPanel() {
        PfArenaTable.load(this);                 // 场次表 (36 条) —— 内联配置要用

        Ui.add(this, Ui.section(this, "PF 配置 (打哪一场 / 什么条件)"));
        btnArena = Ui.button(this, "", "从场次表选目标场次 (自动带出能量/分数/规则)", feed, this::pickArena);
        btnEnergy = Ui.button(this, "", "编队时每个槽位要凑够的钉数下限", feed, this::pickEnergy);
        btnScore = Ui.button(this, "", "本场想刷到的分数 (达标就停)", feed, this::pickScore);
        btnRule = Ui.button(this, "", "出战规则 (约束编队选人)", feed, this::pickRule);
        btnFav = Ui.button(this, "", "编队选人时只用收藏角色", feed, this::toggleFav);
        btnFabSize = Ui.button(this, "", "挂机时那颗红色「暂停」小浮标的大小 (小了不挡游戏)", feed, this::pickFabSize);
        Ui.button(this, "📜 历史场次 (只读: 已过期的场次 / 每天扫到几张卡)",
                "之前的场次只在这里回看, 不在上面的可选列表里", feed, this::showArenaHistory);
        /* 场次表原来只能靠重装 APK 更新 (assets 在增量安装下还常常读不到),
         * 现在改成从 GitHub Pages 拉 —— 官方上了新场次, 点一下就有。 */
        btnArenaSync = Ui.button(this, "",
                "从线上 (zhiyaunhe-ops.github.io) 拉最新场次表; 失败就继续用上次缓存/内置表",
                feed, this::syncArena);

        Ui.add(this, Ui.section(this, "PF 操作"));
        pfBtn = Ui.button(this, pfLabel(), "", feed, this::togglePf);
        Ui.button(this, "▶ 就地开始（先在游戏里手动停在 PF）", "", feed, () -> startPf(true));
        Ui.button(this, "监控 (当前界面 / 场次 / 时长 / 日志)", "", feed,
                () -> startActivity(new Intent(this, MonitorActivity.class)));
        Ui.button(this, "WebUI 控制台 (状态 / 开始 / 实时画面 / 采集开关)", "", feed,
                () -> startActivity(new Intent(this, WebUiActivity.class)));

        refreshPfLabels();
    }

    /** 每秒刷新 PF 配置按钮上的"当前值" (配置可能被 WebUI 改)。 */
    private void refreshPfLabels() {
        if (pfBox == null || pfBox.getVisibility() != View.VISIBLE) return;
        setLabel(btnArena, "目标场次: " + (targetArena() == null ? "未指定 (按卡位保守口径)" : targetArena()));
        setLabel(btnEnergy, "能量下限: " + pfInt("energy_cost", 4) + " 钉/槽");
        long st = (long) pfInt("score_target", 40000000);
        setLabel(btnScore, "分数上限: " + (st < 0 ? "无上限 (月场)" : (st / 10000000) + "kw"));
        setLabel(btnRule, "出战规则: " + (ruleText() == null ? "无" : ruleText()));
        setLabel(btnFav, "只用收藏角色: " + (pfBool("favorite", true) ? "开" : "关"));
        setLabel(btnFabSize, "暂停浮标大小: " + pfInt("fab_dp", 36) + "dp");
        /* ⚠️ 只读内存里的来源标记, 不在这里碰文件/网络 —— 这个方法是每秒跑一次的。 */
        setLabel(btnArenaSync, "场次表: " + PfArenaTable.sourceDesc() + " (点这里拉线上最新)");
    }

    private void setLabel(Button b, String s) {
        if (b == null) return;
        if (!s.contentEquals(b.getText())) b.setText(s);   // 只在变了时写, 免掉按下高亮
    }

    /* ---- 配置读写 (都走合并后的配置, 不是 override 文件本身) ---- */

    private JSONObject pfCfg() {
        try {
            JSONObject all = BotService.cfgOr(this).read();
            return all == null ? null : all.optJSONObject("pf");
        } catch (Throwable t) { return null; }
    }

    private int pfInt(String k, int def) {
        JSONObject p = pfCfg();
        return p == null ? def : p.optInt(k, def);
    }

    private boolean pfBool(String k, boolean def) {
        JSONObject p = pfCfg();
        return p == null ? def : p.optBoolean(k, def);
    }

    /** config 里的 pf.arena (null/空 = 未指定)。 */
    private String targetArena() {
        try {
            JSONObject p = pfCfg();
            String a = p == null ? null : p.optString("arena", "");
            return a == null || a.isEmpty() ? null : a;
        } catch (Throwable t) { return null; }
    }

    /** config 里的 pf.rule 显示成 "class:Filia" (null = 无)。 */
    private String ruleText() {
        try {
            JSONObject p = pfCfg();
            JSONObject r = p == null ? null : p.optJSONObject("rule");
            if (r == null) return null;
            String t = r.optString("type", ""), v = r.optString("value", "");
            if (t.isEmpty() || v.isEmpty()) return null;
            return t + ":" + v;
        } catch (Throwable t) { return null; }
    }

    /** 写若干 pf.* 键 (value 为 null = 删键) + 热重载; 返回 null = 成功。 */
    private String writePf(java.util.Map<String, Object> kv) {
        String err = ConfigActivity.setOverrides(this, kv);
        if (err != null) return err;
        BotService.trigger("reload");     // 引擎在跑时也要立刻看到新条件
        return null;
    }

    private void pfDone(String msg) {
        feed.setTextColor(Ui.OK);
        feed.setText("✓ " + msg + (BotService.isRunning()
                ? " — 引擎在跑, 下一场起生效" : ""));
        refreshPfLabels();
    }

    /* ---- 弹单选层的公共骨架 ---- */

    private interface Pick { void on(int i); }

    private void pick(String title, String[] items, int cur, Pick cb) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(items, cur, (d, w) -> {
                    d.dismiss();
                    cb.on(w);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /* ---- 各项选择 ---- */

    /**
     * 拉线上场次表 → 重新载入 → 刷新标签。
     *
     * <p>⚠️ 拉取是**后台线程**, 落盘校验在 {@link PfArenaRemote#sync} 里; 这里只做 UI 反馈。
     *    手动拉失败不影响正在跑的引擎 (旧表还在内存里)。</p>
     */
    private void syncArena() {
        feed.setTextColor(Ui.OK);
        feed.setText("› 正在拉取场次表… (" + PfArenaRemote.url(this) + ")");
        SgmLog.i("ui", "手动拉取场次表");
        PfArenaRemote.syncAsync(this, () -> {
            PfArenaTable.reload(this);
            refreshPfLabels();
            boolean ok = PfArenaRemote.lastOk();
            feed.setTextColor(ok ? Ui.OK : Ui.ERR);
            feed.setText((ok ? "✓ 场次表: " : "✗ 拉取失败, 继续用 ")
                    + PfArenaTable.sourceDesc()
                    + " — " + PfArenaRemote.lastResult()
                    + (ok ? " (缓存 " + PfArenaRemote.status() + ")" : ""));
        });
    }

    private void pickArena() {
        SgmLog.i("ui", "pickArena 被点 (弹场次表 " + PfArenaTable.names().size() + " 个)");
        java.util.List<String> names = PfArenaTable.names();
        final String[] items = new String[names.size() + 1];
        items[0] = "✖ 不指定场次 (按卡位保守口径: 4kw / 4 钉 / 无规则)";
        for (int i = 0; i < names.size(); i++) items[i + 1] = names.get(i);
        String cur = targetArena();
        int sel = 0;
        if (cur != null) {
            for (int i = 0; i < names.size(); i++) if (names.get(i).equals(cur)) { sel = i + 1; break; }
        }
        pick("目标场次 (" + names.size() + " 个)", items, sel, i -> {
            java.util.Map<String, Object> kv = new java.util.LinkedHashMap<>();
            if (i == 0) {
                /* 不指定 = 删四个键, 回落到按卡位的保守口径 (不是写个空串进去) */
                kv.put("pf.arena", "");
                kv.put("pf.energy_cost", null);
                kv.put("pf.score_target", null);
                kv.put("pf.rule", null);
                String err = writePf(kv);
                if (err != null) { feed.setTextColor(Ui.ERR); feed.setText("✗ " + err); return; }
                pfDone("已清除场次指定 — 回到按卡位保守口径 (4kw / 4 钉 / 无规则)");
                return;
            }
            String name = names.get(i - 1);
            PfArenaTable.Spec sp = PfArenaTable.spec(name);
            if (sp == null) { feed.setTextColor(Ui.ERR); feed.setText("✗ 场次表里找不到 " + name); return; }
            kv.put("pf.arena", name);
            kv.put("pf.energy_cost", sp.energy());
            kv.put("pf.score_target", sp.noLimit() ? PfArenaTable.NO_LIMIT : sp.scoreTarget());
            /* rule 可能是 null (misc/unknown 场) ⇒ 写 null 删键, 别把 "null" 当字符串存进去 */
            String r = sp.rule();
            kv.put("pf.rule", r == null ? null : ruleJson(r));
            String err = writePf(kv);
            if (err != null) { feed.setTextColor(Ui.ERR); feed.setText("✗ " + err); return; }
            pfDone("已选场次 " + name + " → " + sp.energy() + " 钉 / "
                    + (sp.noLimit() ? "无上限" : (sp.scoreTarget() / 10000000) + "kw")
                    + " / " + (r == null ? "无规则" : r));
        });
    }

    /** "class:Filia" / "element:water" → {"type":..,"value":..} (PfTeam.configure 的口径)。 */
    private static JSONObject ruleJson(String s) {
        try {
            int c = s.indexOf(':');
            JSONObject o = new JSONObject();
            o.put("type", s.substring(0, c));
            o.put("value", s.substring(c + 1));
            return o;
        } catch (Throwable t) { return null; }
    }

    private void pickEnergy() {
        final int[] vals = {3, 4, 5, 6};
        String[] items = new String[vals.length];
        for (int i = 0; i < vals.length; i++) items[i] = vals[i] + " 钉/槽";
        int cur = 0;
        for (int i = 0; i < vals.length; i++) if (vals[i] == pfInt("energy_cost", 4)) cur = i;
        pick("能量下限 (编队每槽要凑够的钉数)", items, cur, i -> {
            String err = writePf(kv1("pf.energy_cost", vals[i]));
            if (err != null) { feed.setTextColor(Ui.ERR); feed.setText("✗ " + err); return; }
            pfDone("能量下限 = " + vals[i] + " 钉 (不够会拉人换)");
        });
    }

    private void pickScore() {
        final long[] vals = {40_000_000L, 50_000_000L, PfArenaTable.NO_LIMIT};
        String[] items = {"4kw (4000 万)", "5kw (5000 万)", "无上限 (月场就是这种)"};
        int cur = 1;
        long c = (long) pfInt("score_target", 40000000);
        for (int i = 0; i < vals.length; i++) if (vals[i] == c) cur = i;
        pick("分数上限 (达标就停; 无上限 = 一直刷)", items, cur, i -> {
            String err = writePf(kv1("pf.score_target", vals[i]));
            if (err != null) { feed.setTextColor(Ui.ERR); feed.setText("✗ " + err); return; }
            pfDone(vals[i] < 0 ? "分数上限 = 无上限" : "分数上限 = " + (vals[i] / 10000000) + "kw");
        });
    }

    private void pickRule() {
        java.util.List<String> rs = PfArenaTable.rules();
        final String[] items = new String[rs.size() + 1];
        items[0] = "✖ 无规则 (不约束编队)";
        for (int i = 0; i < rs.size(); i++) items[i + 1] = rs.get(i);
        String cur = ruleText();
        int sel = 0;
        if (cur != null) {
            for (int i = 0; i < rs.size(); i++) if (rs.get(i).equals(cur)) { sel = i + 1; break; }
        }
        pick("出战规则 (约束编队选人)", items, sel, i -> {
            java.util.Map<String, Object> kv = new java.util.LinkedHashMap<>();
            kv.put("pf.rule", i == 0 ? null : ruleJson(rs.get(i - 1)));
            String err = writePf(kv);
            if (err != null) { feed.setTextColor(Ui.ERR); feed.setText("✗ " + err); return; }
            pfDone(i == 0 ? "出战规则 = 无" : "出战规则 = " + rs.get(i - 1));
        });
    }

    private void toggleFav() {
        boolean on = pfBool("favorite", true);
        String err = writePf(kv1("pf.favorite", !on));
        if (err != null) { feed.setTextColor(Ui.ERR); feed.setText("✗ " + err); return; }
        pfDone(!on ? "只用收藏角色 = 开" : "只用收藏角色 = 关 (全部可选)");
    }

    /**
     * 「历史场次」只读弹层 —— 2026-10-18 用户: 「之前的场次只在历史记录里可以回看」。
     * 两块: ① 每天扫描到的卡数 (pf_scan_history.jsonl, 最近 10 天);
     *       ② 场次表里**已过期**的场次 (until &lt; 今天), 只展示不能选。
     */
    private void showArenaHistory() {
        StringBuilder sb = new StringBuilder();
        java.util.List<String[]> his = PfScan.history(10);
        sb.append("【最近扫描】\n");
        if (his.isEmpty()) {
            sb.append("  (还没有记录 — 跑一次「开始」(自动导航) 就会扫一遍 hub)\n");
        } else {
            for (String[] h : his) sb.append("  ").append(h[0]).append(" — ").append(h[1]).append(" 张卡\n");
        }
        java.util.List<String> old = PfArenaTable.namesExpired();
        sb.append("\n【已过期的场次】(").append(old.size()).append(" 条, 只读)\n");
        if (old.isEmpty()) {
            sb.append("  (无)\n");
        } else {
            for (String n : old) sb.append("  ").append(n).append('\n');
        }
        sb.append("\n⚠️ 过期场次不在「目标场次」可选列表里 —— 需要打它们请等表更新。");
        new AlertDialog.Builder(this)
                .setTitle("历史场次")
                .setMessage(sb.toString())
                .setPositiveButton("知道了", null)
                .show();
    }

    /** 暂停浮标大小 (2026-10-17 用户: 「你可以缩的更小点, 然后可以设置大小」)。 */
    private void pickFabSize() {
        final int[] vals = {28, 32, 36, 40, 48, 56, 64};
        String[] items = new String[vals.length];
        for (int i = 0; i < vals.length; i++) {
            items[i] = vals[i] + "dp" + (vals[i] == 36 ? " (默认)" : "")
                    + (vals[i] <= 32 ? " 很小" : vals[i] >= 56 ? " 很大" : "");
        }
        int cur = 0;
        int c = pfInt("fab_dp", 36);
        for (int i = 0; i < vals.length; i++) if (vals[i] == c) cur = i;
        pick("暂停浮标大小 (挂机时右下角那颗红钮)", items, cur, i -> {
            String err = writePf(kv1("pf.fab_dp", vals[i]));
            if (err != null) { feed.setTextColor(Ui.ERR); feed.setText("✗ " + err); return; }
            /* 引擎在跑时浮标正挂着 ⇒ OverlayBar.setFabVisible(true) 会按新尺寸即时重建;
             * 没在跑就下次挂机生效。 */
            feed.setTextColor(Ui.OK);
            feed.setText("✓ 暂停浮标 = " + vals[i] + "dp"
                    + (BotService.isRunning() ? " — 已即时重建" : " — 下次挂机生效"));
            refreshPfLabels();
        });
    }

    private static java.util.Map<String, Object> kv1(String k, Object v) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put(k, v);
        return m;
    }

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
        sb.append("引擎         ").append(engineText()).append('\n');
        sb.append("运行模式     ").append(MODE_NAMES[appModeIdx()]).append('\n');
        String tgtArena = targetArena();
        sb.append("PF 目标场次  ").append(tgtArena == null ? "未指定 (按卡位保守口径)" : tgtArena)
                .append('\n');
        Store st = Store.load();
        sb.append("战绩         ").append(st.text(0))
                .append(" · 累计 ").append(st.totalRounds()).append(" 场 / ")
                .append(st.activeDays()).append(" 天").append('\n');
        JSONObject m = cfgMeta();
        sb.append("App 版本     ").append(appVer()).append('\n');
        sb.append("更新源       ").append(m.optString("repo", "(未配)")).append('\n');
        sb.append("bundle 水位  ").append(m.optString("bv", "(还没拉取过)"));
        JSONObject lj = Update.lastJson();
        if (lj != null && lj.optBoolean("ok")) {
            String rv = lj.optString("ver_bundle", "");
            if (rv.length() > 0) {
                sb.append(" → 远端 ").append(Update.fmtStamp(rv))
                        .append(lj.optBoolean("need_bundle") ? " (有更新)" : " (已最新)");
            }
        }
        String ul = Update.lastResult();
        if (ul.length() > 0) sb.append("\n最近更新     ").append(ul);
        sb.append("\n外部根       ").append(Paths.root());
        return sb.toString();
    }

    private static String mark(boolean ok, String yes, String no) {
        return ok ? "✓ " + yes : "✗ " + no;
    }

    /**
     * 盯更新进度直到结束 —— 轮询 Update.busy()/stage()/pct(), 跑完再落结果。
     *
     * ⚠️ 原来是 `h.postDelayed(this::showUpdateResult, 2500/6000)` —— **只读一眼**:
     *    国内直连 api.github.com(查询) 与 objects.githubusercontent.com(下载资产) 都不快,
     *    那一拍结果往往还没回来 ⇒ 显示「还没拿到结果」, 而**之后再也不会刷新**
     *    ⇒ 用户看到的正是"没有进度、没有提示" (2026-10-11 报障)。
     *    trigger 只是 `new Thread(...).start()`, 线程起来有毫秒级延迟, 所以前 1.5s
     *    即使 busy() 还是 false 也继续等, 免得抢跑到"上一轮的结果"。
     */
    private void watchUpdate() {
        final long t0 = System.currentTimeMillis();
        final Runnable[] tick = new Runnable[1];
        tick[0] = () -> {
            if (isFinishing()) return;
            boolean waiting = Update.busy() || System.currentTimeMillis() - t0 < 1500;
            if (waiting && System.currentTimeMillis() - t0 < 180000) {
                String s = Update.stage();
                int p = Update.pct();
                feed.setTextColor(Ui.SUB);
                feed.setText("› " + (s.length() == 0 ? "处理中…" : s) + (p >= 0 ? " " + p + "%" : ""));
                h.postDelayed(tick[0], 400);
                return;
            }
            showUpdateResult();
        };
        h.postDelayed(tick[0], 250);
    }

    /**
     * 更新结果回显。
     *
     * ⚠️ 判成败改看 **结构化的 Update.lastLevel()**, 不再在文案里找字符串。
     *    旧实现 `boolean ok = !r.startsWith("检查: update.repo") && !r.contains("失败")
     *    && !r.contains("超时")` 有个致命漏判: "拉取: update.repo 未配 — …" 这句里
     *    **既没有"失败"也没有"超时"** ⇒ 判成成功 ⇒ **错误被显示成绿色 ✓**
     *    (2026-10-11 用户报障「没有什么提示」的很大一部分)。
     *    LV_WARN 用黄色 ! —— 那是"更新成功了但有部分被跳过"(如没授所有文件访问时装不了 APK),
     *    既不是成功也不是失败, 必须在视觉上区分出来。
     */
    private void showUpdateResult() {
        h.post(() -> {
            if (isFinishing()) return;
            if (Update.lastJson() == null) {
                feed.setTextColor(Ui.WARN);
                feed.setText("· 还没拿到结果 — 请求可能没发出去; 可翻 logs/app.log 看原因");
            } else {
                int lv = Update.lastLevel();
                feed.setTextColor(lv == Update.LV_OK ? Ui.OK
                        : (lv == Update.LV_WARN ? Ui.WARN : Ui.ERR));
                feed.setText((lv == Update.LV_OK ? "✓ " : (lv == Update.LV_WARN ? "! " : "✗ "))
                        + Update.lastResult());
            }
            status.setText(statusText());
        });
    }

    /** App 自身版本 (versionName + versionCode)。
     *  CI 每次构建都会把两者改成递增的值 (见 build-apk.yml 的 Bump versionCode),
     *  所以**装上新包这里一定会变**。
     *  ⚠️ 之前界面完全不显示版本号 —— 用户点完更新根本无从判断"到底装上没有",
     *     只能去系统「应用信息」翻, 而那正是"版本号不变"这种错觉的来源 (2026-10-11 报障)。 */
    private String appVer() {
        try {
            android.content.pm.PackageInfo pi =
                    getPackageManager().getPackageInfo(getPackageName(), 0);
            return pi.versionName + " (" + pi.versionCode + ")";
        } catch (Throwable t) {
            return "未知";
        }
    }

    /** 状态页用的更新信息 (repo / 本地 bundle 水位)
     *  ⚠️ 走 cfgOr: 服务没起时 BotService.cfg() 是 null, 而"打开 App 就看状态"正是
     *     服务没起的情形 —— 原来会显示成"更新源  bundle=0"(repo 读成空串) (2026-10-11)。 */
    private JSONObject cfgMeta() {
        try {
            Config c = BotService.cfgOr(this);
            JSONObject all = c == null ? null : c.read();
            JSONObject u = all == null ? null : all.optJSONObject("update");
            JSONObject m = all == null ? null : all.optJSONObject("meta");
            String repo = u == null ? "" : u.optString("repo", "");
            String bv = m == null ? "" : m.optString("bundle_version", "");
            boolean unset = bv.length() == 0 || "0".equals(bv);
            return new JSONObject()
                    .put("repo", repo.length() == 0 ? "(未配 — 见 config.update.repo)" : repo)
                    .put("bv", unset ? "(还没拉取过)" : Update.fmtStamp(bv));
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
