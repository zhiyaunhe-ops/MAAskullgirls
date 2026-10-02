"""PF 场景导航 / 场地扫描 (2026-10-02 自 pf_bot.py 拆出 —— 用户要求功能独立)。

职责: PF hub 认路 (goto_pf_hub)、轮播读卡与场地识别 (read_center_card)、
今日场地扫描录入 (scan_arenas -> debug/pf/arenas.json, **一天一次**:
刷新线每天凌晨 01:00, 之后重扫过期)、场地绑定居中 (center_scene: 名字子串 /
#N 位置)、开始时一次性导航 (navigate_to_scene, 含接力队列所需的场景识别)。

SceneNav 是 mixin: 依赖宿主 pf_bot.PfBot 提供截图/模板/OCR/点击基础设施
(snap / match_tpl / ocr_text / controller) 与 _adb / _tpl_cache 两个字段;
pf_scene.py 的 PfScene 经 PfBot 继承沿用同一份读卡逻辑, 标定注释随代码住这里。
"""
from __future__ import annotations

import difflib
import json
import re
import subprocess
import time
from datetime import datetime, timedelta
from pathlib import Path

from pf_env import (PROJECT_ROOT, STATE, SUBPROC_TEXT,
                    mumu_launch_game, preload_msvcrt, resolve_adb)

preload_msvcrt()  # 必须早于 cv2/MAA 导入 (独立 import pf_nav 也要安全)

import cv2  # noqa: E402

IMG = "pf/"   # 资源 image/pf/ 下的模板前缀 (与 pf_bot 同名同值)
TPL_HUB_PLAY = IMG + "hub_play.png"   # PF hub 居中卡 PLAY! (pf_bot.step 同用, 单一定义)


class NavAborted(Exception):
    """导航/扫描中途用户结束或停止 (running 翻转 / quit 置位) —— 协作式主动退出, 不是失败。

    没有它时: 用户点「结束」后导航/扫描仍把剩下的滑动走完 (实测多刷 10s+
    还在居中), 结束后又冒出"已到第 N 个场地"这类动作, 用户像在看卡死。
    """
RESOURCE_DIR = PROJECT_ROOT / "assets" / "resource" / "base"
ARENAS_PATH = PROJECT_ROOT / "debug" / "pf" / "arenas.json"   # 场地扫描录入 (一天一次, 01:00 过期)

REFRESH_HOUR = 1   # 场地刷新线: 每天凌晨 01:00 (用户口径 2026-10-02, 本机时区)


def refresh_boundary(now: float = None) -> float:
    """最近一条场地刷新线 (每天 01:00) 的 epoch。00:30 时线在昨天 01:00。"""
    dt = datetime.fromtimestamp(now if now is not None else time.time())
    boundary = dt.replace(hour=REFRESH_HOUR, minute=0, second=0, microsecond=0)
    if dt < boundary:
        boundary -= timedelta(days=1)
    return boundary.timestamp()


def game_day(now: float = None) -> str:
    """当前"游戏日"标签 (= 最近一条刷新线所在日期), 用于展示与旧文件兼容。"""
    return datetime.fromtimestamp(refresh_boundary(now)).strftime("%Y-%m-%d")


def arenas_fresh(data, now: float = None) -> bool:
    """扫描缓存是否有效: 时间戳晚于最近一条 01:00 刷新线即有效 (一天一次就够)。

    旧格式 (只有 day 没有 ts) 按游戏日字符串兼容; 无 ts 且日期不符 = 过期。"""
    if not isinstance(data, dict):
        return False
    ts = data.get("ts")
    if isinstance(ts, (int, float)) and not isinstance(ts, bool):
        return ts >= refresh_boundary(now)
    return data.get("day") == game_day(now)

# ---- 场景识别 / 接力导航 (2026-10-02: 场地绑定 + 打完自动切场) ----
# 与 pf_scene.py 同源的坐标/模板; 阈值沿用那边实测标定, 改动须有任务依据。
TPL_HALL_PRIZE = IMG + "hall_prize_fights.png"  # 大厅 PRIZE FIGHTS 菱形
TPL_SCENE_X = IMG + "scene_popup_x.png"         # 大厅促销弹窗 X (BACK TO SCHOOL 等)
TPL_MODAL_X = IMG + "popup_close_x.png"         # 弹窗通用方形关闭 X (每日奖励等)

