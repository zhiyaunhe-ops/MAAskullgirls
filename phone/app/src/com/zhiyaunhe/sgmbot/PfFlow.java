package com.zhiyaunhe.sgmbot;

import android.graphics.Bitmap;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * PF (Prize Fight) 主状态机 —— 移植电脑端 {@code tools/pf_bot.py} 的 {@code PfBot.step()}。
 *
 * <h3>与结算循环 (SettleLoop) 的分工</h3>
 * {@link SettleLoop} 只会"在战斗/结算页里打转"(它假设游戏已经在这一段流程内)。
 * 本类补上它缺的<b>整条链</b>: 从任意界面 → PF hub → 选对手 → 编队(每场重选角色)
 * → FIGHT → 战斗 → 结算 → 再战。
 *
 * <h3>step() 的优先级顺序不可调换</h3>
 * PC 注释里反复强调过为什么, 每条都在下面的分支上标了原因。总原则:
 * <ol>
 *   <li>**弹窗最优先** —— 遮罩会压暗底下的按钮, 但模板匹配对亮度不敏感, 所以压着弹窗
 *       时底下的 REFRESH/FIGHT 仍然会命中 ⇒ 不先剥弹窗就会对着被遮住的按钮空点。</li>
 *   <li>**无 X 的模态弹窗** (存档确认/防守说明/难度锁定) 只能靠正文或按钮识别, 必须排在
 *       它们遮住的页面判据<b>之前</b>。</li>
 *   <li>**HUB_PLAY / HALL_PRIZE** 是两个"锚": 前者说明已在 PF hub, 后者说明掉回大厅。</li>
 * </ol>
 *
 * <h3>与 PC 版的差异 (手机端没有 OCR)</h3>
 * <ul>
 *   <li>文本类判据 (防守/锁定/存档弹窗正文、分数、战力、倍率、难度 DIAMOND、防守槽角色名)
 *       暂缺 —— 相关位置已标 {@code TODO(ocr)}, 走各自的**降级路径**:
 *       战力/倍率读不到 ⇒ 退化为"取第一张对手卡"(PC 的 min() 在 None 上也这么走);
 *       分数读不到 ⇒ 不做达标收官 (按场次循环, 与 fast 的口径一致)。</li>
 *   <li>因此本版验收口径是"**按场次循环 20 场**", 不是"按分数达标收官"。</li>
 * </ul>
 */
public final class PfFlow {

    private final ShizukuCtl sh;
    private final TplStore tpls;
    private final OverlayBar bar;
    private final PfTeam team;

    /** 本场次内已处理的"每场次一次"标记 (PC 的 _defense_done / _filter_cleared 等)。 */
    private boolean defenseDone;
    private boolean filterCleared;
    private boolean battleAutoChecked;
    private boolean saveTeamGiveUp;
    private int saveTeamTries;
    private int ruleDoneFight = -1;
    private int ruleRedo;
    /** 当前场次已开打的场数 (PC 的 fight_no): REFRESH 命中即 +1。 */
    private int fightNo;
    /** 未知界面的恢复尝试次数 (交替返回键/右上 X)。 */
    private int unknownTries;
    /** 场次编号 (仅用于日志与"每场次一次"的判据比较)。 */
    private int sessionFightNo;
    /** 已完成的场数 (本地计数, 与 Store 各管一份)。 */
    public int roundsDone;

    private String stepText = "-";
    private long lastActMs;
    private long lastUiMs;
    /** 上一次「不在游戏里 ⇒ 补拉起」的时刻 (节流, 免 monkey 连发)。 */
    private long lastLaunchMs;
    /** 上一次「游戏加载中」提示的时刻 (节流, 免刷屏)。 */
    private long lastNoteMs;

    /** 结构性停止原因 (null = 继续跑)。 */
    private String fatal;

    /** 界面快速识别器 (见 {@link PfScene} 的类注释: 快路径靠它, 认不出才回退串行链)。 */
    private final PfScene scene;

    /** 正在跑的那个实例 (给监控页读实时状态; 服务里同一时刻只有一个)。 */
    private static volatile PfFlow live;

    /** 本次运行开始时刻 (监控页算已跑时长)。 */
    private long startMs;

    /** 监控页用: 当前在跑的实例, 没在跑返回 null。 */
    static PfFlow live() { return live; }

    /** 监控页用: 当前识别出的界面名 (没识别过返回 "-")。 */
    String sceneText() { return scene == null ? "-" : scene.lastScene().name(); }

    /** 监控页用: 已跑毫秒。 */
    long elapsedMs() {
        return startMs == 0 ? 0 : System.currentTimeMillis() - startMs;
    }

    public PfFlow(ShizukuCtl sh, TplStore tpls, OverlayBar bar) {
        this.sh = sh;
        this.tpls = tpls;
        this.bar = bar;
        this.scene = new PfScene(tpls);
        this.team = new PfTeam(sh, tpls, this);
    }

    public String stepText() { return stepText; }

    /** 停止原因 (供 BotService 呈现); 正常结束返回 null。 */
    public String fatalReason() { return fatal; }

    public void stop(String reason) { this.fatal = reason; }

    // ---- 供 PfTeam 读写的"每场次一次"水位 ----

    boolean isFilterCleared() { return filterCleared; }
    void markFilterCleared() { filterCleared = true; }
    /** 本场已做过规则保障的场号 (PC 的 _rule_done_fight)。 */
    int ruleDoneFight() { return ruleDoneFight; }
    void setRuleDone(int fightNo) { ruleDoneFight = fightNo; ruleRedo = 0; }
    /** 当前场次已开打的场数 (PC 的 fight_no)。 */
    int fightNo() { return fightNo; }

    // ==================================================================
    //  主循环
    // ==================================================================

