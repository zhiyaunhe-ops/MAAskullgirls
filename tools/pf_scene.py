"""SGM 场景导航: 启动 MuMu/游戏 → 大厅 → PF hub; explore=轮播扫分找 score=0 的场地。

用法 (anaconda python, 仓库根目录):
  python tools/pf_scene.py goto             # MuMu(若未开)→游戏→大厅→PF hub
  python tools/pf_scene.py explore          # goto 后左滑扫每个居中场地的 SCORE,
                                            #   报告 score=0 的场地, 结束回到初始居中卡
  python tools/pf_scene.py goto --skip-mumu # 跳过 MuMu 状态检查/启动 (已在跑时加速)

注意: pf_bot 运行中不要跑本脚本 (会跟 bot 抢点击)。
"""
from __future__ import annotations

import difflib
import json
import re
import subprocess
import sys
import time
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT_ROOT / "tools"))

from pf_bot import PROJECT_ROOT as ROOT  # noqa: F401,E402  (统一 sys.path 语义)
from pf_bot import PfBot  # noqa: E402
from pf_env import resolve_adb  # noqa: E402

GAME_PKG = "com.autumn.skullgirls"
IMG = "pf/"
TPL_HALL_PRIZE = IMG + "hall_prize_fights.png"  # 大厅 PRIZE FIGHTS 菱形
TPL_HUB_PLAY = IMG + "hub_play.png"             # PF hub 居中卡 PLAY!
TPL_CONTINUE = IMG + "pf_continue_btn.png"      # 结算 CONTINUE (逃出残局用)
TPL_RESULT_CONTINUE = IMG + "result_continue.png"
TPL_SCENE_X = IMG + "scene_popup_x.png"         # 大厅促销弹窗 X (BACK TO SCHOOL 等)

ROI_HUB_PLAY = (500, 400, 780, 530)   # x0,y0,x1,y1
ROI_RESULT = (400, 570, 980, 700)
ROI_TOPRIGHT = (1040, 15, 1260, 115)
ROI_SCENE_X = (950, 30, 1240, 180)    # 促销弹窗右上 X
ROI_CARD_SCORE = (505, 138, 775, 185)  # 居中卡 "SCORE: n"
ROI_CARD_TITLE = (500, 290, 780, 378)  # 居中卡场地名 (可两行)

HOME_BTN = (115, 37)   # 顶栏房子: 回大厅
HALL_TIMEOUT = 150.0   # 冷启动含 CONNECTING
HUB_TIMEOUT = 30.0


def log(msg: str, level: str = "info") -> None:
    print(f"[{time.strftime('%H:%M:%S')}][{level}] {msg}", flush=True)


def ensure_mumu(adb_path: str) -> None:
    """MuMu 未启动则拉起 0 号设备, 等到 start_finished (须在 PfScene 构建前调用,
    否则 setup() 的 adb 连接会先炸)。"""
    mgr = str(Path(adb_path).with_name("MuMuManager.exe"))
    nx_main = Path(adb_path).with_name("MuMuNxMain.exe")
    for _ in range(2):
        p = subprocess.run([mgr, "info", "-v", "0"],
                           capture_output=True, text=True, timeout=15)
        try:
            info = json.loads(p.stdout)
        except json.JSONDecodeError:
            info = {}
        if info.get("player_state") == "start_finished":
            log("MuMu 设备已就绪")
            return
        log("启动 MuMu 0 号设备 ...")
        subprocess.Popen([str(nx_main), "-v", "0"], cwd=str(nx_main.parent))
        for _ in range(60):
            time.sleep(2)
            q = subprocess.run([mgr, "info", "-v", "0"],
                               capture_output=True, text=True, timeout=15)
            try:
                if json.loads(q.stdout).get("player_state") == "start_finished":
                    log("MuMu 设备已就绪")
                    return
            except json.JSONDecodeError:
                pass
    raise RuntimeError("MuMu 120s 内未就绪")


