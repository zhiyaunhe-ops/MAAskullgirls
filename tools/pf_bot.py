"""Skullgirls Mobile Prize Fight 自动化主程序。

流程: 选对手(火框倍率最大/无火选战力最低) -> 编队(能量>=4的角色拖入槽位)
      -> FIGHT(自动战斗) -> Continue 领奖 -> 循环。
能量不足弹窗: 关闭后自动进编队, 只替换能量不足的槽位。

用法: python tools/pf_bot.py
WebUI: http://127.0.0.1:<pf_env.WEBUI_PORT>  (本机 config.json 可覆盖)

日志: 全部写 debug/pf/pf.log (与托盘/调度器同一份)。装在 import 之前, 因为
      maa/pf_webui 等模块在 import 期也会打日志, 装晚了那些就漏了。
"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

# ⚠️ 必须早于任何其他 import: 统一日志接管 stdout/stderr (2026-10-02)。
# 为什么承重: 改之前 bot 的日志是「谁启动谁选文件」—— pf_schedule 和托盘写
# bot_stdout.log, 自我重生写 bot_stdout_<时间戳>.log。于是 18:30 bot 明明在跑
# 第 143 场, bot_stdout.log 却停在 13:04, 查问题的人以为它卡了 5 小时。
# 详见 tools/pf_logging.py 模块文档 (含"多进程 append 会丢行"的实测数据)。
from pf_logging import install as _install_logging

_install_logging()

import difflib  # noqa: E402
import json  # noqa: E402
import os  # noqa: E402
import re  # noqa: E402
import threading  # noqa: E402
import time  # noqa: E402
from pathlib import Path  # noqa: E402,F811  (与上面 sys.path 那行同源, 保留别名复用)

from pf_env import (
    adb_connect,
    mumu_launch_game,
    mumu_shutdown,
    resolve_adb,
    spawn_detached,
    PROJECT_ROOT,
    STATE,
    SUBPROC_TEXT,
    WEBUI_PORT,
    preload_msvcrt,
    start_debug_cleaner,
)

preload_msvcrt()

import cv2  # noqa: E402
from maa.controller import AdbController  # noqa: E402
from maa.define import MaaAdbScreencapMethodEnum, MaaAdbInputMethodEnum  # noqa: E402
from maa.pipeline import (  # noqa: E402
    JOCR,
    JRecognitionType,
    JTemplateMatch,
)
from maa.resource import Resource  # noqa: E402
from maa.toolkit import Toolkit  # noqa: E402
from maa.tasker import Tasker  # noqa: E402

import pf_vision as vis  # noqa: E402
from pf_nav import (RESOURCE_DIR, ROI_RESULT, ROI_TOPRIGHT,  # noqa: E402  (场景导航/扫描独立模块)
                    NavAborted, SceneNav, TPL_CONTINUE, TPL_HUB_PLAY,
                    TPL_RESULT_CONTINUE)
from pf_domain import ScoreTracker  # noqa: E402  (clean_* 在 pf_webui.apply_session)
from pf_store import STORE  # noqa: E402
from pf_webui import apply_session, start_webui  # noqa: E402

SHOT_DIR = PROJECT_ROOT / "debug" / "pf" / "run"

IMG = "pf/"
TPL_FIGHT = IMG + "vs_fight_btn.png"
TPL_FILTER_X = IMG + "pf_filter_x.png"
TPL_DRAGHINT = IMG + "drag_hint.png"
# 防守队编辑器的右上确认按钮 (2026-09-29 实机裁图: assets/.../pf/defense_confirm.png,
# 源帧 debug/pf/web_latest.jpg)。它**不是** vs_fight_btn —— 防守队编辑器右上写的是
# CONFIRM, 老代码只认 FIGHT/CONTINUE 所以确认不了 (见 setup_defense_team 注释)。
# 反向验证: 本帧 1.0; 促销/VS/选对手/层级卡等帧最高 0.494 (阈值 0.7 分离干净)。
TPL_DEFENSE_CONFIRM = IMG + "defense_confirm.png"
TPL_ENERGY_X = IMG + "energy_x.png"
TPL_REFRESH = IMG + "pf_refresh_btn.png"
TPL_STREAK_X = IMG + "streak_x.png"
TPL_DETAIL_STATS = IMG + "detail_stats.png"
TPL_OPTIONS_X = IMG + "options_x.png"
TPL_SRV_OK = IMG + "srv_ok.png"
TPL_SRV_RETRY = IMG + "srv_retry.png"
TPL_SPD_1X = IMG + "battle_spd_1x.png"
TPL_SPD_2X = IMG + "battle_spd_2x.png"
TPL_SPD_3X = IMG + "battle_spd_3x.png"
ROI_POPUP = (860, 160, 1060, 340)   # 弹窗右上 X 区域

# 场景识别/导航/扫描常量与方法 2026-10-02 起住 pf_nav.SceneNav (PfBot 继承)

# 编队页筛选面板芯片坐标 (1280x720, 见 docs/screenshots/filter_panel.png)
FILTER_BTN = (1215, 395)
FILTER_CLEAR = (272, 126)
FILTER_CLOSE = (1228, 85)
FILTER_HEART = (616, 237)
FILTER_COLS = [197, 302, 407, 512, 616, 721]
ELEMENT_CHIPS = {"fire": (197, 370), "water": (302, 370), "wind": (407, 370),
                 "light": (512, 370), "dark": (616, 370), "neutral": (721, 370)}
CLASS_CHIPS = {f"c{i+1}": (FILTER_COLS[i % 6], 505 if i < 6 else 594) for i in range(12)}
# 角色场防守队限定: 筛选面板第 3/4 行是**角色图标** (6 列 x 2 行, 坐标即 CLASS_CHIPS)。
# 用户口径 2026-09-29: 角色场的防守阵容必须包含该期角色 —— 不满足时编辑器右上红
# 禁止标 + CONFIRM 变灰点不动 (实测帧 debug/pf/_def_now.png)。名单顺序与 webui.js 的
# RULE_CLASSES (c1..c12) 同一份; c5=菱形图标=Cerebella 已实机印证 (筛选后候选整排变
# Cerebella, CONFIRM 转亮)。
CHARACTER_CHIPS = {
    "Annie": CLASS_CHIPS["c1"], "Beowulf": CLASS_CHIPS["c2"],
    "Big Band": CLASS_CHIPS["c3"], "Black Dahlia": CLASS_CHIPS["c4"],
    "Cerebella": CLASS_CHIPS["c5"], "Double": CLASS_CHIPS["c6"],
    "Eliza": CLASS_CHIPS["c7"], "Filia": CLASS_CHIPS["c8"],
    "Fukua": CLASS_CHIPS["c9"], "Marie": CLASS_CHIPS["c10"],
    "Ms. Fortune": CLASS_CHIPS["c11"], "Painwheel": CLASS_CHIPS["c12"],
}
# 防守队编辑器是**另一套版式** (2026-09-29 实测): 三槽居中 (y≈190), 候选列在 y≈555,
# 且**没有能量黄钉** (黄钉是出战队概念) —— 不能复用 pf_vision 的出战队几何与能量判定。
# 拖拽源取候选列正中一张 (640,555): 只要筛选生效, 整排都是目标角色, 无需精确标定。
DEF_SLOT_DROP = [(440, 190), (640, 190), (840, 190)]
DEF_DROP_SRC = (640, 555)
# 槽位卡的**角色名** band (卡面下方彩色条, 如 'CEREBELLA'): 放人后的复验 ROI。
DEF_SLOT_NAME_ROI = [(cx - 95, 292, cx + 95, 350) for cx, _ in DEF_SLOT_DROP]

ROI_FILTER_X = (1150, 30, 1280, 140)  # 筛选面板关闭 X 搜索区
# 候选卡左缘框条的元素色相区间 (light/neutral 不可颜色分辨 -> 不做筛选复验)
ELEMENT_HUE = {"fire": [(0, 9), (170, 180)], "water": [(96, 130)],
               "wind": [(50, 97)], "dark": [(131, 146)]}

# ---- 防守队弹窗 (每个新 PF 首次进入时一次性, 见 PfBot.setup_defense_team) ----
# 实测帧: debug/pf/run/0920_011213/0001_defense_probe.jpg (2026-09-20 01:12)。
# 弹窗**叠在选对手页之上** (该帧 REFRESH 仍然 0.95 命中), 所以判据不能靠
# "没有已知界面", 只能认文字: 正文分带 OCR 读出
# 'Before continuing, you must set a Defense Team. The better your Defense Team
#  performs against other players, the more points you'll earn in this Prize Fight!'
# 归一化 (去空格标点) 后含 DEFENSETEAM 即命中。
# 分带原因: MAA OCR 对整块大区域会读空串 (pf_scene 标题兜底 ROI 注释同源)。
DEFENSE_BANDS = [(250, y, 1030, y + 50) for y in range(180, 470, 50)]
# OK 按钮: 同帧绿色按钮块 (x672 y526 w159 h31) 的中心, 该区域 OCR 读出 'OK' ——
# 依据 A 级 (坐标与标签同源)。离线复验: 正样本命中, 18 张非弹窗帧零误报。
DEFENSE_OK_BTN = (751, 541)

# ---- 角色场「难度锁定」确认弹窗 (PLAY!→编队→FIGHT! 后一次性, 2026-09-29) ----
# 正文: "You are about to enter the Diamond <场地> PRIZE FIGHT. ... you will be
# locked into the Diamond difficulty and will no longer be able to enter other
# difficulties for this Prize Fight."  按钮 CANCEL/CONTINUE, **无 X**。
# 取证帧: 用户截图 MuMu-20260929-055627-979.png -> debug/pf/_lock_popup.png。
# 分带 OCR 实测 (同 find_defense_popup 思路): 两探针任一命中即认定;
# CONTINUE 面心取 HSV 绿掩码质心 (748,575)。点 CONTINUE = 锁定难度, 正是所要;
# 点 CANCEL 会退回, 绝不能点。
LOCK_BANDS = [(390, y, 895, y + 50) for y in range(170, 490, 50)]
LOCK_PROBES = ("LOCKEDINTO", "OTHERDIFFICULT")
LOCK_CONTINUE_BTN = (748, 576)


class LinkDead(RuntimeError):
    """自愈后仍拿不到截图 —— MAA 内部作业已僵死 (kill-server 死等), 只能换进程重生。"""


def _hard_exit(code: int) -> None:
    """立即结束进程, 不走 MAA 销毁 —— 僵死的 MAA 等不起。

    2026-09-28 现场: 重生后老进程 `run()` 返回 → 解释器收尾 → 主线程卡死在
    `MaaControllerDestroy` (maafw.log 停在 `AsyncRunner::release` 之后不再动), 进程
    当了 10 分钟+ 的僵尸: 8790 端口不放、bot_stdout.log 句柄也不放 —— 于是新进程
    既绑不上端口 (start_webui 探到 'self' 直接拒绝) 也写不了日志 (cmd `>>` 报"另一个
    程序正在使用此文件", python 根本没被启动), 重生成了空枪, 最后页面彻底失联。
    正常退出该让 MAA 自己清 (会收掉它起的 adb 子进程), 但僵死时只能硬退: os._exit
    跳过解释器收尾与所有线程 join, 端口与句柄立刻释放。
    """
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.flush()
        except Exception:  # noqa: BLE001  关流失败也得退
            pass
    os._exit(code)


def _arm_exit_watchdog(seconds: float = 25.0) -> None:
    """正常退出路径的保险丝: N 秒还没退完, 由守护线程 `_hard_exit` 强制收尾。

    2026-09-27 01:02 现场: 点「停止」后主循环确实退出了, 但进程停在 MAA 销毁里不出来
    —— 页面还在、端口还占着, 用户点「开始」毫无反应。挂在这条路径上的还有场次结束、
    达标与调度 stop_pf。僵死的 MAA 销毁会 join 卡在 adb 死等里的工作线程, 等它没有意义。
    """
    def _bomb() -> None:
        time.sleep(seconds)
        STATE.log(f"退出超时 {seconds:.0f}s (MAA 销毁卡住), 强制结束进程", "warn")
        _hard_exit(0)

    threading.Thread(target=_bomb, daemon=True, name="exit-watchdog").start()


class PfBot(SceneNav):
    _RESPAWN_MARKER = Path(PROJECT_ROOT) / "debug" / "pf" / "respawn.json"
    # 2026-10-02 起不再用它写分世代日志 (统一写 pf.log), 但保留常量:
    # tests/test_respawn_exit.py 仍在 patch 它做隔离, 删掉会让测试写进真目录。
    _RESPAWN_LOG_DIR = Path(PROJECT_ROOT) / "debug" / "pf"
    _RESPAWN_MAX = 20          # 每自然日重生上限, 用尽即彻底退出等人工 (2026-09-28 用户口径)
    _RESPAWN_GAP = 300.0       # 两次重生最小间隔 (秒)

    def __init__(self) -> None:
        self.controller: AdbController | None = None
        self.tasker = Tasker()
        self.resource = None           # Resource 在 setup 加载 (不依赖模拟器)
        self._link_down_since = None   # 断链起始时刻 (日志节流, 见 _link_log)
        self.shot_seq = 0
        self.tracker = ScoreTracker()  # 总分采样基线（随场次自动重置）
        self._rule_done_fight = -1    # 本场已做过规则替换的场次号
        self._rule_redo = 0           # 本场规则重做次数 (能量替换破坏规则时++)
        self._filter_cleared = False  # 本次开始运行以来是否已归位过筛选 (首次编队)
        self._battle_auto_checked = False  # 首场战斗已做过自动战斗/速度检查
        self._defense_done = False         # 本场次已处理过「先设防守队」弹窗 (每 PF 一次)
        self.fights_since_rest = 0    # 距上次休息的已结算场数
        self._goal_closed = False     # 本次运行是否已因达标关过模拟器 (只做一次)
        self._stuck_shot = None       # 卡住未完成的截图作业 (见 _screencap_bounded)
        self._stuck_shot_warned = 0.0  # 上次提醒"截图卡住"的时间戳
        self._shot_fails = 0          # 连续截图失败计数 (断链自愈用)
        self._adb = None              # (adb_path, address) 懒解析 (轮播滑动用)
        self._tpl_cache = {}          # cv2 模板缓存 (find_modal_x_cv 用)
        self._nav_running_at_entry = None  # 导航/扫描中止判据基线 (见 pf_nav._nav_abort)
        self.run_dir = SHOT_DIR / time.strftime("%m%d_%H%M%S")

    # ---------- 基础设施 ----------

    def setup(self) -> None:
        self.run_dir.mkdir(parents=True, exist_ok=True)
        STATE.arenas = self._load_arenas()   # 今天的扫描录入先回读 (WebUI 开页即见)
        start_debug_cleaner(self.run_dir, log=STATE.log)  # 图片≤150MB / 日志≤50MB
        Toolkit.init_option(str(PROJECT_ROOT / "debug"))
        # Resource 加载**不依赖模拟器**, 先做好 —— MuMu 不在线时服务照样活着
        # (2026-09-30 用户口径: 没检测到 MuMu 不许整个断掉), 只差连接这一步。
        self.resource = Resource()
        self.resource.use_cpu()  # 本机 DirectML 枚举不到适配器, OCR 用 CPU 推理
        job = self.resource.post_bundle(str(RESOURCE_DIR))
        job.wait()
        if not job.succeeded:
            raise RuntimeError("资源加载失败 (检查 model/ocr 与 image/pf)")
        STATE.log("[ok] 资源加载完成")
        if not self.ensure_connection():
            # 连不上不再 raise 退出 (旧版在这里 3 次重试后进程整个没了, WebUI 跟着
            # 失联 —— "服务没检测到 MuMu 就自动断")。改由主循环每 10s 重连。
            STATE.log("MuMu 未上线 —— 服务保持待命, 每 10s 自动重连; "
                      "「开始」/「开跑」会先拉起模拟器", "warn")

    def ensure_connection(self) -> bool:
        """确保 MAA controller 已连接且 tasker 已绑定; 断了/没建就 (重)建。

        已连接时是纯内存检查, 零 I/O, 主循环每轮调用无负担。连接失败**不抛
        异常**只返回 False —— 调用方 (setup / run 循环) 决定等待策略。
        2026-09-30 用户口径: MuMu 缺席时服务保持在线, 模拟器一回来自动接上。
        """
        if (self.controller is not None and self.controller.connected
                and self.tasker.inited):
            return True
        try:
            adb_path, address = resolve_adb()
            if not adb_path:
                self._link_log("未找到 MuMu adb (config.json / 自动探测都没有)")
                return False
            if self.controller is None or not self.controller.connected:
                adb_connect()
                self.controller = AdbController(
                    adb_path=adb_path,
                    address=address,
                    screencap_methods=int(MaaAdbScreencapMethodEnum.Default),
                    input_methods=int(MaaAdbInputMethodEnum.Default),
                )
                self.controller.post_connection().wait()
                if not self.controller.connected:
                    self._link_log(f"等待 MuMu 上线 ({address})")
                    return False
            # 换了 controller (或首次) 就重建 tasker 绑定 —— 不赌旧 tasker 能否重绑
            self.tasker = Tasker()
            if not self.tasker.bind(self.resource, self.controller):
                self._link_log("Tasker 绑定失败")
                return False
            if not self.tasker.inited:
                self._link_log("Tasker 初始化失败")
                return False
            self._link_down_since = None
            STATE.log(f"[ok] 已连接 {adb_path} @ {address}")
            return True
        except Exception as e:  # noqa: BLE001
            self._link_log(f"重连异常: {e}")
            return False

    def _link_log(self, msg: str) -> None:
        """断链期间的日志节流: 状态变化立刻记, 之后每 60s 最多一条。"""
        now = time.time()
        if self._link_down_since is None:
            self._link_down_since = now
            STATE.log(msg, "warn")
        elif now - self._link_down_since >= 60.0:
            self._link_down_since = now
            STATE.log(msg, "warn")

    def snap(self, tag: str = ""):
        """截图: 存档 + 推送 WebUI, 返回图像。走自愈层, 拿不到图抛 LinkDead。"""
        img = self._screencap_resilient()
        if img is None:
            raise LinkDead("截图失败 (连接不稳, 已自动补连仍拿不到图 —— MAA 内部作业僵死)")
        self.shot_seq += 1
        name = f"{self.shot_seq:04d}_{tag}.jpg"
        path = self.run_dir / name
        # 存档是调试产物, 不许带崩主循环: 2026-09-29 实测 pf_scene(待命 bot 期间) 的
        # 清理器把本进程 run_dir 整删, 恢复运行后第一次 write_bytes 抛
        # FileNotFoundError -> "运行异常" ERROR 停摆 (帧没了, 战斗本身没事)。
        try:
            self.run_dir.mkdir(parents=True, exist_ok=True)
        except OSError:
            pass
        # cv2.imwrite 对非 ASCII 路径会静默失败, 用 imencode + write_bytes
        ok, buf = cv2.imencode(".jpg", img, [cv2.IMWRITE_JPEG_QUALITY, 85])
        if ok:
            try:
                path.write_bytes(buf.tobytes())
            except OSError as e:
                STATE.log(f"帧存档失败 (不影响运行): {e}", "warn")
        latest = PROJECT_ROOT / "debug" / "pf" / "web_latest.jpg"
        ok2, buf2 = cv2.imencode(".jpg", img, [cv2.IMWRITE_JPEG_QUALITY, 85])
        if ok2:
            try:
                latest.write_bytes(buf2.tobytes())
            except OSError as e:
                STATE.log(f"预览帧写入失败 (不影响运行): {e}", "warn")
        STATE.push_shot(latest)
        return img

    # ---------- MAA 识别封装 ----------

    @staticmethod
    def to_maa_roi(roi: tuple) -> tuple:
        """(x0,y0,x1,y1) -> MAA 的 (x,y,w,h)"""
        x0, y0, x1, y1 = roi
        return (x0, y0, x1 - x0, y1 - y0)

    def match_tpl(self, img, template: str, roi: tuple = (0, 0, 0, 0), th: float = 0.7):
        """模板匹配, 命中返回中心坐标, 否则 None。roi 为 (x0,y0,x1,y1)。"""
        job = self.tasker.post_recognition(
            JRecognitionType.TemplateMatch,
            JTemplateMatch(template=[template], roi=self.to_maa_roi(roi), threshold=[th]),
            img,
        )
        job.wait()
        detail = job.get()
        reco = detail.nodes[0].recognition if detail and detail.nodes else None
        if not reco or not reco.hit:
            return None
        box = reco.box
        if not box or box[2] <= 0:
            return None
        return (int(box[0] + box[2] / 2), int(box[1] + box[3] / 2))

    def find_popup_x(self, img):
        """弹窗关闭按钮 (能量/streak 同款 X), 命中返回中心。"""
        return (self.match_tpl(img, TPL_ENERGY_X, ROI_POPUP, th=0.7)
                or self.match_tpl(img, TPL_STREAK_X, ROI_POPUP, th=0.7)
                or self.match_tpl(img, TPL_OPTIONS_X, (1140, 5, 1240, 75), th=0.7))

    def find_defense_popup(self, img) -> bool:
        """「先设防守队」弹窗是否在前台。

        每个新 PF 第一次进入时必弹 (2026-09-18 起每次新场都撞上), 不设就打不了。
        只认正文文字, 不认图形 —— 它压在选对手页之上, 底下的 REFRESH/FIGHT 都还在,
        任何"已知界面"判据都会漏掉它 (2026-09-20 实测: bot 因此空转 8 轮 score=0)。
        """
        parts = [self.ocr_text(img, roi) for roi in DEFENSE_BANDS]
        norm = "".join(ch for ch in " ".join(parts).upper() if ch.isalpha())
        return "DEFENSETEAM" in norm

    def find_lock_popup(self, img) -> bool:
        """角色场「锁定难度」确认弹窗是否在前台 (正文文字判定, 同上)。

        探针取两处独有措辞; 与防守队弹窗文本互不包含 (离线互验过),
        两个弹窗的先后 (锁定→防守队) 不会互相误判。
        """
        parts = [self.ocr_text(img, roi) for roi in LOCK_BANDS]
        norm = "".join(ch for ch in " ".join(parts).upper() if ch.isalpha())
        return any(p in norm for p in LOCK_PROBES)

    def setup_defense_team(self) -> bool:
        """防守队弹窗/编辑器: (弹窗时) 点 OK → 左1 放合规角色 → CONFIRM 确认。

        左1 放谁 (两条规则, 都只作用于**防守队**):
          - element 规则: 放对应元素的角色 (用户 2026-09-20 元素通用规定);
          - class 规则:   放该角色 (用户 2026-09-29 角色场防守要求, 见 CHARACTER_CHIPS)。
        rule 为 None 不动左1, 交给常规编队。

        2026-09-29 起两个入口共用本方法 (实机取证, 之前只有弹窗入口):
          ①「先设防守队」弹窗 (点 OK 进编辑器);
          ②主循环直接落在**编辑器**上 —— 右上角是 CONFIRM 而不是 FIGHT!/CONTINUE。
        编辑器里没有 OK 按钮, 对 (751,541) 乱点会碰名册, 所以先判 CONFIRM,
        命中就跳过 OK。两个入口的后续完全一致 (规则替换 + 确认)。
        """
        STATE.set_step("设防守队")
        img = self.snap("防守队入口")
        if self.match_tpl(img, TPL_DEFENSE_CONFIRM, ROI_TOPRIGHT):
            STATE.log("已在防守队编辑器 (右上 CONFIRM), 跳过 OK", "warn")
        else:
            STATE.log("防守队弹窗: 点 OK 进编队", "warn")
            for attempt in range(3):
                self._tap(*DEFENSE_OK_BTN)  # 驻留式点击: 面板会吃掉零时长 post_click
                time.sleep(2.5)
                img = self.snap(f"防守队OK后{attempt + 1}")
                if self.match_tpl(img, TPL_DRAGHINT, (430, 405, 850, 465), th=0.6):
                    break
                STATE.log(f"OK 后没进编队页 (第{attempt + 1}次) —— 可能是按钮坐标偏了, "
                          f"见 DEFENSE_OK_BTN 注释", "warn")
            else:
                STATE.log("防守队弹窗: 连点 3 次 OK 都没进编队页, 交回主循环 "
                          "(不无限重试)", "err")
                return False

        rule = STATE.pf_rule or {}
        fav_chips = [FILTER_HEART] if STATE.filter_favorite else []
        if rule.get("type") == "element":
            chip = ELEMENT_CHIPS.get(rule.get("value"))
            if chip:
                STATE.log(f"防守队: 左1 放一个 {rule['value']} 角色 (元素通用规定)", "warn")
                if not self.refill_rule_slot(0, [chip] + fav_chips, fav_chips):
                    STATE.log("防守队: 左1 放不进合规角色 (筛选池无达标能量 / 元素复验"
                              "不过), 交回主循环", "err")
                    return False
            else:
                STATE.log(f"防守队: 规则元素 {rule.get('value')!r} 没有筛选芯片, 跳过", "err")
        elif rule.get("type") == "class":
            # 角色场: 防守阵容必须含该期角色 (用户 2026-09-29 口径)。出战队不受限,
            # 所以这条只在这里生效, 不进 judge_rule。
            chip = CHARACTER_CHIPS.get(rule.get("value"))
            if chip:
                STATE.log(f"防守队: 左1 放一个 {rule['value']} (角色场防守要求)", "warn")
                if not self.put_char_in_defense(0, chip, rule["value"]):
                    STATE.log("防守队: 左1 放不进目标角色 (筛选没生效/拖拽没落位), "
                              "交回主循环", "err")
                    return False
            else:
                STATE.log(f"防守队: 角色 {rule.get('value')!r} 没有筛选芯片, 跳过", "err")
        else:
            STATE.log("防守队: 本场无元素/角色规则, 左1 不强制换人 (交给常规编队)", "warn")

        # 确认按钮: 编辑器里是 CONFIRM (2026-09-29 实机: 老代码只找 FIGHT/CONTINUE,
        # 结果报「都找不到」交回主循环, 主循环又把编辑器当常规编队页 —— 那页没有
        # 能量黄钉, 黄钉全读 0, 空烧到「无可用能量角色」停摆)。FIGHT/CONTINUE 兜底
        # 保留, 覆盖历史上出现过的其它确认款。
        img2 = self.snap("防守队确认前")
        btn = (self.match_tpl(img2, TPL_DEFENSE_CONFIRM, ROI_TOPRIGHT)
               or self.match_tpl(img2, TPL_FIGHT, ROI_TOPRIGHT)
               or self.match_tpl(img2, TPL_CONTINUE, ROI_TOPRIGHT))
        if not btn:
            STATE.log("防守队确认: CONFIRM/FIGHT/CONTINUE 都找不到, 交回主循环 "
                      f"(截图 {self.run_dir.name}/防守队确认前)", "err")
            return False
        self.controller.post_click(*btn).wait()
        time.sleep(2.5)
        STATE.log("防守队已确认 (点 CONFIRM/FIGHT), 接着等本场打完")
        if not self._battle_auto_checked:
            self._battle_auto_checked = True
            self.ensure_battle_auto()
        self.wait_battle_end()
        return True

    def ocr_text(self, img, roi: tuple) -> str:
        """rec-only OCR, roi 为 (x0,y0,x1,y1)。返回识别文本。"""
        job = self.tasker.post_recognition(
            JRecognitionType.OCR,
            JOCR(roi=self.to_maa_roi(roi), only_rec=True),
            img,
        )
        job.wait()
        detail = job.get()
        reco = detail.nodes[0].recognition if detail and detail.nodes else None
        if not reco:
            return ""
        if reco.best_result and reco.best_result.text:
            return reco.best_result.text.strip()
        parts = [r.text for r in (reco.all_results or []) if r.text]
        return " ".join(parts).strip()

    EXPECTED_MULTS = ["x1", "x1.5", "x2", "x2.5", "x3", "x4", "x5"]

    def ocr_expected(self, crop) -> str:
        """小图 rec-only OCR，带倍率候选集过滤。"""
        job = self.tasker.post_recognition(
            JRecognitionType.OCR,
            JOCR(roi=(0, 0, 0, 0), only_rec=True, expected=self.EXPECTED_MULTS),
            crop,
        )
        job.wait()
        detail = job.get()
        reco = detail.nodes[0].recognition if detail and detail.nodes else None
        if reco and reco.best_result and reco.best_result.text:
            return reco.best_result.text.strip()
        return ""

    # ---------- 拖拽 ----------

    def drag_card(self, sx: int, sy: int, dx: int, dy: int, ox: int = 0, oy: int = 0) -> None:
        """受控拖拽: 按下 → 分段移动 → 落点停顿 → 抬起。

        post_swipe 是连续滑动+立即释放, Unity 的拖放判定需要指针在目标上
        停留一两帧, 窄判定区的槽位 (如 2 号槽) 会脱靶。
        """
        ctrl = self.controller
        ctrl.post_touch_down(sx, sy).wait()
        time.sleep(0.15)
        ctrl.post_touch_move(sx + (dx - sx) // 3, sy + (dy - sy) // 3).wait()
        time.sleep(0.12)
        ctrl.post_touch_move(dx + ox, dy + oy).wait()
        time.sleep(0.35)
        ctrl.post_touch_up().wait()

    def scroll_roster(self, x1: int, x2: int, y: int = 570) -> None:
        """候选列横向滚动: 分步移动 + 末段停顿再抬起, 避免惯性甩动过冲。"""
        ctrl = self.controller
        ctrl.post_touch_down(x1, y).wait()
        time.sleep(0.12)
        steps = 5
        for i in range(1, steps + 1):
            ctrl.post_touch_move(x1 + (x2 - x1) * i // steps, y).wait()
            time.sleep(0.06)
        time.sleep(0.25)
        ctrl.post_touch_up().wait()

    def find_server_error(self, img):
        """服务器错误弹窗: 'difficulty reaching our servers'(紫OK) 与
        'SERVER ERROR'(红OK) 两种变体。命中返回 OK 中心坐标。"""
        ok = self.match_tpl(img, TPL_SRV_OK, (400, 380, 880, 560), th=0.75)
        if ok:
            return ok
        ok = self.match_tpl(img, TPL_SRV_RETRY, (400, 380, 880, 560), th=0.75)
        if ok:
            return ok
        job = self.tasker.post_recognition(
            JRecognitionType.OCR,
            JOCR(roi=(240, 60, 800, 560), expected=["SERVER ERROR"]),
            img,
        )
        job.wait()
        detail = job.get()
        reco = detail.nodes[0].recognition if detail and detail.nodes else None
        if reco and reco.hit and reco.box:
            # 红色变体: 标题下方 ~70px 即 OK 按钮
            return (int(reco.box[0] + reco.box[2] / 2), int(reco.box[1] + 70))
        return None

    def detail_open(self, img) -> bool:
        """是否误入了角色详情页 (INFO/STATS 标签栏)。"""
        return self.match_tpl(img, TPL_DETAIL_STATS, (760, 15, 1240, 75), th=0.7) is not None

    # ---------- 计分 ----------

    def read_score(self, img):
        """读对手选择页左面板的总分, 失败返回 None。"""
        val = vis.parse_power(self.ocr_text(img, vis.SCORE_ROI))
        return int(val) if val is not None else None

    def track_score(self, img) -> None:
        """对手页: 待结算时记录新总分并算差值, 否则记录起始总分; 连胜一起记录。"""
        streak = vis.parse_power(self.ocr_text(img, vis.STREAK_ROI))
        if streak is not None:
            STATE.streak = int(streak)
        val = self.read_score(img)
        if val is None:
            return
        STATE.score = val
        streak_txt = f"，连胜 {STATE.streak}" if STATE.streak is not None else ""
        if STATE.score_target is not None and val >= STATE.score_target:
            # 无暂停态 (2026-09-27 用户口径): 达标 = 场次结束, 主循环随即回 IDLE 待命
            STATE.log(f"总分 {val:,} 已达上限 {STATE.score_target:,}, 结束场次", "warn")
            STATE.end_reason = "goal"
            STATE.running = False
            self.on_goal_reached(val)
            return
        ev = self.tracker.on_score(val, STATE.fight_no)
        if ev is None:
            return
        sid = STORE.session_id or "default"
        if ev["event"] == "fight":
            if ev["delta"] is not None:
                STATE.log(f"总分: {val:,}（本场 {ev['delta']:+,}{streak_txt}）", "warn")
                STORE.append_csv(sid, val, ev["delta"], STATE.streak, STATE.fight_no)
            else:
                STATE.log(f"总分: {val:,}{streak_txt}")
        else:
            # 没打仗但分数变了（手动干预等），同样记录
            STATE.log(f"总分变化: {val:,}{streak_txt}")
        STORE.record(sid, val, STATE.streak, STATE.fight_no)

    def on_goal_reached(self, val: int) -> None:
        """达标收尾: 开关打开则关掉 MuMu (接力队列非空时不关, 直接接下一场)。

        关模拟器是**有副作用的外界动作** (会连带打断同一实例上别的 MAA 自启),
        所以受「达标关模拟器」开关控制 (默认开, 用户 2026-09-26 口径: 凌晨无人
        值守跑完就该关机; WebUI 可关), 且每次运行只做一次 ——
        否则达标后 bot 停在对手页, 每次循环都会再读一次总分、再关一次。
        2026-10-02: 排了接力队列时, "跑完就关机"顺延到队列清空那一场 ——
        排任务的用户要的是"这个场打完切下一个", 中途关机队列就断了。
        """
        if STORE.queue_list():
            STATE.log("接力队列还有下一场, 达标不关模拟器, 直接接续", "warn")
            return
        if not STATE.close_mumu_on_goal or self._goal_closed:
            return
        self._goal_closed = True
        STATE.set_step("达标关机")
        STATE.log(f"已达到目标分 {STATE.score_target:,}, 正在关闭 MuMu ...", "warn")
        if mumu_shutdown():
            STATE.log("MuMu 已关闭 (达标收尾)", "warn")
        else:
            STATE.log("关闭 MuMu 失败: MuMuManager 不可用或未响应 (请手动关闭)", "err")

    def _queue_next(self):
        """**只有达标 (goal) 结束**才自动接续队列下一场 (2026-10-02 用户口径)。

        手动「结束」= 用户要停 (整条连刷随之暂停, /api/end 负责), 绝不能把
        结束解读成"换下一场" —— 首版把 manual/scene 也接续, 用户点结束后又开始
        认路+扫描下一个场, 观感就是"卡在扫描循环里出不来"。scene 失败 (场地没
        找到) 同样停下等人工: 自动跳过会在队列里一路扫描, 制造同样观感。
        队列消费时的两道防呆:
          - 队首与当前场次相同 -> 丢弃 (防同一场次被接续重跑; 分数已达标的场次
            重跑会开场即"达标", 再接续再重跑 = 无限循环);
          - 场次已被删除 -> 丢弃并接着取下一个 (而不是把剩余队列一起搁置)。
        取到的场次由调用方 apply_session 并继续运行。"""
        reason = STATE.end_reason
        STATE.end_reason = None
        left = len(STORE.queue_list())
        if reason != "goal":
            if reason in ("manual", "scene") and left:
                STATE.log(f"接力队列还剩 {left} 场未接续 (非达标结束); 想接着跑: "
                          f"重新勾选「连刷模式」, 或直接开始目标场次", "warn")
            return None
        while True:
            sid = STORE.queue_pop()
            if sid is None:
                return None
            if sid == STORE.session_id:
                STATE.log(f"接力队列队首即当前场次 ({sid}), 丢弃防重跑", "warn")
                continue
            nxt = STORE.get(sid)
            if not nxt:
                STATE.log(f"接力队列: 场次 {sid} 已删除, 跳过", "warn")
                continue
            STATE.log(f"接力队列: 接续场次「{nxt['name']}」", "warn")
            return sid

    # ---------- 各阶段 ----------

    def pick_opponent(self, img) -> None:
        """选对手: 有火框选倍率最大, 否则选战力最低。"""
        STATE.set_step("选对手")
        cards = []
        for i, card in enumerate(vis.OPPONENT_CARDS):
            badge = vis.find_fire_badge(img, vis.FIRE_SEARCH[i])
            fire = badge is not None
            mult = None
            if fire:
                crop = img[badge[1]:badge[3], badge[0]:badge[2]]
                crop = crop[int(crop.shape[0] * 0.30):, :]  # 裁掉顶部火焰尾巴
                txt = self.ocr_expected(crop)
                mult = vis.parse_mult(txt)
            power = vis.parse_power(self.ocr_text(img, card["power_roi"]))
            if power is not None and power < 100:
                power *= 1000        # 选人界面 k 后缀被 OCR 丢掉: 87 -> 87k
            cards.append({"name": card["name"], "click": card["click"],
                          "fire": fire, "mult": mult, "power": power})
            STATE.log(f"{card['name']}: 战力={power} 倍率={mult} 火框={fire}")
        fire_cards = [c for c in cards if c["fire"]]
        if fire_cards:
            # 倍率可读的排前(倍率大优先), 读不出的排后按战力
            def fire_key(c):
                if c["mult"] is not None:
                    return (0, -c["mult"], c["power"] or 1e18)
                return (1, 0, c["power"] or 1e18)
            chosen = min(fire_cards, key=fire_key)
            reason = "火框倍率最大" + ("" if chosen["mult"] is not None else "(倍率不可读,组内战力优先)")
        else:
            candidates = [c for c in cards if c["power"] is not None] or cards
            chosen = min(candidates, key=lambda c: c["power"] or 1e18)
            reason = "无火框, 战力最低"
        STATE.log(f"选择 {chosen['name']} ({reason})", "warn")
        self.controller.post_click(*chosen["click"]).wait()
        time.sleep(0.9)
        self.snap("选对手后")
        cont = self.match_tpl(self.snap("点继续前"), TPL_CONTINUE, ROI_TOPRIGHT)
        if cont:
            self.controller.post_click(*cont).wait()
            STATE.log("已点 CONTINUE")
            # 等对手页消失 (切 VS), 避免重复选人
            for _ in range(10):
                time.sleep(1.0)
                if not self.match_tpl(self.snap("等切屏"), TPL_REFRESH, (700, 15, 960, 115), th=0.7):
                    break

    def _tap(self, x: int, y: int) -> None:
        """驻留式点击: 按下-停-抬起。筛选面板会吃掉零时长的 post_click
        (爱心/关闭按钮实测无效), 必须给 Unity 一两帧指针停留。"""
        self.controller.post_touch_down(x, y).wait()
        time.sleep(0.18)
        self.controller.post_touch_up().wait()

    def _panel_open(self, img) -> bool:
        """筛选面板是否还开着 (右上角 X 模板)。"""
        return self.match_tpl(img, TPL_FILTER_X, ROI_FILTER_X, th=0.7) is not None

    def set_filter(self, chips) -> None:
        """打开筛选面板: 清空 -> 依次点亮 chips -> 关闭(验证失败自动重试)。"""
        self._tap(*FILTER_BTN)
        time.sleep(1.2)
        self._tap(*FILTER_CLEAR)
        time.sleep(0.4)
        for c in chips:
            self._tap(*c)
            time.sleep(0.4)
        for attempt in range(3):
            self._tap(*FILTER_CLOSE)
            time.sleep(1.0)
            if not self._panel_open(self.snap("筛选面板关闭验证")):
                return
            STATE.log(f"筛选面板未关闭, 重试 {attempt + 1}/2", "warn")
        STATE.log("筛选面板关闭失败, 带面板继续 (后续读数会异常)", "err")

    def _candidate_is_element(self, img, element: str) -> bool:
        """候选列第一张卡是否目标元素 (要求已归零滚动)。

        取首卡左缘框条 (x 37-49, y 470-620) 数元素色占比; light/neutral 返回 True
        (不验证)。用于确认筛选面板的清空/点亮确实生效。
        """
        ranges = ELEMENT_HUE.get(element)
        if not ranges:
            return True
        hsv = cv2.cvtColor(img[470:620, 37:49], cv2.COLOR_BGR2HSV)
        ok = 0
        for lo, hi in ranges:
            ok += cv2.countNonZero(cv2.inRange(hsv, (lo, 80, 90), (hi, 255, 255)))
        # 实测风首卡绿色 ~43%, 错元素卡为 0: 阈值 15% 两侧余量都足够
        return ok >= 0.15 * 12 * 150

    def refill_rule_slot(self, slot_i: int, chips, fav_chips) -> bool:
        """按 规则芯片+喜爱 筛选, 拖一个能量达标的合规角色进指定槽, 再还原筛选。

        返回 False = 筛选后无达标角色。规则槽能量耗尽 (FIGHT 弹能量窗) 时也走这里。
        """
        rule = STATE.pf_rule or {}
        want_el = rule.get("value") if rule.get("type") == "element" else None
        ok = False
        for attempt in range(2):
            self.set_filter(chips)
            for _ in range(4):
                self.controller.post_swipe(420, 570, 1150, 570, 400).wait()
                time.sleep(0.5)
            img_chk = self.snap("筛选复验")
            if self._candidate_is_element(img_chk, want_el or ""):
                ok = True
                break
            STATE.log("筛选后首卡元素不符 (清空/点亮可能被吃), 重开面板再筛", "warn")
        if not ok:
            STATE.log("筛选复验连续失败, 放弃本次补人", "err")
            return False
        ok = self.drag_best_to_slot(slot_i)
        self.set_filter(fav_chips)  # 取消规则筛选, 保留喜爱
        for _ in range(4):
            self.controller.post_swipe(420, 570, 1150, 570, 400).wait()
            time.sleep(0.5)
        return ok

    def _slot_fighter_name(self, img, slot_i: int) -> str:
        """OCR 防守队槽位卡的**角色名** band (卡面下方彩色条, 如 'CEREBELLA')。"""
        raw = self.ocr_text(img, DEF_SLOT_NAME_ROI[slot_i])
        return re.sub(r"[^A-Z]", "", raw.upper())

    def put_char_in_defense(self, slot_i: int, chip, want: str) -> bool:
        """筛选指定角色芯片 -> 拖候选卡进防守队槽位 -> 还原筛选 -> 按槽位角色名复验。

        与出战队选人 (refill_rule_slot) 同款思路, 但两处必须不同:
          ①防守队编辑器**没有能量黄钉**, 不能做能量判定 (做了就是全 0 空烧);
          ②版式不同 (三槽居中), 拖拽落点用 DEF_SLOT_DROP, 源点取候选列正中一张。
        返回 False = 筛选/拖拽/复验任一环节没成, 由调用方交回主循环。
        """
        fav = [FILTER_HEART] if STATE.filter_favorite else []
        self.set_filter([chip] + fav)
        self.drag_card(*DEF_DROP_SRC, *DEF_SLOT_DROP[slot_i])
        time.sleep(1.5)
        self.set_filter(fav)                     # 还原筛选, 别把面板留给后面的常规编队
        img = self.snap("防守队放人后")
        got = self._slot_fighter_name(img, slot_i)
        want_norm = re.sub(r"[^A-Z]", "", want.upper())
        if want_norm and (want_norm in got
                          or difflib.SequenceMatcher(None, want_norm, got).ratio() >= 0.7):
            STATE.log(f"防守队: 槽位{slot_i + 1} 已放入 {want} (OCR {got!r})")
            return True
        STATE.log(f"防守队: 槽位{slot_i + 1} 角色名复验不过 (OCR {got!r}, 期望 "
                  f"{want!r}) —— 筛选或拖拽没生效", "err")
        return False

    def judge_rule(self, img) -> bool:
        """判定当前**出战队**是否满足规则。

        游戏自身以 FIGHT 按钮颜色提示合规性: 满足=橙色(高饱和), 不满足=灰色。
        实测橙按钮高饱和亮色占比 ~55% (S中位251), 灰按钮接近 0%, 阈值取 15%。
        角色(class)规则只约束**防守队**, 出战队不受限 (用户 2026-09-29 口径) ——
        旧实现返回 False 是桩, 会让出战队每场都进"筛选替换"空烧, 已废弃。
        找不到 FIGHT 按钮 (不在编队页) 时返回 True, 不触发筛选。
        """
        rule = STATE.pf_rule
        if not rule:
            return True
        if rule["type"] == "class":
            return True
        fight = self.match_tpl(img, TPL_FIGHT, ROI_TOPRIGHT)
        if not fight:
            return True
        cx, cy = fight
        roi = img[max(cy - 18, 0):cy + 18, max(cx - 65, 0):cx + 65]
        hsv = cv2.cvtColor(roi, cv2.COLOR_BGR2HSV)
        mask = cv2.inRange(hsv, (0, 100, 120), (180, 255, 255))
        lit = cv2.countNonZero(mask) / mask.size
        STATE.log(f"规则判定: FIGHT 按钮高饱和占比 {lit * 100:.0f}% "
                  f"-> {'满足' if lit >= 0.15 else '不满足'}")
        return lit >= 0.15

    def drag_best_to_slot(self, slot_i: int) -> bool:
        """从当前(可能已筛选)候选区拖左一个能量达标的角色到指定槽位, 带校验重试。"""
        pages = 0
        while STATE.running:
            img = self.snap("规则选人")
            roster = vis.read_roster_energy(img)
            STATE.log(f"筛选后候选区能量 {roster}")
            src = next((i for i, e in enumerate(roster) if e >= STATE.energy_cost), None)
            if src is None:
                pages += 1
                if pages > 30:
                    return False
                self.scroll_roster(1150, 420)
                time.sleep(0.9)
                continue
            sx, sy = vis.ROSTER_CENTERS[src]
            self.drag_card(sx, sy, vis.SLOT_DROP_X[slot_i], 240)
            time.sleep(1.4)
            img2 = self.snap("规则拖拽后")
            if vis.read_slot_energy(img2)[slot_i] >= STATE.energy_cost:
                return True
            for _ in range(4):
                self.scroll_roster(420, 1150)
                time.sleep(0.4)
        return False

    def fix_team(self, replace_note: str) -> bool:
        """把能量不足的槽位换成候选区里能量达标的角色 (战力优先=从左往右)。

        返回 False 表示候选耗尽。
        """
        STATE.set_step(f"编队({replace_note})")
        # 首次编队: 按 WebUI 设置归位筛选 (清残留 + 喜爱芯片) —— 游戏内筛选跨运行持久
        if not self._filter_cleared:
            self._filter_cleared = True
            fav = [FILTER_HEART] if STATE.filter_favorite else []
            STATE.log(f"首次编队归位筛选: 喜爱筛选{'开' if fav else '关'}")
            self.set_filter(fav)
        # 归零滚动: 连续右滑, 回到候选列表起点
        for _ in range(4):
            self.controller.post_swipe(420, 570, 1150, 570, 400).wait()
            time.sleep(0.6)
        # 规则: FIGHT 灰则筛选拖入合规角色 (槽1->2->3), 直到 FIGHT 变橙
        rule_slot = None
        fav_chips = [FILTER_HEART] if STATE.filter_favorite else []
        rule_chips = list(fav_chips)
        if STATE.pf_rule:
            rule = STATE.pf_rule
            chip_tbl = ELEMENT_CHIPS if rule["type"] == "element" else CLASS_CHIPS
            chip = chip_tbl.get(rule["value"])
            if chip:
                rule_chips.insert(0, chip)
            if self._rule_done_fight == STATE.fight_no:
                rule_slot = 0  # 本场已保障
            else:
                img0 = self.snap("规则判定")
                if self.judge_rule(img0):
                    STATE.log("队伍已满足规则")
                else:
                    STATE.log(f"队伍不满足规则 {rule['type']}={rule['value']}, 筛选替换", "warn")
                    placed = 0
                    while placed < 3 and STATE.running:
                        # ⚠️ 2026-09-20 修: 这里原来是 `chips` —— 该名字在本函数里
                        # 根本不存在 (只有 rule_chips / fav_chips), 一进"规则不满足"
                        # 分支就 NameError 崩掉。以前所有场次 rule 都是 null,
                        # 这条路径从没被执行过, 所以 bug 一直躺着; 2026-09-20 首次
                        # 给场次设 element=dark (A SHOT IN THE DARK) 才踩到。
                        if not self.refill_rule_slot(placed, rule_chips, fav_chips):
                            STATE.log(f"规则补人失败 (复验不过或筛选池无达标能量, "
                                      f"已放入{placed}), 停止", "err")
                            return False
                        placed += 1
                        img0 = self.snap(f"规则复验{placed}")
                        if self.judge_rule(img0):
                            break
                    if not self.judge_rule(img0):
                        STATE.log("放入3人后 FIGHT 仍灰, 规则无法满足, 停止", "err")
                        return False
                    STATE.log("规则替换完成, 剩余槽位做能量替换", "warn")
                self._rule_done_fight = STATE.fight_no
                self._rule_redo = 0
                rule_slot = 0
        pages = 0
        slot_fails = {}  # 槽位 -> 连续失败次数 (用于落点微调)
        while STATE.running:
            img = self.snap("编队检视")
            if self.detail_open(img):
                STATE.log("误入角色详情页, 返回", "warn")
                self.controller.post_click(45, 40).wait()
                time.sleep(2.0)
                continue
            slots = vis.read_slot_energy(img)
            cost = STATE.energy_cost
            # 规则槽能量不足: 优先直接补合规角色 (不等能量弹窗, 也先于普通槽)
            if rule_slot == 0 and slots[0] < cost:
                STATE.log(f"槽位能量 {slots}, 规则槽1能量不足, "
                          f"优先筛选替换合规角色", "warn")
                if not self.refill_rule_slot(0, rule_chips, fav_chips):
                    STATE.log("规则补人失败 (复验不过或筛选池无达标能量), 停止", "err")
                    return False
                continue
            bad = [i for i, e in enumerate(slots) if e < cost and i != rule_slot]
            if not bad:
                STATE.log(f"槽位能量 {slots}, 全部达标(门槛{cost})")
                return True
            STATE.log(f"槽位能量 {slots}, 需替换槽位 {[i+1 for i in bad]}")

            roster = vis.read_roster_energy(img)
            STATE.log(f"候选区能量 {roster}")
            src = next((i for i, e in enumerate(roster) if e >= cost), None)
            if src is None:
                pages += 1
                if pages > 30:
                    STATE.log("候选区翻页超限, 没有可用能量的角色了", "err")
                    return False
                self.scroll_roster(1150, 420)
                time.sleep(0.9)
                self.snap(f"翻页{pages}")
                continue
            slot_i = bad[0]
            sx, sy = vis.ROSTER_CENTERS[src]
            dx = vis.SLOT_DROP_X[slot_i]
            fails = slot_fails.get(slot_i, 0)
            # 落点微调: 失败次数越多偏移越大 (判定区窄或被邻卡遮挡)
            offsets = [(0, 0), (14, 6), (-14, 10), (0, 18)]
            ox, oy = offsets[min(fails, len(offsets) - 1)]
            STATE.log(f"拖拽 候选[{src}](能量{roster[src]}) -> 槽位{slot_i+1}"
                      + (f" (偏移{ox},{oy})" if (ox or oy) else ""))
            self.drag_card(sx, sy, dx, 240, ox, oy)
            time.sleep(1.4)
            # 校验: 槽位没变好 => 记失败并归零候选列 (失败拖拽会滚动列表)
            img2 = self.snap("拖拽后")
            slots2 = vis.read_slot_energy(img2)
            if slots2[slot_i] < cost:
                slot_fails[slot_i] = fails + 1
                STATE.log(f"槽位{slot_i+1} 拖拽未生效, 归零候选列重试", "warn")
                for _ in range(4):
                    self.scroll_roster(420, 1150)
                    time.sleep(0.4)
            else:
                slot_fails[slot_i] = 0
        return False

    def fight_flow(self) -> None:
        """从 VS 界面: 进编队 -> 修正 -> FIGHT, 处理能量弹窗。若已在编队页则直接修正。"""
        STATE.set_step("进编队")
        img = self.snap("fight_flow入口")
        if not self.match_tpl(img, TPL_DRAGHINT, (430, 405, 850, 465), th=0.6):
            self.controller.post_click(637, 555).wait()  # TEAM 菱形
            time.sleep(1.6)
            img = self.snap("team_click后")
            x = self.find_popup_x(img)
            if x:
                STATE.log("能量弹窗出现, 关闭", "warn")
                self.controller.post_click(*x).wait()
                time.sleep(1.6)
                self.snap("关弹窗后")

        img = self.snap("编队页确认")
        if not self.match_tpl(img, TPL_DRAGHINT, (430, 405, 850, 465), th=0.6):
            STATE.log("未进入编队页 (无 DRAG 提示), 回到主循环重试", "warn")
            return

        rotations = 0
        while STATE.running:
            if not self.fix_team("替换能量不足" if rotations else "初始化"):
                STATE.status = "ERROR"
                STATE.log("编队失败: 无可用能量角色, 停止", "err")
                return
            img = self.snap("fight前")
            if (STATE.pf_rule and self._rule_done_fight == STATE.fight_no
                    and not self.judge_rule(img)):
                self._rule_redo += 1
                if self._rule_redo > 2:
                    STATE.status = "ERROR"
                    STATE.log("能量替换反复破坏规则 (FIGHT 仍灰), 停止", "err")
                    return
                STATE.log("能量替换后 FIGHT 变灰, 重做规则替换", "warn")
                self._rule_done_fight = -1
                continue
            fight = self.match_tpl(img, TPL_FIGHT, ROI_TOPRIGHT)
            if not fight:
                STATE.log("找不到 FIGHT 按钮, 回主循环", "warn")
                return
            self.controller.post_click(*fight).wait()
            time.sleep(2.5)
            img = self.snap("fight点击后")
            if self.find_lock_popup(img):
                # 角色场首入: FIGHT! 后弹「锁定难度」确认 (无 X)。点 CONTINUE 后
                # 游戏回选对手页 —— 交回主循环, 别再留在编队逻辑里。
                STATE.log("难度锁定弹窗: 点 CONTINUE 锁定, 交回主循环", "warn")
                self.controller.post_click(*LOCK_CONTINUE_BTN).wait()
                time.sleep(3.0)
                return
            ex = self.find_popup_x(img)
            if ex:
                rotations += 1
                if rotations > 15:
                    STATE.status = "ERROR"
                    STATE.log("能量弹窗循环超限, 停止", "err")
                    return
                STATE.log("FIGHT 后能量弹窗, 关闭并继续换人", "warn")
                self.controller.post_click(*ex).wait()
                time.sleep(1.6)
                if STATE.pf_rule:
                    img2 = self.snap("弹窗后检视")
                    slots2 = vis.read_slot_energy(img2)
                    cost = STATE.energy_cost
                    if any(e < cost for e in slots2):
                        bad_i = min(i for i, e in enumerate(slots2) if e < cost)
                        if bad_i > 0:
                            # 2/3号槽: 交回 fix_team 能量替换 (喜爱筛选, 元素不限)
                            STATE.log(f"槽位能量 {slots2}, 槽{bad_i + 1}不足, "
                                      f"由能量替换处理", "warn")
                        else:
                            # 仅规则槽(1号)缺能量时才补合规角色
                            STATE.log(f"槽位能量 {slots2}, 规则槽1不足, "
                                      f"筛选替换合规角色", "warn")
                            rule = STATE.pf_rule
                            fav_chips = [FILTER_HEART] if STATE.filter_favorite else []
                            chips = list(fav_chips)
                            chip_tbl = (ELEMENT_CHIPS if rule["type"] == "element"
                                        else CLASS_CHIPS)
                            chip = chip_tbl.get(rule["value"])
                            if chip:
                                chips.insert(0, chip)
                            if not self.refill_rule_slot(0, chips, fav_chips):
                                STATE.status = "ERROR"
                                STATE.log("规则补人失败 (复验不过或筛选池无达标能量), 停止",
                                          "err")
                                return
                continue
            STATE.log("战斗已开始, 等待结束...")
            if not self._battle_auto_checked:
                self._battle_auto_checked = True
                self.ensure_battle_auto()
            self.wait_battle_end()
            return

    def handle_results(self) -> None:
        """连续点击结算链上的 CONTINUE (XP/里程碑/streak 等), 最多 6 页。"""
        for i in range(6):
            img = self.snap(f"结算{i+1}")
            box = self.match_tpl(img, TPL_RESULT_CONTINUE, ROI_RESULT, th=0.7) or                 self.match_tpl(img, TPL_CONTINUE, ROI_TOPRIGHT)
            if not box:
                STATE.log("结算页结束")
                self.fights_since_rest += 1
                if (STATE.rest_every > 0 and STATE.rest_minutes > 0
                        and self.fights_since_rest >= STATE.rest_every):
                    STATE.rest_until = time.time() + STATE.rest_minutes * 60
                    STATE.log(f"已连续 {self.fights_since_rest} 场, 休息 "
                              f"{STATE.rest_minutes} 分钟回能 (下次结算后恢复计数)", "warn")
                    self.fights_since_rest = 0
                return
            STATE.log(f"点击结算 CONTINUE (第{i+1}页)")
            self.controller.post_click(*box).wait()
            time.sleep(2.2)

    def battle_speed_level(self, img) -> int:
        """战斗速度泡 (640,605): 0=无泡(auto未开或未渲染), 1/2/3=当前倍率。
        th=0.85: 同位实测 ≥0.95, 1x/3x 串扰实测 ≤0.76, 无泡画面 ≤0.14。"""
        roi = (600, 565, 690, 645)
        for lvl, tpl in ((3, TPL_SPD_3X), (2, TPL_SPD_2X), (1, TPL_SPD_1X)):
            if self.match_tpl(img, tpl, roi, th=0.85):
                return lvl
        return 0

    def brain_auto_on(self, img) -> bool:
        """脑子图标 (640,690) 亮=自动战斗开。实测亮 V均值~101, 灯灭 ~49, 阈值 75。"""
        crop = img[670:710, 620:660]
        return float(cv2.cvtColor(crop, cv2.COLOR_BGR2HSV)[:, :, 2].mean()) >= 75

    def ensure_battle_auto(self) -> None:
        """战斗开场检查自动战斗+3x 速度 (每次「开始」后的首场都查, 2026-10-02 口径)。
        开场介绍画面 ~2.5s 人物不动: 脑子(640,690)=auto 开关, 速度泡(640,605) 点一下升一档,
        两项设置游戏内跨场持久 —— 旧版因此每进程只查首场; 但「开始」之间用户可能
        手动关过 auto 或重开过游戏, 检查点改为随每次开始复位 (见 run() 起始块)。
        失败只告警, 不影响运行。"""
        time.sleep(1.8)
        try:
            img = self.snap("battle_auto")
            spd = self.battle_speed_level(img)
            if spd == 0 and not self.brain_auto_on(img):
                STATE.log("自动战斗未开启, 点脑子 (640,690)", "warn")
                self.controller.post_click(640, 690).wait()
                time.sleep(0.6)
                img = self.snap("battle_auto_brain")
                spd = self.battle_speed_level(img)
            if spd == 0:
                if self.brain_auto_on(img):
                    STATE.log("自动战斗已开但速度泡未识别, 跳过提速", "warn")
                else:
                    STATE.log("速度泡与脑子均未识别 (界面未就绪?), 跳过", "warn")
                return
            if spd < 3:
                STATE.log(f"战斗速度 {spd}x, 升档到 3x", "warn")
                for _ in range(3 - spd):
                    self.controller.post_click(640, 605).wait()
                    time.sleep(0.45)
                spd2 = self.battle_speed_level(self.snap("battle_auto_3x"))
                if spd2 == 3:
                    STATE.log("速度校验: 3x")
                else:
                    STATE.log(f"速度校验: {spd2 or '未识别'}x, 请留意", "warn")
            else:
                STATE.log("自动战斗已开, 速度 3x")
        except Exception as e:  # noqa: BLE001
            STATE.log(f"自动战斗检查异常 (不影响运行): {e}", "warn")

    def wait_battle_end(self) -> None:
        t0 = time.time()
        n = 0
        while STATE.running and time.time() - t0 < 300:
            time.sleep(4)
            n += 1
            img = self.snap(f"battle_{n}")
            srv = self.find_server_error(img)
            if srv:
                STATE.log("战斗中出现服务器错误弹窗, 点 OK", "warn")
                self.controller.post_click(*srv).wait()
                time.sleep(2.5)
                continue
            # 弹窗优先于"结束"判据: KEEP STREAK?/能量弹窗会盖住结算 CONTINUE,
            # 不剥掉就一直找不到结算 → 干等满 300s 超时。
            # (2026-09-19: 结算链与主循环都有 find_popup_x, 唯独这里漏了。)
            px = self.find_popup_x(img)
            if px:
                STATE.log("战斗等待中出现弹窗 (能量/连胜等), 点 X 关闭", "warn")
                self.controller.post_click(*px).wait()
                time.sleep(1.8)
                continue
            if self.match_tpl(img, TPL_DRAGHINT, (430, 405, 850, 465), th=0.6):
                STATE.log("回到编队页?", "warn")
                return
            if self.match_tpl(img, TPL_RESULT_CONTINUE, ROI_RESULT, th=0.7):
                STATE.log("出现结算 CONTINUE (战斗胜利)")
                return
            if self.match_tpl(img, TPL_CONTINUE, ROI_TOPRIGHT):
                STATE.log("出现 CONTINUE (战斗结束)")
                return
        if not STATE.running:
            return
        STATE.log("战斗等待超时 300s", "err")

    # ---------- 主循环 ----------

    def _screencap_bounded(self, timeout: float = 10.0):
        """带超时的截图封装; 卡住返回 None —— 主循环不许被 MAA 的内部重连拖死。

        2026-09-27 实测: MAA 的 AdbControlUnitMgr 在连接丢失后会先跑 `adb kill-server`
        再 connect, 而本机这条 kill-server 会死等 (maafw.log 里 duration=34825717ms
        ≈ 9h40m; 另一个同类子进程挂了一小时仍在跑)。于是 post_screencap().wait()
        永不返回: 主循环停在 IDLE 分支里连 STATE.quit 都读不到 —— 用户点「停止」只留
        一行日志, 点「开始」毫无反应, 进程还占着 WebUI 端口 (2026-09-27 01:02 现场)。
        超时后主循环照常转; 卡住那张作业留在 MAA 里, 下次靠它的 done 自愈 ——
        卡住期间不再往 MAA 里叠新作业 (否则每轮叠一个)。
        """
        if self._screencap_stuck():
            return None
        try:
            job = self.controller.post_screencap()
        except Exception:  # noqa: BLE001
            return None                      # 同旧行为: 取不到图就当这轮没图
        self._stuck_shot = job
        deadline = time.time() + timeout
        while not job.done and time.time() < deadline:
            time.sleep(0.05)
        if not job.done:
            return None                      # 卡住 (作业仍在 MAA 内), 见上方说明
        self._stuck_shot = None
        try:
            return job.get()
        except Exception:  # noqa: BLE001
            return None

    def _screencap_stuck(self) -> bool:
        job = self._stuck_shot
        if job is None:
            return False
        try:
            return not job.done
        except Exception:  # noqa: BLE001
            return False                     # 句柄不可用当没卡住, 让下一次调用自己报错

    def _warn_stuck_screencap(self) -> None:
        """截图卡住时每分钟提醒一次 —— "点了没反应"必须在日志里有答案。"""
        now = time.time()
        if now - self._stuck_shot_warned < 60:
            return
        self._stuck_shot_warned = now
        STATE.log("截图调用卡住未返回 (MAA 内部重连 / adb kill-server 死等), 本轮已跳过; "
                  "停止·开始仍可响应, 持续不恢复请重启 bot", "warn")

    def _screencap_resilient(self, attempts: int = 3, gap: float = 4.0):
        """snap() 的自愈层: 拿不到图不立刻判死 —— 2026-09-27 13:00 实测 MuMu 的 adbd
        会把 TCP 设备从 adb 表里掉线 (设备表只剩 emulator-5554), MAA 自带重连又会
        撞 kill-server 死等。这里连续失败时补一次 adb connect (幂等, 独立客户端进程,
        与 MAA 的僵死管道无关) 再试; 三次都失败才把异常抛回主循环走 ERROR 干净停机。
        """
        for i in range(attempts):
            img = self._screencap_bounded()
            if img is not None:
                self._shot_fails = 0
                return img
            self._shot_fails += 1
            if self._screencap_stuck():
                self._warn_stuck_screencap()
            if i < attempts - 1:
                if self._shot_fails >= 2:
                    ok = adb_connect()
                    STATE.log(f"截图连续失败 ({self._shot_fails}次), 补 adb connect -> "
                              f"{'成功' if ok else '失败'}", "warn")
                time.sleep(gap)
        return None

    def _respawn_with_resume(self) -> bool:
        """MAA 僵死后的自我重生: 拉起带 SGM_PF_RESUME=1 的新进程替自己续跑当前场次。

        限流 (debug/pf/respawn.json): 两次间隔 ≥5 分钟, 每自然日 ≤`_RESPAWN_MAX` 次 ——
        防 MuMu 真挂了时进入无限重生循环 (那种情况该触发的是人工检查, 不是转圈); 不限流
        不通过只返回 False, 由调用方**彻底退出**(见 run 的 LinkDead 分支), 不留僵尸。
        新进程 setup 成功后读环境变量自动 STATE.running=True; setup 失败则由它自己
        的 ERROR 路径兜底, 不会再来一层重生 (RESUME 只影响续跑, 不叠加重生)。

        新进程写**同一个** `debug/pf/pf.log`（`spawn_detached` 不再传 log_path,
        由 pf_logging.install() 接管 stdout 落盘）。装在 import 之前, 装晚了就
        接管不到; 打一行 `════ 进程启动 pid=… ════` 作为世代标记, 从此不用靠
        文件名猜哪一代活着。

        ⚠️ 2026-10-02 之前这里是 `bot_stdout_<时间戳>.log` 每代一个文件, 理由是
        `cmd >>` 会被老进程的句柄挡住(2026-09-28 重生空枪)。现在不用外部重定向了
        —— 落盘由 pf_logging 自己做, 内部持**跨进程行锁**(实测 6 进程并发 1200 行
        零丢行; 直接 append 同一文件会丢 161 行)。所以可以安全地共用一个文件。
        """
        now = time.time()
        today = time.strftime("%Y-%m-%d")
        data = {"day": today, "count": 0, "last": 0.0}
        try:
            data.update(json.loads(self._RESPAWN_MARKER.read_text(encoding="utf-8")))
        except Exception:  # noqa: BLE001  没有文件/坏了都从零计
            pass
        if data.get("day") != today:
            data = {"day": today, "count": 0, "last": 0.0}
        count = int(data.get("count") or 0)
        if now - float(data.get("last") or 0) < self._RESPAWN_GAP:
            STATE.log(f"重生受限: 距上次重生不足 {int(self._RESPAWN_GAP)}s, 不重复拉起", "err")
            return False
        if count >= self._RESPAWN_MAX:
            STATE.log(f"重生已用尽 (今日 {count}/{self._RESPAWN_MAX} 次), 不再拉起", "err")
            return False
        data.update(count=count + 1, last=now)
        try:
            self._RESPAWN_MARKER.parent.mkdir(parents=True, exist_ok=True)
            self._RESPAWN_MARKER.write_text(json.dumps(data), encoding="utf-8")
            # 不传 log_path: 新进程自己 install() 后写同一个 pf.log。
            spawn_detached('"%s" tools\\pf_bot.py' % sys.executable, str(PROJECT_ROOT),
                           log_path=None,
                           env_lines=('set "PYTHONUTF8=1"', 'set "PYTHONIOENCODING=utf-8"',
                                      'set "SGM_PF_RESUME=1"'))
        except Exception as e:  # noqa: BLE001
            STATE.log(f"重生拉起新进程失败: {e}", "err")
            return False
        STATE.log(f"自我重生 (今日第{data['count']}/{self._RESPAWN_MAX}次): "
                  f"新进程将自动续跑当前场次, 接管同一份 pf.log", "warn")
        return True

    def run(self) -> None:
        """常驻监督循环: WebUI 可随时 开始/暂停/停止 (停止则进程退出)。"""
        STATE.status = "IDLE"
        STATE.log("==== PF Bot 就绪, 等待开始 ====")
        while True:
            if STATE.quit:
                STATE.status = "STOPPED"
                STATE.log("==== PF Bot 已停止, 进程退出 ====")
                return
            if not self.ensure_connection():
                # MuMu 缺席/连接断 —— 服务保持在线等模拟器回来 (2026-09-30 用户口径:
                # "没检测到 mumu 就自动断"已废)。running 中的场次也在此挂起: 模拟器
                # 一上线自动接着跑, 不再烧重生额度。
                STATE.set_step("等待 MuMu")
                time.sleep(10)
                continue
            if not STATE.running:
                if STATE.scan_requested:
                    # 「连刷编排」页的立即扫描: 待命时去 PF hub 扫一遍录入 (不开跑)。
                    # 连接已由循环顶部保证。**必须兜底捕获 Exception** —— 扫描是可选
                    # 增强, 任何失败 (2026-10-02 实测: pf_nav 漏常量 NameError 穿透
                    # 此处带崩 run() 主循环, 服务整个退出) 都只许记日志; LinkDead
                    # 另有自愈/重生语义, 单独提示。NavAborted = 用户点了开始/停止,
                    # 是正常中止不是失败 (基线 False: running 一翻转即退)。
                    STATE.scan_requested = False
                    self._nav_running_at_entry = STATE.running
                    try:
                        self.goto_pf_hub()
                        self.scan_arenas()
                    except NavAborted:
                        STATE.log("手动扫描已中止 (收到开始/停止请求)", "warn")
                    except LinkDead as e:
                        STATE.log(f"手动扫描中断 (MAA 僵死, 稍后自愈/重生兜底): {e}", "err")
                    except Exception as e:  # noqa: BLE001
                        STATE.log(f"手动扫描失败 (不影响待命): {e}", "err")
                    STATE.step = "-"      # 扫完复位阶段标签 (别一直挂在"扫描场地")
                    continue
                if STATE.status == "RUNNING":
                    # 场次刚结束: 先看接力队列 —— 正常打完 (达标/手动结束/场景没找到)
                    # 且队列非空就自动接下一场; error 结束不接, 留给人工看现场。
                    nxt = self._queue_next()
                    if nxt is not None and STORE.get(nxt):
                        STORE.set_session(nxt)
                        apply_session(STORE.get(nxt))
                        # 无条件置位: 每次进 PF 都扫描录入今日场地 (绑定了再居中)。
                        STATE.scene_pending = True
                        # status 归位 IDLE: 本场视为已收尾, 下轮从「(重)开始」起始块
                        # 进新场 (自动战斗检查复位/场景导航都挂在那个块上, 漏了就是空枪)。
                        STATE.status = "IDLE"
                        STATE.running = True
                        continue
                    # 无暂停态: 结束/达标/关机前停跑都走这里 —— 场次结束, 回到待命。
                    # STOPPED 仍保留给进程真退出 (/api/stop, 托盘/调度按"等进程退出"依赖它)。
                    STATE.status = "IDLE"
                    STATE.log("==== 场次结束, 回到待命 ====")
                # 非运行态仍清理阻塞弹窗 (服务器错误/X), 防止屏幕卡死。
                # 截图走带超时的封装: MAA 内部重连有可能死等 (见 _screencap_bounded),
                # 不能让主循环陪着一起等 —— 那会让「停止」「开始」全部失效。
                img = self._screencap_bounded()
                if self._screencap_stuck():
                    self._warn_stuck_screencap()
                elif img is not None:
                    try:
                        srv = self.find_server_error(img)
                        if srv:
                            STATE.log("暂停期间出现服务器错误弹窗, 点按钮", "warn")
                            self.controller.post_click(*srv).wait()
                            time.sleep(2)
                        else:
                            x = self.find_popup_x(img)
                            if x:
                                self.controller.post_click(*x).wait()
                                time.sleep(1.5)
                    except Exception:  # noqa: BLE001
                        pass
                time.sleep(2)
                continue
            if self.tracker.ensure(STORE.session_id):
                # 换了场次开局: 重置采样基线/场次号 (暂停后续跑同场次不重置)
                STATE.fight_no = 0
                self._rule_done_fight = -1
                self._rule_redo = 0
                sess = STORE.get(STORE.session_id or "")
                rule = (sess or {}).get("rule")
                rule_desc = f"{rule['type']}={rule['value']}" if rule else "无"
                re_n, re_m = (sess or {}).get("rest_every") or 0, (sess or {}).get("rest_minutes") or 0
                rest_desc = f", 每{re_n}场休{re_m}分" if re_n > 0 and re_m > 0 else ""
                STATE.log(f"==== 场次「{(sess or {}).get('name', '?')}」开始"
                          f"（规则: {rule_desc}{rest_desc}）====")
                # 换场次: 休息计数/休息截止一并重置 (休息配置随场次)
                self.fights_since_rest = 0
                STATE.rest_until = 0
            if STATE.status != "RUNNING":
                STATE.status = "RUNNING"
                self._filter_cleared = False   # 每次(重)开始: 首次编队重新按设置归位筛选
                self._defense_done = False     # 换场次: 防守队弹窗重新允许触发 (每 PF 一次)
                # 自动战斗/速度检查随每次「开始」复位 (2026-10-02 用户口径: 旧版每进程
                # 只查首场, 开始之间手动关过 auto 或重开过游戏就查不到了)。
                self._battle_auto_checked = False
                # 达标关机的"只做一次"标记: 分数还没到目标才复位, 避免刚恢复运行
                # 就因为当前分已超标而立刻又关一次模拟器。
                if STATE.score_target is None or (STATE.score or 0) < STATE.score_target:
                    self._goal_closed = False
                STATE.log("==== PF Bot 运行中 ====")
                if STATE.scene_pending:
                    # 场景识别/导航 (2026-10-02): 每次点「开始」都走回 PF hub 扫描
                    # 录入今日场地; 绑定了场地的场次再把该场居中开打。
                    # kind 分级: scene=场地没找到 / nav=认路失败 (都停, 队列不自动接);
                    # stopped=用户中途结束 (正常运行态, 什么都不做, 回待命)。
                    STATE.scene_pending = False
                    ok, _title, kind = self.navigate_to_scene(STATE.scene)
                    if not ok:
                        STATE.end_reason = "scene" if kind == "scene" else "error"
                        STATE.running = False
                        continue
            try:
                self.step()
            except LinkDead as e:
                # 自愈已尽力仍拿不到图 = MAA 内部作业僵死 (进程内无解): 换进程重生 (带续跑);
                # 限流/拉起失败则彻底退出 —— MAA 已救不回, 留着只会当僵尸占端口、占日志。
                STATE.status = "ERROR"
                STATE.log(f"{e}", "err")
                if self._respawn_with_resume():
                    STATE.log("本进程退出, 由新进程接管", "warn")
                    _hard_exit(0)
                STATE.log("==== 连接僵死且无法重生, 彻底退出 (人工检查后重新启动) ====", "err")
                _hard_exit(3)
            except Exception as e:  # noqa: BLE001
                STATE.status = "ERROR"
                STATE.end_reason = "error"     # 异常结束不接续队列 (要人工看现场)
                STATE.log(f"运行异常: {e}", "err")
                STATE.running = False
                continue  # 不 return: 保持监督循环存活, WebUI 可重新开始
            if STATE.status == "ERROR":
                STATE.end_reason = "error"     # 同上: step() 内部判定的错误也不接续
                STATE.running = False
                STATE.log("==== 运行出错已停止 (查看日志后可在 WebUI 重新开始) ====", "err")

    def step(self) -> None:
        """状态机单步: 截图一次并按优先级处理当前界面。"""
        if not STATE.running:
            return
        if STATE.rest_until > time.time():
            remain = int((STATE.rest_until - time.time()) / 60) + 1
            STATE.step = f"休息中 (剩 ~{remain} 分钟, 回能)"
            try:
                img = self._screencap_bounded()
                if self._screencap_stuck():
                    self._warn_stuck_screencap()
                elif img is not None:
                    srv = self.find_server_error(img)
                    if srv:
                        STATE.log("休息期间出现服务器错误弹窗, 点按钮", "warn")
                        self.controller.post_click(*srv).wait()
                        time.sleep(2)
                    else:
                        x = self.find_popup_x(img)
                        if x:
                            self.controller.post_click(*x).wait()
                            time.sleep(1.5)
            except Exception:  # noqa: BLE001
                pass
            time.sleep(5)
            return
        if STATE.rest_until:
            STATE.rest_until = 0
            STATE.log("休息结束, 继续运行", "warn")
        img = self.snap()
        # 弹窗最优先: 遮罩会压暗其他按钮, 但模板匹配对亮度不敏感, 必须先判弹窗
        x = self.find_popup_x(img)
        if x:
            self.unknown_tries = 0
            STATE.log("弹窗 (能量/streak/OPTIONS), 关闭", "warn")
            self.controller.post_click(*x).wait()
            time.sleep(1.5)
        elif self.find_server_error(img):
            ok = self.find_server_error(img)
            self.unknown_tries = 0
            STATE.log("服务器错误弹窗, 点 OK", "warn")
            self.controller.post_click(*ok).wait()
            time.sleep(2.5)
        elif self.detail_open(img):
            STATE.log("误入角色详情页, 返回", "warn")
            self.controller.post_click(45, 40).wait()
            time.sleep(2.0)
        elif self.match_tpl(img, TPL_HUB_PLAY, (500, 400, 780, 530), th=0.7):
            # PF 主页面 (延迟导致的误退出会落到这里): 点中心 PLAY! 重新进入
            self.unknown_tries = 0
            STATE.set_step("PF 主页面")
            STATE.log("检测到 PF 主页面, 点 PLAY! 进入", "warn")
            box = self.match_tpl(img, TPL_HUB_PLAY, (500, 400, 780, 530), th=0.7)
            self.controller.post_click(*box).wait()
            time.sleep(3.0)
            self.snap("play点击后")
        elif (not self._defense_done and STATE.fight_no <= 1
              and self.find_defense_popup(img)):
            # 防守队弹窗: 压在选对手页之上, 底下的 REFRESH 仍命中, 所以必须排在
            # REFRESH 分支**前面**, 否则 bot 会一直"选对手→CONTINUE"空转 (2026-09-20)。
            # 每场次只处理一次: 失败也置位, 避免无限点 OK (2026-09-18 空烧教训)。
            self.unknown_tries = 0
            self._defense_done = True
            if not self.setup_defense_team():
                STATE.log("防守队处理未成功, 本场次不再重试 (需人工看截图)", "err")
        elif self.match_tpl(img, TPL_DEFENSE_CONFIRM, ROI_TOPRIGHT):
            # 防守队编辑器本体 (右上 CONFIRM): 这一页**没有能量黄钉**, 绝不能当常规
            # 编队页处理 —— 2026-09-29 实测: 槽位/候选能量全读 0, 空烧到「无可用能量
            # 角色」停摆。排在 DRAGHINT 判据前面 (编辑器同样显示 DRAG 提示)。
            self.unknown_tries = 0
            self._defense_done = True
            if not self.setup_defense_team():
                STATE.log("防守队编辑器处理未成功, 本场次不再重试 (需人工看截图)", "err")
        elif self.find_lock_popup(img):
            # 角色场「锁定难度」确认弹窗 (无 X, CANCEL/CONTINUE): 点 CONTINUE 锁定。
            # 必须排在 REFRESH/FIGHT 判据前 —— 弹窗背后的 FIGHT! 仍可见, 否则会
            # 反复走 fight_flow (2026-09-29 用户截图取证)。
            self.unknown_tries = 0
            STATE.log("难度锁定弹窗: 点 CONTINUE 锁定", "warn")
            self.controller.post_click(*LOCK_CONTINUE_BTN).wait()
            time.sleep(2.5)
        elif self.match_tpl(img, TPL_REFRESH, (700, 15, 960, 115), th=0.7):
            self.unknown_tries = 0
            self.track_score(img)
            STATE.fight_no += 1
            self.pick_opponent(img)
        elif self.match_tpl(img, TPL_DRAGHINT, (430, 405, 850, 465), th=0.6):
            self.unknown_tries = 0
            self.fight_flow()
        elif self.match_tpl(img, TPL_FIGHT, ROI_TOPRIGHT):
            self.unknown_tries = 0
            self.fight_flow()
        elif self.match_tpl(img, TPL_RESULT_CONTINUE, ROI_RESULT, th=0.7) or                     self.match_tpl(img, TPL_CONTINUE, ROI_TOPRIGHT):
            STATE.set_step("战斗结算")
            self.handle_results()
        else:
            STATE.set_step("等待已知界面")
            self.unknown_tries = getattr(self, "unknown_tries", 0) + 1
            STATE.log(f"未知界面, 等待 3s (第{self.unknown_tries}次恢复尝试)")
            time.sleep(3)
            img2 = self.snap("未知界面")
            if not any([
                self.match_tpl(img2, TPL_REFRESH, (700, 15, 960, 115), th=0.7),
                self.match_tpl(img2, TPL_DRAGHINT, (430, 405, 850, 465), th=0.6),
                self.find_popup_x(img2),
                self.match_tpl(img2, TPL_FIGHT, ROI_TOPRIGHT),
                self.match_tpl(img2, TPL_CONTINUE, ROI_TOPRIGHT),
                self.match_tpl(img2, TPL_RESULT_CONTINUE, ROI_RESULT, th=0.7),
            ]):
                # 交替按 左上返回键 / 右上关闭X: 某些界面左上是设置齿轮,
                # 只按返回键会打开 OPTIONS 菜单卡死
                if self.unknown_tries % 2 == 1:
                    STATE.log("仍未知, 按返回键", "warn")
                    self.controller.post_click(45, 40).wait()
                else:
                    STATE.log("仍未知, 按右上角关闭 X", "warn")
                    self.controller.post_click(1188, 37).wait()
                time.sleep(3.5)


def main() -> int:
    start_webui()
    STATE.log(f"WebUI: http://127.0.0.1:{WEBUI_PORT}")
    bot = PfBot()
    try:
        bot.setup()
    except Exception as e:  # noqa: BLE001
        STATE.status = "ERROR"
        STATE.step = str(e)[:100]        # 托盘读 /api/state 的 step 给出真实原因
        STATE.log(f"初始化失败: {e}", "err")
        _arm_exit_watchdog()             # 退出前挂保险丝: MAA 销毁卡死也能退干净
        return 1
    if os.environ.get("SGM_PF_RESUME") == "1" and STORE.session_id:
        # 自我重生的新进程: 接着死前那场跑 (场次指针已持久化)。
        # 场次配置套用与 WebUI /api/start 同一份 apply_session (首版手抄漏字段,
        # 48.6M/50M 不会自动收官 —— 别再拆两份)。重生是原场续跑, 不做场景导航。
        sess = STORE.get(STORE.session_id) or {}
        apply_session(sess)
        STATE.running = True
        STATE.log(f"自动续跑场次「{sess.get('name', '?')}」(重生接管)", "warn")
    try:
        bot.run()
    except Exception as e:  # noqa: BLE001
        STATE.status = "ERROR"
        STATE.log(f"运行异常: {e}", "err")
        _arm_exit_watchdog()
        return 1
    _arm_exit_watchdog()
    return 0


if __name__ == "__main__":
    sys.exit(main())