    /**
     * PF 主循环 (在 BotService 工作线程里跑)。
     *
     * @param run    停止标志; 内部判定到结构性错误时会置 false
     * @param cfg    配置 (读 {@code pf} 段; 缺省用 {@link Pf} 常量)
     * @param quiet  true = 只跑主循环不主动导航 (从当前界面接力, 调试用)
     */
    public void run(AtomicBoolean run, Config cfg, boolean quiet) {
        JSONObject c = cfg.read();
        Vision.configure(c);

        team.configure(c);
        if (!team.loadTemplates()) {
            SgmLog.i("pf", "缺 PF 模板 (assets/templates/pf/) — 不开跑");
            fatal = "缺模板";
            return;
        }
        defenseDone = false;
        filterCleared = false;
        battleAutoChecked = false;
        saveTeamGiveUp = false;
        saveTeamTries = 0;
        ruleDoneFight = -1;
        ruleRedo = 0;
        fightNo = 0;
        unknownTries = 0;
        dumpSeq = 0;
        lastLaunchMs = 0;
        lastNoteMs = 0;
        bootWaitStart = 0;
        lastBackMs = 0;
        justFoughtTicks = 0;
        roundsDone = 0;
        sessionFightNo = 0;
        lastActMs = System.currentTimeMillis();

        SgmLog.i("pf", "PF 主循环开始 — 每场重选角色 | 匹配=" + Vision.modeText()
                + (quiet ? " | 静默模式(不主动导航)" : ""));
        live = this;                       // 监控页从这里读实时状态
        startMs = System.currentTimeMillis();

        /* 起步: 先确保站在 PF hub (PC 的 run() 在每次「开始」都走 navigate_to_scene)。
         * quiet 模式跳过 —— 给"人已经停在编队页"的调试场景用。 */
        if (!quiet && !gotoHub(run, c)) {
            if (fatal == null) fatal = "导航失败";
            return;
        }

        while (run.get() && fatal == null) {
            if (System.currentTimeMillis() - lastUiMs > 2000) {
                lastUiMs = System.currentTimeMillis();
                pushUi();
            }
            Bitmap bmp = sh.capture();
            if (bmp == null) {
                SgmLog.i("pf", "截屏失败 — 下一轮重试");
                sleep_(600);
                continue;
            }
            Vision.Frame work = Vision.toWork(bmp);
            step(work, bmp, run, c);
            sleep_(team.tapDelayMs());
        }
        pushUi();
        SgmLog.i("pf", "PF 主循环结束 — 本次 " + roundsDone + " 场"
                + (fatal != null ? " (" + fatal + ")" : ""));
        if (live == this) live = null;     // 停了就别让监控页读到陈实例
    }

