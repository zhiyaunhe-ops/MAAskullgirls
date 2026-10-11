package com.zhiyaunhe.sgmbot;

import java.util.ArrayList;
import java.util.List;

/**
 * work 坐标系上的落点几何 —— 把「要点的槽」+「必须绕开的挡块」算成一个可点坐标。
 *
 * <p><b>为什么有它 (2026-10-11 用户报障「你点的位置不对, 会点到 x3 那里」)</b>：
 * 快速连点模式的缺省落点 = 结算按钮行**中槽中心** (work 640,512)。
 * 而**战斗界面**的 1x/3x 速度泡 K70 真机实测占 work x608~672 / y450~518 ——
 * 中轴与落点完全重合 (它的下沿就是 y512), 于是盲点会一路点亮速度泡。
 * 速度泡与按钮行是两套独立 HUD (一个在战斗层, 一个在结算层), 各自都不能挪，
 * 所以**落点必须自己避开**。往右挪：中槽 x530~750, 结算按钮面板 x546~734,
 * 速度泡右沿 672 ⇒ 落点放到 672+gap 仍在按钮面板内, 且离两侧都还有余量。
 *
 * <p>y 方向挪不动：速度泡下沿 ~518、按钮面板下沿 ~528, 只剩 10px, 太紧。
 *
 * <p><b>为什么单独成类</b>：{@code tools/SlotProbe} 与 {@link SettleLoop} 必须给出
 * **同一个**落点, 否则又是「探针说没事 / 实机点到别处」。纯 {@code java.util},
 * 不碰 android, 两边都能编译进来共用。
 */
public final class WorkPoint {

    private WorkPoint() {}

    /**
     * 出厂挡块表 (work 坐标, 每项 {@code [x,y,w,h]}) —— 缺省值, config.fast.avoid 可覆盖。
     *
     * <p>唯一一条 = 战斗界面的速度泡。实测 (K70 2400x1080, work 1280x576)：
     * 模板 {@code battle_spd_1x} 在 wt3.png 上匹配 **1.000 @(610,452)** (60x60 是内圈),
     * 扫中轴列/横行得到实体范围 x608~672 / y450~518 ⇒ 四面各留 4px 余量得本项。
     */
    public static final int[][] DEFAULT_AVOID = {{604, 446, 72, 76}};

    /** 出厂安全间距 (work 像素)：落点距挡块边缘至少这么远 */
    public static final int DEFAULT_GAP = 24;

    /** 点是否落在矩形 {@code [x,y,w,h]} 内 (含边界) */
    public static boolean inside(int[] r, float x, float y) {
        return x >= r[0] && x <= r[0] + r[2] && y >= r[1] && y <= r[1] + r[3];
    }

    /** 点是否落在任一矩形内 */
    public static boolean inside(List<int[]> rects, float x, float y) {
        if (rects != null)
            for (int[] r : rects) if (inside(r, x, y)) return true;
        return false;
    }

    /** 命中的第一个矩形, 没有则 null */
    public static int[] firstHit(List<int[]> rects, float x, float y) {
        if (rects != null)
            for (int[] r : rects) if (inside(r, x, y)) return r;
        return null;
    }

    /** 状态码 → 人话 (日志用) */
    public static String describe(int state) {
        switch (state) {
            case 0: return "中槽中心即安全";
            case 1: return "已右移绕开挡块";
            case 2: return "已左移绕开挡块";
            default: return "槽内无处可挪, 仍在挡块里";
        }
    }

    /**
     * 在槽内取一个可点坐标, 并绕开挡块。
     *
     * <p>策略: 从槽中心出发, 若落在挡块里 ⇒ **优先右移** (挡块右沿 + gap), 右移出槽则左移;
     * 两边都放不下就退回中心 —— 宁可照原点, 也要让日志能抓到「绕不开」这件事,
     * 而不是悄悄点偏。
     *
     * @return {@code {x, y, state}}；state 见 {@link #describe}
     */
    public static float[] pick(int[] slot, List<int[]> avoid, int gap) {
        float cx = slot[0] + slot[2] / 2f, cy = slot[1] + slot[3] / 2f;
        if (avoid == null || avoid.isEmpty()) return new float[]{cx, cy, 0};
        float x = cx;
        /* 上限 4 轮: 挡块互相重叠时防来回弹 */
        for (int round = 0; round < 4; round++) {
            int[] hit = firstHit(avoid, x, cy);
            if (hit == null) break;
            float right = hit[0] + hit[2] + gap;
            float left = hit[0] - gap;
            if (inside(slot, right, cy)) { x = right; continue; }
            if (inside(slot, left, cy)) { x = left; continue; }
            return new float[]{cx, cy, 3};
        }
        if (Math.abs(x - cx) < 0.5f) return new float[]{cx, cy, 0};
        return new float[]{x, cy, x > cx ? 1 : 2};
    }

    /** 出厂挡块表的一份副本 (field 用, 免得被外部改到常量) */
    public static List<int[]> defaultAvoid() {
        List<int[]> l = new ArrayList<int[]>(DEFAULT_AVOID.length);
        for (int[] r : DEFAULT_AVOID) l.add(new int[]{r[0], r[1], r[2], r[3]});
        return l;
    }
}
