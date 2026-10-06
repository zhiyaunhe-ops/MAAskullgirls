"""PF 定时任务: 按时间自动跑 SGM PF 全链路 (实验版, 不动 pf_bot/pf_scene 代码)。

场次绑定"怎么跑" (sessions.json), 本模块绑定"什么时候跑" (debug/pf/schedule.json):

{
  "jobs": [
    {"name": "凌晨跑元素场", "time": "01:00", "days": "daily",
     "action": "run_pf",
     "params": {"arena": "EYE", "parent_session": "s1788366023203", "restart": true}},
    {"name": "早上停", "time": "07:00", "action": "stop_pf"}
  ]
}

一次性任务: 加 "date": "YYYY-MM-DD" 则仅该日触发, 过后自然失效 (如今天 5 点关 bot)。

action:
  run_pf   全链: (运行中且 restart=true 则先停) → MuMu 就绪 → 开游戏 → PF hub
           → center 场地 → 场次(parent_session 建子场 / session_id 直用)
           → 后台起 pf_bot → /api/start → 验证 RUNNING
  stop_pf  /api/stop 并等进程退出
  explore  停 bot → scene explore 扫分报告(写 schedule.log) → 可选 resume

用法 (anaconda python):
  python tools/pf_schedule.py             # 常驻, 每 20s 扫描
  python tools/pf_schedule.py --fire 名称  # 立即触发指定任务 (测试用)
  python tools/pf_schedule.py --list      # 列出任务

注意: 错过窗口 (宿主机睡眠) 在 grace_minutes(默认90) 内补跑一次; 运行中严禁再跑
pf_scene/pf_bot 手工操作 (见 skill sgm-pf-run 硬规则)。
"""
from __future__ import annotations

import datetime as dt
import json
import os
import subprocess
import sys
import threading
import time
import urllib.request
from pathlib import Path

from pf_env import WEBUI_PORT
from pf_logging import log as _pf_log

PROJECT_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT_ROOT / "tools"))

SCHEDULE_PATH = PROJECT_ROOT / "debug" / "pf" / "schedule.json"
STATE_PATH = PROJECT_ROOT / "debug" / "pf" / "schedule_state.json"
SCHED_LOG = PROJECT_ROOT / "debug" / "pf" / "schedule.log"
BOT_LOG = PROJECT_ROOT / "debug" / "pf" / "bot_stdout.log"
PY = sys.executable  # 调度器必须用 anaconda python 启动; bot 子进程沿用同一解释器
# 端口 PF 服务锁定值 (pf_env.WEBUI_PORT, 可由 config.json 覆盖), 不再写死 8787
API = f"http://127.0.0.1:{WEBUI_PORT}"
SVC_ID = "sgm-pf-bot"   # 身份标签, 必须与 pf_webui.SVC_ID 一致

WEEKDAYS = {"mon": 0, "tue": 1, "wed": 2, "thu": 3, "fri": 4, "sat": 5, "sun": 6}


def log(msg: str, level: str = "info") -> None:
    """写统一日志 pf.log（2026-10-02 起不再单独写 schedule.log）。

    保留 print: 调度器常被手工前台跑, 终端仍要看得到。
    调度器事件与 bot 事件同处一份, 按时间混排 —— 这样"谁在什么时候把 bot 拉起来
    的"一行就能查到, 不用在 schedule.log / bot_stdout.log 之间来回跳。
    """
    line = f"[{time.strftime('%m-%d %H:%M:%S')}][{level}] {msg}"
    print(line, flush=True)
    _pf_log(line, level, tag="sched")


# ---------- HTTP 辅助 ----------

def api_get(path: str, timeout: int = 3):
    try:
        with urllib.request.urlopen(API + path, timeout=timeout) as r:
            return json.loads(r.read().decode("utf-8"))
    except Exception:  # noqa: BLE001
        return None


