/*
 * pf_nav / settle_bot 程序级验证 — 电脑上 Node 跑 (真机行为另行验证):
 *   1) pf_nav 纯决策函数单测: 饱和度判可用 / 速度计划 / 大厅逃逸优先级 / 轮播处置
 *   2) pickAndPlay 仿真: stub AutoJs6 全局, 验证 翻卡→点中心→实机坐标换算 与 全灰防死转
 *   3) runNav 控制流: 缺模板自动转采集 + 停止哨兵
 *   4) settle_bot.js 装载冒烟: 三按钮 handler 挂上, 导航/开始 require 通路
 *   5) pf_store 纯逻辑单测: 日历天滚动 / 计数 / 上限 / 存取闭环
 *   6) pf_select 纯逻辑单测: 黄钉游程计数 / 编队修正状态机 / 规则色判定
 * 用法: node test_nav.js   (全部通过退出码 0)
 */
"use strict";
var path = require("path");
var passed = 0, failures = 0;

function ok(cond, name) {
    if (cond) { passed++; console.log("  ok   " + name); }
    else { failures++; console.log("  FAIL " + name); }
}
function section(t) { console.log("\n== " + t); }
function noop() {}

/* ---------- 1. 纯决策函数 ---------- */
var nav = require(path.join(__dirname, "pf_nav.js"));

section("pf_nav 纯决策");
ok(typeof nav.runNav === "function" && typeof nav.runCapture === "function"
    && typeof nav.pickAndPlay === "function", "入口导出齐全");

/* satOfArgb: 橙 PLAY (255,120,48) → (255-48)/255*255 ≈ 207; 灰 (128,128,128) → 0 */
ok(Math.abs(nav.satOfArgb(0xFFFF7830) - 207) < 1, "satOfArgb 橙 ≈ 207");
ok(nav.satOfArgb(0xFF808080) === 0, "satOfArgb 灰 = 0");
ok(nav.vOfArgb(0xFF102030) === 0x30, "vOfArgb = max(r,g,b)");
ok(nav.playAvailable(207) && !nav.playAvailable(40), "橙可用 / 灰不可用");
ok(nav.playAvailable(nav.config.PLAY_MIN_S)
    && !nav.playAvailable(nav.config.PLAY_MIN_S - 1), "饱和度阈值边界");

/* planSpeed — pf_bot ensure_battle_auto 分支表 */
var p = nav.planSpeed(3, true, true);
ok(p.taps === 0 && !p.skip && !p.tapBrain, "3x 不动作");
ok(nav.planSpeed(2, true, true).taps === 1, "2x 点一下");
ok(nav.planSpeed(1, true, true).taps === 2, "1x 点两下");
p = nav.planSpeed(0, true, true);
ok(!p.tapBrain && p.taps === 0 && p.skip, "脑子亮但无泡 → 跳过提速");
p = nav.planSpeed(0, false, true);
ok(p.tapBrain && p.taps === 0, "脑子灭 → 先点脑子");
p = nav.planSpeed(0, false, false);
ok(!p.tapBrain && p.skip, "无速度模板 → 不盲点 (安全门)");

/* decideHallFrame — 优先级: 弹窗X → 结算先手 → CONTINUE → 大厅 → 回家 → 等待 */
var FT = JSON.stringify(nav.config.FIRST_TAP);
ok(nav.decideHallFrame({ x: [1, 2], vic: true, cont: [3, 4], hall: [5, 6], n: 1, homeTries: 0 }).act === "tapX", "弹窗 X 最高优先");
ok(nav.decideHallFrame({ x: null, vic: true, def: false, cont: [3, 4], hall: [5, 6], n: 1, homeTries: 0 }).act === "firstTap", "VICTORY 走右槽先手");
ok(JSON.stringify(nav.decideHallFrame({ x: null, vic: false, def: true, cont: [3, 4], hall: [5, 6], n: 1, homeTries: 0 }).xy) === FT, "DEFEAT 同样右槽先手");
ok(nav.decideHallFrame({ x: null, vic: false, def: false, cont: [3, 4], hall: [5, 6], n: 1, homeTries: 0 }).act === "tapContinue", "CONTINUE 次之");
ok(nav.decideHallFrame({ x: null, vic: false, def: false, cont: null, hall: [5, 6], n: 1, homeTries: 0 }).act === "hall", "大厅就绪");
ok(nav.decideHallFrame({ x: null, vic: false, def: false, cont: null, hall: null, n: 4, homeTries: 1 }).act === "home", "无识别偶发回家");
ok(nav.decideHallFrame({ x: null, vic: false, def: false, cont: null, hall: null, n: 4, homeTries: 3 }).act === "wait", "回家 3 次后只等待");
ok(nav.decideHallFrame({ x: null, vic: false, def: false, cont: null, hall: null, n: 1, homeTries: 0 }).act === "wait", "非第 4 帧不回家");

