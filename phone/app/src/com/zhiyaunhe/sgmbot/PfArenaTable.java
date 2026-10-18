package com.zhiyaunhe.sgmbot;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 场次表 —— hub 卡名/卡位 → tag 与取值条件 (PC {@code pf_artag.classify_arena} 的手机移植)。
 *
 * <h3>口径来源 (PF_BOT.md §6.11/§6.12 + 项目 MEMORY 的「场次条件」表)</h3>
 * <ul>
 *   <li>⚠️⚠️ <b>月场固定占 hub #1/#2</b> ({@code MONTHLY_SLOTS=2}) —— <b>位置优先于名称</b>,
 *       idx&lt;2 一律按月场处理, 且**不查**非月场表。教训: 位置比名称硬。</li>
 *   <li>⚠️ <b>sgmnow 的 name ≠ hub 卡名</b> (两套命名空间) ⇒ 名称相似度路径对非月场结构性失效,
 *       所以卡名→类别只能走人工表 (本类读的就是合并后的那张表)。</li>
 *   <li>未命中 ⟹ <b>不猜</b>: tag 落 unknown, 取最保守的 4kw / 4 能量, rule=null
 *       —— 「宁可漏跑不错跑」。</li>
 * </ul>
 *
 * <h3>条件表 (铁律, 见项目 MEMORY)</h3>
 * <pre>
 * character      5kw   5 能量  rule=class (仅约束防守队)
 * element        5kw   4 能量  rule=element
 * misc           5kw   4 能量  None
 * monthly_*      无上限 4 能量  monthly_element 挂 element
 * 其余 + unknown  4kw   4 能量  None
 * </pre>
 * ⚠️ {@code score_target} 为「无上限」≠ 漏填 —— 是**真的没有上限**, bot 判据要直接跳过。
 */
final class PfArenaTable {

    /** 「无上限」哨兵值 (与 PC 的 None 对应)。 */
    static final long NO_LIMIT = -1L;

    private static final Map<String, JSONObject> TABLE = new HashMap<>();
    private static int monthlySlots = 2;
    private static boolean loaded;

    private PfArenaTable() { }

    /** 读 assets/pf_arena.json (幂等)。 */
    static synchronized void load(Context c) {
        if (loaded) return;
        loaded = true;
        try {
            InputStream in = c.getAssets().open("pf_arena.json");
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            JSONObject root = new JSONObject(new String(bo.toByteArray(), "UTF-8"));
            monthlySlots = root.optInt("monthly_slots", 2);
            JSONObject arenas = root.optJSONObject("arenas");
            if (arenas == null) return;
            for (Iterator<String> it = arenas.keys(); it.hasNext(); ) {
                String k = it.next();
                TABLE.put(k, arenas.optJSONObject(k));
            }
            SgmLog.i("pf", "场次表已载入 " + TABLE.size() + " 条 (月场位前 " + monthlySlots + " 张)");
        } catch (Throwable t) {
            SgmLog.i("pf", "场次表载入失败: " + t);
        }
    }

    /**
     * hub 卡名 + 卡位 → 场次定义。
     *
     * <p>⚠️ 顺序不能改 (与 PC 一致): **先看位置** (月场位不查名字), 再查人工表。
     */
    static Spec classify(String cardName, int hubIdx, String day) {
        boolean monthlySlot = hubIdx >= 0 && hubIdx < monthlySlots;
        if (cardName != null) {
            JSONObject e = TABLE.get(key(cardName));
            if (e != null && (!monthlySlot || e.optBoolean("monthly"))) {
                if (valid(e, day)) return Spec.of(e, cardName, false);
            }
        }
        if (monthlySlot) {
            /* 位置说是月场但表里没有这一条 (新场次/名字读错) ⇒ 按"月场里的杂项"保守处理,
             * 不往下当普通场 —— 位置优先于名称。 */
            return new Spec(cardName, "monthly_misc", null, null, true, false);
        }
        return new Spec(cardName, "unknown", null, null, false, false);
    }

    /** 有效期判定 (月场表带 valid_from/valid_until; 缺 day 视为有效)。 */
    private static boolean valid(JSONObject e, String day) {
        if (day == null) return true;
        String f = e.optString("from", null), u = e.optString("until", null);
        if (f != null && !f.isEmpty() && day.compareTo(f) < 0) return false;
        if (u != null && !u.isEmpty() && day.compareTo(u) > 0) return false;
        return true;
    }