def api_post(path: str, data: dict, timeout: int = 5):
    req = urllib.request.Request(
        API + path, data=json.dumps(data).encode("utf-8"),
        headers={"Content-Type": "application/json"}, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.loads(r.read().decode("utf-8"))
    except Exception:  # noqa: BLE001
        return None


def bot_alive() -> bool:
    """端口有响应 ≠ bot 在跑 —— 必须校验身份标签。

    本机 87xx 段挤着别的服务 (8787 上实测是一个银企直连需求服务): 旧实现只问
    "端口通不通", 于是把别人的服务认成 bot, run_pf 会以为"bot 已在运行"而
    拒绝启动, 或者反过来向别的服务发 /api/start。所以比对 svc 字段。
    """
    st = api_get("/api/state")
    return bool(st and st.get("svc") == SVC_ID)


def wait_bot(dead: bool = False, timeout: int = 40) -> bool:
    """等 bot 进程出现(dead=False)或消失(dead=True)。"""
    t0 = time.time()
    while time.time() - t0 < timeout:
        if bot_alive() != dead:
            return True
        time.sleep(1.5)
    return False


# ---------- 动作 ----------

def act_run_pf(params: dict) -> None:
    from pf_scene import PfScene, ensure_mumu  # 延迟导入 (重, 且 bot 停后才能用)
    from pf_env import resolve_adb

    if bot_alive():
        if not params.get("restart"):
            log("bot 运行中, restart 未开, 跳过 run_pf", "warn")
            return
        log("bot 运行中, 先停止 (restart=true)")
        api_post("/api/stop", {})
        if not wait_bot(dead=True, timeout=40):
            raise RuntimeError("bot 40s 未退出, 放弃本次触发")

    ensure_mumu(resolve_adb()[0])
    scene = PfScene()
    scene.launch_game()
    scene.goto_pf()
    arena = params.get("arena")
    if arena:
        scene.center(arena)

    sid = params.get("session_id")
    if not sid and params.get("parent_session"):
        from pf_store import STORE  # bot 未运行, 独占 sessions.json 安全
        parent = STORE.get(params["parent_session"])
        if not parent:
            raise RuntimeError(f"parent_session {params['parent_session']} 不存在")
        # 2026-10-06: 父子已退役, 新建场次一律按 tag 取条件。父场次是迁移
        # 过来的存量, 多数带 tag -> 直接复用它的 tag 条件(等价于按 tag 建,
        # 只是名字沿用父名+日期); 没 tag 的(未迁移/人工建)才退回旧继承,
        # 免得schedule.json 里存量任务直接崩。
        if parent.get("tag"):
            from pf_artag import conditions_of
            tgt, ec, _ = conditions_of(parent["tag"])
            name = f"{parent['name']} {time.strftime('%m-%d')}"
            sess = STORE.create_tagged(name, parent.get("rule"),
                                       tag=parent["tag"],
                                       tag_basis=f"沿用父场次 {parent['id']} 的 tag",
                                       score_target=tgt, energy_cost=ec)
            sid = sess["id"]
            log(f"按父场次 tag={parent['tag']} 建场次「{sess['name']}」({sid}) "
                f"tgt={tgt} ec={ec}")
        else:
            child = STORE.create_child(params["parent_session"])
            sid = child["id"]
            log(f"父场次 {params['parent_session']} 无 tag, 退回旧式继承建子场次"
                f"「{child['name']}」({sid}) —— 该父场次未迁移, 建议先跑 "
                f"tools/migrate_sessions_to_tag.py", "warn")
    if not sid:
        raise RuntimeError("params 缺 session_id / parent_session")
    start_bot(sid)


def start_bot(sid: str) -> bool:
    """保证 pf_bot 进程在跑, 并对指定场次 /api/start。返回是否 RUNNING。"""
    start_bot_process()
    r = api_post("/api/start", {"session_id": sid})
    if not r or not r.get("ok"):
        raise RuntimeError(f"/api/start 失败: {r!r}")
    ok = wait_running(timeout=60)
    log(f"已开跑: session={sid} RUNNING={ok}", "info" if ok else "warn")
    return ok


def start_bot_process() -> None:
    """拉起 pf_bot 进程; 已在跑则直接复用 (幂等)。"""
    if bot_alive():
        log("pf_bot 已在运行, 复用该进程")
        return
    log("后台启动 pf_bot ...")
    flags = subprocess.CREATE_NO_WINDOW | subprocess.CREATE_NEW_PROCESS_GROUP
    try:
        # 首选: 脱离调度器的作业对象 (需要 job 允许 breakaway), 自己留句柄
        flags |= subprocess.CREATE_BREAKAWAY_FROM_JOB
        # 不再 open(BOT_LOG,"ab") 抢句柄 (2026-10-02): 调度器与 bot 各写一份同一个
        # 文件时, Windows 的 append 不原子 → 丢行(实测 6 进程丢 161/1200)。
        # bot 进程内部 pf_logging.install() 自己写 pf.log, 这里丢弃它的 stdout。
        bot = subprocess.Popen(
            [PY, "tools/pf_bot.py"], cwd=str(PROJECT_ROOT),
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            close_fds=True, creationflags=flags)
        bot  # noqa: B018 (留存引用, 进程随调度器常驻)
    except OSError:
        # breakaway 被宿主作业对象拒绝 (workbuddy/终端等 agent 宿主): 普通 Popen 会让
        # bot 留在宿主作业对象里, 宿主收尾清树时被整树 TerminateProcess —— 2026-09-26
        # 01:07 实锤: workbuddy 自动化跑完 run_new_pf 收尾, 孤儿 pf_bot 无声死亡。
        # 改经 WMI 由 WmiPrvSE 代生, 彻底在调用方作业对象之外 (无句柄, 就绪靠 HTTP 轮询)。
        from pf_env import spawn_detached
        pid = spawn_detached(
            '"%s" tools/pf_bot.py' % PY, str(PROJECT_ROOT), None,
            env_lines=('set "PYTHONUTF8=1"', 'set "PYTHONIOENCODING=utf-8"',
                       'set "PYTHONUNBUFFERED=1"'))
        log(f"breakaway 不被允许, 已改用 WMI 独立进程拉起 pf_bot (pid={pid})")
    if not wait_bot(dead=False, timeout=60):
        raise RuntimeError(f"pf_bot 60s 未就绪 ({WEBUI_PORT} 无响应)")


def wait_running(timeout: int = 60) -> bool:
    t0 = time.time()
    while time.time() - t0 < timeout:
        st = api_get("/api/state")
        if st and st.get("status") == "RUNNING":
            return True
        time.sleep(3)
    return False


def act_stop_pf(params: dict) -> None:
    if not bot_alive():
        log("bot 未在运行, stop_pf 无事可做")
        return
    api_post("/api/stop", {})
    ok = wait_bot(dead=True, timeout=40)
    log(f"stop_pf 完成: 进程退出={ok}")


def act_run_new_pf(params: dict) -> None:
    """凌晨新 PF 全链 (用户定义的路线):

    MuMu → 唯一 device → Skullgirls → 清弹窗 → 大厅 → PF hub → 左右滑动找今天
    新出的 PF(score=0) → **先更新 sgmnow 场次** → **按 tag 归类取条件** → 建当日子场次
    → 起 bot。

    ⚠️ 2026-10-06 起走 tag 归类 (classify_and_create), 不再走
    resolve_arena + create_child 父子继承 (用户口径「不搞父子了, 只搞归类tag」)。
    params.legacy_parent 保留可回退旧路径(调试对照用), 默认新路径。

    params:
      fallback_title  没扫到 score=0 时的兜底场地关键词 (可选)
      dry_run         只归类不建场次不起bot (核对 tag 是否判对)
      no_start        只建场次, 不起 bot (调试用, 默认 False)
      legacy_parent   给父场次 id 则走**旧父子路径**(仅调试对照)
      restart         bot 在跑时先停 (默认 True)
    """
    from pf_scene import PfScene, ensure_mumu
    from pf_env import resolve_adb

    if bot_alive():
        if not params.get("restart", True):
            log("bot 运行中, restart 未开, 跳过", "warn")
            return
        log("bot 运行中, 先停止 (restart=true)")
        api_post("/api/stop", {})
        if not wait_bot(dead=True, timeout=40):
            raise RuntimeError("bot 40s 未退出, 放弃本次触发")

    ensure_mumu(resolve_adb()[0])          # ① 模拟器 + 唯一 device (-v 0)
    scene = PfScene()
    scene.launch_game()                    # ② 打开 Skullgirls
    scene.goto_pf()                        # ③④ 清弹窗 → 大厅 → PF hub
    #   wait_hall 内部已按 促销弹窗X → 结算CONTINUE → 顶栏房子回家 的顺序逃逸,
    #   这就是"清掉所有弹窗"那一环 (不另写视觉逻辑, 复用实测过的链路)

    title = pick_new_arena(scene, params)   # ⑤⑥ 左右滑动 + 选今天新出的 PF

    # ⑦ 按 tag 归类建场次 (内部: 先更新 sgmnow 场次, 再比对归类)
    sid, info = classify_and_create(title, params)
    if not sid:
        return# dry_run: 已在 classify_and_create 里打印
    if params.get("legacy_parent"):
        log("legacy_parent: 新场次已建, 额外再挂一个旧式子场次(调试对照)", "warn")
        from pf_store import STORE
        child = STORE.create_child(params["legacy_parent"])
        log(f"旧式子场次「{child['name']}」({child['id']})", "warn")
    if params.get("no_start"):
        log(f"no_start: 停在已居中的「{title}」, 场次 {sid} 待人工开跑")
        return
    start_bot(sid)


def pick_new_arena(scene, params: dict) -> str:
    """扫轮播找出今天新出的 PF (score=0), 回到那张卡并返回场地名。

    两条硬校验 (2026-09-18 教训: 标题 OCR 失效 -> center 子串误命中 -> 回落到
    错卡, 旧实现直接拿错卡建了子场次):
    1. 回卡用轮播位 (goto_index), 不靠标题匹配;
    2. 居中后复读分数, 不是 0 就中止, 不建场次不起 bot。
    """
    seen = scene.explore()                 # 内部已左滑一圈并恢复初始卡
    zeros = [(i, t) for i, (t, s) in enumerate(seen) if s == 0 and t]
    if zeros:
        idx, target = zeros[0]
        more = [t for _, t in zeros[1:]]
        log(f"扫到 {len(seen)} 张场地卡, score=0 的新场: {target!r} (轮播位 {idx})"
            + (f"; 另有 {more} (取第一个)" if more else ""))
        scene.goto_index(idx)              # 按轮播位原路回去
        title, score = scene.read_center_card()
        if score != 0:
            raise RuntimeError(
                f"居中复核失败: 轮播位 {idx} 复读到 {title!r} score={score}, "
                f"与扫描时的 {target!r} score=0 不符 —— 不建场次 (疑似轮播位漂移/OCR 误判)")
        log(f"居中复核通过: {title!r} (score={score:,})")
    else:
        target = params.get("fallback_title") or ""
        if not target:
            raise RuntimeError(
                f"没有 score=0 的新场 (扫到: {[t for t, _ in seen]}), 且未给 fallback_title")
        log(f"无 score=0 的场, 按 fallback_title 走 {target}", "warn")
        title, score = scene.center(target)
        log(f"已居中新场: {title} (score={score:,})")
    return title


def resolve_arena(title: str, params: dict):
    """场地名 -> (rule, parent_id, kind, note)。映射见 tools/data/arena_rules.json。

    ⚠️ **2026-10-06 起此函数只服务「旧父子路径」**, 新建场次走
    classify_and_create() (pf_artag): tag 优先用 sgmnow 官方分类, 不靠读名字猜。
    保留它是因为 schedule.json 里可能还留着 parent_session 参数的旧任务,
    以及人工在 WebUI 上设的规则仍走 sessions.json。
    """
    import json as _json
    from pathlib import Path as _P

    key = "".join(ch for ch in title.upper() if ch.isalnum())
    table = {}
    try:
        table = (_json.loads((_P(PROJECT_ROOT) / "tools" / "data" /
                              "arena_rules.json").read_text(encoding="utf-8"))
                 .get("arenas") or {})
    except (OSError, _json.JSONDecodeError) as e:
        log(f"读 arena_rules.json 失败: {e}", "warn")

    spec, tkey = None, None
    for name, s in table.items():
        nk = "".join(ch for ch in name.upper() if ch.isalnum())
        # 短 key (<4 字母) 只接受全等: 'M' 做子串匹配会命中 MEDICISHAKEDOWN
        # (2026-09-18 实测 OCR 残串导致错挂父场次)。
        if nk and (nk == key or (len(key) >= 4 and (nk in key or key in nk))):
            spec, tkey = s, name
            break
    if spec is None:
        log(f"场地「{title}」不在 arena_rules.json 里, 按无规则处理 (需人工确认类别)",
            "warn")
        spec = {"rule": None, "parent_id": None, "kind": "未知", "依据": "未收录"}
        tkey = "(未收录)"

    rule = spec.get("rule")
    # 自动绑定只允许元素规则: 游戏里**只有元素 PF 限定队伍** (2026-09-16 用户确认),
    # 角色场/金币场/星助手场/月场都不限定; 而 pf_bot.judge_rule() 对 {"type":"class"}
    # 是恒返回 False 的桩 —— 一旦被自动写进子场次, 就会每场战斗都进"筛选替换"循环,
    # 空烧能量且永远满足不了。人工在 WebUI 上设的规则走 sessions.json, 不经过这里。
    if isinstance(rule, dict) and rule.get("type") != "element":
        log(f"arena_rules[{tkey}] 的规则 {rule} 不是元素类 —— PF 里只有元素场有限定, "
            f"自动路径按无规则处理", "warn")
        rule = None
    elif rule is not None and not isinstance(rule, dict):
        log(f"arena_rules[{tkey}] 的规则结构非法 ({rule!r}), 自动路径按无规则处理", "warn")
        rule = None
    pid = None
    for kw, sid in (params.get("parents") or {}).items():
        if "".join(ch for ch in kw.upper() if ch.isalnum()) in key:
            pid, note = sid, f"父场次由 params.parents 指定 ({kw})"
            break
    if not pid and spec.get("parent_id"):
        pid = spec["parent_id"]
        note = f"父场次来自 arena_rules[{tkey}]"
    if not pid and params.get("default_parent"):
        pid, note = params["default_parent"], "父场次用 params.default_parent"
    if not pid:
        note = "无父场次"
    note = f"{note} | 依据={spec.get('依据','?')}"
    return rule, pid, spec.get("kind") or "未知", note


# ---------------------------------------------------------------- tag 归类建场次

def classify_and_create(title: str, params: dict):
    """场地名 -> (session_id, info)。**新路径: 按 tag 归类建独立场次**。

    取代旧的三段式 `resolve_arena` + `create_child` (父子继承) —— 用户
    2026-10-06 口径「不搞父子了, 只搞归类 tag」。

    流程 (顺序即约束):
      ① **先更新场次** (JJC.today(): 当天有快照就用, 没有现抓 sgmnow) ——
         「先更新场次再做比对」这条顺序缺了就会拿旧数据比, 白跑一天;
      ② pf_artag.classify_arena 按名称相似度定 tag (阈值 0.75);
      ③ 条件一律**从条件表(pf_artag.conditions_of)按 tag 取**, 不继承任何场次;
      ④ 场次名带日期, 便于连刷编排识别。

    归类不中(判不出 tag)时**照样建场次**, 但取最保守条件(4kw/4能量/无规则)
    并打 warn —— 与「漏跑不错跑」同理: 场次本身有效, 只是条件保守, 人工可在
    WebUI 上改。**不因为认不出类别就不建** —— 那会让 bot 无场次可跑。

    params:
      dry_run   只算不建, 返回 (None, info) (调试/核对用)
      no_start  建场次但不起bot (调用方决定后续)
    """
    from jjc_store import JJC, sgm_day
    import pf_artag as T

    # ① 先更新场次 (缺这一步=拿昨天的数据比今天的场地)
    snap = None
    try:
        snap = JJC.today(log=lambda m, level="info": log(m, level))
    except Exception as e:  # noqa: BLE001 抓取失败不该阻断建场次
        log(f"JJC 快照获取失败 ({e}), 将按无数据源归类(条件取最保守)", "warn")
    stale = bool((snap or {}).get("stale"))
    day = (snap or {}).get("day") or sgm_day()
    if snap:
        log(f"sgmnow 快照: 游戏日 {day}"
            + ("  ⚠️ 当日未取到, 显示最近一次归档" if stale else "")
            + f" (revision {snap.get('revision')})")

    # ② 按 tag 归类
    index = T.build_tag_index(snap) if snap else []
    r = T.classify_arena(title, index, day=day)
    log(f"归类「{title}」-> tag={r['tag']} | {r['note']}")
    if r["tag"] == T.UNKNOWN_TAG:
        log(f"场地「{title}」判不出类别, 按最保守条件建场次 "
            f"(4kw/4能量/无规则) —— 需人工在 WebUI 上确认类别", "warn")
    elif r["tag"] in ("character", "element", "rift", "monthly_element") and not r["rule"]:
        log(f"tag={r['tag']} 未能产出规则(缺防守角色/元素名), 该场次将不限队伍",
            "warn")

    info = {"tag": r["tag"], "rule": r["rule"],
            "score_target": r["score_target"],
            "energy_cost": r["energy_cost"], "basis": r["note"],
            "day": day, "stale": stale}

    if params.get("dry_run"):
        log(f"dry_run: 不建场次 (tag={r['tag']} tgt={info['score_target']} "
            f"ec={info['energy_cost']} rule={info['rule']})")
        return None, info

    # ③ 按 tag 取条件建独立场次 (不继承任何父场次)
    from pf_store import STORE
    name = f"{title} {time.strftime('%m-%d')}"
    sess = STORE.create_tagged(
        name, info["rule"], tag=info["tag"], tag_basis=info["basis"],
        score_target=info["score_target"], energy_cost=info["energy_cost"])
    log(f"建场次「{sess['name']}」({sess['id']}) tag={info['tag']} "
        f"tgt={info['score_target']} ec={info['energy_cost']} rule={info['rule']}")
    return sess["id"], info


def act_explore(params: dict) -> None:
    from pf_scene import PfScene, ensure_mumu
    from pf_env import resolve_adb

    if bot_alive():
        log("explore 前先停 bot")
        api_post("/api/stop", {})
        if not wait_bot(dead=True, timeout=40):
            raise RuntimeError("bot 40s 未退出, 放弃 explore")
    ensure_mumu(resolve_adb()[0])
    scene = PfScene()
    scene.launch_game()
    scene.goto_pf()
    scene.explore()  # 报告随 scene log 落 stdout; 关键行亦在 schedule.log
    if params.get("resume") and params.get("session_id"):
        log(f"explore 后恢复挂机: {params['session_id']}")
        act_run_pf({"session_id": params["session_id"], "restart": False})


ACTIONS = {"run_pf": act_run_pf, "stop_pf": act_stop_pf, "explore": act_explore,
           "run_new_pf": act_run_new_pf}

# 只串行化本进程内的调度动作；跨进程设备所有权仍依赖现有 bot 停止/等待协议。
_ACTION_LOCK = threading.Lock()


def _execute_action(action: str, params: dict) -> None:
    if not _ACTION_LOCK.acquire(blocking=False):
        log(f"action「{action}」等待前一个调度动作完成", "warn")
        _ACTION_LOCK.acquire()
    try:
        fn = ACTIONS.get(action)
        if not fn:
            raise RuntimeError(f"未知 action: {action!r}")
        # bot_alive/restart 等实时检查留在锁内执行，不能使用排队前的设备状态。
        fn(params)
    finally:
        _ACTION_LOCK.release()


# ---------- 调度 ----------

def load_jobs() -> list:
    try:
        with open(SCHEDULE_PATH, encoding="utf-8") as f:
            return json.load(f).get("jobs") or []
    except (OSError, json.JSONDecodeError):
        return []


def load_fired() -> dict:
    try:
        with open(STATE_PATH, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, json.JSONDecodeError):
        return {}


def mark_fired(name: str) -> None:
    fired = load_fired()
    fired[name] = time.strftime("%Y-%m-%d")
    tmp = STATE_PATH.with_suffix(".json.tmp")
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(fired, f, ensure_ascii=False)
    tmp.replace(STATE_PATH)


def job_due(job: dict, now: dt.datetime | None = None) -> bool:
    now = now or dt.datetime.now()
    if job.get("date") and job["date"] != now.strftime("%Y-%m-%d"):
        return False  # 一次性任务: 仅指定日期当天生效
    hh, mm = str(job.get("time", "")).split(":")[:2]
    start = now.replace(hour=int(hh), minute=int(mm), second=0, microsecond=0)
    grace = int(job.get("grace_minutes", 90))
    if not (start <= now <= start + dt.timedelta(minutes=grace)):
        return False
    days = job.get("days", "daily")
    if days != "daily":
        days = [WEEKDAYS[d.lower()[:3]] for d in days if d.lower()[:3] in WEEKDAYS]
        if now.weekday() not in days:
            return False
    return load_fired().get(job.get("name", "")) != now.strftime("%Y-%m-%d")


def fire(job: dict) -> threading.Thread:
    name = job.get("name", "?")
    action = job.get("action", "")
    mark_fired(name)  # 先记账再执行, 长任务不会重复触发
    log(f"==== 触发任务「{name}」({action}) ====", "warn")

    def run():
        try:
            _execute_action(action, job.get("params") or {})
            log(f"==== 任务「{name}」结束 ====")
        except Exception as e:  # noqa: BLE001
            log(f"任务「{name}」失败: {e}", "err")

    worker = threading.Thread(target=run, daemon=True)
    worker.start()
    return worker


def scan_loop() -> None:
    log(f"调度器启动, {len(load_jobs())} 个任务, 每 20s 扫描")
    while True:
        try:
            for job in load_jobs():
                if job_due(job):
                    fire(job)
        except Exception as e:  # noqa: BLE001
            log(f"扫描异常: {e}", "err")
        time.sleep(20)


def main() -> int:
    if "--list" in sys.argv:
        for j in load_jobs():
            print(f"{j.get('time')} {j.get('name')} -> {j.get('action')} {j.get('params')}")
        return 0
    if "--action" in sys.argv:
        # 自包含入口: 不依赖 schedule.json, 参数直接跟在后面 (一次凌晨任务用)
        idx = sys.argv.index("--action")
        action = sys.argv[idx + 1]
        fn = ACTIONS.get(action)
        if not fn:
            print(f"未知 action: {action} (可用: {', '.join(ACTIONS)})")
            return 1
        params = {}
        if "--params" in sys.argv:
            params = json.loads(sys.argv[sys.argv.index("--params") + 1])
        # 硬看门狗: 实测 explore() 会在 MAA OCR 的 post_recognition().wait() 里
        # 永久卡住 (2026-09-16 凌晨 9 分钟无进展, 只留下 6 张截图)。那次是手动杀的,
        # 但凌晨无人值守时必须自己兜住 —— 超时就判死退出, 别把设备锁到白天。
        deadline = int(params.pop("_timeout", 480))
        log(f"==== 直接执行 action「{action}」params={params} "
            f"超时上限={deadline}s ====", "warn")
        box = {}

        def _run():
            try:
                _execute_action(action, params)
                log(f"==== action「{action}」结束 ====")
                box["ok"] = True
            except Exception as e:  # noqa: BLE001
                log(f"action「{action}」失败: {e}", "err")
                box["ok"] = False

        th = threading.Thread(target=_run, daemon=True)
        th.start()
        th.join(deadline)
        if th.is_alive():
            log(f"action「{action}」超过 {deadline}s 未完成 —— 判定卡死 "
                f"(多半是 MAA OCR 的 wait() 不返回), 强制退出", "err")
            sys.stdout.flush()
            os._exit(3)          # 线程杀不掉, 只能整进程退; 子进程/adb 可能残留
        return 0 if box.get("ok") else 1
    if "--fire" in sys.argv:
        name = sys.argv[sys.argv.index("--fire") + 1]
        job = next((j for j in load_jobs() if j.get("name") == name), None)
        if not job:
            print(f"任务不存在: {name}")
            return 1
        # 单次入口必须等动作完成；守护线程不会在主进程退出后继续运行。
        # 常驻 scan_loop 仍异步触发任务，--action 保留自己的超时看门狗。
        fire(job).join()
        return 0
    scan_loop()
    return 0


if __name__ == "__main__":
    sys.exit(main())