/* planCard — 轮播处置 */
ok(nav.planCard({ play: [1, 2], sat: 207, swipes: 0 }).act === "play", "可用 PLAY → 点");
ok(nav.planCard({ play: [1, 2], sat: 30, swipes: 0 }).act === "swipe", "置灰 PLAY → 翻卡跳过");
ok(nav.planCard({ play: null, sat: null, swipes: 0 }).act === "swipe", "无 PLAY → 翻卡");
ok(nav.planCard({ play: null, sat: null, swipes: nav.config.MAX_SWIPES }).act === "fail", "翻够上限 → 失败");
ok(nav.planCard({ play: [1, 2], sat: 30, swipes: nav.config.MAX_SWIPES }).act === "fail", "置灰也受上限约束 (防死转)");

/* ---------- 1.5 pf_store 场次存储 ---------- */
var store = require(path.join(__dirname, "pf_store.js"));
section("pf_store 场次存储");

function memIO(raw) {
    return { read: function () { return raw; }, write: function (t) { raw = t; } };
}
var T0 = new Date(2026, 8, 8, 12, 0, 0).getTime();    // 2026-09-08 白天
var T1 = new Date(2026, 8, 8, 23, 59, 0).getTime();
var T2 = new Date(2026, 8, 9, 0, 1, 0).getTime();     // 跨 0 点
ok(store.todayStr(T0) === "2026-09-08" && store.todayStr(T1) === "2026-09-08"
    && store.todayStr(T2) === "2026-09-09", "todayStr 日历天 + 0 点滚动");

var s = store.loadStore(memIO(null), T0);
ok(s.rounds === 0 && s.day === "2026-09-08", "无文件 → 空记录归今天");
s = store.loadStore(memIO('{"day":"2026-09-08","rounds":12,"wins":7,"loses":5}'), T1);
ok(s.rounds === 12 && s.wins === 7 && s.loses === 5, "同日读回保留");
s = store.loadStore(memIO('{"day":"2026-09-07","rounds":12,"wins":7,"loses":5}'), T0);
ok(s.rounds === 0 && s.day === "2026-09-08", "昨日记录 → 跨天归零");
s = store.loadStore(memIO('{"day":broken!'), T0);
ok(s.rounds === 0 && s.day === "2026-09-08", "损坏 JSON → 静默重置");
s = store.loadStore(memIO('{"day":"2026-09-08","rounds":"x","wins":null}'), T0);
ok(s.rounds === 0 && s.wins === 0, "非法字段 → 0");

s = store.addRound({ day: "2026-09-08", rounds: 11, wins: 7, loses: 4 }, true, T1);
ok(s.rounds === 12 && s.wins === 8, "addRound 计胜");
s = store.addRound({ day: "2026-09-08", rounds: 11, wins: 7, loses: 4 }, false, T2);
ok(s.rounds === 1 && s.loses === 1 && s.wins === 0 && s.day === "2026-09-09",
    "addRound 跨 0 点先归零再计");

ok(store.capReached({ rounds: 99 }, 0) === false, "上限 0 = 不限");
ok(store.capReached({ rounds: 30 }, 30) === true, "到上限");
ok(store.capReached({ rounds: 29 }, 30) === false, "未到上限");

var io = memIO(null);
s = store.loadStore(io, T0);
store.addRound(s, true, T0); store.addRound(s, true, T0); store.addRound(s, false, T0);
store.saveStore(io, s);
var s2 = store.loadStore(io, T0);
ok(s2.rounds === 3 && s2.wins === 2 && s2.loses === 1, "save→load 存取闭环");

