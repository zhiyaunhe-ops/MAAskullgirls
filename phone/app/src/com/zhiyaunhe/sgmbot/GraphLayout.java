package com.zhiyaunhe.sgmbot;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 判定图的几何 —— **不依赖任何 Android 类**, 所以能被探针在 JVM 里直接跑
 * (同 {@link WorkPoint} 的理由: 探针与实现共用同一份几何, 才不会出现
 *  "探针说没事、实机点到别处")。
 *
 * <p>这里管三件事, 且**只在这里管**:
 * <ol>
 *   <li><b>世界坐标</b> — 节点中心由 lane(行) / col(列) 算出来, {@link Graph#layout}
 *       也调这里 (所以"图上画在哪"与"命中判定"永不会各算一套)。设计单位 = dp。</li>
 *   <li><b>视口变换</b> — 缩放 k (设计单位 → 屏幕 px) + 平移 (ox, oy = 可视区左上角,
 *       设计单位)。屏幕↔世界的来回换算只有 {@link #sx}/{@link #wx} 两处。</li>
 *   <li><b>命中判定</b> — 屏幕点落在哪个节点框里。</li>
 * </ol>
 *
 * ⚠️ **世界单位不是像素**: 网页版把 110/185/128 当 CSS px 用, 在手机上必然小到看不清
 * (竖屏 1080px 宽塞 1481 个单位)。原生版把这些当 **dp**, 绘制时统一乘 density,
 * 于是 1:1 时字号就是 13sp —— 可读。这也是"能看清"与"能看全"不能兼得的根因,
 * 所以界面给了「全图 / 1:1」两个档, 而不是偷偷选一个。
 */
public final class GraphLayout {

    /** 节点框 (设计单位)。必须与列距/行距配套: 见 GraphLayoutProbe 的 assert。 */
    public static final int NW = 152, NH = 54;
    /** 首列中心 / 列距 */
    public static final int X0 = 110, DX = 185;
    /** 首行中心 / 行距 */
    public static final int Y0 = 70, DY = 128;

    /** 最小/最大缩放 (设计单位 → px 的比例, 已含 density)。防止缩放到看不见或糊成一团。 */
    public static final float K_MIN_FACTOR = 0.25f, K_MAX_FACTOR = 4f;

    private GraphLayout() { }

    /** 第 col 列节点的中心 x (设计单位) */
    public static int x(int col) { return X0 + col * DX; }

    /** 第 lane 行节点的中心 y (设计单位) */
    public static int y(int lane) { return Y0 + lane * DY; }

    /* ---------------- 世界包围盒 ---------------- */

    /** 所有节点的包围盒 [x0, y0, x1, y1] (设计单位, 含节点框)。空图返回 null。 */
    public static float[] bounds(JSONArray nodes) {
        if (nodes == null || nodes.length() == 0) return null;
        float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject n = nodes.optJSONObject(i);
            if (n == null) continue;
            float cx = (float) n.optDouble("x", 0), cy = (float) n.optDouble("y", 0);
            x0 = Math.min(x0, cx - NW / 2f); x1 = Math.max(x1, cx + NW / 2f);
            y0 = Math.min(y0, cy - NH / 2f); y1 = Math.max(y1, cy + NH / 2f);
        }
        if (x0 > x1) return null;
        return new float[]{x0, y0, x1, y1};
    }

    /**
     * 适应视口: 把整张图塞进 [0,vw]×[0,vh] 并居中。
     *
     * @param pad 屏幕内边距 (px)
     * @return {k, ox, oy} — k 是"设计单位 → px"的比例 (已含 density); ox/oy 是可视区
     *         左上角在世界里的位置 (设计单位)。空图或视口为 0 时返回最小缩放。
     */
    public static float[] fit(JSONArray nodes, float vw, float vh, float pad, float minK) {
        float[] b = bounds(nodes);
        if (b == null || vw <= 2 * pad || vh <= 2 * pad) return new float[]{minK, 0, 0};
        float w = b[2] - b[0], h = b[3] - b[1];
        if (w <= 0 || h <= 0) return new float[]{minK, 0, 0};
        float k = Math.min((vw - 2 * pad) / w, (vh - 2 * pad) / h);
        float cx = (b[0] + b[2]) / 2f, cy = (b[1] + b[3]) / 2f;
        float ox = cx - (vw / 2f) / k, oy = cy - (vh / 2f) / k;
        return new float[]{k, ox, oy};
    }

    /** 平移范围: 可视区左上角被夹在这个区间内, 免得把图拖出屏幕外找不回来。
     *  ⚠️ 图比视口还小时 (缩得很小) [lo,hi] 会反转 —— 那不是"没范围", 而是"没得挪",
     *     此时两边都取中点让图**居中**, 否则会被夹到左边界上偏在一边。 */
    public static float[] clampRange(JSONArray nodes, float vw, float vh, float k, float slack) {
        float[] b = bounds(nodes);
        if (b == null) return new float[]{-1e9f, 1e9f, -1e9f, 1e9f};
        float vwd = vw / k, vhd = vh / k;            // 可视区在世界里的尺寸 (设计单位)
        float[] g = {b[0] - slack, b[2] + slack - vwd, b[1] - slack, b[3] + slack - vhd};
        if (g[1] < g[0]) g[1] = g[0] = (g[0] + g[1]) / 2f;
        if (g[3] < g[2]) g[3] = g[2] = (g[2] + g[3]) / 2f;
        return g;
    }

    /* ---------------- 屏幕 ↔ 世界 ---------------- */

    public static float sx(float wx, float ox, float k) { return (wx - ox) * k; }

    public static float sy(float wy, float oy, float k) { return (wy - oy) * k; }

    public static float wx(float px, float ox, float k) { return px / k + ox; }

    public static float wy(float py, float oy, float k) { return py / k + oy; }

    /* ---------------- 命中判定 ---------------- */

    /** 屏幕点落在哪个节点框里 — 返回节点 id, 没命中返回 ""。后画的压在上面 (倒序查)。 */
    public static String hit(JSONArray nodes, float px, float py, float ox, float oy, float k) {
        if (nodes == null || k <= 0) return "";
        float wxp = wx(px, ox, k), wyp = wy(py, oy, k);
        for (int i = nodes.length() - 1; i >= 0; i--) {
            JSONObject n = nodes.optJSONObject(i);
            if (n == null) continue;
            float cx = (float) n.optDouble("x", 0), cy = (float) n.optDouble("y", 0);
            if (Math.abs(wxp - cx) <= NW / 2f && Math.abs(wyp - cy) <= NH / 2f)
                return n.optString("id", "");
        }
        return "";
    }

    /* ---------------- 边 ---------------- */

    /**
     * 一条边的起讫点 (中心连线与两个节点框边界的交点) —— 箭头才画得贴框。
     *
     * @return {x1, y1, x2, y2}; 两框同心 (退化) 时返回 null 让调用方跳过
     */
    public static float[] edgePoints(float ax, float ay, float bx, float by) {
        float dx = bx - ax, dy = by - ay;
        if (dx == 0 && dy == 0) return null;
        float[] p1 = borderPoint(ax, ay, dx, dy);        // 从 a 出发, 朝 b
        float[] p2 = borderPoint(bx, by, -dx, -dy);      // 从 b 出发, 朝 a
        return new float[]{p1[0], p1[1], p2[0], p2[1]};
    }

    /** 从 (cx,cy) 沿 (dx,dy) 射出, 与自身矩形边界的交点 */
    private static float[] borderPoint(float cx, float cy, float dx, float dy) {
        float hw = NW / 2f, hh = NH / 2f;
        float tx = dx == 0 ? Float.MAX_VALUE : hw / Math.abs(dx);
        float ty = dy == 0 ? Float.MAX_VALUE : hh / Math.abs(dy);
        float t = Math.min(tx, ty);
        if (t == Float.MAX_VALUE) return new float[]{cx, cy};
        return new float[]{cx + dx * t, cy + dy * t};
    }

    /** from→to 这条边是否该画成弧 (存在反向边 to→from 时两条会重叠) */
    public static boolean isReversePair(JSONArray edges, String from, String to) {
        if (edges == null) return false;
        for (int i = 0; i < edges.length(); i++) {
            JSONObject e = edges.optJSONObject(i);
            if (e == null) continue;
            if (to.equals(e.optString("from", "")) && from.equals(e.optString("to", "")))
                return true;
        }
        return false;
    }

    /**
     * 反向边对里, 哪一条画弧 —— 约定: **id 序在前的画直线**, 另一条画弧。
     * 这样两条边不会都拐弯, 也不会都不拐 (撞在一起)。
     */
    public static boolean drawsArc(JSONArray edges, String from, String to) {
        if (!isReversePair(edges, from, to)) return false;
        return to.compareTo(from) < 0;
    }

    /** byNode 里取某节点的最近实测 (score/hit/tpl/act) — 取不到返回 null */
    public static JSONObject recent(JSONObject byNode, String id) {
        if (byNode == null || id == null || id.length() == 0) return null;
        return byNode.optJSONObject(id);
    }

    /** 阈值/取值那一行的文字 (与网页版 thText 同口径): 有 val 显示"阈 x", 否则退回类别 */
    public static String thText(JSONObject node) {
        if (node == null) return "";
        if (!node.has("val") || node.isNull("val")) return node.optString("kind", "");
        String v = String.valueOf(node.opt("val"));
        if (v.length() == 0 || "null".equals(v)) return node.optString("kind", "");
        return "阈 " + v;
    }

    /** 节点第 2 行: 最近实测分数 + 命中与否 (没有实测返回 "-") */
    public static String scoreText(JSONObject recent) {
        if (recent == null) return "-";
        double s = recent.optDouble("score", -3);
        if (s <= -2) return "-";
        return String.format(java.util.Locale.US, "%.3f", s)
                + (recent.optBoolean("hit", false) ? " / " + firstNonEmpty(recent.optString("act", ""), "命中")
                                                   : " 未过阈");
    }

    private static String firstNonEmpty(String a, String b) {
        return a == null || a.length() == 0 ? b : a;
    }

    /** 节点详情一行 (点击后展示; 口径与网页版 info 栏一致) */
    public static String detail(JSONObject node, JSONObject recent) {
        if (node == null) return "";
        StringBuilder sb = new StringBuilder();
        sb.append(node.optString("label", node.optString("id", "?")));
        String cond = node.optString("cond", "");
        if (cond.length() > 0) sb.append(" — ").append(cond);
        String path = node.has("of") ? node.optString("of", "") : node.optString("cfg", "");
        if (path.length() > 0) {
            String v = node.has("val") && !node.isNull("val") ? String.valueOf(node.opt("val")) : "空";
            sb.append("  [").append(path).append(" = ").append(v).append(']');
        } else if (node.optString("kind", "").length() > 0) {
            sb.append("  (类别 ").append(node.optString("kind", "")).append(", 无阈值)");
        }
        if (recent != null && recent.optDouble("score", -3) > -2)
            sb.append(" · 最近实测 ").append(scoreText(recent))
              .append(recent.optString("tpl", "").length() > 0
                      ? " (" + recent.optString("tpl", "") + ")" : "");
        else sb.append(" · 最近无实测记录");
        String note = node.optString("note", "");
        if (note.length() > 0) sb.append("  · ").append(note);
        return sb.toString();
    }
}
