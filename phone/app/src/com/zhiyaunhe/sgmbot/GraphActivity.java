package com.zhiyaunhe.sgmbot;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 判定路径图 — 手机上直接看, 不用 adb forward。
 *
 * 做法: 内嵌 WebView 打开 http://127.0.0.1:8791/ 。那个 HTTP 服务由 App 进程
 * 自己在 onCreate 里起 (App.java), 只绑回环地址, 所以**同进程内的 WebView 天然
 * 能访问** —— 不需要任何网络权限之外的配置, 也不用对外暴露。
 *
 * ⚠️ WebView 默认**关 JavaScript**: 页面全靠 JS 拉 /graph 再画 SVG, 不开就是
 *    一片空白 (这种"白屏但没报错"最容易误判成接口坏了)。所以必须
 *    setJavaScriptEnabled(true), 且要在 loadUrl 之前设。
 * ⚠️ 服务可能还没起 (App 刚冷启动) 或已停 → 先探 /status, 探不到就提示并允许
 *    就地启动服务, 而不是丢一个空白页让人猜。
 */
public class GraphActivity extends Activity {
    private WebView web;
    private TextView tip;
    private final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(20, 24, 20, 20);

        TextView t = new TextView(this);
        t.setText("判定路径图 — 节点/边全部来自 config.graph");
        t.setTextSize(15f);
        page.addView(t);

        tip = new TextView(this);
        tip.setTextSize(12f);
        tip.setTextColor(Color.parseColor("#6B7280"));
        tip.setPadding(0, 6, 0, 6);
        page.addView(tip);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        Button reload = new Button(this);
        reload.setText("刷新");
        reload.setAllCaps(false);
        reload.setOnClickListener(v -> load());
        btns.addView(reload);
        Button start = new Button(this);
        start.setText("启动服务");
        start.setAllCaps(false);
        start.setOnClickListener(v -> {
            String why = Ui.blockReason(this, Ui.NEED_SHIZUKU_SERVER);
            if (why != null) {
                tip.setTextColor(Color.parseColor("#D93636"));
                tip.setText("✗ " + why);
                Ui.toast(this, why);
                return;
            }
            BotService.start(this, BotService.MODE_SETTLE);
            tip.setTextColor(Color.parseColor("#6B7280"));
            tip.setText("› 已请求启动 — 等 2~3 秒后刷新");
            h.postDelayed(this::load, 2500);
        });
        btns.addView(start);
        page.addView(btns);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);          // 必须在 loadUrl 之前
        s.setDomStorageEnabled(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        page.addView(web, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        navBar(page);                // 底部页签 (本轮新增: 与战绩页/配置页互跳)
        setContentView(page);
        load();
    }

    /** 底部导航 — 判定图/战绩/配置都是"看数据"的页, 互相能跳省得回主页再点 */
    private void navBar(LinearLayout page) {
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setPadding(20, 4, 20, 16);
        String[] names = {"判定图", "战绩热力图", "编辑配置"};
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            TextView b = new TextView(this);
            b.setText(names[i]);
            b.setTextSize(13f);
            b.setGravity(Gravity.CENTER);
            b.setPadding(0, 18, 0, 18);
            b.setTextColor(idx == 0 ? 0xFFFFFFFF : Color.parseColor("#2F6BFF"));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(6, 0, 6, 0);
            b.setLayoutParams(lp);
            android.graphics.drawable.GradientDrawable g =
                    new android.graphics.drawable.GradientDrawable();
            g.setColor(idx == 0 ? Color.parseColor("#2F6BFF") : Color.TRANSPARENT);
            g.setCornerRadius(20);
            g.setStroke(2, Color.parseColor("#2F6BFF"));
            b.setBackground(g);
            b.setOnClickListener(v -> {
                if (idx == 0) return;                       // 当前页
                if (idx == 1) startActivity(new android.content.Intent(this, StatsActivity.class));
                else startActivity(new android.content.Intent(this, ConfigActivity.class));
            });
            nav.addView(b);
        }
        page.addView(nav);
    }

    private void load() {
        tip.setText("取 http://127.0.0.1:8791/ …");
        new Thread(() -> {
            final boolean up = probe();
            h.post(() -> {
                if (up) {
                    tip.setText("已连接本地服务 (127.0.0.1:8791)");
                    web.loadUrl("http://127.0.0.1:8791/");
                } else {
                    tip.setText("本地服务没响应 — 点「启动服务」跑起来再看 "
                            + "(或去主页确认 Shizuku/悬浮窗已授权)");
                    web.loadData("<html><body style='font:14px sans-serif;color:#6b7280;"
                            + "padding:20px'>服务未运行</body></html>", "text/html", "UTF-8");
                }
            });
        }, "graph-probe").start();
    }

    /** 探一下 8791 是否可达 (App 进程自己起的服务, 这里就是同进程回环请求) */
    private static boolean probe() {
        java.net.Socket s = null;
        try {
            s = new java.net.Socket();
            s.connect(new java.net.InetSocketAddress("127.0.0.1", 8791), 900);
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            if (s != null) try { s.close(); } catch (Throwable ignored) { }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (web != null) {
            web.stopLoading();
            web.destroy();
            web = null;
        }
    }
}
