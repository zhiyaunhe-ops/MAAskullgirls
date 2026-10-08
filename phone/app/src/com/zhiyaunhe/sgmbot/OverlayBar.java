package com.zhiyaunhe.sgmbot;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 悬浮控制条 — 移植自 autojs settle_bot.js 最新悬浮面板 (2026-10-07 口径):
 *   五键一行 (开始/导航/停止/结束/◀) + 三行统计; ◀ 折成贴边小箭头, 点箭头展开;
 *   拖动按住空白处, 松手 clamp 拉回屏内 (无贴边缩进);
 *   停止 = 暂停引擎可再开; 结束 = 连服务/悬浮窗/通知一并收掉 (退出脚本同义)。
 *
 * ⚠️ 2026-10-08 改造: **不再依赖 BotService 实例** —— 用户要求"悬浮窗可以单独启动"。
 *    之前构造要传 BotService, 而窗口的 Context/WindowManager/屏幕尺寸全从它拿,
 *    等于"服务没起就没悬浮条", 与 autojs 版(floaty.window 在脚本里就能开)的体验
 *    不一致。现在改成只收 Context (Activity 也行) + 一组尺寸回调, 详见 OverlayCtl。
 *    窗口类型仍是 TYPE_APPLICATION_OVERLAY, 所以**谁是 Context 都不影响挂载**。
 */
public final class OverlayBar {
    private final Context ctx;
    private final WindowManager wm;
    /** 屏幕尺寸提供者 — Activity 侧用 DisplayMetrics, Service 侧同款; 默认走 wm */
    private final Size size;
    private WindowManager.LayoutParams lp;
    private FrameLayout root;
    private LinearLayout panel;
    private Button btnMini;
    private TextView tvStat;
    private boolean folded;
    /** 统计文案 (单独启动时由 OverlayCtl 周期刷新; 服务跑时由 SettleLoop 推) */
    private volatile String statText = "待机";
    /** 折叠/位置变化回调 (托盘侧要持久化记忆位置时用) */
    private Runnable onLayoutChange;

    public interface Size { int w(); int h(); }

    public OverlayBar(Context ctx) {
        this(ctx, null);
    }

    public OverlayBar(Context ctx, Size size) {
        this.ctx = ctx;
        this.wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        this.size = size != null ? size : defaultSize(ctx);
    }

    /** 默认屏幕尺寸 — 用 WindowManager 的显示指标 (不依赖任何 Service 方法) */
    private static Size defaultSize(final Context c) {
        return new Size() {
            @Override public int w() {
                try {
                    android.util.DisplayMetrics m = new android.util.DisplayMetrics();
                    ((WindowManager) c.getSystemService(Context.WINDOW_SERVICE))
                            .getDefaultDisplay().getMetrics(m);
                    return m.widthPixels;
                } catch (Throwable t) { return 1080; }
            }
            @Override public int h() {
                try {
                    android.util.DisplayMetrics m = new android.util.DisplayMetrics();
                    ((WindowManager) c.getSystemService(Context.WINDOW_SERVICE))
                            .getDefaultDisplay().getMetrics(m);
                    return m.heightPixels;
                } catch (Throwable t) { return 2340; }
            }
        };
    }

    public void setOnLayoutChange(Runnable r) { this.onLayoutChange = r; }

    public boolean isShown() { return root != null; }

    @SuppressLint("ClickableViewAccessibility")
    public void show() {
        if (root != null) return;

        /* ---- 面板: 按键行 + 统计 ---- */
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        addBtn(row, "开始", "start");
        addBtn(row, "导航", "start_nav");
        addBtn(row, "停止", "stop");
        addBtn(row, "结束", "end");
        Button fold = new Button(ctx);
        fold.setText("◀");
        fold.setTextSize(12f);
        fold.setPadding(0, 0, 0, 0);
        fold.setOnClickListener(v -> setFold(true));
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(64, 66);
        fp.leftMargin = 6;
        row.addView(fold, fp);

        tvStat = new TextView(ctx);
        tvStat.setTextSize(15f);
        tvStat.setTextColor(0xFFFFFFFF);
        tvStat.setGravity(Gravity.CENTER);
        tvStat.setText(statText);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 210);
        sp.topMargin = 6;

        panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.addView(row);
        panel.addView(tvStat, sp);

        /* ---- 折叠小箭头 (初始隐藏) ---- */
        btnMini = new Button(ctx);
        btnMini.setText("◀");
        btnMini.setTextSize(12f);
        btnMini.setPadding(0, 0, 0, 0);
        btnMini.setVisibility(View.GONE);
        btnMini.setOnClickListener(v -> setFold(false));

        root = new FrameLayout(ctx);
        root.setBackgroundColor(0xCC1E1E1E);
        root.setPadding(14, 14, 14, 14);
        root.addView(panel);
        root.addView(btnMini, new FrameLayout.LayoutParams(96, 66));

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = 24;
        lp.y = 90;
        /* 单独启动时没有服务给它记位置 ⇒ 从上次关掉的地方恢复 (OverlayCtl 存盘) */
        int[] pos = OverlayCtl.savedPos();
        if (pos != null) { lp.x = pos[0]; lp.y = pos[1]; }

