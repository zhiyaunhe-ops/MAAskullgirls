import com.zhiyaunhe.sgmbot.Store;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.Locale;

/**
 * Store 归档逻辑离线回归 — 不碰 android.*, 纯 JVM (JDK8 + 真 org.json) 跑。
 *
 * 为什么值得写: 热力图全靠 history 归档, 而归档有三处**极易写错又不易发现**的地方:
 *   ① 跨天归零时把 history 一起清了 → 热力图每天只剩一格 (且当天看不出问题)
 *   ② 旧 store.json (没有 history 键) 升级后 → 今天那一格是空的
 *   ③ trim 越界把**今天**裁掉 → 数据丢得静默
 * 这三条都在下面有用例, 改 Store 时先跑它。
 *
 * 用法:
 *   javac -cp <真 org.json>:phone/app/src -d out phone/app/tools/StoreProbe.java \
 *         phone/app/src/com/zhiyaunhe/sgmbot/Store.java
 *   java  -cp out:<真 org.json> StoreProbe
 * 全过 exit 0, 有错 exit 1。
 */
public class StoreProbe {
    static int fail = 0;
    static int pass = 0;

    public static void main(String[] a) throws Exception {
        String tmp = System.getProperty("store.probe.dir",
                System.getProperty("java.io.tmpdir") + "/sgmbot-store-probe");
        File dir = new File(tmp);
        dir.mkdirs();
        File store = new File(dir, "store.json");
        if (store.exists()) store.delete();

        /* Store 的路径走 Paths (安卓侧), 这里用反射把它换掉 —— 不引 android.graphics,
           只引 android.content.Context 的签名类 (反正是桩, 只要类能加载即可) */
        setPathsRoot(dir.getAbsolutePath());

        t1_freshToday(store);
        t2_crossDayKeepsHistory(store);
        t3_legacyFileUpgrade(store);
        t4_trimKeepsToday(store);
        t5_streakAndTotals(store);
        t6_corruptFile(store);

        System.out.println("\n== StoreProbe " + pass + " 通过 / " + fail + " 失败 ==");
        System.exit(fail == 0 ? 0 : 1);
    }

    /* ---------------- 用例 ---------------- */

    /** 全新文件: 今天一格存在, 计数从 0 起 */
    static void t1_freshToday(File store) throws Exception {
        store.delete();
        Store s = Store.load();
        eq("1a 全新: 今日 day = 今天", Store.todayStr(), s.day);
        eq("1b 全新: rounds=0", 0, s.rounds);
        eq("1c 全新: history 含今天一格", true, s.history().containsKey(Store.todayStr()));

        s.addRound(true);
        s.addRound(true);
        s.addRound(false);
        s.save();
        Store r = Store.load();
        int[] h = r.dayOf(Store.todayStr());
        eq("1d 存盘回读: rounds=3", 3, h[0]);
        eq("1e 存盘回读: wins=2", 2, h[1]);
        eq("1f 存盘回读: loses=1", 2 + 0, h[1] + 0);
        eq("1g 存盘回读: loses=1", 1, h[2]);
        eq("1h 今日口径一致", 3, r.rounds);
    }

    /** ⚠️ 核心回归: 跨天归零**不能**清归档 */
    static void t2_crossDayKeepsHistory(File store) throws Exception {
        store.delete();
        // 手造: 昨天打了 30 场, 今天是新的一天
        String today = Store.todayStr();
        String yesterday = shift(today, -1);
        String older = shift(today, -30);
        JSONObject o = new JSONObject();
        o.put("day", yesterday).put("rounds", 30).put("wins", 18).put("loses", 12);
        JSONObject hi = new JSONObject();
        hi.put(yesterday, new JSONObject().put("rounds", 30).put("wins", 18).put("loses", 12));
        hi.put(older, new JSONObject().put("rounds", 5).put("wins", 2).put("loses", 3));
        o.put("history", hi);
        write(store, o);

        Store s = Store.load();
        eq("2a 跨天: 今日归零", 0, s.rounds);
        eq("2b 跨天: day 变今天", today, s.day);
        eq("2c 跨天: 归档保住昨天的 30 场", 30, s.dayOf(yesterday)[0]);
        eq("2d 跨天: 归档保住 30 天前的 5 场", 5, s.dayOf(older)[0]);
        eq("2e 跨天: 归档总天数 = 3 (含今天)", 3, s.history().size());

        s.addRound(true);
        s.save();
        Store r = Store.load();
        eq("2f 跨天后再打一场: 昨天仍是 30", 30, r.dayOf(yesterday)[0]);
        eq("2g 跨天后再打一场: 今天 1", 1, r.dayOf(today)[0]);
    }

    /** 旧版 store.json (没有 history 键) 升级后今天不能是空 */
    static void t3_legacyFileUpgrade(File store) throws Exception {
        store.delete();
        String today = Store.todayStr();
        JSONObject o = new JSONObject();
        o.put("day", today).put("rounds", 7).put("wins", 4).put("loses", 3);
        write(store, o);                       // 旧格式: 只有四个键

        Store s = Store.load();
        int[] h = s.dayOf(today);
        ok("3a 旧文件升级: 今天那格存在", h != null);
        eq("3b 旧文件升级: 今天 rounds 继承 7", 7, h == null ? -1 : h[0]);
        eq("3c 旧文件升级: 今天 wins 继承 4", 4, h == null ? -1 : h[1]);

        s.addRound(false);
        s.save();
        Store r = Store.load();
        eq("3d 旧文件升级后继续计场: 8", 8, r.dayOf(today)[0]);
        eq("3e 旧文件升级后继续计场: loses=4", 4, r.dayOf(today)[2]);
    }

