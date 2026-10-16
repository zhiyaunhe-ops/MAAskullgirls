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
        return new File(Paths.root(), "arena_target.png");
    }

    boolean hasTarget() {
        File f = file();
        return f.isFile() && f.length() > 1000;
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
     */
    boolean ensureTarget(Bitmap bmp) {
        Vision.Frame w = Vision.toWork(bmp);
        if (!hasTarget()) {
            if (learn(bmp)) {
                flow.log("场次锁定: 首次学习目标场次 (当前居中卡已存为指纹)");
                return true;
            }
            return false;
        }
        double s = score(w);
        if (s >= TH) {
            flow.log("场次锁定: 居中卡就是目标场 (%.2f)".replace("%.2f", fmt(s)));
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
                flow.log("场次锁定: 找回目标场次");
                return true;
            }
        }
        flow.log("⚠ 场次锁定: 滑了 " + MAX_SLIDES + " 次仍没找到目标场次 — 不进场"
                + " (宁可漏跑也不错跑; 想换场次请删掉 " + file().getAbsolutePath() + ")");
        return false;
    }

    /** 当前居中卡与指纹的相似度 (0~1)。 */
    double score(Vision.Frame w) {
        Vision.Frame t = fingerprint();
        if (t == null) return 0;
        int[] roi = {Math.max(0, CARD[0] - PAD), Math.max(0, CARD[1] - PAD),
                CARD[2] + PAD * 2, CARD[3] + PAD * 2};
        double[] r = Vision.findScored(w, t, roi, 0.0, -1, 0);
        return r == null ? 0 : r[0];
    }

    /** 读指纹 PNG → work 基准的 Frame (与别的模板同一口径)。 */
    private Vision.Frame fingerprint() {
        try {
            Bitmap b = android.graphics.BitmapFactory.decodeFile(file().getAbsolutePath());
            return b == null ? null : Vision.toWork(b);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 把当前居中卡存成目标指纹。
     *
     * <p>⚠️ 裁剪用**帧高换算** (同 {@code ShizukuCtl.tapWork}): 实机帧 2400x1080 → work 1280x576
     * 的倍数是 1080/576 = 1.875, 直接写死 1.875 会在竖屏帧 (259x576 → s=1.0) 上裁飞。
     */
    private boolean learn(Bitmap bmp) {
        try {
            float s = bmp.getHeight() / 576f;
            Bitmap crop = Bitmap.createBitmap(bmp,
                    Math.round(CARD[0] * s), Math.round(CARD[1] * s),
                    Math.round(CARD[2] * s), Math.round(CARD[3] * s));
            FileOutputStream fo = new FileOutputStream(file());
            crop.compress(Bitmap.CompressFormat.PNG, 100, fo);
            fo.close();
            SgmLog.i("pf", "场次锁定: 指纹已存 " + crop.getWidth() + "x" + crop.getHeight()
                    + " → " + file().getAbsolutePath());
            return true;
        } catch (Throwable t) {
            SgmLog.i("pf", "场次锁定: 存指纹失败 " + t);
            return false;
        }
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.US, "%.2f", v);
    }
}