    /**
     * 状态机单步: 截图一次并按优先级处理当前界面 —— PC {@code PfBot.step()} 的直译。
     */
    private void step(Vision.Frame work, Bitmap bmp, AtomicBoolean run, JSONObject c) {
        if (justFoughtTicks > 0) justFoughtTicks--;   // "刚点完 FIGHT"的时效窗 (见 ⑨)

        /* ---- 快路径: 界面识别 (PfScene) ----
         * ⚠️ 原来下面是一条**串行的全帧匹配链**, 每一步依次对十几个模板做 ROI_FULL 全搜,
         * 真机实测一轮循环慢到 14 秒 —— 识别比操作还贵。
         * 现在先花一顿便宜的代价认出"这是哪一页"(小 ROI 模板 + 像素特征), 认出来就
         * **直接跳到对应分支**, 不再逐个试。认不出才回退原链 (宁可慢也别误判)。
         * 弹窗类 (①/①b/②/③) 因为压在别的界面之上, 必须比页面分支先处理。 */
        PfScene.S sc = scene.classify(work);
        if (sc != PfScene.S.UNKNOWN) {
            switch (sc) {
                case QUIT_DIALOG:            // 我们按返回键按出来的, 无 X, 必须先点掉否则死锁
                    tap(bmp, PfScene.quitCancelAt(work));
                    unknownTries = 0;
                    sleep_(1500);
                    return;
                case FILTER_PANEL:           // 筛选面板还开着 ⇒ 直接关掉再继续
                    log("筛选面板仍开着, 点 ✕ 关闭");
                    tap(bmp, Pf.FILTER_CLOSE);
                    unknownTries = 0;
                    sleep_(1200);
                    return;
                case HUB:
                    unknownTries = 0;
                    setStep("PF hub");
                    log("PF hub 就绪 — 点 PLAY! 进场");
                    {
                        double[] hub2 = match(work, Pf.T_HUB_PLAY, Pf.ROI_HUB_PLAY, Pf.TH_HUB_PLAY);
                        if (hub2 != null) tapCenter(bmp, work, hub2, Pf.T_HUB_PLAY);
                        else tap(bmp, Pf.ROI_HUB_PLAY[0] + Pf.ROI_HUB_PLAY[2] / 2,
                                 Pf.ROI_HUB_PLAY[1] + Pf.ROI_HUB_PLAY[3] / 2);
                    }
                    sleep_(3000);
                    return;
                case HALL:
                    unknownTries = 0;
                    setStep("主大厅");
                    log("在主大厅 — 点 PRIZE FIGHTS 进入 PF");
                    tap(bmp, Pf.HALL_PRIZE_TAP);
                    sleep_(2500);
                    return;
                case OPPONENT:
                    unknownTries = 0;
                    justFoughtTicks = 0;
                    sessionFightNo++;
                    fightNo++;
                    setStep("选对手");
                    team.pickOpponent(work, bmp);
                    lastActMs = System.currentTimeMillis();
                    return;
                case TEAM_EDITOR:
                case TEAM_PREVIEW:
                    unknownTries = 0;
                    fightFlow(work, bmp, run);
                    lastActMs = System.currentTimeMillis();
                    return;
                case RESULTS:
                    unknownTries = 0;
                    setStep("战斗结算");
                    handleResults(bmp, run, c);
                    lastActMs = System.currentTimeMillis();
                    return;
                default:
                    break;               // BATTLE / NOT_IN_GAME / BOOT: 交给下面的原链
            }
        }

        // ① QUIT GAME? 确认框 (快路径没认出时的兜底: 模板判据)。
        if (handleQuitDialog(work, bmp)) {
            unknownTries = 0;
            return;
        }
        // ①b 弹窗 (能量/streak/OPTIONS): 遮罩会压暗底下按钮, 必须最先剥。
        int[] x = findPopupX(work);
        if (x != null) {
            unknownTries = 0;
            log("弹窗 (能量/streak/OPTIONS), 关闭");
            tap(bmp, x);
            sleep_(1500);
            return;
        }
        // ② 队伍存档确认「Save changes to your Team?」(无 X, NO/YES)。
        //    它压住防守队编辑器, 而编辑器右上 CONFIRM 在遮罩下仍能命中 ⇒ 必须排在
        //    CONFIRM/REFRESH 等页面判据之前, 否则对着被遮住的 CONFIRM 空点。
        if (confirmSaveTeam(bmp)) {
            unknownTries = 0;
            return;
        }
        // ③ 服务器错误弹窗。
        if (findServerError(work) != null) {
            unknownTries = 0;
            log("服务器错误弹窗, 点 OK");
            tap(bmp, findServerError(work));
            sleep_(2500);
            return;
        }
        // ④ 误入角色详情页: 返回。(全搜 —— 同 findServerError 那条教训: 金字塔会造伪峰)
        double[] det = matchFull(work, Pf.T_DETAIL, Pf.ROI_FULL, Pf.TH_FIGHT);
        if (det != null) {
            log("误入角色详情页, 返回");
            tap(bmp, Pf.BACK_BTN);
            sleep_(2000);
            return;
        }
        // ⑤ PF hub 就绪: 点居中卡 PLAY! 进场。
        double[] hub = match(work, Pf.T_HUB_PLAY, Pf.ROI_HUB_PLAY, Pf.TH_HUB_PLAY);
        if (hub != null) {
            unknownTries = 0;
            setStep("PF hub");
            log("PF hub 就绪 — 点 PLAY! 进场");
            tapCenter(bmp, work, hub, Pf.T_HUB_PLAY);
            sleep_(3000);
            return;
        }
        // ⑥ 游戏主大厅: 点 PRIZE FIGHTS 菱形进 PF hub。
        if (inHall(work)) {
            unknownTries = 0;
            setStep("主大厅");
            log("在主大厅 — 点 PRIZE FIGHTS 进入 PF");
            tap(bmp, Pf.HALL_PRIZE_TAP);
            sleep_(2500);
            return;
        }
        // ⑦ 防守队说明弹窗 (每场次只处理一次; 失败也置位, 避免无限点 OK 空烧)。
        if (!defenseDone && sessionFightNo <= 1 && team.findDefensePopup(work)) {
            unknownTries = 0;
            defenseDone = true;
            log("防守队说明弹窗 — 点 OK" + (team.hasRule()
                    ? " (规则在场时需在编辑器里放合规角色, TODO(ocr) 复验未做)" : ""));
            tap(bmp, Pf.DEFENSE_OK);
            sleep_(2000);
            return;
        }
        // ⑧ 防守队编辑器本体 (右上 CONFIRM): 这一页**没有能量黄钉**, 绝不能当常规编队页处理。
        double[] dc = match(work, Pf.T_DEF_CONFIRM, Pf.ROI_TOPRIGHT, Pf.TH_FIGHT);
        if (dc != null) {
            unknownTries = 0;
            defenseDone = true;
            log("防守队编辑器 — 点 CONFIRM 提交");
            tapCenter(bmp, work, dc, Pf.T_DEF_CONFIRM);
            sleep_(2000);
            return;
        }
        // ⑨ 难度锁定弹窗 (无 X, CANCEL/CONTINUE): 点 CONTINUE 锁定。
        //    必须排在 REFRESH/FIGHT 前 —— 弹窗背后的 FIGHT! 仍可见。
        //    ⚠️⚠️ **只在刚点完 FIGHT 的几步内才认** (2026-10-15 实栽): 判据只是
        //    "CONTINUE 位置有块绿" (手机端没 OCR 认不了正文 LOCKEDINTO), 而排行榜
        //    (LEADERBOARDS) 页恰好也有一块绿 ⇒ 打完第 2 场晋级 SILVER 后一进排行榜就误报,
        //    点 (726,461) 无效 → 判未知 → 返回键 → hub → PLAY → 又误报, **永远循环**。
        //    这个弹窗本来就只在点完 FIGHT 之后出现, 用"刚点过 FIGHT"做闸即可根除误报。
        if (justFoughtTicks > 0 && team.findLockPopup(work)) {
            unknownTries = 0;
            log("难度锁定弹窗: 点 CONTINUE 锁定");
            tap(bmp, Pf.LOCK_CONTINUE);
            sleep_(2500);
            return;
        }
        // ⑨b 「缺 Moves / Guest Star」无 X 弹窗 (2026-10-15 真机首次遇到, PC 代码无此判据):
        //     正文问 "FIGHT anyway or EQUIP ... first?", 两按钮在**画面中央**。
        //     ⚠️ 它挡在编队页之上, 而编队页**右上**也有 FIGHT! ⇒ 必须排在 ⑫ 之前,
        //     否则会去点被遮罩吃掉的右上 FIGHT 而原地踏步 (实测现场: gotoHub 反复点顶栏
        //     房子无效, 游戏停在编队页). 点 FIGHT! = 照打, 缺 Moves 不影响开战。
        double[] mf = match(work, Pf.T_MOVES_FIGHT, Pf.ROI_CENTER_FIGHT, Pf.TH_FIGHT);
        if (mf != null) {
            unknownTries = 0;
            log("「缺 Moves/Guest Star」弹窗: 点 FIGHT! 照打");
            tapCenter(bmp, work, mf, Pf.T_MOVES_FIGHT);
            sleep_(2000);
            return;
        }
        // ⑩ 选对手页 (REFRESH): 读分 + 场号 +1 + 选对手 (每场都重选)。
        double[] ref = match(work, Pf.T_REFRESH, Pf.ROI_REFRESH, Pf.TH_REFRESH);
        if (ref != null) {
            unknownTries = 0;
            justFoughtTicks = 0;          // 上一场已彻底走完, 进入下一场的选对手
            sessionFightNo++;
            fightNo++;
            setStep("选对手");
            team.pickOpponent(work, bmp);
            lastActMs = System.currentTimeMillis();
            return;
        }
        // ⑪ 编队页 (DRAG 提示): 修正队伍 → FIGHT。
        double[] drag = match(work, Pf.T_DRAGHINT, Pf.ROI_DRAGHINT, Pf.TH_DRAGHINT);
        if (drag != null) {
            unknownTries = 0;
            fightFlow(work, bmp, run);
            lastActMs = System.currentTimeMillis();
            return;
        }
        // ⑫ FIGHT 按钮 (右上通用区)。
        double[] fight = match(work, Pf.T_FIGHT, Pf.ROI_TOPRIGHT, Pf.TH_FIGHT);
        if (fight != null) {
            unknownTries = 0;
            fightFlow(work, bmp, run);
            lastActMs = System.currentTimeMillis();
            return;
        }
        // ⑬ 结算页 CONTINUE / 右上 CONTINUE。
        double[] rc = matchFull(work, Pf.T_RESULT_CONT, Pf.ROI_RESULT_C, Pf.TH_RESULT_C);
        if (rc == null) rc = match(work, Pf.T_CONTINUE, Pf.ROI_TOPRIGHT, Pf.TH_CONTINUE);
        if (rc != null) {
            unknownTries = 0;
            setStep("战斗结算");
            handleResults(bmp, run, c);
            lastActMs = System.currentTimeMillis();
            return;
        }
        // ⑭ 未知界面: 等 3s 再看一眼, 仍不认识就交替按返回键/右上 X
        //    (某些界面左上是设置齿轮, 只按返回键会打开 OPTIONS 卡死)。
        setStep("等待已知界面");
        unknownTries++;
        log("未知界面, 等待 3s (第 " + unknownTries + " 次)");
        sleep_(3000);
        Bitmap b2 = sh.capture();
        if (b2 != null) {
            Vision.Frame w2 = Vision.toWork(b2);
            boolean known = match(w2, Pf.T_REFRESH, Pf.ROI_REFRESH, Pf.TH_REFRESH) != null
                    || match(w2, Pf.T_DRAGHINT, Pf.ROI_DRAGHINT, Pf.TH_DRAGHINT) != null
                    || findPopupX(w2) != null
                    || match(w2, Pf.T_FIGHT, Pf.ROI_TOPRIGHT, Pf.TH_FIGHT) != null
                    || match(w2, Pf.T_CONTINUE, Pf.ROI_TOPRIGHT, Pf.TH_CONTINUE) != null
                    || matchFull(w2, Pf.T_RESULT_CONT, Pf.ROI_RESULT_C, Pf.TH_RESULT_C) != null
                    || match(w2, Pf.T_HUB_PLAY, Pf.ROI_HUB_PLAY, Pf.TH_HUB_PLAY) != null
                    || inHall(w2);
            if (!known) {
                /* ⚠️ 与 gotoHub 同一套判据 (2026-10-15 实栽): **不在游戏里 (竖屏帧)** 或
                 * **游戏加载中 (单色帧)** 时绝不能按返回键 —— 那正是把游戏按退出、
                 * 再按到桌面下拉通知栏的元凶。这两种情况一律交回导航 (它会补拉起/等待)。 */
                if (bootFrame(w2)) {
                    log("不在游戏/游戏启动中 — 交回导航处理");
                    unknownTries = 0;
                    if (!gotoHub(run, c)) fatal = "自愈失败";
                    return;
                }
                dumpUnknown(b2, w2);
                if (unknownTries % 2 == 1) {
                    /* ⚠️ 用**系统返回键**而不是点左上角: 真机上 work(25,26) 是"设置齿轮",
                     * 点它会打开 OPTIONS 菜单 —— 而 OPTIONS 又不在本方法的已知判据里,
                     * 于是永远出不去 (2026-10-15 实栽: gotoHub 空转到 300s 超时)。
                     * PC 用点击是因为它只有 MAA 的点击通道; 手机端有 input keyevent。 */
                    log("仍未知, 按系统返回键");
                    sh.exec("input keyevent 4");
                } else {
                    int[] px = findPopupX(w2);      // ROI 已覆盖真机右上 ⇒ 能命中 OPTIONS 等的 X
                    if (px != null) {
                        log("仍未知, 点弹窗 X @ " + px[0] + "," + px[1]);
                        tap(b2, px);
                    } else {
                        log("仍未知, 点右上角 X");
                        tap(b2, Pf.CLOSE_X);
                    }
                }
                sleep_(3500);
            }
        }
        // 连续多次判不出 = 结构性问题, 交由导航自愈 (PC 由 run() 的异常/重生兜底)。
        if (unknownTries >= 12) {
            log("连续 " + unknownTries + " 次判不出界面 — 重跑导航自愈");
            unknownTries = 0;
            if (!gotoHub(run, c)) fatal = "自愈失败";
        }
    }

