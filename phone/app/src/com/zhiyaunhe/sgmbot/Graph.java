package com.zhiyaunhe.sgmbot;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 判定路径图 — 结构**完全来自 config.graph**, 代码里没有硬编码节点。
 *
 * <h3>两件事都要看得见 (2026-10-08 用户纠正: 「判定图指的是整个程序的, 不是运行到哪」)</h3>
 * <ol>
 *   <li><b>静态全图</b> — 循环走了哪些判断、每个判断的**条件和当前阈值**是多少、
 *       不满足时往哪走。这是理解"程序到底怎么决策"的主视图, 引擎没跑也要能看。</li>
 *   <li><b>实时位置</b> — 此刻走到哪一步、最近几百次判定的峰值。叠加在全图上。</li>
 * </ol>
 * 所以 nodes/edges 是**整棵决策树**(25 节点), cursor 只是其中一个附加字段。
 *
 * <h3>阈值不写死</h3>
 * 节点用 {@code cfg:"thresholds.min_sim"} 记**取值路径**, load 时回填到 {@code val}。
 * 这样改了 config 图跟着变, 不会出现「图上写 0.72 / 实际是 0.75」。
 *
 * <h3>escape 链由配置展开</h3>
 * 带 {@code expand:"escape.items"} 的节点在 load 时按 escape.items 展开成每项的
 * 子节点 (各带自己的 th) —— 改逃生链表不必同步改 graph, 图自动跟着变。
 *
 * <h3>布局</h3>
 * {@code lane} 把节点分到泳道 (阶段); load 时按泳道+序号算出 x/y, 前端直接画。
 */
public final class Graph {

    private static volatile JSONObject nodes = new JSONObject();   // id → node(+x/y/val)
    private static volatile JSONArray edges = new JSONArray();
    private static volatile JSONArray lanes = new JSONArray();
    private static volatile JSONObject cfgRef = null;
    private static volatile String cursor = "";
    private static volatile String title = "SGM 判定路径图";
    private static volatile String note = "";

    private Graph() { }

    /** 循环开始时按 config 装载 (热更后重新装载) */
    public static synchronized void load(JSONObject cfg) {
        cfgRef = cfg;
        JSONObject g = cfg == null ? null : cfg.optJSONObject("graph");
        if (g == null) return;
        JSONArray ns = g.optJSONArray("nodes");
        if (ns == null) return;

        title = g.optString("title", title);
        note = g.optString("note", note);
        lanes = g.optJSONArray("lanes") != null ? g.optJSONArray("lanes") : new JSONArray();

        JSONObject map = new JSONObject();
        JSONArray outEdges = new JSONArray();
        // expand 出的链: 占位节点 id → [链首, 链尾]。配完要把挂在该占位上的边重接,
        // 否则 `stall→escape` / `escape→escfb` 这两条边会因为 escape 不再存在而悬空。
        JSONObject chains = new JSONObject();
        try {
            for (int i = 0; i < ns.length(); i++) {
                JSONObject n = ns.optJSONObject(i);
                if (n == null) continue;
                if (n.has("expand")) {
                    // ⚠️ 必须往**正在构建的 map** 里写。第一版写成作用在旧的
                    //    nodes 静态字段上, 而 load 结尾 `nodes = map` 会把它整个
                    //    覆盖掉 ⇒ 展开出来的 escape 链节点一个都不剩。
                    String[] ends = expand(map, n, outEdges);
                    if (ends != null)
                        chains.put(n.optString("id"), new JSONArray(java.util.Arrays.asList(
                                (Object) ends[0], (Object) ends[1])));
                    continue;
                }
                put(map, n, n.optInt("lane", 0));
            }
            JSONArray es = g.optJSONArray("edges");
            if (es != null) for (int i = 0; i < es.length(); i++) {
                JSONObject e = es.optJSONObject(i);
                if (e == null) continue;
                String from = e.optString("from", ""), to = e.optString("to", "");
                if (chains.has(from)) from = chains.getJSONArray(from).optString(1);  // 出边←链尾
                if (chains.has(to)) to = chains.getJSONArray(to).optString(0);        // 入边→链首
                e.put("from", from).put("to", to);
                outEdges.put(e);
            }
            layout(map);
        } catch (Exception e) {
            SgmLog.i("graph", "装载失败: " + e);
            return;
        }
        nodes = map;
        edges = outEdges;
    }

