package com.zhiyaunhe.sgmbot;

/**
 * 界面 (scene) 快速识别 —— "现在屏幕上这是哪一页"。
 *
 * <h3>为什么单独抽这一层</h3>
 * 之前 {@code PfFlow.step()} 是一条**串行的全帧匹配链**: 每一步依次对十几个模板做
 * {@code ROI_FULL} 全搜, 直到某一个命中。真机实测一轮循环曾慢到 **14 秒**
 * (两个 200x200 的模板满屏搜就吃掉大半), 于是"识别"比"操作"还贵 —— 战斗都打完了,
 * 状态机还在算上一帧是哪个界面。用户点名要的就是这个: "在某个界面要快速识别到这是哪个界面"。
 *
 * <h3>三条提速手段 (按性价比排序)</h3>
 * <ol>
 *   <li><b>像素特征优先</b> —— 能用"某个小窗口是不是某种颜色/亮度"判定的, 绝不做模板匹配。
 *       O(几十个像素) vs O(几十万次卷积)。典型: 冷启动画面 (单色)、QUIT 弹窗 (橙色标题栏)。</li>
 *   <li><b>紧 ROI</b> —— 每个界面元素在真机上的位置是**固定**的 (都实测过), 给它一个小
 *       搜索窗即可, 不必满屏找。典型: 大厅 EVENTS 只在 work(790,380) 一带。</li>
 *   <li><b>小模板</b> —— 只裁最有辨识度的一小块, 而不是整块卡片/整条标题栏。</li>
 * </ol>
 *
 * <h3>短路顺序 = 出现频率</h3>
 * 战斗 &gt; 编队编辑器 &gt; 选对手 &gt; hub &gt; 大厅 &gt; …… 命中即返回, 不再往下试。
 * 识别不出 ({@link S#UNKNOWN}) 时调用方要**回退到原有的串行链**, 本类只做快路径,
 * 不追求 100% 覆盖 —— 覆盖不到时宁可慢一点也别误判。
 *
 * <h3>不变式</h3>
 * 本类**只识别、不操作**。误判的代价由调用方承担, 所以每条判据都标了实测分数/余量,
 * 改阈值前先看那些数字。
 */
final class PfScene {

    /** 界面枚举。 */
    enum S {
        /** 竖屏 (不在游戏里) 或纯色加载画面。 */
        NOT_IN_GAME, BOOT,
        /** 战斗中 (速度泡可见)。 */
        BATTLE,
        /** 游戏主大厅。 */
        HALL,
        /** PF hub (三张场次卡 + PLAY!)。 */
        HUB,
        /** 选对手页 (REFRESH + 三张对手卡)。 */
        OPPONENT,
        /** 编队编辑器 (DRAG 提示 / 筛选漏斗 —— 能拖人那页)。 */
        TEAM_EDITOR,
        /** 编队预览页 (右上也有 FIGHT!，但不是编辑器)。 */
        TEAM_PREVIEW,
        /** 结算页。 */
        RESULTS,
        /** 筛选面板开着。 */
        FILTER_PANEL,
        /** 「QUIT GAME?」确认框。 */
        QUIT_DIALOG,
        /** 带 X 的通用弹窗 (能量/streak/OPTIONS)。 */
        POPUP_X,
        /** 认不出来 —— 调用方回退串行链。 */
        UNKNOWN
    }

    private final TplStore tpls;

    PfScene(TplStore tpls) { this.tpls = tpls; }

    /** 上一帧识别结果 —— 同样界面大概率还是它, 用于**窄化**下一帧的判定顺序 (非跳过)。 */
    private S last = S.UNKNOWN;

    S lastScene() { return last; }

    /**
     * 识别当前帧属于哪个界面。
     *
     * @param w   work 帧 (1280x576)
     * @return 命中的界面; 未识别返回 {@link S#UNKNOWN}
     */
    S classify(Vision.Frame w) {
        if (w == null) return S.UNKNOWN;
        S s = (last == S.BATTLE || last == S.TEAM_EDITOR)
                ? classifyOrdered(w, true) : classifyOrdered(w, false);
        last = s;
        return s;
    }

