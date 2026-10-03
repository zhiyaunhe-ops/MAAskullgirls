/*
 * SGM 结算循环 bot — AutoJs6 版 (悬浮窗 + Shizuku 执行)
 *
 * 流程: 战斗中只查 VICTORY!/DEFEAT! 大字 → 命中即计场, 并先手点击右槽固定位置
 *       (胜局该格是 CONTINUE → 略过结算进奖励页; 败局该格恰好是 REMATCH → 直接再战,
 *        两种情况都不需要按钮匹配) → 随后只在按钮三槽查 REMATCH/CONTINUE 并点击。
 * 免 Root / 免无障碍 / 免录屏弹窗: 截屏 (screencap) 与点击 (input tap) 全走 Shizuku shell。
 * 模板与阈值与 phone/settle_bot.py 同源 (基准 1280x576, 帧自动缩放匹配)。
 *
 * 「导航」按钮: 调同目录 pf_nav.js 自动走 启动游戏→大厅→EVENTS→划到目标活动卡
 * (Pillow Talk, ev_target 模板) PLAY!→最右战斗节点→PLAY→AUTO/3x 自检, 完成后无缝接
 * 本结算循环 (缺模板会自动转采集模式)。
 *
 * 悬浮条: 运行中随时可拖动; 松手时靠近屏幕边缘会自动缩进, 只留一小条, 拖出即恢复。
 * 场次: 今日场数/胜负持久化到 store.json (pf_store.js), DAILY_CAP 可设每日上限, 到限自动停。
 *
 * 运行前提:
 *   1. Shizuku 服务已启动 (无线调试激活), AutoJs6 侧栏抽屉已开启 Shizuku 权限开关
 *   2. AutoJs6 已授予存储、悬浮窗权限 (首次运行有引导)
 *   3. templates/ 目录与本脚本同目录
 *   4. 开发者选项开启「USB 调试(安全设置)」, 否则 input 注入无效 (指令成功但屏幕无反应)
 */

/* ---------- 配置 ---------- */
var WORK_H = 576;            // 模板基准高 (素材源分辨率), 帧先缩放到此高度再匹配
var MIN_SIM = 0.72;          // findImage 相似度阈值 (误点调高 / 漏识别调低)
var BATTLE_MS = 800;         // 战斗阶段轮询间隔 (只查 2 个大字, 战斗本身耗时)
var RESULT_MS = 350;         // 结算/奖励阶段轮询间隔 (要快速点按钮)
var TAP_DELAY_MS = 1200;     // 点击后过场动画等待
var STALL_SEC = 30;          // 连续无识别告警阈值
var RESULT_FALLBACK = 3;     // 结算阶段连续 N 帧无按钮命中 → 判定已入战斗, 回大字检测
var DAILY_CAP = 0;           // 今日场数上限 (0=不限): 判完这局到限自动停; 0 点日历天自动归零

var TPL_DIR = files.cwd() + "/templates/";
var SHOT_DIR = "/sdcard/sgm_settle/";
var SHOT_PATH = SHOT_DIR + "frame.png";   // shell(uid 2000) 与 app 都可读写的位置
var STORE_PATH = SHOT_DIR + "store.json"; // 今日场次持久化文件 (pf_store.js 读写)

/* ROI 为 [x, y, w, h], 1280x576 基准 (标定见 phone/assets/settle/make_templates.py) */
var ROI_TITLE = [400, 20, 480, 100];     // VICTORY!/DEFEAT! 大字区
var BTN_ROIS = [                          // 按钮三槽位 (结算/奖励页固定)
    [265, 478, 220, 68],                  //   左槽
    [530, 478, 220, 68],                  //   中槽
    [793, 478, 220, 68]                   //   右槽
];
var FIRST_TAP = [903, 512];               // 右槽中心: 胜局=CONTINUE / 败局=REMATCH, 先手免匹配
var TPL_NAMES = ["victory", "defeat", "btn_rematch", "btn_continue"];

/* ---------- 运行状态 ---------- */
var running = false;
var worker = null;
var runStart = 0;
var stat = { wins: 0, loses: 0, rematches: 0, continues: 0, seen: false,
             lastAct: 0, phase: 0, noHitRun: 0 };

/* ---------- 今日场次 (数据层 pf_store.js, 这里注入手机 files 作存储后端) ---------- */