/* ---------- 1.6 pf_select 编队换人 ---------- */
var sel = require(path.join(__dirname, "pf_select.js"));
section("pf_select 编队换人");

/* isBolt: 钉芯黄 (255,219,66) 过 / 金卡淡黄底 (231,231,132) 不过 (pf_vision 同判据) */
ok(sel.isBolt(0xFFDB42) === true, "钉芯黄通过");
ok(sel.isBolt(0xFFE7E784) === false, "金卡淡黄底不通过");
ok(sel.isBolt(0xFFDFD966) === false, "r<240 拒绝");

/* 游程计数: 合成钉条带 (带宽 400x20) */
function bandPx(pins) {
    return function (x, y) {
        for (var i = 0; i < pins.length; i++)
            if (x >= pins[i][0] && x < pins[i][1]) return 0xFFDB42;
        return 0xFF808080;
    };
}
var px = bandPx([[10, 18], [40, 46], [70, 71], [100, 112]]);
ok(sel.countBolts(px, 0, 20, 200, 200) === 2, "游程计数: 8/6 计入, 1/12 剔除");
var px9 = bandPx([[10, 19]]);
ok(sel.countBolts(px9, 0, 20, 100, 100) === 1, "run 9 默认上限内 (候选口径)");
ok(sel.countBolts(px9, 0, 20, 100, 100, { runMax: 8 }) === 0, "run 9 > 8 剔除 (槽位口径)");
ok(sel.countBolts(bandPx([]), 0, 20, 100, 100) === 0, "空带 = 0 (maxFrac 门)");

/* 几何装配: PC 基准钉条行带 + 等距中心 */
function canvasPx(paint) {
    return function (x, y) {
        for (var i = 0; i < paint.length; i++) {
            var b = paint[i];
            if (y >= b.y0 && y < b.y1) {
                for (var j = 0; j < b.pins.length; j++)
                    if (x >= b.pins[j][0] && x < b.pins[j][1]) return 0xFFDB42;
            }
        }
        return 0xFF808080;
    };
}
var pxS = canvasPx([
    { y0: 349, y1: 369, pins: [[60, 68], [74, 82], [88, 96], [102, 110]] },
    { y0: 349, y1: 369, pins: [[280, 288], [294, 302]] },
    { y0: 349, y1: 369, pins: [] }
]);
var slots = sel.readSlots(pxS);
ok(slots[0] === 4 && slots[1] === 2 && slots[2] === 0, "槽位钉条几何读数 [4,2,0]");
var pxR = canvasPx([
    { y0: 679, y1: 701, pins: [[96, 104], [110, 118], [124, 132], [138, 146]] },
    { y0: 679, y1: 701, pins: [[300, 308], [314, 322], [328, 336]] },
    { y0: 679, y1: 701, pins: [] },
    { y0: 679, y1: 701, pins: [] },
    { y0: 679, y1: 701, pins: [] },
    { y0: 679, y1: 701, pins: [] }
]);
var roster = sel.readRoster(pxR);
ok(roster[0] === 4 && roster[1] === 3 && roster[5] === 0, "候选钉条几何读数 [4,3,0,0,0,0]");

/* 规则色判定 (FIGHT 橙/灰) */
ok(sel.ruleLit(function () { return 0xFFFF7830; }, 0, 0, 100, 100) === 1, "全橙 → 占比 1");
ok(sel.ruleLit(function () { return 0xFF808080; }, 0, 0, 100, 100) === 0, "全灰 → 占比 0");
var pxHalf = function (x, y) { return x < 50 ? 0xFFFF7830 : 0xFF808080; };
ok(Math.abs(sel.ruleLit(pxHalf, 0, 0, 100, 100) - 0.5) < 0.01, "半橙区域占比 0.5");

/* 编队修正状态机 (fix_team 同语义) */
function mkSt(extra) {
    var st = { cost: 4, pages: 0, slotFails: {}, ruleSlot: null, needReset: false };
    for (var k in (extra || {})) st[k] = extra[k];
    return st;
}
ok(sel.planFixTeam([4, 5, 6], [4, 4, 4], mkSt()).act === "done", "全达标 → done");
var a = sel.planFixTeam([2, 5, 4], [3, 4, 6], mkSt());
ok(a.act === "drag" && a.src === 1 && a.slot === 0 && a.ox === 0 && a.oy === 0,
    "战力优先取首个达标候选, 替换首个不足槽");