    /** 归一化键: 只留 [A-Z0-9] (与 PC 的 _key 一致)。 */
    static String key(String s) {
        StringBuilder sb = new StringBuilder();
        for (char ch : s.toUpperCase().toCharArray()) {
            if ((ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9')) sb.append(ch);
        }
        return sb.toString();
    }

    /**
     * 全部场次的**显示名**(按名字排序) —— 给 App 内「PF 设置」页列出来选。
     *
     * <p>⚠️⚠️ 2026-10-17: 这张表以前**只 load 不查询**(全项目只有 PfFlow 里一句 load),
     *    也就是说 22 个场次的条件是死数据、App 里没有任何地方能选它 —— 用户的原话是
     *    「WebUI 的那些选场次什么能量上限等等条件应该都在 APP 里面配置才对呀,
     *     你现在完全没有地方配置, 也没有读取场次的那个逻辑」。本方法与
     *    {@link #spec(String)} 就是给那个设置页用的两个口子。</p>
     *
     * <p>⚠️ 2026-10-18 用户: 「只需要当前的场次可选, 之前的场次只在历史记录里可以回看」
     *    ⇒ 这里**只返回当前有效**的 (带 {@code until} 且已过期的月场不列)。
     *    过期那些走 {@link #namesExpired()} (只读展示, 不能选)。</p>
     */
    static java.util.List<String> names() {
        return namesFiltered(false);
    }

    /** 已经过期 (until &lt; 今天) 的场次 —— **只读**, 给「历史场次」回看用, 不能选。 */
    static java.util.List<String> namesExpired() {
        return namesFiltered(true);
    }

    private static java.util.List<String> namesFiltered(boolean wantExpired) {
        String today = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(new java.util.Date());
        java.util.List<String> out = new java.util.ArrayList<>();
        for (JSONObject e : TABLE.values()) {
            if (e == null) continue;
            String n = e.optString("name", "");
            if (n.isEmpty()) continue;
            String u = e.optString("until", null);
            boolean expired = u != null && !u.isEmpty() && today.compareTo(u) > 0;
            if (expired == wantExpired) out.add(n);
        }
        for (int i = 1; i < out.size(); i++) {                 // 插入排序: 表只有 20 来条, 不值得引排序器
            String v = out.get(i);
            int j = i - 1;
            while (j >= 0 && out.get(j).compareToIgnoreCase(v) > 0) { out.set(j + 1, out.get(j)); j--; }
            out.set(j + 1, v);
        }
        return out;
    }

    /** 按显示名查场次 (查不到返回 null)。 */
    static Spec spec(String displayName) {
        if (displayName == null) return null;
        JSONObject e = TABLE.get(key(displayName));
        if (e == null) return null;
        String n = e.optString("name", displayName);
        return Spec.of(e, n, e.optBoolean("monthly"));
    }

    /**
     * 全部可能的**出战规则**取值 (排序去重) —— 主页 PF 配置的「出战规则」弹层用。
     *
     * <p>形如 {@code class:Filia} / {@code element:water}。与 {@link Spec#rule()} 同一口径,
     * 只是这里遍历整张表收集候选, 让用户能**手动**指定 (自动带出之外的override)。
     */
    static java.util.List<String> rules() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (JSONObject e : TABLE.values()) {
            if (e == null) continue;
            String r = Spec.of(e, "", false).rule();     // 只看 rule() 的推导, 名字/月场位无关
            if (r == null || r.isEmpty() || out.contains(r)) continue;
            out.add(r);
        }
        java.util.Collections.sort(out);
        return out;
    }

    /** 一个场次的完整定义 + 取值条件。 */
    static final class Spec {
        final String name;      // hub 上读到的原名 (可能是 null)
        final String tag;       // character/element/misc/monthly_*/unknown/gold/...
        final String element;   // element 类才有
        final String charName;  // character 类才有 (显示名)
        final boolean monthly;
        final boolean matched;  // 人工表命中过 (没命中 = 猜的, 别当依据)

        Spec(String name, String tag, String element, String charName,
             boolean monthly, boolean matched) {
            this.name = name; this.tag = tag; this.element = element;
            this.charName = charName; this.monthly = monthly; this.matched = matched;
        }

        static Spec of(JSONObject e, String name, boolean monthlySlot) {
            return new Spec(name, e.optString("tag", "unknown"),
                    e.isNull("element") ? null : e.optString("element", null),
                    e.isNull("char") ? null : e.optString("char", null),
                    e.optBoolean("monthly", monthlySlot), true);
        }

        /** 分数目标 (NO_LIMIT = 无上限 ⇒ 判据跳过)。 */
        long scoreTarget() {
            if (monthly || "monthly_element".equals(tag) || "monthly_character".equals(tag)
                    || "monthly_misc".equals(tag)) return NO_LIMIT;
            if ("character".equals(tag) || "element".equals(tag) || "misc".equals(tag)) {
                return 50_000_000L;
            }
            return 40_000_000L;                   // 其余 + unknown: 最保守
        }

        /** 每场消耗的能量 (编队门槛)。 */
        int energy() {
            if ("character".equals(tag)) return 5;
            return 4;
        }

        /** 取人规则: character 只约束**防守队**; element 限制出战队元素; 其余 null。 */
        String rule() {
            if ("element".equals(tag) || "monthly_element".equals(tag)) {
                return element == null ? null : "element:" + element;
            }
            if ("character".equals(tag)) {
                return charName == null ? null : "class:" + charName;
            }
            return null;
        }

        boolean noLimit() { return scoreTarget() == NO_LIMIT; }

        @Override public String toString() {
            return tag + (element != null ? "/" + element : "")
                    + (charName != null ? "/" + charName : "")
                    + (matched ? "" : "(未命中, 保守取值)");
        }
    }
}
