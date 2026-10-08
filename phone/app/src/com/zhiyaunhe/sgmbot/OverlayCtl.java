package com.zhiyaunhe.sgmbot;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

/**
 * 悬浮条的生命周期管理 — **独立于 BotService**。
 *
 * 为什么单独抽一层 (用户原话: "悬浮窗可以单独启动"):
 *   旧实现把悬浮条塞在 BotService.onCreate 里 —— 于是"想看悬浮条"必须先"起服务",
 *   而"起服务"又要求 Shizuku 就绪, 否则 onStartCommand 里 `return START_STICKY`
 *   直接把模式丢掉, 服务虽然活着但什么都不干。结果就是:
 *     没装 Shizuku → 没悬浮条 → 也没法从 App 看状态, 一片死。
 *   而 autojs 版 floaty.window 是脚本自己开的, 跟"能不能跑"无关。现在就对齐它。
 *
 * 三条规则:
 *   ① 谁先来谁负责创建, 后到的**复用**已有实例 (服务起来时不动用户已经摆好的位置)
 *   ② 只有 App 进程存在, 悬浮条就在 (托盘/主页都能启停)
 *   ③ 用户关掉"显示在应用上层"权限后, 进程内实例可能还在 (窗口被系统摘掉),
 *      所以每次 show 都先查 canDrawOverlays, 不靠缓存状态
 */
public final class OverlayCtl {
    private static volatile OverlayBar bar;
    private static volatile String err = "";
    private static Handler timer;
    private static Runnable tick;
    /** 当前这条窗口是不是"服务起来时挂的" —— 服务退出时只有这种该被收掉 */
    private static volatile boolean svcOwned;

    private OverlayCtl() { }

    /** 上次失败原因 (空 = 没失败过) */
    public static String lastError() { return err; }

    public static boolean isShown() { return bar != null && bar.isShown(); }

    public static OverlayBar bar() { return bar; }

    /**
     * 显示悬浮条 (幂等)。
     * @return null = 成功, 否则返回**人话失败原因** (UI 直接弹这个)
     */
    public static synchronized String show(Context ctx) {
        err = "";
        if (ctx == null) ctx = App.inst;
        if (ctx == null) return err = "App 还没起来 (没有 Context)";
        if (!Settings.canDrawOverlays(ctx)) {
            err = "没有「显示在应用上层」权限 — 去主页点「② 授权悬浮窗」打开开关";
            SgmLog.i("overlay", "单独启动被拦: " + err);
            return err;
        }
        if (bar != null && bar.isShown()) {
            SgmLog.i("overlay", "已显示, 复用现有窗口");
            return null;
        }
        try {
            bar = new OverlayBar(ctx);
            bar.show();
            bar.setText("待机\n今日" + Store.load().rounds + "场\n点开始跑");
            svcOwned = false;                    // 用户自己开的 ⇒ 服务退出不收它
            startTick(ctx);
            SgmLog.i("overlay", "已独立启动 (不依赖服务)");
            return null;
        } catch (Throwable t) {
            bar = null;
            err = "挂载失败: " + Ui.human(t);
            SgmLog.i("overlay", "挂载异常: " + t);
            return err;
        }
    }

    /** 隐藏 (只收窗口, 不动服务/引擎) */
    public static synchronized void hide() {
        stopTick();
        if (bar != null) {
            bar.destroy();
            bar = null;
            SgmLog.i("overlay", "已隐藏 (独立于服务)");
        } else {
            SgmLog.i("overlay", "本来就没显示");
        }
    }

    /**
     * 服务侧调用: 服务起来时**只补挂**不覆盖。
     * 与 show() 的区别: 这里不查权限、不弹错 (服务静默跑), 失败只记日志。
     */
    public static synchronized OverlayBar attachForService(Context ctx) {
        if (bar != null && bar.isShown()) return bar;      // 用户已摆好的, 别动
        if (!Settings.canDrawOverlays(ctx)) {
            SgmLog.i("overlay", "服务起时无悬浮窗权限 — 只跑引擎");
            return null;
        }
        try {
            bar = new OverlayBar(ctx);
            bar.show();
            svcOwned = true;                     // 服务名下 ⇒ 服务退出时一并收掉
            startTick(ctx);
            return bar;
        } catch (Throwable t) {
            bar = null;
            SgmLog.i("overlay", "服务侧挂载失败: " + t);
            return null;
        }
    }

    /** 当前窗口是不是服务挂的 (服务 onDestroy 用它决定收不收) */
    public static boolean ownedByService() { return svcOwned && bar != null; }

    /* ---------------- 无服务时的统计刷新 ----------------
     * 服务跑的时候统计由 SettleLoop.pushUi 推 (每 2s)。
     * 服务**没**跑时没人推 —— 那格会一直停在"待机"。这里用一个 5s 定时器兜:
     * 只读 store.json 的今日计数 + 进程内的运行状态, 不截屏不点击, 开销可忽略。
     * (JS 版也有个 5s 的保活 setInterval 干同一件事。) */

    private static void startTick(final Context ctx) {
        stopTick();
        timer = new Handler(Looper.getMainLooper());
        tick = new Runnable() {
            @Override public void run() {
                OverlayBar b = bar;
                if (b == null || !b.isShown()) { stopTick(); return; }
                /* 服务在跑时不要抢 SettleLoop 的文案 (它会显示时长/胜负) */
                if (!BotService.isRunning()) {
                    Store s = Store.load();
                    String phase = ShizukuCtl.ready() ? "就绪" : "通道未就绪";
                    b.setText("待机 (" + phase + ")\n今日" + s.rounds + "场\n点开始跑");
                }
                if (timer != null) timer.postDelayed(this, 5000);
            }
        };
        timer.postDelayed(tick, 5000);
    }

    private static void stopTick() {
        if (timer != null && tick != null) timer.removeCallbacks(tick);
        tick = null;
    }

    /* ---------------- 位置记忆 (独立启动没有服务记, 自己存) ---------------- */

    private static final String POS = "overlay_pos";

    static void rememberPos(int x, int y) {
        try {
            Context c = App.inst;
            if (c == null) return;
            c.getSharedPreferences("sgmbot", Context.MODE_PRIVATE)
                    .edit().putString(POS, x + "," + y).apply();
        } catch (Throwable ignored) { }
    }

    static int[] savedPos() {
        try {
            Context c = App.inst;
            if (c == null) return null;
            String s = c.getSharedPreferences("sgmbot", Context.MODE_PRIVATE)
                    .getString(POS, "");
            if (s == null || s.length() == 0) return null;
            String[] p = s.split(",");
            return new int[]{Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim())};
        } catch (Throwable t) {
            return null;
        }
    }

    private static final String FOLD = "overlay_fold";

    static void rememberFold(boolean on) {
        try {
            Context c = App.inst;
            if (c == null) return;
            c.getSharedPreferences("sgmbot", Context.MODE_PRIVATE)
                    .edit().putBoolean(FOLD, on).apply();
        } catch (Throwable ignored) { }
    }
}