a = sel.planFixTeam([5, 2, 4], [2, 2, 2], mkSt());
ok(a.act === "page" && a.pages === 1, "候选无达标 → 翻页");
var stP = mkSt();
for (var pi = 0; pi < 30; pi++) a = sel.planFixTeam([5, 2, 4], [2, 2, 2], stP);
ok(a.act === "page" && a.pages === 30, "翻页计到上限 30");
ok(sel.planFixTeam([5, 2, 4], [2, 2, 2], stP).act === "fail", "翻页超限 → fail");
var stR = mkSt({ ruleSlot: 0 });
a = sel.planFixTeam([2, 5, 4], [4, 4, 4], stR);
ok(a.act === "refillRule" && a.slot === 0, "规则槽不足最优先");
a = sel.planFixTeam([4, 2, 4], [4, 4, 4], stR);
ok(a.act === "drag" && a.slot === 1, "规则槽达标后做普通槽");

/* 失败链: 偏移逐档递进并钳到末档, 每次失败后先归零候选列 */
function dragChain() {
    var st = mkSt(), acts = [];
    for (var i = 0; i < 12; i++) {
        var r = sel.planFixTeam([1, 5, 5], [6, 6, 6], st);
        acts.push(r.act + (r.act === "drag" ? "(" + r.ox + "," + r.oy + ")" : ""));
        if (r.act === "drag") sel.noteDragFail(st, r.slot);   // 模拟一直拖不上
    }
    return acts;
}
var acts = dragChain();
ok(acts[0] === "drag(0,0)" && acts[1] === "resetScroll" && acts[2] === "drag(14,6)"
    && acts[3] === "resetScroll" && acts[4] === "drag(-14,10)" && acts[5] === "resetScroll"
    && acts[6] === "drag(0,18)" && acts[7] === "resetScroll" && acts[8] === "drag(0,18)",
    "失败链: 偏移逐档递进钳到末档, 每败先归零候选列");

/* ---------- AutoJs6 全局 stub (2/3/4 节共用) ---------- */
var cmds = [];            // shizuku 指令流
var threadsStarted = [];  // 起过的线程函数 (不真跑)
global.files = {
    cwd: function () { return "/mock"; },
    exists: function () { return true; },
    createWithDirs: noop, remove: noop, copy: noop
};
global.images = {
    read: function () { return fakeImg(2560, 1152); },
    resize: function () { return fakeImg(1280, 576); },
    findImage: function (work, tpl) {
        if (tpl === EV_PLAY_TPL) return findQueue.length ? findQueue.shift() : null;
        return null;
    },
    pixel: function () { return 0xFFFF7830; }   // 默认橙
};
global.shizuku = function (cmd) { cmds.push(String(cmd)); return { result: "ok" }; };
global.sleep = noop;
global.ui = { run: function (f) { f(); } };
global.threads = { start: function (f) { threadsStarted.push(f); return {}; } };
global.toast = noop;
global.log = noop;
global.device = { width: 1080, height: 2400 };
global.app = { launchPackage: function () { return true; } };
global.setInterval = noop;

function fakeImg(w, h) {
    return { getWidth: function () { return w; }, getHeight: function () { return h; }, recycle: noop };
}
var EV_PLAY_TPL = { getWidth: function () { return 150; }, getHeight: function () { return 46; } };
var findQueue = [];

/* ---------- 2. pickAndPlay 仿真 (帧 2560x1152 → scale 2) ---------- */
section("pickAndPlay 仿真");

/* 场景 A: 第 1 帧无 PLAY → 左滑; 第 2 帧橙 PLAY@(500,300) → 点按钮中心 */
findQueue = [null, { x: 500, y: 300 }];
global.images.pixel = function () { return 0xFFFF7830; };
cmds = [];
nav.pickAndPlay(noop, { ev_play: EV_PLAY_TPL }, function () { return false; });
var swipesA = cmds.filter(function (c) { return c.indexOf("input swipe") === 0; }).length;
var tapsA = cmds.filter(function (c) { return c.indexOf("input tap") === 0; });
ok(swipesA === 1, "无 PLAY 翻卡一次");
ok(tapsA[tapsA.length - 1] === "input tap 1150 646", "点 PLAY! 中心并换算实机坐标 ((500+75)*2, (300+23)*2)");

