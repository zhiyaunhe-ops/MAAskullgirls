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
 * M2: settle 模式跑 SettleLoop (移植 autojs 结算循环); 导航链待 NavChain 接入。
 * 控制面三入口 (悬浮条/广播/HTTP) 全部汇到 start/stop/trigger。
 */
public class BotService extends Service {
    public static final String MODE_SETTLE = "settle";
    public static final String MODE_NAV = "nav";

    private static volatile boolean running = false;
    private static volatile String mode = "";
    private static Config cfg;
    private static ShizukuCtl sh;
    private static TplStore tpls;
    private static TouchCtl touch;
    /** 循环停止标志 — 与 running 同步置位 (SettleLoop 达上限时也会自己置 false) */
    private static final AtomicBoolean RUN = new AtomicBoolean(false);
    private static final Handler UI = new Handler(Looper.getMainLooper());

    private OverlayBar bar;

    public static ShizukuCtl sh() { return sh; }
    public static Config cfg() { return cfg; }
    public static boolean isRunning() { return running; }
    public static String currentMode() { return mode; }

    public void runOnUi(Runnable r) { UI.post(r); }
    public int screenW() { return getResources().getDisplayMetrics().widthPixels; }
    public int screenH() { return getResources().getDisplayMetrics().heightPixels; }

    /** 开始: 服务活着只起工作线程 (停止=暂停, 服务/悬浮条保留); 服务没起则先起服务 */
    public static void start(Context ctx, String m) {
        if (running) { SgmLog.i("svc", "already running mode=" + mode); return; }
        if (App.service != null) { App.service.spawn(m); return; }
        ctx.startService(new Intent(ctx, BotService.class).putExtra("mode", m));
    }

    /** 停止 = 暂停引擎 (服务/悬浮条保留, 可再点开始) */
    public static void stop(Context ctx) { running = false; }

    /** 结束 = 退出脚本同义: 引擎停 + 服务停 + 悬浮窗/通知一并收掉 (JS endBot → exit()) */
    public static void end(Context ctx) {
        running = false;
        /* ⚠️ 悬浮条必须在这里显式收, **不能指望 onDestroy** —— onDestroy 只收「服务名下」
         *    (OverlayCtl.ownedByService()) 的窗口, 为的是"服务意外退出别弄丢用户自己摆的条"。
         *    而最常见的使用顺序恰恰是「先单独显示悬浮窗 → 再点开始」:
         *      OverlayCtl.show() 把 svcOwned 置 false, 之后 startService 时 attachForService
         *      看到已有窗口就复用、**不会**改 svcOwned ⇒ 服务退出时那条悬浮窗被"保留"。
         *    结果就是用户点「结束」后: 服务没了、悬浮窗还赖在屏幕上 (2026-10-11 用户报障:
         *    「我点结束应该是退出悬浮窗, 咋只是退出服务呢」)。
         *    语义定死: 「停止」= 只停引擎、窗口留着可再开;「结束」= 连窗口一起收 (同 JS exit())。
         *    也正因为这句, MainActivity 那个按钮的文案「结束服务 (停引擎 + 收服务/通知/悬浮条)」
         *    才不是空头支票。 */
        OverlayCtl.hide();
        if (ctx != null) ctx.stopService(new Intent(ctx, BotService.class));
    }

    public static void trigger(String action) {
        Context c = App.inst;
        if (action == null || c == null) return;
        switch (action) {
            case "start_nav": start(c, MODE_NAV); break;
            case "start": start(c, MODE_SETTLE); break;
            case "stop": stop(c); break;
            case "end": end(c); break;
            case "reload": reload(); break;
            /* 悬浮条独立启停 (不需要服务) — 主页/悬浮条/HTTP 都能调 */
            case "overlay_show": OverlayCtl.show(c); break;
            case "overlay_hide": OverlayCtl.hide(); break;
            /* 更新: 网络 IO, 一律丢后台线程 (trigger 会被 UI 线程/HTTP 线程直接调) */
            case "check_update": background(() -> {
                JSONObject r = Update.check(cfg);
                SgmLog.i("ctl", r.optBoolean("ok")
                        ? "更新检查: bundle" + (r.optBoolean("need_bundle") ? "有" : "无")
                          + " apk" + (r.optBoolean("need_apk") ? "有" : "无")
                          + " (tag=" + r.optString("tag") + ")"
                        : "更新检查失败 — " + r.optString("msg"));
            }); break;
            case "update": background(() -> {
                JSONObject r = Update.apply(c, cfg, true);
                SgmLog.i("ctl", "update: " + r);
            }); break;
            case "update_bundle": background(() -> {
                JSONObject r = Update.apply(c, cfg, false);
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
        if (cfg == null) cfg = new Config(this);
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
        mode = m;
        final String mm = m;
        new Thread(() -> runBot(mm), "bot-" + mm).start();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        RUN.set(false);
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
        mode = m;
        new Thread(() -> runBot(m), "bot-" + m).start();
    }

    private void runBot(String m) {
        if (MODE_NAV.equals(m)) {
            /* M2: NavChain 未接入前, 导航模式退化为直接进结算循环 (不盲点未知界面) */
            SgmLog.i("bot", "导航链待 NavChain (M2) 接入 — 本次先跑结算循环");
        }
        SgmLog.i("bot", "开始 mode=" + m);
        notify_("SGM挂机 " + m + " — 结算循环中");
        Update.autoCheck(this, cfg, false);   // 顺手查一次更新 (默认只热更 bundle, 不自动装 APK)
        try {
            new SettleLoop(sh, tpls, bar).run(RUN, cfg);
        } catch (Throwable t) {
            SgmLog.i("bot", "循环异常: " + t);
        }
        running = false;
        RUN.set(false);
        notify_("SGM挂机已停止");
        SgmLog.i("svc", "stopped mode=" + m);
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
