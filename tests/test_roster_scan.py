"""角色仓库扫描的离线测试 —— 不碰设备, 全部用合成帧。

要钉住的两条性质 (用户 2026-10-13 口径):
  ① **不重复统计**: 网格滑动后同一张卡会被反复看见, 但它只许记一次;
  ② **不许硬猜**: 两帧没有重叠 (拖太多 / 列表内容变了) 时必须停下报告,
     宁可留缺口也不能把已读的卡再记一遍。
外加一条反直觉的: **同名卡 (同一屏 3 张 NUNSENSE) 是 3 张真卡**, 不许按名字合并。

跑法: C:/Users/zhiya/anaconda3/python.exe -m pytest tests/test_roster_scan.py -q
"""
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO / "tools"))

import pf_roster as R  # noqa: E402

X0 = 108.0          # 世界坐标: 第 0 列卡中心的全局 x (实机标定值)
PITCH = R.PITCH


def world(ncols: int, prefix: str = "CARD") -> dict:
    """造一份 2 行 x ncols 的卡表 (名字唯一, 便于断言)。"""
    return {row: [f"{prefix}{i:02d}" for i in range(ncols)]
            for row, _, _ in R.ROWS}


def frame(names: dict, scroll: float) -> list:
    """按 scroll 生成一帧的卡表: 屏幕 cx = 世界中心 - scroll, 半张不评。"""
    cards = []
    for row, _, _ in R.ROWS:
        for i, nm in enumerate(names[row]):
            cx = X0 + i * PITCH - scroll
            if cx - R.HALF < 0 or cx + R.HALF > R.W:
                continue
            cards.append({"row": row, "cx": int(round(cx)), "name": nm,
                          "base": "fi", "char": "Filia", "element": 1,
                          "how": "exact", "strip": nm})
    return cards


# ---------- 去重 ----------

def test_first_frame_records_everything():
    st = R.Store()
    rep = st.merge(frame(world(20), 0))
    assert rep["aligned"] and rep["new"] == 14 and rep["dup"] == 0


def test_same_frame_twice_is_all_duplicate():
    """画面没动 -> 一张都不许新增 (到头判据的基础)。"""
    st = R.Store()
    st.merge(frame(world(20), 0))
    rep = st.merge(frame(world(20), 0))
    assert rep["new"] == 0 and rep["dup"] == 14 and rep["delta"] == 0
    assert st.summary()["count"] == 14


def test_arbitrary_shift_is_deduped():
    """平移 412px —— 不是列距 (165.5) 的整数倍, 写死坐标的写法在这里必然重复。"""
    st = R.Store()
    st.merge(frame(world(20), 0))
    rep = st.merge(frame(world(20), 412))
    assert rep["aligned"] and 0 < rep["new"] < 14
    # 本帧每张卡要么是新的、要么是重复的, 不允许凭空多出来
    assert rep["new"] + rep["dup"] == 14, rep
    s = st.summary()
    assert s["count"] == 14 + rep["new"]
    assert s["dup_gaps"] == [] and s["miss_gaps"] == []   # 没并卡、也没漏卡
    assert abs(s["min_gap"] - PITCH) < 3


def test_duplicate_names_are_three_real_cards():
    """同一屏 3 张同名卡 (实机 NUNSENSE x3) —— 必须记 3 张, 不能按名字去重。"""
    names = {row: ["NUNSENSE"] * 12 for row, _, _ in R.ROWS}
    st = R.Store()
    st.merge(frame(names, 0))
    s = st.summary()
    assert s["count"] == 14
    assert sum(1 for c in s["cards"] if c["name"] == "NUNSENSE") == 14


def test_many_small_drags_keep_gx_stable():
    """连续小步拖动 (每帧 40px) 不许漂: 同一张卡 20 帧后仍是同一个 gx。"""
    st = R.Store()
    st.merge(frame(world(60), 0))
    first = {(c["row"], c["name"]): c["gx"] for c in st.summary()["cards"]}
    for k in range(1, 21):
        st.merge(frame(world(60), 40 * k))
    after = {(c["row"], c["name"]): c["gx"] for c in st.summary()["cards"]}
    for key, gx in first.items():
        if key in after:
            assert after[key] == gx, f"{key} 漂了: {gx} -> {after[key]}"


