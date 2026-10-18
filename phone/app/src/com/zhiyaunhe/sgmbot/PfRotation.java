package com.zhiyaunhe.sgmbot;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 场次轮换公式 → 「**今天**开放哪些场次」。
 *
 * <h3>为什么要有它 (2026-10-18 用户)</h3>
 * 用户: 「事实上只有 5 张, 而且我做了一个网站是显示目前的场次的」。在此之前 App 的「选场次」
 * 把表里 **40 条全列出来** —— 那些是历届场次的目录, 不是"现在能打什么"。站点
 * ({@code https://zhiyaunhe-ops.github.io/sgm-pf/}) 早就按公式算当天开放列表, App 缺了这一步。
 *
 * <h3>口径来源</h3>
 * 逐字移植站点 {@code app.js} 的 {@code buildDays()} / {@code currentGameDay()}:
 * <pre>
 * 游戏日     太平洋时间 - 10 小时 (10:00 PT 换日)
 * 周序号 w   ⌊(本周一 − anchor)/7⌋      anchor = 2024-06-03
 * 角色对     pair_order[w mod 9]        wd ≤ 2 → 前半 (周一–周三), wd ≤ 5 → 后半 (周四–周六)
 * 元素       element_order[w mod 5]     wd ≥ 5 (周六–周日)
 * 金币场     wd == 2 || wd == 3         (场名轮换顺序未确认 ⇒ 不给名)
 * 星助手场   wd == 4 || wd == 5         SEEING STARS
 * 招式场     wd ≥ 5                    SHOW ME YOUR MOVES (官方"隔周", 相位未核实 ⇒ **不列为可选**)
 * 月场:      表里 monthly && from ≤ 今日 &lt; until
 * </pre>
 * ⚠️⚠️ 招式场**只标窗口、不进可选列表**: 2026-10-18 用户口径 —— 官方只写「隔周、周六–周日」,
 * 相位未确认, 排进去等于断言"当天开"。窗口内但未核实的项用 {@link Open#unverified} 标出,
 * {@link Open#pickable()} 会把它挡在选项外, 只在日志里说明。
 * ⚠️ wd 以**周一 = 0** 计 (与站点一致), 不是 Calendar 的周日 = 1。
 *
 * <h3>⚠️ 公式 != hub 实况</h3>
 * 公式给的是"按官方排期应该开放什么"。hub 上还会出现**公式算不出来的卡**:
 * ① 表里缺条目 (2026-10 的月场角色场 FALL OF THE DARK 就没进表);
 * ② 窗口刚结束时卡还在 (SEEING STARS 周日晚还剩 3 小时)。
 * 所以这里的结果只用来**收窄候选**, 不能当成"hub 上有几张"的答案 —— 那个数只能靠
 * {@link PfScan} (且它对重复/漏计很敏感, 见其类注释)。
 */
final class PfRotation {

    /** 一天开放的一项。 */
    static final class Open {
        final String type;          // character / element / gold / assist / move / monthly_*
        final String name;          // 表里的显示名; null = 这一项没有名字 (金币场/表缺条目)
        final String slot;          // 窗口说明: 周一–周三 / 周六–周日 / 月场
        final boolean unverified;   // 官方窗口确定但相位未核实 (招式场)

        Open(String type, String name, String slot, boolean unverified) {
            this.type = type;
            this.name = name;
            this.slot = slot;
            this.unverified = unverified;
        }

        /** 能拿去选的只有「有名字 + 不是未核实项」。 */
        boolean pickable() { return name != null && !name.isEmpty() && !unverified; }
    }

    private PfRotation() { }

    /**
     * 当前**游戏日** (YYYY-MM-DD): 太平洋时间 − 10 小时 ⇒ 每天 10:00 PT 换日。
     *
     * <p>⚠️ 不能直接用手机本地日期: 国内比 PT 快 15/16 小时, 用本地日期会在**每晚 0 点**
     * 提前跳天, 而游戏要到次日上午 10:00 PT 才换 —— 那一段会"算出明天/昨天的场次"。</p>
     */
    static String gameDay() {
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("America/Los_Angeles"));
        cal.add(Calendar.HOUR_OF_DAY, -10);
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.getTime());
    }

    /** 用给定时区/时刻算游戏日 (测试用)。 */
    static String gameDayAt(long ms, TimeZone tz) {
        Calendar cal = Calendar.getInstance(tz);
        cal.setTimeInMillis(ms);
        cal.add(Calendar.HOUR_OF_DAY, -10);
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.getTime());
    }

    /** 今天开放的场次 (顺序: 角色 → 元素 → 金币 → 星助手 → 招式 → 月场)。 */
    static List<Open> today(JSONObject root) {
        String day = gameDay();
        return on(root, day);
    }

    /**
     * 指定游戏日开放的场次。
     *
     * @param root 线上/内置表根 ({@code {rotation:{...}, arenas:{...}}})
     */
    static List<Open> on(JSONObject root, String day) {
        List<Open> out = new ArrayList<>();
        if (root == null || day == null) return out;
        JSONObject rot = root.optJSONObject("rotation");
        JSONObject arenas = root.optJSONObject("arenas");
        if (rot == null || arenas == null) return out;
        JSONArray pairs = rot.optJSONArray("pair_order");
        JSONArray elems = rot.optJSONArray("element_order");
        if (pairs == null || pairs.length() == 0 || elems == null || elems.length() == 0) return out;

        long anchor = epochDay(rot.optString("anchor", "2024-06-03"));
        long today = epochDay(day);
        if (anchor < 0 || today < 0) return out;

        int wd = weekday0(day);                       // 0 = 周一
        long monday = today - wd;
        long weeks = Math.round((monday - anchor) / 7.0);
        JSONArray pair = pairs.optJSONArray((int) mod(weeks, pairs.length()));
        String elem = elems.optString((int) mod(weeks, elems.length()), null);
        String charA = pair == null ? null : pair.optString(0, null);
        String charB = pair == null ? null : pair.optString(1, null);

        if (wd <= 2) out.add(new Open("character", findChar(arenas, charA), "周一–周三", false));
        else if (wd <= 5) out.add(new Open("character", findChar(arenas, charB), "周四–周六", false));
        if (wd >= 5) out.add(new Open("element", findElem(arenas, elem), "周六–周日", false));
        if (wd == 2 || wd == 3) out.add(new Open("gold", null, "周三起 1–2 天", true));
        if (wd == 4 || wd == 5) out.add(new Open("assist", findByName(arenas, "SEEING STARS"), "周五–周六", false));
        /* 官方写"周六–周日、隔周", 窗口确定、相位不定 ⇒ 标 unverified, 别让调用方当成确定。 */
        if (wd >= 5) out.add(new Open("move", findByName(arenas, "SHOW ME YOUR MOVES"), "周六–周日·隔周", true));

        // 月场: 只有两端都有档期的才算 (只写名字的不进列表 —— 假阳性比缺失更糟)
        JSONArray keys = arenas.names();
        for (int i = 0; keys != null && i < keys.length(); i++) {
            JSONObject e = arenas.optJSONObject(keys.optString(i));
            if (e == null || !e.optBoolean("monthly")) continue;
            String f = e.optString("from", ""), u = e.optString("until", "");
            if (f.isEmpty() || u.isEmpty()) continue;
            if (day.compareTo(f) >= 0 && day.compareTo(u) < 0) {
                out.add(new Open(e.optString("tag", "monthly"), e.optString("name", ""), "月场 " + f + "→" + u, false));
            }
        }
        return out;
    }

    /* ---------------- 内部工具 ---------------- */

    /** 周几, **周一 = 0** (与站点 {@code (getUTCDay()+6)%7} 同口径)。 */
    static int weekday0(String day) {
        long ms = parse(day);
        if (ms < 0) return 0;
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.setTimeInMillis(ms);
        return (c.get(Calendar.DAY_OF_WEEK) + 5) % 7;   // DAY_OF_WEEK: 周日=1 ⇒ (1+5)%7=6 ✓
    }

    private static long mod(long a, int n) { return ((a % n) + n) % n; }

    private static long parse(String day) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date d = f.parse(day);
            return d == null ? -1 : d.getTime();
        } catch (Throwable t) {
            return -1;
        }
    }

    private static long epochDay(String day) {
        long ms = parse(day);
        return ms < 0 ? -1 : ms / 86400000L;
    }

    private static String findChar(JSONObject arenas, String ch) {
        if (ch == null) return null;
        JSONArray keys = arenas.names();
        for (int i = 0; keys != null && i < keys.length(); i++) {
            JSONObject e = arenas.optJSONObject(keys.optString(i));
            if (e == null) continue;
            if ("character".equals(e.optString("tag")) && ch.equalsIgnoreCase(e.optString("char", ""))) {
                return e.optString("name", null);
            }
        }
        return null;
    }

    private static String findElem(JSONObject arenas, String el) {
        if (el == null) return null;
        JSONArray keys = arenas.names();
        for (int i = 0; keys != null && i < keys.length(); i++) {
            JSONObject e = arenas.optJSONObject(keys.optString(i));
            if (e == null) continue;
            if ("element".equals(e.optString("tag")) && el.equalsIgnoreCase(e.optString("element", ""))) {
                return e.optString("name", null);
            }
        }
        return null;
    }

    private static String findByName(JSONObject arenas, String name) {
        JSONArray keys = arenas.names();
        for (int i = 0; keys != null && i < keys.length(); i++) {
            JSONObject e = arenas.optJSONObject(keys.optString(i));
            if (e != null && name.equalsIgnoreCase(e.optString("name", ""))) return e.optString("name");
        }
        return null;
    }
}
