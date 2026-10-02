"""SGM 场景导航: 启动 MuMu/游戏 → 大厅 → PF hub; explore=轮播扫分找 score=0 的场地。

用法 (anaconda python, 仓库根目录):
  python tools/pf_scene.py goto             # MuMu(若未开)→游戏→大厅→PF hub
  python tools/pf_scene.py explore          # goto 后左滑扫每个居中场地的 SCORE,
                                            #   报告 score=0 的场地, 结束回到初始居中卡
  python tools/pf_scene.py goto --skip-mumu # 跳过 MuMu 状态检查/启动 (已在跑时加速)

注意: pf_bot 运行中不要跑本脚本 (会跟 bot 抢点击)。
"""
from __future__ import annotations

import json
import re
import subprocess
import sys
import time

from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT_ROOT / "tools"))

from pf_env import (GAME_PKG, SUBPROC_TEXT, mumu_is_running, mumu_start,  # noqa: E402
                    preload_msvcrt, resolve_adb)

preload_msvcrt()  # 必须早于 cv2/MAA 导入，不能依赖 pf_bot 的间接预载。

import cv2  # noqa: E402  (find_modal_x_cv 用; anaconda python 必备)
from pf_bot import PROJECT_ROOT as ROOT  # noqa: F401,E402  (统一 sys.path 语义)
from pf_bot import PfBot  # noqa: E402

IMG = "pf/"
TPL_HALL_PRIZE = IMG + "hall_prize_fights.png"  # 大厅 PRIZE FIGHTS 菱形
TPL_HUB_PLAY = IMG + "hub_play.png"             # PF hub 居中卡 PLAY!
TPL_CONTINUE = IMG + "pf_continue_btn.png"      # 结算 CONTINUE (逃出残局用)
TPL_RESULT_CONTINUE = IMG + "result_continue.png"
TPL_SCENE_X = IMG + "scene_popup_x.png"         # 大厅促销弹窗 X (BACK TO SCHOOL 等)
TPL_MODAL_X = IMG + "popup_close_x.png"         # 弹窗通用关闭 X (每日奖励/HEADLINER 等)