class PfScene:
    def __init__(self) -> None:
        self.bot = PfBot()
        self.bot.setup()  # adb 连接 + 资源 + tasker (setup 前需 MuMu 已启动)
        adb_path, _ = resolve_adb()
        self.adb = adb_path
        self.mgr = str(Path(adb_path).with_name("MuMuManager.exe"))

    # ---------- 设备层 ----------

    def adb_shell(self, cmd: str) -> str:
        p = subprocess.run([self.adb, "-s", "127.0.0.1:16384", "shell", cmd],
                           capture_output=True, text=True, timeout=30)
        return p.stdout.strip()

    def ensure_mumu(self) -> None:
        """兼容旧调用: 转发模块级 ensure_mumu。"""
        ensure_mumu(self.adb)

    def launch_game(self) -> None:
        out = self.adb_shell(f"monkey -p {GAME_PKG} "
                             f"-c android.intent.category.LAUNCHER 1")
        if "Events injected: 1" not in out:
            raise RuntimeError(f"游戏启动失败: {out!r}")
        log("已发出游戏启动指令, 等大厅 ...")

    # ---------- 识别/操作 (复用 PfBot 基础设施) ----------

    def snap(self, tag: str = "scene"):
        return self.bot.snap(tag)

    def tap(self, x: int, y: int) -> None:
        self.bot.controller.post_click(x, y).wait()

    def swipe_left(self) -> None:
        # MAA post_swipe 瞬时释放会被吸附轮播弹回, 用 adb input swipe (实机验证过);
        # 340px+600ms: 520px 大步会因惯性一次跳 2 张卡, 中间场场地会被漏扫
        self.adb_shell("input swipe 900 400 560 400 600")

    def swipe_right(self) -> None:
        self.adb_shell("input swipe 560 400 900 400 600")

    def match(self, tpl: str, roi: tuple, th: float = 0.72):
        return self.bot.match_tpl(self.snap(), tpl, roi, th=th)

    def ocr(self, roi: tuple) -> str:
        return self.bot.ocr_text(self.snap(), roi)

    KNOWN_TITLES = [
        "EYE OF THE STORM", "AGAINST THE WIND", "TRIAL BY FIRE", "THE BIG THAW",
        "A CLASS OF ONE'S OWN", "SEEING STARS", "DIAMOND NIGHT'S GHOUL",
        "MEDICI SHAKEDOWN", "ROSHAMBOH", "GOLD RUSH", "BELLE OF THE BRAWL",
    ]

    def read_center_card(self) -> tuple[str, int]:
        """读居中场地的 (名称, 分数)。名称优先匹配已知场地名。"""
        raw = self.ocr(ROI_CARD_SCORE)
        m = re.search(r"[\d,]+", raw)
        score = int(m.group().replace(",", "")) if m else -1
        title = re.sub(r"[^A-Z]", "", self.ocr(ROI_CARD_TITLE).upper())
        close = difflib.get_close_matches(
            title, [re.sub(r"[^A-Z]", "", k) for k in self.KNOWN_TITLES],
            n=1, cutoff=0.55)
        if close:
            return self.KNOWN_TITLES[[re.sub(r"[^A-Z]", "", k) for k in
                                      self.KNOWN_TITLES].index(close[0])], score
        return title, score

    # ---------- 场景 ----------

    def wait_hall(self) -> tuple[int, int]:
        """等大厅 (PRIZE FIGHTS 菱形可见), 返回菱形中心。

        优先级: 促销弹窗X → 结算CONTINUE → 大厅菱形 → 房子回家。
        吸附位不固定 → 全屏搜。
        """
        t0 = time.time()
        tried_home = 0
        n = 0
        while time.time() - t0 < HALL_TIMEOUT:
            n += 1
            img = self.snap()
            x = self.bot.match_tpl(img, TPL_SCENE_X, ROI_SCENE_X, th=0.8)
            if x:
                log("促销弹窗, 点 X 关闭", "warn")
                self.tap(*x)
                time.sleep(1.8)
                continue
            cont = (self.bot.match_tpl(img, TPL_RESULT_CONTINUE, ROI_RESULT, th=0.7)
                    or self.bot.match_tpl(img, TPL_CONTINUE, ROI_TOPRIGHT, th=0.7))
            if cont:
                log("残局结算页, 点 CONTINUE 脱离", "warn")
                self.tap(*cont)
                time.sleep(2.5)
                continue
            box = self.bot.match_tpl(img, TPL_HALL_PRIZE, (0, 0, 0, 0), th=0.72)
            if box:
                log(f"大厅就绪, PRIZE FIGHTS @ {box}")
                return box
            if tried_home < 3 and n % 4 == 0:
                log("不在大厅, 点顶栏房子回家", "warn")
                self.tap(*HOME_BTN)
                tried_home += 1
                time.sleep(3.0)
            else:
                if n % 5 == 0:
                    log(f"等待大厅 ... ({int(time.time() - t0)}s)")
                time.sleep(2.0)
        raise RuntimeError("150s 未回到大厅 (PRIZE FIGHTS 菱形不可见)")

    def goto_pf(self) -> None:
        """大厅 → PF hub (居中场地方程页)。单点未生效则补点 (首点居中再点生效)。"""
        cx, cy = self.wait_hall()
        t0 = time.time()
        while time.time() - t0 < HUB_TIMEOUT:
            img = self.snap()
            if self.bot.match_tpl(img, TPL_HUB_PLAY, ROI_HUB_PLAY, th=0.7):
                log("PF hub 就绪")
                return
            box = self.bot.match_tpl(img, TPL_HALL_PRIZE, (0, 0, 0, 0), th=0.72)
            if box:
                self.tap(*box)
                log(f"点 PRIZE FIGHTS @ {box}")
            time.sleep(2.5)
        raise RuntimeError("30s 未进入 PF hub")

    def center(self, keyword: str) -> tuple[str, int]:
        """在 PF hub 轮播中把名称匹配 keyword 的场地转到居中, 返回 (名称, 分数)。"""
        kw = re.sub(r"[^A-Z]", "", keyword.upper())
        for _ in range(10):
            title, score = self.read_center_card()
            if kw and kw in re.sub(r"[^A-Z]", "", title):
                log(f"已居中: {title} (score={score:,})")
                return title, score
            self.swipe_left()
            time.sleep(1.8)
        raise RuntimeError(f"10 步内未转到场地 {keyword!r}")

    def explore(self) -> list[tuple[str, int]]:
        """左滑扫场轮播: 收集每个居中场地的分数, 报告 score=0, 回到初始居中卡。"""
        seen: list[tuple[str, int]] = []
        for i in range(10):
            title, score = self.read_center_card()
            log(f"居中卡[{i}] {title!r} score={score}")
            key = re.sub(r"[^A-Z0-9]", "", title)[:14]
            if any(key and key == re.sub(r"[^A-Z0-9]", "", t)[:14] for t, _ in seen):
                log("转回已见场地, 扫描结束")
                break
            seen.append((title, score))
            self.swipe_left()
            time.sleep(1.8)
        zeros = [t for t, s in seen if s == 0]
        log("==== PF 场地扫描结果 ====")
        for t, s in seen:
            mark = "  ← score=0" if s == 0 else ""
            log(f"  {t or '(未识别)'}: {s:,}{mark}")
        if zeros:
            log(f"score=0 的场地: {', '.join(zeros)}")
        # 恢复初始居中卡: 右滑直到回到第一张
        first = re.sub(r"[^A-Z0-9]", "", seen[0][0])[:14] if seen else ""
        for i in range(len(seen) + 2):
            title, _ = self.read_center_card()
            if re.sub(r"[^A-Z0-9]", "", title)[:14] == first:
                log(f"已恢复初始居中卡: {seen[0][0]}")
                break
            self.swipe_right()
            time.sleep(1.8)
        return seen


def main() -> int:
    mode = sys.argv[1] if len(sys.argv) > 1 else "goto"
    skip_mumu = "--skip-mumu" in sys.argv
    if not skip_mumu:
        # MuMu 必须先就绪: PfScene 构建时会连 adb, 模拟器没开就会炸
        ensure_mumu(resolve_adb()[0])
    scene = PfScene()
    scene.launch_game()
    scene.goto_pf()
    if mode == "explore":
        scene.explore()
    elif mode == "center":
        kw = sys.argv[2] if len(sys.argv) > 2 else ""
        scene.center(kw)
    else:
        log("goto 完成, 停在 PF hub")
    return 0


if __name__ == "__main__":
    sys.exit(main())
