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
 * 悬浮条: 随时可拖动 (按住空白处); 「◀」折成贴边小箭头隐藏面板, 点箭头展开。
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
var BATTLE_STALL_SEC = 180;  // 战斗检测无结果上限: 超时走 PLAY 拉回, 连续 3 跳失败走导航自愈
var OK_OFFSET = [210, 188];  // srv_ok 模板左上 → OK 按钮中心 (576 系, 2026-10-06 用户截图标定)
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
var mode = "";                                // "nav"=导航接棒中 / "loop"=结算循环 (小状态用)
var folded = false;                           // 面板是否已折成小箭头
var tplX = null, tplM = null, tplS = null, tplE = null, tplF = null, tplO = null;   // X/RETRY/srv_ok/vs_fight/OK按钮 模板 (stall 逃生用)
var navBusy = false;                          // 自愈导航进行中 (stall 连发时防重复触发)
var _pfNav;                                   // 惰性 require: 缺 pf_nav.js 只跳过自愈, 不挡循环
var stat = { wins: 0, loses: 0, rematches: 0, continues: 0, seen: false,
             lastAct: 0, phase: 0, noHitRun: 0, lastVic: true, rounds: 0,
             battleT0: 0, battleStalls: 0 };

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

/* 一局完成计数 (只在点 REMATCH/败局先手时调, 每局恰一次);
 * 到上限不再点任何按钮, 留在结算页 (安全) */
