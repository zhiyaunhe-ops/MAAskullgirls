package com.zhiyaunhe.sgmbot;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * 每日战绩热力图 — GitHub contributions 风格 (用户原话: "模仿 github 那种热力图")。
 *
 * 布局同 GitHub:
 *   列 = 周 (最左是 weeks 周前那一周, 最右是本周)
 *   行 = 周一..周日 (GitHub 行首是周日; 这里用**周一**为行首, 更符合国内习惯)
 *   每格 = 一天, 颜色 = 当天场数档次
 *   颜色分档 (可配 config.stats.levels): [1,3,6,11] → 5 档
 *     0 场 = 空槽 (浅灰); 1~2; 3~5; 6~10; 11+ 逐级加深
 *
 * ⚠️ 为什么不用 WebView 画: 热力图要能**点格子看明细**, WebView 里得再写一层
 *    JS↔Java 桥; 这个格子数 (53×7=371) 用 Canvas 画既够快 (一帧 <3ms) 又能
 *    直接拿触摸坐标查日期, 少一层桥就少一类"点了没反应"的故障。
 *
 * ⚠️ 颜色刻意**不跟随系统深浅色**: 深色模式下一片绿在暗底上看不清档位, 这里固定
 *    用 GitHub 那套浅底的绿; 底色跟着卡片走 (白)。
 */
public class HeatmapView extends View {

    /** 档次阈值 (上界): <1 / <3 / <6 / <11 / >=11 */
    private int[] levels = {1, 3, 6, 11};
    /** 5 档颜色 (0 场那一档是空槽色) */
    private static final int[] COLORS = {
            0xFFEBEDF0,   // 0 场
            0xFF9BE9A8,   // 1~2
            0xFF40C463,   // 3~5
            0xFF30A14E,   // 6~10
            0xFF216E39,   // 11+
    };

    private int weeks = 26;              // 默认展示半年 (小屏也看得清)
    private int cell = 0;                // 格子边长 (px, 按控件宽算)
    private int gap = 0;
    private int topPad = 0;

    /** 日期 → [rounds, wins, loses] */
    private LinkedHashMap<String, int[]> data = new LinkedHashMap<>();
    private final List<RectF> cells = new ArrayList<>();
    private final List<String> cellDates = new ArrayList<>();

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF();

    private OnDayClick listener;
    private int selCol = -1, selRow = -1;

    public interface OnDayClick { void onDay(String date, int[] v); }

    public HeatmapView(Context c) { super(c); init(); }