ROI_HUB_PLAY = (500, 400, 780, 530)   # x0,y0,x1,y1
ROI_RESULT = (400, 570, 980, 700)
ROI_TOPRIGHT = (1040, 15, 1260, 115)
# 促销弹窗 (带价格/商店入口那种) 右上 X。
# 实测 X 中心 ~(1100,83) 属「金环+蓝底+金 X」款式, x 落在 1073~1130。
# 2026-09-19 复核: 原 (950,30,1240,180) 其实**够得着**它 (命中 1103,97),
#   当时误判成 ROI 没框到 —— 真因是 goto_pf() 缺弹窗分支 (见该函数注释)。
# 起点仍左移到 880 留余量: 历史上 DAILY LOGIN BONUS 的 X 在 832,
#   交给 ROI_MODAL_X 处理, 这里左移不会有副作用 (两者点同一类关闭钮)。
ROI_SCENE_X = (880, 30, 1240, 180)
# 启动时的弹窗栈是一串, X 的位置随各弹窗面板高度变:
#   DAILY LOGIN BONUS      X 中心 (832, 30)
#   HEADLINER DAILY PASS   X 中心 (832,189)   <- 关掉上一个才露出这个
#   6-DAY DAILY PASS       X 中心 (855,132)   <- 2026-09-17 01:25 run_pf 卡死实证
# 所以 ROI 必须放高到 300 才够; 横向上限取 1060, 把 OPTIONS 面板的 X (1164,14) 排除,
# 否则会误点 OPTIONS. 实测: 本 ROI 内弹窗帧 0.993~1.000, 30 张大堂/战斗图最高仅 0.529,
# 且每个弹窗内只有一个 >0.70 的峰 —— 阈值 0.90 分离得很干净.
#
# ⚠️ 2026-09-29 上限 1000 -> 1060: 新「30-DAY DAILY PASS」促销弹窗 (面板比旧款宽) 的
#   方形 X 中心在 (1012,157), 模板 40x40 要 x1≥1032 才框得下 —— 上限 1000 时
#   cv2 匹配区容不下模板, 得分只有 0.201 (实测帧 debug/pf/_nav2.png), 三路检测全盲,
#   wait_hall 连点 3 次房子后 150s 超时 (01:05 explore 实证). 放宽后同帧 0.963 命中,
#   次峰 0.209 (唯一性通过). 1060 仍把 OPTIONS X (1164,14) 与本弹窗右侧货币条
#   (x≥1100) 挡在外面, 覆盖表里其余弹窗不受影响.
ROI_MODAL_X = (700, 0, 1060, 300)
# 模态 X 只保留方形按钮这一种图形:
#   方形按钮 X  (popup_close_x.png)  弹窗帧 0.987+, 非弹窗 340 张最高 0.529, 零误报
#
# ⚠️ 2026-09-19 删除 `popup_close_x_round.png` (原阈值 ROI_MODAL_X_ROUND_TH=0.95)。
#   该文件内容是「近黑底 + 底部一条金边」(实测上半 RGB≈(4,3,1), 下半 (42,77,88)),
#   均值远暗于真正的 X 按钮 (streak_x/energy_x 均值 ~90) —— 它是**误截的面板边框**,
#   根本不是关闭钮。危害实证: 在 KEEP STREAK 帧上命中 0.976 伪峰 @(749,185),
#   那里正是面板金边; 它仅靠 MODAL_X_PEAK_MARGIN(0.05) 侥幸挡住 (差 0.044),
#   margin 稍松就会点边框空转。KEEP STREAK 的真 X 在 (960,211), 该模板那里只有 0.53。
#   删掉后, KEEP STREAK 交给 find_popup_x 的 streak_x (0.985) 处理, 覆盖更准。
# 模态 X 的"峰值唯一性"判据: 主峰与邻域被抹除后的次峰之差下限。
# 真 X 是孤立峰 (实测次峰仅 0.5x); 背景渐变会形成连片平台 (实测多峰 0.951~0.953,
# 差仅 0.001~0.002)。取 0.05 —— 真峰远大于此, 伪平台远小于此, 分离很干净。
MODAL_X_PEAK_MARGIN = 0.05
# 居中卡 SCORE/标题 ROI (ROI_CARD_*) 与字库 2026-10-02 起住在 pf_bot
# (接力导航共用同一份读卡逻辑), 本文件经 PfScene 委托使用。

HOME_BTN = (115, 37)   # 顶栏房子: 回大厅
HALL_TIMEOUT = 150.0   # 冷启动含 CONNECTING
HUB_TIMEOUT = 30.0


def log(msg: str, level: str = "info") -> None:
    print(f"[{time.strftime('%H:%M:%S')}][{level}] {msg}", flush=True)


def ensure_mumu(adb_path: str) -> None:
    """MuMu 未启动则拉起 0 号设备, 等到 start_finished (须在 PfScene 构建前调用,
    否则 setup() 的 adb 连接会先炸)。

    启停语义统一在 pf_env.mumu_* (那里也定义了「只能走 MuMuManager shutdown」的铁律),
    本函数只负责重试与日志。
    """
    if mumu_is_running(adb_path):
        log("MuMu 设备已就绪")
        return
    for _ in range(2):
        log("启动 MuMu 0 号设备 ...")
        if mumu_start(adb_path, timeout=120):
            log("MuMu 设备已就绪")
            return
    raise RuntimeError("MuMu 未就绪")