/* 场景 B: 每帧都是置灰 PLAY → 翻卡至上限后报错, 不死转 */
findQueue = [];
for (var i = 0; i < 25; i++) findQueue.push({ x: 500, y: 300 });
global.images.pixel = function () { return 0xFF808080; };
cmds = [];
var threw = null;
try { nav.pickAndPlay(noop, { ev_play: EV_PLAY_TPL }, function () { return false; }); }
catch (e) { threw = e; }
ok(!!threw && String(threw.message || threw).indexOf("没有可用的 PLAY") >= 0, "全灰卡翻够上限报错");
var swipesB = cmds.filter(function (c) { return c.indexOf("input swipe") === 0; }).length;
ok(swipesB === nav.config.MAX_SWIPES, "翻卡次数正好等于上限 (" + nav.config.MAX_SWIPES + ")");

/* ---------- 3. runNav 控制流 ---------- */
section("runNav 控制流");
global.files.exists = function () { return false; };   // 缺 hall_events/ev_play
var logs = [];
var r = nav.runNav(function (m) { logs.push(m); }, function () { return true; });
ok(r === false, "缺模板 + 立即停止 → 返回 false (不交棒)");
ok(logs.join("\n").indexOf("采集模式") >= 0, "缺模板日志提示自动转采集");
global.files.exists = function () { return true; };

/* ---------- 3.5 模板装载 ---------- */
section("模板装载");
var ctx = nav.loadNavTemplates();
ok(!!ctx.tpls.victory && !!ctx.tpls.defeat && !!ctx.tpls.btn_continue,
    "逃逸链结算模板已装载 (waitHall 依赖, regression)");
ok(!!ctx.tpls.hall_events && !!ctx.tpls.ev_play, "导航必需模板已装载");
ok(ctx.haveSpd === true, "速度三模板齐 → 允许动脑子/速度泡");
ok(nav.missingRequired().length === 0, "模板齐 → missingRequired 为空");
global.files.exists = function () { return false; };
ok(nav.missingRequired().length === 5, "全缺 → 报 5 个必需模板");
global.files.exists = function () { return true; };

/* ---------- 4. settle_bot.js 装载冒烟 ---------- */
section("settle_bot 装载冒烟");
var handlers = {};
var xmlSeen = "";
global.floaty = {
    window: function (xml) {
        xmlSeen = xml;
        return {
            root: { post: function (f) { f(); }, setOnTouchListener: noop, getWidth: function () { return 322; } },
            btnStart: { on: function (e, fn) { handlers.start = fn; } },
            btnNav: { on: function (e, fn) { handlers.nav = fn; } },
            btnStop: { on: function (e, fn) { handlers.stop = fn; } },
            tvStat: { setText: noop }
        };
    }
};
require(path.join(__dirname, "settle_bot.js"));
ok(xmlSeen.indexOf("btnNav") >= 0, "悬浮窗 XML 含导航按钮");
ok(typeof handlers.start === "function" && typeof handlers.nav === "function"
    && typeof handlers.stop === "function", "开始/导航/停止 handler 全部挂上");

handlers.nav();          // 走 startNav: Shizuku 检查 → dailyLoad → require pf_nav → 起线程 (stub 不真跑)
ok(threadsStarted.length === 1, "导航按钮起工作线程 (require pf_nav 通路)");
handlers.stop();         // stopBot 收场
ok(threadsStarted.length === 1, "停止不另起线程");

handlers.start();        // startBot: Shizuku stub 过 → dailyLoad (pf_store 通路) → 起线程
ok(threadsStarted.length === 2, "开始按钮起工作线程 (pf_store 装载通路)");
handlers.stop();

/* ---------- 汇总 ---------- */
console.log("\n== 结果: " + passed + " 通过, " + failures + " 失败");
process.exit(failures ? 1 : 0);
