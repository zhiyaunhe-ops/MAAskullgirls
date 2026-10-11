package com.zhiyaunhe.sgmbot;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * 判定路径图 — **App 内原生绘制**, 不跳网页。
 *
 * <h3>改了什么 (2026-10-11 用户原话: "别再跳转什么网页了 真的很丑")</h3>
 * 旧实现是一个内嵌 WebView 打开 {@code http://127.0.0.1:8791/} —— 那个页面本来是
 * 给 PC 用 {@code adb forward} 看的 (见 DebugHttp.GRAPH_HTML), 搬到手机上就露馅:
 * 网页 px 字号在手机上小到看不清、手感与 App 其它页不一致、页面自己还挂着
 * /status /log /config 一排链接 (一点就跳走)、还得等 HTTP 往返。
 *
 * <p>现在: 直接读同进程的 {@link Graph#toJson()} 交给 {@link GraphView} 画。
 * <b>PC 侧的网页版保留</b> (浏览器里看挺方便, 且 GraphPageProbe 在守着它),
 * 两条路共用同一个数据源 (config.graph + Trace), 不会图实不符。
 *
 * <p>三个页签: 路径图 (主视图) / 判定流水 / 怎么读。
 */
public class GraphActivity extends Activity {

    private static final int BG = 0xFFF6F7F9, CARD = 0xFFFFFFFF, LINE = 0xFFE3E6EB;
    private static final int TXT = 0xFF1F2329, SUB = 0xFF6B7280, ACC = 0xFF2F6BFF;

    private TextView tabGraph, tabTrace, tabHelp;
    private LinearLayout paneGraph, paneTrace, paneHelp;
    private GraphView gv;
    private TextView top, detail, zoomTxt;
    private LinearLayout rows;
    private CheckBox auto;
    private int tab = 0;

    private final Handler h = new Handler(Looper.getMainLooper());

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (isFinishing()) return;
            if (auto == null || auto.isChecked()) refresh();
            h.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Graph.ensureLoaded(BotService.cfgOr(this));      // 引擎没跑也要有图 (幂等)

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(BG);

        /* ---- 顶部信息条 ---- */
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setBackgroundColor(CARD);
        head.setPadding(dp(16), dp(12), dp(16), dp(10));
        TextView t = new TextView(this);
        t.setText("判定路径图");
        t.setTextSize(18f);
        t.setTextColor(TXT);
        head.addView(t);
        top = new TextView(this);
        top.setTextSize(12f);
        top.setTextColor(SUB);
        top.setPadding(0, dp(4), 0, 0);
        top.setText("装载中…");
        head.addView(top);
        page.addView(head);
        View sep = new View(this);
        sep.setBackgroundColor(LINE);
        page.addView(sep, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        /* ---- 页签 ---- */
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setBackgroundColor(CARD);
        tabs.setPadding(dp(10), dp(8), dp(10), dp(8));
        tabGraph = tabBtn("路径图", 0);
        tabTrace = tabBtn("判定流水", 1);
        tabHelp = tabBtn("怎么读", 2);
        tabs.addView(tabGraph, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tabs.addView(tabTrace, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tabs.addView(tabHelp, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        page.addView(tabs);

        /* ---- 页签 0: 路径图 ---- */
        paneGraph = new LinearLayout(this);
        paneGraph.setOrientation(LinearLayout.VERTICAL);
        gv = new GraphView(this);
        gv.setOnPick((node, recent) -> {
            detail.setTextColor(TXT);
            detail.setText(GraphLayout.detail(node, recent));
        });
        gv.setOnViewChange(() -> zoomTxt.setText(zoomPctText()));
        paneGraph.addView(gv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(CARD);
        bar.setPadding(dp(10), dp(6), dp(10), dp(6));
        bar.addView(smallBtn("全图", v -> gv.fitAll()));
        bar.addView(smallBtn("1:1", v -> gv.zoom1()));
        bar.addView(smallBtn("当前", v -> gv.focusCursor()));
        bar.addView(smallBtn("−", v -> gv.zoomBy(1 / 1.35f)));
        bar.addView(smallBtn("＋", v -> gv.zoomBy(1.35f)));
        zoomTxt = new TextView(this);
        zoomTxt.setTextSize(12f);
        zoomTxt.setTextColor(SUB);
        zoomTxt.setPadding(dp(6), 0, dp(6), 0);
        bar.addView(zoomTxt);
        auto = new CheckBox(this);
        auto.setText("自动刷新");
        auto.setTextSize(12f);
        auto.setChecked(true);
        auto.setTextColor(SUB);
        bar.addView(auto);
        paneGraph.addView(bar);

        detail = new TextView(this);
        detail.setTextSize(12.5f);
        detail.setTextColor(SUB);
        detail.setBackgroundColor(CARD);
        detail.setPadding(dp(14), dp(10), dp(14), dp(12));
        detail.setText("点任意节点 → 这里显示它的条件 / 阈值来源 / 最近实测分数。\n"
                + "节点第 2 行 = 最近实测峰值 (NCC, 绿=命中 红=未过阈); 第 3 行 = 当前阈值。\n"
                + "拖动移动, 双指缩放。「全图」看形状 (字会很小), 「1:1」看内容, 「当前」跳到正在走的那步。");
        paneGraph.addView(detail);
        page.addView(paneGraph, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        /* ---- 页签 1: 判定流水 ---- */
        ScrollView sv = new ScrollView(this);
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(dp(12), dp(10), dp(12), dp(40));
        sv.addView(rows, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        paneTrace = new LinearLayout(this);
        paneTrace.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        page.addView(paneTrace, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        /* ---- 页签 2: 怎么读 ---- */
        ScrollView sv2 = new ScrollView(this);
        LinearLayout help = new LinearLayout(this);
        help.setOrientation(LinearLayout.VERTICAL);
        help.setPadding(dp(16), dp(12), dp(16), dp(40));
        help.addView(h("这张图是什么"));
        help.addView(p("整个程序的判定树 —— 循环每一步在判什么、阈值多少、不满足往哪走。"
                + "节点/边/坐标全部来自 config.json 的 graph 段, 代码里没有硬编码节点, "
                + "所以改配置就等于改图 (改完点「重载配置」)。"));
        help.addView(h("颜色"));
        help.addView(p("节点底色 = 节点类别 (扫描 / 匹配 / 点击 / 逃生链 / 守护…); "
                + "橙色呼吸框 = 此刻正走到这一步 (引擎没跑就没有)。\n"
                + "节点第 2 行是**最近一次实测的 NCC 峰值**: 绿色 = 过了阈值 (命中), "
                + "红色 = 没过。差 0.02 卡在哪, 看这一行最快。"));
        help.addView(h("阈值为什么不写死"));
        help.addView(p("节点上印的是**当前配置里的实际值** (load 时按 cfg 路径回填), "
                + "所以不会出现「图上写 0.72、实机按 0.75 判」这种图实不符。"));
        help.addView(h("逃生链是展开出来的"));
        help.addView(p("escape.items 里每一项都会展开成一个子节点, 首尾相接。"
                + "加一条逃生弹窗只改 escape.items, 图自动跟着变。"));
        help.addView(h("PC 上也能看"));
        help.addView(p("App 进程内有个只绑 127.0.0.1:8791 的调试服务, 浏览器版页面在:\n"
                + "  adb forward tcp:8791 tcp:8791\n"
                + "  http://127.0.0.1:8791/  (判定图, 网页版)\n"
                + "  /status /log /config /screencap /update\n"
                + "网页版和本页共用同一份数据, 只是渲染不同。"));
        sv2.addView(help, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        paneHelp = new LinearLayout(this);
        paneHelp.addView(sv2, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        page.addView(paneHelp, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        /* ---- 底部导航 (与战绩页/配置页互跳) ---- */
        page.addView(navBar());

        setContentView(page);
        paint();
        refresh();
        h.postDelayed(tick, 1000);
    }

    private String zoomPctText() {
        return String.format(Locale.US, "%.0f%%", gv.zoomPct() * 100);
    }

    /* ---------------- 刷新 ---------------- */

    private void refresh() {
        JSONObject g = Graph.toJson();
        JSONArray nodes = g.optJSONArray("nodes");
        JSONArray edges = g.optJSONArray("edges");
        String cursor = g.optString("cursor", "");
        int nn = nodes == null ? 0 : nodes.length();
        int ne = edges == null ? 0 : edges.length();
        boolean running = g.optBoolean("running", false);

        top.setText("节点 " + nn + " · 边 " + ne
                + " · " + (running ? "引擎在跑" : "引擎没跑 (静态全图)")
                + " · 当前 " + (cursor.length() == 0 ? "(未跑)" : cursor)
                + " · 更新于 " + hhmm(g.optLong("ts", System.currentTimeMillis())));

        gv.setData(nodes, edges, g.optJSONObject("byNode"), cursor, g.optJSONArray("lanes"));
        zoomTxt.setText(zoomPctText());
        if (tab == 1) renderTrace(g.optJSONArray("trace"));
    }

    private void renderTrace(JSONArray tr) {
        int n = tr == null ? 0 : Math.min(tr.length(), 40);
        while (rows.getChildCount() < n) rows.addView(traceRow());
        while (rows.getChildCount() > n) rows.removeViewAt(rows.getChildCount() - 1);
        if (n == 0) {
            if (rows.getChildCount() == 0) {
                TextView t = p("还没有判定流水 — 引擎跑起来才会有。\n"
                        + "每条 = 一次模板判定/点击/逃生: 节点 / 模板 / NCC 峰值 / 动作。");
                rows.addView(t);
            }
            return;
        }
        /* 最新的在最上面 (与网页版流水表相反, 手机上往下翻找旧的更别扭) */
        for (int i = 0; i < n; i++) {
            JSONObject o = tr.optJSONObject(tr.length() - 1 - i);
            TextView tv = (TextView) rows.getChildAt(i);
            if (o == null) { tv.setText(""); continue; }
            double s = o.optDouble("score", -3);
            boolean hit = o.optBoolean("hit", false);
            String line = hhmm(o.optLong("t", 0)) + "  " + pad(o.optString("node", ""), 10)
                    + "  " + pad(o.optString("tpl", ""), 14)
                    + "  " + (s <= -2 ? "  -  " : String.format(Locale.US, "%.3f", s))
                    + (hit ? " ✓ " : " ✗ ") + o.optString("act", "");
            tv.setText(line);
            tv.setTextColor(hit ? 0xFF17964B : (s <= -2 ? SUB : 0xFFD93636));
        }
    }

    private TextView traceRow() {
        TextView t = new TextView(this);
        t.setTextSize(11.5f);
        t.setTypeface(Typeface.MONOSPACE);
        t.setPadding(0, dp(5), 0, dp(5));
        return t;
    }

    private static String pad(String s, int n) {
        if (s == null) s = "";
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < n) sb.append(' ');
        return sb.length() > n ? sb.substring(0, n) : sb.toString();
    }

    private static String hhmm(long t) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(t);
        return String.format(Locale.US, "%02d:%02d:%02d",
                c.get(java.util.Calendar.HOUR_OF_DAY),
                c.get(java.util.Calendar.MINUTE),
                c.get(java.util.Calendar.SECOND));
    }

    /* ---------------- 页签 ---------------- */

    private void paint() {
        paneGraph.setVisibility(tab == 0 ? View.VISIBLE : View.GONE);
        paneTrace.setVisibility(tab == 1 ? View.VISIBLE : View.GONE);
        paneHelp.setVisibility(tab == 2 ? View.VISIBLE : View.GONE);
        style(tabGraph, tab == 0);
        style(tabTrace, tab == 1);
        style(tabHelp, tab == 2);
        if (tab == 1) renderTrace(Graph.toJson().optJSONArray("trace"));
    }

    private TextView tabBtn(String name, final int idx) {
        TextView t = new TextView(this);
        t.setText(name);
        t.setTextSize(14f);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(10), 0, dp(10));
        t.setOnClickListener(v -> {
            if (tab == idx) return;
            tab = idx;
            paint();
        });
        return t;
    }

    private void style(TextView t, boolean on) {
        t.setTextColor(on ? 0xFFFFFFFF : SUB);
        GradientDrawable g = new GradientDrawable();
        g.setColor(on ? ACC : Color.TRANSPARENT);
        g.setCornerRadius(dp(8));
        t.setBackground(g);
    }

    private TextView smallBtn(String name, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(name);
        t.setTextSize(13f);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(ACC);
        t.setPadding(dp(10), dp(7), dp(10), dp(7));
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.TRANSPARENT);
        g.setCornerRadius(dp(8));
        g.setStroke(dp(1), ACC);
        t.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(6);
        t.setLayoutParams(lp);
        t.setOnClickListener(l);
        return t;
    }

    private LinearLayout navBar() {
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setBackgroundColor(CARD);
        nav.setPadding(dp(12), dp(6), dp(12), dp(12));
        String[] names = {"判定图", "战绩热力图", "编辑配置"};
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            TextView b = new TextView(this);
            b.setText(names[i]);
            b.setTextSize(13f);
            b.setGravity(Gravity.CENTER);
            b.setPadding(0, dp(10), 0, dp(10));
            b.setTextColor(idx == 0 ? 0xFFFFFFFF : ACC);
            GradientDrawable g = new GradientDrawable();
            g.setColor(idx == 0 ? ACC : Color.TRANSPARENT);
            g.setCornerRadius(dp(20));
            g.setStroke(dp(1), ACC);
            b.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(dp(4), 0, dp(4), 0);
            b.setLayoutParams(lp);
            b.setOnClickListener(v -> {
                if (idx == 0) return;
                startActivity(new android.content.Intent(this,
                        idx == 1 ? StatsActivity.class : ConfigActivity.class));
            });
            nav.addView(b);
        }
        return nav;
    }

    /* ---------------- 小工具 ---------------- */

    private TextView h(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(14.5f);
        t.setTextColor(ACC);
        t.setPadding(0, dp(18), 0, dp(6));
        return t;
    }

    private TextView p(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(12.5f);
        t.setTextColor(SUB);
        t.setLineSpacing(dp(3), 1f);
        return t;
    }

    private int dp(int v) { return Ui.dp(this, v); }

    @Override
    protected void onResume() {
        super.onResume();
        Graph.ensureLoaded(BotService.cfgOr(this));
        refresh();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        h.removeCallbacks(tick);
    }
}