    /** 按"最可能在前"的顺序试一遍。 {@code freqFirst}=true 时把高频界面再往前挪。 */
    private S classifyOrdered(Vision.Frame w, boolean freqFirst) {
        // ① 不在游戏 / 还在加载 —— 纯像素, 最便宜, 且必须是第一顺位 (后面所有判据都靠游戏 UI)
        if (w.w < w.h) return S.NOT_IN_GAME;
        if (PfFlow.blankFrame(w)) return S.BOOT;

        if (freqFirst) {
            S hot = fastThree(w);
            if (hot != null) return hot;
        }

        // ② QUIT 确认框 —— 橙色标题栏, 只数一个 40x20 小窗的橙色占比
        if (quitDialog(w)) return S.QUIT_DIALOG;

        // ③ 战斗中 —— 速度泡 (小模板 + 小 ROI)
        /* ⚠️ 阈值必须跟 PfTeam 同口径: 旧值 0.80 只够 1x 帧 (0.805~0.818),
         * 3x 帧只有 0.727~0.745 ⇒ 打着 3x 反而判不出"在战斗"。统一用 Pf.TH_SPD。 */
        if (hit(w, Pf.T_SPD_2X, ROI_SPD, Pf.TH_SPD) != null
                || hit(w, Pf.T_SPD_3X, ROI_SPD, Pf.TH_SPD) != null
                || hit(w, Pf.T_SPD_1X, ROI_SPD, Pf.TH_SPD) != null) return S.BATTLE;

        // ④ 筛选面板 —— 面板右上 ✕ (只在这一页出现, 是它的专属锚点)
        if (hit(w, Pf.T_FILTER_X, Pf.ROI_FILTER_X, Pf.TH_FILTER_X) != null) return S.FILTER_PANEL;

        // ⑤ 编队编辑器 —— 漏斗 / DRAG 提示 (两页区分的关键, 见 PfFlow.inTeamEditor)
        if (hit(w, Pf.T_FILTER_BTN, Pf.ROI_FILTER_BTN, Pf.TH_FILTER_BTN) != null) return S.TEAM_EDITOR;
        if (hit(w, Pf.T_DRAGHINT, Pf.ROI_DRAGHINT, Pf.TH_DRAGHINT) != null) return S.TEAM_EDITOR;

        // ⑥ 选对手页 —— REFRESH
        if (hit(w, Pf.T_REFRESH, Pf.ROI_REFRESH, Pf.TH_REFRESH) != null) return S.OPPONENT;

        // ⑦ PF hub —— 居中卡 PLAY!
        if (hit(w, Pf.T_HUB_PLAY, Pf.ROI_HUB_PLAY, Pf.TH_HUB_PLAY) != null) return S.HUB;

        // ⑧ 主大厅 —— EVENTS 菱形 (小模板紧 ROI; PRIZE FIGHTS 那块会被自家浮条压住, 不可靠)
        if (hit(w, Pf.T_HALL_EVENTS, ROI_HALL_EVENTS, Pf.TH_HALL) != null) return S.HALL;

        // ⑨ 结算页 —— 底部 CONTINUE
        if (hit(w, Pf.T_RESULT_CONT, Pf.ROI_RESULT_C, Pf.TH_RESULT_C) != null) return S.RESULTS;

        if (!freqFirst) {
            S hot = fastThree(w);
            if (hot != null) return hot;
        }

        // ⑩ 编队预览页 —— 右上 FIGHT! 命中但上面都不是 ⇒ 只能是预览页 (右上也有 FIGHT!)
        if (hit(w, Pf.T_FIGHT, Pf.ROI_TOPRIGHT, Pf.TH_FIGHT) != null) return S.TEAM_PREVIEW;

        return S.UNKNOWN;
    }

