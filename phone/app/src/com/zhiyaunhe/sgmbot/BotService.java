package com.zhiyaunhe.sgmbot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.IBinder;

/**
 * 前台服务外壳 — 常驻通知 + 工作线程宿主。
 * M2 起主循环 (SettleLoop/NavChain 移植) 在这里起线程; 本文件先交付控制面:
 * 广播/HTTP/悬浮条三种入口都汇到 start()/stop()/trigger(), 状态写 SgmLog.state。
 */
public class BotService extends Service {
    public static final String MODE_SETTLE = "settle";
    public static final String MODE_NAV = "nav";

    private static volatile boolean running = false;
    private static volatile String mode = "";
    private static Config cfg;
    private static ShizukuCtl sh;
    private static TouchCtl touch;

    public static ShizukuCtl sh() { return sh; }
    public static Config cfg() { return cfg; }

    public static void start(Context ctx, String m) {
        if (running) { SgmLog.i("svc", "already running mode=" + mode); return; }
        ctx.startService(new Intent(ctx, BotService.class).putExtra("mode", m));
    }

    public static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, BotService.class));
    }

    /** 广播/HTTP 的动作分发 (与 CtlReceiver 同一套语义) */
    public static void trigger(String action) {
        Context c = App.inst;
        if ("start_nav".equals(action)) start(c, MODE_NAV);
        else if ("start".equals(action)) start(c, MODE_SETTLE);
        else if ("stop".equals(action)) stop(c);
        else if ("reload".equals(action)) cfg.read();
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
        notify_("SGM挂机待命");
        SgmLog.i("svc", "created");
    }

    @Override
    public int onStartCommand(Intent in, int flags, int id) {
        String m = in != null ? in.getStringExtra("mode") : null;
        if (m != null && !running && sh.alive()) {
            running = true;
            mode = m;
            final String mm = m;
            new Thread(new Runnable() {
                @Override public void run() { runBot(mm); }
            }, "bot-" + mm).start();
        } else if (!sh.alive()) {
            SgmLog.i("svc", "shizuku not connected — 引导用户去 App 主页连接");
            notify_("Shizuku 未连接 — 打开 App 按引导激活");
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        App.service = null;
        SgmLog.i("svc", "destroyed");
        super.onDestroy();
    }

    private void runBot(String m) {
        SgmLog.i("bot", "runBot mode=" + m + " (M2: SettleLoop/NavChain 移植后接管)");
        notify_("SGM挂机 " + m + " 运行中");
        // TODO(M2): m.equals(MODE_NAV) ? new NavChain(cfg, sh, touch).run() : new SettleLoop(...)
        long t0 = System.currentTimeMillis();
        while (running) {
            try {
                SgmLog.state(DebugHttp.readFileJson(SgmLog.DIR + "state.json")
                        .put("mode", m).put("running", true)
                        .put("elapsed_s", (System.currentTimeMillis() - t0) / 1000));
            } catch (Exception ignored) { }
            try { Thread.sleep(5000); } catch (InterruptedException e) { break; }
        }
        running = false;
        notify_("SGM挂机已停止");
    }

    private void notify_(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        String ch = "bot";
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(ch, "挂机", NotificationManager.IMPORTANCE_LOW));
        }
        Notification n = null;
        if (Build.VERSION.SDK_INT >= 26) {
            n = new Notification.Builder(this, ch)
                    .setContentTitle("SGM挂机").setContentText(text).setSmallIcon(android.R.drawable.ic_media_play)
                    .setOngoing(true).build();
        } else {
            n = new Notification.Builder(this)
                    .setContentTitle("SGM挂机").setContentText(text).setSmallIcon(android.R.drawable.ic_media_play)
                    .setOngoing(true).build();
        }
        startForeground(1, n);
    }

    /* 供 DebugHttp/Receiver 侧的临时取帧 (M1 调试用) */
    public Bitmap grab() { return sh != null ? sh.capture() : null; }
}
