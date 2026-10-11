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
    private float[] firstTapR = null;
    private JSONObject cfgRef;
    /** 模板缩放倍数 (非 abs/center 分辨率模式下 ≠1) — 变了就重新取缩放版模板 */
    private double tplSX = 1.0, tplSY = 1.0;
    private Vision.Frame tVic, tDef, tRm, tCt;

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
        final float[] firstTap = farr(c, "first_tap", new float[]{903, 512});
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
            syncRoi(work, titleRoi, btnRois, firstTap);
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

            if (phase == 0) {
                /* —— 战斗阶段: 只查两个大字 —— */
                Graph.cursor("battle");
                double[] v = Vision.findScored(work, tVic, titleR, minSim);
                boolean vic = v != null && v[2] >= minSim;
                double[] d = vic ? null : Vision.findScored(work, tDef, titleR, minSim);
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
        SgmLog.i("bot", "结算循环结束 — 本次 " + rounds + " 场 " + wins + "胜" + loses + "负");
    }

    /* ---- ROI / 坐标的多分辨率映射 ---- */

    /** 取模板 (按当前屏幕的 x/y 缩放倍数); 缺 victory/defeat 返回 false */
    private boolean loadTpls(double sx, double sy) {
        tVic = tpls.getScaled("victory", sx, sy);
        tDef = tpls.getScaled("defeat", sx, sy);
        tRm = tpls.getScaled("btn_rematch", sx, sy);
        tCt = tpls.getScaled("btn_continue", sx, sy);
        return tVic != null && tDef != null;
    }

    private void syncRoi(Vision.Frame work, int[] titleRoi, List<int[]> btnRois, float[] firstTap) {
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
        firstTapR = Roi.resolvePoint(cfgRef, firstTap, work.w, work.h);
        SgmLog.i("屏", work.w + "x" + work.h + " (ar=" + String.format("%.2f", work.w / (float) work.h)
                + ") 档=" + Roi.profileOf(cfgRef, work.w, work.h)
                + " mode=" + Roi.modeOf(cfgRef, work.w, work.h));
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
        /* 兜底: 三槽按钮 (把卡住的结算页推一把) */
        double minSim = th(c, "min_sim", 0.72);
        List<int[]> btnRois = rois(c, "btn_rois");
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
        if (tpl == null) return "[-]";
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < rois.size(); i++) {
            double[] p = Vision.findScored(work, tpl, rois.get(i), 2.0);
            if (i > 0) sb.append(' ');
            sb.append(p == null ? "-" : String.format("%.2f", p[2]));
        }
        return sb.append(')').toString();
    }

    /** 三槽里找按钮, 返回 {命中中心x, 中心y, 峰值}; 未过阈值也带回峰值 (图上好看) */
    private static double[] findSlot(Vision.Frame work, Vision.Frame tpl,
                                     List<int[]> rois, double th) {
        if (tpl == null) return null;
        double best = -2, bx = 0, by = 0;
        for (int[] r : rois) {
            double[] p = Vision.findScored(work, tpl, r, th);
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
                    .put("mode", "settle").put("running", true)
                    .put("phase", phase == 0 ? "battle" : "result")
                    .put("rounds", rounds).put("wins", wins).put("loses", loses)
                    .put("rematches", rematches).put("continues", continues)
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
