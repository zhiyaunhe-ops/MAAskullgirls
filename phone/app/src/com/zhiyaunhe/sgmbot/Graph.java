package com.zhiyaunhe.sgmbot;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 判定路径图 — 结构**完全来自 config.graph**, 代码里没有硬编码节点。
 *
 *   "graph": {"layout":"grid", "nodes":[{"id","label","kind","tpl","roi","th"}...],
 *             "edges":[{"from","to","on"}...]}
 *
 * 循环每走到一个判定点就 Graph.cursor(id) + Trace.put(...), 网页端
 * GET /graph 拿到 {nodes, edges, cursor, trace} 画图并高亮当前节点 ——
 * 换句话说: 改 config 的 nodes/edges 就是改这张图 (加个判定点不必发版)。
 */
public final class Graph {
    private static volatile JSONObject nodes = new JSONObject();   // id → node(+x/y)
    private static volatile JSONArray edges = new JSONArray();
    private static volatile String cursor = "";

    /** 循环开始时按 config 装载 (热更后重新装载) */
    public static void load(JSONObject cfg) {
        JSONObject g = cfg == null ? null : cfg.optJSONObject("graph");
        if (g == null) return;
        JSONArray ns = g.optJSONArray("nodes");
        JSONArray es = g.optJSONArray("edges");
        if (ns == null) return;
        String layout = g.optString("layout", "grid");
        JSONObject map = new JSONObject();
        try {
            for (int i = 0; i < ns.length(); i++) {
                JSONObject n = ns.optJSONObject(i);
                if (n == null) continue;
                String id = n.optString("id", "n" + i);
                // 布局: 节点自带 x/y 优先, 否则 grid 自动排 (4 列)
                if (!n.has("x") || !n.has("y")) {
                    int col = i % 4, row = i / 4;
                    n.put("x", 120 + col * 200);
                    n.put("y", 90 + row * 130);
                }
                n.put("_i", i);
                map.put(id, n);
            }
        } catch (Exception e) {
            SgmLog.i("graph", "装载失败: " + e);
            return;
        }
        nodes = map;
        edges = es != null ? es : new JSONArray();
        if (!"grid".equals(layout)) { /* 预留: 其它布局算法 */ }
    }

    /** 循环走到哪个节点 (网页高亮用) */
    public static void cursor(String id) {
        cursor = id == null ? "" : id;
    }

    public static String cursor() { return cursor; }

    /** 网页/状态快照用; nodes 按 config 里声明的顺序出数组 (画图按序编号更直观) */
    public static JSONObject toJson() {
        try {
            JSONArray arr = new JSONArray();
            JSONArray ord = new JSONArray(nodes.length());
            java.util.Iterator<String> it = nodes.keys();
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
                    .put("nodes", arr)
                    .put("edges", edges)
                    .put("cursor", cursor)
                    .put("byNode", Trace.byNode())
                    .put("trace", Trace.recent(60))
                    .put("ts", System.currentTimeMillis());
        } catch (Exception e) {
            return new JSONObject();
        }
    }
}
