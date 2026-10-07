package com.zhiyaunhe.sgmbot;

import android.annotation.SuppressLint;
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
 * 窗口挂在 BotService 名下, 服务亡则窗亡。
 */
public final class OverlayBar {
    private final BotService svc;
    private final WindowManager wm;
    private WindowManager.LayoutParams lp;
    private FrameLayout root;
    private LinearLayout panel;
    private Button btnMini;
    private TextView tvStat;
    private boolean folded;

    public OverlayBar(BotService svc) {
        this.svc = svc;
        this.wm = (WindowManager) svc.getSystemService(android.content.Context.WINDOW_SERVICE);
    }

    @SuppressLint("ClickableViewAccessibility")
    public void show() {
        if (root != null) return;

        /* ---- 面板: 按键行 + 统计 ---- */
        LinearLayout row = new LinearLayout(svc);
        row.setOrientation(LinearLayout.HORIZONTAL);
        addBtn(row, "开始", "start");
        addBtn(row, "导航", "start_nav");
        addBtn(row, "停止", "stop");
        addBtn(row, "结束", "end");
        Button fold = new Button(svc);
        fold.setText("◀");
        fold.setTextSize(12f);
        fold.setPadding(0, 0, 0, 0);
        fold.setOnClickListener(v -> setFold(true));
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(64, 66);
        fp.leftMargin = 6;
        row.addView(fold, fp);

        tvStat = new TextView(svc);
        tvStat.setTextSize(15f);
        tvStat.setTextColor(0xFFFFFFFF);
        tvStat.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 210);
        sp.topMargin = 6;

        panel = new LinearLayout(svc);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.addView(row);
        panel.addView(tvStat, sp);

        /* ---- 折叠小箭头 (初始隐藏) ---- */
        btnMini = new Button(svc);
        btnMini.setText("◀");
        btnMini.setTextSize(12f);
        btnMini.setPadding(0, 0, 0, 0);
        btnMini.setVisibility(View.GONE);
        btnMini.setOnClickListener(v -> setFold(false));

        root = new FrameLayout(svc);
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

        root.setOnTouchListener(dragListener());
        btnMini.setOnTouchListener(dragListener());   // 小箭头也能拖 (点击=展开)

        try {
            wm.addView(root, lp);
            SgmLog.i("overlay", "悬浮条已挂载");
        } catch (Exception e) {
            SgmLog.i("overlay", "挂载失败 (缺悬浮窗权限?): " + e);
        }
    }

    private void addBtn(LinearLayout row, String name, String act) {
        Button b = new Button(svc);
        b.setText(name);
        b.setTextSize(12f);
        b.setPadding(0, 0, 0, 0);
        b.setOnClickListener(v -> BotService.trigger(act));
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
                    return true;
            }
            return false;
        };
    }

    /** 松手拉回屏内: 面板/小箭头都不能被拖丢在屏外 (JS clampPos 同款) */
    private void clampPos() {
        int w = root.getWidth(), h = root.getHeight(), sw = svc.screenW(), sh = svc.screenH();
        if (w <= 0 || h <= 0) return;
        if (lp.x < 0) lp.x = 0;
        if (lp.y < 0) lp.y = 0;
        if (lp.x + w > sw) lp.x = sw - w;
        if (lp.y + h > sh) lp.y = sh - h;
    }

    /** 折叠: 面板 ↔ 小箭头; 折叠时窗口变小, 把窗口拉回屏内防箭头消失在屏外 */
    private void setFold(boolean on) {
        folded = on;
        panel.setVisibility(on ? View.GONE : View.VISIBLE);
        btnMini.setVisibility(on ? View.VISIBLE : View.GONE);
        clampPos();
        try { wm.updateViewLayout(root, lp); } catch (Exception ignored) { }
    }

    public void destroy() {
        if (root != null) {
            try { wm.removeView(root); } catch (Exception ignored) { }
            root = null;
        }
    }

    /** 三行统计: 前缀+时长 / 今日场数 / 本次胜负 — 同 autojs setStat 文案 */
    public void setStat(String prefix, String elapsed, String daily, int wins, int loses) {
        if (tvStat == null) return;
        svc.runOnUi(() -> tvStat.setText(prefix + " " + elapsed + "\n" + daily
                + "\n" + wins + "胜" + loses + "负"));
    }

    public void setText(String s) {
        if (tvStat == null) return;
        svc.runOnUi(() -> tvStat.setText(s));
    }
}
