package com.zhiyaunhe.sgmbot;

import android.annotation.SuppressLint;
import android.graphics.PixelFormat;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 悬浮控制条 — 替代 AutoJs6 floaty 版。三键顶排 + 三行统计, 空白处拖动, 贴边缩进。
 * 与 autojs 版同交互模型: 按钮是 clickable 子 view 自吞触摸, 拖动走根空白区的
 * OnTouchListener (空白才收到, 互不抢)。窗口挂在 BotService 名下, 服务亡则窗亡。
 */
public final class OverlayBar {
    private static final int PEEK = 26;   // 贴边露出像素
    private static final int SNAP = 70;   // 触发吸附的边缘距离

    private final BotService svc;
    private final WindowManager wm;
    private LinearLayout root;
    private TextView tvStat;
    private int winW;

    public OverlayBar(BotService svc) {
        this.svc = svc;
        this.wm = (WindowManager) svc.getSystemService(android.content.Context.WINDOW_SERVICE);
    }

    @SuppressLint("ClickableViewAccessibility")
    public void show() {
        if (root != null) return;
        LinearLayout row = new LinearLayout(svc);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(110, 70);
        bp.rightMargin = 8;
        String[] names = {"开始", "导航", "停止"};
        final String[] acts = {"start", "start_nav", "stop"};
        for (int i = 0; i < 3; i++) {
            Button b = new Button(svc);
            b.setText(names[i]);
            b.setTextSize(14f);
            b.setPadding(0, 0, 0, 0);
            final String act = acts[i];
            b.setOnClickListener(v -> BotService.trigger(act));
            row.addView(b, bp);
        }

        tvStat = new TextView(svc);
        tvStat.setTextSize(15f);
        tvStat.setTextColor(0xFFFFFFFF);
        tvStat.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 150);
        sp.topMargin = 6;

        root = new LinearLayout(svc);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xCC1E1E1E);
        root.setPadding(14, 14, 14, 14);
        root.addView(row);
        root.addView(tvStat, sp);

        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
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

        final int[] down = new int[2];   // 按下时指针相对窗口偏移
        final int[] pos = new int[2];    // 拖动中窗口位置
        root.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        down[0] = (int) e.getRawX() - lp.x;
                        down[1] = (int) e.getRawY() - lp.y;
                        winW = root.getWidth();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        pos[0] = (int) e.getRawX() - down[0];
                        pos[1] = (int) e.getRawY() - down[1];
                        lp.x = pos[0]; lp.y = pos[1];
                        try { wm.updateViewLayout(root, lp); } catch (Exception ignored) { }
                        return true;
                    case MotionEvent.ACTION_UP:
                        winW = root.getWidth();
                        if (winW > 0) {
                            if (lp.x + winW >= svc.screenW() - SNAP) lp.x = svc.screenW() - PEEK;
                            else if (lp.x <= SNAP) lp.x = -(winW - PEEK);
                            try { wm.updateViewLayout(root, lp); } catch (Exception ignored) { }
                        }
                        return true;
                }
                return false;
            }
        });

        try {
            wm.addView(root, lp);
            SgmLog.i("overlay", "悬浮条已挂载");
        } catch (Exception e) {
            SgmLog.i("overlay", "挂载失败 (缺悬浮窗权限?): " + e);
        }
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
