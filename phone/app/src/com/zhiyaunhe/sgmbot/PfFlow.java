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

    /** 结构性停止原因 (null = 继续跑)。 */
    private String fatal;

    public PfFlow(ShizukuCtl sh, TplStore tpls, OverlayBar bar) {
        this.sh = sh;
        this.tpls = tpls;
        this.bar = bar;
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
        roundsDone = 0;
        sessionFightNo = 0;
        lastActMs = System.currentTimeMillis();

        SgmLog.i("pf", "PF 主循环开始 — 每场重选角色 | 匹配=" + Vision.modeText()
                + (quiet ? " | 静默模式(不主动导航)" : ""));

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
    }

    /**
     * 状态机单步: 截图一次并按优先级处理当前界面 —— PC {@code PfBot.step()} 的直译。
     */
    private void step(Vision.Frame work, Bitmap bmp, AtomicBoolean run, JSONObject c) {
        // ① 弹窗 (能量/streak/OPTIONS): 遮罩会压暗底下按钮, 必须最先剥。
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
        // ④ 误入角色详情页: 返回。
        double[] det = match(work, Pf.T_DETAIL, Pf.ROI_FULL, Pf.TH_FIGHT);
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
        double[] hall = match(work, Pf.T_HALL_PRIZE, Pf.ROI_FULL, Pf.TH_HALL_PRIZE);
        if (hall != null) {
            unknownTries = 0;
            setStep("主大厅");
            log("在主大厅 — 点 PRIZE FIGHTS 进入 PF");
            tapCenter(bmp, work, hall, Pf.T_HALL_PRIZE);
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
        if (team.findLockPopup(work)) {
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
                    || match(w2, Pf.T_HALL_PRIZE, Pf.ROI_FULL, Pf.TH_HALL_PRIZE) != null;
            if (!known) {
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
        boolean launched = false;
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
            double[] hall = match(work, Pf.T_HALL_PRIZE, Pf.ROI_FULL, Pf.TH_HALL_PRIZE);
            if (hall != null) {
                log("导航: 在大厅, 点 PRIZE FIGHTS");
                tapCenter(bmp, work, hall, Pf.T_HALL_PRIZE);
                sleep_(2500);
                continue;
            }
            if (!launched) {
                launched = true;
                log("导航: 补拉起 Skullgirls (冷启动兜底)");
                sh.exec("monkey -p com.autumn.skullgirls -c android.intent.category.LAUNCHER 1");
            }
            /* 兜底脱离: 真机**大厅顶栏没有房子**(那个位置是齿轮, 点它会开 OPTIONS),
             * 所以默认走**系统返回键** —— 实测对页面型弹窗有效 (PROFILE 0.19→0.98 回大厅),
             * 只有 Unity 自己的 OPTIONS 菜单不吃返回键 (那款由 findPopupX 的 options_x 关)。
             * 房子模板仅在 PF 内部 (hub 等) 才命中, 命中就用它。 */
            double[] home = match(work, Pf.T_HOME, Pf.ROI_HOME, 0.70);
            if (home != null) {
                tapCenter(bmp, work, home, Pf.T_HOME);
            } else {
                log("导航: 未知界面, 按系统返回键尝试脱离");
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
     * 是否在编队页。
     *
     * <p>⚠️ PC 用 {@code DRAGHINT} 单判据, 但**真机上该提示不出现** (2026-10-15 实测:
     * 编队页截图里没有 DRAG 字样, 模板最佳只有 0.443)。而编队页右上一定有 FIGHT!
     * ⇒ 改成"两者任一命中"。这也是 step() 用 FIGHT 分支兜住编队页的原因。
     */
    private boolean inTeamPage(Vision.Frame w) {
        return match(w, Pf.T_DRAGHINT, Pf.ROI_DRAGHINT, Pf.TH_DRAGHINT) != null
                || match(w, Pf.T_FIGHT, Pf.ROI_TOPRIGHT, Pf.TH_FIGHT) != null;
    }

    /** 若已在编队页则直接修正; 否则先从 VS 界面点进编队。 */
    private void fightFlow(Vision.Frame work, Bitmap bmp, AtomicBoolean run) {
        setStep("进编队");
        if (!inTeamPage(work)) {
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
        if (!inTeamPage(wi)) {
            log("未进入编队页, 回主循环重试");
            return;
        }

        int rotations = 0;
        while (run.get()) {
            if (!team.fixTeam(this, rotations > 0)) {
                fatal = "编队失败: 无可用能量角色";
                log("编队失败: 无可用能量角色, 停止");
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
            log("战斗已开始, 等待结束…");
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
        double[] r = match(work, Pf.T_SRV_OK, Pf.ROI_FULL, 0.75);
        if (r != null) return centerOf(work, r, Pf.T_SRV_OK);
        r = match(work, Pf.T_SRV_RETRY, Pf.ROI_FULL, 0.75);
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
