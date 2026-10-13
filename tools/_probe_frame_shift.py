"""用**图像相关**直接测相邻帧的真实像素位移 —— 与卡名/OCR/配准链完全无关。

**它是"到底该谁对"的唯一裁判。** 2026-10-14 实机扫描出过 255 与 253 两个数字,
两轮的左右端点帧一模一样; 靠它测出真值步长 (run1 frame0032->0033 真值 +484,
而配准老代码选的那步等价于 +318) 才定案: **255 是完整且不重复的**。

判据: 测得的真实步长累加出 Δ 链 -> 加上帧内 cx 范围 -> 总跨度 / 列距 = 真实列数。
列距用 `_probe_ocr_offline` 的名条框中心跨帧平均 (实测 165.5, 484 个样本)。

用法:
    python tools/_probe_frame_shift.py <帧目录>           # 逐帧位移表 + 总位移
    python tools/_probe_frame_shift.py <帧目录> --span    # 顺带估真实列数

阶段 A 是"回起点"(内容向右, 位移为正), 阶段 B 是"扫"(位移为负); 折返点在
前后符号翻转处, 两段分别累加再取跨度。
"""
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from _probe_ocr_offline import load  # noqa: E402

d = Path(sys.argv[1])
Y0, Y1 = 300, 500          # row1 卡面 (纹样丰富, 利于相关)
MAXS = 1100


def band(p):
    im = load(str(p))
    if im is None:
        return None
    g = im[Y0:Y1].astype(np.float32).mean(axis=2)
    return g


def shift(a, b):
    """返回 s: b 的内容 = a 的内容右移 s 像素 (即 b 上 x 对应 a 上 x-s)。"""
    best, bs = None, 0
    for s in range(-MAXS, MAXS + 1, 2):          # 粗搜
        if s >= 0:
            xa0, xb0, n = s, 0, a.shape[1] - s
        else:
            xa0, xb0, n = 0, -s, a.shape[1] + s
        if n < 400:
            continue
        v = np.abs(a[:, xa0:xa0 + n] - b[:, xb0:xb0 + n]).mean()
        if best is None or v < best:
            best, bs = v, s
    for s in range(bs - 3, bs + 4):              # 精搜
        if s >= 0:
            xa0, xb0, n = s, 0, a.shape[1] - s
        else:
            xa0, xb0, n = 0, -s, a.shape[1] + s
        if n < 400:
            continue
        v = np.abs(a[:, xa0:xa0 + n] - b[:, xb0:xb0 + n]).mean()
        if v < best:
            best, bs = v, s
    return bs, best


files = sorted(d.glob("*_roster_scan.jpg"))
prev = band(files[0])
pos, steps = 0, [(files[0].name[:4], 0, 0.0)]
for p in files[1:]:
    s, err = shift(prev, band(p))
    pos += s
    steps.append((p.name[:4], s, round(float(err), 2)))
    prev = band(p)

print("步表 (帧, 内容位移 px, 残差, 该帧累计位置):")
run = 0
for name, s, e in steps:
    run += s
    mark = "  ← 没动" if abs(s) < 5 else ""
    print(f"   {name} {s:6d}  err={e:6.2f}  位置 {run:7d}{mark}")

lo = min(run for run in np.cumsum([s for _n, s, _e in steps]))
hi = max(np.cumsum([s for _n, s, _e in steps]))
print(f"\n总跨度 = {hi - lo} px   (最低 {lo} / 最高 {hi})")

if "--span" in sys.argv:
    import pf_roster as R
    rd = R.OfflineReader()
    gaps = []
    for p in files:
        img = load(str(p))
        for _row, _b, loc in R.ROWS:
            boxes = rd.det.boxes(img, (0, loc[0], R.W, loc[1]))
            cs = sorted((b[0] + b[2]) / 2 for b in boxes
                        if b[0] > R.EDGE_L and b[2] < R.EDGE_R)
            gaps += [b - a for a, b in zip(cs, cs[1:])]
    pitch = float(np.median(gaps))
    # ⚠️ 列数要用 **gx 跨度**, 不是 Δ 跨度: 两端的帧各自还带一段帧内 cx 范围
    #    (左端最左卡 ~cx 90、右端最右卡 ~cx 1200)。直接拿 Δ 跨度算会少 ~6 列。
    print(f"列距 (名条框中心, {len(gaps)} 样本): 中位 {pitch:.3f}")
    print(f"Δ 跨度 {hi - lo}px 只是下限; 真实列数要拿 (Δ 跨度 + 两端帧内可达范围) 除以列距")