    // ==================================================================
    //  导航: 从任意界面走回 PF hub (跨 pf_nav.goto_pf_hub)
    // ==================================================================

    /**
     * 走回 PF hub: 弹窗优先 → PLAY 就绪 → 大厅菱形 → 房子回家。
     *
     * <p>两态必须认 (PC 2026-10-02 补):
     * ① <b>战斗残留</b> —— 速度泡命中 = 还在打, 只等不点 (对着战斗界面按房子无效);
     * ② <b>残局结算页</b> —— 点 CONTINUE 逐页剥掉 (VICTORY/里程碑/streak), 再回 hub。
     *
     * @return 成功到 hub 返回 true
     */
    private boolean gotoHub(AtomicBoolean run, JSONObject c) {
        setStep("回到 PF hub");
        long t0 = System.currentTimeMillis();
        long limit = 300_000L;                       // NAV_TIMEOUT
        long battleLogAt = 0;
        while (run.get() && System.currentTimeMillis() - t0 < limit) {
            Bitmap bmp = sh.capture();
            if (bmp == null) { sleep_(1000); continue; }
            Vision.Frame work = Vision.toWork(bmp);

            if (match(work, Pf.T_HUB_PLAY, Pf.ROI_HUB_PLAY, Pf.TH_HUB_PLAY) != null) {
                log("PF hub 就绪");
                return true;
            }
            if (team.battleSpeedLevel(work) > 0) {           // 战斗残留: 只等
                if (System.currentTimeMillis() - battleLogAt > 15000) {
                    battleLogAt = System.currentTimeMillis();
                    log("导航: 上一场战斗仍在进行, 等它打完 (已等 "
                            + ((System.currentTimeMillis() - t0) / 1000) + "s)");
                }
                sleep_(6000);
                continue;
            }
            if (confirmSaveTeam(bmp)) continue;
            // QUIT GAME? 确认框: 我们按返回键按出来的, 无 X, 只能点 CANCEL。
            if (handleQuitDialog(work, bmp)) continue;
            // 「缺 Moves/Guest Star」无 X 弹窗: 挡路且无 X 可点, 只能点中央 FIGHT! 照打。
            double[] mf = match(work, Pf.T_MOVES_FIGHT, Pf.ROI_CENTER_FIGHT, Pf.TH_FIGHT);
            if (mf != null) {
                log("「缺 Moves/Guest Star」弹窗, 点 FIGHT! 照打");
                tapCenter(bmp, work, mf, Pf.T_MOVES_FIGHT);
                sleep_(2000);
                continue;
            }
            int[] px = findPopupX(work);
            if (px == null) {
                double[] sc = match(work, Pf.T_SCENE_X, Pf.ROI_SCENE_X, 0.8);
                if (sc != null) px = centerOf(work, sc, Pf.T_SCENE_X);
            }
            if (px == null) {
                double[] mx = match(work, Pf.T_MODAL_X, Pf.ROI_MODAL_X, 0.8);
                if (mx != null) px = centerOf(work, mx, Pf.T_MODAL_X);
            }
            if (px != null) {
                log("导航途中弹窗, 点 X");
                tap(bmp, px);
                sleep_(1800);
                continue;
            }
            double[] cont = matchFull(work, Pf.T_RESULT_CONT, Pf.ROI_RESULT_C, Pf.TH_RESULT_C);
            if (cont == null) cont = match(work, Pf.T_CONTINUE, Pf.ROI_TOPRIGHT, Pf.TH_CONTINUE);
            if (cont != null) {
                log("导航: 残局结算页, 点 CONTINUE 脱离");
                tapCenter(bmp, work, cont, Pf.T_CONTINUE);
                sleep_(2500);
                continue;
            }
            if (inHall(work)) {
                log("导航: 在大厅, 点 PRIZE FIGHTS");
                tap(bmp, Pf.HALL_PRIZE_TAP);
                sleep_(2500);
                continue;
            }
            /* ---------- 认不出界面时的处置: 按「我在哪一层」分三种 ----------
             * ⚠️⚠️ 2026-10-15 真机实栽并重写。旧实现是"一律按系统返回键", 在**游戏冷启动**
             * 期间完全破坏性: 加载闪屏 (纯黑→纯白) 与开场 splash 都判不出 ⇒ 每 3.5s 按一次
             * 返回 ⇒ 直接把游戏按退出、继续按又拉到桌面并下拉通知栏 (现场截图: 桌面 + 控制中心)。
             * 而且 `launched` 只置位一次 ⇒ 游戏退出后**永不补拉起** ⇒ 300s 空转到超时。
             */
            // ① 竖屏帧 ⇒ 根本不在游戏里 (游戏恒为横屏 2400x1080) ⇒ 重新拉起, 绝不按返回
            if (work.w < work.h) {
                bootWaitStart = 0;
                if (System.currentTimeMillis() - lastLaunchMs > 8000) {
                    lastLaunchMs = System.currentTimeMillis();
                    log("导航: 不在游戏里 (竖屏帧 " + work.w + "x" + work.h + "), 拉起 Skullgirls");
                    sh.exec("monkey -p com.autumn.skullgirls -c android.intent.category.LAUNCHER 1");
                }
                sleep_(3000);
                continue;
            }
            // ② 冷启动画面 (纯色闪屏 / 深色 logo splash) ⇒ **只等, 不发任何输入**
            //    实测游戏冷启动要走 黑→白→splash(均值~42)→大厅 约 50~70s, 期间任何按键
            //    都可能把它按退。这里给 BOOT_WAIT_MS 的静默等待窗口。
            if (bootFrame(work)) {
                if (bootWaitStart == 0) bootWaitStart = System.currentTimeMillis();
                long waited = System.currentTimeMillis() - bootWaitStart;
                if (waited < BOOT_WAIT_MS) {
                    if (System.currentTimeMillis() - lastNoteMs > 10000) {
                        lastNoteMs = System.currentTimeMillis();
                        log("导航: 游戏启动中 (画面单色/深色), 静默等待 " + (waited / 1000) + "s");
                    }
                    sleep_(3000);
                    continue;
                }
                if (System.currentTimeMillis() - lastNoteMs > 10000) {
                    lastNoteMs = System.currentTimeMillis();
                    log("导航: 启动等待 " + (BOOT_WAIT_MS / 1000) + "s 仍认不出, 转为通用脱困");
                }
            } else {
                bootWaitStart = 0;
            }
            /* ③ 横屏 + 有内容: 通用脱困。真机**大厅顶栏没有房子**(那个位置是齿轮, 点它会开
             * OPTIONS), 所以默认走**系统返回键** —— 实测对页面型弹窗有效 (PROFILE 0.19→0.98
             * 回大厅), 只有 Unity 自己的 OPTIONS 菜单不吃返回键 (那款由 findPopupX 关)。
             * 房子模板仅在 PF 内部 (hub 等) 才命中, 命中就用它。 */
            bootWaitStart = 0;
            double[] home = match(work, Pf.T_HOME, Pf.ROI_HOME, 0.70);
            if (home != null) {
                tapCenter(bmp, work, home, Pf.T_HOME);
            } else if (System.currentTimeMillis() - lastBackMs < BACK_MIN_GAP_MS) {
                /* 返回键**限流**: 每 BACK_MIN_GAP_MS 最多一次。
                 * 理由: 游戏开场 splash 只持续 3~6s 且含内容 (非纯色 ⇒ 过不了 bootFrame),
                 * 会被判"未知"。若每 3.5s 就按一次返回, 很可能在 splash 期间连按几次 —— 而
                 * 真机上返回键是会**退出游戏**的。限流后这类瞬态画面自己就过去了。 */
                sleep_(2500);
                continue;
            } else {
                lastBackMs = System.currentTimeMillis();
                log("导航: 未知界面, 按系统返回键尝试脱离");
                dumpUnknown(bmp, work);
                sh.exec("input keyevent 4");
            }
            sleep_(2500);
        }
        if (!run.get()) return false;
        log("300s 未走回 PF hub");
        return false;
    }

