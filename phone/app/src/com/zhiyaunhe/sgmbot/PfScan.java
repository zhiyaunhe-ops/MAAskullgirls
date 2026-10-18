package com.zhiyaunhe.sgmbot;

import android.graphics.Bitmap;

import java.util.ArrayList;
import java.util.List;

/**
 * PF hub 轮播扫描器 —— 移植 PC {@code pf_nav.PfNav.scan_arenas} 的**骨架**。
 *
 * <h3>它做什么</h3>
 * 先右滑到最左, 再逐卡左滑, 每张卡截一处"卡面指纹"(居中卡区域缩略图), 直到某一张
 * 与上一张相同 (轮播到头) 或到 {@link #MAX_CARDS} 上限。返回每张卡的序号 + 指纹。
 *
 * <h3>⚠️ 它**不**读场次名</h3>
 * PC 端 {@code read_center_card()} 靠 OCR 读标题 + SCORE 行, 再拿人工表把卡名映射成
 * tag/条件。**App 端没有 OCR**(手工构建接不了 ML Kit 的 5 个 AAR), 所以这里只能给出
 * "hub 上现在有几张卡、每张长什么样", 给不出名字。
 * ⇒ 场次仍由用户在主页从「当前有效场次表」里选; 扫描的价值是**先让人看见今天到底几张**,
 *    以及"同一张卡"的判重 (扫描尽头 / 后续接 OCR 时的锚点)。
 *
 * <h3>和导航的关系 (2026-10-18 用户: 「把 navigate 和扫描加到一起」)</h3>
 * 不再分两步: {@link PfFlow#gotoHub} 一到 hub 就自动扫一遍 (见 PfFlow.run 的调用点),
 * 日志/状态里直接能看到"今日场地 N 张"。
 */
final class PfScan {

    /** 居中卡指纹区 (work 坐标) —— 与 {@link PfArena#CARD} 同一块, 复用实测值。 */
    private static final int[] CARD = PfArena.CARD;

    /** 缩略图尺寸 (判重用; 越小越快, 24x20 对"同图/异图"的区分已经足够)。 */
    private static final int TW = 24, TH = 20;

    /** 轮播最多几张 (PC 默认 10)。 */
    private static final int MAX_CARDS = 10;

    /** 平均绝对差 < 此值 ⇒ 同一张卡 (0~255 灰度尺度)。实测同图 ~2, 异图 ~40+。 */
    private static final double SAME_DIFF = 8;

    /** hub 轮播滑动参数 —— 与 PfArena 同口径 (y=250 避开 PLAY!/顶栏 HUD)。 */
    private static final int SLIDE_Y = 250, SLIDE_CX = 640, SLIDE_DX = 300;

    /** 一张卡的扫描结果。 */
    static final class Card {
        final int idx;              // 0 = 最左
        final int[] thumb;          // 缩略灰度 (TW*TH)
        Card(int idx, int[] thumb) { this.idx = idx; this.thumb = thumb; }

        /** 与另一张是否同一张卡 (平均绝对差)。 */
        boolean sameAs(Card o) {
            if (o == null || o.thumb == null || thumb == null) return false;
            long sum = 0;
            for (int i = 0; i < thumb.length; i++) sum += Math.abs(thumb[i] - o.thumb[i]);
            return sum / (double) thumb.length < SAME_DIFF;
        }
    }

    private PfScan() { }

    /**
     * 扫一遍 hub 轮播。**会动屏幕** (左右滑), 只在 hub 页调用。
     *
     * @return 每张卡的指纹列表 (可能为空: 截屏失败); 顺序 = 从左到右
     */
    static List<Card> scan(ShizukuCtl sh, Bitmap firstFrame) {
        List<Card> out = new ArrayList<>();
        Bitmap b = firstFrame;
        if (b == null) b = sh.capture();
        if (b == null) return out;

        /* ① 先滑到最左: 右滑 MAX_CARDS 次 (到底再滑不会动, 无副作用)。 */
        for (int i = 0; i < MAX_CARDS; i++) {
            sh.swipeWork(b, SLIDE_CX, SLIDE_Y, SLIDE_CX + SLIDE_DX, SLIDE_Y, 420);
            PfFlow.sleep_(320);
            Bitmap nb = sh.capture();
            if (nb != null) b = nb;
        }
        PfFlow.sleep_(600);

        /* ② 逐卡左滑读取 + 判到头。 */
        Card prev = null;
        for (int i = 0; i < MAX_CARDS; i++) {
            b = sh.capture();
            if (b == null) break;
            Card c = new Card(i, thumb(b));
            if (c.thumb == null) break;
            if (prev != null && c.sameAs(prev)) {
                SgmLog.i("pf", "场地扫描: 第 " + (i + 1) + " 张与上一张相同 ⇒ 轮播到头");
                break;
            }
            out.add(c);
            prev = c;
            sh.swipeWork(b, SLIDE_CX, SLIDE_Y, SLIDE_CX - SLIDE_DX, SLIDE_Y, 420);
            PfFlow.sleep_(1000);
        }
        return out;
    }

