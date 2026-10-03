/*
 * SGM 导航模块 — AutoJs6 版 (由 settle_bot.js 的「导航」按钮 require 调用, 不单独运行)
 *
 * 流程: 启动游戏 → 等大厅 (促销弹窗 X / 结算残局逃逸) → 点 EVENTS 菱形 → 轮播划到
 *       目标活动卡 (Pillow Talk, ev_target 模板命中居中卡才点) → 点 PLAY! (绝不点
 *       SKIP!) → 按 FIGHT_STEPS 标定 划轨/选最右战斗节点/点 PLAY 开打 → 战斗开场
 *       AUTO+3x 自检 (同 pf_bot ensure_battle_auto) → 交棒 settle_bot 结算循环。
 *
 * 坐标基准 1280x576 (同 settle_bot: 帧缩放到高 576 再匹配, 点击按 scale 换算回实机)。
 *
 * 标定状态 (2026-10-03 改 Pillow Talk 目标链, 真机截图标定后启用):
 *   已标定  结算大字/三槽/右槽先手 (沿用 settle_bot 模板与 FIRST_TAP)
 *   待标定  hall_events / ev_play / ev_target 模板 (缺 → 导航自动转采集模式)
 *   待标定  FIGHT_STEPS (PLAY! 之后的 划轨/最右节点/PLAY, 真机截图后填)
 *   待标定  战斗开场脑子/速度泡坐标 (MuMu y*0.8 估算; 速度三模板没裁出来前不盲点)
 *
 * 安全规则: 模板/坐标未标定的界面一律不盲点 — 宁可停在安全点等人, 防止误触
 *   SKIP!/购买/消耗资源的入口。
 *
 * 纯决策函数 (playAvailable/planSpeed/decideHallFrame/planCard/satOfArgb/vOfArgb)
 * 不碰 AutoJs6 全局, 可被 test_nav.js 在 Node 里单测; I/O 全在 run* 函数内。
 */

/* ---------- 配置 (坐标均为 1280x576 基准) ---------- */

var GAME_PKG = "com.autumn.skullgirls";

var POLL_MS = 1200;          // 大厅/进入等待轮询间隔
var TAP_WAIT_MS = 1500;      // 一般点击后的过场等待
var SWIPE_WAIT_MS = 1800;    // 轮播翻卡等待 (吸附回弹)
var LAUNCH_TIMEOUT = 150;    // 冷启动到大厅秒数 (含 CONNECTING, 同 pf_scene)
var HUB_TIMEOUT = 40;        // 点 EVENTS 到进入活动轮播的秒数
var MAX_SWIPES = 18;         // 活动轮播最多翻卡数 (卡 20+ 张, 防死转)
var SWIPE_MS = 600;          // input swipe 时长; 340px 大步防一次跳 2 卡 (pf_scene 实测)

var EV_PLAY_TH = 0.78;       // ev_play 模板阈值 (灰 PLAY 靠饱和度门再拦一层)
var HALL_TH = 0.72;
var TITLE_TH = 0.72;
var CONT_TH = 0.72;
var X_TH = 0.8;
var SPD_TH = 0.85;           // 速度泡三模板 (同 pf_bot)

var FIRST_TAP = [903, 512];  // 右槽先手 (同 settle_bot): 胜=CONTINUE / 负=REMATCH
var HOME_XY = [115, 30];     // 顶栏房子回大厅 (MuMu (115,37) y*0.8 估算, TODO 真机标定)

/* EVENTS 居中卡区域 [x,y,w,h]: MuMu (475,120)-(805,700) → y*0.8, TODO 真机标定 */
var CARD_ROI = [475, 96, 330, 464];
/* 轮播左滑 (内容左移 = 翋下一张卡): MuMu swipe 900,400→560,400 → y*0.8 */
var SWIPE_FROM = [900, 320];
var SWIPE_TO = [560, 320];

/* 轮播目标卡: 划到该模板命中的居中卡才点 PLAY! (当前 = Pillow Talk 卡面/标题,
 * 建议裁居中卡的活动标题字样, 避开相邻卡边缘)。换活动时重裁 ev_target.png 即可,
 * 不用改代码; 置空 = 退回「任意可用 PLAY!」旧行为 */