    /** 战斗 / 编辑器 / 选对手 三个高频界面的极简判定 (给"上一帧就是它"的场合用)。 */
    private S fastThree(Vision.Frame w) {
        /* ⚠️ 阈值必须跟 PfTeam 同口径: 旧值 0.80 只够 1x 帧 (0.805~0.818),
         * 3x 帧只有 0.727~0.745 ⇒ 打着 3x 反而判不出"在战斗"。统一用 Pf.TH_SPD。 */
        if (hit(w, Pf.T_SPD_2X, ROI_SPD, Pf.TH_SPD) != null
                || hit(w, Pf.T_SPD_3X, ROI_SPD, Pf.TH_SPD) != null
                || hit(w, Pf.T_SPD_1X, ROI_SPD, Pf.TH_SPD) != null) return S.BATTLE;
        if (hit(w, Pf.T_DRAGHINT, Pf.ROI_DRAGHINT, Pf.TH_DRAGHINT) != null) return S.TEAM_EDITOR;
        if (hit(w, Pf.T_REFRESH, Pf.ROI_REFRESH, Pf.TH_REFRESH) != null) return S.OPPONENT;
        return null;
    }

    // ---------------- 像素特征 (不用模板, 最便宜) ----------------

    /**
     * 「QUIT GAME?」确认框 —— 橙色标题栏。
     *
     * <p>实测 (真机帧): 弹窗标题栏是 444x56 的橙色实心块, 中心约 work(640,208)。
     * 这里只数中心一个 40x20 小窗里的橙色占比 —— **40x20=800 个像素的活**, 而原判据是
     * 456x64 的标题栏模板满屏搜索 (几十万次卷积)。实测正例 ≈1.0、反例 0。
     */
    private static boolean quitDialog(Vision.Frame f) {
        int w0 = 40, h0 = 20, cx = 640, cy = 208;
        int x0 = cx - w0 / 2, y0 = cy - h0 / 2;
        if (x0 < 0 || y0 < 0 || x0 + w0 > f.w || y0 + h0 > f.h) return false;
        int ok = 0;
        float[] hsv = new float[3];
        for (int y = y0; y < y0 + h0; y++) {
            for (int x = x0; x < x0 + w0; x++) {
                PfTeam.hsv(f.px[y * f.w + x], hsv);
                // 橙色: 色相 10~35 度, 且够饱和够亮
                if (hsv[0] >= 10 && hsv[0] <= 35 && hsv[1] >= 0.45f && hsv[2] >= 0.55f) ok++;
            }
        }
        return ok >= w0 * h0 * 0.5;
    }

    /**
     * QUIT 确认框的 CANCEL 落点。
     *
     * <p>弹窗标题栏的位置在所有实测帧里都一样 (中心 work(640,208)), 所以 CANCEL 直接用
     * 固定偏移算 (见 {@link Pf#QUIT_CANCEL_OFF}), **不必再搜一次 456x64 的标题栏模板** ——
     * 那正是原来最贵的一处判定。
     */
    static int[] quitCancelAt(Vision.Frame f) {
        int x = 640 + Pf.QUIT_CANCEL_OFF[0];
        int y = 208 + Pf.QUIT_CANCEL_OFF[1];
        return new int[]{Math.min(Math.max(x, 0), f.w - 1), Math.min(Math.max(y, 0), f.h - 1)};
    }

    // ---------------- 服务器错误弹窗 (CANCEL / RETRY) ----------------

    /**
     * 服务器错误弹窗 RETRY 按钮的**内芯绿块** (work 坐标, 2026-10-17 真机实测)。
     *
     * <p>⚠️⚠️ 为什么不用模板: 全屏搜 137x50 的 {@code srv_retry} 要 ~4e9 次乘加
     * (真机几十秒), 所以老代码只敢在 unknown 恢复链里调 {@code findServerError} ——
     * 结果这个弹窗压在**别的高级界面之上**时根本没人检查它。现场 (2026-10-17 用户:
     * 「又有弹窗 你这个没处理？」): 弹窗盖在编队预览页上, {@code PfScene} 按「FIGHT! 可见」
     * 判成 TEAM_PREVIEW ⇒ 直接进 fightFlow ⇒ 「点 TEAM 菱形 → 未进入编队编辑器」
     * 死循环刷了 6 小时。色块判据只要 ~5k 个像素的活, 可以放在**每一轮的最前面**。
     *
     * <p>⚠️ 也别改成"只认绿块": 单看绿块在普通界面上最高能到 0.038 但**紫块**能到 0.313
     * (0306/0241 那两帧的卡面美术), 所以两个按钮**同时**成立才算 —— 实测正例
     * 绿 0.688 / 紫 0.771, 20 张普通帧最像的只有 绿 0.038 / 紫 0.313, 阈值 0.45 落在空档正中。
     */
    static final int[] SRV_RETRY_BOX = {668, 367, 135, 29};
    /** CANCEL 按钮的**内芯紫块** (work) —— 与绿块配对使用。 */
    static final int[] SRV_CANCEL_BOX = {476, 367, 136, 25};

