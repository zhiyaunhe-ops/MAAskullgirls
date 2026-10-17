package com.zhiyaunhe.sgmbot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;

import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 前台服务 — 悬浮条/工作线程/常驻通知的宿主。
 * 两种模式: {@code settle} = 结算闭环 (SettleLoop); {@code nav} = PF 全链 (PfFlow,
 * 导航+选场+选对手+编队选人+FIGHT+结算)。控制面三入口 (悬浮条/广播/HTTP) 全部汇到
 * start/stop/trigger。
 */
public class BotService extends Service {
    public static final String MODE_SETTLE = "settle";
    /** PF 全链 (2026-10-15 起实装, 不再是占位)。 */
    public static final String MODE_NAV = "nav";
    /**
     * PF 全链, 但**跳过起步导航** —— 「我已经在 PF 里了, 就地开始」。
     *
     * <p>⚠️ 2026-10-16 用户要求「你都没做选场逻辑, 就别再在外面开始拉, 就直接在 pf 里面开始」。
     * 现在选场(读 hub 卡名)还没做 ⇒ 从外面一路导航进去只能盲点居中那张卡, 很可能不是你想要的场;
     * 而人自己把游戏停到目标场次的 hub 上再点开始, 场次就是对的。
     * 所以把它做成一条**正式模式**而不是调试开关 (PfFlow 里早有 quiet 参数, 之前没人用)。
     *
     * <p>只影响"起步那一下": 之后的每场循环照旧自己认界面/补导航 —— 否则打完一场就出不来了。
     */
    public static final String MODE_NAV_HERE = "nav_here";

    /**
     * 日常 Events · 快速 —— settle 循环 + **强制 fast.enable=true** (盲点连点结算)。
     * ⚠️ 是"强制覆盖", 不是"读配置": 模式本身就是用户的选择, 再让它被 fast.enable 二次开关
     *    就会重演「两条路各说各话」那类毛病 (见本文件开头 2026-10-11 的教训)。
     */
    public static final String MODE_EVENT_FAST = "event_fast";

    /**
     * 日常 Events · 长滚 —— 进场 → 地图长滚找王关 → 开打 → 结算 → 循环。
     *
     * <p>⚠️⚠️ **进场 + 王关链还没移植完** (2026-10-17): 那一段目前只存在于 AutoJS 的
     * {@code phone/autojs/pf_nav.js} (FIGHT_STEPS: 地图右移×2 → 绿✓色锚点定位王关 → FIGHT!,
     * 真机标定过), 而 App 这条链还缺"大厅 → EVENTS 菱形 → 活动卡 PLAY! → 地图"的起步导航。
     * 所以现在选到这个模式**只跑结算闭环**(等价快速模式的非 fast 版), 日志会明确写出来
     * —— 宁可说清"这段没做", 也不要让用户以为它在长滚。</p>
     */
    public static final String MODE_EVENT_LONG = "event_long";

    /** 是不是 PF 全链 (两种起点都算)。 */
    public static boolean isPfMode(String m) { return MODE_NAV.equals(m) || MODE_NAV_HERE.equals(m); }

    /* ---------------- 三种「用户模式」(2026-10-17 用户要求) ----------------
     * 用户原话: 「手机 APP 里面可以切换三种模式吧 —— 日常 Events 的长滚快速模式、
     *   PF 的这个屏幕刷的模式 … 然后 WebUI 的那些选场次 / 能量上限等等条件应该都在
     *   APP 里面配置才对呀」。
     *
     * ⚠️ 与上面 MODE_* 的关系: MODE_* 是**引擎层**的取值 (settle/nav/nav_here),
     *    这里 MODE_APP_* 是**配置层**的取值 (存在 config 的 app.mode), 由
     *    {@code trigger("start_mode")} 翻译成引擎层 —— 这样三条老入口
     *    (start / start_nav / start_here) 的契约一个字都不用动
     *    (铁律: 动作名不许改, adb 广播 + HTTP /trigger 都按它们拼)。 */
    /** 日常 Events · 长滚: 进场 → 地图长滚找王关 → 开打 → 结算 → 循环。 */
    public static final String MODE_APP_EVENT_LONG = "event_long";
    /** 日常 Events · 快速: 盲点连点结算 (settle + fast.enable=true)。 */
    public static final String MODE_APP_EVENT_FAST = "event_fast";
    /** PF 刷: 就地开始 PF 全链 (人自己把游戏停在目标场次的 hub/编队页)。 */
    public static final String MODE_APP_PF = "pf";