var _pfStore;    // 惰性 require: 缺文件不挡结算循环, 只是今日计数不持久化/无上限
function pfStoreLib() {
    if (_pfStore === undefined) {
        try { _pfStore = require("./pf_store.js"); }
        catch (e) { _pfStore = null; log("[!!] 缺 pf_store.js — 今日场次不持久化/无上限"); }
    }
    return _pfStore;
}
var daily = { day: "", rounds: 0, wins: 0, loses: 0 };

function dailyIO() {
    return {
        read: function () { return files.exists(STORE_PATH) ? files.read(STORE_PATH) : null; },
        write: function (txt) { files.createWithDirs(STORE_PATH); files.write(STORE_PATH, txt); }
    };
}
function dailyLoad() {
    var lib = pfStoreLib();
    if (lib) daily = lib.loadStore(dailyIO());
    return daily;
}
function dailyCount(win) {
    var lib = pfStoreLib();
    if (!lib) return;
    lib.addRound(daily, win);
    lib.saveStore(dailyIO(), daily);      // 每场落盘, 崩溃/重启不丢今日计数
}
function dailyCapHit() {
    var lib = pfStoreLib();
    return !!(lib && lib.capReached(daily, DAILY_CAP));
}
function dailyText() {
    return "今日" + daily.rounds + (DAILY_CAP > 0 ? "/" + DAILY_CAP : "") + "场";
}

/* ---------- 基础封装 ---------- */

function checkShizuku() {
    try {
        var r = shizuku("echo ok");
        if (r && String(r.result).indexOf("ok") >= 0) return true;
    } catch (e) { /* Shizuku 模块未开或服务未启动 */ }
    return false;
}

function loadTemplates() {
    var m = {};
    for (var i = 0; i < TPL_NAMES.length; i++) {
        var p = TPL_DIR + TPL_NAMES[i] + ".png";
        var img = images.read(p);
        if (!img) throw new Error("缺模板: " + p);
        m[TPL_NAMES[i]] = img;
    }
    return m;
}

/* 三槽位里找按钮。findImage 返回命中左上角, 换算成中心坐标 (1280x576 系)。 */
function findAnySlot(work, tpl) {
    for (var i = 0; i < BTN_ROIS.length; i++) {
        var p = images.findImage(work, tpl, { region: BTN_ROIS[i], threshold: MIN_SIM });
        if (p) return { x: p.x + tpl.getWidth() / 2, y: p.y + tpl.getHeight() / 2 };
    }
    return null;
}

function fmtDur(ms) {
    var s = Math.floor(ms / 1000), m = Math.floor(s / 60) % 60, h = Math.floor(s / 3600);
    s = s % 60;
    var mm = (m < 10 ? "0" : "") + m, ss = (s < 10 ? "0" : "") + s;
    return h > 0 ? h + ":" + mm + ":" + ss : mm + ":" + ss;
}

function setStat(prefix) {
    ui.run(function () {
        w.tvStat.setText(prefix + " " + fmtDur(Date.now() - runStart)
            + " " + dailyText() + " " + stat.wins + "胜" + stat.loses + "负");
    });
}

/* ---------- 悬浮窗 ---------- */

var w = floaty.window(
    '<frame id="root" bg="#CC1E1E1E" padding="6">'
    + '  <horizontal>'
    + '    <button id="btnStart" text="开始" w="56" h="42" marginRight="4" textSize="13sp"/>'
    + '    <button id="btnNav" text="导航" w="56" h="42" marginRight="4" textSize="13sp"/>'
    + '    <button id="btnStop" text="停止" w="56" h="42" marginRight="6" textSize="13sp"/>'
    + '    <text id="tvStat" text="待机" w="224" h="42" textSize="13sp" textColor="#FFFFFF" gravity="center"/>'
    + '  </horizontal>'
    + '</frame>'
);

/* 拖动 + 贴边缩进: 运行中随时可拖; 松手时距屏幕左/右缘 70px 内自动缩进, 只留一小条 */
(function () {
    var PEEK = 26;    // 缩进后露出像素
    var SNAP = 70;    // 距边缘多近触发吸附
    var winW = 0, dx = 0, dy = 0, wx = 0, wy = 0;
    w.root.post(function () { winW = w.root.getWidth(); });

    function snapEdge() {
        if (winW <= 0) return;
        var x = w.getX(), y = w.getY(), sw = device.width;
        if (x + winW >= sw - SNAP) w.setPosition(sw - PEEK, y);          // 吸右, 露出左缘
        else if (x <= SNAP) w.setPosition(-(winW - PEEK), y);            // 吸左, 露出右缘
    }

    w.root.setOnTouchListener(function (view, event) {
        if (event.getAction() === event.ACTION_DOWN) {
            dx = event.getRawX(); dy = event.getRawY();
            wx = w.getX(); wy = w.getY();
        } else if (event.getAction() === event.ACTION_MOVE) {
            w.setPosition(wx + (event.getRawX() - dx), wy + (event.getRawY() - dy));
        } else if (event.getAction() === event.ACTION_UP) {
            snapEdge();
        }
        return true;   // 必须消费事件: 返回 false 时 DOWN 之后不再收到 MOVE/UP, 拖不动
    });
})();