        root.setOnTouchListener(dragListener());
        btnMini.setOnTouchListener(dragListener());   // 小箭头也能拖 (点击=展开)

        try {
            wm.addView(root, lp);
            SgmLog.i("overlay", "悬浮条已挂载 (独立于服务)");
        } catch (Exception e) {
            root = null;
            SgmLog.i("overlay", "挂载失败 (缺悬浮窗权限?): " + e);
            throw new IllegalStateException("挂载失败: " + e.getMessage(), e);
        }
    }

    private void addBtn(LinearLayout row, String name, String act) {
        Button b = new Button(ctx);
        b.setText(name);
        b.setTextSize(12f);
        b.setPadding(0, 0, 0, 0);
        b.setOnClickListener(v -> {
            /* 悬浮条上点按钮也要有反馈 —— 之前点了没动静让人以为是按钮坏了 */
            String why = Ui.blockReason(null, Ui.NEED_SHIZUKU_SERVER);
            if ("start".equals(act) || "start_nav".equals(act)) {
                if (why != null) {
                    setText(why);
                    SgmLog.i("overlay", "开始被拦: " + why);
                    /* 送用户去指引页 (悬浮窗没法跳 Activity 之外的设置, 但可以说明) */
                    return;
                }
            }
            setText("已发送: " + name);
            BotService.trigger(act);
            SgmLog.i("overlay", "按钮 " + name + " → trigger(" + act + ")");
        });
        row.addView(b, new LinearLayout.LayoutParams(96, 66));
    }

    /** 按住空白处拖动; 松手 clamp 拉回屏内 (JS clampPos 同款, 无贴边缩进) */
    @SuppressLint("ClickableViewAccessibility")
    private View.OnTouchListener dragListener() {
        final int[] down = new int[2];
        final int[] moved = {0};
        return (v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down[0] = (int) e.getRawX() - lp.x;
                    down[1] = (int) e.getRawY() - lp.y;
                    moved[0] = 0;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    int nx = (int) e.getRawX() - down[0], ny = (int) e.getRawY() - down[1];
                    if (Math.abs(nx - lp.x) + Math.abs(ny - lp.y) > 4) moved[0] = 1;
                    lp.x = nx; lp.y = ny;
                    try { wm.updateViewLayout(root, lp); } catch (Exception ignored) { }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    if (v == btnMini && moved[0] == 0) setFold(false);   // 点箭头未拖 = 展开
                    clampPos();
                    try { wm.updateViewLayout(root, lp); } catch (Exception ignored) { }
                    OverlayCtl.rememberPos(lp.x, lp.y);      // 记住位置, 下次独立启动复原
                    if (onLayoutChange != null) onLayoutChange.run();
                    return true;
            }
            return false;
        };
    }

    /** 松手拉回屏内: 面板/小箭头都不能被拖丢在屏外 (JS clampPos 同款) */
    private void clampPos() {
        int w = root.getWidth(), h = root.getHeight(), sw = size.w(), sh = size.h();
        if (w <= 0 || h <= 0) return;
        if (lp.x < 0) lp.x = 0;
        if (lp.y < 0) lp.y = 0;
        if (lp.x + w > sw) lp.x = sw - w;
        if (lp.y + h > sh) lp.y = sh - h;
    }

    /**
     * 折叠: 面板 ↔ 小箭头; 折叠时窗口变小, 把窗口拉回屏内防箭头消失在屏外。
     * ⚠️ 折叠态也必须 clamp —— 小箭头比面板小得多, 不 clamp 的话原本贴右下的面板
     *    折起来后箭头会留在屏外 (JS 版踩过)。
     */
    private void setFold(boolean on) {
        folded = on;
        panel.setVisibility(on ? View.GONE : View.VISIBLE);
        btnMini.setVisibility(on ? View.VISIBLE : View.GONE);
        clampPos();
        try { wm.updateViewLayout(root, lp); } catch (Exception ignored) { }
        OverlayCtl.rememberFold(on);
    }

    public void destroy() {
        if (root != null) {
            try { wm.removeView(root); } catch (Exception ignored) { }
            root = null;
        }
    }

    /** 三行统计: 前缀+时长 / 今日场数 / 本次胜负 — 同 autojs setStat 文案 */
    public void setStat(String prefix, String elapsed, String daily, int wins, int loses) {
        setText(prefix + " " + elapsed + "\n" + daily + "\n" + wins + "胜" + loses + "负");
    }

    public void setText(String s) {
        if (s == null) return;
        statText = s;
        final TextView t = tvStat;
        if (t == null) return;
        post(() -> t.setText(s));
    }

    /** UI 线程 post — Activity 与 Service 都能用 (Context 不保证是 Service) */
    private void post(Runnable r) {
        try {
            if (ctx instanceof android.app.Service) {
                new android.os.Handler(android.os.Looper.getMainLooper()).post(r);
            } else {
                new android.os.Handler(android.os.Looper.getMainLooper()).post(r);
            }
        } catch (Throwable t) {
            /* 没有 Looper 时直接跑 (极罕见) */
            r.run();
        }
    }

    /** 当前统计文案 (给 OverlayCtl 的周期刷新用) */
    public String statText() { return statText; }

    /** 窗口是否处于折叠态 */
    public boolean folded() { return folded; }
}