    /** 从配置读当前用户模式 (缺省 {@code pf})。 */
    public static String appMode(Context c) {
        try {
            JSONObject a = cfgOr(c).read().optJSONObject("app");
            String m = a == null ? null : a.optString("mode", "");
            if (MODE_APP_EVENT_LONG.equals(m) || MODE_APP_EVENT_FAST.equals(m)
                    || MODE_APP_PF.equals(m)) return m;
        } catch (Throwable ignored) { }
        return MODE_APP_PF;                        // 缺省按用户当下在搞的那条来
    }

    /** 上一轮 PF 循环的停止原因 (null = 正常结束)。 */
    private volatile String lastFatal;

    private static volatile boolean running = false;
    /**
     * 这一轮有没有真的跑起来过 (暂停后要据此把键名显示成「继续」)。
     * ⚠️ 不能只看 running: 它区分不出"从没开始"和"暂停中", 而这两种状态在界面上
     *    该说不同的话 (开始 vs 继续) —— 名字对不上状态就是用户报的"名字有歧义"。
     */
    private static volatile boolean everRan = false;
    private static volatile String mode = "";
    private static Config cfg;
    /** 服务没起时的兜底配置层 (cfgOr 用)。与 cfg 读同一份文件, 谁先建都行。 */
    private static volatile Config loose;
    private static ShizukuCtl sh;
    private static TplStore tpls;
    private static TouchCtl touch;
    /** 循环停止标志 — 与 running 同步置位 (SettleLoop 达上限时也会自己置 false) */
    private static final AtomicBoolean RUN = new AtomicBoolean(false);
    private static final Handler UI = new Handler(Looper.getMainLooper());

    private OverlayBar bar;

    public static ShizukuCtl sh() { return sh; }
    public static Config cfg() { return cfg; }

    /**
     * 配置层 (兜底版) — 服务没起过时 {@link #cfg} 是 null, 这里临时造一个读**同一份文件**。
     *
     * ⚠️ 为什么非有不可 (2026-10-11 用户报障「点请求更新, 版本号不变 / 没进度 / 没提示」):
     *    这些操作**都不依赖服务** —— 打开 App 就能点, 而它们全走 trigger/HTTP 读 cfg:
     *      服务没起 ⇒ cfg == null ⇒ Update.sec(null) 得到空 JSON ⇒ repo 读成 ""
     *      ⇒ 直接返回「update.repo 未配 — config.json 里填 "owner/name"」,
     *    可用户明明配了。DebugHttp 早就踩过这个坑并自己兜了底 (DebugHttp.cfg()),
     *    但**主页按钮 → trigger 这条路漏了** —— 同一个坑修了一处漏一处。
     *    现在收口到这一处, 两条路共用一个兜底。
     */
    public static Config cfgOr(Context ctx) {
        Config c = cfg;
        if (c != null) return c;
        Context x = ctx != null ? ctx : App.inst;
        if (x == null) return null;
        if (loose == null) loose = new Config(x);
        return loose;
    }
    public static boolean isRunning() { return running; }

    /**
     * 跑过又停下 = **暂停** (悬浮条上那颗键的文案要从「开始」变成「继续」)。
     * 从没跑过 / 已「结束」/ 服务已退 ⇒ false。
     */
    public static boolean paused() { return !running && everRan; }

    public static String currentMode() { return mode; }

    public void runOnUi(Runnable r) { UI.post(r); }
    public int screenW() { return getResources().getDisplayMetrics().widthPixels; }
    public int screenH() { return getResources().getDisplayMetrics().heightPixels; }

    /** 开始: 服务活着只起工作线程 (暂停 ≠ 结束, 服务/悬浮条保留); 服务没起则先起服务 */
    public static void start(Context ctx, String m) {
        if (running) { SgmLog.i("svc", "already running mode=" + mode); return; }
        if (App.service != null) { App.service.spawn(m); return; }
        ctx.startService(new Intent(ctx, BotService.class).putExtra("mode", m));
    }

    /**
     * 暂停 = 停循环、**服务与悬浮条都留着**, 再点「开始」(会显示成「继续」) 接着跑。
     *
     * ⚠️ 键名 2026-10-11 由「停止」改为「暂停」: 行为一直就是暂停, 名字却叫停止,
     *    而旁边还有一颗真正收窗口的「结束」—— 两颗都像"停", 用户按错就以为程序坏了。
     *    **动作名 `stop` 一个字都没动** (adb 广播 / HTTP 的接口契约)。
     */
    public static void stop(Context ctx) {
        /* ⚠️ 必须**连 RUN 一起置**。早先只置 running=false, 而循环判的是 `run.get()`
         *    (=RUN) ⇒ 那颗键其实停不下来, 循环照跑; 更糟的是再点「开始」时
         *    spawn() 只看 running, 于是又起一个线程 —— 两个循环同时在点。
         *    (2026-10-11 加「运行模式」切换按钮时发现: 用户切换后要「暂停再开始」,
         *     这条路的头一步就是坏的。) */
        running = false;
        RUN.set(false);
    }