    /** ⚠️ trim 不能把今天/最近几天裁掉 */
    static void t4_trimKeepsToday(File store) throws Exception {
        store.delete();
        String today = Store.todayStr();
        JSONObject o = new JSONObject();
        o.put("day", today).put("rounds", 0).put("wins", 0).put("loses", 0);
        o.put("keep_days", 3);                           // 只留 3 天
        JSONObject hi = new JSONObject();
        for (int i = 10; i >= 0; i--)                    // 造 11 天
            hi.put(shift(today, -i), new JSONObject().put("rounds", i).put("wins", i).put("loses", 0));
        o.put("history", hi);
        write(store, o);

        Store s = Store.load();
        eq("4a trim: 只留 keep_days 天", 3, s.history().size());
        ok("4b trim: 今天还在", s.history().containsKey(today));
        ok("4c trim: 昨天还在", s.history().containsKey(shift(today, -1)));
        eq("4d trim: 留的是**最新**的 3 天", 0,
                s.dayOf(today)[0]);                      // i=0 → rounds 0
        eq("4e trim: 最旧那天(10 天前)已丢", null, s.dayOf(shift(today, -10)));
    }

    /** 连续天数 / 合计 / 近期 N 天 */
    static void t5_streakAndTotals(File store) throws Exception {
        store.delete();
        String today = Store.todayStr();
        JSONObject o = new JSONObject();
        o.put("day", today).put("rounds", 2).put("wins", 1).put("loses", 1);
        JSONObject hi = new JSONObject();
        // 今天、昨天、前天 连着打; 4 天前断了; 5/6 天前连着
        int[] seq = {0, 1, 2, 4, 5};
        for (int i : seq)
            hi.put(shift(today, -i), new JSONObject().put("rounds", 10).put("wins", 6).put("loses", 4));
        o.put("history", hi);
        o.put("keep_days", 0);                            // 不限
        write(store, o);

        Store s = Store.load();
        eq("5a 合计场数 = 5*10", 50, s.totalRounds());
        eq("5b 合计胜 = 5*6", 30, s.totalWins());
        eq("5c 活跃天数 = 5", 5, s.activeDays());
        eq("5d 最长连续 = 3", 3, s.bestStreak());
        int[] d7 = s.lastDays(7);
        eq("5e 近 7 天 = 5*10", 50, d7[0]);
        eq("5f 近 3 天 = 3*10", 30, s.lastDays(3)[0]);
    }

    /** 损坏文件不能炸, 且要退化成"今天 0 场"而不是丢归档之外的东西 */
    static void t6_corruptFile(File store) throws Exception {
        store.delete();
        FileOutputStream os = new FileOutputStream(store);
        os.write("{这不是 JSON".getBytes("UTF-8"));
        os.close();
        Store s = Store.load();
        eq("6a 损坏文件: 不炸, rounds=0", 0, s.rounds);
        eq("6b 损坏文件: day = 今天", Store.todayStr(), s.day);

        store.delete();
        s = Store.load();
        eq("6c 文件不存在: rounds=0", 0, s.rounds);
        s.addRound(true);                                  // 仍可用
        s.save();
        eq("6d 文件不存在: 重建后可写", 1, Store.load().rounds);
    }

    /* ---------------- 工具 ---------------- */

    /** 把 Paths.root 反射换成本地临时目录 (Paths.init 需要 Context, 这里绕过) */
    static void setPathsRoot(String root) throws Exception {
        Class<?> p = Class.forName("com.zhiyaunhe.sgmbot.Paths");
        Field f = p.getDeclaredField("root");
        f.setAccessible(true);
        f.set(null, root);
        Field lg = p.getDeclaredField("legacy");
        lg.setAccessible(true);
        lg.set(null, true);
    }

    static String shift(String ymd, int days) {
        String[] p = ymd.split("-");
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(Integer.parseInt(p[0]), Integer.parseInt(p[1]) - 1, Integer.parseInt(p[2]));
        c.add(Calendar.DAY_OF_MONTH, days);
        return String.format(Locale.US, "%04d-%02d-%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    static void write(File f, JSONObject o) throws Exception {
        FileOutputStream os = new FileOutputStream(f);
        os.write(o.toString(2).getBytes("UTF-8"));
        os.close();
    }

    static void ok(String name, boolean cond) {
        if (cond) { pass++; System.out.println("  [ok] " + name); }
        else { fail++; System.out.println("  [XX] " + name); }
    }

    static void eq(String name, Object want, Object got) {
        boolean c = want == null ? got == null : want.equals(got);
        if (c) { pass++; System.out.println("  [ok] " + name); }
        else { fail++; System.out.println("  [XX] " + name + " — 期望 " + want + ", 实际 " + got); }
    }
}
