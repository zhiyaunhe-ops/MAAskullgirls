package com.zhiyaunhe.sgmbot;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * 场次记账 — 移植 phone/autojs/pf_store.js (同口径: 日历天归零 / 上限 / 读写失败静默降级)。
 *
 * 文件 <Paths.store()>:
 * {
 *   "day":"2026-10-08", "rounds":12,"wins":7,"loses":5,       ← 今日 (兼容旧版)
 *   "history": {                                              ← 按日归档 (热力图数据源)
 *       "2026-10-07":{"rounds":30,"wins":18,"loses":12},
 *       "2026-10-08":{"rounds":12,"wins":7,"loses":5}
 *   }
 * }
 *
 * ⚠️ history 是**只增不减**的账本 —— 跨天归零时只重置 today 三键, 归档要保住
 *    (否则热力图每天都只剩一个格子)。addRound 时同步累加今天那一格。
 *    归档条数由 config.store.keep_days 控制 (默认 400, ≈ 13 个月)。
 *
 * ⚠️ 写盘策略不变: 每场 save() (崩溃/重启不丢)。history 大了之后单次写 ≈ 几 KB,
 *    对每场一次来说可以忽略; 真到了万条量级再考虑改成每天落一次。
 */
public final class Store {
    public String day = "";
    public int rounds, wins, loses;

    /** 按日归档: "YYYY-MM-DD" → [rounds, wins, loses] (有序, 旧→新) */
    private final java.util.LinkedHashMap<String, int[]> history = new java.util.LinkedHashMap<>();
    /** 归档保留天数 (0 = 不限) */
    private int keepDays = 400;