class PfScene:
    def __init__(self) -> None:
        self.bot = PfBot()
        self.bot.setup()  # adb 连接 + 资源 + tasker (setup 前需 MuMu 已启动)
        adb_path, self.address = resolve_adb()
        self.adb = adb_path
        self.mgr = str(Path(adb_path).with_name("MuMuManager.exe"))

    # ---------- 设备层 ----------

    def adb_shell(self, cmd: str) -> str:
        # SUBPROC_TEXT: 见 pf_env 顶部说明 —— adb 输出可能含 UTF-8 中文设备名,
        # 而 bat 起进程时 locale 是 GBK, 不指定就会在 subprocess 读线程里炸且捕不住。
        p = subprocess.run([self.adb, "-s", self.address, "shell", cmd],
                           capture_output=True, text=True, timeout=30,
                           **SUBPROC_TEXT)
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

        ⚠️ 2026-09-19 加**峰值唯一性**判据 (踩坑实证):
          圆环模板在促销弹窗帧上, y=77 整行拿到 0.951~0.953 的**连续平台**
          (x=722/902/922/942/962 连成一片) —— 那是渐变背景造成的伪峰, 不是 X。
          单看最高分会命中 (963,77), 而那位置是**顶栏头像按钮**, 点下去会打开
          PROFILE 面板, 把大厅链路彻底带偏 (实测级联失败: 连点 5 次 → PROFILE
          打开 → 该面板的 X 又检测不到 → 150s 超时)。
          真 X 的特征是**孤立单峰**; 伪峰是连片平台。判据: 主峰与"非极大抑制后
          的次峰"得分差必须够大, 否则视为背景纹理, 不认。
        """
        img_dir = ROOT / "assets" / "resource" / "base" / "image"
        # 只认方形按钮 X。圆环模板 popup_close_x_round.png 已于 2026-09-19 删除
        # (实为误截的面板金边, 会在 KEEP STREAK 帧上产生 0.976 伪峰) —— 见文件头注释。
        for tpl_path, th in ((TPL_MODAL_X, 0.90),):
            tpl = cv2.imread(str(img_dir / tpl_path))
            if tpl is None:
                continue
            x0, y0, x1, y1 = ROI_MODAL_X
            sub = img[y0:y1, x0:x1]
            if sub.shape[0] < tpl.shape[0] or sub.shape[1] < tpl.shape[1]:
                continue
            res = cv2.matchTemplate(sub, tpl, cv2.TM_CCOEFF_NORMED)
            _, mx, _, ml = cv2.minMaxLoc(res)
            if mx < th:
                continue
            # 峰值唯一性: 抹掉主峰邻域后再取次峰。真 X => 次峰明显低;
            # 背景平台 => 次峰与主峰几乎相同。
            h, w = tpl.shape[:2]
            pad = max(h, w) * 2
            cy0, cx0 = ml[1], ml[0]
            masked = res.copy()
            masked[max(0, cy0 - pad):cy0 + pad,
                   max(0, cx0 - pad):cx0 + pad] = -1.0
            second = cv2.minMaxLoc(masked)[1]
            if mx - second < MODAL_X_PEAK_MARGIN:
                log(f"模态 X 疑似背景伪峰: 主峰 {mx:.3f} @ {ml}, 次峰 {second:.3f} "
                    f"(差 {mx - second:.3f} < {MODAL_X_PEAK_MARGIN}) —— 忽略", "warn")
                continue
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

    # ---- 场地识别 (2026-10-02 起委托 PfBot) ----
    # 读卡/字库/分数归一逻辑 2026-10-02 起住 pf_nav.SceneNav (pf_bot.PfBot 继承),
    # bot 的接力导航共用同一份; 这里保留同名薄委托, explore/center/goto_index 行为不变。

    parse_score_ocr = staticmethod(PfBot.parse_score_ocr)

    def read_center_card(self) -> tuple[str, int]:
        """读居中场地的 (名称, 分数)。实现在 PfBot.read_center_card。"""
        return self.bot.read_center_card()

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
            # 统一走 find_any_popup_x: 之前只认 find_modal_x_cv + TPL_SCENE_X,
            # KEEP STREAK 的 X (960,211) 那两路都认不出 → 漏检 150s 超时。
            hit = self.find_any_popup_x(img)
            modal = hit[0] if hit else None
            src = hit[1] if hit else ""
            if modal:
                # 防饿死: 同一处模态 X 连点若干次还在, 说明它关不掉 ——
                # 要么是 cv2 对非弹窗界面的 X 形图案误匹配, 要么点击位置偏了,
                # 要么输入通道整体失效。此时改按返回键 + 房子回家, 别再原地打转。
                #
                # ⚠️ 2026-09-19 修: 原判据 `modal == last_modal` 用严格相等 —— 但
                # cv2 模板匹配的峰值坐标**必然抖动** (实测同一弹窗连读 19 次, x 在
                # 747~760 之间跳, y 恒 185), streak 每轮被重置成 1, 永远到不了阈值,
                # 防饿死形同虚设 → 对着面板边框上的伪 X (真 X 在 815,180) 连点 19 次,
                # 150s 超时。改用「同一区域」判定: 坐标距离 <= MODAL_SAME_TOL 即视为
                # 同一处。真 X 与伪 X 相距 ~55px, 远超容差, 不会误并。
                MODAL_SAME_TOL = 15
                if last_modal and max(abs(modal[0] - last_modal[0]),
                                      abs(modal[1] - last_modal[1])) <= MODAL_SAME_TOL:
                    modal_streak += 1
                else:
                    modal_streak = 1
                last_modal = modal
                if modal_streak >= 5:
                    log(f"弹窗 X ({src}) @{modal} 同处连点 {modal_streak} 次未生效, "
                        f"疑似误匹配/点击位置偏/输入失效 —— 按返回键+点房子脱离", "warn")
                    self.adb_shell("input keyevent KEYCODE_BACK")
                    time.sleep(2.0)
                    self.tap(*HOME_BTN)
                    tried_home += 1
                    last_modal, modal_streak = None, 0
                    time.sleep(3.0)
                    continue
                log(f"弹窗 ({src}, 每日奖励/通行证/连胜等), 点 X 关闭 @ {modal}", "warn")
                self.tap(*modal)
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

    def find_any_popup_x(self, img, tagged: bool = True):
        """汇总三个弹窗关闭钮检测器, 命中返回 (中心坐标, 来源标签), 否则 None。

        ⚠️ 2026-09-19 修的断层 (Luna 指出「streak 本就在原逻辑里」):
        三个检测器各管一小块 ROI, 谁也不覆盖全部 ——

          弹窗                    X 位置      find_popup_x  find_modal_x_cv  TPL_SCENE_X
          DAILY LOGIN BONUS       (832, 30)       ✗              ✓             ✓
          HEADLINER DAILY PASS    (832,189)       ✗              ✓             ✓
          6-DAY DAILY PASS        (855,132)       ✗              ✓(坏模板)      ✗
          KEEP STREAK             (960,211)       ✓              ✓(坏模板)      ✗
          OPTIONS                 (1164,14)       ✗              ✗             ✗

        `pf_bot.find_popup_x` (含 `streak_x.png`) 一直在, 但 `pf_scene` 的三条
        逃逸链只调 `find_modal_x_cv` + `TPL_SCENE_X`, **从没调过它** ——
        `streak_x` 因此在 pf_scene 侧形同虚设 (setup_env 的模板清单里却有它)。
        实测 KEEP STREAK 帧: find_popup_x 命中 0.985 @(960,211),
        而 find_modal_x_cv 的方形模板只有 0.530、圆环模板 0.976 但落在
        (749,185) 伪峰上被唯一性判据挡掉 → 两路皆空 → 150s 超时。

        现在统一收口到这里, 三条链共用, 不再各写各的。
        """
        # 1) 模态 X: ROI_MODAL_X 的方/圆两种样式 (cv2, 带峰值唯一性判据)
        if tagged:
            modal = self.find_modal_x_cv(img)
            if modal:
                return modal, "模态X"
        # 2) pf_bot 的通用弹窗 X: 能量/streak/OPTIONS 三模板
        #    (KEEP STREAK 的 X 只有这一路认得出)
        x = self.bot.find_popup_x(img)
        if x:
            return x, "通用X"
        # 3) 促销弹窗 X (大厅 Right 上角那种带价格的)
        x = self.bot.match_tpl(img, TPL_SCENE_X, ROI_SCENE_X, th=0.8)
        if x:
            return x, "促销X"
        return None

    def dismiss_popup_once(self, img=None) -> bool:
        """若当前帧有可关闭的弹窗, 点掉它并返回 True。

        覆盖三类关闭钮 (同一"通用关闭"语义) —— 见 find_any_popup_x 的覆盖表:
          - 模态 X     (ROI_MODAL_X, cv2 直算, 方/圆两种样式)
          - 通用弹窗 X  (pf_bot.find_popup_x: 能量/streak/OPTIONS)  ← 09-19 补接
          - 促销弹窗 X  (ROI_SCENE_X)
        这是 wait_hall 逃逸链的**可复用内核** —— goto_pf 也要用它, 否则会出现
        「wait_hall 报了大厅就绪, 紧接着弹窗压上来, goto_pf 只认 PLAY/菱形
        两个模板, 对弹窗视而不见 → 30s 空转超时」。
        (2026-09-19 实测: GUEST STAR RELIC PACK 促销弹窗在 wait_hall 返回后弹出,
         goto_pf 连续 30s 找不到 PLAY, 报「30s 未进入 PF hub」。)
        """
        img = self.snap() if img is None else img
        hit = self.find_any_popup_x(img)
        if hit:
            (x, y), src = hit
            log(f"弹窗 X ({src}) @ ({x},{y}), 点掉", "warn")
            self.tap(x, y)
            time.sleep(1.8)
            return True
        return False

    def goto_pf(self) -> None:
        """大厅 → PF hub (居中场地方程页)。单点未生效则补点 (首点居中再点生效)。

        ⚠️ 每轮**先剥弹窗**: 大厅阶段会不定时弹促销/每日奖励弹窗 (它们常盖住
        PLAY 与菱形), 必须先清掉再看 PLAY —— 见 dismiss_popup_once 的说明。
        """
        self.wait_hall()
        t0 = time.time()
        while time.time() - t0 < HUB_TIMEOUT:
            img = self.snap()
            if self.bot.match_tpl(img, TPL_HUB_PLAY, ROI_HUB_PLAY, th=0.7):
                log("PF hub 就绪")
                return
            if self.dismiss_popup_once(img):
                continue
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
        """左滑扫场轮播: 收集每个居中场地的分数, 报告 score=0, 回到初始居中卡。

        2026-09-22: PF hub 轮播**不循环** —— 滑到最右一张后再滑画面不动
        (实证 09-22: 第 3 张 INFINITY AND BEYOND 后连滑 7 次画面停在原卡,
        卡上无 SCORE 行 -> 连续 8 次读出 ('', -1), 空串 key 不触发已有的
        标题去重, 白白扫满 10 槽还把扫描结果塞满 8 条空项)。加"相邻两次
        读数完全相同 -> 到头, 提前结束"判据: 正常轮播相邻两卡名字必不同,
        相同只可能是同一张卡 (滑动无效)。
        """
        seen: list[tuple[str, int]] = []
        prev: tuple[str, int] | None = None
        for i in range(10):
            title, score = self.read_center_card()
            log(f"居中卡[{i}] {title!r} score={score}")
            if prev is not None and (title, score) == prev:
                log("画面与上一张完全相同, 轮播已到头, 扫描结束")
                break
            prev = (title, score)
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