/* ---------- 主循环 (后台线程, 两阶段状态机) ---------- */

function loop() {
    var tpl = loadTemplates();
    files.createWithDirs(SHOT_PATH);
    runStart = Date.now();
    stat.lastAct = Date.now();
    setStat("运行");

    while (running) {
        shizuku("screencap -p " + SHOT_PATH);          // shell 截屏, 免录屏弹窗
        var frame = images.read(SHOT_PATH);
        if (!frame) { sleep(800); continue; }

        var scale = frame.getHeight() / WORK_H;
        var work = frame;
        if (Math.abs(scale - 1) > 0.001) {
            work = images.resize(frame, [Math.round(frame.getWidth() / scale), WORK_H]);
        }

        if (stat.phase === 0) {
            /* —— 战斗阶段: 只查两个大字 (便宜), 命中才进结算阶段 —— */
            var vic = images.findImage(work, tpl.victory, { region: ROI_TITLE, threshold: MIN_SIM });
            var def = images.findImage(work, tpl.defeat, { region: ROI_TITLE, threshold: MIN_SIM });
            if (!vic && !def) {
                stat.seen = false;                      // 无大字 = 战斗/过场, 允许下一场再计
            } else if (!stat.seen) {
                stat.seen = true;
                stat.phase = 1;
                stat.noHitRun = 0;
                dailyCount(!!vic);
                if (vic) { stat.wins++; log("[场] VICTORY (共 " + stat.wins + " 胜 " + stat.loses + " 负)"); }
                else { stat.loses++; log("[场] DEFEAT (共 " + stat.wins + " 胜 " + stat.loses + " 负)"); }

                if (dailyCapHit()) {
                    log("[停] " + dailyText() + " 已达每日上限 — 停止, 留在结算页 (改 settle_bot.js 顶部 DAILY_CAP 调整)");
                    toast("今日上限 " + DAILY_CAP + " 场已到, 自动停止");
                    running = false;                 // 不再点任何按钮 (安全), 线程 finally 收尾
                    break;
                }

                /* 先手点右槽固定位置, 免按钮匹配:
                   胜局该格是 CONTINUE (略过结算) / 败局该格是 REMATCH (直接再战) */
                var px = Math.round(FIRST_TAP[0] * scale), py = Math.round(FIRST_TAP[1] * scale);
                shizuku("input tap " + px + " " + py);
                stat.lastAct = Date.now();
                log("[动] 先手 (" + (vic ? "CONTINUE" : "REMATCH") + ") → (" + px + "," + py + ")");
                setStat("运行");
                sleep(TAP_DELAY_MS);
            }
        } else {
            /* —— 结算/奖励阶段: 只查 REMATCH 和 CONTINUE (各三槽) —— */
            var rm = findAnySlot(work, tpl.btn_rematch);
            if (rm) {
                var px2 = Math.round(rm.x * scale), py2 = Math.round(rm.y * scale);
                shizuku("input tap " + px2 + " " + py2);
                stat.rematches++;
                stat.lastAct = Date.now();
                stat.noHitRun = 0;
                stat.phase = 0;                          // REMATCH = 下一场开始, 回大字检测
                log("[动] REMATCH (第 " + stat.rematches + " 场) → (" + px2 + "," + py2 + ")");
                setStat("运行");
                sleep(TAP_DELAY_MS);
            } else {
                var ct = findAnySlot(work, tpl.btn_continue);
                if (ct) {
                    var px3 = Math.round(ct.x * scale), py3 = Math.round(ct.y * scale);
                    shizuku("input tap " + px3 + " " + py3);
                    stat.continues++;
                    stat.lastAct = Date.now();
                    stat.noHitRun = 0;                   // 保持结算阶段 (可能还有下一页 CONTINUE)
                    log("[动] CONTINUE 略过 → (" + px3 + "," + py3 + ")");
                    setStat("运行");
                    sleep(TAP_DELAY_MS);
                } else {
                    stat.noHitRun++;
                    if (stat.noHitRun >= RESULT_FALLBACK) {
                        stat.phase = 0;                  // 连续无按钮 → 已入战斗, 回大字检测
                        log("[转] 进入战斗监测");
                    }
                }
            }
        }

        if (Date.now() - stat.lastAct > STALL_SEC * 1000) {
            log("[!!] " + STALL_SEC + "s 无识别 — 断线/非常规弹窗? 请人工查看");
            stat.lastAct = Date.now();
        }

        if (work !== frame) work.recycle();
        frame.recycle();
        sleep(stat.phase === 0 ? BATTLE_MS : RESULT_MS);
    }
    for (var k in tpl) tpl[k].recycle();
}

