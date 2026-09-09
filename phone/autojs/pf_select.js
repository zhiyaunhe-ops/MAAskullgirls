/*
 * SGM 编队能量换人 — 纯逻辑层 (PC pf_vision/pf_bot 编队链的手机搬运, 逻辑先行)
 *
 * PF bot 最核心的调度逻辑: 卡底黄钉数 = 当前能量, ≥门槛(4)才可出战, 每场耗 1 —
 * 固定队伍 REMATCH 连刷数场必撞「能量不足」弹窗, 手机版要长跑必须接入换人。
 *
 * 分层 (同 pf_nav 先例): 本文件不碰 AutoJs6 全局 —
 *   像素读取 px(x, y) → ARGB int 由调用方注入 (真机 images.pixel / Node 合成画布),
 *   px 越界由接入层兜底返回非钉色; planFixTeam 吃当前帧读数吐下一步动作,
 *   拖拽/滚动/筛选面板等 I/O 全在接入层。
 *
 * 能量判据 (用户确认): 严格高亮黄钉芯 R≥240 G≥200 B≤105 + 列占比游程计数,
 *   金卡淡黄底 (231,231,132) 不通过 — 与 tools/pf_vision.py 同参数同阈值。
 *
 * 标定状态 (2026-09-08):
 *   GEOM = PC MuMu 1280x720 实测; 手机 576 基准粗估 y*0.8, 未标定前不接入不点击。
 *   规则芯片筛选 (元素/类别) 未搬 — refillRule 动作已留钩子, 接入层后补。
 */

/* ---------------- 黄钉识别 (pf_vision 同参) ---------------- */

var ENERGY_COST = 4;      // 出战一场需要的能量 (黄钉数), 可被场次配置覆盖
var BOLT_GATE = 0.12;     // 列占比峰值低于此 = 视为无钉条

function isBolt(c) {
    var r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
    return r >= 240 && g >= 200 && b <= 105;
}

/* (cx±half) × [y0,y1) 行带内的黄钉数: 列占比过阈值后的游程计数。
 * opts: th(0.08) runMin(2) runMax(9) rowStep(1; 真机嫌慢可加大, 等效降低行采样) */
function countBolts(px, y0, y1, cx, half, opts) {
    opts = opts || {};
    var th = opts.th || 0.08, runMin = opts.runMin || 2, runMax = opts.runMax || 9;
    var rowStep = opts.rowStep || 1;
    var rows = [];
    for (var y = y0; y < y1; y += rowStep) rows.push(y);
    var maxFrac = 0, runs = 0, w = 0;
    for (var x = Math.max(0, cx - half); x < cx + half; x++) {
        var hit = 0;
        for (var i = 0; i < rows.length; i++) if (isBolt(px(x, rows[i]))) hit++;
        var frac = rows.length ? hit / rows.length : 0;
        if (frac > maxFrac) maxFrac = frac;
        if (frac >= th) w++;
        else { if (w >= runMin && w <= runMax) runs++; w = 0; }
    }
    if (w >= runMin && w <= runMax) runs++;
    return maxFrac < BOLT_GATE ? 0 : runs;
}

/* PC MuMu 1280x720 实测几何 (pf_vision.py); 手机侧待标定 */
var GEOM = {
    base: "PC MuMu 1280x720 (手机 576 基准粗估 y*0.8, 待标定)",
    slotCenters: [132, 324, 516],   // 出战槽钉条等距层: 132+192i
    slotBand: [349, 369], slotHalf: 75, slotRunMax: 8,
    rosterCenters: [116, 314, 512, 710, 908, 1106],   // 候选横列: 116+198i
    rosterBand: [679, 701], rosterHalf: 66,
    slotDropX: [130, 355, 557]      // 扇形卡面拖拽落点 x (y=240)
};

function readSlots(px, geom) {
    var g = geom || GEOM;
    var out = [];
    for (var i = 0; i < g.slotCenters.length; i++) {
        out.push(countBolts(px, g.slotBand[0], g.slotBand[1],
            g.slotCenters[i], g.slotHalf, { runMax: g.slotRunMax }));
    }
    return out;
}

