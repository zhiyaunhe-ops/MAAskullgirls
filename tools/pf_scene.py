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

import cv2  # noqa: E402  (find_modal_x_cv 用; anaconda python 必备)
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
TPL_MODAL_X = IMG + "popup_close_x.png"         # 弹窗通用关闭 X (每日奖励/HEADLINER 等)
TPL_MODAL_X_ROUND = IMG + "popup_close_x_round.png"  # 圆环样式关闭 X (6-DAY DAILY PASS 等)

ROI_HUB_PLAY = (500, 400, 780, 530)   # x0,y0,x1,y1
ROI_RESULT = (400, 570, 980, 700)
ROI_TOPRIGHT = (1040, 15, 1260, 115)
ROI_SCENE_X = (950, 30, 1240, 180)    # 促销弹窗右上 X
# 启动时的弹窗栈是一串, X 的位置随各弹窗面板高度变:
#   DAILY LOGIN BONUS      X 中心 (832, 30)
#   HEADLINER DAILY PASS   X 中心 (832,189)   <- 关掉上一个才露出这个
#   6-DAY DAILY PASS       X 中心 (855,132)   <- 2026-09-17 01:25 run_pf 卡死实证
# 所以 ROI 必须放高到 300 才够; 横向上限取 1000, 把 OPTIONS 面板的 X (1164,14) 排除,
# 否则会误点 OPTIONS. 实测: 本 ROI 内弹窗帧 0.993~1.000, 30 张大堂/战斗图最高仅 0.529,
# 且每个弹窗内只有一个 >0.70 的峰 —— 阈值 0.90 分离得很干净.
ROI_MODAL_X = (700, 0, 1000, 300)
# X 按钮有两种图形, 都是同一 "通用关闭" 语义:
#   方形按钮 X  (popup_close_x.png)       弹窗帧 0.987+, 非弹窗 340 张最高 0.529, 零误报
#   金圆环 X    (popup_close_x_round.png) 6-DAY 弹窗 1.000; 但筛选面板的关闭钮是同款
#             圆环 (flt_1_panel 0.968), 只能抬阈值到 0.95 压住 —— 且那些误报对象本身就是
#             "可关闭面板的关闭钮", 点了无害; wait_hall 阶段也不会出现筛选面板.
ROI_MODAL_X_ROUND_TH = 0.95
ROI_CARD_SCORE = (505, 138, 775, 185)  # 居中卡 "SCORE: n"
ROI_CARD_TITLE = (500, 290, 780, 378)  # 居中卡场地名 (可两行)
# 标题兜底 ROI: 卡面版式不统一, 标题高度不固定。2026-09-18 实测 DEATH METTLE 的
# 标题在 y≈265-295 (比 MEDICI SHAKEDOWN 的 y≈325-365 高 ~60px), 原 ROI 只框到
# 倒计时 '02D:23H:54M', OCR 剥完非字母只剩 'M'。
# ⚠️ 兜底区域不能贪大: MAA OCR 对大/暗区域会整体失灵 (实测 y235-380 读空串),
#    (520,255,780,310) 是扫描多组 ROI 后唯一稳定读出该标题的窗口; 且在
#    A CLASS / AGAINST / MEDICI 各帧上读出的是垃圾 ('PSOOS'/'M') 但都不命中
#    已知名 —— 不会误匹配。只在原 ROI 未命中已知名时才启用, 既有卡行为不变。
ROI_CARD_TITLE_FALLBACK = (520, 255, 780, 310)

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

    def find_modal_x_cv(self, img):
        """cv2 版弹窗关闭 X 检测, 命中返回中心 (x, y), 否则 None。

        为什么不走 bot.match_tpl (MAA TemplateMatch): 2026-09-17 实测, 圆环 X
        模板 MAA 返回 (745,132) —— 弹窗面板内部装饰区, 而真 X 在 (855,132);
        同一张图 cv2 TM_CCOEFF_NORMED 的最高分恰在 (833,110)=1.0。两套引擎的
        命中语义不同 (MAA 疑似 first-hit, 且 method/色彩空间有差异), 所有以
        cv2 做的阈值验证对 MAA 引擎不成立。这里直接用 cv2, 与验证数字同源。
        """
        img_dir = ROOT / "assets" / "resource" / "base" / "image"
        for tpl_path, th in ((TPL_MODAL_X, 0.90), (TPL_MODAL_X_ROUND, 0.95)):
            tpl = cv2.imread(str(img_dir / tpl_path))
            if tpl is None:
                continue
            x0, y0, x1, y1 = ROI_MODAL_X
            sub = img[y0:y1, x0:x1]
            if sub.shape[0] < tpl.shape[0] or sub.shape[1] < tpl.shape[1]:
                continue
            res = cv2.matchTemplate(sub, tpl, cv2.TM_CCOEFF_NORMED)
            _, mx, _, ml = cv2.minMaxLoc(res)
            if mx >= th:
                return (ml[0] + x0 + tpl.shape[1] // 2,
                        ml[1] + y0 + tpl.shape[0] // 2)
        return None

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
        "A CLASS OF ONE'S OWN", "SEEING STARS", "NIGHT'S GHOUL", "BLOOD SPORT",
        "MEDICI SHAKEDOWN", "ROSHAMBOH", "GOLD RUSH", "BELLE OF THE BRAWL",
        "DEATH METTLE",
    ]

    # 卡片上的"难度层级"文字与场次名**同框**: ROI_CARD_TITLE=(500,290,780,378) 实测同时
    # 框到层级小字与场地名大字 (2026-09-16 用 hub_scan/02.png 裁图实证)。所以 OCR 出来的
    # 是 "DIAMOND BLOOD SPORT" 这类带层级前缀的串 —— 历史记录里那个
    # "DIAMOND NIGHT'S GHOUL" 就是这么来的 (DIAMOND 是层级, 不属于名字)。
    # 但**不能无脑删前缀**: "GOLD RUSH" 本身就以 GOLD 开头。所以做法是把
    # "原串 / 去前缀串" 都当候选, 谁跟已知场地名更接近用谁。
    TIER_PREFIXES = ("BRONZE", "SILVER", "GOLD", "DIAMOND")

    def _title_cands(self, raw: str) -> tuple[str, list[str], bool]:
        """OCR 原文 -> (字母归一串, [原串, 去层级前缀串...], 是否剥过前缀)。"""
        title = re.sub(r"[^A-Z]", "", raw.upper())
        cands = [title]
        stripped = False
        for pre in self.TIER_PREFIXES:
            if title.startswith(pre) and len(title) > len(pre) + 2:
                cands.append(title[len(pre):])
                stripped = True
        return title, cands, stripped

    def _match_known(self, cands: list[str]) -> str | None:
        keys = [re.sub(r"[^A-Z]", "", k) for k in self.KNOWN_TITLES]
        best, best_ratio = None, 0.0
        for cand in cands:
            close = difflib.get_close_matches(cand, keys, n=1, cutoff=0.55)
            if close:
                ratio = difflib.SequenceMatcher(None, cand, close[0]).ratio()
                if ratio > best_ratio:
                    best_ratio, best = ratio, self.KNOWN_TITLES[keys.index(close[0])]
        return best

    @staticmethod
    def parse_score_ocr(raw: str) -> int:
        """把居中卡 SCORE 行的 OCR 原文解析成分数。读不出返回 -1。

        坑史 (按发现顺序):
          ① 20:17 之前 —— 新场分数是 "SCORE: 0", OCR 把这个 0 认成**字母 O**,
             整行变 'SCORE: O' / 'SCOREO', 一个数字都搜不到 -> score=-1 ->
             run_new_pf 报「没有 score=0 的新场」。
             修法: 先剥 SCORE 标签, 再把 O/Q -> 0。**必须先后剥标签**, 否则标签里
             那个 O 会被当成数字 0, 得到 '0' + 'SCORE' 的错值。
          ② 2026-09-19 复现 —— 原实现只在这一步失败时 (m is None) 才走归一化分支,
             但 'SCOREO' 里 **没有数字**, re.search(r"[\\d,]+") 确实返回 None,
             所以按理该进分支……实测却仍报「无 score=0」。根因是标签正则
             `score\\s*:?` 紧跟的 `\\s*:?` 允许零宽匹配, 而 `(?i)` 下 'SCOREO' 的
             'SCORE' 被剥掉后剩 'O' -> 0 -> m 命中 '0', **这一步其实是对的**;
             真正漏掉的是下一层: '0' 解析成 0 之后, 上层 pick_new_arena 用
             `score == 0` 判新场 —— 逻辑没问题。
             => 结论: 该分支本身可用, 但**只在完全没有数字时才触发**, 覆盖面太窄。
             例如 OCR 把 'SCORE: 0' 读成 'SCORE: 6' / 'SCORE: 8' 时,
             re.search 命中 '6'/'8', 分支不触发, 直接得 score=6 —— 与真值 0 不符,
             新场被漏判。这就是 2026-09-19 01:02 实测 SEEING STARS 卡 score=6 的来源
             (截图 debug/pf/run/0919_010200/0018_scene.jpg 卡面确为 'SCORE: 0')。
        本实现对**两条路径都做归一化**, 并加一条领域约束兜底:
          SGM 的 PF 分数不可能是个位数 (< 100 的 score 只可能是 OCR 把 0 读错),
          个位数一律判 0。
        """
        if not raw:
            return -1
        # 剥标签 (大小写不敏感, 允许 'SCORE' 与 'SCORE:' 两种写法)
        body = re.sub(r"(?i)\bscore\b\s*:?", " ", raw)
        # 字母 -> 数字 (0/O/Q 互认, 以及常见的 6/8/9 与 0 互认)
        norm = re.sub(r"[OQ]", "0", body)
        norm = re.sub(r"[^0-9,]", " ", norm).strip()
        # 取最长的数字串作为分数 (留 ',' 以便千分位)
        chunks = [c for c in re.split(r"\s+", norm) if c]
        if not chunks:
            return -1
        best = max(chunks, key=lambda c: len(c.replace(",", "")))
        digits = best.replace(",", "")
        if not digits:
            return -1
        try:
            score = int(digits)
        except ValueError:
            return -1
        # 领域约束: 个位数只可能是 OCR 把 0 读错 (SGM 分数没有个位数)
        if score < 10:
            log(f"分数 OCR 得个位数 {score}, 按 0 处理 (原文 {raw!r})", "warn")
            return 0
        return score

    def read_center_card(self) -> tuple[str, int]:
        """读居中场地的 (名称, 分数)。名称优先匹配已知场地名。

        层级前缀不直接删, 而是展开成候选再择优 —— 见 TIER_PREFIXES 上面的说明。
        """
        score = self.parse_score_ocr(self.ocr(ROI_CARD_SCORE))
        raw_title = self.ocr(ROI_CARD_TITLE)
        full, cands, stripped = self._title_cands(raw_title)
        best = self._match_known(cands)
        if best is None:
            # 原 ROI 未命中已知名 -> 卡面版式可能不同, 用加高 ROI 兜底再试一次
            # (2026-09-18 DEATH METTLE: 标题比常规版式高 ~60px, 原 ROI 框到倒计时)。
            raw2 = self.ocr(ROI_CARD_TITLE_FALLBACK)
            full2, cands2, stripped2 = self._title_cands(raw2)
            best2 = self._match_known(cands2)
            if best2 is not None:
                log(f"标题 ROI 未命中, 加高 ROI 兜底命中: {raw_title!r} -> {raw2!r} "
                    f"-> {best2!r}", "warn")
                full, cands, stripped = full2, cands2, stripped2
                best = best2
        if best:
            if stripped:
                log(f"场地名带层级前缀, 归一为 {best!r}: OCR 原文 {full}", "warn")
            return best, score
        # 未命中已知名: 返回去前缀那版 (带层级前缀的串拿去找 arena_rules 没意义)
        return (cands[-1] if len(cands) > 1 else full), score

    # ---------- 场景 ----------

    def wait_hall(self) -> tuple[int, int]:
        """等大厅 (PRIZE FIGHTS 菱形可见), 返回菱形中心。

        优先级: 弹窗通用X → 促销弹窗X → 结算CONTINUE → 大厅菱形 → 房子回家。
        吸附位不固定 → 全屏搜。

        通用 X 放最前: 冷启动时会连着弹一串模态弹窗, 实测 2026-09-16 是
        DAILY LOGIN BONUS (X@832,30) 关掉后紧跟 HEADLINER DAILY PASS
        (X@832,189), 它们都盖住大厅菱形。原实现只认促销弹窗的 X, 于是整条
        链路每次都在 150s 后以"未回到大厅"失败。这里每轮只关一个, 靠循环
        逐个剥掉, 不预设弹窗个数。
        """
        t0 = time.time()
        tried_home = 0
        n = 0
        last_modal = None
        modal_streak = 0
        while time.time() - t0 < HALL_TIMEOUT:
            n += 1
            img = self.snap()
            modal = self.find_modal_x_cv(img)
            if modal:
                # 防饿死: 同一坐标的模态 X 连点 5 次还在, 说明它关不掉 ——
                # 要么是 cv2 对非弹窗界面的 X 形图案误匹配 (2026-09-18 实测: 残留的
                # VS 编队界面在 (901,77) 被误命中, 点击无效且每轮短路, 把下面
                # "点房子回家"分支饿死, 150s 超时), 要么输入通道整体失效。
                # 此时改按返回键 + 房子回家, 别再原地打转。
                if modal == last_modal:
                    modal_streak += 1
                else:
                    last_modal, modal_streak = modal, 1
                if modal_streak >= 5:
                    log(f"模态 X @{modal} 连点 {modal_streak} 次未生效, "
                        f"疑似误匹配/点击无效 —— 按返回键+点房子脱离", "warn")
                    self.adb_shell("input keyevent KEYCODE_BACK")
                    time.sleep(2.0)
                    self.tap(*HOME_BTN)
                    tried_home += 1
                    last_modal, modal_streak = None, 0
                    time.sleep(3.0)
                    continue
                log(f"模态弹窗 (每日奖励/通行证等), 点 X 关闭 @ {modal}", "warn")
                self.tap(*modal)
                time.sleep(1.8)
                continue
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
        """在 PF hub 轮播中把名称匹配 keyword 的场地转到居中, 返回 (名称, 分数)。

        短关键词 (<4 字母) 只接受全等: 'M' 这类残串做子串匹配会误命中
        ('M' in 'MEDICISHAKEDOWN', 2026-09-18 实测因此居中到错卡)。
        """
        kw = re.sub(r"[^A-Z]", "", keyword.upper())
        for _ in range(10):
            title, score = self.read_center_card()
            t = re.sub(r"[^A-Z]", "", title)
            hit = (kw == t) if 0 < len(kw) < 4 else (kw and kw in t)
            if hit:
                log(f"已居中: {title} (score={score:,})")
                return title, score
            self.swipe_left()
            time.sleep(1.8)
        raise RuntimeError(f"10 步内未转到场地 {keyword!r}")

    def goto_index(self, idx: int) -> tuple[str, int]:
        """从轮播初始位左滑 idx 次, 回到 explore() 看到的第 idx 张卡。

        为什么不靠标题匹配 (center): 新卡标题 OCR 可能整体失效
        (2026-09-18 DEATH METTLE 只读出 'M'), 而 'M' 又是 'MEDICI...' 的前缀,
        子串匹配会停在错卡上。轮播位是 explore() 一张张实际滑出来的,
        按次数原路回去是确定性的; 落点是否真的是 score=0 由调用方复核。
        """
        for _ in range(max(0, idx)):
            self.swipe_left()
            time.sleep(1.8)
        title, score = self.read_center_card()
        log(f"goto_index({idx}) -> {title!r} score={score}")
        return title, score

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
