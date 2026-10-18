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

    /** 场次锁定 (保证一直打同一场, 见 {@link PfArena} 类注释)。 */
    private final PfArena arena;

    /** 正在跑的那个实例 (给监控页读实时状态; 服务里同一时刻只有一个)。 */
    private static volatile PfFlow live;
    /** 当前这一轮循环的停止闸门 (= BotService 传进来的 RUN)。
     *  ⚠️ 2026-10-17 实栽: sleep_ 原来是裸 Thread.sleep, 而循环里到处是 2.5~6s 的长等待
     *  (导航/选人/冷启动甚至有 300s 轮询) ⇒ 点「暂停」后要等当前这串睡满才真停,
     *  实测 3 分 20 秒 —— 浮标还挂在屏幕上, 用户以为"点了没反应"。
     *  现在 sleep_ 按 60ms 分段睡, 每段查一次闸门。 */
    private static volatile java.util.concurrent.atomic.AtomicBoolean gate;

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
        this.arena = new PfArena(this, sh);
        /* 场次表 (hub 卡名/卡位 → tag + 条件)。⚠️ 只载一次, 失败也只是退化成 unknown, 不拦流程。
         * App 未起 (单测/异常路径) 时不给 Context, 留给下一次 classify 时的懒加载。 */
        if (App.inst != null) PfArenaTable.load(App.inst);
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
     * 「读取场次」—— 把 App「PF 设置」页选定的场次过一遍场次表, 算出本场的取值条件。
     *
     * <p>⚠️⚠️ 2026-10-17 之前这块是断的: {@link PfArenaTable} 只有一句 {@code load()},
     * 整张 22 场次的表**从未被查询过** —— 用户的原话是「你也没有读取场次的那个逻辑没有搬过来」。
     * 现在至少把"用户选的场次 → tag/能量/分数上限/出战规则"这条链路接通并打进日志, 让
     * 条件来源可见 (而不是像以前那样只能靠 config 里手填的裸数字)。</p>
     *
     * <p>⚠️ 仍未实装的是**自动认卡名**: App 内没有 OCR (PC 那套 PaddleOCR 的 det+rec 移植
     * 代价另议), 所以现在靠"用户选 + 指纹锁"; 卡位规则 (月场固定占 #1/#2) 与名称表两条
     * 路径都已经在 {@link PfArenaTable#classify} 里备好, 等卡名来源接上即可直接用。</p>
     */
    private void readArena(JSONObject c) {
        try {
            PfArenaTable.load(App.inst);
            JSONObject p = c.optJSONObject("pf");
            String want = p == null ? null : p.optString("arena", "");
            if (want == null || want.isEmpty()) {
                log("读取场次: 未指定目标场次 — 按**卡位**的保守口径 (4kw 分数 / 4 能量 / 无规则)");
                arenaName = null;
                return;
            }
            PfArenaTable.Spec sp = PfArenaTable.spec(want);
            if (sp == null) {
                log("读取场次: 「" + want + "」不在场次表里 (拼错? 新场次?) — 保守放行, 不做规则约束");
                arenaName = null;
                return;
            }
            arenaName = sp.name;
            log("读取场次: " + sp.name + " → 类别 " + sp.tag
                    + " | 能量 " + sp.energy() + " 钉"
                    + " | 分数上限 " + (sp.noLimit() ? "无上限(月场, 判据跳过)"
                            : String.valueOf(sp.scoreTarget()))
                    + " | 出战规则 " + (sp.rule() == null ? "无" : sp.rule())
                    + (sp.matched ? "" : " ⚠️ 未命中人工表, 取保守值"));
            /* ⚠️ 场次表说了"这一场该用多少能量/什么规则", 但**不在这里改用户的配置**:
             *    写入只在「PF 设置」页做 —— 一处写、多处读, 否则又是两套状态。
             *    这里只做**一致性核对**, 不一致就提示 (看日志就知道要不要回设置页点一下)。 */
            if (p != null && p.has("energy_cost") && p.optInt("energy_cost") != sp.energy()) {
                log("⚠ 场次表说这一场要 " + sp.energy() + " 钉, 而配置里是 "
                        + p.optInt("energy_cost") + " — 想按表走请到「PF 设置」页重新点一次该场次");
            }
        } catch (Throwable t) {
            log("读取场次失败 (不致命): " + t);
        }
    }

    /** 当前目标场次名 (未指定 = null) —— 监控页/状态页显示用。 */
    volatile String arenaName;

    /** 最近一次 hub 扫描到的卡数 (-1 = 还没扫过) —— /pf/state 与监控页显示。 */
    volatile int hubCards = -1;

    /** 大厅归位滑动计数 (gotoHub 每轮重置)。滑到最左最多 3 次, 之后不再滑。 */
    private int hallSlides;
    private static final int HALL_SLIDE_MAX = 3;

    /**
     * 扫一遍 hub 轮播 (导航到位后自动调用; 2026-10-18 用户: 「把 navigate 和扫描加到一起」)。
     *
     * <p>⚠️ 它**不含场次名**: App 没有 OCR, 只能给出"今天 hub 上几张卡"(见 PfScan 类注释)。
     * 价值在两点: ① 让人在日志/状态里看见今天的场次数量 (而不是盲点居中卡);
     * ② 扫描完轮播停在**最左**, 之后点 PLAY! 进场的是最左那张 —— 与"月场固定占 #1/#2"
     * 的卡位口径对齐 (PfArenaTable 的位置优先规则)。</p>
     */
    private void scanHub(AtomicBoolean run) {
        try {
            Bitmap b = sh.capture();
            if (b == null) {
                log("场地扫描: 截屏失败 — 跳过 (不影响进场)");
                return;
            }
            log("场地扫描: 开始 (先定滑动方向 → 走到一端 → 反向逐卡位记指纹)");
            java.util.List<PfScan.Card> cards = PfScan.scan(sh, b);
            hubCards = cards.size();
            if (hubCards <= 0) {
                log("⚠ 场地扫描: 一个卡位都没读到 —— 界面不是 hub? 或轮播滑动没生效");
            } else {
                /* ⚠️ 文案必须说"卡位"不要说"张卡": 轮播不吸附时同一张卡可能占两格,
                 * 这个数只是**上限**。权威的"今天开哪些场"是 PfRotation (站点轮换公式)。 */
                int today = PfArenaTable.namesToday().size();
                SgmLog.i("pf", "场地扫描: hub 上扫到 " + hubCards + " 个卡位"
                        + " (上限; 非卡数) | 轮换公式今日 " + today + " 场 "
                        + PfArenaTable.namesToday(), b);
            }
            PfScan.save(cards);
            noteProgress();                 // 扫完算进展, 别让看门狗当成卡死
        } catch (Throwable t) {
            log("场地扫描异常 (不影响主链): " + t);
        }
    }

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
        Shots.configure(c);          // 带图日志的开关/上限 (log.shots_*)

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
        lastProgressMs = System.currentTimeMillis();
        stuckLevel = 0;
        lastSceneSeen = null;
        roundsDone = 0;
        sessionFightNo = 0;
        lastActMs = System.currentTimeMillis();

        gate = run;                     // sleep_ 的分段睡要查它 (见 gate 字段注释)
        SgmLog.i("pf", "PF 主循环开始 — 每场重选角色 | 匹配=" + Vision.modeText()
                + (quiet ? " | 静默模式(不主动导航)" : ""));
        readArena(c);                      // 「读取场次」: 选定的场次 → 分类 → 条件 (日志可见)
        live = this;                       // 监控页从这里读实时状态
        startMs = System.currentTimeMillis();

        /* 起步: 先确保站在 PF hub (PC 的 run() 在每次「开始」都走 navigate_to_scene)。
         * quiet 模式跳过 —— 给"人已经停在编队页"的调试场景用。 */
        if (!quiet && !gotoHub(run, c)) {
            if (fatal == null) fatal = "导航失败";
            return;
        }
        /* ⚠️ 2026-10-18 用户: 「把 navigate 和扫描加到一起」——
         * 导航一到 hub 就**顺手扫一遍轮播** (PC 的 scan_arenas 同一步), 不再让人单独触发。
         * 扫描会左右滑几张卡 (约 10~15 秒), 但它把"今天 hub 上到底几张"变成日志里的事实,
         * 之后选场次/排期才有依据。
         * ⚠️ 扫描**不读场次名** (App 没有 OCR, 见 PfScan 类注释), 只给卡数与指纹;
         *    所以它只做"看得见", 不做"替你选"。
         * ⚠️ quiet 模式 (人已停在编队页) 不扫 —— 那时屏幕上不是 hub, 扫了只会乱滑。 */
        if (!quiet) scanHub(run);

        while (run.get() && fatal == null) {
            /* ---- 看门狗: 长时间没有任何实质进展就自动脱困 ----
             * ⚠️⚠️ 2026-10-16 实栽, 这是"几小时才 80 场"的头号原因: 日志里有一段
             * **18326 秒(5.1 小时)的空档**, 期间刷了 8600 行同样的
             *     「不在编队编辑器 → 点 TEAM 菱形 → 未进入 → 回主循环」
             * 每 4 秒一次, 没有任何机制打断它 —— 因为 fightFlow 只是"返回", 而 step()
             * 下一轮又走同一条路点同一个坐标。单点失败不可怕, **没有脱困升级链**才可怕。
             * 现在: 超过 STUCK_MS 没进展就按 返回键 → 重拉游戏 → 重新导航 逐级升级,
             * 每级之间重新计时, 不让它停在同一级。 */
            long nowMs = System.currentTimeMillis();
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
            /* 起步阶段不算卡住: 第一场还没开打时, 导航本身就要花时间 (冷启动/结算残留),
             * 那时按返回键只是噪声。第一场开打之后才开始计时。
             * ⚠️ 这条检查放在**截图之后**: 升级脱困时要把"卡住那一刻的画面"存进日志。 */
            if (roundsDone > 0 && nowMs - lastProgressMs > STUCK_MS) {
                SgmLog.i("pf", "看门狗: 超 " + (nowMs - lastProgressMs) / 1000
                        + "s 无进展 — 升级脱困", bmp);
                escalateStuck(run, c);
                lastProgressMs = System.currentTimeMillis();
            }
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

        /* ---- ⓪b 连接中/加载中: **只等, 绝不按返回键** (2026-10-16 用户: 「又卡进 leaderboard 界面了」) ----
         * 现场抓到的是 **「CONNECTING...」**(断网重连, 中央一块红骷髅徽章面板 + 右侧 120 倒计时)。
         * 旧行为: 判"未知界面" → 等 3s → **按系统返回键** → 越按越乱, 而且期间还会被
         * `findLockPopup` 的绿色判据误认成"难度锁定弹窗"去点 CONTINUE。
         *
         * 判据: **整屏近黑占比 > 0.92**。实测 CONNECTING = **0.985**,
         * 而十个真实界面 (编辑器/预览/hub/选对手/结算/弹窗…) 全部 **≤ 0.774**
         * —— 中间空档很大, 不会把正常界面误判成连接中。
         * 等待是有界的: 连等 CONNECTING_WAIT_MS 之后交回主循环让看门狗升级脱困
         * (断网久了确实需要重拉游戏, 但不能一上来就按返回键)。 */
        if (connectingFrame(work)) {
            if (connectingSince == 0) connectingSince = System.currentTimeMillis();
            long waited = System.currentTimeMillis() - connectingSince;
            if (waited < CONNECTING_WAIT_MS) {
                if (System.currentTimeMillis() - lastNoteMs > 15_000) {
                    lastNoteMs = System.currentTimeMillis();
                    log("连接中/加载中 (近黑帧) — 等待, 不按返回键 (已等 " + waited / 1000 + "s)");
                }
                setStepText("连接中… 等待");
                sleep_(4000);
                return;
            }
            log("连接中已超 " + CONNECTING_WAIT_MS / 1000 + "s — 交回主循环让看门狗脱困");
            connectingSince = 0;
        } else {
            connectingSince = 0;
        }

        /* ---- ⓪a 服务器错误弹窗 —— 排在最前, 比 X 弹窗还靠前 ----
         * (2026-10-17 用户: 「又有弹窗 你这个没处理？」) 现场: 「We're having some difficulty
         * reaching our servers」盖在**编队预览页**上, 而预览页右上还有 FIGHT! ⇒ PfScene 判
         * TEAM_PREVIEW ⇒ 直接进 fightFlow ⇒ 「点 TEAM 菱形 → 未进入编队编辑器」死循环 6 小时
         * (打到 83 场后卡死)。老代码只在 unknown 恢复链里查, 而这里**根本没走到那一步**。
         *
         * 判据是**像素色块**不是模板 (见 PfScene.serverPopup 注释: 全屏搜 srv_retry 要几十秒,
         * 只有色块才付得起"每轮都查"的代价), 所以放在 X 弹窗之前也不心疼。
         * 点 RETRY 而不是 CANCEL —— 这是网络请求失败, 重试才是本意。
         * ⚠️ 点完要 noteProgress(): 弹窗不消失时界面不变, 看门狗会以为卡死然后去重拉游戏。 */
        if (PfScene.serverPopup(work)) {
            unknownTries = 0;
            SgmLog.i("pf", "服务器错误弹窗 (CANCEL/RETRY) — 点 RETRY", bmp);
            tap(bmp, PfScene.serverRetryAt());
            sleep_(2500);
            noteProgress();
            return;
        }

        /* ---- ⓪ 弹窗**必须最先剥** (2026-10-16 实栽, 用户: 「能量看错了, 才导致这个弹窗」) ----
         * 原来弹窗检查在快路径**之后**, 于是: PfScene 把"编辑器 + ENERGY REFILL 弹窗"认成
         * TEAM_EDITOR (漏斗露在弹窗右侧, 0.785 > 阈值) ⇒ switch 直接跳进 fightFlow,
         * ①/①b 的弹窗链**根本没走到** ⇒ 弹窗永远没人关 ⇒ 它盖住出战槽与候选列 ⇒
         * 钉条读数全 0 ⇒ bot 判"三个槽都要换人"却一个都拖不动 ⇒ 白等 30 轮能量恢复
         * (每轮 60s = 半小时)。日志现场「槽位能量 [0,0,0] / 候选区能量 [0,...]」刷屏,
         * 而截图里 ENERGY REFILL 清清楚楚。
         *
         * 为什么不担心误报: 四个 X 模板在**真弹窗**上 0.964 / 0.834, 在**十个普通界面**
         * (编辑器/预览/hub/选对手/结算…) 上全部 ≤0.358 —— 阈值 0.70 正中空档。 */
        int[] px0 = findPopupX(work);
        if (px0 != null) {
            unknownTries = 0;
            /* 紧跟在"点 FIGHT"之后的弹窗 = ENERGY REFILL ("Someone needs a breather!")。
             * 它出现就说明**能量门槛判低了** (角色场每场要 5 钉, 而配置是 4) ⇒ 自我纠正 +1。
             * 这就是「能量看错了才导致这个弹窗」的正解: 让 bot 从游戏自己的拒绝里学, 
             * 比猜场次级能量靠谱 (卡名还没 OCR, 认不出这是不是角色场)。 */
            if (justFoughtTicks > 0 || System.currentTimeMillis() - lastFightTapMs < 20_000) {
                team.bumpEnergyCost();
            }
            SgmLog.i("pf", "弹窗 (能量/streak/OPTIONS), 关闭", bmp);
            tap(bmp, px0);
            sleep_(1500);
            return;
        }

        /* ---- ⓪a (已上移到 X 弹窗之前) ---- */

        /* ---- 快路径: 界面识别 (PfScene) ----
         * ⚠️ 原来下面是一条**串行的全帧匹配链**, 每一步依次对十几个模板做 ROI_FULL 全搜,
         * 真机实测一轮循环慢到 14 秒 —— 识别比操作还贵。
         * 现在先花一顿便宜的代价认出"这是哪一页"(小 ROI 模板 + 像素特征), 认出来就
         * **直接跳到对应分支**, 不再逐个试。认不出才回退原链 (宁可慢也别误判)。
         * ⚠️ 弹窗 (上面那一段) 一定要排在页面分支**之前** —— 它压在别的界面之上。 */
        PfScene.S sc = scene.classify(work);
        /* 界面一变就是进展 (看门狗的计时基准) —— 卡死时界面是不变的。 */
        if (sc != lastSceneSeen) {
            if (lastSceneSeen != null && sc != PfScene.S.UNKNOWN) noteProgress();
            /* 界面切换 = **最该带图**的一刻: 排查时一眼看出"当时到底停在哪一页、长什么样"。 */
            SgmLog.i("pf", "界面 " + (lastSceneSeen == null ? "?" : lastSceneSeen) + " → " + sc, bmp);
            lastSceneSeen = sc;
        }
        if (sc != PfScene.S.UNKNOWN) {
            if (sc != PfScene.S.LEADERBOARD) lbHits = 0;   // 离开排行榜就把连击计数清掉
            switch (sc) {
                case LEADERBOARD:
                    /* 用户 2026-10-18: 「不要等个什么 30 秒, 然后才触发回到上一页的全局兜底」
                     * —— 有判据就当轮退, 不等看门狗。 */
                    unknownTries = 0;
                    setStep("退出排行榜");
                    exitLeaderboard(bmp);
                    return;
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
                    /* ⚠️⚠️ 点 PLAY! 之前**必须核对场次** (2026-10-16 用户: 「掉到别的场次里了」)。
                     * 看门狗会重拉游戏 → 从大厅重进 hub → 轮播位置变了 ⇒ 盲点居中卡就进错场。
                     * ensureTarget 会拿指纹核对, 不对就滑轮播找回; 找不回就**不进场**。 */
                    if (!arena.ensureTarget(bmp)) {
                        log("PF hub: 没确认到目标场次 — 这次不进场, 退回导航重试");
                        noteProgress();
                        return;
                    }
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
                    /* 同 gotoHub: 不许盲点, 一律走「滑到最左 + 读文字条 + 相对偏移」。 */
                    log("在主大厅 — 读 PRIZE FIGHTS 进入 PF");
                    tapHallPrizeFights(bmp, work);
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
            SgmLog.i("pf", "弹窗 (能量/streak/OPTIONS), 关闭", bmp);
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
            /* 同 gotoHub 的 HUB 分支: **先核对场次再点 PLAY** (见 PfArena 类注释)。 */
            if (!arena.ensureTarget(bmp)) {
                log("PF hub: 没确认到目标场次 — 这次不进场");
                noteProgress();
                return;
            }
            log("PF hub 就绪 — 点 PLAY! 进场");
            tapCenter(bmp, work, hub, Pf.T_HUB_PLAY);
            sleep_(3000);
            return;
        }
        // ⑥ 游戏主大厅: 点 PRIZE FIGHTS 菱形进 PF hub。
        if (inHall(work)) {
            unknownTries = 0;
            setStep("主大厅");
            /* ⚠️⚠️ 2026-10-18 用户: 「首页是可以拉动的, 你得要么拉最左边, 然后 ocr 读
             * prize fighter 才行」—— 两件事都指向同一个根因:
             *   ① 大厅是**可横向拖动的地图**, 节点位置不固定 ⇒ 原来硬编码的
             *      HALL_PRIZE_TAP(240,310) 会落到 GUILDS 上 (实测就卡在"在大厅, 点
             *      PRIZE FIGHTS"无限循环);
             *   ② 整块菱形模板不可靠: 里面带**每秒变的倒计时**, 且 PRIZE/STORY/EVENTS
             *      三个菱形版式雷同 ⇒ 会互相抢分。
             * 现在统一走 {@link #tapHallPrizeFights}: 读「PRIZE FIGHTS」**文字条**
             * (实测 0.95 vs 异菱形 0.20, 等价于读文字) 定位, 点它的**相对偏移**。 */
            tapHallPrizeFights(bmp, work);
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
        /* ⚠️ 未知界面每 3 秒就来一次 ⇒ 只在**第 1 次和每第 5 次**存图, 否则光是它就能把
         * 图片量刷爆 (一个卡死的小时 = 1200 张)。 */
        if (unknownTries <= 1 || unknownTries % 5 == 0) {
            SgmLog.i("pf", "未知界面, 等待 3s (第 " + unknownTries + " 次)", bmp);
        } else {
            log("未知界面, 等待 3s (第 " + unknownTries + " 次)");
        }
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
     * 大厅里进 PF: 用「PRIZE FIGHTS」**文字条**定位, 点它的**相对偏移**。
     *
     * <p>为什么是相对偏移而不是绝对坐标: 大厅是**可横向拖动的地图**, 同一个节点在不同时刻
     * 落在不同的 x 上。文字条一旦命中, 点击点就跟着走, 不需要"先拉最左"这个前提。</p>
     *
     * <p>匹配必须走 {@link #matchFull} (强制全搜): 文字条只有 148x36, 金字塔在 0.5x/0.25x
     * 上会把它的峰值削到 0.15~0.18 ⇒ 用 {@code match()} 会**永远读到 0**。</p>
     *
     * <p>文字读不到时才右滑归位重试 (抽屉式: 最多 {@link #HALL_SLIDE_MAX} 次) ——
     * 右滑 = 内容右移 = 露出地图左边, 横幅从左边进来。三次都没有才退固定坐标兜底。</p>
     *
     * @return 总是 true (调用方一律 continue); 留返回值只为将来可能"什么都没做"
     */
    private boolean tapHallPrizeFights(Bitmap bmp, Vision.Frame work) {
        double[] txt = matchFull(work, Pf.T_HALL_PRIZE_TEXT, Pf.ROI_FULL, Pf.TH_HALL_PRIZE_TEXT);
        if (txt != null) {
            int[] c = centerOf(work, txt, Pf.T_HALL_PRIZE_TEXT);
            int tx = c[0] + Pf.HALL_PRIZE_DX, ty = c[1] + Pf.HALL_PRIZE_DY;
            log("大厅: 读到 PRIZE FIGHTS 文字 (" + Math.round(txt[2] * 100) / 100.0
                    + ") @(" + (int) txt[0] + "," + (int) txt[1] + ") — 点横幅 (" + tx + "," + ty + ")");
            tap(bmp, tx, ty);
            sleep_(2500);
            return true;
        }
        if (hallSlides < HALL_SLIDE_MAX) {
            hallSlides++;
            log("大厅: 没读到 PRIZE FIGHTS 文字 — 第 " + hallSlides + " 次右滑把地图拉回最左");
            swipe(bmp, 380, 340, 1050, 340, 420);
            sleep_(900);
            return true;
        }
        log("大厅: 文字一直读不到 — 退回固定坐标兜底 (" + Pf.HALL_PRIZE_TAP[0]
                + "," + Pf.HALL_PRIZE_TAP[1] + ")");
        tap(bmp, Pf.HALL_PRIZE_TAP);
        sleep_(2500);
        return true;
    }

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
        hallSlides = 0;                  // 每轮导航重新归位 (大厅位置可能被上次拖动改过)
        hallSlides = 0;                  // 每轮导航重新归位 (大厅位置可能被上次拖动改过)
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
                /* ⚠️⚠️ 2026-10-19 实栽: 这里原本是**固定坐标盲点** HALL_PRIZE_TAP, 而
                 * {@link #step} 里的大厅分支早就改成 {@link #tapHallPrizeFights} 了。
                 * 两处各有一份 ⇒ 只改一处等于没改: 这一夜在大厅连点 21 次 (65,320) 全落空
                 * (地图不在最左 / 该点被横幅外的区域遮住), 300s 导航预算烧光。
                 * 现在两处共用同一个方法 (滑到最左 → 读文字条 → 点相对偏移)。 */
                tapHallPrizeFights(bmp, work);
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
                /* ⚠️⚠️ 顺序不能反 (2026-10-18 实栽): dumpUnknown 在真机上要跑 **~3 分钟**
                 * (17 个模板 x 全帧 step=1; 注释里"一次几百 ms"是 PC 估算)。放在返回键
                 * **之前**, 一次判不出就吃掉 300s 导航预算的一半 —— 日志表现为"按返回键"
                 * 之后 3 分钟空窗, 然后直接超时。先脱困, 再存证。 */
                sh.exec("input keyevent 4");
                dumpUnknown(bmp, work);
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
        /* ⚠️ 2026-10-18 加**文字**判据 (用户: 「ocr 读 prize fighter」): 原来那两条整块菱形
         * 模板里带**倒计时数字**, 每几秒变 ⇒ 实测同一画面只剩 0.25, 认不出大厅, 于是
         * gotoHub 永远停在"未知界面"、step 卡在"回到 PF hub" (导航彻底不工作)。
         * 文字条不含倒计时, 实测大厅 1.000 / hub 0.358。 */
        return matchFull(work, Pf.T_HALL_STORY_TEXT, Pf.ROI_FULL, Pf.TH_HALL_STORY_TEXT) != null
                || matchFull(work, Pf.T_HALL_PRIZE, Pf.ROI_FULL, Pf.TH_HALL_PRIZE) != null
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

    /**
     * 从 LEADERBOARDS 排行榜页**当轮**退出去 —— 不等看门狗。
     *
     * <p>2026-10-18 用户: 「不要等个什么 30 秒, 然后才触发回到上一页的这个全局兜底」。
     * 之前这页没有任何专属判据 ⇒ PfScene 判 UNKNOWN ⇒ 串行链也认不出 ⇒ 只能等
     * {@code STUCK_MS}(90s) 的第 1 级"系统返回键"。现在有了标题判据, 一帧就能脱身。</p>
     *
     * <p>退出手段: 先点**左上角返回箭头** (真机实测 work 36,30) —— 它是页面自己的返回,
     * 不依赖系统返回键 (这个游戏的返回键有时被页面吃掉, 按错还会叠一层);
     * 连点两次还没出去 (点位漂了/被遮挡) 才退回 {@code input keyevent 4}。</p>
     */
    private void exitLeaderboard(Bitmap bmp) {
        lbHits++;
        log("误入 LEADERBOARDS — 当轮退出 (第 " + lbHits + " 次)");
        if (lbHits <= 2) tap(bmp, Pf.LB_BACK);
        else {
            log("连点返回箭头 2 次仍没退出 — 改用系统返回键");
            sh.exec("input keyevent 4");
        }
        sleep_(800);
        /* 退出动作**不算进展**: 万一这个判据是误报 (排行榜其实不在), noteProgress 会让
         * 看门狗一直重置 ⇒ 真的卡死时反而救不回来。只记账不重置计时。 */
        lastActMs = System.currentTimeMillis();
    }

    /**
     * TEAM 菱形命中位置的中心 60x60 是否为**深蓝底** (菱形的牌面颜色)。
     *
     * <p>背景: 内接灰度模板缩小后, 战斗页 (SHOWTIME 灯泡字) 在 ROI 中央能撞出 0.89 ——
     * 和真菱形 (0.88~0.90) 分不开。颜色一锤定音: 真菱形中心深蓝占比实测 0.31 (三张
     * 不同对手的帧全部 0.309~0.310), SHOWTIME 处 0.018, 选对手页 0.0。阈值 0.15 居中。
     * 判据 {@code b>r+12 && g>r && r<110}: 深蓝 #2a3a5e 类 (B≈94 G≈58 R≈42)。</p>
     */
    private boolean diamondBlueOk(Vision.Frame w, double[] hit) {
        Vision.Frame t = tpls.get(Pf.T_TEAM_DIAMOND);
        int tw = t == null ? 56 : t.w, th = t == null ? 72 : t.h;
        int cx = (int) hit[0] + tw / 2;
        int cy = (int) hit[1] + th / 2;
        int blue = 0, total = 0;
        for (int y = cy - 30; y < cy + 30; y++) {
            for (int x = cx - 30; x < cx + 30; x++) {
                if (x < 0 || y < 0 || x >= w.w || y >= w.h) continue;
                int argb = w.px[y * w.w + x];
                int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
                total++;
                if (b > 55 && b < 150 && b > r + 12 && g > r && r < 110) blue++;
            }
        }
        return total > 0 && blue >= total * 0.15;
    }

    /** 若已在编队编辑器则直接修正; 否则先点 TEAM 菱形进入。 */
    private void fightFlow(Vision.Frame work, Bitmap bmp, AtomicBoolean run) {
        setStep("进编队");
        if (!inTeamEditor(work)) {
            log("不在编队编辑器 — 点 TEAM 菱形进入");
            /* ⚠️ 必须**模板定位**, 不能用固定坐标 —— 菱形位置随场次变 (见 Pf.TEAM_DIAMOND 注释)。 */
            double[] dm = matchFull(work, Pf.T_TEAM_DIAMOND, Pf.ROI_TEAM_DIAMOND,
                    Pf.TH_TEAM_DIAMOND);
            if (dm == null) {
                /* 预览页是淡入的, 这一帧可能是半透明过渡帧 ⇒ 补一帧再看 (见 TH_TEAM_DIAMOND)。 */
                sleep_(900);
                Bitmap bd = sh.capture();
                if (bd != null) {
                    Vision.Frame wd = Vision.toWork(bd);
                    dm = matchFull(wd, Pf.T_TEAM_DIAMOND, Pf.ROI_TEAM_DIAMOND,
                            Pf.TH_TEAM_DIAMOND);
                    if (dm != null) { bmp = bd; work = wd; }
                }
            }
            if (dm != null && !diamondBlueOk(work, dm)) {
                /* 灰度模板在战斗页 (SHOWTIME) 的 ROI 中央能撞出 0.89 —— 靠颜色校验挡:
                 * 真菱形中心是**深蓝底**, 正例占比 0.31 / 该反例 0.02 (阈值 0.15)。 */
                log("TEAM 菱形候选位置没有深蓝底 (疑似别的页面) — 视为未命中");
                dm = null;
            }
            if (dm != null) {
                teamDiamondMiss = 0;
                tapCenter(bmp, work, dm, Pf.T_TEAM_DIAMOND);
            } else {
                /* ⚠️⚠️ 2026-10-18 用户实栽, 这条兜底**必须删掉**, 不许盲点。
                 *
                 * 它原本的算盘是"菱形位置漂了, 点个大概也能中"。实测代价是灾难性的:
                 * 22:56:56 菱形模板 0.150 (阈值 0.62) —— 程序自己都承认**没看见菱形** ——
                 * 却仍然点了 work(638,444)。而**同一坐标在 LEADERBOARDS 页上正好是入口**,
                 * 于是点进排行榜, 再也没自己出来 (只能等看门狗 90s, 用户看到的就是"卡住")。
                 *
                 * 教训写成一句话: 判据缺失时点下去不是"可能命中", 而是"可能点中别的页面的
                 * 致命按钮"。宁可这一轮什么都不做, 让主循环重新识别 (scene 会重判, 该退就退),
                 * 也别蒙一个坐标。 */
                /* 记下**峰值** (而不是只写"没命中"): 这类故障光看结论分不清是"模板形态
                 * 变了"还是"页面根本不对"。SCAN_ALL + step=1 只在这个分支跑, ROI 340x220
                 * 不大, 又是 miss 才走, 成本可接受。 */
                double peak = -1;
                Vision.Frame tt = tpls.get(Pf.T_TEAM_DIAMOND);
                if (tt != null) {
                    double[] pr = Vision.findScored(work, tt, Pf.ROI_TEAM_DIAMOND,
                            Vision.SCAN_ALL, -1, 1);
                    if (pr != null) peak = pr[2];
                }
                teamDiamondMiss++;
                log("TEAM 菱形不在画面里 (峰值 " + (peak < 0 ? "?" : Math.round(peak * 100) / 100.0)
                        + ", 阈值 " + Pf.TH_TEAM_DIAMOND + ") — 不盲点, 第 " + teamDiamondMiss + " 次");
                if (teamDiamondMiss >= 3) {
                    /* 不盲点 ≠ 干等。判据连续失败 3 次说明这一页不是靠"再看一帧"能好的
                     * (模板形态不匹配 / 页面卡在半透明过渡), 必须换手段退出重判。 */
                    teamDiamondMiss = 0;
                    log("连续 3 次认不出 TEAM 菱形 — 按返回键脱离当前页重新定位");
                    sh.exec("input keyevent 4");
                    sleep_(1500);
                } else {
                    sleep_(900);       // 别空转到 0.5s 一轮 (实测 90s 能转 172 次)
                }
                return;
            }
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
            /* ⚠️ 轮次**必须封顶** (2026-10-16 实测: 有一场为了凑齐 3 个槽位跑了 **43 次拖拽 /
             * 14 轮 fixTeam**, 单场耗时 193 秒, 而战斗本身只花了 12 秒)。
             * 拖拽后槽位能量回读不稳定时, fixTeam 会一次次返回 false, 每轮还要做 4 次归零滑动
             * + 全列读数 ≈ 10 秒 —— 不封顶就等于把时间全烧在重复读同一批卡上。
             * 3 轮是经验值: 正常情况下 1 轮就够 (能量齐), 最多再试 2 轮覆盖抖动。 */
            if (rotations >= FIX_TEAM_MAX_ROUNDS) {
                log("编队已试 " + rotations + " 轮仍未就绪 — 本轮放弃, 回主循环");
                noteProgress();
                return;
            }
            rotations++;
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
            lastFightTapMs = System.currentTimeMillis();   // 用于识别"紧随其后的 ENERGY REFILL"
            sleep_(2500);
            Bitmap bp = sh.capture();
            if (bp == null) return;
            Vision.Frame wp = Vision.toWork(bp);

            if (team.findLockPopup(wp)) {           // 角色场首入: 锁定难度确认 (无 X)
                /* ⚠️ 复核一帧再认 (2026-10-16 实栽): 点完 FIGHT 之后的**过渡帧**也会误报
                 * (这条误报链在 ⑨ 的注释里记过一次: 排行榜页也有绿块 ⇒ 误点 → 判未知 →
                 * 返回键 → hub → PLAY → 又误报 = 死循环)。真弹窗会一直挂着, 过渡帧不会,
                 * 所以"隔 1.2s 再看一眼"就能把过渡帧滤掉, 又不会漏掉真弹窗。 */
                sleep_(1200);
                Bitmap bc = sh.capture();
                if (bc == null || !team.findLockPopup(Vision.toWork(bc))) {
                    log("难度锁定弹窗复核不过 — 判为过渡帧误报, 忽略");
                } else {
                    log("难度锁定弹窗: 点 CONTINUE 锁定, 交回主循环");
                    tap(bc, Pf.LOCK_CONTINUE);
                    sleep_(3000);
                    return;
                }
            }
            int[] ex = findPopupX(wp);
            if (ex != null) {
                rotations++;
                if (rotations > 15) {
                    fatal = "能量弹窗循环超限";
                    log("能量弹窗循环超限, 停止");
                    return;
                }
                /* ⚠️⚠️ 关键一步: **把门槛抬上去再回去换人** (2026-10-16 实栽)。
                 * 原来只关弹窗 + continue, 门槛还是 4 ⇒ fixTeam 看 [7,4,9] 判"全部达标"
                 * ⇒ 又点 FIGHT ⇒ 又弹 ⇒ 关 ⇒ 又点…空转到 rotations>15 报错停机。
                 * 真正的原因就是门槛低了一档 (角色场每场要 5 钉, 配置是 4),
                 * 只要把 4 抬到 5, fixTeam 下一轮就会把槽 2 那颗 4 钉的角色换掉。 */
                team.bumpEnergyCost();
                log("FIGHT 后能量弹窗, 关闭并继续换人");
                tap(bp, ex);
                sleep_(1600);
                continue;                            // 回 fixTeam 换人
            }
            roundsDone++;                     // 打完才算一场? 不 —— 开场就算, 便于中途观察进度
            noteProgress();                   // 开打 = 最硬的进展
            log("▶ 第 " + roundsDone + " 场开始 (本次累计 " + roundsDone + " 场), 等待结束…");
            /* ⚠️⚠️ 2026-10-18: 这里原来先置位再调用 —— 一次性 latch。第一次若因为"HUD 还没
             * 渲染完"扑空, 整轮**再也不会试**, 用户看到的就是"每场都只有 *1"。
             * 现口径 (用户指定): **每一场开打都核验一次**, 不再做"只信第一次"的优化 ——
             * 游戏侧随时可能把速度打回 1x (换场次/重开/手动点一下), 而一次核验
             * = 一次截屏 + 三次小模板匹配, 相对一场战斗的开销可以忽略。 */
            battleAutoChecked = team.ensureBattleAuto();
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
        if (saveTeamGiveUp) return false;
        Vision.Frame w = Vision.toWork(bmp);
        /* 「Save changes to your Team?」: 正文问要不要保存, 两个按钮 **NO(紫蓝) / YES(绿)**,
         * 同一行 y=441 (PC 源码: NO x=527 / YES x=751)。手机端没 OCR ⇒ 用**按钮配色对**兜底:
         * 只有"NO 位是紫蓝 **且** YES 位是绿"才认 —— 单看一个绿会误报 (难度锁定弹窗/排行榜
         * 都踩过这个坑), 两个颜色同时对上就基本不会错。
         * ⚠️ 用户 2026-10-16 明确要求「第一次进来需要点取消」⇒ 这里点 **NO** 而不是 YES。 */
        boolean noPurple = purpleRatio(w, Pf.SAVE_TEAM_NO[0], Pf.SAVE_TEAM_NO[1], 30, 12) >= 0.45;
        boolean yesGreen = team.greenRatio(w, Pf.SAVE_TEAM_YES[0], Pf.SAVE_TEAM_YES[1], 30, 12) >= 0.45;
        if (!(noPurple && yesGreen)) return false;
        if (++saveTeamTries > 3) {
            saveTeamGiveUp = true;
            log("存档确认弹窗连点 3 次仍在 — 本次运行不再处理它");
            return false;
        }
        log("「Save changes to your Team?」弹窗, 点 NO/取消 (第 " + saveTeamTries + " 次)");
        tap(bmp, Pf.SAVE_TEAM_NO);
        sleep_(1800);
        return true;
    }

    /**
     * 某点周围窗口里"紫蓝"像素占比 (存档弹窗 NO 按钮的底色)。
     *
     * <p>返回**占比**而不是布尔 —— 与 {@link #greenRatio} 保持同一形状, 阈值留给调用处,
     * 这样日志里能直接打印分数 (排查时最有用的东西)。
     */
    private static double purpleRatio(Vision.Frame f, int cx, int cy, int hw, int hh) {
        int ok = 0, n = 0;
        float[] hsv = new float[3];
        for (int y = cy - hh; y <= cy + hh; y++) {
            if (y < 0 || y >= f.h) continue;
            for (int x = cx - hw; x <= cx + hw; x++) {
                if (x < 0 || x >= f.w) continue;
                PfTeam.hsv(f.px[y * f.w + x], hsv);
                n++;
                // 紫蓝: 色相 225~285 度 (HSV 里 0~360 映射到 0~360? 本实现用 0~360 尺度)
                if (hsv[0] >= 225 && hsv[0] <= 285 && hsv[1] >= 0.25f && hsv[2] >= 0.35f) ok++;
            }
        }
        return n == 0 ? 0 : ok / (double) n;
    }

    // ==================================================================
    //  匹配与点击工具
    // ==================================================================

    /** 诊断转储用的模板子集 (判不出界面时要看的那些)。 */
    private static final String[] DIAG_TPL = {
            /* ⚠️ 只留"能一眼定方位"的 6 个。2026-10-18: 17 个 x 全帧 step=1 在真机上要 ~3 分钟,
             * 把导航预算吃光; 图片本身才是主要证据, 分数只是辅助。 */
            Pf.T_HUB_PLAY, Pf.T_FIGHT, Pf.T_REFRESH, Pf.T_DRAGHINT, Pf.T_HALL_STORY_TEXT,
            Pf.T_SPD_3X,
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
                /* step=2: 诊断只要"大差不差", 不必逐像素; step=1 在真机上慢 4 倍 */
                double[] r = Vision.findScored(work, t, Pf.ROI_FULL,
                        Vision.SCAN_ALL, -1, 0, 2);
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
    /** 连续从 LEADERBOARDS 退出的次数 —— 前两次点左上箭头, 之后换系统返回键 (见 exitLeaderboard)。 */
    private int lbHits;
    /**
     * 连续「认不出 TEAM 菱形」的次数。
     *
     * <p>⚠️⚠️ 2026-10-19 实栽: 上一轮把盲点兜底删掉是对的 (盲点会点到别的页面的致命按钮),
     * 但**只删不给替代** ⇒ {@code fightFlow} 变成「判 TEAM_PREVIEW → 菱形 miss → return」
     * 的纯空转: 00:04:22~00:05:54 一共转了 **172 次**, 最后靠看门狗 91s 第 2 级"重拉游戏"
     * 才脱身 (代价是游戏重启、回到大厅、整轮白跑)。用户看到的"卡住"就是这个。
     * 现在的口径: 前两次仍只等 (预览页是淡入的, 补帧就能读到), 第 3 次起**按返回键脱离**,
     * 让主循环重新定位 —— 前提是 scene 已判为 TEAM_PREVIEW, 此时返回键不会退游戏。</p>
     */
    private int teamDiamondMiss;

    /** 上一次点 FIGHT 的时刻 —— 用来认出"紧随其后"的 ENERGY REFILL 弹窗 (见 step ⓪)。 */
    private long lastFightTapMs;

    /** 本段"连接中"从何时开始算 (0 = 没在连接中)。 */
    private long connectingSince;

    /** 连接中最多等多久就交回主循环 (断网久了确实要重拉游戏, 但不能一上来就按返回键)。 */
    private static final long CONNECTING_WAIT_MS = 120_000L;

    /**
     * 是不是"连接中/加载中"画面 —— 判据: **整屏近黑**。
     *
     * <p>实测 (2026-10-16, 11 帧对照): CONNECTING 帧近黑占比 **0.985**;
     * 十个真实界面 (编辑器 0.306 / 预览 0.289 / hub 0.610 / 选对手 0.213 /
     * 结算弹窗 0.774 / 详情页 0.235…) 全部 **≤0.774**。阈值 0.92 落在空档正中。
     *
     * <p>⚠️ 与 {@link #blankFrame} 的区别: 那个只认**纯色**(标准差极小), 而 CONNECTING
     * 中央有一块亮面板(骷髅徽章 + 文字), 标准差并不小 ⇒ blankFrame 抓不到它。
     * 两者是互补的: blankFrame = 冷启动闪屏, connectingFrame = 连服务器。
     */
    static boolean connectingFrame(Vision.Frame f) {
        if (f == null || f.px == null) return false;
        long dark = 0, n = 0;
        for (int y = 0; y < f.h; y += 4) {
            for (int x = 0; x < f.w; x += 4) {
                n++;
                int p = f.px[y * f.w + x];
                int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, bl = p & 0xFF;
                if ((r + g + bl) / 3 < 40) dark++;
            }
        }
        return n > 0 && dark / (double) n > 0.92;
    }

    /** 「刚点完 FIGHT」的剩余步数 (>0 才认难度锁定弹窗, 见 step() ⑨ 的注释)。 */
    private int justFoughtTicks;

    /**
     * 看门狗: 多久没有实质进展就认为卡住。
     *
     * <p>90s 的依据: 正常一轮循环 (战斗→结算→编辑器→再战) 实测约 15~40s, 最慢的合法
     * 环节是等能量恢复 (但那会打日志并走 setStepText, 同样算进展, 见 {@link #noteProgress})。
     * 取 90s 比最慢合法环节宽一倍, 又比"5 小时空转"快三个数量级。
     */
    private static final long STUCK_MS = 90_000L;

    /** 编队最多试几轮 (超过就回主循环, 见 fightFlow 里的注释)。 */
    private static final int FIX_TEAM_MAX_ROUNDS = 3;

    /** 上一次出现实质进展的时刻。 */
    private long lastProgressMs;

    /** 脱困升级链当前级别 (0=返回键 1=重拉游戏 2=重新导航)。 */
    private int stuckLevel;

    /** 上一帧识别出的界面 (变了就算有进展 —— 看门狗的判据之一)。 */
    private PfScene.S lastSceneSeen;

    /** 记一次"真的在往前走的进展" —— 界面变了 / 开打了 / 拖成了 / 结算过了 都算。 */
    private void noteProgress() { lastProgressMs = System.currentTimeMillis(); }

    /**
     * 卡住时的分级脱困。
     *
     * <p>顺序刻意从**最温和**到**最重**: 系统返回键 (可能只是有个弹窗没剥掉) →
     * 重拉 Skullgirls (进程卡死/断网重连) → 整套重新导航 (界面彻底跑丢)。
     * 每级用完都 {@link #noteProgress} 重新计时, 所以即使某一级无效也会在下一个
     * STUCK_MS 后试下一级, 不会像旧实现那样永远停在"点 TEAM 菱形"。
     */
    private void escalateStuck(AtomicBoolean run, JSONObject c) {
        if (!run.get()) return;
        String[] acts = {"系统返回键", "重拉 Skullgirls", "重新导航到 PF hub"};
        String act = acts[Math.min(stuckLevel, acts.length - 1)];
        SgmLog.i("pf", "⚠ 看门狗: " + (System.currentTimeMillis() - lastProgressMs) / 1000
                + "s 无进展 (界面=" + sceneText() + ", 场次=" + roundsDone
                + ") — 脱困第 " + (stuckLevel + 1) + " 级: " + act);
        unknownTries = 0;
        switch (Math.min(stuckLevel, 2)) {
            case 0:
                sh.exec("input keyevent 4");           // 系统返回键
                PfFlow.sleep_(2500);
                break;
            case 1:
                sh.exec("am force-stop com.autumn.skullgirls");
                PfFlow.sleep_(1500);
                sh.exec("monkey -p com.autumn.skullgirls -c android.intent.category.LAUNCHER 1");
                PfFlow.sleep_(6000);                   // 冷启动要时间, 别急着再判
                break;
            default:
                gotoHub(run, c);
                PfFlow.sleep_(2500);
                break;
        }
        stuckLevel = (stuckLevel + 1) % acts.length;   // 循环升级, 不永久停在一级
        noteProgress();
    }

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

    /**
     * 分段睡: 每 60ms 查一次停止闸门, 叫停就**立刻醒** (不等睡满)。
     * ⚠️ 只有 {@code gate} 为 false 时才提前返回; 正常睡眠时长不受影响
     * (下一轮循环会重新截图判断界面, 早醒 60ms 没有副作用)。 */
    static void sleep_(long ms) {
        if (ms <= 0) return;
        long end = System.currentTimeMillis() + ms;
        while (true) {
            java.util.concurrent.atomic.AtomicBoolean g = gate;
            if (g != null && !g.get()) return;             // 已被叫停 ⇒ 立刻醒
            long left = end - System.currentTimeMillis();
            if (left <= 0) return;
            try { Thread.sleep(Math.min(60, left)); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }
}
