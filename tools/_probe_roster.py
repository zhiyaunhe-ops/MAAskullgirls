"""角色仓库 (COLLECTION / FIGHTERS) 读卡闭环验证探针。

背景 (2026-10-13 用户口径): 角色仓库里 **卡名可重复、角色框多、坐标不固定**。验证
思路不靠手抄真值 —— 卡面上本来就有两套名字:
  卡名 (装饰大字, 如 'Regally Blonde')        <- 要读的目标, 经 variants.json 映射
  名条 (小字, 如 'PARASOUL')                  <- 干净的**地面真值角色**
两者对上才算端到端正确。走 **MAA 绑定** (PgBot.ocr_text_line), 即实机路径。

列坐标不写死: 先让 det 在名条带上找行, 行框中心即卡中心 (对应"坐标不固定")。

用法:
  python tools/_probe_roster.py <图> [更多图...] [--cutoff 0.75]
输出: 每卡一行 (卡名 / 命中方式 / 映射角色 / 名条 / 是否一致) + 汇总命中率。
"""
import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import pf_fighters as fighters                       # noqa: E402
from pf_bot import PfBot                             # noqa: E402
from _probe_ocr_offline import load                  # noqa: E402

# 名条/卡名 的竖直带 (1280x720 版式): 行内名条在 y322-350 / y552-580,
# 卡名 (装饰大字) 在其上方 ~26px。
BANDS = {"row1": dict(banner=(296, 326), strip=(322, 350)),
         "row2": dict(banner=(526, 556), strip=(552, 580))}
HALF = 76           # 以卡中心为准的左右半宽
RIGHT_EDGE = 1240   # 右缘残缺列 (被屏幕切掉) 不评
LEFT_EDGE = 40      # 左缘同理 (滑动后最左列会被切一半)


def norm(s):
    return "".join(ch for ch in (s or "").upper() if ch.isalnum())


def main(argv):
    ap = argparse.ArgumentParser(description="角色仓库读卡闭环验证")
    ap.add_argument("images", nargs="+")
    ap.add_argument("--cutoff", type=float, default=0.75)
    a = ap.parse_args(argv)

    bot = PfBot()
    bot.setup()
    tot = agree = exact = found = 0
    for path in a.images:
        img = load(path)
        if img is None:
            print(f"读不到图: {path}")
            continue
        print("=" * 92)
        print(path, img.shape)
        for rname, cfg in BANDS.items():
            by0, by1 = cfg["strip"]
            rows = [r for r in bot.ocr_lines(img, (0, by0, 1280, by1))
                    if r[0][2] < RIGHT_EDGE and r[0][0] > LEFT_EDGE]
            rows.sort(key=lambda r: r[0][0])
            print(f"-- {rname}: {len(rows)} 张完整卡 --")
            for (sx0, sy0, sx1, sy1), raw_strip in rows:
                cx = (sx0 + sx1) // 2
                by0b, by1b = cfg["banner"]
                card = bot.ocr_text_line(img, (cx - HALF, by0b, cx + HALF, by1b))
                strip = bot.ocr_text_line(img, (cx - HALF, by0, cx + HALF, by1))
                base, char, element, how = fighters.resolve(card, a.cutoff)
                ok = char is not None and norm(char) == norm(strip)
                tot += 1
                found += 1 if char else 0
                exact += 1 if how == "exact" else 0
                agree += 1 if ok else 0
                print(f"   cx={cx:4d} 卡名={card!r:20s}[{how:11s}] -> {str(char):12s} "
                      f"| 名条={strip!r:14s} {'OK' if ok else 'X'}")
    print("-" * 92)
    print(f"  卡名命中表 (含模糊): {found}/{tot}")
    print(f"  卡名**精确**命中  : {exact}/{tot}")
    print(f"  闭环一致 (卡名->角色 == 名条直读): {agree}/{tot}"
          + (f" = {agree / tot * 100:.0f}%" if tot else ""))
    print("  注: 不一致时先看名条侧 —— 小字号名条本身会读错 ('FIUA'/'SQUGLY' 实测出现过),")
    print("      卡名走的是 306 条闭集, 抖动能被纠回来, 这也是优先用卡名的原因。")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
