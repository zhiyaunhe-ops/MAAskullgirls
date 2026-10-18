package com.zhiyaunhe.sgmbot;

import android.graphics.Bitmap;

import java.util.ArrayList;
import java.util.List;

/**
 * PF hub 轮播扫描器 —— 移植 PC {@code pf_nav.PfNav.scan_arenas} 的**骨架**。
 *
 * <h3>它做什么</h3>
 * 先探明轮播的滑动方向, 走到一端, 再反向逐"卡位"截一处指纹 (居中卡区域缩略图),
 * 屏幕不再变化即到头。返回每个卡位的指纹。
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

    /**
     * "画面有没有真的动"的阈值 (96x48 灰度平均绝对差)。
     * 实测: 静止帧 0.3~0.84 / 真实换卡 23.78 ⇒ 取 3.0。
     */
    private static final double STILL_DIFF = 3.0;

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
     * <h3>⚠️⚠️ 2026-10-18 实测重写 (原版把 5 张数成 7 张)</h3>
     * 用户指出「事实上只有 5 张」。实测复现: 连续 8 帧横扫, **整帧 96x48 灰度平均绝对差只有
     * 0.3~0.84** —— 也就是说画面根本没动 (滑到端了), 但旧实现照样每帧记一张卡: 它的判重
     * 只跟**上一张**比, 而指纹区含**实时分数数字 + 卡面动画**, 静止帧之间也超阈值
     * (真实换卡的差异是 **23.78**, 两个分布差 30 倍)。
     *
     * <p>现在的三条闸:
     * <ol>
     *   <li><b>位移闸</b>: 每次滑完先比整帧, 差异 &lt; {@link #STILL_DIFF} 就判"到头", 不进下一格;</li>
     *   <li><b>全局判重</b>: 与**已记录的每一张**比, 不是只比上一张;</li>
     *   <li><b>方向自检</b>: 不假设滑动方向。实测"手指右滑反而让列表前进", 与直觉相反 ⇒
     *       每次先试一个方向, 画面不动就反过来 (一次 swipe 的成本远低于记错方向)。</li>
     * </ol></p>
     *
     * <h3>⚠️ 结果仍只是"卡位"不是"卡数"</h3>
     * 每滑一屏记一个位置。轮播不吸附 (滑动距离与卡宽不严格相等) 时, 同一张卡可能落在
     * 两格里 ⇒ 数出来是**上限**。**权威的"今天开哪些场"来自 {@link PfRotation}** (站点
     * 那张表的轮换公式), 这里的数只用来"人眼对一下今天几张"。
     *
     * @return 每个卡位的指纹 (可能为空: 截屏失败); 顺序 = 扫描方向
     */
    static List<Card> scan(ShizukuCtl sh, Bitmap firstFrame) {
        List<Card> out = new ArrayList<>();
        Bitmap b = firstFrame;
        if (b == null) b = sh.capture();
        if (b == null) return out;

        /* ① 定方向: 先试"手指向左", 画面不动就反过来。 */
        int dir = 0;
        for (int sign : new int[]{-1, 1}) {
            Bitmap before = b;
            sh.swipeWork(b, SLIDE_CX, SLIDE_Y, SLIDE_CX + sign * SLIDE_DX, SLIDE_Y, 420);
            PfFlow.sleep_(900);
            Bitmap nb = sh.capture();
            if (nb == null) return out;
            b = nb;
            if (moved(before, b)) { dir = sign; break; }
        }
        if (dir == 0) {
            /* 左右都不动 ⇒ hub 只有一屏 (或轮播没做), 记下当前这一格就结束, 别硬凑数。 */
            int[] t0 = thumb(b);
            if (t0 != null) out.add(new Card(0, t0));
            SgmLog.i("pf", "场地扫描: 左右滑都不改变画面 ⇒ hub 只有一屏 (记 1 个卡位)");
            return out;
        }

        /* ② 先走到一端 (同一方向滑到画面不再变化)。 */
        for (int i = 0; i < MAX_CARDS; i++) {
            Bitmap before = b;
            sh.swipeWork(b, SLIDE_CX, SLIDE_Y, SLIDE_CX + dir * SLIDE_DX, SLIDE_Y, 420);
            PfFlow.sleep_(700);
            Bitmap nb = sh.capture();
            if (nb == null) break;
            b = nb;
            if (!moved(before, b)) break;
        }

        /* ③ 反向逐个卡位记录 (全局判重)。 */
        dir = -dir;
        for (int i = 0; i < MAX_CARDS; i++) {
            int[] th = thumb(b);
            if (th == null) break;
            Card c = new Card(out.size(), th);
            boolean dup = false;
            for (Card o : out) if (c.sameAs(o)) { dup = true; break; }
            if (!dup) out.add(c);
            Bitmap before = b;
            sh.swipeWork(b, SLIDE_CX, SLIDE_Y, SLIDE_CX + dir * SLIDE_DX, SLIDE_Y, 420);
            PfFlow.sleep_(900);
            Bitmap nb = sh.capture();
            if (nb == null) break;
            b = nb;
            if (!moved(before, b)) break;          // 到另一端
        }
        return out;
    }

    /**
     * 两次截屏之间画面是否真的动了 (96x48 灰度平均绝对差 &gt; {@link #STILL_DIFF})。
     *
     * <p>实测分布: 静止 0.3~0.84 / 真实换卡 23.78 ⇒ 3.0 这个阈值有 3.5 倍余量。</p>
     */
    private static boolean moved(Bitmap a, Bitmap c) {
        int[] x = sig(a), y = sig(c);
        if (x == null || y == null) return true;      // 算不出就当动了 (宁可多扫一格)
        long sum = 0;
        for (int i = 0; i < x.length; i++) sum += Math.abs(x[i] - y[i]);
        return sum / (double) x.length > STILL_DIFF;
    }

    /** 96x48 灰度签名 (全屏; 判"有没有位移"用, 与卡位指纹不是一回事)。 */
    private static int[] sig(Bitmap bmp) {
        try {
            Bitmap s = Bitmap.createScaledBitmap(bmp, 96, 48, true);
            int[] p = new int[96 * 48];
            s.getPixels(p, 0, 96, 0, 0, 96, 48);
            if (s != bmp) s.recycle();
            for (int i = 0; i < p.length; i++) {
                int v = p[i], r = (v >> 16) & 0xFF, g = (v >> 8) & 0xFF, bl = v & 0xFF;
                p[i] = (r * 299 + g * 587 + bl * 114) / 1000;
            }
            return p;
        } catch (Throwable t) {
            return null;
        }
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
