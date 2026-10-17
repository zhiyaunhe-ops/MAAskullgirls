package com.zhiyaunhe.sgmbot;

import android.graphics.Bitmap;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 「场次锁定」—— 保证 bot 一直打的是**同一场**, 不会掉到别的场次里。
 *
 * <h3>要解决的问题 (2026-10-16 用户: 「为啥会掉出来…掉到别的场次里了」)</h3>
 * 老实现进 PF hub 后**直接点当时居中的那张卡**上的 PLAY!, 完全不看它是哪一场。
 * 而看门狗的脱困第 2/3 级会把游戏**拉回大厅再重进 PF hub** —— 重进之后轮播位置
 * 很可能变了 ⇒ 进去的就是**另一个场次**。日志实锤:
 * <pre>
 * 21:04:28 看门狗: 125s 无进展 — 脱困第 2 级: 重拉 Skullgirls
 * 21:04:47 在主大厅 — 点 PRIZE FIGHTS 进入 PF
 * 21:04:51 PF hub 就绪 — 点 PLAY! 进场      ← 盲点居中卡
 * </pre>
 *
 * <h3>为什么不用 OCR</h3>
 * 读卡名要 OCR (数字都没做完, 整串英文更难)。但"锁定同一场"其实**不需要知道它叫什么**:
 * 把目标场次的**居中卡画面**存成一个指纹, 之后每次进 hub 拿当前居中卡跟指纹比,
 * 不像就左右滑轮播找回来。纯模板匹配, 手机端现成能力。
 *
 * <h3>指纹怎么来</h3>
 * 第一次进 hub 且还没指纹时, 把**当前居中卡**存下来当目标 —— 这条与
 * {@link BotService#MODE_NAV_HERE} 是同一个前提: **人已经停在想要的那一场上**。
 * 想换场次就删掉指纹文件 (或换一张卡再让它重新学)。
 */
final class PfArena {

    /** 居中卡的指纹区 (work 坐标)。取自 hub 实测: 选中卡带粉框, 上方是封面+标题。 */
    static final int[] CARD = {516, 88, 306, 252};

    /** 指纹搜索区 = CARD 四周各留 24px 余量 (容忍一两像素的抖动)。 */
    private static final int PAD = 24;

    /**
     * 轮播滑动。⚠️ y 取 250 (卡面中部): 太高会摸到顶栏 HUD, 太低会按到 PLAY!/REWARDS
     * 那一列按钮上 —— 那两个是**有副作用的**(直接进场 / 开奖励页)。
     */
    private static final int SLIDE_Y = 250;
    private static final int SLIDE_X0 = 640;
    private static final int SLIDE_DX = 300;

    /** 最多滑几次去找目标卡 (左右各试)。多了说明指纹过期/卡面变了, 该报错而不是瞎转。 */
    private static final int MAX_SLIDES = 4;

    /** 认得出来就算同一场 (指纹是同一块区域裁的, 正常 ≥0.9; 不同场 ≤0.5)。 */
    private static final double TH = 0.72;

    private final PfFlow flow;
    private final ShizukuCtl sh;

    PfArena(PfFlow flow, ShizukuCtl sh) {
        this.flow = flow;
        this.sh = sh;
    }

    private File file() {
        return targetFile();
    }

    /** 指纹文件 (静态版 —— PF 设置页/其它无实例的地方也要用)。 */
    static File targetFile() {
        return new File(Paths.root(), "arena_target.png");
    }

    /**
     * 有没有可用的指纹。
     *
     * <p>⚠️ 还要**校验尺寸** —— 老版本存的是实机尺寸的裁剪块 (574x473), 与现在的 work 基准
     * 指纹 (306x252) 不同坐标系, 留着只会让匹配恒 0.00。尺寸不对就当作过期, 丢掉重学,
     * 这样装机后不用手动删文件。
     */
    boolean hasTarget() {
        File f = file();
        if (!f.isFile() || f.length() <= 1000) return false;
        try {
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            if (o.outWidth != CARD[2] || o.outHeight != CARD[3]) {
                SgmLog.i("pf", "场次锁定: 旧格式指纹 " + o.outWidth + "x" + o.outHeight
                        + " 与 work 基准 " + CARD[2] + "x" + CARD[3] + " 不符 — 丢弃重学");
                f.delete();
                return false;
            }
        } catch (Throwable ignored) { }
        return true;
    }

    /** 忘掉目标 (换场次用)。 */
    void forget() {
        try { file().delete(); } catch (Throwable ignored) { }
        SgmLog.i("pf", "场次锁定: 已清除目标指纹");
    }

    /**
     * 在 hub 上确认"现在居中的就是目标场次"; 不是就滑轮播找回来。
     *
     * @return true = 现在居中卡就是目标 (可以放心点 PLAY!);
     *         false = 没找到 —— **上层绝不能再点 PLAY!** (宁可不打, 也不能进错场)
     *
     * <p>⚠️⚠️ 2026-10-17 用户: 「你这个场次, 不需要存了, 只记录现在有的场次」——
     * 指纹那套 (App 内记住/清除按钮 + 这里的首次自动学习) **整体不再使用**:
     * 主页 PF 配置只从场次表选场次 (写 config 的 pf.arena 等), 不再截图标定。
     * 所以**没有指纹文件时直接放行** (按卡位盲点居中卡), 不再自动 learn 存盘。
     * 只有当用户自己往 {@code arena_target.png} 放了文件 (或老机器上有残留) 时才核对。 */
    boolean ensureTarget(Bitmap bmp) {
        if (disabled) return true;                 // 已关掉锁定 ⇒ 直接放行 (见 MAX_FAILS 注释)
        if (!hasTarget()) return true;             // 没有指纹 = 不锁定 (见上面注释)
        Vision.Frame w = Vision.toWork(bmp);
        double s = score(w);
        if (s >= TH) {
            failCount = 0;                         // 找到了就清零
            flow.log("场次锁定: 居中卡就是目标场 (" + fmt(s) + ")");
            return true;
        }
        flow.log("场次锁定: 居中卡不是目标场 (" + fmt(s) + ") — 滑轮播找回");
        for (int i = 0; i < MAX_SLIDES; i++) {
            boolean left = (i % 2 == 0);
            flow.swipe(bmp, SLIDE_X0, SLIDE_Y,
                    left ? SLIDE_X0 - SLIDE_DX : SLIDE_X0 + SLIDE_DX, SLIDE_Y, 420);
            PfFlow.sleep_(1200);
            Bitmap b2 = sh.capture();
            if (b2 == null) continue;
            bmp = b2;
            double s2 = score(Vision.toWork(b2));
            flow.log("场次锁定: 滑动 " + (i + 1) + " 次 (" + (left ? "左" : "右")
                    + ") 后匹配 " + fmt(s2));
            if (s2 >= TH) {
                failCount = 0;
                flow.log("场次锁定: 找回目标场次");
                return true;
            }
        }
        flow.log("⚠ 场次锁定: 滑了 " + MAX_SLIDES + " 次仍没找到目标场次 — 不进场"
                + " (宁可漏跑也不错跑; 想换场次请删掉 " + file().getAbsolutePath() + ")");
        /* ⚠️⚠️ 必须**有放弃上限** (2026-10-16 实栽): 指纹一旦是坏的, 这里每次返回 false
         * ⇒ 上层"退回导航重试" ⇒ 再进 hub ⇒ 再滑 4 次 ⇒ 又 false …**无限循环**,
         * 实测连刷 169 次、一场没打。失败满 MAX_FAILS 次就**本次运行关掉锁定**并放行,
         * 宁可这次打错场次, 也不能把挂机卡死。 */
        if (++failCount >= MAX_FAILS) {
            disabled = true;
            flow.log("⚠ 场次锁定连续失败 " + failCount + " 次 — **本次运行关闭锁定**放行"
                    + " (先保证能打上; 下次运行会重新学习指纹)");
            return true;
        }
        return false;
    }

    /** 连续几次找回失败就本运行关闭锁定 (见 ensureTarget 注释)。 */
    private static final int MAX_FAILS = 3;

    private int failCount;
    private boolean disabled;

    /** 本次运行是否已因连续失败而关闭锁定。 */
    boolean disabled() { return disabled; }

    /** 当前居中卡与指纹的相似度 (0~1)。 */
    double score(Vision.Frame w) {
        Vision.Frame t = fingerprint();
        if (t == null) return 0;
        int[] roi = {Math.max(0, CARD[0] - PAD), Math.max(0, CARD[1] - PAD),
                CARD[2] + PAD * 2, CARD[3] + PAD * 2};
        /* ⚠️ 传 0.0 会命中 findScored 的"早退" ⇒ 拿到 ROI 左上角附近第一个非负补丁的分,
         * 与真实相似度无关 ⇒ 场次判据随机失效 (2026-10-17 与倍率同根因)。 */
        double[] r = Vision.findScored(w, t, roi, Vision.SCAN_ALL, -1, 0, 1);
        /* ⚠️⚠️ 取 **r[2]** 不是 r[0]!  `findScored` 返回的是 **{x, y, best}** ——
         * 第一版写成 r[0] 取到了 **x 坐标**, 于是日志打出「居中卡就是目标场 (494.00)」
         * 这种不可能的分值 (分数恒在 0~1), 且 494 >= 0.72 ⇒ **永远判"就是目标场"**
         * ⇒ 场次锁定形同虚设, 还掩盖了真正的错场 —— 用户看到的正是这种
         * 「选人 ↔ hub」来回跳。 */
        return r == null ? 0 : r[2];
    }

    /** 读指纹 PNG → Frame。⚠️ **不能走 {@link Vision#toWork}** —— 见 learn() 的注释。 */
    private Vision.Frame fingerprint() {
        try {
            Bitmap b = android.graphics.BitmapFactory.decodeFile(file().getAbsolutePath());
            if (b == null) return null;
            int w = b.getWidth(), h = b.getHeight();
            int[] px = new int[w * h];
            b.getPixels(px, 0, w, 0, 0, w, h);
            return new Vision.Frame(px, w, h);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 把当前居中卡存成目标指纹 (**work 基准**)。
     *
     * <p>⚠️⚠️ 2026-10-16 实栽, 一度让 bot 在 hub 无限循环: 第一版直接裁实机帧存盘,
     * 读回来又过了一遍 {@link Vision#toWork} —— 那函数会**把图缩放到高 576**, 而指纹是
     * 574x473 的裁剪块 ⇒ 被拉成 699x576, 比搜索区(354x300)还大 ⇒ 匹配恒 0.00
     * ⇒ "不是目标场" ⇒ 滑轮播 4 次 ⇒ 不进场 ⇒ 回导航 ⇒ 再进 hub ⇒ 又 0.00 …循环 169 次。
     * 修法: **先把帧转成 work 基准再裁**, 存的就是 work 尺寸; 读回来**原样**包成 Frame,
     * 绝不再做任何缩放。指纹和搜索区从此同坐标系。
     */
    private boolean learn(Bitmap bmp) {
        return remember(bmp);
    }

    /**
     * 把当前帧的居中卡存成目标指纹 —— 抽成**包级静态**是为了让 App 内的
     * 「PF 设置」页也能手动记一次 (用户换场次时不必先去跑一轮让 bot 自己学)。
     * 口径与实例 {@code learn()} 完全一致 (见上面的移植踩坑注释)。
     */
    static boolean remember(Bitmap bmp) {
        try {
            Vision.Frame wf = Vision.toWork(bmp);        // 实机 → work(1280x576)
            int x0 = CARD[0], y0 = CARD[1], cw = CARD[2], ch = CARD[3];
            if (x0 + cw > wf.w || y0 + ch > wf.h) return false;
            int[] px = new int[cw * ch];
            for (int y = 0; y < ch; y++) {
                System.arraycopy(wf.px, (y0 + y) * wf.w + x0, px, y * cw, cw);
            }
            Bitmap crop = Bitmap.createBitmap(px, cw, ch, Bitmap.Config.ARGB_8888);
            FileOutputStream fo = new FileOutputStream(targetFile());
            crop.compress(Bitmap.CompressFormat.PNG, 100, fo);
            fo.close();
            SgmLog.i("pf", "场次锁定: 指纹已存 " + cw + "x" + ch + " (work 基准) → "
                    + targetFile().getAbsolutePath());
            return true;
        } catch (Throwable t) {
            SgmLog.i("pf", "场次锁定: 存指纹失败 " + t);
            return false;
        }
    }

    /** 删掉指纹 (= 解除场次锁定; PC/WebUI 那句"想换场次请删掉 arena_target.png")。 */
    static boolean forgetTarget() {
        try { return !targetFile().exists() || targetFile().delete(); } catch (Throwable t) { return false; }
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.US, "%.2f", v);
    }
}