# ---------- 配准: 为什么不能只靠几何投票 ----------

def test_true_shift_scores_high_alias_scores_negative():
    """列是周期性的: 真平移 ±165.5k 的**别名**在几何上同样成立, 只能靠卡名内容区分。

    实机实测 (p2->p3): 真 Δ=66 得 +14, 次优只有 -2。合成帧同理。
    """
    st = R.Store()
    st.merge(frame(world(40), 0))
    cards = frame(world(40), 67)
    assert st._score(cards, 67)[0] >= 12
    assert st._score(cards, 67 + round(PITCH))[0] < 0
    assert st._score(cards, 67 - round(PITCH))[0] < 0


def test_align_picks_true_shift_over_alias():
    st = R.Store()
    st.merge(frame(world(40), 0))
    d, score, hits = st._align(frame(world(40), 67))
    assert d in (66, 67) and score >= 12 and hits >= 6


def store_covering(rows: dict, ncols: int) -> R.Store:
    """直接铺一个"只扫到前 ncols 列"的全局表 (不必真跑一遍扫描)。"""
    st = R.Store()
    for row, _, _ in R.ROWS:
        for i in range(ncols):
            st.cards.append({"row": row, "gx": int(round(X0 + i * PITCH)),
                             "name": rows[row][i], "base": "fi", "char": "Filia",
                             "element": 1, "how": "exact", "strip": rows[row][i]})
    return st


def test_align_prefers_zero_conflict_on_score_tie():
    """⚠️ 长串同名卡里的配准别名: **分数并列时不许选"有矛盾"的那个**。

    实机踩坑 (2026-10-14): 一遍扫出 255 张、另一遍 253 张, 两轮的左右端点帧
    一模一样 —— 坏的那次在 COLD STONES -> UNDERDOG 边界选了个"差一个列距"的
    别名 Δ, 整帧并成 dup, **漏**了一列 (图像相关测出的真值 Δ 恰是 0 冲突那个)。

    构造照着实机形状来: 本帧显示列 1..7, 全局表只覆盖列 0..4 (扫到一半、落后一列),
    于是"少一列的别名"能多配上一张卡 (多 1 命中) 却必然在名字边界撞一次 (多 1 冲突)
    ⇒ 与真值**同分**。旧版 `s > best_s` 保留先遇到的 (Δ 更小) = 别名 ⇒ 漏统计。
    """
    names = {"row1": ["P"] + ["Q"] * 7,     # 列 0/列 1 名字不同 -> 别名在这里撞一次
             "row2": ["Q"] * 8}             # row2 没有那个边界 -> 别名在这里白赚一命中
    st = store_covering(names, 5)
    cards = frame(names, PITCH)

    true_d = round(PITCH)
    alias_d = 0
    s_true = st._score(cards, true_d)
    s_alias = st._score(cards, alias_d)
    assert s_true[0] == s_alias[0], "构造失败: 真值与别名应当同分"
    assert s_true[2] == 0 and s_alias[2] == 1, "构造失败: 真值 0 冲突 / 别名 1 冲突"

    d, _score, _hits = st._align(cards)
    assert abs(d - true_d) <= R.PAIR_TOL, f"选了有冲突的别名 Δ={d} (真值 {true_d})"


def test_disjoint_content_refuses_to_merge():
    """内容完全不同 (实机 p1 vs p2 就是这样) -> 必须 aligned=False, 一张都不新增。"""
    st = R.Store()
    st.merge(frame(world(20, "OLD"), 0))
    rep = st.merge(frame(world(20, "NEW"), 0))
    assert rep["aligned"] is False and rep["new"] == 0 and rep["dup"] == 0
    assert st.summary()["count"] == 14