    // ==================================================================
    //  选对手 → 编队 → FIGHT  (PC fight_flow)
    // ==================================================================

    /**
     * 是否在游戏主大厅 —— PRIZE FIGHTS 或 EVENTS 任一命中即算。
     *
     * <p>⚠️ 为什么需要**两个**判据 (2026-10-15 真机实栽): 只看 PRIZE FIGHTS 会翻车 ——
     * 本 App 自己的浮条被拖到画面左侧后**压住 PRIZE FIGHTS 节点**, 模板从 0.98 掉到 0.55,
     * 于是判不出大厅 → 按返回键 → 弹出 QUIT GAME? → 画面压暗 → 更判不出 → 死锁。
     * EVENTS 节点在画面右半边, 浮条盖不到 (实测大厅 0.87~1.00、其它界面 ≤0.42)。
     *
     * <p>点击一律走**固定坐标** {@link Pf#HALL_PRIZE_TAP} —— 该节点位置在所有实测帧里恒为同一点,
     * 这样"判据可以是被遮住的那个, 定位不依赖模板"。
     */
    private boolean inHall(Vision.Frame work) {
        return matchFull(work, Pf.T_HALL_PRIZE, Pf.ROI_FULL, Pf.TH_HALL_PRIZE) != null
                || matchFull(work, Pf.T_HALL_EVENTS, Pf.ROI_FULL, Pf.TH_HALL) != null;
    }

    /**
     * 是否在**编队编辑器**(可拖人那一页)。
     *
     * <p>⚠️⚠️ 2026-10-15 真机实栽并改判据。真机上 PF 有**两页长得几乎一样**:
     * ① 编队预览页 (只有大立绘 + 底部候选列 + TEAM 菱形);
     * ② 编队编辑器 (点 TEAM 菱形进) —— 多了「DRAG DESIRED FIGHTER TO A SLOT」提示条
     *    和右下角的**筛选漏斗**, 拖人/筛选都在这一页。
     * 两页右上**都有 FIGHT!**, 所以旧的"DRAGHINT 或 FIGHT"判据恒为真 ⇒ 永远停在预览页,
     * {@code fixTeam} 里的筛选漏斗点在预览页上 → 打不开面板 → **静默放弃筛选**
     * (这就是"选角色不加筛选条件"的真身)。
     *
     * <p>现在的判据: 编辑器 = **DRAGHINT 或 筛选漏斗**。实测 (均为真机帧):
     * DRAGHINT 编辑器 0.65~0.69 / 预览页 0.12; 漏斗 编辑器 0.88~1.00 / 预览页 0.05。
     * 不用 FIGHT 是**故意**的 —— 它在两页都出现, 区分不了。
     */
    private boolean inTeamEditor(Vision.Frame w) {
        return matchFull(w, Pf.T_DRAGHINT, Pf.ROI_DRAGHINT, Pf.TH_DRAGHINT) != null
                || funnel(w) != null;
    }

    /**
     * 筛选漏斗中心 (work 坐标), 未命中返回 null。
     *
     * <p>⚠️ **必须全搜**: 漏斗只有 60x50, 金字塔 (pyr4x) 把它降到 15x12 后基本认不出来 ——
     * 项目里这条规则早就钉死了 ("金字塔只对全屏搜大按钮成立, 召回关键一律 findExact"),
     * 这里踩的是同一个坑: 面板明明在, 模板却"没匹配上" ⇒ setFilter 判"打不开"放弃筛选。
     */
    int[] funnel(Vision.Frame w) {
        double[] r = matchFull(w, Pf.T_FILTER_BTN, Pf.ROI_FILTER_BTN, Pf.TH_FILTER_BTN);
        return r == null ? null : centerOf(w, r, Pf.T_FILTER_BTN);
    }

