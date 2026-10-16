package com.zhiyaunhe.sgmbot;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 运行监控页 —— "App 随时监控"的那个入口。
 *
 * <h3>为什么需要它</h3>
 * 长跑（目标是一场 200 场）时人不在旁边，只靠 {@code logcat} / 拉日志文件看进度太别扭。
 * 这一页把"现在在干嘛"直接摆出来，1.5 秒自动刷：
 * <ul>
 *   <li>在不在跑、跑到第几场、跑了多久</li>
 *   <li><b>当前识别到的是哪个界面</b>（{@link PfScene}）—— 卡住时一眼看出卡在哪一页</li>
 *   <li>最近 20 行日志（不截断，滚着看）</li>
 * </ul>
 *
 * <h3>取数方式</h3>
 * 全部从 {@link PfFlow#live()} 读**实时**字段，不走 HTTP、不额外截图 —— 监控本身不能
 * 打扰正在跑的循环（早期版本为了让主线程有响应才搞了 8791 调试口，那是给电脑用的；
 * 手机上看就直接读内存里的实例）。
 */
public class MonitorActivity extends Activity {

    private TextView tvState, tvScene, tvStep, tvCount, tvTime, tvLog;
    private final Handler h = new Handler(Looper.getMainLooper());

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            refresh();
            h.postDelayed(this, 1500);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF14161A);
        int pad = (int) (14 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        tvState = big("—", 20f, 0xFFFFFFFF);
        tvScene = line("界面: -");
        tvStep  = line("动作: -");
        tvCount = line("场次: -");
        tvTime  = line("已跑: -");
        tvLog   = mono();

        root.addView(tvState);
        root.addView(gap());
        root.addView(tvScene);
        root.addView(tvStep);
        root.addView(tvCount);
        root.addView(tvTime);
        root.addView(gap());
        root.addView(label("最近日志"));
        root.addView(tvLog);
        sv.addView(root);
        setContentView(sv);
    }

    @Override protected void onResume() { super.onResume(); h.post(tick); }
    @Override protected void onPause() { h.removeCallbacks(tick); super.onPause(); }

    private void refresh() {
        PfFlow f = PfFlow.live();
        boolean running = BotService.isRunning() && f != null;
        tvState.setText(running ? "● 运行中" : (BotService.paused() ? "⏸ 已暂停" : "○ 未运行"));
        tvState.setTextColor(running ? 0xFF4ADE80 : (BotService.paused() ? 0xFFFBBF24 : 0xFF9CA3AF));
        if (f == null) {
            tvScene.setText("界面: -");
            tvStep.setText("动作: -");
            tvCount.setText("场次: -");
            tvTime.setText("已跑: -");
        } else {
            tvScene.setText("界面: " + f.sceneText());
            tvStep.setText("动作: " + f.stepText());
            tvCount.setText("场次: " + f.roundsDone + " 场");
            long s = f.elapsedMs() / 1000;
            tvTime.setText(String.format("已跑: %d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60));
        }
        tvLog.setText(tail(20));
    }

    /** 日志尾部 N 行 —— 直接读盘, 与 SgmLog 落盘的是同一个文件。 */
    private String tail(int n) {
        File f = new File(Paths.logDir(), "app.log");
        if (!f.exists()) return "(还没有日志)";
        Deque<String> q = new ArrayDeque<>();
        try {
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String ln;
            while ((ln = r.readLine()) != null) {
                q.addLast(ln);
                if (q.size() > n) q.removeFirst();
            }
            r.close();
        } catch (Exception e) {
            return "(读日志失败: " + e + ")";
        }
        return TextUtils.join("\n", q);
    }

    // ---------------- 小控件 ----------------

    private TextView big(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        return t;
    }

    private TextView line(String s) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextColor(0xFFD1D5DB);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        t.setPadding(0, 6, 0, 6);
        return t;
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextColor(0xFF60A5FA);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        t.setPadding(0, 8, 0, 4);
        return t;
    }

    private TextView mono() {
        TextView t = new TextView(this);
        t.setTextColor(0xFF9CA3AF);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        t.setTypeface(android.graphics.Typeface.MONOSPACE);
        t.setBackgroundColor(0xFF1E2128);
        t.setPadding(8, 8, 8, 8);
        t.setGravity(Gravity.TOP);
        return t;
    }

    private TextView gap() {
        TextView t = new TextView(this);
        t.setHeight((int) (10 * getResources().getDisplayMetrics().density));
        return t;
    }
}