# ---------- 到头判据 ----------

def test_scan_sweeps_whole_grid_exactly_once():
    """整条链路: 回起点 -> 扫到底, 40 列 x 2 行 = 80 张, 不多不少。

    "到底" 用连续两次画面不动判定 (与既有轮播判据同款, 取两次是为了防
    `_adb_shell` 静默失效把"滑不动"误判成"到头")。
    """
    ncols = 40
    x0, last = X0, X0 + (ncols - 1) * PITCH
    limit = last - R.W + R.HALF + 1      # 最后一列刚好完整可见时的 scroll
    pos = {"s": limit}                   # 直接从最右端开始 (回起点要走一段)

    def make_frame():
        return frame(world(ncols), pos["s"])

    def drag(content_left: bool, alt: bool = False):
        step = 750 if content_left else -750
        pos["s"] = max(0.0, min(limit, pos["s"] + step))

    st = R.scan(make_frame, drag, log=lambda *a, **k: None, settle=0, max_drags=40)
    s = st.summary()
    assert s["count"] == ncols * 2, s["by_char"]
    assert s["dup_gaps"] == [] and s["miss_gaps"] == []
    assert s["conflicts"] == []
    assert abs(s["min_gap"] - PITCH) < 3


def test_scan_stops_at_end_instead_of_looping_forever():
    pos = {"s": 0.0, "drags": 0}

    def make_frame():
        return frame(world(12), pos["s"])

    def drag(content_left: bool, alt: bool = False):
        pos["drags"] += 1
        pos["s"] = min(pos["s"] + (750 if content_left else -750), 900.0)

    R.scan(make_frame, drag, log=lambda *a, **k: None, settle=0, max_drags=40)
    # 12 列 = 1900px 宽, 750/次 -> 回起点 0 次 + 扫 2 次就到头, 再加 2 次"没动"
    assert pos["drags"] <= 8, pos["drags"]


# ---------- 读一帧 (reader 适配) ----------

class FakeReader:
    """按世界列喂名条框与卡名, 并记录被问过的卡名 ROI。"""

    def __init__(self, names: dict, scroll: float):
        self.asked = []
        self.names = {}
        self.boxes = {}
        for row, _, _ in R.ROWS:
            bs = []
            for i, nm in enumerate(names[row]):
                cx = int(round(X0 + i * PITCH - scroll))
                bs.append((cx - 25, 330, cx + 25, 338))     # 名条框 (宽 50)
                self.names[cx] = nm
            self.boxes[row] = bs

    def lines(self, img, roi):
        row = "row1" if roi[1] < 400 else "row2"
        return [(b, self.names.get((b[0] + b[2]) // 2, "")) for b in self.boxes[row]
                if b[0] >= roi[0] and b[2] <= roi[2]]

    def read_name(self, img, roi):
        self.asked.append(roi)
        return self.names.get((roi[0] + roi[2]) // 2, "")


def test_make_cards_uses_strip_boxes_for_centres_and_banner_roi_for_name():
    rd = FakeReader(world(20), 0)
    cards = R.make_cards(rd, None)
    assert len(cards) == 14
    assert all(c["name"].startswith("CARD") for c in cards)
    # 卡名 ROI 必须正好是 (cx±HALF, 卡名带), 且只用在卡名带上
    row1 = [r for r in rd.asked if r[1] == 296]
    assert len(row1) == 7 and all(r[3] == 326 and r[2] - r[0] == 2 * R.HALF for r in row1)


def test_make_cards_skips_half_visible_edge_cards():
    """名条框越界 (半张卡) 直接不评 —— 否则会把切一半的名字当新卡记进去。"""
    rd = FakeReader(world(20), 0)
    for row in rd.boxes:
        rd.boxes[row] = [(1233, 330, 1276, 338)] + rd.boxes[row][:7]   # 右缘半张
    assert len(R.make_cards(rd, None)) == 14    # 半张那一列被丢, 不能凭空多出