    public HeatmapView(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        tp.setTextSize(24f);
        tp.setColor(0xFF6B7280);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);   // 避免个别机型上圆角失效
    }

    /** 装载数据; weeks = 展示多少周 (含本周) */
    public void setData(LinkedHashMap<String, int[]> hist, int weeks, int[] lv) {
        this.data = hist == null ? new LinkedHashMap<String, int[]>() : hist;
        if (weeks >= 8 && weeks <= 60) this.weeks = weeks;
        if (lv != null && lv.length == 4) this.levels = lv;
        requestLayout();
        invalidate();
    }

    public void setOnDayClick(OnDayClick l) { this.listener = l; }

    /** 展示的日期范围 (给"共 N 天"这类文案用) */
    public String rangeText() {
        Calendar end = today0();
        Calendar start = (Calendar) end.clone();
        start.add(Calendar.DAY_OF_MONTH, -(weeks * 7 - 1));
        return dayStr(start) + " ~ " + dayStr(end);
    }

    /* ---------------- 测量 ---------------- */

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        int padH = 8;
        // 可用宽 = 左侧星期标签 + 格子区
        int labelW = 40;
        float each = (w - padH * 2 - labelW) / (float) weeks;
        gap = Math.max(2, (int) (each * 0.16f));
        cell = Math.max(6, (int) (each - gap));
        topPad = 46;                       // 顶部月份标签
        int gridH = 7 * (cell + gap) - gap;
        int h = topPad + gridH + 8;
        setMeasuredDimension(w, resolveSize(h, hSpec));
    }

    /* ---------------- 绘制 ---------------- */

    @Override
    protected void onDraw(Canvas cv) {
        super.onDraw(cv);
        cells.clear();
        cellDates.clear();

        int labelW = 40;
        int x0 = labelW;
        Calendar end = today0();
        // 让最右列 = 本周; 行首 = 周一
        Calendar start = (Calendar) end.clone();
        start.add(Calendar.DAY_OF_MONTH, -(weeks * 7 - 1));
        // 回退到所在周的周一
        while (mondayIdx(start) != 0) start.add(Calendar.DAY_OF_MONTH, -1);

        /* 顶部月份标签 (跨月时在该列上方写月份) */
        tp.setTextSize(cell * 0.9f + 10f);
        tp.setColor(0xFF6B7280);
        int lastMonth = -1;

        String selDate = selCol >= 0 ? dateAt(selCol, selRow) : null;

        for (int c = 0; c < weeks; c++) {
            Calendar colCal = (Calendar) start.clone();
            colCal.add(Calendar.DAY_OF_MONTH, c * 7);
            int month = colCal.get(Calendar.MONTH);
            if (month != lastMonth) {
                lastMonth = month;
                cv.drawText(monthName(month), x0 + c * (cell + gap), topPad - 12, tp);
            }
            for (int r = 0; r < 7; r++) {
                Calendar d = (Calendar) colCal.clone();
                d.add(Calendar.DAY_OF_MONTH, r);
                float left = x0 + c * (cell + gap);
                float top = topPad + r * (cell + gap);
                tmp.set(left, top, left + cell, top + cell);

                String key = dayStr(d);
                boolean future = d.after(end);
                int[] v = data.get(key);
                int n = v == null ? 0 : v[0];

                p.setColor(future ? 0x00000000 : COLORS[levelOf(n, levels)]);
                cv.drawRoundRect(tmp, cell * 0.22f, cell * 0.22f, p);

                /* 选中态描边 (空白格也要能看出选到哪) */
                if (key.equals(selDate)) {
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(Math.max(2f, cell * 0.14f));
                    p.setColor(0xFF1F2329);
                    cv.drawRoundRect(tmp, cell * 0.22f, cell * 0.22f, p);
                    p.setStyle(Paint.Style.FILL);
                }
                cells.add(new RectF(tmp));
                cellDates.add(key);
            }
        }

        /* 左侧星期标签 (只写周一/周三/周五, GitHub 同款) */
        tp.setTextSize(Math.max(20f, cell * 0.68f));
        tp.setColor(0xFF8A9099);
        String[] names = {"一", "二", "三", "四", "五", "六", "日"};
        for (int r = 0; r < 7; r++) {
            if (r % 2 != 0 && r != 6) continue;
            float yy = topPad + r * (cell + gap) + cell * 0.85f;
            cv.drawText(names[r], 2, yy, tp);
        }

        /* 右下角图例: 少 → 多 */
        int lgW = (cell + gap) * 5;
        float lx = getWidth() - 12 - lgW;
        float ly = getHeight() - cell - 6;
        tp.setTextSize(Math.max(20f, cell * 0.7f));
        tp.setColor(0xFF8A9099);
        cv.drawText("少", lx - cell * 2.2f, ly + cell * 0.85f, tp);
        for (int i = 0; i < 5; i++) {
            p.setColor(COLORS[i]);
            float left = lx + i * (cell + gap);
            tmp.set(left, ly, left + cell, ly + cell);
            cv.drawRoundRect(tmp, cell * 0.22f, cell * 0.22f, p);
        }
        cv.drawText("多", lx + lgW + 2, ly + cell * 0.85f, tp);
    }

    /* ---------------- 触摸 ---------------- */

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_UP && listener != null) {
            float x = e.getX(), y = e.getY();
            for (int i = 0; i < cells.size(); i++) {
                if (cells.get(i).contains(x, y)) {
                    selCol = i / 7;
                    selRow = i % 7;
                    String date = cellDates.get(i);
                    int[] v = data.get(date);
                    listener.onDay(date, v == null ? new int[3] : v);
                    invalidate();
                    return true;
                }
            }
        }
        return true;
    }

    /* ---------------- 小工具 ---------------- */

    /** 场数 → 档次 0..4 (0=空槽) */
    static int levelOf(int n, int[] levels) {
        if (n <= 0) return 0;
        if (n < levels[0]) return 1;
        if (n < levels[1]) return 2;
        if (n < levels[2]) return 3;
        return 4;
    }

    /** 该格的日期 (col,row) → "YYYY-MM-DD"; 越界返回 null */
    private String dateAt(int col, int row) {
        if (col < 0 || row < 0) return null;
        int i = col * 7 + row;
        return i < cellDates.size() ? cellDates.get(i) : null;
    }

    private static int mondayIdx(Calendar c) {
        int dow = c.get(Calendar.DAY_OF_WEEK);          // SUNDAY=1 .. SATURDAY=7
        return (dow + 5) % 7;                          // MONDAY→0 .. SUNDAY→6
    }

    private static Calendar today0() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c;
    }

    private static String dayStr(Calendar c) {
        return String.format(Locale.US, "%04d-%02d-%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    private static String monthName(int m) {
        return (m + 1) + "月";
    }
}