    /** 截居中卡区域 → 灰度缩略 (work 坐标; 越界返回 null)。 */
    private static int[] thumb(Bitmap bmp) {
        try {
            Vision.Frame w = Vision.toWork(bmp);
            int x0 = CARD[0], y0 = CARD[1], cw = CARD[2], ch = CARD[3];
            if (x0 + cw > w.w || y0 + ch > w.h) return null;
            int[] t = new int[TW * TH];
            for (int ty = 0; ty < TH; ty++) {
                int sy = y0 + (ty * ch) / TH + ch / (TH * 2);
                for (int tx = 0; tx < TW; tx++) {
                    int sx = x0 + (tx * cw) / TW + cw / (TW * 2);
                    int p = w.px[sy * w.w + sx];
                    int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, bl = p & 0xFF;
                    t[ty * TW + tx] = (r * 299 + g * 587 + bl * 114) / 1000;   // 灰度
                }
            }
            return t;
        } catch (Throwable t) {
            return null;
        }
    }

    /* ================= 落盘: 今日 + 历史 ================= */

    /**
     * 扫描结果落盘。
     * <ul>
     *   <li>{@code pf_scan.json} —— **今天**这一份 (主页"历史"弹层顶行显示);</li>
     *   <li>{@code pf_scan_history.jsonl} —— 按天追加一行 (同一天重复扫就覆盖当天那行),
     *       供「历史记录」回看每天 hub 上有几张 —— 2026-10-18 用户: 「之前的场次只在
     *       历史记录里可以回看」。</li>
     * </ul>
     * ⚠️ 只记**卡数**不记名字 (没有 OCR); 名字的历史看战绩页。
     */
    static void save(List<Card> cards) {
        try {
            if (Paths.root() == null) return;
            java.io.File dir = new java.io.File(Paths.root());
            if (!dir.exists()) dir.mkdirs();
            String day = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                    .format(new java.util.Date());
            String line = "{\"day\":\"" + day + "\",\"count\":" + cards.size()
                    + ",\"ts\":" + (System.currentTimeMillis() / 1000) + "}";

            java.io.File cur = new java.io.File(dir, "pf_scan.json");
            writeFile(cur, line);

            java.io.File his = new java.io.File(dir, "pf_scan_history.jsonl");
            StringBuilder sb = new StringBuilder();
            if (his.exists()) {
                java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(new java.io.FileInputStream(his), "UTF-8"));
                String ln;
                while ((ln = r.readLine()) != null) {
                    if (ln.trim().isEmpty()) continue;
                    if (ln.contains("\"day\":\"" + day + "\"")) continue;   // 当天旧记录丢掉
                    sb.append(ln).append('\n');
                }
                r.close();
            }
            sb.append(line).append('\n');
            writeFile(his, sb.toString());
        } catch (Throwable ignored) { }
    }

    /** 读历史 (最近 limit 天, 新的在前) —— 给主页「历史」弹层。 */
    static List<String[]> history(int limit) {
        List<String[]> out = new ArrayList<>();
        try {
            if (Paths.root() == null) return out;
            java.io.File his = new java.io.File(Paths.root(), "pf_scan_history.jsonl");
            if (!his.exists()) return out;
            List<String> lines = new ArrayList<>();
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(his), "UTF-8"));
            String ln;
            while ((ln = r.readLine()) != null) if (!ln.trim().isEmpty()) lines.add(ln.trim());
            r.close();
            for (int i = lines.size() - 1; i >= 0 && out.size() < limit; i--) {
                org.json.JSONObject o = new org.json.JSONObject(lines.get(i));
                out.add(new String[]{o.optString("day", "?"), String.valueOf(o.optInt("count", 0))});
            }
        } catch (Throwable ignored) { }
        return out;
    }

    private static void writeFile(java.io.File f, String content) throws Exception {
        java.io.FileOutputStream os = new java.io.FileOutputStream(f);
        os.write(content.getBytes("UTF-8"));
        os.close();
    }
}
