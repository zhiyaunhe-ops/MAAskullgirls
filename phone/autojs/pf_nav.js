/*
 * SGM 导航模块 — AutoJs6 版 (由 settle_bot.js 的「导航」按钮 require 调用, 不单独运行)
 *
 * 流程: 启动游戏 → 等大厅 (促销弹窗X/通用弹窗X/结算残局逃逸) → 点 EVENTS 菱形 →
 *       轮播划到目标活动卡 (Pillow Talk, ev_target 模板命中居中卡才点) → 点 PLAY! →
 *       按 FIGHT_STEPS 地图右移×2 → 最右绿✓锚点定位王关 → 点 FIGHT! → 战斗开场
 *       AUTO+3x 自检 (同 pf_bot ensure_battle_auto) → 交棒 settle_bot 结算循环。
 *
 * 坐标基准 1280x576 (同 settle_bot: 帧缩放到高 576 再匹配, 点击按 scale 换算回实机)。
 * PC(MuMu 720 基准)模板/坐标 → 手机: UI 尺寸 ×0.8 双轴 (2026-10-04 多尺度实测 0.98 分),
 * 横向位置因宽高比不同不可靠, 只用宽 ROI + 真机实测坐标。
 *
 * 标定状态 (2026-10-04 K70 真机走链标定):
 *   已标定  结算大字/三槽/右槽先手 (沿用 settle_bot 模板与 FIRST_TAP)
 *   已标定  hall_events / ev_play / ev_target (真机帧裁剪) + scene_x/modal_x (PC ×0.8 转换)
 *   已标定  vs_fight (VS 页右上 FIGHT!, 2026-10-06; runNav 开头快速接棒 + settle 逃生链共用)
 *   已标定  FIGHT_STEPS 王关链: 地图右移×2 + 绿✓锚点 + FIGHT! (真机走链验证可重复)
 *   已标定  战斗开场脑子/速度泡 (真机战斗帧实测; 速度模板 1x/3x, 每场持久仅重启重置)
 *
 * 安全规则: 模板/坐标未标定的界面一律不盲点 — 宁可停在安全点等人, 防止误触
 *   SKIP!/购买/消耗资源的入口。
 *
 * 纯决策函数 (playAvailable/decideHallFrame/planCard/planTargetCard/anchorHit/planAnchor)
 * 不碰 AutoJs6 全局, 可被 test_nav.js 在 Node 里单测; I/O 全在 run* 函数内。
 */

/* ---------- 配置 (坐标均为 1280x576 基准) ---------- */

var GAME_PKG = "com.autumn.skullgirls";

var POLL_MS = 1200;          // 大厅/进入等待轮询间隔
var TAP_WAIT_MS = 1500;      // 一般点击后的过场等待
var SWIPE_WAIT_MS = 1800;    // 轮播翻卡等待 (吸附回弹)
var LAUNCH_TIMEOUT = 300;    // 冷启动/断联重连到大厅秒数 — 5min, 服务器错误/慢加载等它自己恢复
var HUB_TIMEOUT = 120;       // 点 EVENTS 到进入活动轮播的秒数 (2min, 网络差也等)
var MAX_SWIPES = 18;         // 活动轮播最多翻卡数 (卡 20+ 张, 防死转)
var SWIPE_MS = 600;          // input swipe 时长; 340px 大步防一次跳 2 卡 (pf_scene 实测)

var EV_PLAY_TH = 0.78;       // ev_play 模板阈值 (灰 PLAY 靠饱和度门再拦一层)
var HALL_TH = 0.72;
var TITLE_TH = 0.72;
var CONT_TH = 0.72;
var X_TH = 0.8;
var SRV_TH = 0.75;           // 服务器错误弹窗 RETRY 按钮 (绿, 真机截图裁剪)
var FIGHT_TH = 0.75;         // VS 页/地图右上 FIGHT! 按钮 (橙, 2026-10-06 自愈失败帧裁剪)
var FIGHT_ROI = [1020, 0, 260, 130];   // 右上 FIGHT! 搜索区 (576 系; VS 页与地图页同位)
var OK_TH = 0.8;             // 弹窗 OK 按钮通用兜底 (紫底金边; 文案变体多, 按钮本体跨文案稳定)
var OK_ROI = [480, 300, 320, 180];     // OK 按钮搜索区 (576 系, 弹窗居中固定位)