    /** 结束 = 退出脚本同义: 引擎停 + 服务停 + 悬浮窗/通知一并收掉 (JS endBot → exit()) */
    public static void end(Context ctx) {
        running = false;
        everRan = false;                 // 退出了 ⇒ 下次该是「开始」而不是「继续」
        /* ⚠️ 悬浮条必须在这里显式收, **不能指望 onDestroy** —— onDestroy 只收「服务名下」
         *    (OverlayCtl.ownedByService()) 的窗口, 为的是"服务意外退出别弄丢用户自己摆的条"。
         *    而最常见的使用顺序恰恰是「先单独显示悬浮窗 → 再点开始」:
         *      OverlayCtl.show() 把 svcOwned 置 false, 之后 startService 时 attachForService
         *      看到已有窗口就复用、**不会**改 svcOwned ⇒ 服务退出时那条悬浮窗被"保留"。
         *    结果就是用户点「结束」后: 服务没了、悬浮窗还赖在屏幕上 (2026-10-11 用户报障:
         *    「我点结束应该是退出悬浮窗, 咋只是退出服务呢」)。
         *    语义定死: 「暂停」= 只停循环、窗口留着可再开;「结束」= 连窗口一起收 (同 JS exit())。
         *    2026-10-11 起这条语义只由**悬浮窗**上那两颗键触发 (主页的运行键已按用户要求删光)。 */
        OverlayCtl.hide();
        if (ctx != null) ctx.stopService(new Intent(ctx, BotService.class));
    }