    /**
     * 配置变了 → 立刻把当前图作废, 下次取图/装载时按新配置重建。
     *
     * <p>为什么要这一手: {@link #ensureLoaded} 是幂等的 (有节点就不重装), 于是一旦
     * 静态装载过一次, 后续改配置**图不会跟着变** —— 界面上还印着旧阈值, 就是"图与实
     * 不符"。配置的每条写入路径都收口在 {@code BotService.reload()}, 在那里统一作废。
     */
    public static synchronized void invalidate() {
        nodes = new JSONObject();
        edges = new JSONArray();
        cursor = "";
    }

    /** 引擎没跑时也要能看 —— 首次取图前用当前配置**静态装载**一次, 幂等。
     *
     * <p>⚠️ 上面 javadoc 承诺「静态全图…引擎没跑也要能看」, 但 {@link #load} 原先
     * 只在 BotService 起跑 / SettleLoop 开跑时被调用 ⇒ **没跑就没图**: 打开判定图
     * 只会看到「图还没装载」。这正好撞上用户 2026-10-08 纠正的那句话
     * (「判定图指的是整个程序的, 不是运行到哪」)。由此 HTTP 层在取 /graph 前补一次。
     *
     * <p>幂等: 已有节点直接返回 —— 引擎在跑时由 SettleLoop 按热更重装, 这里不抢。
     */
    public static synchronized void ensureLoaded(Config c) {
        if (c == null || nodes.length() > 0) return;
        load(c.read());
    }

    /** 登记一个节点: 回填阈值到 val, 记下顺序 */
    private static void put(JSONObject map, JSONObject n, int lane) throws Exception {
        String id = n.optString("id", "");
        if (id.isEmpty()) return;
        n.put("lane", lane);
        String p = n.optString("cfg", "");
        if (!p.isEmpty()) n.put("val", valueAt(cfgRef, p));
        n.put("_i", map.length());
        map.put(id, n);
    }

    /**
     * escape 链的展开: 一个配置项一个子节点, 链内首尾相接。
     * 这样「加/删/改一项的阈值」只动 escape.items, graph 段不用跟着改。
     *
     * @return 展开出的 [链首 id, 链尾 id]; 没能展开成链返回 null
     */
    private static String[] expand(JSONObject map, JSONObject holder, JSONArray outEdges)
            throws Exception {
        String path = holder.optString("expand", "");
        JSONObject section = valueAsObj(cfgRef, path);
        int lane = holder.optInt("lane", 0);
        if (section == null) { put(map, holder, lane); return null; }
        JSONArray items = section.optJSONArray("items");
        if (items == null || items.length() == 0) { put(map, holder, lane); return null; }
        String prev = holder.optString("from", "");
        String head = null;
        for (int k = 0; k < items.length(); k++) {
            JSONObject it = items.optJSONObject(k);
            if (it == null) continue;
            String name = it.optString("tpl", "item" + k);
            JSONObject child = new JSONObject()
                    .put("id", "esc_" + name)
                    .put("label", name)
                    .put("kind", "chain")
                    .put("lane", lane)
                    .put("cond", "命中即点" + (it.has("roi") ? " (限定区域)" : " (全屏查)"))
                    .put("val", it.opt("th"))
                    .put("of", path + "[" + k + "].th");
            put(map, child, lane);
            if (head == null) head = "esc_" + name;
            if (!prev.isEmpty())
                outEdges.put(new JSONObject().put("from", prev).put("to", "esc_" + name)
                        .put("on", "未命中"));
            prev = "esc_" + name;
        }
        return head == null ? null : new String[]{head, prev};
    }