var FIRST_TAP = [903, 512];  // 右槽先手 (同 settle_bot): 胜=CONTINUE / 负=REMATCH
var HOME_XY = [95, 32];      // 顶栏房子回大厅 (轮播页实测; 地图页在 ~72,27 —
var HOME_ROI = [0, 0, 180, 70];  // 优先 home.png 模板定位, 模板缺失才退固定坐标)

/* EVENTS 居中卡区域 [x,y,w,h]: K70 真机轮播实测, 居中卡 PLAY! 在内、相邻卡在外 */
var CARD_ROI = [475, 96, 330, 464];
/* 轮播左滑 (内容左移 = 翋下一张卡): K70 真机实测一步一卡 */
var SWIPE_FROM = [900, 320];
var SWIPE_TO = [560, 320];

/* 轮播目标卡: 划到该模板命中的居中卡才点 PLAY! (当前 = Pillow Talk 卡面/标题,
 * 建议裁居中卡的活动标题字样, 避开相邻卡边缘)。换活动时重裁 ev_target.png 即可,
 * 不用改代码; 置空 = 退回「任意可用 PLAY!」旧行为 */
var TARGET_CARD = "ev_target";
var TARGET_TH = 0.75;        // 目标卡模板阈值 (误命中调高 / 划不到调低)

var PLAY_MIN_S = 80;         // PLAY 按钮饱和度均值下限: 彩色=可用 / 灰=禁用 (用户色彩规则)

/* 战斗开场 AUTO/3x (K70 真机战斗帧标定 2026-10-04: 战斗 HUD 屏幕居中锚定)
 * 脑子亮度判自动 (亮=已开); 速度泡模板判档位: 1x 点两下 / 3x 不动 / 读不到跳过。
 * 速度只在程序重启后重置, 而重启必然走导航 → 只在此处 (和自愈导航) 判一次,
 * 结算循环不再管速度 (用户拍板) */
var BRAIN_BOX = [608, 536, 64, 36];  // 脑子图标 (V 均值判亮: 实测亮~108, pf_bot 灭~49)
var BRAIN_XY = [640, 552];
var BRAIN_ON_V = 75;                 // V 均值 ≥75 = 亮
var SPD_ROI = [580, 440, 120, 110];  // 速度泡区 (脑子上方; 模板 1x/3x, 0.95 区分档位)
var SPD_TH = 0.95;
var SPD_XY = [640, 482];             // 速度泡中心, 每点一档 1x→2x→3x

/* PLAY! 之后到开打的步骤表 — Pillow Talk 王关链 (2026-10-04 K70 真机走链标定,
 * 重进+重放验证逐像素可重复):
 *   地图右移×2 (到右边界钳位, 王关✓进入右半屏) → 绿✓锚点定位王关圆心 → FIGHT! */
var FIGHT_STEPS = [
    { note: "地图右移到右边界 (1/2)", swipe: [960, 288, 320, 288, 700], waitMs: 2200 },
    { note: "地图右移到右边界 (2/2)", swipe: [960, 288, 320, 288, 700], waitMs: 2200 },
    { note: "点王关 (最右绿✓锚点)",
      anchor: { color: 0xFF7AC241, tol: 50, roi: [640, 40, 640, 500],
                offset: [-72, 2], minTotal: 36, minCluster: 18, cell: 4 },
      waitMs: 4000 },
    { note: "FIGHT! 开打", taps: [[1175, 54]], waitMs: 2500 }
];
/* 步骤类型: taps=固定坐标点击; tpl=模板点击 (缺失即中止不盲点);
 * swipe=一次滑动 [x1,y1,x2,y2,ms?]; anchor=颜色锚点定位点击 (大小/平移不敏感):
 *   anchor.color 目标色 ARGB / tol 逐通道容差 / roi 网格扫描区 / cell 采样步距,
 *   取「最靠右的 28px 簇」质心 + offset 落点; 找不到 → 中止不盲点。 */

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
var X_ROI = [600, 0, 560, 180];      // 促销弹窗右上 X (PC ROI_SCENE_X ×0.8 后放宽, 容宽屏位移)
var MODAL_ROI = [480, 0, 480, 280];  // 通用弹窗方 X (PC ROI_MODAL_X (700,0,1060,300) ×0.8 放宽)
var MODAL_TH = 0.90;                 // popup_close_x 方 X 阈值 (PC 同款, 零误报口径)

/* 结算逃逸链复用的 settle 模板 (与 settle_bot 同名同目录) */
var NAV_TPL_SETTLE = ["victory", "defeat", "btn_continue"];
var NAV_TPL_REQUIRED = ["hall_events", "ev_play"]
    .concat(TARGET_CARD ? [TARGET_CARD] : []).concat(NAV_TPL_SETTLE);