    /** 是否「We're having some difficulty reaching our servers. Please try again!」弹窗。 */
    static boolean serverPopup(Vision.Frame f) {
        if (f == null || f.w < 900 || f.h < 500) return false;
        return fracGreen(f, SRV_RETRY_BOX) >= 0.45 && fracPurple(f, SRV_CANCEL_BOX) >= 0.45;
    }

    /** RETRY 落点 (work 坐标) —— 弹窗恒居中, 位置固定, 走色块判据就不必再搜模板。 */
    static int[] serverRetryAt() {
        return new int[]{SRV_RETRY_BOX[0] + SRV_RETRY_BOX[2] / 2,
                SRV_RETRY_BOX[1] + SRV_RETRY_BOX[3] / 2};
    }

    private static double fracGreen(Vision.Frame f, int[] b) {
        int n = 0, ok = 0;
        for (int y = b[1]; y < b[1] + b[3] && y < f.h; y++)
            for (int x = b[0]; x < b[0] + b[2] && x < f.w; x++) {
                if (x < 0 || y < 0) continue;
                int p = f.px[y * f.w + x];
                int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, bl = p & 0xFF;
                n++;
                if (g > 110 && g - r > 40 && g - bl > 40) ok++;
            }
        return n == 0 ? 0 : ok / (double) n;
    }

    private static double fracPurple(Vision.Frame f, int[] b) {
        int n = 0, ok = 0;
        for (int y = b[1]; y < b[1] + b[3] && y < f.h; y++)
            for (int x = b[0]; x < b[0] + b[2] && x < f.w; x++) {
                if (x < 0 || y < 0) continue;
                int p = f.px[y * f.w + x];
                int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, bl = p & 0xFF;
                n++;
                if (bl > 70 && r > 50 && bl - g > 25 && r < 170 && g < 150) ok++;
            }
        return n == 0 ? 0 : ok / (double) n;
    }

    // ---------------- 模板 + 紧 ROI ----------------

    /**
     * 大厅 EVENTS 菱形的紧 ROI。
     *
     * <p>实测该菱形在所有大厅帧里都落在 work(790,380) 一带 (±15px), 给它一个 150x90 的窗
     * 就够 —— 比满屏 (1280x576) 少两个数量级的搜索量。
     * ⚠️ 大厅判定**不**用 PRIZE FIGHTS: 那块在画面左侧, 会被本 App 自己的浮条压住
     * (实测从 0.98 掉到 0.55)。
     */
    private static final int[] ROI_HALL_EVENTS = {760, 360, 160, 80};

    /**
     * 战斗速度泡 —— 沿用 {@link Pf#ROI_SPD} (PC 换算值, 目前真机上**还没标定成功**:
     * 日志一直报"自动战斗已开但速度泡未识别")。这里不另造一个值, 免得两处口径打架;
     * 标定完之后改 {@link Pf#ROI_SPD} 一处即可, 两处同时生效。
     */
    private static final int[] ROI_SPD = Pf.ROI_SPD;

    /** 命中返回左上坐标; 未命中/缺模板返回 null。一律**强制全搜** (ROI 已很小, 全搜才可靠)。 */
    private double[] hit(Vision.Frame w, String tpl, int[] roi, double th) {
        Vision.Frame t = tpls.get(tpl);
        if (t == null) return null;
        double[] r = Vision.findScored(w, t, roi, th, -1, 0);
        return r != null && r[2] >= th ? r : null;
    }
}
