package com.zhiyaunhe.sgmbot;

import android.graphics.Bitmap;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 结算循环 — 移植 phone/autojs/settle_bot.js 的 loop() (两阶段状态机, 同口径同文案)。
 *
 *   phase 0 (战斗): 只查 VICTORY!/DEFEAT! 大字 (便宜, scan.battle_ms 一轮)
 *     命中 → 记胜负 + 先手点右槽固定位置 (胜局该格=CONTINUE / 败局该格=REMATCH,
 *            两种情况都不用按钮匹配) → 进 phase 1; 败局先手即一局完成, 当场计场
 *   phase 1 (结算/奖励): 只在三槽查 REMATCH/CONTINUE (scan.result_ms 一轮, 快速跟点)
 *     REMATCH → 点 + 计场 + 回 phase 0; CONTINUE → 点, 保持 phase 1 (可能还有下一页)
 *     连续 result_fallback 帧无按钮 → 判定已入战斗, 回 phase 0
 *
 * 计场时机 (关键, 2026-10-05 教训): 大字**不**计场 —— 结算两页都有 VICTORY 横幅,
 * 过场会重置 seen 导致重复计数 (实测虚高 20 倍); 统一挪到「点 REMATCH / 败局先手」
 * 那一刻, 一局恰一次。
 *
 * 新增可配置 (2026-10-08):
 *   scan.battle_ms / result_ms  分阶段扫描间隔; scan.adaptive 命中后加快、久无命中放缓
 *   screen.roi_mode / profiles  ROI 按当前画面宽高比映射 (多分辨率适配, 见 Roi)
 *   run.max_minutes / pause_from~pause_to  单次最长时长 / 夜间暂停时段
 *   matcher.chan / step / refine            匹配口径
 *   graph.nodes / edges + Trace            判定路径图 (结构全在 config, 见 Graph)
 *
 * 快速连点模式 (config.fast, 2026-10-11 用户提出) — 见 {@link #runFast}:
 *   结算页第一个界面的 CONTINUE 与第二个界面的 REMATCH **落在同一个点** (都是按钮行里
 *   居中那颗; K70 真机帧实测中心 (640,512), 即 btn_rois 中槽中心, 见 tools/SlotProbe),
 *   所以"一直点它"就能做到 页A 前进 / 页B 重开 —— 不必逐帧做模板匹配。
 *   前提是「一直胜利 + 该页没有额外奖励按钮 (SELL ALL / HIDE STATS)」, 故默认关。
 *   弹窗检测保留, 但搬出热路径: 连点器只点不截屏, 截屏/弹窗/计场交给**独立的观察线程**
 *   (每 observe_ms 一帧, 默认 3s —— 见「每帧成本」: 周期 = max(每帧耗时, observe_ms))。
 *   剥弹窗后进 **FIGHT 追踪窗口** (fast.fight_wait_s, 默认 15s): 弹窗剥完常常正落在
 *   战斗准备界面 (右上角 FIGHT!), 连点器不知道此刻该按谁 ⇒ 窗口内持续找 vs_fight,
 *   命中即点并立刻恢复连点; 超时未命中也恢复 (下一站可能不是战斗页, 不干等)。
 *
 * 大保底 (config.map_check, 2026-10-11 用户提出): 每 every_s (默认 5min) 在左上角
 *   home_roi 找 home 图标 —— 挂机一旦被顶回大地图/大厅, 左上角必然出现那颗房子钮;
 *   连续两帧确认 (防单帧误判) → 停车。两个模式都挂: 常规模式的 stall 链对"干净的
 *   大地图"无计可施 (没弹窗可剥, selfHeal 只记日志), 只能在这里保底。
 *
 * 逃生链 (stall / 战斗超时) 全部走 config 的 escape 表 — 新弹窗 = 加一条配置。
 * ⚠️ 没模板的界面一律不盲点 (安全规则同 autojs 版): 自愈导航/PLAY 拉回待 M2 的
 *    NavChain 接入, 当前只记日志。
 */
public final class SettleLoop {

    /* ---- 状态 (一轮一帧, 与 autojs stat 对象一一对应) ---- */
    private int phase = 0;
    private boolean seen = false;
    private boolean lastVic = true;
    private int noHitRun = 0;
    private int rounds, wins, loses, rematches, continues;
    private int stallRuns, battleStalls;
    private long lastAct, battleT0, runStart;
    /** 上次弹窗守卫检查的时刻 (与 lastAct 分开: 守卫可以在"没动作"期间周期性跑) */
    private long lastPopupChk;
    /** 上次结算页判定结果 + 时刻 — 结果变化(或满 3s)时才记一条含三槽分数的日志 */
    private String lastPick = "";
    private long lastJudgeMs;
    private Store daily;

    private final ShizukuCtl sh;
    private final TplStore tpls;
    private final OverlayBar bar;
    private volatile String phaseText = "战斗监测";

    /* ---- ROI 缓存: 只在 work 尺寸变化时重算 (Roi.resolve 便宜但没必要每帧) ---- */
    private int roiW = -1, roiH = -1;
    private int[] titleR = null;
    private List<int[]> btnR = null;
    /** 快速连点落点要绕开的 HUD 挡块 (已按当前分辨率解析); 见 WorkPoint */
    private List<int[]> avoidR = null;
    private int avoidGap = WorkPoint.DEFAULT_GAP;
    private JSONObject cfgRef;
    /** 模板缩放倍数 (非 abs/center 分辨率模式下 ≠1) — 变了就重新取缩放版模板 */
    private double tplSX = 1.0, tplSY = 1.0;
    private Vision.Frame tVic, tDef, tRm, tCt;

    /* ---- 快速连点模式 (fast) 的跨线程状态 ---- */
    /** 连点器与观察线程共享的落点 (work 坐标); 观察线程解析出来后连点器才开点 */
    private volatile float fxX = 640, fxY = 512;
    private volatile boolean fxReady = false;
    /** 落点是怎么算出来的 (WorkPoint 状态码) — 日志用, 见 {@link #fxPointText()} */
    private volatile int fxState = 0;
    /** 连点闸门时刻: 剥完弹窗后短暂停手, 让画面稳下来再点 (避免一掌拍在弹窗按钮上) */
    private volatile long fxGateUntil = 0;
    /** 连点次数 (观察线程日志里报) */
    private volatile int fxTaps = 0;
    /** 落点体检是否**拦车** (config.fast.tap_check, 默认 false = 只提示); 见 ③b */
    private volatile boolean fxTapCheck = false;
    /** 本线程跑的是不是 fast 分支 — 状态快照里区分 mode */
    private volatile boolean fastOn = false;

    /* ---- FIGHT 追踪窗口 (fast 剥弹窗后) + 大保底 (map_check) ---- */
    /** >0: 剥弹窗后的 fight 追踪截止时刻 (work 时间轴); 命中/超时/缺模板都清零 */
    private volatile long fxFightUntil = 0;
    /** fight 窗口内点 FIGHT 的次数 (状态快照) */
    private volatile int fightTaps = 0;
    /** fast.fight 判据: 模板名/th/roi(原始) — th/roi 缺省继承 escape.items 同名项 */
    private String fightTpl = "vs_fight";
    private double fightTh = 0.75;
    private int[] fightRoiRaw;
    private long fightWaitMs, fightScanMs = 500;
    /** 大保底 (map_check): 两模式共用 */
    private boolean mapOn = true;
    private long mapEveryMs = 300_000;
    private String mapTpl = "home";
    private double mapTh = 0.85;
    private int[] mapRoiRaw;
    private long lastMapChk;
    private int mapChecks;
    /** 懒解析缓存: 模板按屏幕缩放 + ROI 按当前 work 尺寸; 分辨率变了才重算 */
    private Vision.Frame tFight, tHome;
    private int[] fightR, homeR;
    private int chkW = -1, chkH = -1;

    public SettleLoop(ShizukuCtl sh, TplStore tpls, OverlayBar bar) {
        this.sh = sh;
        this.tpls = tpls;
        this.bar = bar;
    }

    /** 小状态文案 (悬浮条/状态快照用) */
    public String phaseText() { return phaseText; }

    /**
     * 结算循环主入口 (在 BotService 工作线程里跑)。
     * @param run 停止标志 — 达到每日上限/最长时长时本方法内部也会置 false
     */
    public void run(AtomicBoolean run, Config cfg) {
        JSONObject c = cfg.read();
        cfgRef = c;
        Vision.configure(c);                                  // 匹配口径可配
        Graph.load(c);                                        // 判定图结构可配
        Trace.configure(sub(c, "log").optInt("trace_size", 200));

        final int cap = c.optInt("daily_cap", 0);
        final double minSim = th(c, "min_sim", 0.72);
        final int battleMs = scanMs(c, "battle_ms", tg(c, "battle_ms", 800));
        final int resultMs = scanMs(c, "result_ms", tg(c, "result_ms", 350));
        final int tapDelay = tg(c, "tap_delay_ms", 1200);
        final int stallSec = tg(c, "stall_sec", 30);
        final int battleStallSec = tg(c, "battle_stall_sec", 180);
        final int resultFallback = c.optInt("result_fallback", 3);
        final int maxMinutes = sub(c, "run").optInt("max_minutes", 0);
        final int[] titleRoi = arr(c, "title_roi", new int[]{400, 20, 480, 100});
        final List<int[]> btnRois = rois(c, "btn_rois");

        JSONObject ad = sub(c, "scan").optJSONObject("adaptive");
        final boolean adaptive = ad != null && ad.optBoolean("on", true);
        final int adMin = ad == null ? 250 : ad.optInt("min_ms", 250);
        final int adMax = ad == null ? 2000 : ad.optInt("max_ms", 2000);
        final int adHit = ad == null ? 350 : ad.optInt("hit_ms", 350);
        final int adMiss = ad == null ? 900 : ad.optInt("miss_ms", 900);

        /* 弹窗守卫: 「无动作超过 after_sec」就开始周期性剥弹窗 (间隔 every_sec)。
         * 判据用"无动作"而不是"卡在某个界面" —— 弹窗出现必然挡住按钮 ⇒ 必然无动作。 */
        JSONObject pu = sub(c, "popup");
        final boolean popupGuard = pu == null || pu.optBoolean("guard", true);
        final long popupAfterMs = Math.max(1, pu == null ? 5 : pu.optInt("after_sec", 5)) * 1000L;
        final long popupEveryMs = Math.max(1, pu == null ? 5 : pu.optInt("every_sec", 5)) * 1000L;

        if (!loadTpls(1.0, 1.0)) {
            SgmLog.i("bot", "缺模板 victory/defeat — 不开跑 (assets/templates 或 "
                    + Paths.tplDir() + " 下补齐)");
            return;
        }

        daily = Store.load();
        if (daily.capReached(cap)) {
            SgmLog.i("计", daily.text(cap) + " 已达每日上限 — 不开跑 (改 config daily_cap)");
            return;
        }

        runStart = System.currentTimeMillis();
        lastAct = runStart;
        battleT0 = runStart;
        parseMapCfg(c);                       // 大保底判据 (两模式共用)

        /* 快速连点模式: 换一条完全不同的循环 (盲点 + 独立观察线程), 见 runFast 注释。
         * 放在模板/上限/路径图都就绪**之后** —— 两条路共用同一套前置检查。 */
        if (sub(c, "fast").optBoolean("enable", false)) {
            fastOn = true;
            runFast(run, c, cap);
            summary();
            return;
        }

        long lastUi = 0;
        SgmLog.i("bot", "结算循环开始 — " + daily.text(cap) + " 上限=" + (cap > 0 ? cap : "不限")
                + " | 匹配=" + Vision.modeText() + " | 扫描=" + battleMs + "/" + resultMs
                + (adaptive ? "(自适应)" : ""));

        while (run.get()) {
            /* 夜间暂停时段: 不截屏不点击, 醒着等 (挂机过夜用) */
            if (inPause(c)) {
                if (System.currentTimeMillis() - lastUi > 60000) {
                    lastUi = System.currentTimeMillis();
                    SgmLog.i("休", "暂停时段内 — 待机 (run.pause_from~pause_to)");
                }
                lastAct = System.currentTimeMillis();      // 别让 stall 误判
                sleep_(20000);
                continue;
            }
            if (maxMinutes > 0
                    && System.currentTimeMillis() - runStart > maxMinutes * 60000L) {
                SgmLog.i("停", "已达单次最长 " + maxMinutes + " 分钟 — 停止 (run.max_minutes)");
                run.set(false);
                break;
            }

            Bitmap bmp = sh.capture();
            if (bmp == null) {
                SgmLog.i("bot", "截屏失败 — 下一轮重试");
                sleep_(battleMs);
                continue;
            }
            Vision.Frame work = Vision.toWork(bmp);
            long now = System.currentTimeMillis();
            syncRoi(work, titleRoi, btnRois);
            boolean hitThisRound = false;

            /* —— 弹窗守卫 (任何相位都跑) ——
             * JS 的弹窗处理分散在两处: pf_nav.waitHall **每帧**剥 (启动/导航阶段),
             * settle_bot 只在 stall 30s 后剥。Java 版没有导航链, 所以把"每帧剥"
             * 这条语义挂到结算循环上 —— 否则启动时正好停在网络错误弹窗上, 要等满
             * 30s 才试第一次, 且剥完之后仍然不知道该去哪 (导航仍是 M2 待办)。
             * 触发判据 = 距上次有效动作超过 after_sec: 弹窗必然挡住按钮 ⇒ 必然无动作。 */
            if (popupGuard && now - lastPopupChk >= popupEveryMs
                    && now - lastAct >= popupAfterMs) {
                lastPopupChk = now;
                Graph.cursor("popup");
                if (escape(work, bmp, c)) {
                    hitThisRound = true;
                    lastAct = now;
                    noHitRun = 0;
                    SgmLog.i("弹", "守卫剥弹窗 → 回到原相位 (phase=" + phase + ")");
                }
            }

            /* —— 大保底 (map_check): 每 every_s 看一眼左上角 ——
             * 被顶回大地图/大厅 = 脱离战斗流程, 后面所有判定都不会再命中; stall 链
             * 对"干净的大地图"无计可施 (没弹窗可剥, selfHeal 只记日志) ⇒ 只能在这里
             * 保底。判据与停车细节见 mapGuard。 */
            if (mapOn && now - lastMapChk >= mapEveryMs && mapGuard(work, run)) break;

            if (phase == 0) {
                /* —— 战斗阶段: 只查两个大字 —— */
                Graph.cursor("battle");
                double[] v = findExact(work, tVic, titleR, minSim);
                boolean vic = v != null && v[2] >= minSim;
                double[] d = vic ? null : findExact(work, tDef, titleR, minSim);
                boolean def = d != null && d[2] >= minSim;
                Trace.put("battle", "match", "victory", titleR, v == null ? -2 : v[2], vic,
                        vic ? "→大字判定" : "");
                if (!vic) Trace.put("battle", "match", "defeat", titleR, d == null ? -2 : d[2],
                        def, def ? "→大字判定" : "");

                if (vic || def) {
                    if (!seen) {
                        seen = true;
                        lastVic = vic;
                        Graph.cursor("bigword");
                        SgmLog.i("场", lastVic ? "VICTORY" : "DEFEAT");
                    }
                    /* 大字只负责"进入结算阶段" —— 点哪个按钮一律交给 phase 1 的匹配决定。
                     * ⚠️ 「先手盲点右槽」已退役 (2026-10-11, 用户报障
                     *    「有 rematch 的时候点到了 continue, 那就回大地图了」+「莫名其妙连点两次」):
                     *
                     *    老实现 = 见到大字就 tap(first_tap=903,512), 省掉一次按钮匹配。
                     *    但右槽在**有 REMATCH 的结算页**上正好是 CONTINUE —— 离线实测
                     *    (tools/SlotProbe, VICTORY 页) btn_continue 全帧最佳 0.951 @(810,494),
                     *    模板 188x36 ⇒ 按钮中心 = (904,512), 与 first_tap(903,512) 严丝合缝。
                     *    ⇒ 盲点右槽 = 盲点 CONTINUE = 回大地图。
                     *
                     *    为什么会连点两次: `seen` 在"无大字"的过场帧被复位 (上面的 else),
                     *    而结算流程有**多页** (页A 只有 CONTINUE / 页B 是 FAVORITE|REMATCH|CONTINUE,
                     *    两页都有 VICTORY 大字) ⇒ 页A 先手一下 → 换页时大字闪断 → 页B 又先手一下,
                     *    第二下就落在页B 的 CONTINUE 上。用户观察到的"点得慢反而对"也吻合:
                     *    画面稳定不闪断时 seen 不复位, 就不会重复先手。
                     *
                     *    改法: 大字无条件进 phase 1 (不再要求 !seen), 点击交给 findSlot ——
                     *    页A 匹配不到 REMATCH ⇒ 点 CONTINUE (前进, 与原来意图一致);
                     *    页B 匹配到中槽 REMATCH ⇒ 点 REMATCH (正确, 不再回大地图);
                     *    败局页匹配到右槽 REMATCH ⇒ 点 REMATCH, 计场在 phase 1 那一步 (countRound),
                     *    所以原来"败局先手即计场"的那句一并去掉, 免得重复计场。 */
                    phase = 1;
                    noHitRun = 0;
                    battleStalls = 0;                   // 见到大字 = 游戏活着
                    hitThisRound = true;
                } else {
                    seen = false;                       // 无大字 = 战斗/过场
                }
            } else {
                /* —— 结算/奖励阶段: 只查 REMATCH / CONTINUE (各三槽) —— */
                Graph.cursor("result");
                double[] rm = findSlot(work, tRm, btnR, minSim);
                boolean rmOk = rm != null && rm[2] >= minSim;
                double[] ct = rmOk ? null : findSlot(work, tCt, btnR, minSim);
                boolean ctOk = ct != null && ct[2] >= minSim;

                /* ⚠️ 判定日志 (2026-10-11 用户报障「有 REMATCH 时点到了 CONTINUE, 就回大地图了」):
                 *    光记一句"点了 CONTINUE"没用 —— 事后必须能分清是
                 *      ① btn_rematch 在 REMATCH 真身上就没到阈值 (模板过期 / 分辨率掉分 / 通道口径),
                 *      ② 还是它根本不在这一页 (那点 CONTINUE 就是对的)。
                 *    所以日志带**两个模板 × 每个槽的各自峰值**。低频: 结果变化或满 3s 才记。 */
                String pick = rmOk ? "REMATCH" : ctOk ? "CONTINUE" : "none";
                long tnow = System.currentTimeMillis();
                if (!pick.equals(lastPick) || tnow - lastJudgeMs > 3000) {
                    lastPick = pick;
                    lastJudgeMs = tnow;
                    SgmLog.i("判", "结算页 → 点 " + pick
                            + " | rematch" + peaks(work, tRm, btnR)
                            + " continue" + peaks(work, tCt, btnR)
                            + " th=" + minSim + " (" + btnR.size() + " 槽)");
                }

                if (rmOk) {
                    hitThisRound = true;
                    Trace.put("result", "match", "btn_rematch", null, rm[2], true, "→REMATCH");
                    tap(bmp, (float) rm[0], (float) rm[1]);
                    rematches++;
                    lastAct = System.currentTimeMillis();
                    noHitRun = 0;
                    Graph.cursor("rematch");
                    SgmLog.i("动", "REMATCH (第 " + rematches + " 场) → " + xy(bmp, (float) rm[0], (float) rm[1]));
                    countRound(lastVic, run, cap);               // 一局完成, 每局恰一次
                    phase = 0;
                    battleT0 = System.currentTimeMillis();
                    sleep_(tapDelay);
                } else if (ctOk) {
                    hitThisRound = true;
                    Trace.put("result", "match", "btn_continue", null, ct[2], true, "→CONTINUE");
                    tap(bmp, (float) ct[0], (float) ct[1]);
                    continues++;
                    lastAct = System.currentTimeMillis();
                    noHitRun = 0;                    // 保持结算阶段 (可能还有下一页)
                    Graph.cursor("continue");
                    SgmLog.i("动", "CONTINUE 略过 → " + xy(bmp, (float) ct[0], (float) ct[1]));
                    sleep_(tapDelay);
                } else {
                    if (rm != null) Trace.put("result", "match", "btn_rematch", null, rm[2],
                            false, "峰值未过阈");
                    if (ct != null) Trace.put("result", "match", "btn_continue", null, ct[2],
                            false, "峰值未过阈");
                    noHitRun++;
                    if (noHitRun >= resultFallback) {
                        phase = 0;
                        battleT0 = System.currentTimeMillis();
                        SgmLog.i("转", "进入战斗监测");
                    }
                }
            }

            /* —— stall / 战斗超时 —— */
            if (System.currentTimeMillis() - lastAct > stallSec * 1000L) {
                if (!escape(work, bmp, c)) {
                    if (phase == 0) {
                        /* 战斗阶段无大字 ≠ 卡死; 从入战斗起超时才逐跳拉回 */
                        long over = System.currentTimeMillis() - battleT0;
                        if (over >= battleStallSec * 1000L) {
                            battleStalls++;
                            Graph.cursor("stall");
                            SgmLog.i("!!", "战斗 " + (over / 60000) + "min 无结果 (超时第 "
                                    + battleStalls + " 跳)");
                            if (battleStalls >= 3) {
                                selfHeal();
                                battleStalls = 0;
                                battleT0 = System.currentTimeMillis();
                            } else {
                                /* PLAY 拉回需要 NavChain.findPlay (M2) — 缺它不盲点 */
                                SgmLog.i("救", "PLAY 拉回待 NavChain (M2) — 不盲点");
                            }
                        }
                    } else {
                        stallRuns++;
                        SgmLog.i("!!", stallSec + "s 无识别 (连续 " + stallRuns + " 轮) — 断线/非常规弹窗?");
                        if (stallRuns >= 3) { selfHeal(); stallRuns = 0; }
                    }
                } else {
                    stallRuns = 0;
                }
                lastAct = System.currentTimeMillis();
            }

            phaseText = phase == 0 ? "战斗监测" : "结算页";
            if (now - lastUi > 2000) {          // 悬浮条节流 (循环 350-800ms 一轮)
                lastUi = now;
                pushUi(cap);
            }
            snapshot();
            bmp.recycle();
            sleep_(adaptive ? clamp(hitThisRound ? adHit : adMiss, adMin, adMax)
                    : (phase == 0 ? battleMs : resultMs));
        }
        summary();
    }

    private void summary() {
        SgmLog.i("bot", "结算循环结束 — 本次 " + rounds + " 场 " + wins + "胜" + loses + "负");
    }

    /* ==================== 快速连点模式 (config.fast) ==================== */

    /**
     * 快速连点 — 用户 2026-10-11 提的"状态切换"：结算页第一个界面的 CONTINUE 与第二个
     * 界面的 REMATCH 落在**同一个点**（按钮行居中那颗），所以一直点它就能
     * 页A 前进 → 页B 重开，整条结算-再战链不需要任何模板匹配。
     *
     * <p>分工（这是它快的全部原因）：
     * <ul>
     *   <li><b>主线程 = 连点器</b>：只 {@code input tap} 固定点，**不截屏不匹配**。
     *       截屏（`screencap` 一趟几百毫秒）是整条链最贵的一步，省掉它 + tap_delay
     *       从 1200ms 降到 tap_ms（默认 300）⇒ 一个结算周期从 ~3s 掉到 ~0.6s。</li>
     *   <li><b>观察线程 = 安全网</b>（独立线程, 每 observe_ms 截一帧, 默认 3s）：
     *       剥弹窗（复用同一条 escape 链）/ 大字计场 / 每日上限 / 无进展停车 / 状态快照。
     *       只让**一个**线程截屏，避免两路 screencap 互相拖慢。</li>
     * </ul>
     *
     * <p>⚠️ 前提是用户说的两条：**一直胜利** + **该页没有额外奖励按钮**
     * （有 SELL ALL / HIDE STATS 的那版奖励页，中间那颗是 SELL ALL，盲点会卖道具）。
     * 所以 {@code fast.enable} 默认 false，必须显式开。
     */
    private void runFast(AtomicBoolean run, JSONObject c, int cap) {
        JSONObject fx = sub(c, "fast");
        final int tapMs = Math.max(60, fx.optInt("tap_ms", 300));
        final int obsMs = Math.max(200, fx.optInt("observe_ms", 3000));
        final int gapS = Math.max(1, fx.optInt("round_gap_s", 5));
        final int idleS = Math.max(0, fx.optInt("idle_stop_s", 300));
        final int pauseMs = Math.max(0, fx.optInt("popup_pause_ms", 2000));
        final int maxMinutes = sub(c, "run").optInt("max_minutes", 0);
        final JSONArray fxy = fx.optJSONArray("xy");
        parseFightCfg(c);                     // FIGHT 追踪判据 (观察线程用)
        /* 落点体检开关 — **默认关** (2026-10-14): fast 的契约是盲点, 用户要的只有
         * "弹窗 + 大保底"。开着时"落点那格没匹配到结算按钮"会立刻停车; K70 实测它误杀
         * (带 FAVORITE 的三按钮结算页, 落点那格其实是 REMATCH 只是模板对不上)。 */
        fxTapCheck = fx.optBoolean("tap_check", false);

        SgmLog.i("快", "快速连点模式 — 连点 " + tapMs + "ms / 观察 " + obsMs + "ms / 落点 "
                + (fxy != null && fxy.length() >= 2
                    ? "fast.xy(" + (int) fxy.optDouble(0) + "," + (int) fxy.optDouble(1) + ")"
                    : "缺省 = btn_rois 中槽中心, 再绕开 avoid 挡块")
                + " / 一局去抖 " + gapS + "s / 无进展停车 "
                + (idleS > 0 ? idleS + "s" : "关")
                + " / 剥弹窗后找 FIGHT "
                + (fightWaitMs > 0 ? (fightWaitMs / 1000) + "s (tpl=" + fightTpl + " th=" + fightTh + ")"
                                   : "关")
                + " / 落点体检 " + (fxTapCheck ? "开 (不匹配即停车)" : "关 (只提示)")
                + " — 关掉改 config.fast.enable=false");

        Thread obs = new Thread(
                () -> observeFast(run, c, cap, obsMs, gapS, idleS, pauseMs, maxMinutes),
                "fast-observer");
        obs.setDaemon(true);
        obs.start();

        long lastUi = 0;
        while (run.get()) {
            if (inPause(c)) { sleep_(1000); continue; }        // 夜间暂停: 不点, 挂着等
            long now = System.currentTimeMillis();
            /* 等观察线程给出第一帧的落点 (它要同步 ROI / 分辨率档) —— 顶多等 1 个 observe_ms */
            if (fxReady && now >= fxGateUntil) {
                sh.tapWork(fxX, fxY);
                fxTaps++;
            }
            if (now - lastUi > 2000) {
                lastUi = now;
                phaseText = "快速连点";
                pushUi(cap);
            }
            sleep_(tapMs);
        }
        /* 连点器先退, 观察线程最多再跑一帧就自己看到 run=false 退出 */
        try { obs.join(obsMs + 2000L); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 观察线程 —— 快速连点模式里唯一截屏、唯一做判定的地方。三件事，全部低频：
     *   ① 弹窗: 距上次有效动作超过 popup.after_sec 就走 escape 链; 剥成功则闸住连点一小会儿;
     *   ② 计场: 大字 + latch + 时间下限。latch 保证"同一屏的大字只算一次"(结算页会持续
     *      显示 VICTORY! 好几秒); 时间下限 round_gap_s 再兜一层, 防换页闪断被采样到;
     *   ③ 停车: 每日上限 (countRound 内)、单次最长、以及**无进展** (idle_stop_s 内一场没计到,
     *      说明连点没把流程推下去 —— 多半已经脱离战斗界面)。
     *
     * ⚠️ 用户口径 (2026-10-14): **fast 只负责"剥弹窗 + 大保底"** —— 连点本身是盲点,
     * 不该因为"落点那格没匹配到结算按钮"就停车 (K70 上它误杀了带 FAVORITE 的三按钮
     * 结算页)。所以落点体检默认只记日志, 要它拦车得显式开 fast.tap_check。
     */
    private void observeFast(AtomicBoolean run, JSONObject c, int cap, int obsMs, int gapS,
                             int idleS, int pauseMs, int maxMinutes) {
        final double minSim = th(c, "min_sim", 0.72);
        final int[] titleRoi = arr(c, "title_roi", new int[]{400, 20, 480, 100});
        final List<int[]> btnRois = rois(c, "btn_rois");
        final JSONArray fxy = sub(c, "fast").optJSONArray("xy");
        JSONObject pu = sub(c, "popup");
        final boolean guard = pu == null || pu.optBoolean("guard", true);
        final long afterMs = Math.max(1, pu == null ? 5 : pu.optInt("after_sec", 5)) * 1000L;
        /* ⚠️ 剥弹窗重试间隔 (2026-10-14 瘦身): 原来 guard 一过期就**每帧**跑整条 escape 链
         * (6 条模板, 其中 srv_ok / srv_retry 是 roi=null 的全帧搜 ⇒ 一次失败尝试 ≈ 2 次
         * 全帧金字塔搜 + 4 次 ROI 搜 + 6 次槽搜), 而**失败不更新 lastAct** ⇒ 稳态下每帧
         * 白跑一遍。触发条件本身就是 after_sec=5 这个时间尺度, 比它更密地重试没有意义。
         * 默认取 popup.every_sec (5s)。 */
        final long escMs = Math.max(500, (pu == null ? 5 : pu.optInt("every_sec", 5)) * 1000L);
        /* ⚠️ 三槽峰值 (peakArr, th=2.0 ⇒ 不可能"过阈值提前退出", 必然穷举整个 ROI) 是
         * **纯诊断**: 只喂 [观] 日志与默认关闭的 tap_check。按 diag_ms 节流 (默认 2s);
         * 一旦 tap_check=true 必须每帧算 —— 它是停车判据, 不能吃陈旧值。 */
        final int diagMs = Math.max(0, sub(c, "fast").optInt("diag_ms", 2000));
        long lastEscTry = 0;
        long lastDiag = 0;
        double[] lastRp = new double[0], lastCp = new double[0];

        long lastBig = System.currentTimeMillis();     // 上次计场时刻 (无进展停车看它)
        boolean bigLatch = false;                      // 大字 latch: 中间见过"无大字"才允许再计
        long lastLog = 0;
        long lastWarn = 0;                             // 落点体检"只提示"模式的节流 (免得每帧刷屏)
        String lastSeen = "";
        int frames = 0, pops = 0;
        int homeSlot = -1;                             // 落点落在第几格 (体检用); -1 = 不在任何槽里

        while (run.get()) {
            long t0 = System.currentTimeMillis();
            if (inPause(c)) { sleep_(obsMs); continue; }
            if (maxMinutes > 0 && t0 - runStart > maxMinutes * 60000L) {
                SgmLog.i("停", "已达单次最长 " + maxMinutes + " 分钟 — 停止 (run.max_minutes)");
                run.set(false);
                break;
            }
            Bitmap bmp = sh.capture();
            if (bmp == null) { SgmLog.i("快", "观察线程截屏失败 — 下帧重试"); sleep_(obsMs); continue; }
            Vision.Frame work = Vision.toWork(bmp);
            syncRoi(work, titleRoi, btnRois);
            if (!fxReady) {
                resolveFastPoint(fxy);
                fxReady = true;
                lastAct = t0;
                homeSlot = slotOfPoint();
                SgmLog.i("快", "落点就绪 work(" + work.w + "x" + work.h + ") → 点 " + fxPointText()
                        + (homeSlot >= 0 ? " = 第 " + (homeSlot + 1) + " 格" : " (不在任何按钮槽里)")
                        + " | 挡块 " + avoidText()
                        + " | 截屏 " + ShizukuCtl.modeText()
                        + " / 观察 " + obsMs + "ms / 诊断 " + (diagMs > 0 ? diagMs + "ms" : "每帧")
                        + " / 剥弹窗重试 " + (escMs / 1000) + "s");
                if (fxState == 3)
                    SgmLog.i("!!", "落点仍落在挡块 " + avoidText() + " 里且槽内无处可挪 — "
                            + "会点到 HUD (战斗界面就是 1x/3x 速度泡)。请手改 fast.xy 挑一个干净坐标。");
            }
            frames++;

            /* ① 弹窗 (任何时刻: 判据是"距上次有效动作太久" ⇒ 一定有个东西挡着)
             * 重试间隔 escMs = popup.every_sec —— 见 observeFast 声明处的注释 */
            if (guard && t0 - lastAct >= afterMs && t0 - lastEscTry >= escMs) {
                lastEscTry = t0;
                Graph.cursor("popup");
                if (escape(work, bmp, c)) {
                    pops++;
                    lastAct = t0;
                    fxGateUntil = t0 + pauseMs;
                    /* 剥完弹窗常常正落在战斗准备界面 (右上角 FIGHT!) — 连点器不知道
                     * 此刻该按谁, 盲点可能点在 FIGHT 页的空白处。开一个追踪窗口:
                     * fight_wait_s 内持续找 vs_fight, 命中即点; 缺模板/关开关就不开,
                     * 保持原来的死等 pauseMs 行为。 */
                    boolean fightOk = fightWaitMs > 0 && resolveFight(work);
                    fxFightUntil = fightOk ? t0 + fightWaitMs : 0;
                    SgmLog.i("弹", "快速模式剥弹窗 → 连点暂停 " + pauseMs + "ms (第 " + pops + " 次)"
                            + (fightOk ? " → 接下来 " + (fightWaitMs / 1000)
                                       + "s 内持续找 FIGHT! (tpl=" + fightTpl + ")" : ""));
                }
            }

            /* ⓪ FIGHT 追踪窗口: 窗口内每帧找 vs_fight, 命中即点并**立刻**恢复连点;
             * 超时未命中也恢复 (只是恢复, 绝不因为"没等到 FIGHT"停车)。 */
            if (t0 < fxFightUntil) {
                if (resolveFight(work)) {
                    Graph.cursor("fxfight");
                    double[] f = findExact(work, tFight, fightR, fightTh);
                    boolean hitF = f != null && f[2] >= fightTh;
                    Trace.put("fxfight", "match", fightTpl, fightR,
                            f == null ? -2 : f[2], hitF, hitF ? "点 FIGHT → 恢复连点" : "");
                    if (hitF) {
                        tap(bmp, (float) (f[0] + tFight.w / 2f), (float) (f[1] + tFight.h / 2f));
                        fightTaps++;
                        fxFightUntil = 0;
                        fxGateUntil = 0;              // 立刻恢复连点 (不等 pauseMs 走完)
                        lastAct = t0;
                        SgmLog.i("快", "见到 FIGHT! → 点击进战斗 (第 " + fightTaps
                                + " 次, 峰值 " + fmt1(f[2]) + ") → 立刻恢复连点");
                    }
                } else {
                    fxFightUntil = 0;                 // 缺模板: 窗口无意义, 直接恢复
                    SgmLog.i("快", "缺 " + fightTpl + " 模板 — FIGHT 追踪不可用, 直接恢复连点");
                }
            } else if (fxFightUntil > 0) {            // 窗口刚自然过期 (进来就清, 只记一次)
                fxFightUntil = 0;
                SgmLog.i("快", "fight 窗口内没见到 FIGHT! — 恢复连点 (弹窗后的下一站可能不是战斗页)");
            }

            /* ⓪b 大保底: 已退到大地图就停车 (内部自带 every_s 闸, 不命中绝不停车) */
            if (mapGuard(work, run)) break;

            /* ② 大字 → 计场 */
            double[] v = findExact(work, tVic, titleR, minSim);
            boolean vic = v != null && v[2] >= minSim;
            double[] d = vic ? null : findExact(work, tDef, titleR, minSim);
            boolean def = !vic && d != null && d[2] >= minSim;
            if (vic || def) {
                if (!bigLatch && t0 - lastBig >= gapS * 1000L) {
                    lastBig = t0;
                    lastVic = vic;                      // 记下胜负 (与常规模式同一条口径)
                    Graph.cursor("bigword");
                    countRound(vic, run, cap);          // 前提"一直胜利"不成立时这里也会如实记负
                }
                bigLatch = true;
            } else {
                bigLatch = false;
            }

            /* ③ 顺手判"这是哪个页"—— 只喂日志/状态, 不参与点击 (点击是连点器的事)。
             * ⚠️ 按 diagMs 节流 (2026-10-14): 这 6 次匹配是 th=2.0 的**穷举** (不提前退出),
             * 占了每帧 NCC 的大头, 而 tap_check 关闭时它只值一条 10s 一次的日志。 */
            boolean diagDue = diagMs <= 0 || fxTapCheck || t0 - lastDiag >= diagMs;
            if (diagDue) {
                lastRp = peakArr(work, tRm, btnR);
                lastCp = peakArr(work, tCt, btnR);
                lastDiag = t0;
            }
            double[] rp = lastRp, cp = lastCp;
            String seen = seenOf(rp, cp, minSim, vic || def);
            /* 低频日志: **状态变化**或满 10s 才记一条 (连点 300ms/轮, 每帧刷屏没意义)。
             * 带两个模板 × 三个槽的峰值 —— 现场只用这一条就能回答
             * 「落点 (640,512) 这格到底是什么按钮」这个唯一悬而未决的问题。 */
            if (!seen.equals(lastSeen) || t0 - lastLog > 10000) {
                lastSeen = seen;
                lastLog = t0;
                SgmLog.i("观", "第 " + frames + " 帧 → " + seen
                        + " | rematch" + fmt(rp) + " continue" + fmt(cp)
                        + " | 落点(" + (int) fxX + "," + (int) fxY + ")"
                        + " 已连点 " + fxTaps + " 次 / 剥弹窗 " + pops + " 次 / 本轮 " + rounds + " 场");
            }

            /* ③b 落点体检 —— **默认只提示, 不拦车** (config.fast.tap_check, 默认 false)。
             *
             * 快速连点唯一的假设是「落点那格正是 CONTINUE 或 REMATCH」。一旦观察到
             * **结算按钮过阈了、但不在落点那一格**, 那颗可能是别的按钮
             * (有 SELL ALL / HIDE STATS 的那版奖励页中间就是 SELL ALL —— 盲点它 = 卖道具)。
             *
             * ⚠️ 2026-10-14 K70 实测: 这条**误杀**。结算页有两版布局 —— 带 FAVORITE 的那版
             *   是三按钮 (FAVORITE/REMATCH/CONTINUE), CONTINUE 被挤到第 3 格 (峰值 0.95),
             *   而落点那格其实是 REMATCH、只因按钮变窄模板对不上 (0.39 < 0.72) ⇒ 被判
             *   "落点不是结算按钮" ⇒ 半分钟内连停 5 次。可**盲点 REMATCH 就是再打一场**,
             *   是完全正常的推进 —— 停车才是错的那个。
             *
             * 所以默认改成只记日志: 落点那格没匹配到, 不等于点在有害的东西上。
             * 真想让它在"落点那格不是结算按钮"时立刻停车 ⇒ 开 fast.tap_check=true。 */
            if (homeSlot >= 0 && homeSlot < rp.length && homeSlot < cp.length) {
                boolean here = rp[homeSlot] >= minSim || cp[homeSlot] >= minSim;
                int other = -1;
                for (int i = 0; i < rp.length; i++)
                    if (i != homeSlot && (rp[i] >= minSim || cp[i] >= minSim)) { other = i; break; }
                if (!here && other >= 0) {
                    if (fxTapCheck) {
                        Graph.cursor("fxchk");
                        SgmLog.i("!!", "落点体检不通过 → 停车: 落点(第 " + (homeSlot + 1) + " 格)不是结算按钮, "
                                + "而第 " + (other + 1) + " 格有。盲点它会点到别的按钮 (SELL ALL 之类)。"
                                + " | rematch" + fmt(rp) + " continue" + fmt(cp));
                        run.set(false);
                        break;
                    }
                    if (t0 - lastWarn > 60000) {
                        lastWarn = t0;
                        SgmLog.i("观", "落点(第 " + (homeSlot + 1) + " 格)没匹配到结算按钮, 第 "
                                + (other + 1) + " 格有 — 只提示不拦车 (fast.tap_check=false)"
                                + " | rematch" + fmt(rp) + " continue" + fmt(cp));
                    }
                }
            }

            /* ④ 无进展停车 */
            if (idleS > 0 && t0 - lastBig > idleS * 1000L) {
                Graph.cursor("fxidle");
                SgmLog.i("停", "快速模式: 连续 " + idleS + "s 一场没计到 — 停车 "
                        + "(多半已脱离战斗流程; 调 config.fast.idle_stop_s 或关掉它)");
                run.set(false);
                break;
            }

            Graph.cursor("fxobs");
            phaseText = "快速连点";
            snapshot();
            bmp.recycle();
            long used = System.currentTimeMillis() - t0;
            /* fight 追踪窗口内把采样节奏提到 fight_scan_ms (默认 500ms):
             * 15s 窗口按 1s 采样只看 15 帧, FIGHT 按钮亮着的时间不长, 采样太疏会错过 */
            long want = t0 < fxFightUntil ? Math.min(obsMs, fightScanMs) : obsMs;
            if (used < want) sleep_(want - used);
        }
        SgmLog.i("快", "观察线程退出 — 共 " + frames + " 帧 / 剥弹窗 " + pops + " 次 / 连点 "
                + fxTaps + " 次");
    }

    /* ==================== FIGHT 追踪窗口 + 大保底 (map_check) ==================== */

    /**
     * fast.fight 判据: {tpl, th, roi} — 缺省 tpl=vs_fight; **th/roi 缺省继承
     * escape.items 里同名项**。同一素材只标定一次, 两处不会漂移 (这里抄一份配置,
     * 日后改了 escape 阈值忘了这边, 追踪口径就变了 —— 继承从根上免掉这个问题)。
     * 都没有时才回落 th=0.75 / roi=[1020,0,260,130] (escape 链里 vs_fight 的原值)。
     */
    private void parseFightCfg(JSONObject c) {
        JSONObject fx = sub(c, "fast");
        JSONObject fc = fx.optJSONObject("fight");
        fightTpl = fc == null ? "vs_fight" : fc.optString("tpl", "vs_fight");
        double thv = fc == null ? -1 : fc.optDouble("th", -1);
        JSONArray roi = fc == null ? null : fc.optJSONArray("roi");
        JSONArray items = c.optJSONObject("escape") == null
                ? null : c.optJSONObject("escape").optJSONArray("items");
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.optJSONObject(i);
                if (it == null || !fightTpl.equals(it.optString("tpl"))) continue;
                if (thv < 0) thv = it.optDouble("th", -1);
                if (roi == null) roi = it.optJSONArray("roi");
                break;
            }
        }
        fightTh = thv > 0 ? thv : 0.75;
        fightRoiRaw = roi != null && roi.length() >= 4 ? ia(roi)
                : new int[]{1020, 0, 260, 130};
        fightWaitMs = Math.max(0, fx.optInt("fight_wait_s", 15)) * 1000L;
        fightScanMs = Math.max(200, fx.optInt("fight_scan_ms", 500));
        fxFightUntil = 0;                      // 换配置后旧窗口作废
    }

    /** map_check 判据: {on, every_s, tpl, th, roi} — 大保底, 两模式共用 */
    private void parseMapCfg(JSONObject c) {
        JSONObject m = sub(c, "map_check");
        mapOn = m.optBoolean("on", true);
        mapEveryMs = Math.max(30, m.optInt("every_s", 300)) * 1000L;
        mapTpl = m.optString("tpl", "home");
        mapTh = m.optDouble("th", 0.85);
        JSONArray r = m.optJSONArray("roi");
        mapRoiRaw = r != null && r.length() >= 4 ? ia(r) : new int[]{0, 0, 180, 70};
        lastMapChk = 0;
        tHome = null; homeR = null;
    }

    /** fight 模板/ROI 懒解析 (分辨率变了才重算); 缺模板返回 false — 追踪不可用 */
    private boolean resolveFight(Vision.Frame work) {
        if (tFight != null && fightR != null && work.w == chkW && work.h == chkH) return true;
        tFight = tpls.getScaled(fightTpl,
                Roi.scaleX(cfgRef, work.w, work.h), Roi.scaleY(cfgRef, work.w, work.h));
        fightR = Roi.resolve(cfgRef, fightRoiRaw, work.w, work.h);
        chkW = work.w; chkH = work.h;
        return tFight != null;
    }

    /** home 模板/ROI 懒解析 (同上) */
    private boolean resolveHome(Vision.Frame work) {
        if (tHome != null && homeR != null && work.w == chkW && work.h == chkH) return true;
        tHome = tpls.getScaled(mapTpl,
                Roi.scaleX(cfgRef, work.w, work.h), Roi.scaleY(cfgRef, work.w, work.h));
        homeR = Roi.resolve(cfgRef, mapRoiRaw, work.w, work.h);
        chkW = work.w; chkH = work.h;
        return tHome != null;
    }

    /**
     * 大保底 — 检测"是否已退到大地图/大厅" (左上角 home_roi 里出现 home 房子钮)。
     *
     * <p>返回 true = 已停车 (run 置 false)。铁律:
     * <ul>
     *   <li>不命中**绝不**停车 — 单帧峰值、日志照记, 让现场能拿它调 th;</li>
     *   <li>命中先**复检一帧** (隔 800ms 重新截屏再判) — 36x36 的小图标纹理少,
     *       NCC 易在别的界面虚高, 两帧连续过阈才认。误杀一次 = 用户整轮挂机白跑,
     *       保守方向是"漏报"不是"误报"。</li>
     * </ul>
     *
     * <p>⚠️ 只截屏判定, **不点击** — 大地图上该去哪 (重进 EVENTS/PLAY) 属于
     * NavChain (M2), 没导航链之前唯一安全的动作就是停下来, 留给用户或下次开跑。
     */
    private boolean mapGuard(Vision.Frame work, AtomicBoolean run) {
        long now = System.currentTimeMillis();
        if (now - lastMapChk < mapEveryMs) return false;
        lastMapChk = now;
        if (!resolveHome(work)) return false;
        double[] h1 = findExact(work, tHome, homeR, mapTh);
        double s1 = h1 == null ? -2 : h1[2];
        mapChecks++;
        boolean hit1 = s1 >= mapTh;
        SgmLog.i("图", "大保底检查 → home 峰值 " + fmt1(s1) + " th=" + mapTh
                + (hit1 ? " 命中! 复检一帧…" : ""));
        if (!hit1) return false;
        Trace.put("mapchk", "match", mapTpl, homeR, s1, true, "首帧命中 → 复检");
        sleep_(800);                           // 复检间隔; 5min 才一次, 阻塞无碍
        Bitmap b2 = sh.capture();
        if (b2 == null) {
            SgmLog.i("图", "复检截屏失败 — 本轮不停车 (下个周期再看)");
            return false;
        }
        Vision.Frame w2 = Vision.toWork(b2);
        double[] h2 = findExact(w2, tHome, homeR, mapTh);
        double s2 = h2 == null ? -2 : h2[2];
        b2.recycle();
        if (s2 < mapTh) {
            Trace.put("mapchk", "match", mapTpl, homeR, s2, false, "复检未过阈 — 判误报");
            SgmLog.i("图", "复检峰值 " + fmt1(s2) + " 未过阈 — 判为误报, 继续挂机");
            return false;
        }
        Graph.cursor("mapchk");
        Trace.put("mapchk", "match", mapTpl, homeR, s2, true, "两帧确认 → 停车");
        SgmLog.i("停", "大保底: 左上角连着两帧都是 home 图标 (峰值 " + fmt1(s1) + "/"
                + fmt1(s2) + ") — 已退到大地图/大厅, 停车 (关掉改 config.map_check.on=false)");
        run.set(false);
        return true;
    }

    private static String fmt1(double v) { return v < -1 ? "-" : String.format("%.2f", v); }

    /* ---- ROI / 坐标的多分辨率映射 ---- */

    /** 取模板 (按当前屏幕的 x/y 缩放倍数); 缺 victory/defeat 返回 false */
    private boolean loadTpls(double sx, double sy) {
        tVic = tpls.getScaled("victory", sx, sy);
        tDef = tpls.getScaled("defeat", sx, sy);
        tRm = tpls.getScaled("btn_rematch", sx, sy);
        tCt = tpls.getScaled("btn_continue", sx, sy);
        return tVic != null && tDef != null;
    }

    private void syncRoi(Vision.Frame work, int[] titleRoi, List<int[]> btnRois) {
        if (work.w == roiW && work.h == roiH && titleR != null) return;
        roiW = work.w; roiH = work.h;
        double sx = Roi.scaleX(cfgRef, work.w, work.h);
        double sy = Roi.scaleY(cfgRef, work.w, work.h);
        if (Math.abs(sx - tplSX) > 0.01 || Math.abs(sy - tplSY) > 0.01) {
            tplSX = sx; tplSY = sy;
            if (!loadTpls(sx, sy))
                SgmLog.i("bot", "模板在 " + sx + "x" + sy + " 倍缩放下取不到 — 保持上一版");
        }
        titleR = Roi.resolve(cfgRef, titleRoi, work.w, work.h);
        btnR = new ArrayList<>();
        for (int[] r : btnRois) btnR.add(Roi.resolve(cfgRef, r, work.w, work.h));
        avoidR = resolveAvoid(work.w, work.h);
        SgmLog.i("屏", work.w + "x" + work.h + " (ar=" + String.format("%.2f", work.w / (float) work.h)
                + ") 档=" + Roi.profileOf(cfgRef, work.w, work.h)
                + " mode=" + Roi.modeOf(cfgRef, work.w, work.h));
    }

    /**
     * 解析「要绕开的 HUD 挡块」到当前分辨率。
     *
     * <p>config.fast.avoid 缺省时回落到 {@link WorkPoint#DEFAULT_AVOID} —— 那条是
     * K70 真机实测的战斗速度泡, **不能因为配置里没写就当没有**: 少了它落点会重新
     * 落回速度泡中心 (2026-10-11 用户报障「会点到 x3 那里」)。
     */
    private List<int[]> resolveAvoid(int w, int h) {
        JSONObject f = sub(cfgRef, "fast");
        avoidGap = f == null ? WorkPoint.DEFAULT_GAP : clamp(f.optInt("avoid_gap", WorkPoint.DEFAULT_GAP), 0, 200);
        JSONArray raw = f == null ? null : f.optJSONArray("avoid");
        if (raw == null || raw.length() == 0) return WorkPoint.defaultAvoid();
        List<int[]> l = new ArrayList<int[]>(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            JSONArray e = raw.optJSONArray(i);
            if (e == null || e.length() < 4) continue;
            l.add(Roi.resolve(cfgRef, new int[]{
                    (int) e.optDouble(0), (int) e.optDouble(1),
                    (int) e.optDouble(2), (int) e.optDouble(3)}, w, h));
        }
        return l.isEmpty() ? WorkPoint.defaultAvoid() : l;
    }

    /** 落点的一句话描述 (就绪日志 + 状态快照共用) */
    private String fxPointText() {
        return "(" + (int) fxX + "," + (int) fxY + ") " + WorkPoint.describe(fxState);
    }

    /** 挡块表的人话 (日志用); 空表 = 没配也没回落到出厂值, 不该发生 */
    private String avoidText() {
        if (avoidR == null || avoidR.isEmpty()) return "无";
        StringBuilder sb = new StringBuilder("间距" + avoidGap + " [");
        for (int i = 0; i < avoidR.size(); i++) {
            int[] r = avoidR.get(i);
            if (i > 0) sb.append(' ');
            sb.append(r[0]).append(',').append(r[1]).append(',')
              .append(r[2]).append(',').append(r[3]);
        }
        return sb.append(']').toString();
    }

    /**
     * 快速连点模式的落点解析:
     *   ① config.fast.xy 有值 → 按当前屏幕映射 (与其它标定坐标同口径);
     *   ② 缺省 → **btn_rois 中槽中心, 再绕开 HUD 挡块**。
     *
     * <p>取中槽不是随手挑的: 结算页的按钮行是**整行居中**的, 所以"单按钮页"
     * (MATCH REWARDS: 只有 CONTINUE) 和"三按钮页" (FAVORITE|REMATCH|CONTINUE) 的
     * 中间一颗落在**同一个 x** —— 这就是用户说的「第一个界面的 CONTINUE 和第二个界面的
     * REMATCH 在同一个位置」。由 btn_rois 推出来还有个好处: 换分辨率时跟着 ROI 映射一起走。
     *
     * <p><b>但中槽中心本身不能直接点</b> (2026-10-11 用户报障「会点到 x3 那里」):
     * 战斗界面的 1x/3x 速度泡实测占 work x608~672 / y450~518, 其下沿正是中槽中心的 y512
     * ⇒ 盲点在战斗帧上会一路点亮速度泡。所以再走一步 {@link WorkPoint#pick} 把落点
     * 推到挡块右侧 (仍在结算按钮面板 x546~734 内)。
     */
    private void resolveFastPoint(JSONArray fxy) {
        if (fxy != null && fxy.length() >= 2) {
            float[] p = Roi.resolvePoint(cfgRef, new float[]{
                    (float) fxy.optDouble(0), (float) fxy.optDouble(1)}, roiW, roiH);
            if (p != null && p.length >= 2) {
                fxX = p[0]; fxY = p[1];
                fxState = WorkPoint.inside(avoidR, fxX, fxY) ? 3 : 0;   // 手填坐标也体检一次
                return;
            }
        }
        if (btnR != null && btnR.size() > 1) {
            float[] p = WorkPoint.pick(btnR.get(1), avoidR, avoidGap);
            fxX = p[0]; fxY = p[1]; fxState = (int) p[2];
        }
    }

    private static int clamp(int v, int lo, int hi) { return v < lo ? lo : v > hi ? hi : v; }

    /** 夜间暂停: 支持跨夜 (如 23:30~07:00) */
    private static boolean inPause(JSONObject c) {
        JSONObject r = sub(c, "run");
        String a = r.optString("pause_from", ""), b = r.optString("pause_to", "");
        if (a.length() < 3 || b.length() < 3) return false;
        int now = hhmm(Calendar.getInstance()), f = hhmm(a), t = hhmm(b);
        if (now < 0 || f < 0 || t < 0) return false;
        return f <= t ? (now >= f && now < t) : (now >= f || now < t);
    }

    private static int hhmm(Calendar c) {
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
    }

    private static int hhmm(String s) {
        try {
            String[] p = s.split(":");
            return Integer.parseInt(p[0].trim()) * 60 + Integer.parseInt(p[1].trim());
        } catch (Exception e) { return -1; }
    }

    /* ---- 计场 ---- */

    private void countRound(boolean win, AtomicBoolean run, int cap) {
        daily.addRound(win);
        daily.save();                          // 每场落盘: 崩溃/重启不丢今日计数
        rounds++;
        if (win) wins++; else loses++;
        SgmLog.i("计", "今日第 " + daily.rounds + " 场 (" + (win ? "胜" : "负") + ") 本次 "
                + wins + "胜" + loses + "负");
        if (daily.capReached(cap)) {
            SgmLog.i("停", daily.text(cap) + " 已达每日上限 — 停止 (改 config daily_cap 调整)");
            run.set(false);
        }
    }

    /* ---- stall 逃生 (链即配置) ---- */

    /** 按 config escape.items 顺序试: 命中即点并返回 true; 全不中再用三槽按钮兜底 */
    private boolean escape(Vision.Frame work, Bitmap bmp, JSONObject c) {
        JSONObject esc = c.optJSONObject("escape");
        if (esc == null) return false;
        float[] okOff = farr(esc, "ok_offset", new float[]{210, 188});
        JSONArray items = esc.optJSONArray("items");
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.optJSONObject(i);
                if (it == null) continue;
                String name = it.optString("tpl");
                double thv = it.optDouble("th", 0.75);
                Vision.Frame t = tpls.getScaled(name, tplSX, tplSY);
                if (t == null) continue;                   // 缺该模板: 跳过这一条
                int[] roi = it.has("roi")
                        ? Roi.resolve(c, ia(it.optJSONArray("roi")), work.w, work.h) : null;
                /* ⚠️ 这里**故意**不调 findExact: escape 链是"逐条试、命中即点、全不中还有三槽兜底"
                 * 的结构, 单条漏检只影响下一次重试; 而 roi=null 的 srv_ok/srv_retry 是**全屏搜**,
                 * 只有金字塔能救 (3.27e4 ms → 33 ms)。所以这一处保留 config.matcher.pyramid 的口径。 */
                double[] hit = Vision.findScored(work, t, roi, thv);
                if (hit == null || hit[2] < thv) {
                    Trace.put("escape", "match", name, roi, hit == null ? -2 : hit[2], false, "");
                    continue;
                }
                /* srv_ok 的 OK 按钮不在模板内 → 走标定偏移; 其余点模板中心
                   (偏移是基准 1280 下标定的, 非 1 倍屏幕要一起缩放) */
                float ox = "srv_ok".equals(name) ? (float) (okOff[0] * tplSX) : t.w / 2f;
                float oy = "srv_ok".equals(name) ? (float) (okOff[1] * tplSY) : t.h / 2f;
                tap(bmp, (float) hit[0] + ox, (float) hit[1] + oy);
                Trace.put("escape", "match", name, roi, hit[2], true, "点 " + name);
                SgmLog.i("弹", "stall 关弹窗 (" + name + ") → (" + (hit[0] + (int) ox)
                        + "," + (hit[1] + (int) oy) + ")");
                return true;
            }
        }
        /* 兜底: 三槽按钮 (把卡住的结算页推一把)
         * ⚠️ 用**已解析**的 btnR (字段), 不是 rois(c,"btn_rois") 的原始像素 ——
         *    原始值只在 abs 模式下成立, 非 abs (center/rel/fit) 屏上会点偏。 */
        double minSim = th(c, "min_sim", 0.72);
        List<int[]> btnRois = btnR != null ? btnR : rois(c, "btn_rois");
        double[] rb = findSlot(work, tRm, btnRois, minSim);
        boolean isRm = rb != null && rb[2] >= minSim;
        double[] hb = isRm ? rb : findSlot(work, tCt, btnRois, minSim);
        if (hb != null && hb[2] >= minSim) {
            tap(bmp, (float) hb[0], (float) hb[1]);
            Trace.put("escape", "tap", isRm ? "btn_rematch" : "btn_continue", null, hb[2], true, "兜底点按钮");
            SgmLog.i("弹", "stall 点按钮 (" + (isRm ? "REMATCH" : "CONTINUE") + ") → ("
                    + (int) hb[0] + "," + (int) hb[1] + ")");
            return true;
        }
        return false;
    }

    /** 导航自愈 (断联兜底) — M2 接 NavChain; 当前只记日志, 绝不盲点 */
    private void selfHeal() {
        Graph.cursor("stall");
        SgmLog.i("救", "重跑导航自愈 — 待 NavChain (M2) 接入, 当前不盲点");
    }

    /* ---- 原语 ---- */

    /* ---- 匹配口径: 召回关键判定强制全搜 ----
     * ⚠️ 大字计场 / FIGHT 追踪 / 大保底 / 结算按钮 / 三槽 —— 这几处一律**不受
     *    config.matcher.pyramid 影响**。依据 (tools/VisionBench, 真机结算页素材, 2026-10-14):
     *      · 模板真在画面里时, 走金字塔 (0.5x / 0.25x) 峰值只剩 **0.15 / 0.18**
     *        (btn_rematch 甚至是 **-0.04**), 而全搜是 1.0000 ⇒ 直接丢检。
     *      · 更狠的是**粗搜分与真相反相**: 漏检帧粗分 0.33~0.47 反而**高于**命中帧的
     *        0.07~0.18 ⇒ 连"粗搜当门、不确定就回退全搜"这条退路也不成立。
     *      · 而且对"大模板 + 紧 ROI"它还是**负收益**: 粗搜省下的被精修
     *        (PYR_TOP x (2*rad+1)^2 x 模板面积) 吃掉, btn_rematch 反而慢 3~4 倍。
     *    ⇒ 金字塔的用武之地只有**全屏搜大按钮** (srv_ok 32700ms → 33ms), 即 escape 链里
     *      roi=null 的那两条。别处一律全搜 —— 这几个判定错一次就是漏计场次 / 漏点按钮。
     */
    private static final double FULL_COST = 1e12;   // 成本门槛抬到够不着
    private static final int FULL_LEVEL = 0;        // 层数 0 = 关 (与上面双保险)

    /** 强制全搜的 findScored —— 召回关键判定的唯一入口, 别直接调 Vision.findScored */
    private static double[] findExact(Vision.Frame work, Vision.Frame tpl, int[] roi, double th) {
        return Vision.findScored(work, tpl, roi, th, FULL_COST, FULL_LEVEL);
    }

    private void tap(Bitmap bmp, float wx, float wy) {
        sh.tapWork(bmp, wx, wy);
    }

    private static String xy(Bitmap bmp, float wx, float wy) {
        float s = bmp.getHeight() / (float) ShizukuCtl.WORK_H;
        return "(" + Math.round(wx * s) + "," + Math.round(wy * s) + ")";
    }

    /**
     * 三个槽各自的 NCC 峰值 — **只喂日志, 不参与判定**。
     * th 传 2.0 ⇒ findScored 不会走"过阈值提前返回"的分支, 一定穷举完整个 ROI 再取
     * 真峰值 (否则第一个过阈值的点就把峰值截断了, 三槽分数之间没有可比性)。
     */
    private static String peaks(Vision.Frame work, Vision.Frame tpl, List<int[]> rois) {
        return fmt(peakArr(work, tpl, rois));
    }

    /**
     * 每个槽各自的 NCC 峰值 —— **只喂日志/体检, 不参与常规判定**。
     * th 传 2.0 ⇒ findScored 不会走"过阈值提前返回"的分支, 一定穷举完整个 ROI 再取
     * 真峰值 (否则第一个过阈值的点就把峰值截断了, 槽与槽之间没有可比性)。
     */
    private static double[] peakArr(Vision.Frame work, Vision.Frame tpl, List<int[]> rois) {
        double[] out = new double[rois == null ? 0 : rois.size()];
        if (tpl == null || rois == null) { java.util.Arrays.fill(out, -2); return out; }
        for (int i = 0; i < rois.size(); i++) {
            double[] p = Vision.findScored(work, tpl, rois.get(i), 2.0);
            out[i] = p == null ? -2 : p[2];
        }
        return out;
    }

    private static String fmt(double[] v) {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(v[i] < -1 ? "-" : String.format("%.2f", v[i]));
        }
        return sb.append(')').toString();
    }

    /** 峰值数组 → 人话页面名 (只用于日志) */
    private static String seenOf(double[] rp, double[] cp, double minSim, boolean big) {
        for (double v : rp) if (v >= minSim) return "REMATCH页";
        for (double v : cp) if (v >= minSim) return "CONTINUE页";
        return big ? "大字页" : "其他(战斗)";
    }

    /** 落点落在第几格 (0 起); 不在任何槽里返回 -1 —— 快速模式的落点体检用 */
    private int slotOfPoint() {
        if (btnR == null) return -1;
        for (int i = 0; i < btnR.size(); i++) {
            int[] r = btnR.get(i);
            if (fxX >= r[0] && fxX <= r[0] + r[2] && fxY >= r[1] && fxY <= r[1] + r[3]) return i;
        }
        return -1;
    }

    /** 三槽里找按钮, 返回 {命中中心x, 中心y, 峰值}; 未过阈值也带回峰值 (图上好看) */
    private static double[] findSlot(Vision.Frame work, Vision.Frame tpl,
                                     List<int[]> rois, double th) {
        if (tpl == null) return null;
        double best = -2, bx = 0, by = 0;
        for (int[] r : rois) {
            double[] p = findExact(work, tpl, r, th);
            if (p == null) continue;
            if (p[2] > best) { best = p[2]; bx = p[0] + tpl.w / 2f; by = p[1] + tpl.h / 2f; }
            if (p[2] >= th) return new double[]{bx, by, best};
        }
        return best > -2 ? new double[]{bx, by, best} : null;
    }

    private void pushUi(int cap) {
        if (bar == null) return;
        long s = (System.currentTimeMillis() - runStart) / 1000;
        String dur = (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
        bar.setStat("运行", dur, daily.text(cap), wins, loses);
    }

    private void snapshot() {
        try {
            SgmLog.state(new JSONObject()
                    .put("mode", fastOn ? "settle-fast" : "settle").put("running", true)
                    .put("phase", fastOn ? "fast" : (phase == 0 ? "battle" : "result"))
                    .put("rounds", rounds).put("wins", wins).put("loses", loses)
                    .put("rematches", rematches).put("continues", continues)
                    .put("fast", fastOn).put("fast_taps", fxTaps)
                    .put("fast_fight", fightTaps).put("map_checks", mapChecks)
                    .put("fast_xy", (int) fxX + "," + (int) fxY)
                    .put("fast_xy_why", WorkPoint.describe(fxState))
                    .put("matcher", Vision.modeText())
                    .put("roi_mode", Roi.modeOf(cfgRef, roiW, roiH))
                    .put("work", roiW + "x" + roiH)
                    .put("graph_cursor", Graph.cursor())
                    .put("shizuku_ready", ShizukuCtl.ready()));
        } catch (Exception ignored) { }
    }

    private static void sleep_(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /* ---- config 小工具 ---- */

    /** 取子段; 缺则空对象 (免得到处判 null) */
    private static JSONObject sub(JSONObject c, String k) {
        JSONObject o = c == null ? null : c.optJSONObject(k);
        return o == null ? new JSONObject() : o;
    }

    /** scan.<key> 优先, 回落 timing.<key>, 再回落 dflt */
    private static int scanMs(JSONObject c, String key, int dflt) {
        int v = sub(c, "scan").optInt(key, -1);
        return v > 0 ? v : dflt;
    }

    private static double th(JSONObject c, String k, double dflt) {
        JSONObject t = c.optJSONObject("thresholds");
        return t != null ? t.optDouble(k, dflt) : dflt;
    }

    private static int tg(JSONObject c, String k, int dflt) {
        JSONObject t = c.optJSONObject("timing");
        return t != null ? t.optInt(k, dflt) : dflt;
    }

    private static int[] arr(JSONObject c, String k, int[] dflt) {
        JSONArray a = c.optJSONArray(k);
        return a != null && a.length() >= 4 ? ia(a) : dflt;
    }

    private static float[] farr(JSONObject c, String k, float[] dflt) {
        JSONArray a = c.optJSONArray(k);
        if (a == null || a.length() < 2) return dflt;
        try {
            return new float[]{(float) a.getDouble(0), (float) a.getDouble(1)};
        } catch (Exception e) {
            return dflt;
        }
    }

    private static List<int[]> rois(JSONObject c, String k) {
        List<int[]> out = new ArrayList<>();
        JSONArray a = c.optJSONArray(k);
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            JSONArray r = a.optJSONArray(i);
            if (r != null && r.length() >= 4) out.add(ia(r));
        }
        return out;
    }

    private static int[] ia(JSONArray a) {
        int[] v = new int[Math.min(a.length(), 4)];
        for (int i = 0; i < v.length; i++) v[i] = a.optInt(i, 0);
        return v;
    }
}