    /** 若已在编队编辑器则直接修正; 否则先点 TEAM 菱形进入。 */
    private void fightFlow(Vision.Frame work, Bitmap bmp, AtomicBoolean run) {
        setStep("进编队");
        if (!inTeamEditor(work)) {
            log("不在编队编辑器 — 点 TEAM 菱形进入");
            tap(bmp, Pf.TEAM_DIAMOND);           // TEAM 菱形
            sleep_(1600);
            Bitmap b2 = sh.capture();
            if (b2 != null) {
                Vision.Frame w2 = Vision.toWork(b2);
                int[] x = findPopupX(w2);
                if (x != null) {                 // 能量弹窗
                    log("能量弹窗出现, 关闭");
                    tap(b2, x);
                    sleep_(1600);
                }
            }
        }
        Bitmap bi = sh.capture();
        if (bi == null) return;
        Vision.Frame wi = Vision.toWork(bi);
        if (!inTeamEditor(wi)) {
            log("未进入编队编辑器, 回主循环重试");
            return;
        }
        log("已进入编队编辑器");

        int rotations = 0;
        while (run.get()) {
            if (!team.fixTeam(this, rotations > 0)) {
                /* ⚠️ "没能量" 不是故障 (见 PfTeam 里 ENERGY_WAIT_* 的注释): fixTeam 已经
                 * 等过若干轮恢复周期, 这里**不置 fatal** —— 退回主循环让它重新分派,
                 * 下一轮再等。要跑满 200 场, 能量见底一定会发生, 停机就永远跑不完。
                 * 用户仍可随时用悬浮窗的「暂停/结束」中断。 */
                /* ⚠️ 一律**不置 fatal** —— 要跑满 200 场, 中途任何一次编队不顺(没能量 /
                 * 某张卡拖不动)都不该结束整轮, 退回主循环下一轮再试即可。
                 * fixTeam 内部各环节都已各自封顶 (能量最多等 30 轮、拖拽最多 4 次),
                 * 所以这里不会变成无限空转; 真想停就按悬浮窗的「暂停/结束」。 */
                log(team.lastFailWasNoEnergy()
                        ? "能量仍未恢复 — 退回主循环继续等 (不停止本次运行)"
                        : "本次编队没成 (拖拽/规则) — 退回主循环重试, 不停止本次运行");
                return;
            }
            Bitmap bf = sh.capture();
            if (bf == null) return;
            Vision.Frame wf = Vision.toWork(bf);

            // 规则校验: 能量替换可能把合规角色换掉 ⇒ FIGHT 变灰就要重做规则替换。
            if (team.hasRule() && ruleDoneFight == fightNo && !team.judgeRule(wf)) {
                ruleRedo++;
                if (ruleRedo > 2) {
                    fatal = "能量替换反复破坏规则";
                    log("能量替换反复破坏规则 (FIGHT 仍灰), 停止");
                    return;
                }
                log("能量替换后 FIGHT 变灰, 重做规则替换");
                ruleDoneFight = -1;
                continue;
            }
            double[] fight = match(wf, Pf.T_FIGHT, Pf.ROI_TOPRIGHT, Pf.TH_FIGHT);
            if (fight == null) {
                log("找不到 FIGHT 按钮, 回主循环");
                return;
            }
            tapCenter(bf, wf, fight, Pf.T_FIGHT);
            justFoughtTicks = 12;   // 接下来 12 步内才允许认"难度锁定弹窗" (见 ⑨ 注释)
            sleep_(2500);
            Bitmap bp = sh.capture();
            if (bp == null) return;
            Vision.Frame wp = Vision.toWork(bp);

            if (team.findLockPopup(wp)) {           // 角色场首入: 锁定难度确认 (无 X)
                log("难度锁定弹窗: 点 CONTINUE 锁定, 交回主循环");
                tap(bp, Pf.LOCK_CONTINUE);
                sleep_(3000);
                return;
            }
            int[] ex = findPopupX(wp);
            if (ex != null) {
                rotations++;
                if (rotations > 15) {
                    fatal = "能量弹窗循环超限";
                    log("能量弹窗循环超限, 停止");
                    return;
                }
                log("FIGHT 后能量弹窗, 关闭并继续换人");
                tap(bp, ex);
                sleep_(1600);
                continue;                            // 回 fixTeam 换人
            }
            roundsDone++;                     // 打完才算一场? 不 —— 开场就算, 便于中途观察进度
            log("▶ 第 " + roundsDone + " 场开始 (本次累计 " + roundsDone + " 场), 等待结束…");
            if (!battleAutoChecked) {
                battleAutoChecked = true;
                team.ensureBattleAuto();
            }
            waitBattleEnd(run);
            return;
        }
    }

    /** 战斗等待: 最多 300s, 每 4s 一帧; 命中结算/编队页即返回。 */
    private void waitBattleEnd(AtomicBoolean run) {
        long t0 = System.currentTimeMillis();
        int n = 0;
        while (run.get() && System.currentTimeMillis() - t0 < 300_000L) {
            sleep_(4000);
            n++;
            Bitmap bmp = sh.capture();
            if (bmp == null) continue;
            Vision.Frame work = Vision.toWork(bmp);
            if (findServerError(work) != null) {
                log("战斗中出现服务器错误弹窗, 点 OK");
                tap(bmp, findServerError(work));
                sleep_(2500);
                continue;
            }
            if (confirmSaveTeam(bmp)) continue;
            // QUIT GAME? 确认框: 我们按返回键按出来的, 无 X, 只能点 CANCEL。
            if (handleQuitDialog(work, bmp)) continue;
            // 「缺 Moves/Guest Star」无 X 弹窗: 挡路且无 X 可点, 只能点中央 FIGHT! 照打。
            double[] mf = match(work, Pf.T_MOVES_FIGHT, Pf.ROI_CENTER_FIGHT, Pf.TH_FIGHT);
            if (mf != null) {
                log("「缺 Moves/Guest Star」弹窗, 点 FIGHT! 照打");
                tapCenter(bmp, work, mf, Pf.T_MOVES_FIGHT);
                sleep_(2000);
                continue;
            }
            int[] px = findPopupX(work);             // 弹窗优先于"结束"判据
            if (px != null) {
                log("战斗等待中出现弹窗 (能量/连胜等), 点 X 关闭");
                tap(bmp, px);
                sleep_(1800);
                continue;
            }
            if (match(work, Pf.T_DRAGHINT, Pf.ROI_DRAGHINT, Pf.TH_DRAGHINT) != null) {
                log("回到编队页?");
                return;
            }
            if (matchFull(work, Pf.T_RESULT_CONT, Pf.ROI_RESULT_C, Pf.TH_RESULT_C) != null) {
                log("出现结算 CONTINUE (战斗胜利)");
                return;
            }
            if (match(work, Pf.T_CONTINUE, Pf.ROI_TOPRIGHT, Pf.TH_CONTINUE) != null) {
                log("出现 CONTINUE (战斗结束)");
                return;
            }
        }
        if (run.get()) log("战斗等待超时 300s");
    }