    public static void trigger(String action) {
        Context c = App.inst;
        if (action == null || c == null) return;
        switch (action) {
            /* ★ App 内「三种模式」的统一入口 (悬浮窗 PF 模式只剩的那颗「开始」就走它)。
             * 模式取自 config 的 app.mode —— 见 MODE_APP_* 注释: 老的三条入口一律不动。 */
            case "start_mode":
                String am = appMode(c);
                start(c, MODE_APP_PF.equals(am) ? MODE_NAV_HERE
                        : MODE_APP_EVENT_LONG.equals(am) ? MODE_EVENT_LONG : MODE_EVENT_FAST);
                break;
            case "start_nav": start(c, MODE_NAV); break;
            /* 就地开始: 假定人已把游戏停在 PF hub/编队页, 不做起步导航 (见 MODE_NAV_HERE) */
            case "start_here": start(c, MODE_NAV_HERE); break;
            case "start": start(c, MODE_SETTLE); break;
            case "stop": stop(c); break;
            case "end": end(c); break;
            case "reload": reload(); break;
            /* 悬浮条独立启停 (不需要服务) — 主页/悬浮条/HTTP 都能调 */
            case "overlay_show": OverlayCtl.show(c); break;
            case "overlay_hide": OverlayCtl.hide(); break;
            /* 监控页 (长跑看进度)。⚠️ 必须 NEW_TASK —— 悬浮条/广播里没有 Activity 栈。 */
            case "monitor":
                try {
                    c.startActivity(new Intent(c, MonitorActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception e) {
                    SgmLog.i("ctl", "打开监控页失败: " + e);
                }
                break;
            /* 更新: 网络 IO, 一律丢后台线程 (trigger 会被 UI 线程/HTTP 线程直接调)
             * ⚠️ 配置一律走 cfgOr(c) 而不是 static cfg —— 服务没起过时 cfg==null,
             *    而"打开 App 就点更新"是最常见顺序 (服务根本没起)。见 cfgOr 注释。 */
            case "check_update": background(() -> {
                JSONObject r = Update.check(cfgOr(c));
                SgmLog.i("ctl", r.optBoolean("ok")
                        ? "更新检查: bundle" + (r.optBoolean("need_bundle") ? "有" : "无")
                          + " apk" + (r.optBoolean("need_apk") ? "有" : "无")
                          + " (tag=" + r.optString("tag") + ")"
                        : "更新检查失败 — " + r.optString("msg"));
            }); break;
            case "update": background(() -> {
                JSONObject r = Update.apply(c, cfgOr(c), true);
                SgmLog.i("ctl", "update: " + r);
            }); break;
            case "update_bundle": background(() -> {
                JSONObject r = Update.apply(c, cfgOr(c), false);
                SgmLog.i("ctl", "update(bundle only): " + r);
            }); break;
            default: SgmLog.i("ctl", "unknown action " + action);
        }
    }

    /** 配置/模板热更 (POST /reload、bundle 落盘后、主页按钮共用) */
    public static void reload() {
        // ⚠️ 作废必须在下面的 cfg 门禁**之前**: 引擎没跑时 cfg==null, 门禁里不 load,
        //    而"没跑"恰恰是静态装载出来的那张图被看到的情形 (Graph.ensureLoaded)。
        //    不作废的话改完配置界面还是旧阈值 —— 图与实不符。
        Graph.invalidate();
        if (cfg != null) {
            if (tpls != null) tpls.clear();     // 模板热更: 下次取模板重新解码
            JSONObject c = cfg.read();
            Vision.configure(c);                // 匹配口径可能改了
            Shots.configure(c);                 // 带图日志的开关/上限可能改了
            Graph.load(c);                      // 判定图结构可能改了
            SgmLog.i("ctl", "reloaded, target=" + c.optString("target_card")
                    + " | 匹配=" + Vision.modeText());
        }
    }

    private static void background(Runnable r) {
        new Thread(r, "ctl-bg").start();
    }

    @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        App.service = this;
        if (cfg == null) cfg = cfgOr(this);     // 与"服务没起时的兜底"共用同一个对象
        if (sh == null) sh = new ShizukuCtl();
        if (tpls == null) tpls = new TplStore(this);
        if (touch == null) touch = new TouchCtl(sh);
        /* 悬浮条改由 OverlayCtl 统管 (可独立启动, 用户先摆好就复用不重建) */
        bar = OverlayCtl.attachForService(this);
        if (bar != null) bar.setText("待机\n今日" + Store.load().rounds + "场\n点开始跑");
        else SgmLog.i("svc", "无悬浮窗权限 — 去 App 主页授权 (显示在应用上层); 引擎照跑");
        notify_("SGM挂机待命");
        SgmLog.i("svc", "created");
    }

    @Override
    public int onStartCommand(Intent in, int flags, int id) {
        String m = in != null ? in.getStringExtra("mode") : null;
        if (m == null) return START_STICKY;
        if (running) { SgmLog.i("svc", "already running"); return START_STICKY; }
        if (!ShizukuCtl.serverUp()) {
            SgmLog.i("svc", "Shizuku 未连接 — 主页/通知引导");
            notify_("Shizuku 未连接 — 打开 App 按引导激活");
            return START_STICKY;
        }
        ShizukuCtl.tryBind(this);
        running = true;
        RUN.set(true);
        everRan = true;
        mode = m;
        /* 挂机期间折叠悬浮条 —— ⚠️ 这条广播路径**不经过 spawn()**, 折叠必须在这里也做一次,
         * 否则用 adb/HTTP 触发时悬浮条照旧展开、继续挡着游戏 UI (2026-10-15 实栽)。 */
        if (bar != null) bar.foldForRun(true);
        final String mm = m;
        new Thread(() -> runBot(mm), "bot-" + mm).start();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        RUN.set(false);
        everRan = false;              // 服务没了 ⇒ 下次该显示「开始」
        /* ⚠️ 只收「服务名下」的悬浮条 —— 用户单独启动的那条要留在屏幕上。
         *   判据是"是服务起来时挂的": 用户先开的话 OverlayCtl 会复用, 这里就不该收。
         *   实现上交给 OverlayCtl: 它知道当前这条是谁的 (attachForService vs show)。
         * ⚠️ 用 OverlayCtl.isShown() 而不是 bar.isShown(): 「结束」路径里 end() 已经先
         *   调过 OverlayCtl.hide()(它**同步**摘掉静态引用、把 removeView post 到主线程),
         *   而 stopService→onDestroy 的时序不确定 ⇒ 读静态引用才能确定性地跳过"保留"分支,
         *   不会去 setText 一个正在被拆的窗口。 */
        if (bar != null && OverlayCtl.ownedByService()) {
            OverlayCtl.hide();
            bar = null;
        } else if (bar != null && OverlayCtl.isShown()) {
            SgmLog.i("svc", "悬浮条是用户单独启动的 — 服务退出时保留");
            bar.setText("待机 (服务已退)\n点开始重跑");
        }
        App.service = null;
        SgmLog.i("svc", "destroyed");
        super.onDestroy();
    }

    private void spawn(String m) {
        if (running) return;
        running = true;
        RUN.set(true);              // 与 onStartCommand 对齐: stop 会清 RUN, 这里必须重新置
        everRan = true;
        mode = m;
        /* 挂机期间折叠悬浮条 —— 展开的面板会挡住游戏 UI, 实测把大厅 PRIZE FIGHTS 的模板
         * 匹配从 0.98 压到 0.55 ⇒ bot 认不出大厅 ⇒ 自己把自己卡死。见 OverlayBar.foldForRun。 */
        if (bar != null) bar.foldForRun(true);
        new Thread(() -> runBot(m), "bot-" + m).start();
    }

    private void runBot(String m) {
        SgmLog.i("bot", "开始 mode=" + m);
        final boolean pf = isPfMode(m);
        notify_("SGM挂机 " + (pf ? "PF 全链" : "结算循环") + " — 循环中");
        Update.autoCheck(this, cfg, false);   // 顺手查一次更新 (默认只热更 bundle, 不自动装 APK)
        try {
            if (pf) {
                /* nav 模式 = PF 全链 (2026-10-15 起): 导航 → 选场次 → 选对手 → 编队选人
                 * → FIGHT → 战斗 → 结算 → 再战 (每场都重选角色)。移植 tools/pf_bot.py 的
                 * step() 14 分支优先级表, 口径见 PfFlow / PfTeam 类注释。
                 * ⚠️ 旧的"导航退化为结算循环"占位实现已删 —— 那时 NavChain 未接入。 */
                PfFlow flow = new PfFlow(sh, tpls, bar);
                /* quiet = nav_here ⇒ 跳过起步的 gotoHub, 假定人已经把游戏停在 PF hub 上
                 * (理由见 MODE_NAV_HERE 的注释)。之后的循环照常自己认界面。 */
                flow.run(RUN, cfg, MODE_NAV_HERE.equals(m));
                lastFatal = flow.fatalReason();
            } else if (MODE_EVENT_LONG.equals(m)) {
                /* ⚠️ 长滚链未实装 (见 MODE_EVENT_LONG 注释) —— 现在等价"非 fast 的结算闭环",
                 * 但**必须把话说出来**, 否则用户会以为它在长滚地图找王关。 */
                SgmLog.i("bot", "⚠ 长滚模式 (event_long): 进场/王关链尚未移植 (移植源 "
                        + "phone/autojs/pf_nav.js 的 FIGHT_STEPS) — 本次只跑结算闭环; "
                        + "请自己在游戏里把战斗开起来 (或在主页模式里改用其它模式)");
                new SettleLoop(sh, tpls, bar).run(RUN, cfg);
            } else {
                /* event_fast = settle + fast.enable=true。
                 * ⚠️ 这里**不再自己造一份配置**: fast.enable 由主页的模式切换负责写
                 *    (见 MainActivity.setAppMode) —— 一句话说清: "模式即开关", 不留两套
                 *    互相对不上的状态 (Config 是 final 类, 也没法就地造个改了值的副本)。
                 *    异常情况(比如用户手改过 config)只在这里留一行日志。 */
                try {
                    JSONObject f = cfg.read().optJSONObject("fast");
                    if (f == null || !f.optBoolean("enable", false)) {
                        SgmLog.i("bot", "⚠ event_fast 但 fast.enable=false (配置被手改过?) — "
                                + "本次仍按普通结算跑; 在主页点一下「Events·快速」可修好");
                    }
                } catch (Throwable ignored) { }
                new SettleLoop(sh, tpls, bar).run(RUN, cfg);
            }
        } catch (Throwable t) {
            SgmLog.i("bot", "循环异常: " + t);
        }
        running = false;
        RUN.set(false);
        if (bar != null) bar.foldForRun(false);      // 还原用户挂机前的展开状态
        notify_("SGM挂机已暂停 (可在悬浮条上点「继续」)");
        SgmLog.i("svc", "已暂停 mode=" + m + " (服务/悬浮条保留)");
    }

    private static String fmtSec(long ms) {
        long s = ms / 1000;
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }

    private void notify_(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        String ch = "bot";
        Notification n;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(ch, "挂机",
                    NotificationManager.IMPORTANCE_LOW));
            n = new Notification.Builder(this, ch)
                    .setContentTitle("SGM挂机").setContentText(text)
                    .setSmallIcon(android.R.drawable.ic_media_play).setOngoing(true).build();
        } else {
            n = new Notification.Builder(this)
                    .setContentTitle("SGM挂机").setContentText(text)
                    .setSmallIcon(android.R.drawable.ic_media_play).setOngoing(true).build();
        }
        startForeground(1, n);
    }

    /* M2: TouchCtl 引用防未用告警 (保留实例化) */
    @SuppressWarnings("unused")
    private TouchCtl touch() { return touch; }

    /* DebugHttp 侧临时取帧 */
    public Bitmap grab() { return sh != null ? sh.capture() : null; }
}
