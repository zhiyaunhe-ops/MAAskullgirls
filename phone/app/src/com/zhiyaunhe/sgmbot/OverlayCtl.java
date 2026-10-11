package com.zhiyaunhe.sgmbot;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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

    /* ---------------- 主线程调度 ----------------
     * 为什么非有不可 (2026-10-11): WindowManager.addView/removeView 都会经
     * ViewRootImpl —— 它构造时就要 Looper (Choreographer.getInstance()) 且要求
     * "只在创建视图层级的那条线程上触碰视图"。而控制面三个入口线程各不相同:
     *   悬浮条/主页按钮 = 主线程  ✓;  广播 (CtlReceiver) = 主线程 ✓;
     *   HTTP /trigger  (DebugHttp 的 debug-http worker 线程) = **后台线程** ✗。
     * 实测后果: `/trigger?action=overlay_show` 直接抛 "Can't create handler inside
     * thread that has not called Looper.prepare()" (被 show() 的 catch 吞成一句
     * "挂载失败" —— 用户看到的是"没反应"); 现在 end() 也会从这条线调 hide(),
     * 不收口就会在"结束"时崩。所以挂载一律转到主线程并等结果 (show 要返回错误原因)。
     * ⚠️ 等的时候**不能持有 OverlayCtl 的类锁** (内层 showInner 才是 synchronized):
     *    否则主线程上的按钮回调要拿同一把锁, 会卡满 3s 超时 = 界面假死。
     *    (hide 不需要这层: 它自己同步摘引用 + post 删除, 见下。) */

    private static void runOnMainSync(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) { r.run(); return; }
        final CountDownLatch latch = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() {
                try { r.run(); } finally { latch.countDown(); }
            }
        });
        try {
            if (!latch.await(3, TimeUnit.SECONDS))
                SgmLog.i("overlay", "主线程调度超时 (3s) — 结果可能未生效");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 显示悬浮条 (幂等)。
     * @return null = 成功, 否则返回**人话失败原因** (UI 直接弹这个)
     */
    public static String show(final Context c0) {
        final String[] out = new String[1];
        runOnMainSync(() -> out[0] = showInner(c0));
        return out[0];
    }

    private static synchronized String showInner(Context ctx) {
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
            bar.refreshStartLabel();             // 暂停中重新叫出来 ⇒ 那颗键该是「继续」
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

    /**
     * 隐藏 (只收窗口, 不动服务/引擎)。
     * 分两步: **同步**摘掉静态引用 (幂等 + 让 isShown()/ownedByService() 立刻变 false),
     * 再把真正的 removeView **post 到主线程**。
     * 为什么要 defer 而不是就地删 (2026-10-11): 悬浮条上的「结束」按钮点下去时,
     * onClick 就是在**这个窗口自己的视图层级**里跑的, 就地 removeView 等于在自己的
     * 回调里把它拆掉 (部分 ROM 会抛 "View not attached to window manager")。
     * 延后一拍让本次点击分发先走完最稳。
     */
    public static void hide() {
        final OverlayBar b;
        synchronized (OverlayCtl.class) {
            stopTick();
            b = bar;
            bar = null;
        }
        if (b == null) {
            SgmLog.i("overlay", "本来就没显示");
            return;
        }
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() {
                b.destroy();
                SgmLog.i("overlay", "已隐藏 (独立于服务)");
            }
        });
    }

    /**
     * 服务侧调用: 服务起来时**只补挂**不覆盖。
     * 与 show() 的区别: 这里不查权限、不弹错 (服务静默跑), 失败只记日志。
     */
    public static OverlayBar attachForService(final Context c0) {
        final OverlayBar[] out = new OverlayBar[1];
        runOnMainSync(() -> out[0] = attachInner(c0));
        return out[0];
    }

    private static synchronized OverlayBar attachInner(Context ctx) {
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
                /* 「开始 / 继续」的名字跟着状态走 —— 引擎可能不是被按键停的
                 * (跑满每日上限/最长时长时 SettleLoop 自己停), 那时没有点击来立刻刷标签。 */
                b.refreshStartLabel();
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