    /** 结算链: 连点 CONTINUE 最多 6 页 (XP/里程碑/streak 各一页)。 */
    private void handleResults(Bitmap bmp, AtomicBoolean run, JSONObject c) {
        for (int i = 0; i < 6 && run.get(); i++) {
            Bitmap b = i == 0 ? bmp : sh.capture();
            if (b == null) return;
            Vision.Frame work = Vision.toWork(b);
            double[] box = matchFull(work, Pf.T_RESULT_CONT, Pf.ROI_RESULT_C, Pf.TH_RESULT_C);
            if (box == null) box = match(work, Pf.T_CONTINUE, Pf.ROI_TOPRIGHT, Pf.TH_CONTINUE);
            if (box == null) {
                log("结算页结束");
                return;
            }
            log("点击结算 CONTINUE (第 " + (i + 1) + " 页)");
            tapCenter(b, work, box, Pf.T_RESULT_CONT);
            sleep_(2200);
        }
    }

    // ==================================================================
    //  弹窗判据
    // ==================================================================

    /**
     * 「QUIT GAME?」确认弹窗: 认出标题栏就点 CANCEL。
     *
     * <p>⚠️ 这个弹窗是**我们自己按返回键按出来的** (2026-10-15 真机现场: 大堂上按返回 →
     * 弹出它 → 画面压暗 → 大堂模板失配 → 判"未知" → 再按返回 → 死锁)。
     * 它没有 X, findPopupX 抓不到, 必须单独认; 不处理就会永久卡在"未知界面"。
     *
     * @return 处理过返回 true
     */
    private boolean handleQuitDialog(Vision.Frame work, Bitmap bmp) {
        double[] q = match(work, Pf.T_QUIT, Pf.ROI_FULL, 0.85);
        if (q == null) return false;
        Vision.Frame t = tpls.get(Pf.T_QUIT);
        int cx = (int) q[0] + (t == null ? 0 : t.w / 2);
        int cy = (int) q[1] + (t == null ? 0 : t.h / 2);
        int[] p = {cx + Pf.QUIT_CANCEL_OFF[0], cy + Pf.QUIT_CANCEL_OFF[1]};
        log("「QUIT GAME?」弹窗 — 点 CANCEL 取消 (帮我们自己按返回按出来的)");
        tap(bmp, p);
        sleep_(1500);
        return true;
    }

    /**
     * 通用弹窗 X: 三款模板 (能量不足 / 连胜 streak / OPTIONS)。
     * 返回被点位置 (work) 或 null。
     *
     * <p>⚠️ 取**分数最高**的那一款, 不是"第一个过阈值的" —— 三款都是圆形 X、形状相似,
     * 在同一张弹窗上往往**同时**过阈值, 只差几个像素。按固定顺序返回第一个会让点击落到
     * 偏掉的位置上 (2026-10-15 实栽: 在 OPTIONS 菜单上反复点 X 却关不掉, 最后才发现
     * 点的不是 OPTIONS 那个 X)。
     */
    private int[] findPopupX(Vision.Frame work) {
        String[] names = {Pf.T_ENERGY_X, Pf.T_STREAK_X, Pf.T_OPTIONS_X};
        double best = 0;
        String bestName = null;
        double[] bestHit = null;
        for (String n : names) {
            double[] r = match(work, n, Pf.ROI_POPUP, 0.70);
            if (r != null && r[2] > best) {
                best = r[2];
                bestName = n;
                bestHit = r;
            }
        }
        if (bestHit == null) return null;
        int[] c = centerOf(work, bestHit, bestName);
        log(String.format("弹窗 X: %s %.2f @ %d,%d", bestName.substring(bestName.indexOf('/') + 1),
                best, c[0], c[1]));
        return c;
    }

    /** 服务器错误弹窗按钮 (srv_ok / srv_retry)。 */
    private int[] findServerError(Vision.Frame work) {
        /* ⚠️⚠️ 2026-10-16 实栽: 这里原来是 `match()` (走金字塔) + 阈值 0.75, 而 **普通界面
         * 上 srv_ok 就能过线** ⇒ 每 4 秒点一次"OK", 点在一个根本不存在的按钮上, 无限空转
         * (日志刷了几百行"服务器错误弹窗, 点 OK"却什么都没发生)。
         * 两条修正: ① **全搜** —— 金字塔的粗搜会在无关区域撞出伪峰 (实测普通帧全搜只有
         * 0.45~0.65, 金字塔却能顶到 0.75+); ② 阈值抬到 0.85, 与实测误报上限 0.65 拉开余量。 */
        double[] r = matchFull(work, Pf.T_SRV_OK, Pf.ROI_FULL, 0.85);
        if (r != null) return centerOf(work, r, Pf.T_SRV_OK);
        r = matchFull(work, Pf.T_SRV_RETRY, Pf.ROI_FULL, 0.85);
        if (r != null) return centerOf(work, r, Pf.T_SRV_RETRY);
        return null;
    }

    /**
     * 「Save changes to your Team?」存档确认 (无 X, NO/YES)。
     *
     * <p>PC 用正文 OCR 认这个弹窗; 手机端没有 OCR ⇒ {@code TODO(ocr)}。
     * 现用**几何兜底**: 该弹窗的 YES 在固定位置, 且只在"刚动过防守队又离开"时出现
     * (PC 实测触发面很窄)。暂时不主动点, 交给 ⑭ 的返回键/关闭 X 兜底 —— 不盲点未知按钮。
     *
     * @return 是否处理了 (true 时代码调用方 continue)
     */
    private boolean confirmSaveTeam(Bitmap bmp) {
        return false;                                 // TODO(ocr): 补正文/按钮判据后启用
    }

    // ==================================================================
    //  匹配与点击工具
    // ==================================================================

    /** 诊断转储用的模板子集 (判不出界面时要看的那些)。 */
    private static final String[] DIAG_TPL = {
            Pf.T_HALL_PRIZE, Pf.T_HUB_PLAY, Pf.T_FIGHT, Pf.T_REFRESH, Pf.T_DRAGHINT,
            Pf.T_RESULT_CONT, Pf.T_CONTINUE, Pf.T_HOME, Pf.T_OPTIONS_X, Pf.T_ENERGY_X,
            Pf.T_STREAK_X, Pf.T_MOVES_FIGHT, Pf.T_SRV_OK, Pf.T_DEF_CONFIRM, Pf.T_SCENE_X,
            Pf.T_MODAL_X, Pf.T_SPD_3X,
    };