function countRound(win) {
    dailyCount(win);
    stat.rounds = (stat.rounds || 0) + 1;
    if (win) stat.wins++; else stat.loses++;
    log("[计] 今日第 " + daily.rounds + " 场 (" + (win ? "胜" : "负") + ") 本次 "
        + stat.wins + "胜" + stat.loses + "负");
    if (dailyCapHit()) {
        log("[停] " + dailyText() + " 已达每日上限 — 停止 (改 settle_bot.js 顶部 DAILY_CAP 调整)");
        toast("今日上限 " + DAILY_CAP + " 场已到, 自动停止");
        running = false;
    }
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

/* 导航自愈 (断联兜底): 重跑整条导航链, 完成后回结算循环; 结算 stall 满 3 轮与
 * 战斗超时 3 跳共用这条失败路线 */
function selfHealNav() {
    navBusy = true;
    log("[救] 重跑导航自愈 (断联兜底)");
    try {
        if (!_pfNav) _pfNav = require("./pf_nav.js");
        var okNav = _pfNav.runNav(function (m) { log(m); },
            function () { return !running; });
        log(okNav ? "[救] 自愈导航完成, 回到王关继续循环"
            : "[救] 自愈导航未接棒 (停在安全点, 看日志)");
    } catch (e2) {
        log("[错] 自愈导航: " + e2);
    }
    navBusy = false;
}

function fmtDur(ms) {
    var s = Math.floor(ms / 1000), m = Math.floor(s / 60) % 60, h = Math.floor(s / 3600);
    s = s % 60;
    var mm = (m < 10 ? "0" : "") + m, ss = (s < 10 ? "0" : "") + s;
    return h > 0 ? h + ":" + mm + ":" + ss : mm + ":" + ss;
}


var statPrefix = "待机";
function setStat(prefix) {
    statPrefix = prefix;
    ui.run(function () { w.tvStat.setText(statText()); });
}
/* 面板全文: 前缀 时长 · 小状态 / 今日 / 本次+每分钟场次 / 胜负。
 * 保活定时器每 5s 重算一次, 时长与场/分随时走动 */
function statText() {
    var phase = "";
    if (running) {
        phase = " · " + (mode === "nav" ? "导航中" : navBusy ? "自愈中"
            : stat.phase === 1 ? "结算页" : "战斗监测");
    }
    var min = runStart ? (Date.now() - runStart) / 60000 : 0;
    var rpm = min > 0.5 ? (stat.rounds / min).toFixed(1) : "0.0";
    return statPrefix + " " + fmtDur(runStart ? Date.now() - runStart : 0) + phase
        + "\n" + dailyText()
        + "\n本次 " + stat.rounds + " 场 · " + rpm + " 场/分"
        + "\n" + stat.wins + "胜" + stat.loses + "负";
}

/* ---------- 悬浮窗 ---------- */

var w = floaty.window(
    '<frame id="root" bg="#CC1E1E1E" padding="6">'
    + '  <vertical id="panel">'
    + '    <horizontal>'
    + '      <button id="btnStart" text="开始" w="60" h="42" marginRight="4" textSize="12sp"/>'
    + '      <button id="btnNav" text="导航" w="60" h="42" marginRight="4" textSize="12sp"/>'
    + '      <button id="btnStop" text="停止" w="60" h="42" marginRight="4" textSize="12sp"/>'
    + '      <button id="btnEnd" text="结束" w="60" h="42" marginRight="4" textSize="12sp"/>'
    + '      <button id="btnFold" text="◀" w="40" h="42" textSize="12sp"/>'
    + '    </horizontal>'
    + '    <text id="tvStat" text="待机" w="296" h="132" textSize="13sp" textColor="#FFFFFF" gravity="center"/>'
    + '  </vertical>'
    + '  <button id="btnMini" text="◀" w="30" h="30" textSize="12sp" padding="0"/>'
    + '</frame>'
);

/* 拖动移动面板: 按住空白处拖, 松手拉回屏内 (旧的贴边缩进已删 — 隐藏走「◀」折箭头) */
(function () {
    var dx = 0, dy = 0, wx = 0, wy = 0;
    var miniMoved = false;

    /* 小箭头自带手势: btnMini 是 Button 会吃掉触摸, root 的拖动监听收不到。
     * 位移 <10px 视为点击 → 展开; 拖动松手 → 贴回近侧边缘 */
    w.btnMini.setOnTouchListener(function (view, event) {
        var act = event.getAction();
        if (act === event.ACTION_DOWN) {
            dx = event.getRawX(); dy = event.getRawY();
            wx = w.getX(); wy = w.getY();
            miniMoved = false;
        } else if (act === event.ACTION_MOVE) {
            if (!miniMoved && Math.abs(event.getRawX() - dx) < 10
                    && Math.abs(event.getRawY() - dy) < 10) return true;
            miniMoved = true;
            w.setPosition(Math.round(wx + event.getRawX() - dx),
                          Math.round(wy + event.getRawY() - dy));
        } else if (act === event.ACTION_UP) {
            if (miniMoved) { miniSnapX(); clampPos(); } else { setFold(false); }
        }
        return true;
    });

    w.root.setOnTouchListener(function (view, event) {
        if (event.getAction() === event.ACTION_DOWN) {
            dx = event.getRawX(); dy = event.getRawY();
            wx = w.getX(); wy = w.getY();
        } else if (event.getAction() === event.ACTION_MOVE) {
            w.setPosition(wx + (event.getRawX() - dx), wy + (event.getRawY() - dy));
        } else if (event.getAction() === event.ACTION_UP) {
            clampPos();      // 松手拉回屏内: 没有缩进逻辑后, 面板不应被拖丢在屏外
        }
        return true;   // 必须消费事件: 返回 false 时 DOWN 之后不再收到 MOVE/UP, 拖不动
    });
})();

/* ---------- 折叠: 面板 ↔ 小箭头 ---------- */

function clampPos() {
    /* 布局尺寸变化后把窗口拉回屏内 (折叠时窗口变小, 负坐标会让箭头整个消失在屏外) */
    w.root.post(function () {
        var ww = w.root.getWidth(), wh = w.root.getHeight();
        var x = w.getX(), y = w.getY(), sw = device.width, sh = device.height;
        if (x + ww > sw - 8) x = sw - ww - 8;
        if (x < 8) x = 8;
        if (y + wh > sh - 8) y = sh - wh - 8;
        if (y < 8) y = 8;
        w.setPosition(Math.round(x), Math.round(y));
    });
}

function miniSnapX() {
    /* 箭头贴到近侧左右缘 (y 不动), 箭头指向屏内; 展开态/拖动松手/转屏都复用 */
    var ww = w.root.getWidth(), sw = device.width;
    if (ww <= 0) return;
    var toLeft = w.getX() + ww / 2 < sw / 2;
    w.setPosition(Math.round(toLeft ? 8 : sw - ww - 8), Math.round(w.getY()));
    w.btnMini.setText(toLeft ? "▶" : "◀");
}

function setFold(on) {
    folded = on;
    w.panel.setVisibility(on ? android.view.View.GONE : android.view.View.VISIBLE);
    w.btnMini.setVisibility(on ? android.view.View.VISIBLE : android.view.View.GONE);
    if (on) {
        w.root.post(function () { miniSnapX(); clampPos(); });   // post: 等布局收完再取小窗宽度
    } else {
        w.btnMini.setText("◀");
        clampPos();
    }
}
w.btnMini.setVisibility(android.view.View.GONE);   // 初始只显示面板
w.btnFold.on("click", function () { setFold(true); });   // 箭头的点击/拖动在 btnMini 触摸监听里

/* ---------- 主循环 (后台线程, 两阶段状态机) ---------- */

function loop() {
    mode = "loop";                               // 导航接棒后状态行切到 战斗监测/结算页
    var tpl = loadTemplates();
    /* 弹窗模板可选装载 (stall 逃生用; 缺文件不挡循环) */
    if (tplX) { tplX.recycle(); tplX = null; }
    if (tplM) { tplM.recycle(); tplM = null; }
    if (tplS) { tplS.recycle(); tplS = null; }
    if (tplE) { tplE.recycle(); tplE = null; }
    if (tplF) { tplF.recycle(); tplF = null; }
    if (tplO) { tplO.recycle(); tplO = null; }
    try {
        tplX = files.exists(TPL_DIR + "scene_x.png") ? images.read(TPL_DIR + "scene_x.png") : null;
        tplM = files.exists(TPL_DIR + "modal_x.png") ? images.read(TPL_DIR + "modal_x.png") : null;
        tplS = files.exists(TPL_DIR + "srv_retry.png") ? images.read(TPL_DIR + "srv_retry.png") : null;
        tplE = files.exists(TPL_DIR + "srv_ok.png") ? images.read(TPL_DIR + "srv_ok.png") : null;
        tplF = files.exists(TPL_DIR + "vs_fight.png") ? images.read(TPL_DIR + "vs_fight.png") : null;
        tplO = files.exists(TPL_DIR + "ok_btn.png") ? images.read(TPL_DIR + "ok_btn.png") : null;
    } catch (e) { tplX = tplM = tplS = tplE = tplF = tplO = null; }
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
                /* 大字只更新上局胜负归属, 不在此计场 — 结算页两页都有 VICTORY 横幅,
                 * 过场动画会重置 seen 造成重复计数 (2026-10-05 实测虚高 20 倍);
                 * 计场统一挪到点 REMATCH / 败局先手那一刻 (一局只点一次) */
                stat.seen = true;
                stat.phase = 1;
                stat.noHitRun = 0;
                stat.battleStalls = 0;               // 见到大字 = 游戏活着, 战斗超时计数清零
                stat.lastVic = !!vic;
                log("[场] " + (vic ? "VICTORY" : "DEFEAT"));

                /* 先手点右槽固定位置, 免按钮匹配:
                   胜局该格是 CONTINUE (略过结算) / 败局该格是 REMATCH (直接再战) */
                var px = Math.round(FIRST_TAP[0] * scale), py = Math.round(FIRST_TAP[1] * scale);
                shizuku("input tap " + px + " " + py);
                stat.lastAct = Date.now();
                log("[动] 先手 (" + (vic ? "CONTINUE" : "REMATCH") + ") → (" + px + "," + py + ")");
                setStat("运行");
                if (!vic) countRound(false);     // 败局先手 = REMATCH 直接再战, 一局完成
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
                stat.battleT0 = Date.now();              // 下一场战斗计时起点 (超时链用)
                countRound(stat.lastVic);                // 一局完成, 在此计场 (每局恰一次)
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
                        stat.battleT0 = Date.now();
                        log("[转] 进入战斗监测");
                    }
                }
            }
        }

        if (Date.now() - stat.lastAct > STALL_SEC * 1000) {
            /* stall 逃生 (pf_scene 同款思路): 网络错误OK/通用OK按钮 → 服务器RETRY → 弹窗X
             * → VS页FIGHT! → 三槽按钮。
             * 战斗阶段卡死另有超时链: 超 BATTLE_STALL_SEC 无大字 → PLAY 拉回,
             * 连续 3 跳仍无结果 → 导航自愈 (2026-10-06 用户规则) */
            var escaped = false;
            var hitX = null, kindX = null;
            if (!hitX && tplE) {
                hitX = images.findImage(work, tplE, { threshold: 0.75 });
                kindX = "srv_ok";
            }
            if (!hitX && tplO) {
                /* OK 按钮通用兜底: 网络错误弹窗文案多变 (Beep.Boop / We're having...,
                 * 文字框模板只认一种), OK 按钮本体跨文案稳定 (2026-10-06 Error Code: 0 实例) */
                hitX = images.findImage(work, tplO, { region: [480, 300, 320, 180], threshold: 0.8 });
                kindX = "ok_btn";
            }
            if (!hitX && tplS) {
                hitX = images.findImage(work, tplS, { threshold: 0.75 });
                kindX = "srv_retry";
            }
            if (!hitX && tplX) {
                hitX = images.findImage(work, tplX, { region: [600, 0, 560, 180], threshold: 0.8 });
                kindX = "scene_x";
            }
            if (!hitX && tplM) {
                hitX = images.findImage(work, tplM, { region: [480, 0, 480, 280], threshold: 0.9 });
                kindX = "modal_x";
            }
            if (!hitX && tplF) {
                /* VS 对战页/地图页右上 FIGHT!: 直接开打 (pf_scene 认场景做动作同款思路) */
                hitX = images.findImage(work, tplF, { region: [1020, 0, 260, 130], threshold: 0.75 });
                kindX = "vs_fight";
            }
            if (hitX) {
                var offX, offY;
                if (kindX === "srv_ok") {
                    offX = OK_OFFSET[0]; offY = OK_OFFSET[1];   // OK 不在模板内: 标定偏移直点
                } else {
                    var tplHit = kindX === "scene_x" ? tplX
                        : kindX === "modal_x" ? tplM
                        : kindX === "vs_fight" ? tplF
                        : kindX === "ok_btn" ? tplO
                        : tplS;
                    offX = tplHit.getWidth() / 2; offY = tplHit.getHeight() / 2;
                }
                shizuku("input tap "
                    + Math.round((hitX.x + offX) * scale) + " "
                    + Math.round((hitX.y + offY) * scale));
                escaped = true;
                log("[弹] stall 关弹窗 (" + kindX + ") → ("
                    + Math.round(hitX.x + offX) + "," + Math.round(hitX.y + offY) + ")");
            } else if (tpl) {
                var rb = findAnySlot(work, tpl.btn_rematch);
                var cb = rb ? null : findAnySlot(work, tpl.btn_continue);
                var hb = rb || cb;
                if (hb) {
                    shizuku("input tap " + Math.round(hb.x * scale) + " " + Math.round(hb.y * scale));
                    escaped = true;
                    log("[弹] stall 点按钮 (" + (rb ? "REMATCH" : "CONTINUE") + ") → ("
                        + Math.round(hb.x) + "," + Math.round(hb.y) + ")");
                }
            }
            if (escaped) {
                stat.stallRuns = 0;
            } else if (stat.phase === 0) {
                /* 战斗阶段无大字 ≠ 卡死 (战斗本身耗时), 不走 stallRuns;
                 * 战斗计时从 battleT0 (点 REMATCH/入战斗) 起, 超时才逐跳拉回 */
                var overMs = Date.now() - (stat.battleT0 || stat.lastAct);
                if (overMs >= BATTLE_STALL_SEC * 1000) {
                    stat.battleStalls = (stat.battleStalls || 0) + 1;
                    log("[!!] 战斗 " + Math.round(overMs / 6000) / 10 + "min 无结果 (超时第 "
                        + stat.battleStalls + " 跳)" + (navBusy ? " — 自愈导航进行中" : ""));
                    if (stat.battleStalls >= 3 && !navBusy) {
                        selfHealNav();
                        stat.battleStalls = 0;
                        stat.battleT0 = Date.now();
                    } else if (!navBusy) {
                        /* PLAY 拉回: 居中卡区有可用 (彩色) PLAY! 才点, 没有就不动 */
                        try {
                            if (!_pfNav) _pfNav = require("./pf_nav.js");
                            var playHit = _pfNav.findPlay(work);
                            if (playHit) {
                                shizuku("input tap " + Math.round(playHit[0] * scale) + " "
                                    + Math.round(playHit[1] * scale));
                                log("[救] PLAY 拉回 → (" + Math.round(playHit[0]) + ","
                                    + Math.round(playHit[1]) + ") 等入战斗");
                            } else {
                                log("[救] PLAY 场景没找到 (不在活动卡页?) — 下一跳再试");
                            }
                        } catch (e3) {
                            log("[错] PLAY 拉回: " + e3);
                        }
                    }
                }
            } else {
                stat.stallRuns = (stat.stallRuns || 0) + 1;
                log("[!!] " + STALL_SEC + "s 无识别 (连续 " + stat.stallRuns + " 轮)"
                    + (navBusy ? " — 自愈导航进行中" : " — 断线/非常规弹窗?"));
                if (stat.stallRuns >= 3 && !navBusy) {
                    selfHealNav();
                    stat.stallRuns = 0;
                }
            }
            stat.lastAct = Date.now();
        }

        if (work !== frame) work.recycle();
        frame.recycle();
        sleep(stat.phase === 0 ? BATTLE_MS : RESULT_MS);
    }
    for (var k in tpl) tpl[k].recycle();
    if (tplX) { tplX.recycle(); tplX = null; }
    if (tplM) { tplM.recycle(); tplM = null; }
    if (tplS) { tplS.recycle(); tplS = null; }
    if (tplE) { tplE.recycle(); tplE = null; }
    if (tplF) { tplF.recycle(); tplF = null; }
    if (tplO) { tplO.recycle(); tplO = null; }
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
             lastAct: 0, phase: 0, noHitRun: 0, lastVic: true, rounds: 0,
             battleT0: 0, battleStalls: 0 };
    worker = threads.start(function () {
        try {
            loop();
        } catch (e) {
            log("[错] " + e);
            toast("脚本异常: " + e + " (看日志页)");
        } finally {
            running = false;
            mode = "";
            setStat("已停止");
        }
    });
}