var NAV_TPL_OPTIONAL = ["scene_x", "modal_x", "srv_retry", "home", "vs_fight", "ok_btn",
    "battle_spd_1x", "battle_spd_3x"];

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

/* waitHall 单帧处置优先级 (同 pf_scene): 服务器错误RETRY → 促销X → 通用X
 * → 结算大字先手 → CONTINUE → 大厅(完成) → 偶尔回家 → 等待 */
function decideHallFrame(f) {
    if (f.srv) return { act: "tapSrv", xy: f.srv };
    if (f.ok) return { act: "tapOk", xy: f.ok };
    if (f.x) return { act: "tapX", xy: f.x };
    if (f.modal) return { act: "tapModal", xy: f.modal };
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

/* 锚点色判定: 逐通道容差 (王关绿✓ 实测 ~(122,194,65), tol 50 连暗边 (72,169,0) 都拒) */
function anchorHit(c, a) {
    var r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
    var t = a.color, tol = a.tol || 50;
    return Math.abs(r - ((t >> 16) & 255)) <= tol
        && Math.abs(g - ((t >> 8) & 255)) <= tol
        && Math.abs(b - (t & 255)) <= tol;
}

/* 颜色锚点聚合 (大小/平移不敏感的王关定位):
 * pts = I/O 层网格采样命中的坐标; 取「最靠右的 band 簇」质心 + offset = 点击落点。
 * 王关 = 地图最右的绿✓节点; 同屏更靠左的 ✓ (920/805) 不进簇。 */
function planAnchor(pts, a) {
    var minTotal = a.minTotal || 36, minCluster = a.minCluster || 18, band = a.band || 28;
    if (pts.length < minTotal) return { act: "fail", why: "锚点色样本不足 (" + pts.length + ")" };
    var xmax = -1, i;
    for (i = 0; i < pts.length; i++) if (pts[i][0] > xmax) xmax = pts[i][0];
    var cx = 0, cy = 0, n = 0;
    for (i = 0; i < pts.length; i++) {
        if (pts[i][0] > xmax - band) { cx += pts[i][0]; cy += pts[i][1]; n++; }
    }
    if (n < minCluster) return { act: "fail", why: "最右锚点簇太小 (" + n + ")" };
    return { act: "tap", xy: [cx / n + (a.offset ? a.offset[0] : 0),
                              cy / n + (a.offset ? a.offset[1] : 0)] };
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

/* 供 settle_bot 战斗超时拉回用: 居中卡区找可用 (彩色) PLAY!, 命中返回中心 [x,y] (576 系)。
 * 只依赖 ev_play 一张模板 (惰性装载, 缺文件返回 null 不抛); work 由调用方给 (576 基准帧)。 */
var _playTpl = null;
function findPlay(work) {
    try {
        if (!_playTpl) {
            var p = tplDir() + "ev_play.png";
            if (!files.exists(p)) return null;
            _playTpl = images.read(p);
            if (!_playTpl) return null;
        }
        var hit = find(work, _playTpl, CARD_ROI, EV_PLAY_TH);
        if (!hit) return null;
        var s = sampleMean(work, [hit[0], hit[1], _playTpl.getWidth(), _playTpl.getHeight()],
            satOfArgb);
        if (!playAvailable(s)) return null;          // 置灰 PLAY 不点 (次数/能量用完)
        return [hit[0] + _playTpl.getWidth() / 2, hit[1] + _playTpl.getHeight() / 2];
    } catch (e) {
        return null;
    }
}

/* 加载必需 + 存在的可选模板; 返回 {name: img} 字典 */
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
    return tpls;
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
            srv: tpls.srv_retry ? find(w, tpls.srv_retry, null, SRV_TH) : null,
            ok: tpls.ok_btn ? find(w, tpls.ok_btn, OK_ROI, OK_TH) : null,
            x: tpls.scene_x ? find(w, tpls.scene_x, X_ROI, X_TH) : null,
            modal: tpls.modal_x ? find(w, tpls.modal_x, MODAL_ROI, MODAL_TH) : null,
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
        if (d.act === "home") {
            /* 房子按钮在轮播页/地图页位置不同 — 有 home 模板就按模板找 */
            var sh = snapWork();
            var hxy = HOME_XY;
            if (sh) {
                var hh = tpls.home ? find(sh.work, tpls.home, HOME_ROI, 0.8) : null;
                if (hh) hxy = [hh[0] + tpls.home.getWidth() / 2, hh[1] + tpls.home.getHeight() / 2];
                release(sh);
            }
            var s3 = snapWork();
            if (s3) { tapWork(s3, hxy); release(s3); }
            if (tpls.home) logger("[航] 回家 → (" + Math.round(hxy[0]) + "," + Math.round(hxy[1]) + ")");
            homeTries++;
            sleep(TAP_WAIT_MS);
            continue;
        }
        if (d.act === "tapX" || d.act === "tapContinue"
            || d.act === "firstTap" || d.act === "tapModal"
            || d.act === "tapSrv") {
            var s2 = snapWork();
            if (s2) {
                if (d.act === "tapX") tapCenterWork(s2, d.xy, tpls.scene_x);
                else if (d.act === "tapContinue") tapCenterWork(s2, d.xy, tpls.btn_continue);
                else if (d.act === "tapModal") tapCenterWork(s2, d.xy, tpls.modal_x);
                else if (d.act === "tapSrv") tapCenterWork(s2, d.xy, tpls.srv_retry);
                else if (d.act === "tapOk") tapCenterWork(s2, d.xy, tpls.ok_btn);
                else tapWork(s2, d.xy);
                release(s2);
            }
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
        if (dismissPopups(logger, tpls)) { sleep(TAP_WAIT_MS); continue; }
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
        if (dismissPopups(logger, tpls)) { sleep(TAP_WAIT_MS); continue; }
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

/* 关弹窗 (尽力一轮): 促销右上 X → 通用方 X, 命中就点。pf_scene 弹窗优先同款。
 * 返回是否点过 (调用方自行决定要不要再扫)。 */
function dismissPopups(logger, tpls) {
    var s = snapWork();
    if (!s) return false;
    var hit = null, kind = null;
    if (tpls.srv_retry) {
        hit = find(s.work, tpls.srv_retry, null, SRV_TH);
        kind = "srv_retry";
    }
    if (!hit && tpls.scene_x) {
        hit = find(s.work, tpls.scene_x, X_ROI, X_TH);
        kind = "scene_x";
    }
    if (!hit && tpls.modal_x) {
        hit = find(s.work, tpls.modal_x, MODAL_ROI, MODAL_TH);
        kind = "modal_x";
    }
    if (hit) {
        tapCenterWork(s, hit, kind === "scene_x" ? tpls.scene_x : tpls.modal_x);
        logger("[航] 关弹窗 (" + kind + ") → (" + hit[0] + "," + hit[1] + ")");
    }
    release(s);
    return !!hit;
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
        if (dismissPopups(logger, tpls)) sleep(TAP_WAIT_MS);
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
        } else if (step.anchor) {
            var a = step.anchor, hitA = null;
            for (var rr = 0; rr < 3 && !hitA; rr++) {
                chkStop(shouldStop);
                var sa = snapWork();
                if (sa) {
                    var pts = [];
                    for (var gy = 2; gy < a.roi[3] - 2; gy += (a.cell || 4)) {
                        for (var gx = 2; gx < a.roi[2] - 2; gx += (a.cell || 4)) {
                            if (anchorHit(images.pixel(sa.work, a.roi[0] + gx, a.roi[1] + gy), a)) {
                                pts.push([a.roi[0] + gx, a.roi[1] + gy]);
                            }
                        }
                    }
                    release(sa);
                    var da = planAnchor(pts, a);
                    if (da.act === "tap") hitA = da.xy;
                    else logger("[航] 锚点未命中: " + da.why);
                }
                if (!hitA) sleep(2000);
            }
            if (!hitA) throw new Error("锚点步「" + (step.note || "")
                + "」未找到目标色团 — 不盲点, 已中止");
            var s5 = snapWork();
            if (s5) { tapWork(s5, hitA); release(s5); }
            logger("[航] 锚点命中 → (" + Math.round(hitA[0]) + "," + Math.round(hitA[1]) + ")");
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

/* 速度档位处置: 1x 点两下 / 2x 点一下 / 3x 不动 / 读不到跳过 (下一场导航再判) */
function planSpeed(spd) {
    if (spd === 3) return { taps: 0 };
    if (spd === 2) return { taps: 1 };
    if (spd === 1) return { taps: 2 };
    return { taps: 0, skip: "速度泡未识别, 跳过提速" };
}

/* 战斗开场 AUTO+3x (pf_bot ensure_battle_auto 同款时机):
 * 开场介绍 ~2.5s 人物不动是安全窗口。脑子亮度判自动; 速度泡模板判档位
 * (1x=点两下 / 3x=不动 / 读不到=跳过)。速度仅程序重启后重置, 重启必走导航,
 * 所以只在此处判一次。缺 1x/3x 模板 → 只判脑子不动速度 */
function ensureBattleAuto(logger, tpls, shouldStop) {
    sleep(1800);
    chkStop(shouldStop);
    var snap = snapWork();
    if (!snap) { logger("[航] 战斗开场截屏失败, 跳过 AUTO/3x 检查"); return; }
    var brainOn = sampleMean(snap.work, BRAIN_BOX, vOfArgb) >= BRAIN_ON_V;
    release(snap);
    if (!brainOn) {
        logger("[航] 自动战斗未开, 点脑子 (" + BRAIN_XY + ")");
        var s1 = snapWork();
        if (s1) { tapWork(s1, BRAIN_XY); release(s1); }
        sleep(600);
    }
    var spd = 0;
    if (tpls.battle_spd_1x && tpls.battle_spd_3x) {
        snap = snapWork();
        if (snap) {
            var order = [["battle_spd_3x", 3], ["battle_spd_1x", 1]];
            for (var i = 0; i < order.length && !spd; i++) {
                if (find(snap.work, tpls[order[i][0]], SPD_ROI, SPD_TH)) spd = order[i][1];
            }
            release(snap);
        }
        var plan = planSpeed(spd);
        for (var t = 0; t < plan.taps; t++) {
            chkStop(shouldStop);
            var s2 = snapWork();
            if (s2) { tapWork(s2, SPD_XY); release(s2); }
            sleep(450);
        }
        logger("[航] 速度校验: " + (spd || "未识别") + "x → 点 " + plan.taps + " 下"
            + (plan.skip ? " (" + plan.skip + ")" : ""));
    } else {
        logger("[航] 缺 battle_spd_1x/3x 模板 — 只判脑子, 不动速度");
    }
    logger("[航] 战斗已开始 (脑子" + (brainOn ? "亮, 自动已开" : "已点开") + ")");
}

/* ---------- 入口 ---------- */

/* 采集模式: 手动走一遍流程, 每 CAP_MS 存一帧, 供电脑端裁模板/标坐标 */
function runCapture(logger, shouldStop) {
    files.createWithDirs(CAP_DIR + "cap_000.png");
    logger("[采] 采集开始: 请现在手动走一遍 大厅→EVENTS→划到 Pillow Talk→PLAY!→地图右移→王关→FIGHT→战斗开场");
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
        var tpls = loadNavTemplates();

        /* 快速接棒 (pf_scene "认场景→做动作" 同款思路): 已在 VS 对战页/地图 FIGHT! 页 →
         * 直接点右上 FIGHT! 开打, 不绕大厅/EVENTS (整条链会白等到超时)。
         * 自愈常见场景: 断联/网络错误后正好停在 VS 页 (2026-10-06 01:55 实例) */
        var snap0 = snapWork();
        if (snap0) {
            var fightHit = tpls.vs_fight
                ? find(snap0.work, tpls.vs_fight, FIGHT_ROI, FIGHT_TH) : null;
            release(snap0);
            if (fightHit) {
                logger("[航] VS/FIGHT 页直接开打 (右上 FIGHT!)");
                var sf = snapWork();
                if (sf) {
                    tapWork(sf, [fightHit[0] + tpls.vs_fight.getWidth() / 2,
                                 fightHit[1] + tpls.vs_fight.getHeight() / 2]);
                    release(sf);
                }
                sleep(2600);                         // VS→战斗转场 (与正常链 waitMs 同量级)
                ensureBattleAuto(logger, tpls, shouldStop);
                logger("[航] 导航完成, 交棒结算循环");
                return true;
            }
        }

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
        findPlay: findPlay,
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
        anchorHit: anchorHit,
        planAnchor: planAnchor,
        satOfArgb: satOfArgb,
        vOfArgb: vOfArgb,
        config: {
            PLAY_MIN_S: PLAY_MIN_S, MAX_SWIPES: MAX_SWIPES,
            FIRST_TAP: FIRST_TAP, CARD_ROI: CARD_ROI,
            BRAIN_ON_V: BRAIN_ON_V, FIGHT_STEPS: FIGHT_STEPS,
            TARGET_CARD: TARGET_CARD, TARGET_TH: TARGET_TH,
            HOME_XY: HOME_XY
        }
    };
}