    /**
     * 诊断: 判不出界面时, 把**我所见的这一帧**存盘 + 记下全屏各模板最高分。
     *
     * <p>存在的理由 (2026-10-15): 真机排障最耗时的一环是"bot 说未知, 但人在旁边看画面
     * 明明是已知界面"。没有这张落盘帧, 就只能靠猜 (抓 adb 截图的那几秒里画面往往已经变了,
     * 而且无法证明 bot 的截屏通道本身是否正常)。有了它, 离线复盘的输入就是 bot 的**真实输入**。
     *
     * <p>只留前 {@link #DUMP_MAX} 次, 免得刷满盘; 全屏搜 17 个模板 ≈ 一次几百 ms,
     * 只在判不出时付这个代价。
     */
    private void dumpUnknown(Bitmap bmp, Vision.Frame work) {
        if (dumpSeq >= DUMP_MAX) return;
        dumpSeq++;
        try {
            java.io.File dir = new java.io.File(Paths.root() + "/debug");
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            java.io.FileOutputStream fo = new java.io.FileOutputStream(
                    new java.io.File(dir, "u" + String.format("%02d", dumpSeq) + ".png"));
            bmp.compress(Bitmap.CompressFormat.PNG, 100, fo);
            fo.close();
            StringBuilder sb = new StringBuilder();
            for (String n : DIAG_TPL) {
                Vision.Frame t = tpls.get(n);
                if (t == null) continue;
                double[] r = Vision.findScored(work, t, Pf.ROI_FULL, 0.0, -1, 0);
                if (r != null) {
                    sb.append(n).append('=')
                      .append(Math.round(r[2] * 100) / 100.0).append(' ');
                }
            }
            log("诊断帧 u" + dumpSeq + ".png (work " + work.w + "x" + work.h
                    + ", 实机帧 " + bmp.getWidth() + "x" + bmp.getHeight()
                    + ") | 全屏分: " + sb);
        } catch (Throwable ignored) { }
    }

    /** 诊断帧最多保留几次 (u01..u03)。 */
    private static final int DUMP_MAX = 3;

    /** 冷启动静默等待窗口: 游戏 boot 期间 (单色闪屏 / 深色 splash) 一律不发输入。 */
    private static final long BOOT_WAIT_MS = 75_000L;

    /** 上次「疑似还在冷启动」的起始时刻 (0 = 不在冷启动期)。 */
    private long bootWaitStart;

    /** 上次按系统返回键的时刻 (限流用)。 */
    private long lastBackMs;

    /** 「刚点完 FIGHT」的剩余步数 (>0 才认难度锁定弹窗, 见 step() ⑨ 的注释)。 */
    private int justFoughtTicks;

    /** 返回键最小间隔 —— 瞬态过渡画面 (splash/过场) 靠它自然过去, 详见调用处注释。 */
    private static final long BACK_MIN_GAP_MS = 10_000L;

    /**
     * 画面是否"游戏还在启动" —— 只认**纯色闪屏** (黑/白) 与**竖屏帧** (不在游戏里)。
     *
     * <p>⚠️⚠️ 阈值标定史 (2026-10-15, 真机逐帧实测, 教训比结论值钱):
     * 一开始还写了"深色画面 = 启动中 (灰度均值 &lt; 80)", 想连开场 splash 一起兜住。
     * 结果**把大厅也判成了启动画面** —— 实测亮度:
     * 黑闪 **0.2** / splash **44.9** / **大厅 55.8** / 白闪 176.1。
     * 大厅与 splash 的均值、中位数几乎一模一样, **亮度根本分不开这两者**。
     * 症状: 游戏其实 12s 就到大厅了 (`hall_prize` 0.98), bot 却因为"深色 = 还在启动"
     * 在那儿干等 75s 一轮, 循环空转。
     * ⇒ 结论: **别用亮度判"是不是界面"**。是否已到大厅交给模板判据 (循环前面已经有);
     * 这里只负责"这一帧根本不含界面信息"(纯色闪屏) 与"根本不在游戏里"(竖屏)。
     */
    static boolean bootFrame(Vision.Frame f) {
        if (f == null || f.px == null) return true;
        if (f.w < f.h) return true;              // 竖屏 ⇒ 不在游戏里
        return blankFrame(f);                    // 纯色闪屏 (黑 0.2 / 白 176, 标准差都极小)
    }

    /**
     * 画面是否近似单色 (游戏加载/闪屏: 纯黑 → 纯白)。
     *
     * <p>被 {@link #bootFrame} 用作其中一条判据 (纯色是 boot 的真子集)。
     * 8px 网格采样估灰度标准差, 阈值 64 (=标准差 8 级) 足够区分纯色与任何真实界面。
     */
    static boolean blankFrame(Vision.Frame f) {
        if (f == null || f.px == null) return true;
        long s = 0, s2 = 0;
        int n = 0;
        for (int y = 0; y < f.h; y += 8) {
            int row = y * f.w;
            for (int x = 0; x < f.w; x += 8) {
                int p = f.px[row + x];
                int v = (((p >> 16) & 0xff) + ((p >> 8) & 0xff) + (p & 0xff)) / 3;
                s += v;
                s2 += (long) v * v;
                n++;
            }
        }
        if (n == 0) return true;
        double m = s / (double) n;
        double var = s2 / (double) n - m * m;
        return var < 64;
    }

    /** 诊断帧序号 (跨 step/gotoHub 共用一个上限)。 */
    private int dumpSeq = 0;

    /** 命中返回 {x, y(左上), best}; 未命中/缺模板返回 null。 */
    private double[] match(Vision.Frame work, String tplName, int[] roi, double th) {
        Vision.Frame t = tpls.get(tplName);
        if (t == null) return null;
        double[] r = Vision.findScored(work, t, roi, th);
        return r != null && r[2] >= th ? r : null;
    }

    /** 同 match, 但**强制全搜** —— 召回关键一律走这条 (金字塔会丢小字模板)。 */
    private double[] matchFull(Vision.Frame work, String tplName, int[] roi, double th) {
        Vision.Frame t = tpls.get(tplName);
        if (t == null) return null;
        double[] r = Vision.findScored(work, t, roi, th, -1, 0);
        return r != null && r[2] >= th ? r : null;
    }

    /** 命中框中心 (work 坐标)。 */
    private int[] centerOf(Vision.Frame work, double[] hit, String tplName) {
        Vision.Frame t = tpls.get(tplName);
        int tw = t == null ? 0 : t.w, th = t == null ? 0 : t.h;
        return new int[]{(int) hit[0] + tw / 2, (int) hit[1] + th / 2};
    }

    private void tap(Bitmap bmp, int[] p) { sh.tapWork(bmp, p[0], p[1]); }
    private void tap(Bitmap bmp, float[] p) { sh.tapWork(bmp, p[0], p[1]); }
    private void tapCenter(Bitmap bmp, Vision.Frame work, double[] hit, String tplName) {
        tap(bmp, centerOf(work, hit, tplName));
    }

    void tap(Bitmap bmp, int x, int y) { sh.tapWork(bmp, x, y); }

    /** 拽 (编队/防守队拖角色)。 */
    void drag(Bitmap bmp, int sx, int sy, int dx, int dy) {
        sh.swipeWork(bmp, sx, sy, dx, dy, 500);
    }

    void swipe(Bitmap bmp, int x1, int y1, int x2, int y2, long ms) {
        sh.swipeWork(bmp, x1, y1, x2, y2, ms);
    }

    // ==================================================================
    //  状态与日志
    // ==================================================================

    private void pushUi() {
        if (bar == null) return;
        long sec = (System.currentTimeMillis() - lastActMs) / 1000;
        bar.setStat(stepText + " ", sec + "s", roundsDone + "场", roundsDone, 0);
    }

    private void setStep(String s) {
        stepText = s;
        if (bar != null) bar.setText(s);
    }

    void log(String msg) { SgmLog.i("pf", msg); }

    void setStepText(String s) { setStep(s); }

    static void sleep_(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