    public static String todayStr() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        return fmt(c);
    }

    private static String fmt(Calendar c) {
        return String.format(Locale.US, "%04d-%02d-%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    /** 读盘; 跨日历天自动归零今日 (归档不归零), 损坏/缺失返回空记录 (不抛) */
    public static Store load() {
        Store s = new Store();
        String today = todayStr();
        try {
            File f = new File(Paths.store());
            if (f.exists() && f.length() > 0) {
                JSONObject o = readJson(f);
                s.day = o.optString("day", "");
                s.rounds = o.optInt("rounds", 0);
                s.wins = o.optInt("wins", 0);
                s.loses = o.optInt("loses", 0);
                s.keepDays = o.optInt("keep_days", s.keepDays);
                JSONObject hi = o.optJSONObject("history");
                if (hi != null) {
                    List<String> ks = new ArrayList<>();
                    Iterator<String> it = hi.keys();
                    while (it.hasNext()) ks.add(it.next());
                    Collections.sort(ks);                    // 日期是 ISO 格式 ⇒ 字典序 = 时间序
                    for (String k : ks) {
                        JSONObject r = hi.optJSONObject(k);
                        if (r == null) continue;
                        s.history.put(k, new int[]{
                                r.optInt("rounds", 0), r.optInt("wins", 0), r.optInt("loses", 0)});
                    }
                }
            }
        } catch (Exception ignored) { }
        if (!today.equals(s.day)) { s.day = today; s.rounds = 0; s.wins = 0; s.loses = 0; }
        /* 旧文件没有 history (或今天还没写过) ⇒ 用今日数补一格, 免得热力图今天空白 */
        if (!s.history.containsKey(s.day)) {
            s.history.put(s.day, new int[]{s.rounds, s.wins, s.loses});
        }
        s.keepDays = cfgKeepDays(s.keepDays);      // config.store.keep_days 优先
        s.trim();
        return s;
    }

    /** store.keep_days 从 config 读 (文件里那份是"上次写盘时的口径", 配置改了要跟着变) */
    private static int cfgKeepDays(int dflt) {
        try {
            /* 反射拿 BotService.cfg() —— 直连编译会把这一个工具类拖进整个服务依赖图
             * (助手/模板/触控…), 离线单测就没法只编 Store 了。拿不到就回落文件里的值。 */
            Class<?> bs = Class.forName("com.zhiyaunhe.sgmbot.BotService");
            Object cfg = bs.getMethod("cfg").invoke(null);
            if (cfg == null) return dflt;
            JSONObject all = (JSONObject) cfg.getClass().getMethod("read").invoke(cfg);
            JSONObject st = all == null ? null : all.optJSONObject("store");
            return st == null ? dflt : st.optInt("keep_days", dflt);
        } catch (Throwable t) {
            return dflt;      // 离线测试/冷启动没有 BotService, 用文件里的值
        }
    }

    /** 记一场; 运行跨过 0 点先归零今日再计 (归档按**当时**的日历天记) */
    public void addRound(boolean win) {
        String today = todayStr();
        if (!today.equals(day)) { day = today; rounds = 0; wins = 0; loses = 0; }
        rounds++;
        if (win) wins++; else loses++;
        int[] r = history.get(day);
        if (r == null) { r = new int[3]; history.put(day, r); }
        r[0]++; if (win) r[1]++; else r[2]++;
    }

    /** 每场落盘 (崩溃/重启不丢); 失败静默 */
    public void save() {
        try {
            File f = new File(Paths.store());
            File d = f.getParentFile();
            if (d != null && !d.exists()) d.mkdirs();
            JSONObject o = new JSONObject();
            o.put("day", day).put("rounds", rounds).put("wins", wins).put("loses", loses);
            JSONObject hi = new JSONObject();
            for (java.util.Map.Entry<String, int[]> e : history.entrySet()) {
                hi.put(e.getKey(), new JSONObject()
                        .put("rounds", e.getValue()[0])
                        .put("wins", e.getValue()[1])
                        .put("loses", e.getValue()[2]));
            }
            o.put("history", hi);
            o.put("keep_days", keepDays);
            FileOutputStream out = new FileOutputStream(f);
            out.write(o.toString().getBytes("UTF-8"));
            out.close();
        } catch (Exception ignored) { }
    }

    /* ---------------- 归档查询 (热力图/明细页用) ---------------- */

    /** 复制一份归档 (旧→新), 键 "YYYY-MM-DD", 值 [rounds, wins, loses] */
    public java.util.LinkedHashMap<String, int[]> history() {
        return new java.util.LinkedHashMap<>(history);
    }

    public int[] dayOf(String date) { return history.get(date); }

    /** 归档里有记录的天数 (不含全 0 的天) */
    public int activeDays() {
        int n = 0;
        for (int[] v : history.values()) if (v[0] > 0) n++;
        return n;
    }

    public int totalRounds() {
        int n = 0;
        for (int[] v : history.values()) n += v[0];
        return n;
    }

    public int totalWins() {
        int n = 0;
        for (int[] v : history.values()) n += v[1];
        return n;
    }

    /** 最长连天有打的连续天数 (GitHub 风格的"连续") */
    public int bestStreak() {
        int best = 0, cur = 0;
        Calendar c = null;
        for (java.util.Map.Entry<String, int[]> e : history.entrySet()) {
            Calendar d = parse(e.getKey());
            if (d == null) continue;
            if (c != null && isNextDay(c, d) && e.getValue()[0] > 0) cur++;
            else cur = e.getValue()[0] > 0 ? 1 : 0;
            if (cur > best) best = cur;
            c = d;
        }
        return best;
    }

    /** 最近 N 天的合计 (含今天; 缺的天按 0) */
    public int[] lastDays(int n) {
        int[] out = new int[3];
        Calendar c = Calendar.getInstance();
        for (int i = 0; i < n; i++) {
            int[] v = history.get(fmt(c));
            if (v != null) { out[0] += v[0]; out[1] += v[1]; out[2] += v[2]; }
            c.add(Calendar.DAY_OF_MONTH, -1);
        }
        return out;
    }

    /* ---------------- 今日口径 (引擎用, 不变) ---------------- */

    /** cap<=0 = 不限 */
    public boolean capReached(int cap) { return cap > 0 && rounds >= cap; }

    public String text(int cap) {
        return "今日" + rounds + (cap > 0 ? "/" + cap : "") + "场";
    }

    /* ---------------- 内部 ---------------- */

    /** 超保留期就丢最旧的 (keep_days<=0 不限) */
    private void trim() {
        if (keepDays <= 0) return;
        int over = history.size() - keepDays;
        if (over <= 0) return;
        Iterator<String> it = history.keySet().iterator();
        while (over-- > 0 && it.hasNext()) { it.next(); it.remove(); }
    }

    private static Calendar parse(String ymd) {
        try {
            String[] p = ymd.split("-");
            Calendar c = Calendar.getInstance();
            c.clear();
            c.set(Integer.parseInt(p[0]), Integer.parseInt(p[1]) - 1, Integer.parseInt(p[2]));
            return c;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isNextDay(Calendar a, Calendar b) {
        Calendar t = (Calendar) a.clone();
        t.add(Calendar.DAY_OF_MONTH, 1);
        return t.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && t.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    private static JSONObject readJson(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        byte[] buf = new byte[(int) f.length()];
        int n = in.read(buf);
        in.close();
        return new JSONObject(new String(buf, 0, Math.max(n, 0), "UTF-8"));
    }
}
