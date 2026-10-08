package com.zhiyaunhe.sgmbot;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 战绩页 — 三个页签: 热力图 / 明细 / 说明。
 *
 * 用户原话: "加一个页签看每日战绩, 模仿 github 那种热力图"。所以页签是**真的**
 * 页签 (点着切), 不是三段滚动内容; 热力图是主视图 (默认打开)。
 *
 * 数据源 = store.json 的 history 归档 (见 Store)。这里**只读不写** —— 数据由引擎
 * 每场落一次; 本页打开时重新 load, 所以跑着的时候切过来看到的是最新的。
 *
 * ⚠️ 为什么把"说明"也做成页签而不是塞在页脚: 热力图的档位/口径不写清楚, 用户会
 *    以为"颜色深 = 打得好", 实际深 = 打得多。胜率要另看, 这点必须写在显眼处。
 */
public class StatsActivity extends Activity {

    private TextView tabHot, tabList, tabNote;
    private ScrollView body;
    private int tab = 0;
    private Store store;

    private int weeks = 26;
    private int[] levels = {1, 3, 6, 11};

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        loadCfg();
        store = Store.load();

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Ui.BG);

        /* ---- 页签条 ---- */
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setBackgroundColor(0xFFFFFFFF);
        tabs.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), 0);
        tabHot = tabBtn("热力图", 0);
        tabList = tabBtn("明细", 1);
        tabNote = tabBtn("口径说明", 2);
        tabs.addView(tabHot, w(1f));
        tabs.addView(tabList, w(1f));
        tabs.addView(tabNote, w(1f));
        page.addView(tabs);

        body = new ScrollView(this);
        body.setFillViewport(true);
        page.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(page);
        render();
    }

    /* ---------------- 渲染 ---------------- */

    private void render() {
        paintTabs();
        body.removeAllViews();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 40));
        body.addView(root);

        if (tab == 0) hotmap(root);
        else if (tab == 1) list(root);
        else notes(root);
    }

    private void paintTabs() {
        styleTab(tabHot, tab == 0);
        styleTab(tabList, tab == 1);
        styleTab(tabNote, tab == 2);
    }

    /* ---------------- 页签 0: 热力图 ---------------- */

    private void hotmap(LinearLayout root) {
        root.addView(Ui.text(this, "每日战绩热力图", Ui.TXT, 19f));

        root.addView(Ui.hint(this, "数据源: store.json 的 history 归档 (每场落一次盘)\n"
                + "颜色越深 = 那天打得**越多**; 深浅和胜率无关, 胜率看下面。"));

        /* 总计卡 */
        LinearLayout sum = Ui.card(this);
        int tr = store.totalRounds(), tw = store.totalWins();
        int rate = tr > 0 ? Math.round(tw * 100f / tr) : 0;
        int[] d7 = store.lastDays(7);
        sum.addView(Ui.text(this, "累计 " + tr + " 场 · " + tw + " 胜 · 胜率 " + rate + "%",
                Ui.TXT, 15f));
        sum.addView(Ui.text(this, "活跃 " + store.activeDays() + " 天 · 最长连续 "
                + store.bestStreak() + " 天 · 近 7 天 " + d7[0] + " 场", Ui.SUB, 12.5f));
        root.addView(sum);
        root.addView(space(10));

        /* 热力图本体 */
        HeatmapView hm = new HeatmapView(this);
        hm.setData(store.history(), weeks, levels);
        LinearLayout hmCard = Ui.card(this);
        hmCard.setPadding(Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 6));
        hmCard.addView(hm, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(hmCard);

        /* 选中那天的回显 */
        final TextView sel = Ui.text(this, "点任意格子看当天明细", Ui.SUB, 12.5f);
        sel.setPadding(0, Ui.dp(this, 8), 0, 0);
        root.addView(sel);
        hm.setOnDayClick((date, v) -> {
            if (v == null || v[0] == 0) {
                sel.setTextColor(Ui.SUB);
                sel.setText("‹ " + date + " › 没打过 ✗");
            } else {
                int r = Math.round(v[1] * 100f / v[0]);
                sel.setTextColor(Ui.TXT);
                sel.setText("‹ " + date + " ›  " + v[0] + " 场 · " + v[1] + " 胜 " + v[2] + " 负 · 胜率 " + r + "%");
            }
        });

        root.addView(space(6));

        /* 范围切换 */
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int[] opts = {13, 26, 52};
        for (int w : opts) {
            TextView b = chip(w + " 周", w == weeks);
            b.setOnClickListener(v -> {
                if (weeks == w) { Ui.toast(this, "已经是 " + w + " 周了"); return; }
                weeks = w;
                SgmLog.i("stats", "热力图范围切到 " + w + " 周");
                render();
            });
            row.addView(b, w(0f));
        }
        root.addView(row);

        root.addView(space(10));
        root.addView(Ui.hint(this, "当前展示 " + weeks + " 周。范围可在 config.stats.weeks 改默认值。"));
    }

    /* ---------------- 页签 1: 明细 ---------------- */

    private void list(LinearLayout root) {
        root.addView(Ui.text(this, "每日明细", Ui.TXT, 19f));
        LinkedHashMap<String, int[]> h = store.history();
        root.addView(Ui.hint(this, "共 " + h.size() + " 天有记录 (含 0 场的天)。按日期倒序。"));

        /* 倒序: 最近的在上 */
        java.util.List<String> ks = new java.util.ArrayList<>(h.keySet());
        java.util.Collections.reverse(ks);

        LinearLayout lastCard = null;
        for (String k : ks) {
            int[] v = h.get(k);
            LinearLayout card = Ui.card(this);
            LinearLayout head = new LinearLayout(this);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);

            TextView d = Ui.text(this, k + weekday(k), Ui.TXT, 14f);
            head.addView(d, w(1f));

            TextView r = Ui.text(this, v[0] + " 场", v[0] == 0 ? Ui.SUB : Ui.ACC, 14f);
            head.addView(r);
            card.addView(head);

            if (v[0] > 0) {
                int rate = Math.round(v[1] * 100f / v[0]);
                TextView sub = Ui.text(this, "胜 " + v[1] + " · 负 " + v[2] + " · 胜率 " + rate + "%"
                        + bar(v[1], v[2]), Ui.SUB, 12f);
                sub.setPadding(0, Ui.dp(this, 4), 0, 0);
                card.addView(sub);
            }
            root.addView(card);
            lastCard = card;
        }
        if (lastCard == null) root.addView(Ui.hint(this, "(还没有任何记录 — 跑一场就有了)"));

        root.addView(space(10));
        LinearLayout tot = Ui.card(this);
        int tr = store.totalRounds(), tw = store.totalWins();
        tot.addView(Ui.text(this, "合计 " + tr + " 场 / " + tw + " 胜 / "
                + (tr - tw) + " 负", Ui.TXT, 14f));
        root.addView(tot);
    }

    /* ---------------- 页签 2: 说明 ---------------- */

    private void notes(LinearLayout root) {
        root.addView(Ui.text(this, "这张图怎么读", Ui.TXT, 19f));

        root.addView(Ui.section(this, "颜色 = 当天场数, 不是胜率"));
        root.addView(Ui.hint(this,
                "档位分界: " + levels[0] + " 场以下最浅, 依次 "
                + levels[0] + "~" + (levels[1] - 1) + " / "
                + levels[1] + "~" + (levels[2] - 1) + " / "
                + levels[2] + "~" + (levels[3] - 1) + " / >= " + levels[3] + " 最深。\n"
                + "档位在 config.stats.levels 里改 (数组四个数, 从小到大)。\n"
                + "⚠️ 颜色深只说明「打得多」。想看好坏要看每天明细里的胜率。"));

        root.addView(Ui.section(this, "格子怎么排"));
        root.addView(Ui.hint(this,
                "每列是一周, 每行是星期一到星期日; 最右列是本周。\n"
                + "顶部灰字是月份 (跨月处标一次); 左下角「一二三四五六日」是行标签。\n"
                + "空心格 = 那天还没到 (未来), 灰色实心格 = 那天没打。"));

        root.addView(Ui.section(this, "数据从哪来 / 会不会丢"));
        root.addView(Ui.hint(this,
                "来源: " + Paths.store() + " 的 history 段。\n"
                + "引擎**每打完一场就落一次盘** (见 SettleLoop.countRound), 所以:\n"
                + "  崩溃 / 重启 / 后台被杀 都不会丢已记的场次。\n"
                + "  但**手动删 store.json** 会清空全部历史 —— 想在电脑上改的话先备份。\n"
                + "保留: store.keep_days (默认 400 天, 约 13 个月), 超期自动丢最旧的。"));

        root.addView(Ui.section(this, "计场时机 (和胜负判定同源)"));
        root.addView(Ui.hint(this,
                "一场 = 点出 REMATCH / 败局先手那一下, 一局恰计一次。\n"
                + "屏幕上的 VICTORY / DEFEAT 大字**不**计场 —— 结算两页都会有同一张横幅,\n"
                + "按大字计会虚高 (实测 20 倍), 这是刻意避开的坑。"));

        root.addView(Ui.section(this, "和 autojs 版一致吗"));
        root.addView(Ui.hint(this,
                "口径一致: 日历天归零 + 每场落盘, 与 phone/autojs 的 pf_store.js 同源。\n"
                + "新增的只是 history 归档 (autojs 版只有今日计数), 用于画热力图。"));

        root.addView(space(10));
        LinearLayout st = Ui.card(this);
        st.addView(Ui.text(this, "外部根: " + Paths.root(), Ui.SUB, 12f));
        st.addView(Ui.text(this, "store.json " + (new java.io.File(Paths.store()).exists()
                ? "存在 (" + new java.io.File(Paths.store()).length() + " B)" : "还没生成"),
                Ui.SUB, 12f));
        root.addView(st);
    }

    /* ---------------- 控件 ---------------- */

    private TextView tabBtn(String name, int idx) {
        TextView t = new TextView(this);
        t.setText(name);
        t.setTextSize(14.5f);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, Ui.dp(this, 11), 0, Ui.dp(this, 11));
        t.setOnClickListener(v -> {
            if (tab == idx) return;
            tab = idx;
            SgmLog.i("stats", "页签 → " + name);
            render();
        });
        return t;
    }

    private void styleTab(TextView t, boolean on) {
        t.setTextColor(on ? 0xFFFFFFFF : Ui.SUB);
        GradientDrawable g = new GradientDrawable();
        g.setColor(on ? Ui.ACC : 0x00000000);
        g.setCornerRadius(Ui.dp(this, 8));
        t.setBackground(g);
    }

    /** 范围选择胶囊 (选中的实心) */
    private TextView chip(String s, boolean on) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(13f);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(on ? 0xFFFFFFFF : Ui.ACC);
        t.setPadding(0, Ui.dp(this, 9), 0, Ui.dp(this, 9));
        GradientDrawable g = new GradientDrawable();
        g.setColor(on ? Ui.ACC : 0x00000000);
        g.setCornerRadius(Ui.dp(this, 8));
        g.setStroke(Ui.dp(this, 1), Ui.ACC);
        t.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        int m = Ui.dp(this, 4);
        lp.setMargins(m, 0, m, 0);
        t.setLayoutParams(lp);
        return t;
    }

    private View space(int d) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, Ui.dp(this, d)));
        return v;
    }

    private static LinearLayout.LayoutParams w(float weight) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
    }

    /** 简单的胜负条 (文字版, 免得再做一套绘制) */
    private static String bar(int win, int lose) {
        int total = win + lose;
        if (total <= 0) return "";
        int n = 10;
        int w = Math.round(win * n / (float) total);
        StringBuilder sb = new StringBuilder("  ");
        for (int i = 0; i < n; i++) sb.append(i < w ? '█' : '░');
        return sb.toString();
    }

    private static String weekday(String ymd) {
        try {
            String[] p = ymd.split("-");
            Calendar c = Calendar.getInstance();
            c.clear();
            c.set(Integer.parseInt(p[0]), Integer.parseInt(p[1]) - 1, Integer.parseInt(p[2]));
            String[] names = {"日", "一", "二", "三", "四", "五", "六"};
            return " 周" + names[c.get(Calendar.DAY_OF_WEEK) - 1];
        } catch (Exception e) {
            return "";
        }
    }

    /** config.stats: {weeks, levels} */
    private void loadCfg() {
        try {
            Config c = BotService.cfg();
            if (c == null) c = new Config(this);
            JSONObject s = c.read().optJSONObject("stats");
            if (s == null) return;
            int w = s.optInt("weeks", 26);
            if (w >= 8 && w <= 60) weeks = w;
            org.json.JSONArray a = s.optJSONArray("levels");
            if (a != null && a.length() == 4) {
                int[] lv = new int[4];
                for (int i = 0; i < 4; i++) lv[i] = a.optInt(i, levels[i]);
                levels = lv;
            }
        } catch (Throwable t) {
            SgmLog.i("stats", "读 config.stats 失败 (用默认): " + t);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (store != null) {                 // 回来时重读 (跑着的时候切过来看最新)
            store = Store.load();
            render();
        }
    }
}