function startBot() {
    if (running) { toast("已在运行"); return; }
    if (!checkShizuku()) {
        toast("Shizuku 未连接: 确认 Shizuku 服务已启动, 且 AutoJs6 侧栏开启 Shizuku 开关");
        return;
    }
    dailyLoad();
    if (dailyCapHit()) {
        toast(dailyText() + " 已达上限 — 不开跑 (明日自动归零, 或改 settle_bot.js 顶部 DAILY_CAP)");
        return;
    }
    running = true;
    stat = { wins: 0, loses: 0, rematches: 0, continues: 0, seen: false,
             lastAct: 0, phase: 0, noHitRun: 0 };
    worker = threads.start(function () {
        try {
            loop();
        } catch (e) {
            log("[错] " + e);
            toast("脚本异常: " + e + " (看日志页)");
        } finally {
            running = false;
            ui.run(function () { w.tvStat.setText("已停止"); });
        }
    });
}

function stopBot() {
    running = false;
    var dur = runStart ? fmtDur(Date.now() - runStart) : "0:00";
    toast("统计: " + dur + " | " + dailyText() + " | 本次 " + stat.rematches
        + " 场 " + stat.wins + "胜" + stat.loses + "负 | CONTINUE×" + stat.continues);
}

/* 导航: pf_nav.js 走完 启动→大厅→EVENTS→角色场→FIGHT 后, 在同一线程直接接结算循环 */
function startNav() {
    if (running) { toast("已在运行"); return; }
    if (!checkShizuku()) {
        toast("Shizuku 未连接: 确认 Shizuku 服务已启动, 且 AutoJs6 侧栏开启 Shizuku 开关");
        return;
    }
    dailyLoad();
    if (dailyCapHit()) {
        toast(dailyText() + " 已达上限 — 不导航 (明日自动归零, 或改 settle_bot.js 顶部 DAILY_CAP)");
        return;
    }
    var nav;
    try {
        nav = require("./pf_nav.js");
    } catch (e) {
        toast("缺 pf_nav.js: 要与 settle_bot.js 放同一目录");
        log("[错] require pf_nav 失败: " + e);
        return;
    }
    running = true;
    runStart = Date.now();
    stat = { wins: 0, loses: 0, rematches: 0, continues: 0, seen: false,
             lastAct: 0, phase: 0, noHitRun: 0 };
    setStat("导航");
    worker = threads.start(function () {
        try {
            var ok = nav.runNav(function (msg) { log(msg); },
                function () { return !running; });
            if (ok) {
                log("[航] —— 交棒结算循环 ——");
                loop();
            } else {
                log("[航] 导航未接结算循环 (停在安全点/采集完成, 看上方日志)");
            }
        } catch (e) {
            log("[错] 导航: " + e);
            toast("导航异常: " + e + " (看日志页)");
        } finally {
            running = false;
            ui.run(function () { w.tvStat.setText("已停止"); });
        }
    });
}

w.btnStart.on("click", startBot);
w.btnNav.on("click", startNav);
w.btnStop.on("click", stopBot);

/* ---------- 入口 ---------- */
toast("SGM 结算挂机: 开始=直接结算循环 / 导航=自动进一场再开跑 (拖动空白处移动, 靠边缩进)");
dailyLoad();
ui.run(function () { w.tvStat.setText("待机 " + dailyText()); });
if (!checkShizuku()) {
    log("[!!] Shizuku 未连接 — 仍可打开悬浮条, 但点「开始」前需先连上");
}
setInterval(function () { }, 10000);   // 保活: 脚本不退出悬浮窗才常驻
