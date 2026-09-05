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
import subprocess
import sys
import threading
import time
import urllib.request
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT_ROOT / "tools"))

SCHEDULE_PATH = PROJECT_ROOT / "debug" / "pf" / "schedule.json"
STATE_PATH = PROJECT_ROOT / "debug" / "pf" / "schedule_state.json"
SCHED_LOG = PROJECT_ROOT / "debug" / "pf" / "schedule.log"
BOT_LOG = PROJECT_ROOT / "debug" / "pf" / "bot_stdout.log"
PY = sys.executable  # 调度器必须用 anaconda python 启动; bot 子进程沿用同一解释器
API = "http://127.0.0.1:8787"

WEEKDAYS = {"mon": 0, "tue": 1, "wed": 2, "thu": 3, "fri": 4, "sat": 5, "sun": 6}


def log(msg: str, level: str = "info") -> None:
    line = f"[{time.strftime('%m-%d %H:%M:%S')}][{level}] {msg}"
    print(line, flush=True)
    try:
        with open(SCHED_LOG, "a", encoding="utf-8") as f:
            f.write(line + "\n")
    except OSError:
        pass


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
    return api_get("/api/state") is not None


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
        child = STORE.create_child(params["parent_session"])
        sid = child["id"]
        log(f"已建子场次「{child['name']}」({sid})")
    if not sid:
        raise RuntimeError("params 缺 session_id / parent_session")

    log("后台启动 pf_bot ...")
    lf = open(BOT_LOG, "ab")
    flags = subprocess.CREATE_NO_WINDOW | subprocess.CREATE_NEW_PROCESS_GROUP
    try:
        # 脱离调度器的作业对象: 调度器被整树强杀时 bot 不陪葬 (需要 job 允许 breakaway)
        flags |= subprocess.CREATE_BREAKAWAY_FROM_JOB
        bot = subprocess.Popen(
            [PY, "tools/pf_bot.py"], cwd=str(PROJECT_ROOT),
            stdout=lf, stderr=subprocess.STDOUT, close_fds=True, creationflags=flags)
    except OSError:
        log("breakaway 不被允许, 降级普通启动 (调度器被树杀时 bot 会连带)", "warn")
        bot = subprocess.Popen(
            [PY, "tools/pf_bot.py"], cwd=str(PROJECT_ROOT),
            stdout=lf, stderr=subprocess.STDOUT, close_fds=True,
            creationflags=subprocess.CREATE_NO_WINDOW | subprocess.CREATE_NEW_PROCESS_GROUP)
    bot  # noqa: B018 (留存引用, 进程随调度器常驻)
    if not wait_bot(dead=False, timeout=60):
        raise RuntimeError("pf_bot 60s 未就绪 (8787 无响应)")
    r = api_post("/api/start", {"session_id": sid})
    if not r or not r.get("ok"):
        raise RuntimeError(f"/api/start 失败: {r!r}")
    ok = wait_running(timeout=60)
    log(f"run_pf 完成: session={sid} RUNNING={ok}",
        "info" if ok else "warn")


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


ACTIONS = {"run_pf": act_run_pf, "stop_pf": act_stop_pf, "explore": act_explore}

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


def fire(job: dict) -> None:
    name = job.get("name", "?")
    action = job.get("action", "")
    mark_fired(name)  # 先记账再执行, 长任务不会重复触发
    log(f"==== 触发任务「{name}」({action}) ====", "warn")

    def run():
        try:
            fn = ACTIONS.get(action)
            if not fn:
                raise RuntimeError(f"未知 action: {action!r}")
            fn(job.get("params") or {})
            log(f"==== 任务「{name}」结束 ====")
        except Exception as e:  # noqa: BLE001
            log(f"任务「{name}」失败: {e}", "err")

    threading.Thread(target=run, daemon=True).start()


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
    if "--fire" in sys.argv:
        name = sys.argv[sys.argv.index("--fire") + 1]
        job = next((j for j in load_jobs() if j.get("name") == name), None)
        if not job:
            print(f"任务不存在: {name}")
            return 1
        fire(job)
        time.sleep(5)  # 给线程起跑的机会; 长任务继续在后台
        return 0
    scan_loop()
    return 0


if __name__ == "__main__":
    sys.exit(main())
