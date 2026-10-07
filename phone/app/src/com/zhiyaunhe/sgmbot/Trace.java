package com.zhiyaunhe.sgmbot;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * 判定流水 — "模型走的路径"的原材料。
 *
 * 每一次模板判定/点击/逃生都记一条: 节点 id、模板名、ROI、得分、命中与否、耗时。
 * 环形缓冲 (容量由 log.trace_size 配, 默认 200), 供
 *   - HTTP GET /graph (网页判定图高亮 + 最近流水)
 *   - state.json 快照 (PC 侧轮询)
 * 通关判据看的是"哪一跳没走通", 所以每条都带分数 —— 差 0.02 卡住的情形一眼可见。
 */
public final class Trace {
    private static final Object LOCK = new Object();
    private static ArrayDeque<JSONObject> buf = new ArrayDeque<>(200);
    private static int cap = 200;

    /** 每个循环开始按 config 重设容量 (改 config 即生效) */
    public static void configure(int size) {
        synchronized (LOCK) {
            cap = Math.max(20, Math.min(2000, size));
            while (buf.size() > cap) buf.pollFirst();
        }
    }

    public static void put(String node, String kind, String tpl, int[] roi,
                           double score, boolean hit, String action) {
        try {
            JSONObject o = new JSONObject()
                    .put("t", System.currentTimeMillis())
                    .put("node", node).put("kind", kind)
                    .put("tpl", tpl == null ? "" : tpl)
                    .put("roi", roi == null ? "" : roi[0] + "," + roi[1] + "," + roi[2] + "," + roi[3])
                    .put("score", Math.round(score * 1000) / 1000.0)
                    .put("hit", hit)
                    .put("act", action == null ? "" : action);
            synchronized (LOCK) {
                buf.addLast(o);
                while (buf.size() > cap) buf.pollFirst();
            }
        } catch (Exception ignored) { }
    }

    /** 最近 n 条 (时间正序) */
    public static JSONArray recent(int n) {
        JSONArray out = new JSONArray();
        synchronized (LOCK) {
            Iterator<JSONObject> it = buf.iterator();
            // 只要后 n 条
            int skip = Math.max(0, buf.size() - n);
            for (int i = 0; i < skip; i++) if (it.hasNext()) it.next();
            while (it.hasNext()) out.put(it.next());
        }
        return out;
    }

    /** 每个节点最近一次判定的分数/命中 (给图上标数字用) */
    public static JSONObject byNode() {
        JSONObject out = new JSONObject();
        synchronized (LOCK) {
            for (JSONObject o : buf) {
                try {
                    out.put(o.optString("node", "?"), o);
                } catch (Exception ignored) { }
            }
        }
        return out;
    }

    public static void clear() {
        synchronized (LOCK) { buf.clear(); }
    }
}