var TARGET_CARD = "ev_target";
var TARGET_TH = 0.75;        // 目标卡模板阈值 (误命中调高 / 划不到调低)

var PLAY_MIN_S = 80;         // PLAY 按钮饱和度均值下限: 彩色=可用 / 灰=禁用 (用户色彩规则)

/* 战斗开场 AUTO/3x (MuMu y*0.8 估算, TODO 真机标定; 没裁出速度三模板前不会点击) */
var BRAIN_BOX = [620, 536, 40, 32];  // 脑子图标 (MuMu 620,670-660,710)
var BRAIN_XY = [640, 552];
var BRAIN_ON_V = 75;                 // V 均值 ≥75 = 亮 (pf_bot 实测亮~101 灭~49)
var SPD_ROI = [600, 452, 90, 64];    // 速度泡 (MuMu 600,565,690,645)
var SPD_XY = [640, 484];             // 点一下升一档 (1x→2x→3x)

/* PLAY! 之后到开打的步骤表 — Pillow Talk 链: 已选关卡 划轨/选最右战斗节点 → 点 PLAY 开打.
 * 默认空 = 停在安全点; 真机截图标定后填 (坐标 1280x576 基准), 示例:
 *   var FIGHT_STEPS = [
 *     { note: "节点轨划到最右", swipe: [900, 300, 560, 300] },
 *     { note: "选最右战斗节点", taps: [[1100, 300]] },
 *     { note: "PLAY 开打",     taps: [[640, 460]], waitMs: 2500 }
 *   ];
 * taps = 固定坐标点击 (标定过的才许填); tpl = 模板点击 (模板缺失直接中止, 不盲点);
 * swipe = 一次滑动 [x1,y1,x2,y2,ms?] */
var FIGHT_STEPS = [];

/* 采集模式: 每 CAP_MS 存一帧到 /sdcard/sgm_settle/nav/, 共 CAP_FRAMES 帧,
 * 期间手动把流程走一遍 (大厅→EVENTS→划到 Pillow Talk→PLAY→最右节点→PLAY→战斗开场) */
var CAP_FRAMES = 60;
var CAP_MS = 2500;

var SHOT_DIR = "/sdcard/sgm_settle/";
var NAV_SHOT = SHOT_DIR + "nav_frame.png";
var CAP_DIR = SHOT_DIR + "nav/";
var WORK_H = 576;            // 同 settle_bot 模板基准高

/* 大字/结算残局逃逸用的区域 (同 settle_bot 标定) */
var ROI_TITLE = [400, 20, 480, 100];
var X_ROI = [950, 24, 290, 144];     // 促销弹窗右上 X (MuMu 950,30,1240,180 → y*0.8)

/* 结算逃逸链复用的 settle 模板 (与 settle_bot 同名同目录) */
var NAV_TPL_SETTLE = ["victory", "defeat", "btn_continue"];
var NAV_TPL_REQUIRED = ["hall_events", "ev_play"]
    .concat(TARGET_CARD ? [TARGET_CARD] : []).concat(NAV_TPL_SETTLE);
var NAV_TPL_OPTIONAL = ["scene_x", "vs_fight",
    "battle_spd_1x", "battle_spd_2x", "battle_spd_3x"];

/* ---------- 纯决策函数 (Node 可单测) ---------- */

/* HSV 饱和度 (0-255): 彩色按钮高、置灰按钮低 — 全游戏「蓝=可用 灰=禁用」的量化 */
function satOfArgb(c) {
    var r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
    var mx = Math.max(r, g, b), mn = Math.min(r, g, b);
    return mx === 0 ? 0 : (mx - mn) * 255 / mx;
}

