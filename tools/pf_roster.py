"""角色仓库 (COLLECTION / FIGHTERS) 全量扫描 —— 只读上方卡名 + 不重复统计。

用户口径 (2026-10-13):
  - **只读上方那条卡名** (卡面装饰大字, 如 'REGALLY BLONDE'), 名条(角色本名)不读;
  - "到头"判据与既有轮播一致: 往一侧拖, **连续两次画面不动** = 到头;
  - 难点是**滑动后怎么定位同一张卡, 做到不重复统计**。

## 版式 (1280x720, MuMu 实机标定)
2 行 x 8 列可视 (每行 7 张完整 + 两侧各半张), 列距约 165.5px:
    row1: 卡名带 y 296..326, 名条带 y 322..350
    row2: 卡名带 y 526..556, 名条带 y 552..580

## 定位: 名条带 det 只取框, 卡名带 rec-only
为什么不用卡名带直接 det: 装饰字太宽会被切碎 ('REGALLY BLONDE' -> 'REGAL'+'SLONDE',
'PARASITE WEAVE' -> 'PARASITEW'+'WEAVE'), 还有整条漏检 —— 实测 14 格里至少漏 1 格。
**名条带的小字规整**, det 稳定给**一卡一框**, 框中心就是卡中心。于是每张卡只要
1 次识别往返 (在卡名带的固定 ROI 上 rec-only), 而旧探针的 det+rec 要 2 次, 且实测
rec-only 更准: 3 屏 x 14 格 = 42 格全部读对 (det+rec 有 7 格读错或带尾字符)。

## 去重 (用户问的"滑动后怎么定位角色") —— 三道都堵上
① **不能按名字去重**: 实测同一屏上就有 3 张一模一样的 'NUNSENSE' (重复卡是真卡),
   按名字合并会把真卡吃掉;
② **不能写死坐标**: 网格不吸附, 拖动量任意 (实测连续两帧平移 59px / -67px,
   都不是列距的整数倍), 卡的屏幕 x 每次都变;
③ **也不能只按列位置投票**: 列是周期性的 —— 真平移 67px 时, 67±165.5k 这些**错值
   同样能拿到 9 票** (实测), 几何投票的胜出边际只有 14:12, 会翻车。
所以用**卡名内容**给候选平移量打分: 对每个候选 Δ 按最近邻配对, 名字对上 +1、对不上 -1。
实测 p2->p3 (真值已知): 真 Δ=66 得 **+14**, 次优只有 **-2**; 而内容真的变了的
p1->p2 最高只有 -2 ⇒ 该拒绝时拒绝, 不硬猜。
选中的 Δ 再用命中对的中位数残差拧一次 (每帧重置, 误差不累积), 然后
`全局 gx = 本帧 cx + Δ`, 以 (行, gx) 为键去重 ⇒ 重复统计消失。

## 配准别名 (2026-10-14 补的第 4 道; 名字打分**挡不住**)
列是周期性的, 长串同名卡里"差一个列距"的别名 Δ 命中的卡**一样多甚至更多**。
实证 run1 frame0033 (真值由**图像相关**独立测出: 上一步 13601 + 真实步长 484 = 14085):

    Δ=13920  hits=9 miss=1 score=8   <- 旧版选它 (同分 + `s > best_s` 取最小的)
    Δ=14085  hits=8 miss=0 score=8   <- 真值: 唯一 0 冲突的那个
    Δ=14251  hits=6 miss=0 score=6   <- 差 2 列

同一次扫描实机跑两遍: 一遍 255 张、一遍 253 张, 两轮的**左端帧和右端帧完全一样**,
差的是 row1/row2 各少一列 —— 坏的那次是**漏统计**(整帧并成 dup, 那一列再没人补),
不是重复统计。**根因是打分 tie-break, 不是去重逻辑有洞。**
修法 = 同分先比**冲突数** `miss` (配上对但名字不同 = 直接矛盾, 真值该是 0),
再比运动先验 `Δ_prev ± 中位步长`。⚠️ 运动先验**只能当最后一档**: 单独用它会把
真值 14085 顶成 14251, 反而多统计 2 张 (实测)。
**复核工具**: `tools/_probe_frame_shift.py <帧目录>` 用图像相关测相邻帧真实像素位移,
完全绕开 OCR 与配准 —— 这类"到底该谁对"的争议一律用它裁 (纯离线, 不连设备)。

## 到头判据
连续两次拖拽后 |Δ 变化| < NO_MOVE_PX 即到头。两道保险:
① 取**两次**而不是一次 —— `_adb_shell` 曾因漏写 shell 静默失效, 一次不动就把
   "滑不动"当成"到头" (PF_BOT §8 踩坑 27/28 同类);
② 第一次"没动"之后**换一条拖拽几何再试** —— 实机实测: 列表端点的屏幕上是一大片
   空白 + ADD 槽, 主几何的起手点 (1150,460) 已经落在空白/按钮上, "拖不动"是假象。
   2026-10-13 在右端换了 3 种几何 (含从最后一张卡面 (1000,250) 起手、以及
   (1240,250)->(60,250) 的长拖) 全部不动, 才确认是真到头。
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from difflib import SequenceMatcher
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import pf_fighters as fighters  # noqa: E402

W, H = 1280, 720

# (行名, 卡名带, 名条带)
ROWS = (("row1", (296, 326), (322, 350)),
        ("row2", (526, 556), (552, 580)))
HALF = 76           # 以卡中心为准的左右半宽 (卡名带 ROI)
EDGE_L, EDGE_R = 40, 1240   # 名条框完全落在此区间才算"完整卡", 边缘半张不评
PITCH = 165.5       # 列距 (只用于自检, 去重不依赖它)

DRAG_FROM, DRAG_TO, DRAG_Y = 1150, 400, 460   # 一次拖 750px ≈ 4.5 列
# 备用拖拽几何 (判"到头"前的复验): 换到**行1 卡面**上起手, 且从更靠内容一侧出发 ——
# 端点屏幕上有一大片空白 + ADD 槽, 主几何的起手点会落在空白上, 那时"拖不动"是假象。
ALT_DRAG_FROM, ALT_DRAG_TO, ALT_DRAG_Y = 1000, 150, 250
SETTLE_S = 0.9          # 拖完等画面停稳再截图
NO_MOVE_PX = 5          # |Δ 变化| 小于它 = 画面没动
NO_MOVE_STRIKES = 2     # 连着几次没动才判到头
NAME_TOL = 0.85         # 卡名比对 (归一后) 的模糊下限
PAIR_TOL = 4            # 配对容差: |旧cx - Δ - 新cx| <= 它算同一张
CLUSTER_TOL = 3         # 候选 Δ 聚簇容差
MIN_SCORE = 3           # 打分下限: 低于它认为两帧没有重叠
SNAP_TOL = 10           # gx 去重容差 (远小于列距, 不会把邻卡并掉)
MAX_DRAGS = 120
# 运动先验 (2026-10-14 加, 见 _align docstring "配准别名"一节):
STEP_MIN = 100          # 计入"步长"的位移下限 (滤掉"拖不动"的 0 步与抖动)


def norm(s: str) -> str:
    return "".join(ch for ch in (s or "").upper() if ch.isalnum())


def same_name(a: str, b: str) -> bool:
    na, nb = norm(a), norm(b)
    if not na or not nb:
        return False
    return na == nb or SequenceMatcher(None, na, nb).ratio() >= NAME_TOL


def make_cards(reader, img) -> list:
    """一帧 -> 卡表 [{row, cx, name, base, char, element, how, strip}]。

    reader 只需两个方法 (离线/实机各一套实现):
      lines(img, roi) -> [((x0,y0,x1,y1), text)]    # 名条带: 只取**框**, text 是副产品
      read_name(img, roi) -> str                    # 卡名带: rec-only
    """
    cards = []
    for row, ban, loc in ROWS:
        items = [it for it in reader.lines(img, (0, loc[0], W, loc[1]))
                 if it[0][0] > EDGE_L and it[0][2] < EDGE_R]
        for (x0, _y0, x1, _y1), strip in sorted(items, key=lambda it: it[0][0]):
            cx = (x0 + x1) // 2
            if cx - HALF < 0 or cx + HALF > W:
                continue
            name = reader.read_name(img, (cx - HALF, ban[0], cx + HALF, ban[1]))
            base, char, element, how = fighters.resolve(name)
            cards.append({"row": row, "cx": cx, "name": name, "base": base,
                          "char": char, "element": element, "how": how, "strip": strip})
    return cards


class Store:
    """全局卡表: (行, 全局 gx) 唯一 —— "不重复统计"落在这里。"""

    def __init__(self):
        self.cards: list[dict] = []
        self.delta: int | None = None
        self.steps: list[int] = []      # 最近几次有效拖拽的位移 (运动先验用)
        self.notes: list[str] = []

    # ---------- 配准 ----------

    def candidates(self, cards: list) -> list:
        """候选 Δ 聚簇。

        Δ 的定义: `全局 gx = 本帧 cx + Δ`。旧的记录只存**全局 gx**, 而同一张卡在
        本帧的位置是 cx, 所以 Δ = gx_old - cx_new —— 直接由记录给出, 不用旧的 cx。
        (⚠️ 首帧锚定后 Δ=0、gx==cx, 两者恰好相等; 写到第二帧之后就会分叉,
        按 cx 算会 KeyError/算偏。)
        """
        raw = sorted({a["gx"] - b["cx"] for a in self.cards for b in cards
                      if a["row"] == b["row"]})
        out = []
        for d in raw:
            if out and d - out[-1][-1] <= CLUSTER_TOL:
                out[-1].append(d)
            else:
                out.append([d])
        return [c[len(c) // 2] for c in out]

    def _score(self, cards: list, d: int):
        """按 Δ 配对后数名字: 对上 +1 / 对不上 -1 / 没配上 0。"""
        hit = miss = 0
        for a in self.cards:
            near = [b for b in cards if b["row"] == a["row"]
                    and abs(a["gx"] - d - b["cx"]) <= PAIR_TOL]
            if not near:
                continue
            if same_name(a["name"], near[0]["name"]):
                hit += 1
            else:
                miss += 1
        return hit - miss, hit, miss

    def _align(self, cards: list):
        """返回 (Δ, 得分, 名同数); 分数不够 -> (None, 最高分, 名同数)。

        ⚠️ **配准别名** (2026-10-14 实测的坑): 列是周期性的, 长串同名卡里
        "差一个列距"的别名 Δ 会命中**同样多甚至更多**的卡 —— 光看命中数分不开它。
        实证 (run1 frame0033, 图像相关测出的真值 Δ=**14085**, 上一步 13601+真实步长 484):

            Δ=13920  hits=9 miss=1 score=8   <- 旧版选它 (同分 + 取最小的)
            Δ=14085  hits=8 miss=0 score=8   <- 真值: **0 冲突**, 只是命中少 1 个
            Δ=14251  hits=6 miss=0 score=6   <- 差 2 列, 更远

        后果不是"重复统计"而是**漏统计**: 整帧并成 dup, 这一列从此没人补, 最终
        253 张(少 2) 而两轮端点帧一模一样。**同分时必挑"0 冲突"的那个**
        (miss = 配上对但名字不同 = 直接矛盾), 真值恰好是唯一 0 冲突且分最高的。
        (`Store._predict` 的运动先验只做**最后**一档 tie-break: 先验单独用会过头 ——
        曾经把真值 14085 顶成 14251, 反而多统计 2 张。)
        """
        scored = []
        for d in self.candidates(cards):
            s, hit, miss = self._score(cards, d)
            scored.append((s, hit, miss, d))
        if not scored:
            return None, 0, 0
        best_s = max(c[0] for c in scored)
        if best_s < MIN_SCORE:
            return None, best_s, 0
        pred = self._predict()
        # 排序: 分数高 -> 冲突少 -> 贴近运动预测 -> Δ 小
        scored.sort(key=lambda c: (-c[0], c[2],
                                   min((abs(c[3] - e) for e in pred), default=0), c[3]))
        best_s, best_hit, _best_miss, best_d = scored[0]
        # 精对齐: 命中对的中位数残差把 Δ 拧成整数 (每帧都重算一次, 不累积误差)
        # ⚠️ 只取**最近邻**那一对 —— 同名卡 (3 张 NUNSENSE) 的交叉配对残差会差一个
        # 列距, 放进中位数会把 Δ 拉偏。
        res = []
        for a in self.cards:
            near_pair = [b for b in cards if b["row"] == a["row"]
                         and abs(a["gx"] - best_d - b["cx"]) <= PAIR_TOL]
            if near_pair and same_name(a["name"], near_pair[0]["name"]):
                res.append(a["gx"] - near_pair[0]["cx"])
        d = sorted(res)[len(res) // 2] if res else best_d
        return d, best_s, len(res)

    def _predict(self) -> list:
        """运动先验: 返回 `Δ_prev ± 中位步长` 两个预测位移 (无历史时返回 [])。

        **只当最后一档 tie-break 用** (分数与冲突数都并列时) —— 单独拿它选 Δ 会过头:
        2026-10-14 实测它把真值 14085 顶成 14251, 反而多统计 2 张。
        符号留两个: 扫到端点后折返, 手势幅度不变但方向翻过来。
        """
        if self.delta is None or not self.steps:
            return []
        recent = sorted(self.steps[-7:])
        step = recent[len(recent) // 2]
        return [self.delta + step, self.delta - step]

    # ---------- 合并 ----------

    def merge(self, cards: list) -> dict:
        """把一帧并进全局表, 返回 {"new","dup","conflict","delta","score","aligned"}。"""
        if not cards:
            return {"new": 0, "dup": 0, "conflict": 0, "delta": None,
                    "score": 0, "aligned": False}
        if not self.cards:                      # 首帧: 以自身为锚
            d, score = 0, 0
        else:
            d, score, _ = self._align(cards)
            if d is None:
                return {"new": 0, "dup": 0, "conflict": 0, "delta": None,
                        "score": score, "aligned": False}
            # 记"步长"给运动先验用: 只收真正动了的帧 (拖不动的 0 步与抖动会带偏中位数)
            if self.delta is not None and abs(d - self.delta) >= STEP_MIN:
                self.steps.append(abs(d - self.delta))
        new = dup = conflict = 0
        for c in cards:
            gx = c["cx"] + d
            rec = next((r for r in self.cards if r["row"] == c["row"]
                        and abs(r["gx"] - gx) <= SNAP_TOL), None)
            if rec is None:
                self.cards.append({"row": c["row"], "gx": gx, "name": c["name"],
                                   "base": c["base"], "char": c["char"],
                                   "element": c["element"], "how": c["how"],
                                   "strip": c["strip"]})
                new += 1
            else:
                dup += 1
                if not same_name(rec["name"], c["name"]):
                    conflict += 1
                    # 同一张卡两次读数不同: 优先留**能映射出角色**的那个, 另一个留痕人工核
                    if rec["char"] is None and c["char"] is not None:
                        rec.update({k: c[k] for k in ("name", "base", "char", "element", "how")})
                    self.notes.append(f"{c['row']} gx={gx}: "
                                      f"{rec['name']!r} vs {c['name']!r}")
        self.delta = d
        return {"new": new, "dup": dup, "conflict": conflict,
                "delta": d, "score": score, "aligned": True}

    # ---------- 输出 ----------

    def summary(self) -> dict:
        """按 (行, gx) 排序输出; 另外给出角色分布与最小间隔自检。"""
        cards = sorted(self.cards, key=lambda r: (r["row"], r["gx"]))
        chars: dict[str, int] = {}
        for r in cards:
            chars[r["char"] or "(未识别)"] = chars.get(r["char"] or "(未识别)", 0) + 1
        gaps = {row: [] for row, _, _ in ROWS}
        for row, _, _ in ROWS:
            gxs = [r["gx"] for r in cards if r["row"] == row]
            gaps[row] = [b - a for a, b in zip(gxs, gxs[1:])]
        all_gaps = [g for v in gaps.values() for g in v]
        # 自检 A: 相邻卡间隔应≈列距。远小于列距 = 同一张卡被记了两次 (去重失效);
        # 自检 B: 远大于列距 = 中间漏了卡 (配准漂移或某列从没完整可见)。
        # 2026-10-13 实测: 253 张的 253 个间隔全在 162~169, 两条自检都空 —— 这才敢
        # 说"253 是网格的完整内容", 而不是"扫到的部分"。
        dup_gaps = [round(g, 1) for g in all_gaps if g < PITCH * 0.6]
        miss_gaps = [round(g, 1) for g in all_gaps if g > PITCH * 1.4]
        by_name: dict[str, int] = {}
        for r in cards:
            by_name[r["name"]] = by_name.get(r["name"], 0) + 1
        return {
            "count": len(cards),
            "unmapped": sum(1 for r in cards if r["char"] is None),
            "by_char": dict(sorted(chars.items(), key=lambda kv: -kv[1])),
            "dup_names": {k: v for k, v in by_name.items() if v > 1},
            "min_gap": round(min(all_gaps), 1) if all_gaps else None,
            "max_gap": round(max(all_gaps), 1) if all_gaps else None,
            "dup_gaps": dup_gaps,
            "miss_gaps": miss_gaps,
            "cards": [{"row": r["row"], "gx": r["gx"], "name": r["name"],
                       "char": r["char"], "base": r["base"], "strip": r["strip"]}
                      for r in cards],
            "conflicts": self.notes,
        }


def scan(make_frame, drag, log=print, max_drags: int = MAX_DRAGS,
         settle: float = SETTLE_S) -> Store:
    """拖动 + 逐帧并入. make_frame() 返回卡表; drag(content_left, alt=False) 执行一次拖拽。

    先向起点方向拖 (直到连续 NO_MOVE_STRIKES 次不动), 再反向扫到底。
    drag 的 `alt` 参数: 第一次"没动"之后换一条拖拽几何再试 —— 实机教训是
    **"拖不动"不等于"到头"**, 触点落在空白区/ADD 槽上同样拖不动 (端点屏幕上就是
    一大片空白), 所以判到头前必须换条线复验一次 (2026-10-13 实测: 右端换 3 种几何
    都不动, 才敢下"到头"的结论)。
    """
    st = Store()
    cards = make_frame()
    rep = st.merge(cards)
    log(f"[锚定] 首帧 {len(cards)} 卡 (row1 {sum(1 for c in cards if c['row'] == 'row1')}"
        f" / row2 {sum(1 for c in cards if c['row'] == 'row2')})")

    def crawl(content_left: bool, label: str):
        """content_left=True 表示"看后面的卡"; 返回 (结束原因, 拖了几次)。"""
        prev, stale, n = st.delta, 0, 0
        while n < max_drags:
            drag(content_left, stale > 0)
            n += 1
            time.sleep(settle)
            cards = make_frame()
            rep = st.merge(cards)
            if not rep["aligned"]:
                log(f"[{label}{n}] 与已读的卡对不上 (拖太多/内容变了, 得分 "
                    f"{rep['score']}) —— **停下报告, 不硬猜**", "warn")
                return "unaligned", n
            moved = prev is not None and abs(rep["delta"] - prev) >= NO_MOVE_PX
            log(f"[{label}{n}] {len(cards):2d} 卡  新增 {rep['new']:2d} 重复 "
                f"{rep['dup']:2d} 冲突 {rep['conflict']}  平移 "
                f"{rep['delta'] - prev:+5d}  累计 {len(st.cards)}")
            prev = rep["delta"]
            if moved:
                stale = 0
            else:
                stale += 1
                if stale >= NO_MOVE_STRIKES:
                    log(f"[{label}] 连续 {stale} 次画面不动 (已换过拖拽几何) —— 到头了")
                    return "end", n
        return "cap", n

    r1, _ = crawl(False, "回起点")
    log(f"[回起点] {r1}")
    r2, _ = crawl(True, "扫")
    log(f"[扫描] {r2}")
    return st


# ---------- 实机 / 离线 两套 reader ----------

class MaaReader:
    """生产路径: PfBot (MAA 绑定)。名条带走 ocr_lines 取框, 卡名带走 rec-only。"""

    def __init__(self, bot):
        self.bot = bot

    def lines(self, img, roi):
        return self.bot.ocr_lines(img, roi)

    def read_name(self, img, roi):
        return self.bot.ocr_text(img, roi)


class OfflineReader:
    """离线复跑留档截图 (无设备时也能回归)。"""

    def __init__(self):
        from _probe_ocr_offline import DetLocator, RecOCR
        self.det, self.ocr = DetLocator(), RecOCR()

    def lines(self, img, roi):
        return [(b, self.ocr.read(img, b)) for b in self.det.boxes(img, roi)]

    def read_name(self, img, roi):
        return self.ocr.read(img, roi)


def _dump(st: Store, path: Path, log) -> None:
    data = st.summary()
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=1), encoding="utf-8")
    log(f"[输出] {path}")


def _cli_drag(bot):
    """实机拖拽: 主几何 + 备用几何 (见 ALT_DRAG_* 注释)。"""

    def drag(content_left: bool, alt: bool = False):
        if alt:
            x1, x2, y = ALT_DRAG_FROM, ALT_DRAG_TO, ALT_DRAG_Y
        else:
            x1, x2, y = DRAG_FROM, DRAG_TO, DRAG_Y
        if not content_left:
            x1, x2 = x2, x1
        bot.scroll_roster(x1, x2, y)

    return drag


def main(argv):
    ap = argparse.ArgumentParser(description="角色仓库扫描 (读上方卡名 + 不重复统计)")
    ap.add_argument("mode", choices=["scan", "images"])
    ap.add_argument("images", nargs="*", help="images 模式: 按顺序当作连续帧的截图")
    ap.add_argument("--out", default="debug/pf/roster/roster.json")
    ap.add_argument("--max-drags", type=int, default=MAX_DRAGS)
    ap.add_argument("--settle", type=float, default=SETTLE_S)
    a = ap.parse_args(argv)

    if a.mode == "images":
        from _probe_ocr_offline import load
        rd = OfflineReader()
        st = Store()
        for p in a.images:
            img = load(p)
            if img is None:
                print(f"读不到图: {p}")
                continue
            cards = make_cards(rd, img)
            rep = st.merge(cards)
            print(f"{Path(p).name:16s} 本帧 {len(cards):2d} 卡  新增 "
                  f"{rep['new']:2d} 重复 {rep['dup']:2d} 冲突 {rep['conflict']}  "
                  f"Δ={rep['delta']} 得分={rep['score']} "
                  f"{'' if rep['aligned'] else '<- 对不上'}")
        s = st.summary()
        print(f"合计 {s['count']} 张 (未识别 {s['unmapped']}), 最小间隔 {s['min_gap']}")
        print("角色分布:", s["by_char"])
        _dump(st, Path(a.out), print)
        return 0

    from pf_bot import PfBot
    bot = PfBot()
    bot.setup()
    if not bot.ensure_connection():
        print("设备未连接 —— 先起 MuMu (或改用 images 模式复跑留档帧)")
        return 2
    rd = MaaReader(bot)
    st = scan(lambda: make_cards(rd, bot.snap("roster_scan")),
              _cli_drag(bot),
              log=lambda m, lv="info": print(m if lv == "info" else f"[warn] {m}"),
              max_drags=a.max_drags, settle=a.settle)
    s = st.summary()
    print(f"\n合计 {s['count']} 张 (未识别 {s['unmapped']}), 间隔 {s['min_gap']}~"
          f"{s['max_gap']} (列距约 {PITCH})")
    print("角色分布:", s["by_char"])
    if s["dup_gaps"]:
        print(f"⚠️ 间隔过小 {s['dup_gaps']} —— 去重可能失效 (同一张卡记了两次)")
    if s["miss_gaps"]:
        print(f"⚠️ 间隔过大 {s['miss_gaps']} —— 中间可能漏了卡")
    if s["dup_names"]:
        print("同名多张 (角色名可重复, 副本各算一张):",
              dict(sorted(s["dup_names"].items(), key=lambda kv: -kv[1])[:12]))
    if s["conflicts"]:
        print("同一张卡两次读数不同 (需人工核):")
        for c in s["conflicts"]:
            print("   ", c)
    _dump(st, Path(a.out), print)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