function readRoster(px, geom) {
    var g = geom || GEOM;
    var out = [];
    for (var i = 0; i < g.rosterCenters.length; i++) {
        out.push(countBolts(px, g.rosterBand[0], g.rosterBand[1],
            g.rosterCenters[i], g.rosterHalf));
    }
    return out;
}

/* ---------------- 规则判定 (FIGHT 按钮颜色) ---------------- */

var RULE_LIT_TH = 0.15;   // 高饱和亮色占比 ≥15% = FIGHT 橙 = 规则满足 (pf_bot 实测 ~55%/~0%)

/* ROI 内高饱和亮色占比 (HSV s≥100 v≥120 同口径), 步长 2 抽样控像素读取量 */
function ruleLit(px, x0, y0, x1, y1) {
    var hit = 0, tot = 0;
    for (var y = y0; y < y1; y += 2) {
        for (var x = Math.max(0, x0); x < x1; x += 2) {
            var c = px(x, y);
            var r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
            var mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
            var s = mx === 0 ? 0 : (mx - mn) * 255 / mx;
            tot++;
            if (s >= 100 && mx >= 120) hit++;
        }
    }
    return tot ? hit / tot : 0;
}

/* ---------------- 编队修正状态机 (pf_bot fix_team 同语义) ---------------- */

var PAGES_MAX = 30;                                   // 候选区翻页上限
var OFFSETS = [[0, 0], [14, 6], [-14, 10], [0, 18]];  // 拖拽落点微调 (失败递进, pf_bot 实测)

/* st: {cost, pages, slotFails:{槽:连败}, ruleSlot, needReset} — 本函数原地更新 st。
 * 返回动作: done / refillRule{slot} / drag{src,slot,ox,oy} / resetScroll / page{pages} / fail{why}。
 * 拖拽后由接入层复验槽位能量, 未变好则调 noteDragFail 再回本函数。 */
function planFixTeam(slots, roster, st) {
    var cost = st.cost;
    if (st.needReset) {           // 上次拖拽失败把候选列表滚乱了, 先归零
        st.needReset = false;
        return { act: "resetScroll" };
    }
    /* 规则槽 (1 号) 不足: 优先补合规角色, 先于普通槽 (PC 同) */
    if (st.ruleSlot != null && slots[st.ruleSlot] < cost) {
        return { act: "refillRule", slot: st.ruleSlot };
    }
    var bad = [];
    for (var i = 0; i < slots.length; i++) {
        if (slots[i] < cost && i !== st.ruleSlot) bad.push(i);
    }
    if (!bad.length) return { act: "done" };
    var src = -1;                 // 战力优先 = 从左往右第一个达标候选
    for (var j = 0; j < roster.length; j++) {
        if (roster[j] >= cost) { src = j; break; }
    }
    if (src < 0) {
        st.pages++;
        if (st.pages > PAGES_MAX) {
            return { act: "fail", why: "候选区翻页超限, 无可用能量角色" };
        }
        return { act: "page", pages: st.pages };
    }
    var slot = bad[0], fails = st.slotFails[slot] || 0;
    var off = OFFSETS[Math.min(fails, OFFSETS.length - 1)];
    return { act: "drag", src: src, slot: slot, ox: off[0], oy: off[1] };
}

/* 拖拽未生效 (槽位复验没变好): 记连败 (驱动落点微调) 并标记归零候选列 */
function noteDragFail(st, slot) {
    st.slotFails[slot] = (st.slotFails[slot] || 0) + 1;
    st.needReset = true;
}

if (typeof module !== "undefined" && module.exports) {
    module.exports = {
        ENERGY_COST: ENERGY_COST, GEOM: GEOM, RULE_LIT_TH: RULE_LIT_TH,
        PAGES_MAX: PAGES_MAX, OFFSETS: OFFSETS,
        isBolt: isBolt, countBolts: countBolts,
        readSlots: readSlots, readRoster: readRoster,
        ruleLit: ruleLit, planFixTeam: planFixTeam, noteDragFail: noteDragFail
    };
}