# 结算/脱离用 (单一来源: pf_bot 反向导入这四个, 别再各养一份)
TPL_CONTINUE = IMG + "pf_continue_btn.png"        # 结算 CONTINUE (残局脱离用)
TPL_RESULT_CONTINUE = IMG + "result_continue.png"  # VICTORY 结算页底部 CONTINUE
ROI_RESULT = (400, 570, 980, 700)     # 结算页底部 CONTINUE 行
ROI_TOPRIGHT = (1040, 15, 1260, 115)  # x0,y0,x1,y1 (右上按钮通用区)
ROI_HUB_PLAY = (500, 400, 780, 530)   # PF hub 居中卡 PLAY!
ROI_SCENE_X = (880, 30, 1240, 180)    # 促销弹窗右上 X (历史 ROI, 见 pf_scene 同名注释)
ROI_MODAL_X = (700, 0, 1060, 300)     # 模态弹窗方形 X (上限 1060 挡 OPTIONS X, pf_scene 同源)
MODAL_X_PEAK_MARGIN = 0.05            # cv2 模态 X 的峰值唯一性判据 (pf_scene 实测标定)
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
HOME_BTN = (115, 37)                  # 顶栏房子: 从 PF 任意页回大厅
NAV_TIMEOUT = 300.0                   # 场景导航总超时 (300s: 含冷启动拉游戏; 战斗中途点
                                      # 「开始」/结束后重开时要等残留战斗打完才回得了大厅,
                                      # 一场战斗 60~120s + 结算页 + 认路, 240s 会卡边)
# 轮播滑动: MAA post_swipe 瞬时释放会被吸附轮播弹回, 用 adb input swipe (pf_scene 实测);
# 340px+600ms: 520px 大步会因惯性一次跳 2 张卡, 中间场地会被漏扫。
SWIPE_L = ("input", "swipe", "900", "400", "560", "400", "600")
SWIPE_R = ("input", "swipe", "560", "400", "900", "400", "600")


