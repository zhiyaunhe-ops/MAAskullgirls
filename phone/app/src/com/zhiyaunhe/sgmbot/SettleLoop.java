package com.zhiyaunhe.sgmbot;

import android.graphics.Bitmap;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 结算循环 — 移植 phone/autojs/settle_bot.js 的 loop() (两阶段状态机, 同口径同文案)。
 *
 *   phase 0 (战斗): 只查 VICTORY!/DEFEAT! 大字 (便宜, battle_ms 一轮)
 *     命中 → 记胜负 + 先手点右槽固定位置 (胜局该格=CONTINUE / 败局该格=REMATCH,
 *            两种情况都不用按钮匹配) → 进 phase 1; 败局先手即一局完成, 当场计场
 *   phase 1 (结算/奖励): 只在三槽查 REMATCH/CONTINUE (result_ms 一轮, 快速跟点)
 *     REMATCH → 点 + 计场 + 回 phase 0; CONTINUE → 点, 保持 phase 1 (可能还有下一页)
 *     连续 result_fallback 帧无按钮 → 判定已入战斗, 回 phase 0
 *
 * 计场时机 (关键, 2026-10-05 教训): 大字**不**计场 —— 结算两页都有 VICTORY 横幅,
 * 过场会重置 seen 导致重复计数 (实测虚高 20 倍); 统一挪到「点 REMATCH / 败局先手」
 * 那一刻, 一局恰一次。
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
    private Store daily;

    private final ShizukuCtl sh;
    private final TplStore tpls;
    private final OverlayBar bar;
    private volatile String phaseText = "战斗监测";

    public SettleLoop(ShizukuCtl sh, TplStore tpls, OverlayBar bar) {
        this.sh = sh;
        this.tpls = tpls;
        this.bar = bar;
    }

    /** 小状态文案 (悬浮条/状态快照用) */
    public String phaseText() { return phaseText; }

    /**
     * 结算循环主入口 (在 BotService 工作线程里跑)。
     * @param run 停止标志 — 达到每日上限时本方法内部也会置 false
     */
    public void run(AtomicBoolean run, Config cfg) {
        JSONObject c = cfg.read();
        final int cap = c.optInt("daily_cap", 0);
        final double minSim = th(c, "min_sim", 0.72);
        final int battleMs = tg(c, "battle_ms", 800);
        final int resultMs = tg(c, "result_ms", 350);
        final int tapDelay = tg(c, "tap_delay_ms", 1200);
        final int stallSec = tg(c, "stall_sec", 30);
        final int battleStallSec = tg(c, "battle_stall_sec", 180);
        final int resultFallback = c.optInt("result_fallback", 3);
        final int[] titleRoi = arr(c, "title_roi", new int[]{400, 20, 480, 100});
        final float[] firstTap = farr(c, "first_tap", new float[]{903, 512});
        final List<int[]> btnRois = rois(c, "btn_rois");

        Vision.Frame tVic = tpls.get("victory"), tDef = tpls.get("defeat");
        Vision.Frame tRm = tpls.get("btn_rematch"), tCt = tpls.get("btn_continue");
        if (tVic == null || tDef == null) {
            SgmLog.i("bot", "缺模板 victory/defeat — 不开跑 (assets/templates 或 "
                    + TplStore.EXT_DIR + " 下补齐)");
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
        SgmLog.i("bot", "结算循环开始 — " + daily.text(cap) + " 上限=" + (cap > 0 ? cap : "不限"));

        while (run.get()) {
            Bitmap bmp = sh.capture();
            if (bmp == null) {
                SgmLog.i("bot", "截屏失败 — 下一轮重试");
                sleep_(battleMs);
                continue;
            }
            Vision.Frame work = Vision.toWork(bmp);
            long now = System.currentTimeMillis();

            if (phase == 0) {
                /* —— 战斗阶段: 只查两个大字 —— */
                int[] vic = Vision.find(work, tVic, titleRoi, minSim);
                int[] def = vic == null ? Vision.find(work, tDef, titleRoi, minSim) : null;
                if (vic == null && def == null) {
                    seen = false;                       // 无大字 = 战斗/过场
                } else if (!seen) {
                    seen = true;
                    phase = 1;
                    noHitRun = 0;
                    battleStalls = 0;                   // 见到大字 = 游戏活着
                    lastVic = vic != null;
                    SgmLog.i("场", lastVic ? "VICTORY" : "DEFEAT");

                    /* 先手点右槽固定位置 (免按钮匹配) */
                    tap(bmp, firstTap[0], firstTap[1]);
                    lastAct = System.currentTimeMillis();
                    SgmLog.i("动", "先手 (" + (lastVic ? "CONTINUE" : "REMATCH") + ") → "
                            + xy(bmp, firstTap[0], firstTap[1]));
                    if (!lastVic) countRound(false, run, cap);   // 败局先手=REMATCH 即一局完成
                    sleep_(tapDelay);
                }
            } else {
                /* —— 结算/奖励阶段: 只查 REMATCH / CONTINUE (各三槽) —— */
                float[] rm = findSlot(work, tRm, btnRois, minSim);
                if (rm != null) {
                    tap(bmp, rm[0], rm[1]);
                    rematches++;
                    lastAct = System.currentTimeMillis();
                    noHitRun = 0;
                    SgmLog.i("动", "REMATCH (第 " + rematches + " 场) → " + xy(bmp, rm[0], rm[1]));
                    countRound(lastVic, run, cap);               // 一局完成, 每局恰一次
                    phase = 0;
                    battleT0 = System.currentTimeMillis();
                    sleep_(tapDelay);
                } else {
                    float[] ct = findSlot(work, tCt, btnRois, minSim);
                    if (ct != null) {
                        tap(bmp, ct[0], ct[1]);
                        continues++;
                        lastAct = System.currentTimeMillis();
                        noHitRun = 0;                    // 保持结算阶段 (可能还有下一页)
                        SgmLog.i("动", "CONTINUE 略过 → " + xy(bmp, ct[0], ct[1]));
                        sleep_(tapDelay);
                    } else {
                        noHitRun++;
                        if (noHitRun >= resultFallback) {
                            phase = 0;
                            battleT0 = System.currentTimeMillis();
                            SgmLog.i("转", "进入战斗监测");
                        }
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
            sleep_(phase == 0 ? battleMs : resultMs);
        }
        SgmLog.i("bot", "结算循环结束 — 本次 " + rounds + " 场 " + wins + "胜" + loses + "负");
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
                Vision.Frame t = tpls.get(name);
                if (t == null) continue;                   // 缺该模板: 跳过这一条
                int[] roi = it.has("roi") ? ia(it.optJSONArray("roi")) : null;
                int[] hit = Vision.find(work, t, roi, thv);
                if (hit == null) continue;
                /* srv_ok 的 OK 按钮不在模板内 → 走标定偏移; 其余点模板中心 */
                float ox = "srv_ok".equals(name) ? okOff[0] : t.w / 2f;
                float oy = "srv_ok".equals(name) ? okOff[1] : t.h / 2f;
                tap(bmp, hit[0] + ox, hit[1] + oy);
                SgmLog.i("弹", "stall 关弹窗 (" + name + ") → (" + (hit[0] + (int) ox)
                        + "," + (hit[1] + (int) oy) + ")");
                return true;
            }
        }
        /* 兜底: 三槽按钮 (把卡住的结算页推一把) */
        double minSim = th(c, "min_sim", 0.72);
        List<int[]> btnRois = rois(c, "btn_rois");
        float[] rb = findSlot(work, tpls.get("btn_rematch"), btnRois, minSim);
        boolean isRm = rb != null;
        float[] hb = isRm ? rb : findSlot(work, tpls.get("btn_continue"), btnRois, minSim);
        if (hb != null) {
            tap(bmp, hb[0], hb[1]);
            SgmLog.i("弹", "stall 点按钮 (" + (isRm ? "REMATCH" : "CONTINUE") + ") → ("
                    + (int) hb[0] + "," + (int) hb[1] + ")");
            return true;
        }
        return false;
    }

    /** 导航自愈 (断联兜底) — M2 接 NavChain; 当前只记日志, 绝不盲点 */
    private void selfHeal() {
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

    /** 三槽里找按钮, 返回命中**中心** (work 坐标); 未命中 null */
    private static float[] findSlot(Vision.Frame work, Vision.Frame tpl,
                                    List<int[]> rois, double th) {
        if (tpl == null) return null;
        for (int[] r : rois) {
            int[] p = Vision.find(work, tpl, r, th);
            if (p != null) return new float[]{p[0] + tpl.w / 2f, p[1] + tpl.h / 2f};
        }
        return null;
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
                    .put("shizuku_ready", ShizukuCtl.ready()));
        } catch (Exception ignored) { }
    }

    private static void sleep_(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /* ---- config 小工具 ---- */

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