/* HSV 亮度 V (0-255) = max(r,g,b), 与 pf_bot 的 cv2 HSV V 通道同口径 */
function vOfArgb(c) {
    return Math.max((c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF);
}

function playAvailable(satMean) {
    return satMean >= PLAY_MIN_S;
}

/* 速度/脑子处置计划 (pf_bot ensure_battle_auto 的分支表):
 * allowTaps = 速度三模板已裁出 (坐标可信才允许点击), 否则只告警不动手 */
function planSpeed(spd, brainOn, allowTaps) {
    if (spd >= 3) return { taps: 0 };
    if (spd === 0) {
        if (!allowTaps) return { taps: 0, skip: "无速度模板, 不盲点脑子/速度泡" };
        if (!brainOn) return { tapBrain: true, taps: 0 };
        return { taps: 0, skip: "脑子亮但速度泡未识别, 跳过提速" };
    }
    return { taps: 3 - spd };
}

/* waitHall 单帧处置优先级 (同 pf_scene): 弹窗X → 结算大字先手 → CONTINUE
 * → 大厅(完成) → 偶尔回家 → 等待 */
function decideHallFrame(f) {
    if (f.x) return { act: "tapX", xy: f.x };
    if (f.vic || f.def) return { act: "firstTap", xy: FIRST_TAP };
    if (f.cont) return { act: "tapContinue", xy: f.cont };
    if (f.hall) return { act: "hall", xy: f.hall };
    if (f.n % 4 === 0 && f.homeTries < 3) return { act: "home", xy: HOME_XY };
    return { act: "wait" };
}

/* 活动轮播单帧处置: 居中卡有可用 PLAY! → 点; PLAY 置灰或没有 → 翻卡 (翻够上限 → 失败) */
function planCard(find) {
    var gray = !!find.play && !(find.sat !== null && playAvailable(find.sat));
    if (find.play && !gray) return { act: "play", xy: find.play };
    if (find.swipes >= MAX_SWIPES) return { act: "fail" };
    return { act: "swipe", why: gray ? "PLAY 置灰 (不可用), 跳过该卡" : null };
}

/* 目标卡模式单帧处置 (划 PF 等同款翻卡, 只认目标卡): 居中卡 = 目标 → PLAY! 可用则点;
 * 置灰/没 PLAY → 报错 (目标卡绝不跳过 — 划过去还得绕回来, 直接把原因报给人);
 * 不是目标卡 → 翻卡 (翻够上限 → 失败) */
function planTargetCard(find) {
    if (find.card) {
        if (!find.play) return { act: "fail", why: "目标卡居中但 ROI 里没有 PLAY! (模板/ROI 与实际不符?)" };
        if (find.sat !== null && playAvailable(find.sat)) return { act: "play", xy: find.play };
        return { act: "fail", why: "目标卡 PLAY! 置灰 (次数/能量用完?)" };
    }
    if (find.swipes >= MAX_SWIPES) return { act: "fail" };
    return { act: "swipe" };
}

/* ---------- I/O 层 (AutoJs6 + Shizuku) ---------- */

var NAV_STOP = {};   // 哨兵: 用户按「停止」时抛出, run* 里静默收场

function tplDir() {
    return files.cwd() + "/templates/";
}

function chkStop(shouldStop) {
    if (shouldStop && shouldStop()) throw NAV_STOP;
}

function missingRequired() {
    var miss = [];
    for (var i = 0; i < NAV_TPL_REQUIRED.length; i++) {
        if (!files.exists(tplDir() + NAV_TPL_REQUIRED[i] + ".png")) {
            miss.push(NAV_TPL_REQUIRED[i]);
        }
    }
    return miss;
}

/* 加载必需 + 存在的可选模板; 返回 {tpls, haveSpd} */
function loadNavTemplates() {
    var tpls = {};
    var names = NAV_TPL_REQUIRED.concat(NAV_TPL_OPTIONAL);
    for (var i = 0; i < names.length; i++) {
        var p = tplDir() + names[i] + ".png";
        if (files.exists(p)) {
            var img = images.read(p);
            if (!img) throw new Error("模板读不出来: " + p);
            tpls[names[i]] = img;
        }
    }
    for (var j = 0; j < NAV_TPL_REQUIRED.length; j++) {
        if (!tpls[NAV_TPL_REQUIRED[j]]) {
            throw new Error("缺模板: " + NAV_TPL_REQUIRED[j] + ".png");
        }
    }
    return { tpls: tpls, haveSpd: !!(tpls.battle_spd_1x && tpls.battle_spd_2x
        && tpls.battle_spd_3x) };
}

/* 截屏并缩放到 576 基准; 返回 {work, frame, scale}, 用完 release() */
function snapWork() {
    shizuku("screencap -p " + NAV_SHOT);
    var frame = images.read(NAV_SHOT);
    if (!frame) return null;
    var scale = frame.getHeight() / WORK_H;
    var work = frame;
    if (Math.abs(scale - 1) > 0.001) {
        work = images.resize(frame, [Math.round(frame.getWidth() / scale), WORK_H]);
    }
    return { work: work, frame: frame, scale: scale };
}

function release(snap) {
    if (snap.work !== snap.frame) snap.work.recycle();
    snap.frame.recycle();
}

/* 命中左上角, 统一返回 [x, y] 数组 (tapWork 只认数组) */
function find(work, tpl, roi, th) {
    var opt = { threshold: th };
    if (roi) opt.region = roi;
    var p = images.findImage(work, tpl, opt);
    return p ? [p.x, p.y] : null;
}

function tapWork(snap, xy) {
    shizuku("input tap " + Math.round(xy[0] * snap.scale) + " "
        + Math.round(xy[1] * snap.scale));
}

function tapCenterWork(snap, hit, tpl) {
    tapWork(snap, [hit[0] + tpl.getWidth() / 2, hit[1] + tpl.getHeight() / 2]);
}

function swipeLeftWork(snap) {
    shizuku("input swipe "
        + Math.round(SWIPE_FROM[0] * snap.scale) + " " + Math.round(SWIPE_FROM[1] * snap.scale)
        + " " + Math.round(SWIPE_TO[0] * snap.scale) + " " + Math.round(SWIPE_TO[1] * snap.scale)
        + " " + SWIPE_MS);
}

/* 框内抽样均值 (饱和度/亮度), box=[x,y,w,h], 内缩 4px 避开边缘光晕 */
function sampleMean(img, box, fn) {
    var total = 0, cnt = 0;
    for (var yy = 4; yy < box[3] - 4; yy += 4) {
        for (var xx = 4; xx < box[2] - 4; xx += 4) {
            total += fn(images.pixel(img, box[0] + xx, box[1] + yy));
            cnt++;
        }
    }
    return cnt ? total / cnt : 0;
}

function readSpeed(work, tpls) {
    var order = [["battle_spd_3x", 3], ["battle_spd_2x", 2], ["battle_spd_1x", 1]];
    for (var i = 0; i < order.length; i++) {
        if (tpls[order[i][0]] && find(work, tpls[order[i][0]], SPD_ROI, SPD_TH)) {
            return order[i][1];
        }
    }
    return 0;
}

/* ---------- 各阶段 ---------- */

/* 启动游戏并等到大厅, 返回 EVENTS 菱形中心 (work 坐标) */
function waitHall(logger, tpls, shouldStop) {
    var t0 = Date.now();
    var homeTries = 0, n = 0;
    logger("[航] 已发出游戏启动指令, 等大厅 ...");
    while (Date.now() - t0 < LAUNCH_TIMEOUT * 1000) {
        chkStop(shouldStop);
        n++;
        var snap = snapWork();
        if (!snap) { sleep(800); continue; }
        var w = snap.work;
        var d = decideHallFrame({
            x: tpls.scene_x ? find(w, tpls.scene_x, X_ROI, X_TH) : null,
            vic: find(w, tpls.victory, ROI_TITLE, TITLE_TH) !== null,
            def: find(w, tpls.defeat, ROI_TITLE, TITLE_TH) !== null,
            cont: find(w, tpls.btn_continue, null, CONT_TH),
            hall: find(w, tpls.hall_events, null, HALL_TH),
            n: n, homeTries: homeTries
        });
        if (d.act !== "wait") {
            logger("[航] 大厅等待: " + d.act + " (" + Math.round((Date.now() - t0) / 1000) + "s)");
        }
        release(snap);
        if (d.act === "hall") return d.xy;
        if (d.act === "tapX" || d.act === "tapContinue"
            || d.act === "firstTap" || d.act === "home") {
            var s2 = snapWork();
            if (s2) {
                if (d.act === "tapX") tapCenterWork(s2, d.xy, tpls.scene_x);
                else if (d.act === "tapContinue") tapCenterWork(s2, d.xy, tpls.btn_continue);
                else tapWork(s2, d.xy);
                release(s2);
            }
            if (d.act === "home") homeTries++;
            sleep(d.act === "tapContinue" || d.act === "firstTap" ? 2500 : TAP_WAIT_MS);
            continue;
        }
        if (n % 5 === 0) logger("[航] 等待大厅 ... (" + Math.round((Date.now() - t0) / 1000) + "s)");
        sleep(POLL_MS);
    }
    throw new Error(LAUNCH_TIMEOUT + "s 未回到大厅 (EVENTS 菱形不可见)");
}

/* 点 EVENTS 菱形进活动轮播: ev_play 出现 → 成功;
 * EVENTS 菱形连续消失 → 视为已进 hub (哪怕居中卡暂时没有 PLAY, 后面翻卡解决) */
function gotoEvents(logger, tpls, hallBox, shouldStop) {
    var t0 = Date.now();
    var gone = 0, tapped = 0;
    while (Date.now() - t0 < HUB_TIMEOUT * 1000) {
        chkStop(shouldStop);
        var snap = snapWork();
        if (!snap) { sleep(800); continue; }
        var play = find(snap.work, tpls.ev_play, CARD_ROI, EV_PLAY_TH);
        var hall = find(snap.work, tpls.hall_events, null, HALL_TH);
        release(snap);
        if (play) { logger("[航] EVENTS 轮播就绪"); return; }
        if (hall) {
            gone = 0;
            var s2 = snapWork();
            if (s2) { tapCenterWork(s2, hall, tpls.hall_events); release(s2); tapped++; }
            if (tapped <= 3) logger("[航] 点 EVENTS 菱形 → (" + hall[0] + "," + hall[1] + ")");
            sleep(2500);
        } else {
            if (++gone >= 3) { logger("[航] 已离开大厅 (进入 EVENTS)"); return; }
            sleep(POLL_MS);
        }
    }
    throw new Error(HUB_TIMEOUT + "s 未进入 EVENTS 轮播");
}

/* 轮播划到目标活动卡并点 PLAY! (绝不点 SKIP!):
 * TARGET_CARD 模式 = 只点目标卡 (置灰报错不跳过); 目标模板不在 = 任意可用 PLAY! (旧行为) */
function pickAndPlay(logger, tpls, shouldStop) {
    var target = TARGET_CARD ? tpls[TARGET_CARD] : null;
    var swipes = 0;
    while (true) {
        chkStop(shouldStop);
        var snap = snapWork();
        if (!snap) { sleep(800); continue; }
        var play = find(snap.work, tpls.ev_play, CARD_ROI, EV_PLAY_TH);
        var card = target ? find(snap.work, target, CARD_ROI, TARGET_TH) : null;
        var sat = null;
        if (play) {
            sat = sampleMean(snap.work, [play[0], play[1],
                tpls.ev_play.getWidth(), tpls.ev_play.getHeight()], satOfArgb);
        }
        var d = target ? planTargetCard({ card: card, play: play, sat: sat, swipes: swipes })
            : planCard({ play: play, sat: sat, swipes: swipes });
        if (card) {
            logger("[航] 目标卡居中 → " + d.act + (d.why ? " — " + d.why : "")
                + (play && sat !== null ? " (PLAY! 饱和度 " + Math.round(sat)
                    + ", 阈值 " + PLAY_MIN_S + ")" : ""));
        } else if (play) {
            logger("[航] 居中卡 PLAY! 饱和度均值 " + Math.round(sat)
                + " (阈值 " + PLAY_MIN_S + ") → " + d.act
                + (d.why ? " — " + d.why : ""));
        }
        release(snap);
        if (d.act === "fail") {
            throw new Error(d.why || (target
                ? ("翻了 " + swipes + " 张卡没划到目标卡 (ev_target 模板不匹配, 或活动不在轮播?)")
                : ("翻了 " + swipes + " 张卡没有可用的 PLAY! (都置灰? 能量/次数用完?)")));
        }
        if (d.act === "play") {
            var s2 = snapWork();
            if (s2) { tapCenterWork(s2, play, tpls.ev_play); release(s2); }
            logger("[航] 点 PLAY! → (" + (play[0] + tpls.ev_play.getWidth() / 2) + ","
                + (play[1] + tpls.ev_play.getHeight() / 2) + ")"
                + (target ? ", 目标卡" : ", 第 " + (swipes + 1) + " 张卡"));
            sleep(2500);
            return;
        }
        swipeLeft();
        swipes++;
        sleep(SWIPE_WAIT_MS);
    }
}

function swipeLeft() {
    var s = snapWork();
    if (!s) return;
    swipeLeftWork(s);
    release(s);
}

/* PLAY! 之后按标定表走 划轨/选节点/点 PLAY; 空表 = 停在安全点 */
function runFightSteps(logger, tpls, shouldStop) {
    if (!FIGHT_STEPS.length) {
        logger("[!!] FIGHT_STEPS 未标定 — 已停在 PLAY! 之后的安全点");
        logger("[!!] 先跑一次采集 (导航按钮在缺模板时会自动进采集), 拿截图填 pf_nav.js");
        return false;
    }
    for (var i = 0; i < FIGHT_STEPS.length; i++) {
        chkStop(shouldStop);
        var step = FIGHT_STEPS[i];
        logger("[航] 步骤 " + (i + 1) + "/" + FIGHT_STEPS.length + ": " + (step.note || "(未命名)"));
        if (step.tpl) {
            if (!tpls[step.tpl]) {
                throw new Error("FIGHT_STEPS 第 " + (i + 1) + " 步要模板 " + step.tpl
                    + ".png, 但 templates/ 里没有 — 不盲点, 已中止");
            }
            var hit = null;
            for (var r = 0; r < 5 && !hit; r++) {
                chkStop(shouldStop);
                var s1 = snapWork();
                if (s1) {
                    hit = find(s1.work, tpls[step.tpl], step.roi || null, EV_PLAY_TH);
                    release(s1);
                }
                if (!hit) sleep(2000);
            }
            if (!hit) throw new Error("第 " + (i + 1) + " 步模板 " + step.tpl + " 10s 未出现 — 界面与预期不符");
            var s2 = snapWork();
            if (s2) { tapCenterWork(s2, hit, tpls[step.tpl]); release(s2); }
        } else {
            if (step.swipe) {
                var s4 = snapWork();
                if (s4) {
                    shizuku("input swipe "
                        + Math.round(step.swipe[0] * s4.scale) + " " + Math.round(step.swipe[1] * s4.scale)
                        + " " + Math.round(step.swipe[2] * s4.scale) + " " + Math.round(step.swipe[3] * s4.scale)
                        + " " + (step.swipe[4] || SWIPE_MS));
                    release(s4);
                }
            }
            for (var t = 0; t < (step.taps || []).length; t++) {
                var s3 = snapWork();
                if (s3) { tapWork(s3, step.taps[t]); release(s3); }
                sleep(800);
            }
        }
        sleep(step.waitMs || 2000);
    }
    return true;
}

/* 战斗开场 AUTO+3x 自检 (pf_bot ensure_battle_auto 同款):
 * 开场介绍 ~2.5s 人物不动是安全窗口; 速度三模板没裁出来前只读不点 */
function ensureBattleAuto(logger, tpls, shouldStop) {
    sleep(1800);
    chkStop(shouldStop);
    var snap = snapWork();
    if (!snap) { logger("[航] 战斗开场截屏失败, 跳过 AUTO/3x 检查"); return; }
    var spd = readSpeed(snap.work, tpls);
    var brainOn = sampleMean(snap.work, BRAIN_BOX, vOfArgb) >= BRAIN_ON_V;
    release(snap);
    var plan = planSpeed(spd, brainOn, tplCtx.haveSpd);
    if (plan.tapBrain) {
        logger("[航] 自动战斗未开, 点脑子 (" + BRAIN_XY + ")");
        var s1 = snapWork();
        if (s1) { tapWork(s1, BRAIN_XY); release(s1); }
        sleep(600);
        snap = snapWork();
        if (snap) {
            spd = readSpeed(snap.work, tpls);
            brainOn = sampleMean(snap.work, BRAIN_BOX, vOfArgb) >= BRAIN_ON_V;
            release(snap);
        }
        plan = planSpeed(spd, brainOn, tplCtx.haveSpd);
    }
    if (plan.skip) { logger("[航] " + plan.skip); return; }
    for (var i = 0; i < plan.taps; i++) {
        var s2 = snapWork();
        if (s2) { tapWork(s2, SPD_XY); release(s2); }
        sleep(450);
    }
    if (tplCtx.haveSpd) {
        snap = snapWork();
        if (snap) {
            var spd2 = readSpeed(snap.work, tpls);
            release(snap);
            logger(spd2 === 3 ? "[航] 速度校验: 3x"
                : "[!!] 速度校验: " + (spd2 || "未识别") + "x, 请留意");
        }
    }
    logger("[航] 战斗已开始 (脑子" + (brainOn ? "亮" : "?") + ", 速度 " + spd + "x)");
}

/* ---------- 入口 ---------- */

var tplCtx = { haveSpd: false };

/* 采集模式: 手动走一遍流程, 每 CAP_MS 存一帧, 供电脑端裁模板/标坐标 */
function runCapture(logger, shouldStop) {
    files.createWithDirs(CAP_DIR + "cap_000.png");
    logger("[采] 采集开始: 请现在手动走一遍 大厅→EVENTS→划到 Pillow Talk→PLAY!→最右节点→PLAY→战斗开场");
    for (var i = 0; i < CAP_FRAMES; i++) {
        chkStop(shouldStop);
        shizuku("screencap -p " + NAV_SHOT);
        var dst = CAP_DIR + "cap_" + ("00" + i).slice(-3) + ".png";
        files.remove(dst);
        files.copy(NAV_SHOT, dst);
        if (i % 8 === 0) {
            logger("[采] 第 " + i + "/" + CAP_FRAMES + " 帧 (点「停止」可提前结束)");
        }
        sleep(CAP_MS);
    }
    files.remove(NAV_SHOT);
    logger("[采] 采集完成: 电脑上 adb pull /sdcard/sgm_settle/nav/ (或 Tailscale), 然后裁模板/标坐标");
    return false;
}

/* 主流程; 返回 true = 可以交棒结算循环, false = 停在安全点/采集完成 */
function runNav(logger, shouldStop) {
    if (!logger) logger = function () {};
    try {
        var miss = missingRequired();
        if (miss.length) {
            logger("[航] 缺导航模板: " + miss.join(", ")
                + " — 自动转采集模式 (手动走一遍流程, 再裁模板)");
            return runCapture(logger, shouldStop);
        }
        var ctx = loadNavTemplates();
        tplCtx.haveSpd = ctx.haveSpd;
        var tpls = ctx.tpls;

        var appOk = false;
        try { appOk = app.launchPackage(GAME_PKG); } catch (e) { appOk = false; }
        if (!appOk) logger("[!!] app.launchPackage 返回失败, 仍尝试等大厅 (游戏可能已开着)");

        var hallBox = waitHall(logger, tpls, shouldStop);
        gotoEvents(logger, tpls, hallBox, shouldStop);
        pickAndPlay(logger, tpls, shouldStop);
        if (!runFightSteps(logger, tpls, shouldStop)) return false;
        ensureBattleAuto(logger, tpls, shouldStop);
        logger("[航] 导航完成, 交棒结算循环");
        return true;
    } catch (e) {
        if (e === NAV_STOP) { logger("[航] 已停止"); return false; }
        throw e;
    }
}

if (typeof module !== "undefined" && module.exports) {
    module.exports = {
        runNav: runNav,
        runCapture: runCapture,
        /* 以下导出仅供 test_nav.js 单测/仿真 */
        pickAndPlay: pickAndPlay,
        runFightSteps: runFightSteps,
        loadNavTemplates: loadNavTemplates,
        missingRequired: missingRequired,
        playAvailable: playAvailable,
        planSpeed: planSpeed,
        decideHallFrame: decideHallFrame,
        planCard: planCard,
        planTargetCard: planTargetCard,
        satOfArgb: satOfArgb,
        vOfArgb: vOfArgb,
        config: {
            PLAY_MIN_S: PLAY_MIN_S, MAX_SWIPES: MAX_SWIPES,
            FIRST_TAP: FIRST_TAP, CARD_ROI: CARD_ROI,
            BRAIN_ON_V: BRAIN_ON_V, FIGHT_STEPS: FIGHT_STEPS,
            TARGET_CARD: TARGET_CARD, TARGET_TH: TARGET_TH
        }
    };
}