    /**
     * 按 cfg 路径取当前值 — 支持 a.b.c / a~b(两段并列) / 顶层键 / 数组。
     * 取不到返回 "".
     */
    public static Object valueAt(JSONObject root, String path) {
        if (root == null || path.isEmpty()) return "";
        if (path.indexOf('~') > 0) {                       // 例: run.pause_from~pause_to
            String[] two = path.split("~");
            String head = two[0], tail = two[1];
            int dot = head.lastIndexOf('.');
            String prefix = dot > 0 ? head.substring(0, dot + 1) : "";
            String a = String.valueOf(valueAt(root, head));
            String b = String.valueOf(valueAt(root, prefix + tail));
            String av = a.isEmpty() ? "空" : a, bv = b.isEmpty() ? "空" : b;
            if ("空".equals(av) && "空".equals(bv)) return "未启用";
            return av + " ~ " + bv;
        }
        String[] ps = path.split("\\.");
        Object cur = root;
        for (String p : ps) {
            if (cur == null) return "";
            if (!(cur instanceof JSONObject) && p.matches("\\d+")) {
                JSONArray arr = (JSONArray) cur;
                int idx = Integer.parseInt(p);
                cur = idx < arr.length() ? arr.opt(idx) : null;
                continue;
            }
            if (!(cur instanceof JSONObject)) return "";
            cur = ((JSONObject) cur).opt(p);
        }
        if (cur == null) return "";
        if (cur instanceof JSONArray) {
            JSONArray a = (JSONArray) cur;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < a.length() && i < 4; i++) {
                if (i > 0) sb.append(", ");
                sb.append(String.valueOf(a.opt(i)));
            }
            return a.length() > 4 ? sb.append(" …").toString() : sb.toString();
        }
        return cur;
    }

    private static JSONObject valueAsObj(JSONObject root, String path) {
        Object o = valueAt(root, path);
        return o instanceof JSONObject ? (JSONObject) o : null;
    }

    /** 泳道布局 — 每条泳道一行, 行内横向排开; y 按泳道序号下推 */
    private static void layout(JSONObject map) throws Exception {
        int[] cnt = new int[64];
        java.util.Iterator<String> it = map.keys();
        java.util.List<JSONObject> list = new java.util.ArrayList<>();
        while (it.hasNext()) {
            JSONObject n = map.optJSONObject(it.next());
            if (n != null) list.add(n);
        }
        java.util.Collections.sort(list, (a, b) -> a.optInt("_i", 0) - b.optInt("_i", 0));
        for (JSONObject n : list) {
            int lane = Math.max(0, Math.min(cnt.length - 1, n.optInt("lane", 0)));
            int col = cnt[lane]++;
            n.put("lane", lane);
            n.put("col", col);
            n.put("x", 110 + col * 185);
            n.put("y", 70 + lane * 128);
        }
    }

    /** 循环走到哪个节点 (网页高亮用) */
    public static void cursor(String id) {
        cursor = id == null ? "" : id;
    }

    public static String cursor() { return cursor; }

    /** 引擎没跑/图没装载时也要能看结构化说明 */
    public static String title() { return title; }

    public static String note() { return note; }

    /**
     * 网页/状态快照用。
     * nodes 按 config 声明顺序出数组; 每个节点带 val = 当前 config 里的实际取值。
     */
    public static JSONObject toJson() {
        try {
            JSONArray arr = new JSONArray();
            java.util.Iterator<String> it = nodes.keys();
            JSONArray ord = new JSONArray(nodes.length());
            while (it.hasNext()) {
                JSONObject n = nodes.optJSONObject(it.next());
                if (n == null) continue;
                int i = n.optInt("_i", ord.length());
                while (ord.length() <= i) ord.put(JSONObject.NULL);
                ord.put(i, n);
            }
            for (int i = 0; i < ord.length(); i++)
                if (!ord.isNull(i)) arr.put(ord.getJSONObject(i));
            return new JSONObject()
                    .put("title", title)
                    .put("note", note)
                    .put("lanes", lanes)
                    .put("nodes", arr)
                    .put("edges", edges)
                    .put("cursor", cursor)
                    .put("running", BotService.isRunning())
                    .put("byNode", Trace.byNode())
                    .put("trace", Trace.recent(60))
                    .put("ts", System.currentTimeMillis());
        } catch (Exception e) {
            return new JSONObject();
        }
    }
}