class SceneNav:
    """场景识别 / 接力导航。坐标/模板与 pf_scene.py 同源 (标定注释随方法走)。"""

    # 坐标/阈值与 pf_scene.py 同源 (见常量区注释); 读卡逻辑整体从 PfScene 移入,
    # pf_scene 改为委托本类, 两处不再各养一份。

    # 场地名字库 (历史收录; OCR 命中与否影响"未收录"兜底路径, 关键词子串匹配不受限)。
    KNOWN_TITLES = [
        "EYE OF THE STORM", "AGAINST THE WIND", "TRIAL BY FIRE", "THE BIG THAW",
        "A CLASS OF ONE'S OWN", "SEEING STARS", "NIGHT'S GHOUL", "BLOOD SPORT",
        "MEDICI SHAKEDOWN", "ROSHAMBOH", "GOLD RUSH", "BELLE OF THE BRAWL",
        "DEATH METTLE",
        # 2026-09-20: 暗元素场。卡面 OCR 稳定丢空格且丢字母 I, 读成
        # 'ASHOTINTHEDARK' / 'ASHOTNTHEDARK' —— 靠 difflib (cutoff 0.55) 归一到本名,
        # 否则 center('A SHOT IN THE DARK') 会 10 步转不到 (实测 01:27 失败)。
        "A SHOT IN THE DARK",
        # 2026-09-22: Annie 的角色场名 (游戏日 09-21 周一新开)。实机实证:
        # BRONZE + INFINITY AND BEYOND, 无 SCORE 行, 剩 02D:23H:55M, 立绘绿发星饰;
        # 快照 Current Character PF 当日 Marie(收)→Annie(开), 与 §6.12.2 该周
        # Annie/Big Band 对、周一-三=前一个 吻合。OCR 实读 'NFINITYAND BETOND'
        # (丢首字母 I、丢 Y), difflib 能归一, 但**必须先收录**, 否则 fallback 读数
        # 会被丢弃 (见 read_center_card 2026-09-22 注)。
        "INFINITY AND BEYOND",
        # 2026-09-25: Big Band 的角色场名 (游戏日 09-24 周四半周切换新开)。实机实证:
        # BRONZE + BIG BEN'S BEATDOWN, 无 SCORE 行, 剩 02D:23H:56M, 立绘礼帽+耳机+
        # 喇叭手臂 = Big Band; 快照 Current Character PF 当日 Annie→Big Band, 与
        # §6.12.2 该周 Annie/Big Band 对、周四-六=后一个 吻合。OCR 实读
        # 'IG BEN'S BEATDOWN' (丢首字母 B, BEN=BAND 的变体), difflib 能归一。
        # ⚠️ 此卡与 DEATH METTLE/INFINITY AND BEYOND 同款版式: 顶部是 BRONZE
        # 层级标签+装饰图, SCORE ROI (y138-185) 框到装饰区, 读数是随机噪声
        # ('ROgO0' / '900'), 见 read_center_card 2026-09-25 注。
        "BIG BEN'S BEATDOWN",
    ]

    # 卡片上的"难度层级"文字与场次名**同框**: ROI_CARD_TITLE=(500,290,780,378) 实测同时
    # 框到层级小字与场地名大字 (2026-09-16 用 hub_scan/02.png 裁图实证)。所以 OCR 出来的
    # 是 "DIAMOND BLOOD SPORT" 这类带层级前缀的串 —— 历史记录里那个
    # "DIAMOND NIGHT'S GHOUL" 就是这么来的 (DIAMOND 是层级, 不属于名字)。
    # 但**不能无脑删前缀**: "GOLD RUSH" 本身就以 GOLD 开头。所以做法是把
    # "原串 / 去前缀串" 都当候选, 谁跟已知场地名更接近用谁。
    TIER_PREFIXES = ("BRONZE", "SILVER", "GOLD", "DIAMOND")

    def _adb_shell(self, args: list) -> str:
        """直连 adb shell (轮播滑动走它 —— MAA post_swipe 瞬时释放会被吸附轮播弹回)。

        ⚠️ args 前面必须包一层 `shell` 子命令: 2026-10-02 实测漏写时
        `adb -s addr input swipe ...` 报 "unknown command input" 立即退出,
        stderr 又被吞掉 → 滑动静默失效, 扫描把"第一张滑不动"误判成"轮播到头"
        只录到 1 场 (用户看直播抓到)。失败必须打日志。"""
        if self._adb is None:
            adb_path, address = resolve_adb()
            if not adb_path:
                raise RuntimeError("场景导航需要 adb (config.json 配 adb_path 或先连接)")
            self._adb = (adb_path, address)
        p = subprocess.run([self._adb[0], "-s", self._adb[1], "shell", *args],
                           capture_output=True, timeout=30, **SUBPROC_TEXT)
        err = (p.stderr or "").strip()
        if p.returncode != 0 or err:
            STATE.log(f"adb shell {' '.join(args[:2])} 失败 (rc={p.returncode}): "
                      f"{(err or (p.stdout or '').strip())[:160]}", "warn")
        return (p.stdout or "").strip()

    def swipe_left(self) -> None:
        """轮播下一张 (340px+600ms, 大步会因惯性跳 2 张卡 —— pf_scene 实测)。"""
        self._adb_shell(list(SWIPE_L))

    def swipe_right(self) -> None:
        """轮播上一张。"""
        self._adb_shell(list(SWIPE_R))

    def find_modal_x_cv(self, img):
        """cv2 版弹窗方形关闭 X 检测, 命中返回中心 (x, y), 否则 None。

        为什么不走 match_tpl (MAA TemplateMatch): pf_scene 2026-09-17 实测, 圆环 X
        模板 MAA 返回的是弹窗面板内部装饰区坐标, 而同图 cv2 TM_CCOEFF_NORMED 的
        最高分恰在真 X —— 两套引擎命中语义不同。峰值唯一性判据 (主峰与次峰差
        >= MODAL_X_PEAK_MARGIN) 挡背景渐变伪峰。标定与坑史见
        pf_scene.find_modal_x_cv / find_any_popup_x 注释。"""
        img_dir = RESOURCE_DIR / "image"
        for tpl_path, th in ((TPL_MODAL_X, 0.90),):
            tpl = self._tpl_cache.get(tpl_path)
            if tpl is None:
                tpl = cv2.imread(str(img_dir / tpl_path))
                if tpl is None:
                    continue
                self._tpl_cache[tpl_path] = tpl
            x0, y0, x1, y1 = ROI_MODAL_X
            sub = img[y0:y1, x0:x1]
            if sub.shape[0] < tpl.shape[0] or sub.shape[1] < tpl.shape[1]:
                continue
            res = cv2.matchTemplate(sub, tpl, cv2.TM_CCOEFF_NORMED)
            _, mx, _, ml = cv2.minMaxLoc(res)
            if mx < th:
                continue
            h, w = tpl.shape[:2]
            pad = max(h, w) * 2
            cy0, cx0 = ml[1], ml[0]
            masked = res.copy()
            masked[max(0, cy0 - pad):cy0 + pad,
                   max(0, cx0 - pad):cx0 + pad] = -1.0
            second = cv2.minMaxLoc(masked)[1]
            if mx - second < MODAL_X_PEAK_MARGIN:
                STATE.log(f"模态 X 疑似背景伪峰: 主峰 {mx:.3f} @ {ml}, 次峰 {second:.3f}",
                          "warn")
                continue
            return (ml[0] + x0 + w // 2, ml[1] + y0 + h // 2)
        return None

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

        坑史与归一化规则详见 PF_BOT.md §6.9; 要点:
          - 先剥 SCORE 标签, 再把 O/Q -> 0 (顺序不能反, 否则标签里的 O 混进数字);
          - 取最长数字串, 千分位逗号兼容;
          - 领域约束: SGM PF 分数没有个位数, <10 一律按 0 (OCR 把 0 读错的兜底)。
        """
        if not raw:
            return -1
        body = re.sub(r"(?i)\bscore\b\s*:?", " ", raw)
        norm = re.sub(r"[OQ]", "0", body)
        norm = re.sub(r"[^0-9,]", " ", norm).strip()
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
        if score < 10:
            STATE.log(f"分数 OCR 得个位数 {score}, 按 0 处理 (原文 {raw!r})", "warn")
            return 0
        return score

    def read_center_card(self, img=None) -> tuple[str, int]:
        """读 PF hub 居中场地的 (名称, 分数)。名称优先匹配已知场地名。

        层级前缀不直接删, 而是展开成候选再择优 (见 TIER_PREFIXES 注释);
        标题 ROI 未命中已知名时用加高 ROI 兜底再试一次 (DEATH METTLE 高标题版式);
        无 SCORE 行的卡 (新场) 在标题命中字库的前提下判 0 —— 门卫保证 OCR 通道
        活着才判 0, 宁可漏跑不错跑。完整坑史见 PF_BOT.md §6.9 与 pf_scene 历史。"""
        if img is None:
            img = self.snap("识别场地")
        raw_score = self.ocr_text(img, ROI_CARD_SCORE)
        score = self.parse_score_ocr(raw_score)
        raw_title = self.ocr_text(img, ROI_CARD_TITLE)
        full, cands, stripped = self._title_cands(raw_title)
        best = self._match_known(cands)
        if best is None:
            raw2 = self.ocr_text(img, ROI_CARD_TITLE_FALLBACK)
            full2, cands2, stripped2 = self._title_cands(raw2)
            best2 = self._match_known(cands2)
            if best2 is not None:
                STATE.log(f"标题 ROI 未命中, 加高 ROI 兜底命中: {raw_title!r} -> {best2!r}",
                          "warn")
                full, cands, stripped = full2, cands2, stripped2
                best = best2
            elif not cands[0] and cands2[0]:
                # primary 剥完是空串而 fallback 读出了字母 -> 采用 fallback 串留痕
                full, cands, stripped = full2, cands2, stripped2
        score_body = re.sub(r"(?i)\bscore\b\s*:?", " ", raw_score).strip()
        has_score_label = bool(re.search(r"(?i)score", raw_score))
        if best is not None and (
            (score == -1 and not score_body)
            or (not has_score_label and 0 <= score < 10000)
        ):
            STATE.log(f"卡面无 SCORE 行 (标题 {best!r}), 按新场 0 处理", "warn")
            score = 0
        if best:
            if stripped:
                STATE.log(f"场地名带层级前缀, 归一为 {best!r}: OCR 原文 {full}", "warn")
            return best, score
        return (cands[-1] if len(cands) > 1 else full), score

    @staticmethod
    def _card_key(title: str) -> str:
        return re.sub(r"[^A-Z0-9]", "", title or "")[:14]

    @staticmethod
    def _same_card(a: tuple, b: tuple) -> bool:
        """相邻两次读卡是否同一张: 标题键高相似 + 分数相同。

        仅比 key 相等会漏 OCR 抽风: 同一张尾卡被读成 RUNESANDZEROS/UNESANDZEROS
        两个近似串 (2026-10-02 实测, 同分 34,672,590), 被录成两条还多滑一轮。
        分数必须同分才算同张 (不同场分数相同的概率可忽略); 都读不出 (-1) 时
        退化为纯 key 比较。"""
        (ta, sa), (tb, sb) = a, b
        ka, kb = SceneNav._card_key(ta), SceneNav._card_key(tb)
        if not ka or not kb:
            return ka == kb
        return sa == sb and difflib.SequenceMatcher(None, ka, kb).ratio() >= 0.8

    def _kw_hit(self, kw: str, title: str) -> bool:
        """场地关键词命中判定: 只比字母; <4 字母只认全等; ≥4 字母允许
        子串 (双向) 与高相似度 —— OCR 会丢首字母 ('RUNESANDZEROS' 读成
        'UNESANDZEROS', 2026-10-02 实测致居中失败), 单向子串会漏。
        相似度只对 ≥8 字母的长名生效, 避免短词误命中 ('NIGHT' vs 'MIGHT')。"""
        k = re.sub(r"[^A-Z]", "", (kw or "").upper())
        t = re.sub(r"[^A-Z]", "", (title or "").upper())
        if not k:
            return False
        if len(k) < 4:
            return k == t
        if k in t or t in k:
            return True
        if min(len(k), len(t)) >= 8 and difflib.SequenceMatcher(None, k, t).ratio() >= 0.8:
            return True
        return False

    def _nav_abort(self) -> bool:
        """导航/扫描的中途退出判据 (协作式): quit 置位, 或 running 相对**本次
        导航进入时**翻转 —— 开跑路径进导航时 running=True, 用户点「结束/停止」
        翻成 False 即中止; 待命扫描时 running=False, 用户点「开始」翻 True 即中止。
        `_nav_running_at_entry` 由路径入口 (navigate_to_scene / 待命扫描分支) 设定。"""
        if STATE.quit:
            return True
        exp = getattr(self, "_nav_running_at_entry", None)
        return exp is not None and STATE.running != exp

    def _in_battle(self, img) -> bool:
        """战斗进行中判据: 速度泡模板命中 (宿主 PfBot 的 battle_speed_level,
        非战斗帧实测 ≤0.14 零误报)。宿主没提供该方法时恒 False。"""
        fn = getattr(self, "battle_speed_level", None)
        if fn is None:
            return False
        try:
            return fn(img) > 0
        except Exception:  # noqa: BLE001
            return False

    def goto_pf_hub(self, timeout: float = NAV_TIMEOUT) -> None:
        """从任意界面走回 PF hub: 弹窗优先 → PLAY 就绪 → 大厅菱形 → 房子回家。

        第一轮认不出任何界面时先补一发游戏拉起 (冷启动「开始」只拉了模拟器,
        游戏可能还没开; 已开时 monkey 只是把现有任务切到前台, 无副作用)。
        弹窗三路检测与 pf_scene.wait_hall 同源: 模态方形 X (cv2) / 通用 X /
        促销 X, 缺一路就会在促销弹窗上空转到超时。

        ⚠️ 2026-10-02 补两态 (实测: 手动结束在战斗中途 → 游戏自己打完 → 停在
        VICTORY 结算页, 认路只认 hub/大厅, 对结算页按房子 240s 超时 → 用户看到
        "刷了一场就停了"):
          - 战斗残留: 速度泡命中 = 还在打, 只等不点 (对着战斗界面按房子无效);
          - 残局结算页: 点 CONTINUE 逐页剥掉 (VICTORY/里程碑/streak), 再回 hub。"""
        STATE.set_step("回到 PF hub")
        launched = False
        t0 = time.time()
        battle_log_at = 0.0
        while time.time() - t0 < timeout:
            if self._nav_abort():
                raise NavAborted()
            img = self.snap("nav")
            if self.match_tpl(img, TPL_HUB_PLAY, ROI_HUB_PLAY, th=0.7):
                STATE.log("PF hub 就绪")
                return
            if self._in_battle(img):
                now = time.time()
                if now - battle_log_at > 15:
                    battle_log_at = now
                    STATE.log(f"导航: 上一场战斗仍在进行 (结束残留), 等它打完 "
                              f"(已等 {int(now - t0)}s, 上限 {timeout:.0f}s)", "warn")
                time.sleep(6)
                continue
            x = (self.find_modal_x_cv(img)
                 or self.find_popup_x(img)
                 or self.match_tpl(img, TPL_SCENE_X, ROI_SCENE_X, th=0.8))
            if x:
                STATE.log(f"导航途中弹窗, 点 X @ {x}", "warn")
                self.controller.post_click(*x).wait()
                time.sleep(1.8)
                continue
            cont = (self.match_tpl(img, TPL_RESULT_CONTINUE, ROI_RESULT, th=0.7)
                    or self.match_tpl(img, TPL_CONTINUE, ROI_TOPRIGHT, th=0.7))
            if cont:
                STATE.log(f"导航: 残局结算页, 点 CONTINUE 脱离 @ {cont}", "warn")
                self.controller.post_click(*cont).wait()
                time.sleep(2.5)
                continue
            box = self.match_tpl(img, TPL_HALL_PRIZE, (0, 0, 0, 0), th=0.72)
            if box:
                self.controller.post_click(*box).wait()
                time.sleep(2.5)
                continue
            if not launched:
                launched = True
                try:
                    ok, out = mumu_launch_game()
                    if ok:
                        STATE.log("导航: 补拉起 Skullgirls (冷启动兜底)", "warn")
                    else:
                        STATE.log(f"导航: 游戏拉起失败 ({out}), 继续按房子回家", "warn")
                except Exception as e:  # noqa: BLE001
                    STATE.log(f"导航: 游戏拉起异常 ({e}), 继续按房子回家", "warn")
            self.controller.post_click(*HOME_BTN).wait()
            time.sleep(2.5)
        raise RuntimeError(f"{timeout:.0f}s 未走回 PF hub (看 debug/pf/run 最新帧确认卡在哪)")

    @staticmethod
    def parse_scene_keyword(kw):
        """场地绑定关键词解析: '#N' = 轮播第 N 个 (1 起, 最左=月场, 不依赖 OCR 标题);
        其余按名字子串匹配。返回 ("index", n) / ("title", s) / (None, None)。"""
        s = str(kw or "").strip()
        m = re.fullmatch(r"#(\d{1,2})", s)
        if m:
            return ("index", int(m.group(1)))
        return ("title", s) if s else (None, None)

    def center_scene(self, keyword: str, max_steps: int = 14) -> tuple[str, int]:
        """把绑定的场转到居中, 返回 (名称, 分数)。

        keyword 两种写法 (parse_scene_keyword):
          '#N'  —— 位置绑定: 轮播第 N 个 (1 起)。最左两位固定是月场 (用户 2026-10-02
                   口径), 名字 OCR 整卡失败的卡 (PF_BOT §6.9 HIGH WIRE HIJINKS) 走位置。
          名字  —— 字母归一子串匹配, <4 字母只认全等 (pf_scene.center 同口径)。

        名字匹配的轮播**不循环** (pf_scene explore 2026-09-22 实证: 滑到头画面不动):
        先向左扫到头, 没命中再向右扫回起点。扫完仍没有 -> RuntimeError,
        由调用方按"结束本场+接续队列"处理, 不在错场上空烧。"""
        kind, val = self.parse_scene_keyword(keyword)
        if kind is None:
            raise RuntimeError("场地关键词为空")
        STATE.set_step("居中绑定场地")
        if kind == "index":
            if val < 1:
                raise RuntimeError(f"位置绑定从 #1 起 (最左=月场), 不接受 {keyword!r}")
            n = len(STATE.arenas["arenas"]) if STATE.arenas else None
            if n is not None and val > n:
                raise RuntimeError(f"本次扫描只见 {n} 个场, 没有第 {val} 个 (绑定 {keyword!r})")
            return self.goto_index(val - 1, max_steps)
        k = re.sub(r"[^A-Z]", "", val.upper())
        if not k:
            raise RuntimeError(f"场地关键词无字母: {keyword!r}")
        title, score = self.read_center_card()
        if self._kw_hit(k, title):
            STATE.log(f"已在绑定场地: {title} (score={score:,})")
            return title, score
        prev = (title, score)
        for tag, swipe in (("L", self.swipe_left), ("R", self.swipe_right)):
            for i in range(max_steps):
                if self._nav_abort():
                    raise NavAborted()
                swipe()
                time.sleep(1.8)
                title, score = self.read_center_card()
                if self._kw_hit(k, title):
                    STATE.log(f"已居中绑定场地: {title} (score={score:,})")
                    return title, score
                cur = (title, score)
                if self._same_card(cur, prev):
                    STATE.log(f"向{'左' if tag == 'L' else '右'}滑画面不动, 该侧到头")
                    break
                prev = cur
        raise RuntimeError(f"轮播扫完未找到场地关键词 {keyword!r} "
                           f"(停在第 {title!r} 卡, 分数 {score})")

    def _goto_leftmost(self, max_steps: int = 14) -> None:
        """右滑到轮播最左 (相邻两次读卡判同一张 = 画面不动, 到头)。"""
        prev = self.read_center_card()
        for _ in range(max_steps):
            if self._nav_abort():
                raise NavAborted()
            self.swipe_right()
            time.sleep(1.8)
            cur = self.read_center_card()
            if self._same_card(cur, prev):
                return
            prev = cur

    def goto_index(self, idx: int, max_steps: int = 14) -> tuple[str, int]:
        """到轮播第 idx 个场地 (0 起, 0=最左=月场): 先回最左再左滑 idx 次。

        位置法不依赖标题 OCR —— 角色场卡标题 OCR 整卡失败时的既定绕行方案
        (PF_BOT §6.9: hub 不循环、按位置滑), 也是月场这类固定位绑定的正路。"""
        self._goto_leftmost(max_steps)
        for _ in range(max(0, idx)):
            if self._nav_abort():
                raise NavAborted()
            self.swipe_left()
            time.sleep(1.8)
        title, score = self.read_center_card()
        STATE.log(f"已到第 {idx + 1} 个场地: {title or '(未识别)'} (score={score:,})")
        return title, score

    def scan_arenas(self, max_cards: int = 10) -> list:
        """PF hub 轮播全扫描录入 (2026-10-02 用户口径: 每天场不一样, 进 PF 先扫一遍)。

        先右滑到最左, 再逐卡左扫读取, 返回 [{idx,title,score}] (idx=0 最左)。
        结果录入 debug/pf/arenas.json (带时间戳, 刷新线 01:00 后自动过期) +
        STATE.arenas (/api/state 暴露 → WebUI「今日场地」条)。"""
        STATE.set_step("扫描场地")
        self._goto_leftmost(max_cards)
        arenas, prev = [], None
        for idx in range(max_cards):
            if self._nav_abort():
                STATE.log(f"扫描被中止 (收到结束/开始/停止请求), 已读 {len(arenas)} 场, "
                          f"不更新录入", "warn")
                return arenas
            title, score = self.read_center_card()
            cur = (title, score)
            if prev is not None and self._same_card(cur, prev):
                STATE.log("画面与上一张相同, 轮播到头, 扫描结束")
                break
            arenas.append({"idx": idx, "title": title, "score": score})
            prev = cur
            self.swipe_left()
            time.sleep(1.8)
        if arenas:
            STATE.arenas = {"day": game_day(), "ts": time.time(), "arenas": arenas}
            self._save_arenas()
            desc = " ".join(f"{a['idx'] + 1}.{a['title'] or '(未识别)'}({a['score']:,})"
                            for a in arenas)
            STATE.log(f"场地扫描录入: 共 {len(arenas)} 场 —— {desc}")
            if len(arenas) == 1:
                # 正常轮播至少有月场+轮换场几张卡; 只读到 1 场基本 = 滑动没生效
                # (2026-10-02 实测: _adb_shell 漏 shell 子命令, 滑动静默失效)。
                STATE.log("扫描只读到 1 场 —— 请对照模拟器画面确认轮播是否被翻动; "
                          "若画面明明有多张卡, 查 adb shell 滑动链路", "warn")
        return arenas

    def _save_arenas(self, path=None) -> None:
        """扫描结果落盘 (tmp+replace 原子写); 失败只告警, 不带崩主流程。"""
        p = Path(path) if path else ARENAS_PATH
        try:
            p.parent.mkdir(parents=True, exist_ok=True)
            tmp = p.with_suffix(".json.tmp")
            tmp.write_text(json.dumps(STATE.arenas, ensure_ascii=False), encoding="utf-8")
            tmp.replace(p)
        except OSError as e:
            STATE.log(f"场地扫描结果写盘失败 (不影响运行): {e}", "warn")

    @staticmethod
    def _load_arenas(path=None):
        """启动回读扫描录入; 文件缺失/跨刷新线 (01:00)/损坏返回 None。"""
        p = Path(path) if path else ARENAS_PATH
        try:
            data = json.loads(p.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return None
        if (isinstance(data, dict) and isinstance(data.get("arenas"), list)
                and arenas_fresh(data)):
            return {"day": data.get("day") or game_day(),
                    "ts": data.get("ts"),
                    "arenas": data["arenas"]}
        return None

    def navigate_to_scene(self, keyword) -> tuple[bool, str, str]:
        """开始时的一次性场景识别/导航: 走回 PF hub; **今日未扫描录入 (01:00 刷新线
        之后的首次进 PF) 才全扫描一次**, 已录入则直接沿用; 绑定了关键词 (#N 位置 /
        名字) 就居中该场, 未绑定就近开打。

        返回 (ok, 场地名, kind):
          ok       成功
          scene    认路成功但场地没找到 (换链上下一个, 不在错场上开打)
          nav      认路本身失败 (连接/界面问题 —— 停, 别把队列一路烧完)
          stopped  用户中途结束/停止 (running 翻转), 什么都没做
        异常不外抛 (截图层 LinkDead 照常抛, 主循环统一自愈/重生)。"""
        self._nav_running_at_entry = STATE.running   # 中止判据基线, 见 _nav_abort
        try:
            self.goto_pf_hub()
        except NavAborted:
            STATE.log("场景导航已中止 (收到结束/停止请求)", "warn")
            return True, "", "stopped"
        except RuntimeError as e:
            STATE.log(f"场景导航失败 (认路): {e}", "err")
            return False, "", "nav"
        try:
            if arenas_fresh(STATE.arenas):
                # 今天已扫描过 (刷新线 01:00 之后) —— 一天一次就够, 不再重扫
                # (用户口径 2026-10-02: 场只有凌晨 1 点刷新; 每场都扫白等 20s)。
                title, score = self.read_center_card()
                if keyword:
                    title, _ = self.center_scene(keyword)
                else:
                    STATE.log(f"当前场地: {title or '(未识别)'} (score={score:,})"
                              f" —— 今日已扫描录入, 不重复扫; 未绑定场地就近开打")
                return True, title, "ok"
            # 今日首进 (或跨过 01:00 刷新线): 全扫描录入一次
            entry_title, entry_score = self.read_center_card()
            entry_key = self._card_key(entry_title)
            arenas = self.scan_arenas()
            if keyword:
                title, _ = self.center_scene(keyword)
                return True, title, "ok"
            # 未绑定: 回开局卡就近开打 (扫描停在末位卡, 末位就是开局卡则免走)。
            if entry_key:
                hit = next((a for a in arenas
                            if self._card_key(a["title"]) == entry_key), None)
                if hit is not None:
                    if hit["idx"] == len(arenas) - 1:
                        title = entry_title
                    else:
                        title, _ = self.goto_index(hit["idx"])
                    STATE.log(f"当前场地: {title} (score={entry_score:,})"
                              f" —— 未绑定场地, 就近开打")
                    return True, title, "ok"
            title, score = self.goto_index(0)
            STATE.log(f"开局卡未识别, 已停最左: {title or '(未识别)'} (score={score:,})"
                      f" —— 未绑定场地, 请在 WebUI 核对", "warn")
            return True, title, "ok"
        except NavAborted:
            STATE.log("场景导航已中止 (收到结束/停止请求)", "warn")
            return True, "", "stopped"
        except RuntimeError as e:
            STATE.log(f"场景导航失败: {e}", "err")
            return False, "", "scene"

