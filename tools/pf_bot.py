"""Skullgirls Mobile Prize Fight 自动化主程序。

流程: 选对手(火框倍率最大/无火选战力最低) -> 编队(能量>=4的角色拖入槽位)
      -> FIGHT(自动战斗) -> Continue 领奖 -> 循环。
能量不足弹窗: 关闭后自动进编队, 只替换能量不足的槽位。

用法: python tools/pf_bot.py
WebUI: http://127.0.0.1:8787
"""
from __future__ import annotations

import json
import re
import sys
import time
from pathlib import Path

from pf_env import (
    resolve_adb,
    PROJECT_ROOT,
    STATE,
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
from pf_store import STORE, ScoreTracker  # noqa: E402
from pf_webui import start_webui  # noqa: E402

RESOURCE_DIR = PROJECT_ROOT / "assets" / "resource" / "base"
SHOT_DIR = PROJECT_ROOT / "debug" / "pf" / "run"

IMG = "pf/"
TPL_CONTINUE = IMG + "pf_continue_btn.png"
TPL_FIGHT = IMG + "vs_fight_btn.png"
TPL_FILTER_X = IMG + "pf_filter_x.png"
TPL_DRAGHINT = IMG + "drag_hint.png"
TPL_ENERGY_X = IMG + "energy_x.png"
TPL_REFRESH = IMG + "pf_refresh_btn.png"
TPL_RESULT_CONTINUE = IMG + "result_continue.png"
TPL_STREAK_X = IMG + "streak_x.png"
TPL_DETAIL_STATS = IMG + "detail_stats.png"
TPL_OPTIONS_X = IMG + "options_x.png"
TPL_HUB_PLAY = IMG + "hub_play.png"
TPL_SRV_OK = IMG + "srv_ok.png"
TPL_SRV_RETRY = IMG + "srv_retry.png"
ROI_POPUP = (860, 160, 1060, 340)   # 弹窗右上 X 区域

ROI_TOPRIGHT = (1040, 15, 1260, 115)  # x0,y0,x1,y1
# 编队页筛选面板芯片坐标 (1280x720, 见 docs/screenshots/filter_panel.png)
FILTER_BTN = (1215, 395)
FILTER_CLEAR = (272, 126)
FILTER_CLOSE = (1228, 85)
FILTER_HEART = (616, 237)
FILTER_COLS = [197, 302, 407, 512, 616, 721]
ELEMENT_CHIPS = {"fire": (197, 370), "water": (302, 370), "wind": (407, 370),
                 "light": (512, 370), "dark": (616, 370), "neutral": (721, 370)}
CLASS_CHIPS = {f"c{i+1}": (FILTER_COLS[i % 6], 505 if i < 6 else 594) for i in range(12)}

ROI_RESULT = (400, 570, 980, 700)     # 结算页底部 CONTINUE 行
ROI_FILTER_X = (1150, 30, 1280, 140)  # 筛选面板关闭 X 搜索区
# 候选卡左缘框条的元素色相区间 (light/neutral 不可颜色分辨 -> 不做筛选复验)
ELEMENT_HUE = {"fire": [(0, 9), (170, 180)], "water": [(96, 130)],
               "wind": [(50, 97)], "dark": [(131, 146)]}


class PfBot:
    def __init__(self) -> None:
        self.controller: AdbController | None = None
        self.tasker = Tasker()
        self.shot_seq = 0
        self.tracker = ScoreTracker()  # 总分采样基线（随场次自动重置）
        self._rule_done_fight = -1    # 本场已做过规则替换的场次号
        self._rule_redo = 0           # 本场规则重做次数 (能量替换破坏规则时++)
        self._filter_cleared = False  # 本次运行是否已清过残留筛选
        self.fights_since_rest = 0    # 距上次休息的已结算场数
        self.run_dir = SHOT_DIR / time.strftime("%m%d_%H%M%S")

    # ---------- 基础设施 ----------

    def setup(self) -> None:
        self.run_dir.mkdir(parents=True, exist_ok=True)
        start_debug_cleaner(self.run_dir, log=STATE.log)  # 图片≤150MB / 日志≤50MB
        Toolkit.init_option(str(PROJECT_ROOT / "debug"))
        adb_path, address = resolve_adb()
        if not adb_path:
            raise RuntimeError("未找到 MuMu adb: 请在 config.json 配置 adb_path "
                               "(参考 config.example.json) 或确认模拟器已启动")
        self.controller = AdbController(
            adb_path=adb_path,
            address=address,
            screencap_methods=int(MaaAdbScreencapMethodEnum.Default),
            input_methods=int(MaaAdbInputMethodEnum.Default),
        )
        self.controller.post_connection().wait()
        if not self.controller.connected:
            raise RuntimeError("连接 MuMu 失败")
        STATE.log(f"[ok] 已连接 {adb_path} @ {address}")

        resource = Resource()
        resource.use_cpu()  # 本机 DirectML 枚举不到适配器, OCR 用 CPU 推理
        job = resource.post_bundle(str(RESOURCE_DIR))
        job.wait()
        if not job.succeeded:
            raise RuntimeError("资源加载失败 (检查 model/ocr 与 image/pf)")
        STATE.log("[ok] 资源加载完成")

        if not self.tasker.bind(resource, self.controller):
            raise RuntimeError("Tasker 绑定失败")
        if not self.tasker.inited:
            raise RuntimeError("Tasker 初始化失败")

    def snap(self, tag: str = ""):
        """截图: 存档 + 推送 WebUI, 返回图像。"""
        img = self.controller.post_screencap().wait().get()
        if img is None:
            raise RuntimeError("截图失败")
        self.shot_seq += 1
        name = f"{self.shot_seq:04d}_{tag}.jpg"
        path = self.run_dir / name
        # cv2.imwrite 对非 ASCII 路径会静默失败, 用 imencode + write_bytes
        ok, buf = cv2.imencode(".jpg", img, [cv2.IMWRITE_JPEG_QUALITY, 85])
        if ok:
            path.write_bytes(buf.tobytes())
        latest = PROJECT_ROOT / "debug" / "pf" / "web_latest.jpg"
        ok2, buf2 = cv2.imencode(".jpg", img, [cv2.IMWRITE_JPEG_QUALITY, 85])
        if ok2:
            latest.write_bytes(buf2.tobytes())
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
            STATE.log(f"总分 {val:,} 已达上限 {STATE.score_target:,}, 自动暂停", "warn")
            STATE.status = "PAUSED"
            STATE.running = False
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

    def judge_rule(self, img) -> bool:
        """判定当前队伍是否满足规则。

        游戏自身以 FIGHT 按钮颜色提示合规性: 满足=橙色(高饱和), 不满足=灰色。
        实测橙按钮高饱和亮色占比 ~55% (S中位251), 灰按钮接近 0%, 阈值取 15%。
        类别规则 sgm 无数据, 维持不满足 -> 走筛选替换流程。
        找不到 FIGHT 按钮 (不在编队页) 时返回 True, 不触发筛选。
        """
        rule = STATE.pf_rule
        if not rule:
            return True
        if rule["type"] == "class":
            return False
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
        # 规则未启用: 本次运行清一次残留筛选即可 (零开销)
        if not STATE.pf_rule and not self._filter_cleared:
            self.set_filter([])
            self._filter_cleared = True
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
                        if not self.refill_rule_slot(placed, chips, fav_chips):
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
            if slots2[slot_i] < vis.ENERGY_COST:
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
            if self.match_tpl(img, TPL_DRAGHINT, (430, 405, 850, 465), th=0.6):
                STATE.log("回到编队页?", "warn")
                return
            if self.match_tpl(img, TPL_RESULT_CONTINUE, ROI_RESULT, th=0.7):
                STATE.log("出现结算 CONTINUE (战斗胜利)")
                return
            if self.match_tpl(img, TPL_CONTINUE, ROI_TOPRIGHT):
                STATE.log("出现 CONTINUE (战斗结束)")
                return
        STATE.log("战斗等待超时 300s", "err")

    # ---------- 主循环 ----------

    def run(self) -> None:
        """常驻监督循环: WebUI 可随时 开始/暂停, 进程不退出。"""
        STATE.status = "IDLE"
        STATE.log("==== PF Bot 就绪, 等待开始 ====")
        while True:
            if not STATE.running:
                if STATE.status == "RUNNING":
                    STATE.status = "STOPPED"
                    STATE.log("==== PF Bot 已暂停 ====")
                # 暂停期间仍清理阻塞弹窗 (服务器错误/X), 防止屏幕卡死
                try:
                    img = self.controller.post_screencap().wait().get()
                    if img is not None:
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
                STATE.log("==== PF Bot 运行中 ====")
            try:
                self.step()
            except Exception as e:  # noqa: BLE001
                STATE.status = "ERROR"
                STATE.log(f"运行异常: {e}", "err")
                STATE.running = False
                continue  # 不 return: 保持监督循环存活, WebUI 可重新开始
            if STATE.status == "ERROR":
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
                img = self.controller.post_screencap().wait().get()
                if img is not None:
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
    STATE.log("WebUI: http://127.0.0.1:8787")
    bot = PfBot()
    try:
        bot.setup()
    except Exception as e:  # noqa: BLE001
        STATE.status = "ERROR"
        STATE.log(f"初始化失败: {e}", "err")
        return 1
    try:
        bot.run()
    except Exception as e:  # noqa: BLE001
        STATE.status = "ERROR"
        STATE.log(f"运行异常: {e}", "err")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
