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

    /**
     * ⚠️ {@code volatile} + **整表替换**: 线上拉取成功后会在后台线程 reload, 而 UI 线程
     * 可能正在遍历 {@link #names()} / {@link #rules()}。就地 clear+put 会抛
     * ConcurrentModificationException, 整表换引用则最坏只读到旧表 (可接受)。
     */
    private static volatile Map<String, JSONObject> TABLE = new HashMap<>();
    /**
     * 整张表的根 ({@code {rotation, arenas, ...}}) —— {@link PfRotation} 要用 rotation 段算
     * 「今天开放哪些」。与 {@link #TABLE} 一起整体换引用。
     */
    private static volatile JSONObject ROOT;
    private static int monthlySlots = 2;
    private static boolean loaded;
    /** 当前这张表从哪来：{@code 线上} / {@code 内置} —— 设置页要显示，别让人猜。 */
    private static String sourceDesc = "未载入";

    private PfArenaTable() { }

    /**
     * 载入场次表（幂等）。**优先线上缓存，其次 APK 内置**。
     *
     * <p>⚠️ 顺序不能反：内置表只有重装 APK 才会更新，而 {@code adb install -r} 常是增量安装
     * （assets 根本没换，见 MEMORY「真机移植三大坑」①）。线上那份由
     * {@code https://zhiyaunhe-ops.github.io/data/arenas.json} 提供，改了就能立刻生效。</p>
     *
     * <p>⚠️ 缓存坏了（半截文件 / HTML 错误页）必须能自己退回内置 —— 判据是
     * {@link PfArenaRemote#valid}，不是「文件存在」。</p>
     */
    static synchronized void load(Context c) {
        if (loaded) return;
        loaded = true;
        JSONObject root = PfArenaRemote.cached();
        boolean online = root != null && PfArenaRemote.valid(root);
        if (!online) root = assetRoot(c);
        apply(root, online ? "线上" : "内置");
    }

    /** 丢掉内存里的表并重新选源（拉取成功后调；也让「线上→内置」可回落）。 */
    static synchronized void reload(Context c) {
        /* 不先 clear: apply() 会建好新表再整体换引用, 中间态继续用旧表 */
        loaded = false;
        load(c);
    }

    /** 当前表的来源：「线上 42 条」/「内置 36 条」。 */
    static String sourceDesc() {
        return sourceDesc + " " + TABLE.size() + " 条";
    }

    private static JSONObject assetRoot(Context c) {
        try {
            InputStream in = c.getAssets().open("pf_arena.json");
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return new JSONObject(new String(bo.toByteArray(), "UTF-8"));
        } catch (Throwable t) {
            SgmLog.i("pf", "内置场次表读取失败: " + t);
            return null;
        }
    }

    private static void apply(JSONObject root, String src) {
        sourceDesc = src;
        if (root == null) {
            SgmLog.i("pf", "场次表为空 — 按卡位保守口径 (4kw / 4 钉) 跑");
            return;
        }
        try {
            int slots = root.optInt("monthly_slots", 2);
            JSONObject arenas = root.optJSONObject("arenas");
            if (arenas == null) return;
            Map<String, JSONObject> map = new HashMap<>();
            for (Iterator<String> it = arenas.keys(); it.hasNext(); ) {
                String k = it.next();
                JSONObject e = arenas.optJSONObject(k);
                if (e != null) map.put(k, e);
            }
            monthlySlots = slots;
            TABLE = map;                      // 整体换引用 (见字段注释)
            ROOT = root;
            SgmLog.i("pf", "场次表已载入 " + TABLE.size() + " 条 (" + src + ", 月场位前 "
                    + monthlySlots + " 张)");
            /* 目录里有 40 条 ≠ 今天能打 40 场。登录时就把"今天开放"打出来, 免得又把目录当列表。 */
            java.util.List<String> t = namesToday();
            StringBuilder skip = new StringBuilder();
            try {
                for (PfRotation.Open o : PfRotation.today(ROOT)) {
                    if (o.pickable()) continue;
                    if (skip.length() > 0) skip.append(", ");
                    skip.append(o.name == null ? o.type : o.name)
                            .append(o.unverified ? "(相位未核实)" : "(表里无名)");
                }
            } catch (Throwable ignored) { }
            SgmLog.i("pf", "今日开放 (游戏日 " + PfRotation.gameDay() + "): "
                    + (t.isEmpty() ? "轮换公式算不出 (rotation 段缺失) — 退回整表"
                                   : t.size() + " 场 → " + t.toString())
                    + (skip.length() == 0 ? "" : " | 未列入: " + skip));
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
    /**
     * **今天开放**的场次名 (轮换公式, 顺序: 角色 → 元素 → 星助手 → 招式 → 月场)。
     *
     * <p>⚠️⚠️ 2026-10-18 用户: 「事实上只有 5 张, 而且我做了一个网站是显示目前的场次的」
     * —— 在此之前 {@link #names()} 直接把表里 40 条目录全列出来当选项。场次目录与
     * "现在能打什么"是两件事, 后者由站点那张表里的 {@code rotation} 段按天推出
     * (见 {@link PfRotation})。</p>
     *
     * <p>⚠️ 公式算不出来时返回**空表**, 由 {@link #names()} 退回整表 —— 绝不能因为公式
     * 缺数据就让用户选不到任何场次。</p>
     */
    static java.util.List<String> namesToday() {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            for (PfRotation.Open o : PfRotation.today(ROOT)) {
                if (o.pickable() && !out.contains(o.name)) out.add(o.name);
            }
        } catch (Throwable t) {
            SgmLog.i("pf", "轮换公式失败: " + t);
        }
        return out;
    }

    /**
     * 可选场次 = **今天开放的**; 公式不可用才退回「所有未过期」。
     *
     * <p>⚠️ 2026-10-18 用户: 「只需要当前的场次可选, 之前的场次只在历史记录里可以回看」。
     * 历史 (已过期) 走 {@link #namesExpired()}; 完整目录 (含今天没开的) 走 {@link #namesAll()}。</p>
     */
    static java.util.List<String> names() {
        java.util.List<String> today = namesToday();
        return today.isEmpty() ? namesFiltered(false) : today;
    }

    /** 完整目录 (含今天没开放的) —— 只给"手动核对/兜底选择"用, 不是默认列表。 */
    static java.util.List<String> namesAll() {
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

        /** 取人规则: character 只约束**防守队**(含该角色即可); element 要求出战队**含**对应元素;
         *  monthly_character 无限制; 其余 null。 */
        String rule() {
            if ("element".equals(tag) || "monthly_element".equals(tag)) {
                return element == null ? null : "element:" + element;
            }
            if ("character".equals(tag)) {
                /* ⚠️ 只有**周**角色场限制防守队伍的角色。
                 * 月场（含角色月场 COSTUME PARTY）**没有出战限制** —— 2026-10-18 用户口径；
                 * 别把这条外推到 monthly_character，否则脚本会去换防守队、白烧能量。 */
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
