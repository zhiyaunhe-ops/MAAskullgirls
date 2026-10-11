package com.zhiyaunhe.sgmbot;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * 判定路径图 — **Canvas 自绘**, 不再是 WebView。
 *
 * <h3>为什么换掉 WebView (2026-10-11 用户原话: "别再跳转什么网页了 真的很丑")</h3>
 * 旧实现内嵌 WebView 打开 App 自己起的 http://127.0.0.1:8791/ —— 那是给 PC 用
 * `adb forward` 看的页面 (白底、浏览器排版、字号按 CSS px), 塞进手机屏幕后:
 *   · 字号按网页 px, 手机上小到看不清;
 *   · 想在图上缩放/拖动得靠浏览器手势, 和 App 里其它页的手感完全不一样;
 *   · 页面自己还有一排链接 (/status /log /config…) 一点就跳走, 回不来;
 *   · 手机端"取图"要绕一圈 HTTP (还在另一个线程), 图慢一拍。
 * 现在直接读同进程的 {@link Graph#toJson()} 画 —— 不跳页、手感一致、还快。
 * PC 侧的网页版**保留** (那边浏览器看着挺方便), 两条路共用同一个数据源,
 * 不会"图上写 0.72、实机是 0.75"。
 *
 * <h3>坐标系</h3>
 * 世界坐标 = 设计单位 (dp), 由 {@link GraphLayout} 给出。{@link #zoom} 是
 * "设计单位 → 屏幕 px"的比例, **已含 density** ⇒ 1:1 档时字号就是 13sp。
 */
public class GraphView extends View {

    /* ---- 配色 (与 App 其它页同一套; 刻意不跟系统深浅色, 深底上这些色看不清) ---- */
    private static final int BG = 0xFFF6F7F9;
    private static final int LANE_A = 0xFFFFFFFF, LANE_B = 0xFFF1F3F7;
    private static final int GRID = 0xFFE3E6EB;
    private static final int TXT = 0xFF1F2329, SUB = 0xFF6B7280;
    private static final int OK = 0xFF17964B, NO = 0xFFD93636;
    private static final int BORDER = 0xFFAAB2BD, CUR = 0xFFF59E0B;
    private static final int EDGE = 0xFF9AA4B2;

    /** 节点底色按 kind 分 (与网页版 KIND 同义, 补齐了几种) */
    private static int kindColor(String k) {
        if (k == null) return 0xFFEEF2F7;
        switch (k) {
            case "scan":   return 0xFFE0E7FF;
            case "match":  return 0xFFDCFCE7;
            case "tap":    return 0xFFFEF3C7;
            case "chain":  return 0xFFFCE7F3;
            case "heal":   return 0xFFFEE2E2;
            case "guard":  return 0xFFE5E7EB;
            case "roi":    return 0xFFEDE9FE;
            case "branch": return 0xFFCCFBF1;
            case "loop":   return 0xFFE0F2FE;
            case "count":  return 0xFFFFEDD5;
            default:       return 0xFFEEF2F7;
        }
    }

    private final float d;                       // density
    private float zoom;                          // 设计单位 → px (含 d)
    private float ox, oy;                        // 可视区左上角 (设计单位)
    private float minK, maxK;

    private JSONArray nodes = new JSONArray(), edges = new JSONArray();
    private JSONObject byNode = new JSONObject();
    private JSONArray lanes = new JSONArray();
    private String cursor = "";

    private final Paint bgP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lineP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint txtP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint smallP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Path path = new Path();

    private ScaleGestureDetector scale;
    private float lastX, lastY;
    private boolean dragging;
    private boolean moved;
    /** 首次拿到"当前节点"时自动跳过去一次 —— 之后用户自己拖过/缩过就不再抢镜头 */
    private boolean autoFocus = true;

    public interface OnPick { void onPick(JSONObject node, JSONObject recent); }
    private OnPick onPick;
    /** 手势/缩放变化回调 (给外面更新"百分比"显示) */
    private Runnable onViewChange;

    public GraphView(Context c) { this(c, null); }

    public GraphView(Context c, AttributeSet a) {
        super(c, a);
        d = c.getResources().getDisplayMetrics().density;
        zoom = d;                                // 1:1 (1 设计单位 = 1dp)
        minK = d * GraphLayout.K_MIN_FACTOR;
        maxK = d * GraphLayout.K_MAX_FACTOR;
        lineP.setStyle(Paint.Style.STROKE);
        lineP.setStrokeCap(Paint.Cap.ROUND);
        txtP.setColor(TXT); txtP.setTextAlign(Paint.Align.CENTER); txtP.setFakeBoldText(true);
        smallP.setTextAlign(Paint.Align.CENTER);
        scale = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector g) {
                applyZoom(zoom * g.getScaleFactor(), g.getFocusX(), g.getFocusY());
                return true;
            }
        });
        bgP.setColor(BG);
        setBackgroundColor(BG);
    }

    public void setOnPick(OnPick l) { this.onPick = l; }

    public void setOnViewChange(Runnable r) { this.onViewChange = r; }

    /** 装载一帧数据 —— 入参就是 {@link Graph#toJson()} 里那几段, 不再解析 HTML */
    public void setData(JSONArray nodes, JSONArray edges, JSONObject byNode,
                        String cursor, JSONArray lanes) {
        this.nodes = nodes == null ? new JSONArray() : nodes;
        this.edges = edges == null ? new JSONArray() : edges;
        this.byNode = byNode == null ? new JSONObject() : byNode;
        this.cursor = cursor == null ? "" : cursor;
        this.lanes = lanes == null ? new JSONArray() : lanes;
        clampView();
        /* 打开页面时「全图」档只有 20% 缩放 (真图 1817x950), 一团糊 —— 所以一旦知道
         * "引擎现在走到哪", 第一次就把镜头对过去 (1:1)。用户自己拖过就不再干预。 */
        if (autoFocus && this.cursor.length() > 0 && getWidth() > 0) {
            autoFocus = false;
            focusCursor();
            return;
        }
        invalidate();
    }

    public float zoomPct() { return zoom / d; }

    /* ---------------- 视口 ---------------- */

    /** 全图: 把整张图塞进视口 (缩放交给 GraphLayout.fit) */
    public void fitAll() {
        autoFocus = false;                       // 用户主动选了视角
        float[] f = GraphLayout.fit(nodes, getWidth(), getHeight(), 12 * d, minK);
        zoom = Math.max(minK, Math.min(maxK, f[0]));
        ox = f[1]; oy = f[2];
        clampView();
        invalidate();
        if (onViewChange != null) onViewChange.run();
    }

    /** 1:1 — 1 设计单位 = 1dp, 字号 13sp, 这个档才"看得清" */
    public void zoom1() {
        autoFocus = false;
        float cx = GraphLayout.wx(getWidth() / 2f, ox, zoom);
        float cy = GraphLayout.wy(getHeight() / 2f, oy, zoom);
        zoom = d;
        ox = cx - getWidth() / 2f / zoom;
        oy = cy - getHeight() / 2f / zoom;
        clampView();
        invalidate();
        if (onViewChange != null) onViewChange.run();
    }

    /**
     * 定位到当前节点 (切到 1:1) —— 真图 1817x950 设计单位, 手机上「全图」档只剩 20%
     * (13sp 的字缩成 2.6sp, 根本读不了), 所以"我现在走到哪"这个最常问的问题得有一键。
     * 没有当前节点 (引擎没跑) 时退化成 1:1。
     */
    public void focusCursor() {
        JSONObject n = node(cursor);
        if (n == null) { zoom1(); return; }
        zoom = d;
        ox = (float) n.optDouble("x", 0) - getWidth() / 2f / zoom;
        oy = (float) n.optDouble("y", 0) - getHeight() / 2f / zoom;
        clampView();
        invalidate();
        if (onViewChange != null) onViewChange.run();
    }

    /** 以某点为锚放大/缩小 (按钮用; 手势走 applyZoom) */
    public void zoomBy(float factor) {
        applyZoom(zoom * factor, getWidth() / 2f, getHeight() / 2f);
    }

    private void applyZoom(float nz, float fx, float fy) {
        autoFocus = false;                       // 用户动过缩放 = 别再自动抢镜头
        nz = Math.max(minK, Math.min(maxK, nz));
        /* 以手势焦点为锚: 焦点下的世界点缩放前后不动 —— 否则图会"跳走" */
        float wxp = GraphLayout.wx(fx, ox, zoom), wyp = GraphLayout.wy(fy, oy, zoom);
        zoom = nz;
        ox = wxp - fx / zoom;
        oy = wyp - fy / zoom;
        clampView();
        invalidate();
        if (onViewChange != null) onViewChange.run();
    }

    /** 平移夹取: 图不能被拖到完全出屏 (否则"图不见了"找不回来) */
    private void clampView() {
        float[] g = GraphLayout.clampRange(nodes, getWidth(), getHeight(), zoom, 60 * d);
        ox = Math.max(g[0], Math.min(g[1], ox));
        oy = Math.max(g[2], Math.min(g[3], oy));
    }

    /* ---------------- 触摸 ---------------- */

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        scale.onTouchEvent(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = e.getX(); lastY = e.getY(); dragging = true; moved = false;
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                moved = true;                    // 双指 = 缩放, 不要再当"点击"
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!scale.isInProgress() && dragging) {
                    float dx = e.getX() - lastX, dy = e.getY() - lastY;
                    if (Math.abs(dx) + Math.abs(dy) > 3 * d) { moved = true; autoFocus = false; }
                    ox -= dx / zoom; oy -= dy / zoom;
                    lastX = e.getX(); lastY = e.getY();
                    clampView();
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                dragging = false;
                if (!moved) pick(e.getX(), e.getY());
                return true;
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                return true;
        }
        return super.onTouchEvent(e);
    }

    private void pick(float px, float py) {
        if (onPick == null) return;
        String id = GraphLayout.hit(nodes, px, py, ox, oy, zoom);
        if (id.length() == 0) return;
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject n = nodes.optJSONObject(i);
            if (n != null && id.equals(n.optString("id", ""))) {
                onPick.onPick(n, GraphLayout.recent(byNode, id));
                return;
            }
        }
    }

    /* ---------------- 绘制 ---------------- */

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (ow == 0 && oh == 0 && w > 0) fitAll();     // 首次布局: 先给个全图
    }

    @Override
    protected void onDraw(Canvas cv) {
        super.onDraw(cv);
        if (nodes.length() == 0) {
            txtP.setColor(SUB);
            txtP.setTextSize(14 * d);
            txtP.setFakeBoldText(false);
            cv.drawText("图还没装载 — 去「编辑配置」改完点保存, 或回主页点「重载配置」",
                    getWidth() / 2f, getHeight() / 2f, txtP);
            return;
        }
        float k = zoom;
        /* ---- 泳道背景 (在屏幕坐标下画横带, 不受平移影响 x, 但 y 要跟着走) ---- */
        bgP.setColor(LANE_A);
        for (int i = 0; i < lanes.length(); i++) {
            JSONObject l = lanes.optJSONObject(i);
            if (l == null) continue;
            int lane = l.optInt("id", i);
            float y0 = GraphLayout.sy(GraphLayout.y(lane) - GraphLayout.DY / 2f, oy, k);
            float y1 = GraphLayout.sy(GraphLayout.y(lane) + GraphLayout.DY / 2f, oy, k);
            bgP.setColor(lane % 2 == 0 ? LANE_A : LANE_B);
            cv.drawRect(0, y0, getWidth(), y1, bgP);
            bgP.setColor(GRID);
            cv.drawRect(0, y1 - 1, getWidth(), y1, bgP);
        }

        cv.save();
        cv.translate(-ox * k, -oy * k);
        cv.scale(k, k);

        drawEdges(cv);
        drawNodes(cv);

        cv.restore();
    }

    private void drawEdges(Canvas cv) {
        for (int i = 0; i < edges.length(); i++) {
            JSONObject e = edges.optJSONObject(i);
            if (e == null) continue;
            JSONObject a = node(e.optString("from", "")), b = node(e.optString("to", ""));
            if (a == null || b == null) continue;
            float ax = (float) a.optDouble("x", 0), ay = (float) a.optDouble("y", 0);
            float bx = (float) b.optDouble("x", 0), by = (float) b.optDouble("y", 0);
            float[] p = GraphLayout.edgePoints(ax, ay, bx, by);
            if (p == null) continue;

            boolean arc = GraphLayout.drawsArc(edges, e.optString("from", ""), e.optString("to", ""));
            path.reset();
            path.moveTo(p[0], p[1]);
            float mx, my, ang;
            if (!arc) {
                path.lineTo(p[2], p[3]);
                mx = (p[0] + p[2]) / 2f; my = (p[1] + p[3]) / 2f;
                ang = (float) Math.atan2(p[3] - p[1], p[2] - p[0]);
            } else {
                /* 反向边对: 一条走弧, 免得两条叠在一起 —— 中点沿法线推 26 单位 */
                float cx = (ax + bx) / 2f, cy = (ay + by) / 2f;
                float dx = bx - ax, dy = by - ay, L = (float) Math.sqrt(dx * dx + dy * dy);
                if (L == 0) continue;
                float qx = cx - dy / L * 26f, qy = cy + dx / L * 26f;
                path.quadTo(qx, qy, p[2], p[3]);
                mx = qx; my = qy;
                ang = (float) Math.atan2(p[3] - qy, p[2] - qx);
            }
            lineP.setColor(EDGE);
            lineP.setStrokeWidth(1.6f);
            cv.drawPath(path, lineP);

            /* 箭头 (贴在节点框边上) */
            float al = 9f, aw = 5f;
            path.reset();
            path.moveTo(p[2], p[3]);
            path.lineTo(p[2] - al * (float) Math.cos(ang) + aw * (float) Math.sin(ang),
                        p[3] - al * (float) Math.sin(ang) - aw * (float) Math.cos(ang));
            path.lineTo(p[2] - al * (float) Math.cos(ang) - aw * (float) Math.sin(ang),
                        p[3] - al * (float) Math.sin(ang) + aw * (float) Math.cos(ang));
            path.close();
            fillP.setColor(EDGE);
            cv.drawPath(path, fillP);

            String on = e.optString("on", "");
            if (on.length() > 0) {
                smallP.setColor(SUB);
                smallP.setTextSize(10f);
                cv.drawText(on, mx, my - 3f, smallP);
            }
        }
    }

    private void drawNodes(Canvas cv) {
        long now = System.currentTimeMillis();
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject n = nodes.optJSONObject(i);
            if (n == null) continue;
            float cx = (float) n.optDouble("x", 0), cy = (float) n.optDouble("y", 0);
            r.set(cx - GraphLayout.NW / 2f, cy - GraphLayout.NH / 2f,
                  cx + GraphLayout.NW / 2f, cy + GraphLayout.NH / 2f);

            String id = n.optString("id", "");
            boolean isCur = id.equals(cursor);
            fillP.setColor(kindColor(n.optString("kind", "")));
            cv.drawRoundRect(r, 9, 9, fillP);

            /* 当前节点: 橙框 + 呼吸 (不常驻动画线程, 用毫秒算 alpha 即可) */
            if (isCur) {
                int a = 140 + (int) (115 * (0.5 + 0.5 * Math.sin(now / 320.0)));
                lineP.setStrokeWidth(3f);
                lineP.setColor((a << 24) | (CUR & 0xFFFFFF));
                cv.drawRoundRect(r, 9, 9, lineP);
            }
            lineP.setStrokeWidth(1.5f);
            lineP.setColor(BORDER);
            cv.drawRoundRect(r, 9, 9, lineP);

            txtP.setColor(TXT);
            txtP.setTextSize(13f);
            txtP.setFakeBoldText(true);
            cv.drawText(n.optString("label", id), cx, cy - 4f, txtP);

            JSONObject rc = GraphLayout.recent(byNode, id);
            boolean hit = rc != null && rc.optBoolean("hit", false);
            smallP.setColor(rc == null ? SUB : (hit ? OK : NO));
            smallP.setTextSize(11f);
            cv.drawText(GraphLayout.scoreText(rc), cx, cy + 14f, smallP);

            smallP.setColor(SUB);
            smallP.setTextSize(10f);
            cv.drawText(GraphLayout.thText(n), cx, cy + 26f, smallP);
        }
        /* 当前节点的呼吸灯 —— 只在本 view 真的可见时重绘, 别在后台白烧电 */
        if (isShown() && cursor.length() > 0) postInvalidateDelayed(120);
    }

    private JSONObject node(String id) {
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject n = nodes.optJSONObject(i);
            if (n != null && id.equals(n.optString("id", ""))) return n;
        }
        return null;
    }

    /** 某个节点在屏幕上的矩形 (调试/探针用) */
    String screenRectOf(String id) {
        JSONObject n = node(id);
        if (n == null) return "";
        float cx = (float) n.optDouble("x", 0), cy = (float) n.optDouble("y", 0);
        return String.format(Locale.US, "%.1f,%.1f,%.1f,%.1f",
                GraphLayout.sx(cx - GraphLayout.NW / 2f, ox, zoom),
                GraphLayout.sy(cy - GraphLayout.NH / 2f, oy, zoom),
                GraphLayout.sx(cx + GraphLayout.NW / 2f, ox, zoom),
                GraphLayout.sy(cy + GraphLayout.NH / 2f, oy, zoom));
    }
}
