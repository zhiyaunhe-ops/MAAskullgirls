package com.zhiyaunhe.sgmbot;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Locale;

/**
 * 今日场次 — 移植 phone/autojs/pf_store.js (同口径: 日历天归零 / 上限 / 读写失败静默降级)。
 *
 * <Paths.store()> → {"day":"2026-10-07","rounds":12,"wins":7,"loses":5}
 * (默认 /sdcard/sgm_settle/store.json; 未授"所有文件访问"时退到 App 私有目录)
 * PC 版完整场次系统 (子场次/规则按钮组) 在 PC 侧; 手机侧只做今日计数 + 上限。
 */
public final class Store {
    public String day = "";
    public int rounds, wins, loses;

    public static String todayStr() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        return String.format(Locale.US, "%04d-%02d-%02d",
                c.get(java.util.Calendar.YEAR),
                c.get(java.util.Calendar.MONTH) + 1,
                c.get(java.util.Calendar.DAY_OF_MONTH));
    }

    /** 读盘; 跨日历天自动归零, 损坏/缺失返回空记录 (不抛) */
    public static Store load() {
        Store s = new Store();
        String today = todayStr();
        try {
            File f = new File(Paths.store());
            if (f.exists() && f.length() > 0) {
                FileInputStream in = new FileInputStream(f);
                byte[] buf = new byte[(int) f.length()];
                int n = in.read(buf);
                in.close();
                JSONObject o = new JSONObject(new String(buf, 0, Math.max(n, 0), "UTF-8"));
                s.day = o.optString("day", "");
                s.rounds = o.optInt("rounds", 0);
                s.wins = o.optInt("wins", 0);
                s.loses = o.optInt("loses", 0);
            }
        } catch (Exception ignored) { }
        if (!today.equals(s.day)) { s.day = today; s.rounds = 0; s.wins = 0; s.loses = 0; }
        return s;
    }

    /** 记一场; 运行跨过 0 点先归零再计 */
    public void addRound(boolean win) {
        String today = todayStr();
        if (!today.equals(day)) { day = today; rounds = 0; wins = 0; loses = 0; }
        rounds++;
        if (win) wins++; else loses++;
    }

    /** 每场落盘 (崩溃/重启不丢今日计数); 失败静默 */
    public void save() {
        try {
            File f = new File(Paths.store());
            File d = f.getParentFile();
            if (d != null && !d.exists()) d.mkdirs();
            JSONObject o = new JSONObject();
            o.put("day", day).put("rounds", rounds).put("wins", wins).put("loses", loses);
            FileOutputStream out = new FileOutputStream(f);
            out.write(o.toString().getBytes("UTF-8"));
            out.close();
        } catch (Exception ignored) { }
    }

    /** cap<=0 = 不限 */
    public boolean capReached(int cap) { return cap > 0 && rounds >= cap; }

    public String text(int cap) {
        return "今日" + rounds + (cap > 0 ? "/" + cap : "") + "场";
    }
}
