/*
 * SGM 场次数存储 (AutoJs 版) — PC 版 pf_store 场次系统的手机简化:
 * 只做「今日场数/胜负持久化 + 每日上限」, 子场次/规则按钮组仍在 PC 侧。
 *
 * 数据层: 不碰任何 AutoJs6 全局, 存储后端由调用方注入 (io.read/io.write) —
 *   settle_bot.js 注入手机 files 读写, test_nav.js 注入内存对象, 两端同一份逻辑。
 * 存储内容: /sdcard/sgm_settle/store.json → {"day":"2026-09-08","rounds":12,"wins":7,"loses":5}
 *   日期按本机日历天 (0 点滚动); 读写失败一律静默降级, 不影响挂机。
 */

var EMPTY = { day: "", rounds: 0, wins: 0, loses: 0 };

/* 本机日历天 YYYY-MM-DD; ts (ms) 可选, 供测试注入 */
function todayStr(ts) {
    var d = (typeof ts === "number") ? new Date(ts) : new Date();
    var m = d.getMonth() + 1, day = d.getDate();
    return d.getFullYear() + "-" + (m < 10 ? "0" : "") + m
        + "-" + (day < 10 ? "0" : "") + day;
}

function loadStore(io, ts) {
    var s = { day: "", rounds: 0, wins: 0, loses: 0 };
    var today = todayStr(ts);
    try {
        var raw = io.read();
        if (raw) {
            var o = JSON.parse(String(raw));
            if (o && typeof o === "object") {
                s.day = String(o.day || "");
                s.rounds = parseInt(o.rounds, 10) || 0;
                s.wins = parseInt(o.wins, 10) || 0;
                s.loses = parseInt(o.loses, 10) || 0;
            }
        }
    } catch (e) { /* 缺失/损坏 → 空记录 */ }
    if (s.day !== today) s = { day: today, rounds: 0, wins: 0, loses: 0 };
    return s;
}

function saveStore(io, s) {
    try { io.write(JSON.stringify(s)); return true; } catch (e) { return false; }
}

/* 记一场; 运行跨过 0 点 → 先归零再计 (ts 仅供测试注入) */
function addRound(s, win, ts) {
    var today = todayStr(ts);
    if (s.day !== today) { s.day = today; s.rounds = 0; s.wins = 0; s.loses = 0; }
    s.rounds++;
    if (win) s.wins++; else s.loses++;
    return s;
}

/* cap<=0 = 不限 */
function capReached(s, cap) {
    return cap > 0 && s.rounds >= cap;
}

if (typeof module !== "undefined" && module.exports) {
    module.exports = {
        loadStore: loadStore,
        saveStore: saveStore,
        addRound: addRound,
        capReached: capReached,
        todayStr: todayStr
    };
}