function stopBot() {
    running = false;
    var dur = runStart ? fmtDur(Date.now() - runStart) : "0:00";
    toast("统计: " + dur + " | " + dailyText() + " | 本次 " + stat.rematches
        + " 场 " + stat.wins + "胜" + stat.loses + "负 | CONTINUE×" + stat.continues);
}

/* 结束 = 停止 + 退出整个脚本 (悬浮窗/保活定时器/worker 一并收掉; 停止只停循环, 窗口留着可再开) */
function endBot() {
    running = false;          // worker 循环与自愈导航的 abort 判据都吃这个标志
    stopBot();                // 复用统计 toast
    exit();                   // 引擎级退出: 悬浮窗随之消失
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
    mode = "nav";
    runStart = Date.now();
    stat = { wins: 0, loses: 0, rematches: 0, continues: 0, seen: false,
             lastAct: 0, phase: 0, noHitRun: 0, lastVic: true, rounds: 0,
             battleT0: 0, battleStalls: 0 };
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
            mode = "";
            setStat("已停止");
        }
    });
}

w.btnStart.on("click", startBot);
w.btnNav.on("click", startNav);
w.btnStop.on("click", stopBot);
w.btnEnd.on("click", endBot);

/* ---------- 入口 ---------- */
toast("SGM 结算挂机: 开始=直接结算循环 / 导航=自动进一场再开跑 / 停止=暂停可再开 / 结束=退出脚本"
    + " / ◀=折成小箭头 (拖动空白处移动)");
dailyLoad();
setStat("待机");
if (!checkShizuku()) {
    log("[!!] Shizuku 未连接 — 仍可打开悬浮条, 但点「开始」前需先连上");
}
var lastDims = "";                     // 转屏检测: 屏幕尺寸变了就把窗口拉回贴边
setInterval(function () {              // 保活 + 面板随时刷新 (时长/每分钟场次/小状态)
    var dims = device.width + "x" + device.height;
    if (lastDims && dims !== lastDims) {
        if (folded) { miniSnapX(); clampPos(); } else { clampPos(); }
    }
    lastDims = dims;
    if (running) setStat(statPrefix);
}, 5000);
