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

/**
 * 前台服务 — 悬浮条/工作线程/常驻通知的宿主。
 * M1: runBot 为通路自证 (每 5s 截一帧记尺寸); M2 接 SettleLoop/NavChain。
 * 控制面三入口 (悬浮条/广播/HTTP) 全部汇到 start/stop/trigger。
 */
public class BotService extends Service {
    public static final String MODE_SETTLE = "settle";
    public static final String MODE_NAV = "nav";

    private static volatile boolean running = false;
    private static volatile String mode = "";
    private static Config cfg;
    private static ShizukuCtl sh;
    private static TouchCtl touch;
    private static final Handler UI = new Handler(Looper.getMainLooper());

    private OverlayBar bar;

    public static ShizukuCtl sh() { return sh; }
    public static Config cfg() { return cfg; }
    public static boolean isRunning() { return running; }
    public static String currentMode() { return mode; }

    public void runOnUi(Runnable r) { UI.post(r); }
    public int screenW() { return getResources().getDisplayMetrics().widthPixels; }

    public static void start(Context ctx, String m) {
        if (running) { SgmLog.i("svc", "already running mode=" + mode); return; }
        ctx.startService(new Intent(ctx, BotService.class).putExtra("mode", m));
    }

    public static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, BotService.class));
    }

    public static void trigger(String action) {
        Context c = App.inst;
        if (action == null || c == null) return;
        switch (action) {
            case "start_nav": start(c, MODE_NAV); break;
            case "start": start(c, MODE_SETTLE); break;
            case "stop": stop(c); break;
            case "reload":
                if (cfg != null)
                    SgmLog.i("ctl", "reloaded, target=" + cfg.read().optString("target_card"));
                break;
            default: SgmLog.i("ctl", "unknown action " + action);
        }
    }

    @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        App.service = this;
        if (cfg == null) cfg = new Config(this);
        if (sh == null) sh = new ShizukuCtl();
        if (touch == null) touch = new TouchCtl(sh);
        if (Settings.canDrawOverlays(this)) {
            bar = new OverlayBar(this);
            bar.show();
            bar.setText("待机\n今日?场\n点导航开跑");
        } else {
            SgmLog.i("svc", "无悬浮窗权限 — 去 App 主页授权 (显示在应用上层)");
        }
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
        mode = m;
        final String mm = m;
        new Thread(() -> runBot(mm), "bot-" + mm).start();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (bar != null) bar.destroy();
        App.service = null;
        SgmLog.i("svc", "destroyed");
        super.onDestroy();
    }

    private void runBot(String m) {
        SgmLog.i("bot", "M1 通路自证开始 mode=" + m);
        notify_("SGM挂机 " + m + " — 通路自证中");
        long t0 = System.currentTimeMillis();
        int frames = 0, fails = 0;
        while (running) {
            Bitmap b = sh.capture();
            if (b != null) {
                frames++;
                SgmLog.i("bot", "frame#" + frames + " " + b.getWidth() + "x" + b.getHeight());
                b.recycle();
            } else {
                fails++;
                SgmLog.i("bot", "frame fail #" + fails);
            }
            final int fr = frames, fl = fails;
            if (bar != null) bar.setStat(m, fmtSec(System.currentTimeMillis() - t0),
                    "帧 " + fr + "/失" + fl, 0, 0);
            try {
                SgmLog.state(DebugHttp.readFileJson(SgmLog.DIR + "state.json")
                        .put("mode", m).put("running", true)
                        .put("frames", fr).put("frame_fails", fl)
                        .put("shizuku_ready", ShizukuCtl.ready()));
            } catch (Exception ignored) { }
            try { Thread.sleep(5000); } catch (InterruptedException e) { break; }
        }
        running = false;
        notify_("SGM挂机已停止");
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
