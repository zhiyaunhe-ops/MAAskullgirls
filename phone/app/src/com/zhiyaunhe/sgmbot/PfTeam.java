package com.zhiyaunhe.sgmbot;

import android.graphics.Bitmap;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * PF 的"手眼" —— 编队选人、选对手、能量/火框的像素算法。
 * 移植自电脑端 {@code tools/pf_vision.py} + {@code pf_bot.py} 的 fix_team/pick_opponent 族。
 *
 * <h3>为什么这一层完全不依赖 OCR</h3>
 * 能量判据是<b>像素</b>的: 卡底黄色闪电数 = 当前能量, 严格高亮黄掩码 (R≥240,G≥200,B≤105)
 * 对金色卡的淡黄底 (231,231,132) 免疫; 火框是<b>红色连通域</b>; 规则合规性是 FIGHT 按钮的
 * <b>高饱和占比</b>。这三样在 PC 上本来就不用 OCR。
 *
 * <h3>降级点 (手机端无 OCR, 均标 TODO(ocr))</h3>
 * <ul>
 *   <li>对手<b>战力/倍率</b>读不到 ⇒ 退化为"取第一张卡"。PC 的 {@code min(key=power or 1e18)}
 *       在 power 全 None 时也是这个结果, 口径一致 —— 只是选不到最优对手。</li>
 *   <li>防守队<b>角色名复验</b>读不到 ⇒ 只做拖拽不做复验 (失败面收窄但仍会推进)。</li>
 * </ul>
 */
final class PfTeam {

    private final ShizukuCtl sh;
    private final TplStore tpls;
    private final PfFlow flow;

    /** 配置 (config.json 的 pf 段)。 */
    private int energyCost = Pf.ENERGY_COST;

    /**
     * 能量门槛**只升不降**, 最高 {@link Pf#ENERGY_MAX_COST}。
     *
     * <p>⚠️ 2026-10-16 用户: 「能量看错了, 才导致这个弹窗」。根因: 配置 {@code energy_cost=4},
     * 而**角色场每场要 5 颗黄钉** ⇒ 恰好 4 钉的角色被放行出战 ⇒ 游戏弹
     * 「ENERGY REFILL / Someone needs a breather!」。
     * 卡名还没 OCR, 认不出"这是不是角色场", 所以让 bot **从游戏自己的拒绝里学**:
     * 看到这个弹窗就把门槛 +1 (封顶 5 = 各场次的最大值)。只升不降 —— 降会立刻重犯。
     */
    void bumpEnergyCost() {
        if (energyCost >= Pf.ENERGY_MAX_COST) return;
        energyCost++;
        flow.log("能量门槛判低了 (游戏弹了 ENERGY REFILL) — 门槛 " + (energyCost - 1)
                + " → " + energyCost + ", 后续按新门槛编队");
    }
    private boolean favorite = true;
    private String ruleType;      // "element" | "class" | null
    private String ruleValue;
    private int tapDelayMs = 400;
    private int pagesMax = 30;

    PfTeam(ShizukuCtl sh, TplStore tpls, PfFlow flow) {
        this.sh = sh;
        this.tpls = tpls;
        this.flow = flow;
    }

    void configure(JSONObject c) {
        JSONObject p = c.optJSONObject("pf");
        if (p == null) return;
        energyCost = p.optInt("energy_cost", Pf.ENERGY_COST);
        favorite = p.optBoolean("favorite", true);
        tapDelayMs = p.optInt("tap_delay_ms", 400);
        pagesMax = p.optInt("pages_max", 30);
        /* 临时采集 hook (2026-10-16): config 里 "pf": {"dump_opp": true} 即开 */
        PfDump.setEnabled(p.optBoolean("dump_opp", false));
        JSONObject r = p.optJSONObject("rule");
        if (r != null) {
            String t = r.optString("type", "");
            String v = r.optString("value", "");
            if (("element".equals(t) && Pf.validElement(v)) || ("class".equals(t) && !v.isEmpty())) {
                ruleType = t;
                ruleValue = v;
            }
        }
    }

    int tapDelayMs() { return tapDelayMs; }

    /** 当前场次规则 (null = 无)。 */
    String ruleType() { return ruleType; }

    String ruleValue() { return ruleValue; }

    boolean hasRule() { return ruleType != null; }

    boolean loadTemplates() {
        return tpls.has(Pf.T_HUB_PLAY) && tpls.has(Pf.T_REFRESH) && tpls.has(Pf.T_FIGHT);
    }

    // ==================================================================
    //  能量 (黄钉) —— pf_vision.count_yellow_bolts / read_slot_energy
    // ==================================================================

    /**
     * 统计 (cx ± half) 窗口内 band_y 行带的黄钉数量。
     *
     * <p>两颗钉子会连成一段游程, 所以按<b>列占比游程</b>计数而非数连通域:
     * 逐列算该列在行带内的黄像素占比 → 占比 ≥ th 视为"有钉"，连续若干列算一颗。
     * {@code frac.max() < 0.12} 直接判 0 —— 整带没有任何一列够亮, 说明这里压根没有钉条
     * (卡面装饰/空槽), 不参与计数。
     */
    private int countBolts(Vision.Frame f, int[] band, int cx, int half) {
        int x0 = Math.max(0, cx - half), x1 = Math.min(f.w, cx + half);
        int y0 = Math.max(0, band[0]), y1 = Math.min(f.h, band[1]);
        int n = x1 - x0, rows = y1 - y0;
        if (n <= 0 || rows <= 0) return 0;
        double[] frac = new double[n];
        double maxFrac = 0;
        for (int x = 0; x < n; x++) {
            int cnt = 0;
            for (int y = 0; y < rows; y++) {
                if (Pf.isBolt(f.px[(y0 + y) * f.w + (x0 + x)])) cnt++;
            }
            frac[x] = cnt / (double) rows;
            if (frac[x] > maxFrac) maxFrac = frac[x];
        }
        if (maxFrac < 0.12) return 0;
        return countRuns(frac, 0.08, 2, 9);
    }

    /** 占比序列里 ≥ th 的游程数 (长度落在 [runMin, runMax] 才算一颗)。 */
    private static int countRuns(double[] frac, double th, int runMin, int runMax) {
        int count = 0, w = 0;
        boolean in = false;
        for (double v : frac) {
            if (v >= th) {
                in = true;
                w++;
            } else {
                if (in && w >= runMin && w <= runMax) count++;
                in = false;
                w = 0;
            }
        }
        if (in && w >= runMin && w <= runMax) count++;
        return count;
    }

    /** 三个出战槽的黄钉数。 */
    int[] readSlotEnergy(Vision.Frame f) {
        int[] out = new int[3];
        for (int i = 0; i < 3; i++) {
            out[i] = countBoltsScaled(f, Pf.SLOT_BAND, Pf.SLOT_PIP_CX[i], Pf.SLOT_HALF, 8);
        }
        return out;
    }

    /** 可见候选位的黄钉数 (真机 8 张, 见 {@link Pf#ROSTER_CX})。 */
    int[] readRosterEnergy(Vision.Frame f) {
        int[] out = new int[Pf.ROSTER_CENTERS.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = countBolts(f, Pf.ROSTER_BAND, Pf.ROSTER_CENTERS[i][0], Pf.ROSTER_HALF);
        }
        return out;
    }

    private int countBoltsScaled(Vision.Frame f, int[] band, int cx, int half, int runMax) {
        int x0 = Math.max(0, cx - half), x1 = Math.min(f.w, cx + half);
        int y0 = Math.max(0, band[0]), y1 = Math.min(f.h, band[1]);
        int n = x1 - x0, rows = y1 - y0;
        if (n <= 0 || rows <= 0) return 0;
        double[] frac = new double[n];
        double maxFrac = 0;
        for (int x = 0; x < n; x++) {
            int cnt = 0;
            for (int y = 0; y < rows; y++) {
                if (Pf.isBolt(f.px[(y0 + y) * f.w + (x0 + x)])) cnt++;
            }
            frac[x] = cnt / (double) rows;
            if (frac[x] > maxFrac) maxFrac = frac[x];
        }
        if (maxFrac < 0.12) return 0;
        return countRuns(frac, 0.08, 2, runMax);
    }

    // ==================================================================
    //  火框 (红色连通域) —— pf_vision.find_fire_badge
    // ==================================================================

    /**
     * 卡右上角火焰倍率徽章的 bbox {@code [x0,y0,x1,y1]}, 无火返回 null。
     *
     * <p>徽章位置随卡片宽窄浮动 (2 号卡更宽更靠左), 固定 ROI 会切掉数字 ⇒ 必须在搜索窗里
     * 找最大红色连通域, 并要求<b>角部约束</b> (右缘接近窗右、顶部接近窗顶), 以排除卡内
     * 红色元素的头像/装饰。
     */
    static int[] findFireBadge(Vision.Frame f, int[] search) {
        int sx = search[0], sy = search[1], sw = search[2], sh2 = search[3];
        int x1 = Math.min(f.w, sx + sw), y1 = Math.min(f.h, sy + sh2);
        int w = x1 - sx, h = y1 - sy;
        if (w <= 0 || h <= 0) return null;
        boolean[] mask = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                mask[y * w + x] = Pf.isRed(f.px[(sy + y) * f.w + (sx + x)]);
            }
        }
        // 8 邻域连通域 (迭代栈 flood fill), 只保留面积最大且满足角部约束的那个。
        boolean[] seen = new boolean[w * h];
        int[] stack = new int[w * h];
        int bestArea = 0, bx0 = 0, by0 = 0, bx1 = 0, by1 = 0;
        for (int i = 0; i < mask.length; i++) {
            if (!mask[i] || seen[i]) continue;
            int sp = 0;
            stack[sp++] = i;
            seen[i] = true;
            int area = 0, minX = w, maxX = -1, minY = h, maxY = -1;
            while (sp > 0) {
                int p = stack[--sp];
                int px = p % w, py = p / w;
                area++;
                if (px < minX) minX = px;
                if (px > maxX) maxX = px;
                if (py < minY) minY = py;
                if (py > maxY) maxY = py;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = px + dx, ny = py + dy;
                        if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                        int ni = ny * w + nx;
                        if (mask[ni] && !seen[ni]) {
                            seen[ni] = true;
                            stack[sp++] = ni;
                        }
                    }
                }
            }
            /* ⚠️⚠️ 形状约束 (2026-10-17 实栽): 这里原来只有面积 + "右缘/顶部"两条, 而右上角的
             * **CONTINUE 按钮**是橙色 (r-g=140 也算"红")、又贴着窗右与顶部 ⇒ 每页都被当成
             * "卡1 有火框" (日志里「对手卡1: 火框=true」几乎页页成立), 且它的 bbox (66x45) 还会被
             * 当成徽章去取数字带 ⇒ 卡 1 倍率永远读出个 0.2 的垃圾分。
             * 徽章实测 39~45 宽 / 37~57 高; CONTINUE 在带区里被裁成 66 宽 ⇒ 宽度上限 55 就够分开。 */
            if (area < 600 || area <= bestArea) continue;
            int bw = maxX - minX + 1, bh = maxY - minY + 1;
            if (bw < 24 || bw > 55 || bh < 18 || bh > 70) continue;
            // 角部约束: 右缘接近窗右, 顶部接近窗顶
            if (sx + maxX + 1 < x1 - 60 || minY > 60) continue;
            bestArea = area;
            bx0 = sx + minX;
            by0 = sy + minY;
            bx1 = sx + maxX;
            by1 = sy + maxY;
        }
        if (bestArea == 0) return null;
        return new int[]{bx0 - 2, by0 - 2, bx1 + 2, by1 + 2};
    }

    // ==================================================================
    //  选对手 —— pf_bot.pick_opponent
    // ==================================================================

    /**
     * 选对手: 有火框选倍率最大, 否则选战力最低。
     *
     * <p>TODO(ocr): 战力 (卡左上) 与倍率 (火框内数字) 都读不到 ⇒ 现在恒定退化到第 1 张卡。
     * 补齐数字识别后, 把 {@link #readPower}/{@link #readMult} 的返回值接上即可 ——
     * 选择逻辑本身已按 PC 写全 (含"倍率可读的排前、读不出的按战力"那个二级排序)。
     */
    void pickOpponent(Vision.Frame work, Bitmap bmp) {
        flow.setStepText("选对手");
        /* ⚠️⚠️ 先等入场动画落定 (2026-10-17 dump 实证): 选对手页卡片带 ~0.9 倍缩放飞入,
         * 动画中途截屏的倍率徽章小一圈, NCC 只剩 0.6~0.75 且相邻档位交叉分能到 0.75+
         * ⇒ 分数与静止帧不同分布, 既读不出也可能读错档。600ms 后重截一帧再读。 */
        PfFlow.sleep_(600);
        Bitmap b0 = sh.capture();
        if (b0 != null) { bmp = b0; work = Vision.toWork(b0); }

        boolean[] fire = new boolean[3];
        int fireCount = 0, firstFire = -1;
        double[] mults = readPass(work, fire);

        /* 有火框却读不出倍率 ⇒ 多半还是撞上动画尾巴: 等 500ms 重截再读一轮 (只一次) */
        boolean needRetry = false;
        for (int i = 0; i < 3; i++) {
            if (fire[i]) {
                fireCount++;
                if (firstFire < 0) firstFire = i;
                if (Double.isNaN(mults[i])) needRetry = true;
            }
        }
        if (needRetry) {
            flow.log("有火框但倍率不可读 — 疑似动画帧, 500ms 后重读一轮");
            PfFlow.sleep_(500);
            Bitmap b1 = sh.capture();
            if (b1 != null) {
                work = Vision.toWork(b1);
                bmp = b1;
                mults = readPass(work, fire);      // fire 状态重测 (轮播可能换页)
                fireCount = 0; firstFire = -1;
                for (int i = 0; i < 3; i++) {
                    if (fire[i]) {
                        fireCount++;
                        if (firstFire < 0) firstFire = i;
                    }
                }
            }
        }
        for (int i = 0; i < 3; i++) {
            flow.log("对手卡" + (i + 1) + ": 倍率=" + fmt(mults[i]) + " 火框=" + fire[i]);
        }
        /* PC 口径 (pf_bot.pick_opponent): **倍率可读的排前, 倍率大优先**;
         * 都读不出来才退"组内战力最低" (战力也读不到 ⇒ 退首个火框卡 / 第 1 张)。 */
        int chosen = -1;
        double bestMult = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < 3; i++) {
            if (fireCount > 0 && !fire[i]) continue;      // readPass 里已测过, 别重复搜
            if (Double.isNaN(mults[i])) continue;
            if (mults[i] > bestMult) {
                bestMult = mults[i];
                chosen = i;
            }
        }
        if (chosen >= 0) {
            /* 选对手结果**带图**: 三张卡的倍率读数 + 最终选了谁, 一张图就能复盘 */
            SgmLog.i("pf", "选对手: 卡" + (chosen + 1) + " 倍率最大 = " + fmt(bestMult), bmp);
        } else if (fireCount > 0) {
            chosen = firstFire;
            SgmLog.i("pf", "选对手: 倍率不可读 — 退化取首个火框卡 " + (chosen + 1), bmp);
        } else {
            chosen = 0;
            SgmLog.i("pf", "选对手: 无火框 — 退化取第 1 张 (战力不可读)", bmp);
        }
        int[] p = Pf.OPP_CLICK[chosen];
        flow.tap(bmp, p[0], p[1]);
        PfFlow.sleep_(900);        // 点 CONTINUE 进编队, 并等对手页消失 (防重复选人)。
        Bitmap b2 = sh.capture();
        if (b2 == null) return;
        Vision.Frame w2 = Vision.toWork(b2);
        double[] cont = match(w2, Pf.T_CONTINUE, Pf.ROI_TOPRIGHT, Pf.TH_CONTINUE);
        if (cont != null) {
            flow.tap(b2, (int) cont[0] + tplW(Pf.T_CONTINUE) / 2,
                    (int) cont[1] + tplH(Pf.T_CONTINUE) / 2);
            flow.log("已点 CONTINUE");
            for (int i = 0; i < 10; i++) {
                PfFlow.sleep_(1000);
                Bitmap b3 = sh.capture();
                if (b3 == null) break;
                if (match(Vision.toWork(b3), Pf.T_REFRESH, Pf.ROI_REFRESH, Pf.TH_REFRESH) == null) break;
            }
        }
    }

    /**
     * 倍率模板 ↔ 数值。
     *
     * <p>⚠️ 倍率取值是小集合 (x1 / x1.5 / x2 / x2.5 / x3 …), 字与底色都固定 ⇒
     * **不需要 OCR, 模板匹配就够**。缺哪个模板就自动跳过那一档 (TplStore.get 返回 null),
     * 所以这张表可以按需扩充, 不会因为缺样本而报错。
     *
     * <p>⚠️⚠️ 2026-10-17 换成「**数字带**」口径 (见 {@link #bandWindow}), 之前是整块 60x66
     * 徽章模板。换的理由 (真机 206 页 dump 实证):
     * <ul>
     *   <li>整块模板里带了**对手卡面美术** —— 每个对手都不一样 ⇒ 同档位在同一卡位会分裂成
     *       多个簇, 换对手就掉到 0.5~0.65 (当初"3 张卡里 2 张读不出"的真因)。</li>
     *   <li>三张卡是**透视扇形**, 卡 1/卡 3 的徽章比卡 2 小约 13% ⇒ 整块模板还得按卡位各出一套。</li>
     * </ul>
     * 数字带只含「白字 + 红底」, 背景被构造性排除; 再按**白字高度**归一化尺寸 ⇒ 透视与
     * 入场动画缩放一起消掉, **每档一套模板**就够 (离线实测跨卡位同档位能聚到同一簇)。
     */
    private static final String[] MULT_TPL = {
            "pf/mult_1", "pf/mult_15", "pf/mult_2", "pf/mult_25", "pf/mult_3",
            "pf/mult_35", "pf/mult_4", "pf/mult_5"};
    /* ⚠️ 3.5 是真机实见档位 (2026-10-17 采集时遇到 x3.5 徽章), PC 的候选集没有它;
     * x1/x4/x5 的完整徽章模板还没采到, 缺哪个自动跳过那档。 */
    private static final double[] MULT_VAL = {1, 1.5, 2, 2.5, 3, 3.5, 4, 5};

    /* ---- 数字带口径 (⚠️ 与 tools/pf_mult_band.py 完全一致, 改一边必须改另一边) ---- */
    /** 窗口尺寸 = 白字 bbox × 该系数 (留出红底上下文, 让 "x1" 这种窄档也有参照)。 */
    private static final double BAND_WIN = 1.9;
    /** 归一化后的窗口尺寸 (Python 侧 cv2.resize 到同一个尺寸)。 */
    private static final int BAND_W = 48, BAND_H = 27;
    /** 四周边缘复制 padding —— 给匹配留 ±3px 平移容错 (等价 cv2 的 BORDER_REPLICATE)。 */
    private static final int BAND_PAD = 3;

    /**
     * 数字带模板表。同档位带有多个变体 (b 后缀) —— 同一档位在不同卡位/不同对手美术下
     * 会有轻微差异, 分别出模板再**取该档最高分**, 比硬凑一张"平均图"更稳
     * (离线实测: 单变体时 x1.5 在卡 2 上只到 0.81, 补了变体后回到 0.95+)。
     */
    private static final String[] BAND_TPL = {
            "pf/band_15", "pf/band_15b", "pf/band_15c", "pf/band_15d",
            "pf/band_2", "pf/band_25", "pf/band_25b",
            "pf/band_3", "pf/band_3b", "pf/band_35", "pf/band_35b", "pf/band_4", "pf/band_45"};
    private static final double[] BAND_VAL = {
            1.5, 1.5, 1.5, 1.5, 2, 2.5, 2.5, 3, 3, 3.5, 3.5, 4, 4.5};

    /**
     * 倍率命中阈值 —— **0.90 不是拍的**, 是按真机 206 页样本的分布切的:
     * 正确档实测 0.931~0.977 (中位 0.96), 而**错档最高只到 0.874**
     * ("x1.5" 与 "x3.5" 共用后两位字形, 交叉分集中在 0.83~0.874)。
     * ⚠️ 反过来说: 0.90 这条线同时也挡掉了"我们还缺模板的档位"(x1/x5) ——
     * 那些档位现在会被判不可读并退化, 这是**故意**的 (宁可漏读不可错读)。
     */
    private static final double TH_MULT = 0.90;
    /** 第二道门: 最高分要甩开次高 (正确档的领先实测 0.11~0.22)。 */
    private static final double MARGIN_MULT = 0.05;
    /** **高置信豁免**: 绝对分够高时不再强求领先 —— "x1.5 vs x3.5" 这类同尾字形
     *  会给出薄领先 (实测 0.940 vs 0.874 = 0.066), 但 0.94 的绝对分已经足够说明问题。 */
    private static final double TH_STRONG = 0.93;

    /** 一轮对手页读数: 逐卡测火框 + 倍率 (临时采样 hook 挂在这里)。fire[i] 带回各卡火框状态。 */
    private double[] readPass(Vision.Frame work, boolean[] fire) {
        /* 临时截图 hook (2026-10-16): 采选对手页样本补 mult_* 模板 —— 关着时零开销 */
        PfDump.oppPage(work);
        double[] mults = new double[3];
        for (int i = 0; i < 3; i++) {
            /* ⚠️ 火焰框只算一次 (flood fill 别跑两遍) —— readMult 要拿它取数字带。 */
            int[] fb = findFireBadge(work, Pf.FIRE_SEARCH[i]);
            fire[i] = fb != null;
            mults[i] = readMult(work, Pf.FIRE_SEARCH[i], fb);
        }
        return mults;
    }

    /**
     * 读倍率 —— 先按火焰框取出「数字带」, 再拿各档模板比一遍, 取最高的那档。
     *
     * <p>⚠️⚠️ {@code findScored} 返回 **{x, y, best}**, 分数是 **r[2]** ——
     * 曾经在 PfArena 里写成 r[0] 拿到 x 坐标当分数, 害得判据恒真 (2026-10-16 实栽)。
     */
    private double readMult(Vision.Frame f, int[] roi, int[] fb) {
        /* 没有火焰 = 这张卡没倍率徽章 ⇒ 直接判不可读。
         * ⚠️ 别在这里走老口径兜底: 整块模板扫描是要成本的 (每页 3 张卡 × 8 模板 × 全 ROI),
         *    而"没徽章"是最常见的情况 (206 页样本里大半卡位没有), 兜底只会白烧时间 + 刷
         *    「倍率(兜底)不可读」这种误导性日志。 */
        if (fb == null) return Double.NaN;
        Vision.Frame band = bandWindow(f, fb);
        if (band == null) {
            /* 有火焰但取不出数字带 (白字太小 —— 入场动画帧糊掉) ⇒ 老口径兜底:
             * 整块徽章模板放在 ROI 里搜, 比直接返回 NaN 多一次机会。 */
            return readMultLegacy(f, roi);
        }
        Vision.Frame padded = pad(band, BAND_PAD);

        /* 同档位多变体: 先按"档位"归并取最高分, 再跨档比 —— 否则同名档位的两个变体会
         * 互相挤掉 (b1/b2 变成"x1.5 的两个变体", 领先幅度就永远过不了门)。 */
        java.util.LinkedHashMap<Double, Double> byTier = new java.util.LinkedHashMap<>();
        for (int i = 0; i < BAND_TPL.length; i++) {
            Vision.Frame t = tpls.get(BAND_TPL[i]);
            if (t == null) continue;               // 这一档还没模板, 跳过
            /* ⚠️ 别传 0.0 指望"阈值 0 自然拿最高分" —— findScored 是命中早退, th=0 会
             * 返回 ROI 里**第一个非负补丁** (实测 0.0280 vs 真峰值 0.9311, 2026-10-17)。 */
            double[] r = Vision.findScored(padded, t, null, Vision.SCAN_ALL, -1, 0, 1);
            if (r == null) continue;
            double s = r[2];
            Double prev = byTier.get(BAND_VAL[i]);
            if (prev == null || s > prev) byTier.put(BAND_VAL[i], s);
        }
        if (byTier.isEmpty()) return readMultLegacy(f, roi);

        double b1 = 0, b2 = 0, val = Double.NaN;   // 最高 / 次高 / 对应档位
        for (java.util.Map.Entry<Double, Double> e : byTier.entrySet()) {
            double s = e.getValue();
            if (s > b1) { b2 = b1; b1 = s; val = e.getKey(); }
            else if (s > b2) { b2 = s; }
        }
        /* ⚠️⚠️ 两道门: 阈值 + **领先幅度**。数字带口径下同档位自匹配中位 0.958, 异档最高
         * 0.827 (离线 163 样本) ⇒ 0.70 + 领先 0.08 落在空档里。留这道门的理由仍是防
         * **入场动画帧**: 徽章缩水时相邻档位的交叉分会升高, 宁可判不可读也不选错档。 */
        boolean ok = b1 >= TH_STRONG || (b1 >= TH_MULT && b1 - b2 >= MARGIN_MULT);
        if (!ok) {
            flow.log(String.format("倍率不可读: 最高 x%s=%.3f 次高=%.3f 领先=%.3f"
                    + " (门: ≥%.2f 且领先 ≥%.2f, 或 ≥%.2f 直接采纳)",
                    fmt(val), b1, b2, b1 - b2, TH_MULT, MARGIN_MULT, TH_STRONG));
            return Double.NaN;
        }
        return val;
    }

    /** 老口径 (整块徽章模板) —— 只在数字带取不出来时兜底。 */
    private double readMultLegacy(Vision.Frame f, int[] roi) {
        double b1 = 0, b2 = 0;
        String bestName = null;
        double val = Double.NaN;
        for (int i = 0; i < MULT_TPL.length; i++) {
            Vision.Frame t = tpls.get(MULT_TPL[i]);
            if (t == null) continue;
            double[] r = Vision.findScored(f, t, roi, Vision.SCAN_ALL, -1, 0, 1);
            if (r == null) continue;
            double s = r[2];
            if (s > b1) { b2 = b1; b1 = s; bestName = MULT_TPL[i]; val = MULT_VAL[i]; }
            else if (s > b2) { b2 = s; }
        }
        /* ⚠️ 别把"没模板"和"分数不够"混成一句"不可读" —— 2026-10-17 实栽:
         * 离线 cv2 对同一帧打分 0.93, App 内却恒 NaN, 两种原因的修法完全不同。 */
        if (b1 < TH_MULT || b1 - b2 < MARGIN_MULT) {
            flow.log(String.format("倍率(兜底)不可读: 最高 %s=%.3f 次高=%.3f",
                    bestName == null ? "无模板" : bestName, b1, b2));
            return Double.NaN;
        }
        return val;
    }

    /**
     * 从火焰框里取出**归一化的数字带** —— 与 {@code tools/pf_mult_band.py} 同一口径。
     *
     * <p>步骤: ① 火焰 bbox 内找**白字 bbox** (r,g,b 都 >150) ② 以字高为尺度, 开一个
     * {@code 1.9×字宽 × 1.9×字高} 的窗口居中裁出来 ③ 缩放到 48x27。
     * 这样卡位透视 (±13%)、入场动画缩放、以及各档位文字宽度差异全部归一化。</p>
     *
     * @return 归一化后的窗口; 字太小/取不到时返回 null (调用方走老口径兜底)
     */
    static Vision.Frame bandWindow(Vision.Frame f, int[] fb) {
        int x0 = Math.max(0, fb[0]), y0 = Math.max(0, fb[1]);
        int x1 = Math.min(f.w, fb[2]), y1 = Math.min(f.h, fb[3]);
        if (x1 - x0 < 8 || y1 - y0 < 8) return null;
        int mnx = Integer.MAX_VALUE, mny = Integer.MAX_VALUE, mxx = -1, mxy = -1;
        for (int y = y0; y < y1; y++) {
            int row = y * f.w;
            for (int x = x0; x < x1; x++) {
                int p = f.px[row + x];
                int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
                if (r > 150 && g > 150 && b > 150) {          // 白字
                    if (x < mnx) mnx = x;
                    if (x > mxx) mxx = x;
                    if (y < mny) mny = y;
                    if (y > mxy) mxy = y;
                }
            }
        }
        if (mxx < 0) return null;
        int tw = mxx - mnx + 1, th = mxy - mny + 1;
        if (tw < 8 || th < 8) return null;      // 不成比例 (大概把边框认成字了)
        /* 窗口: 以字 bbox 为中心, 尺寸 = 字宽/字高 × BAND_WIN, 夹到帧内后缩放到 48x27 */
        int cw = Math.max(8, (int) Math.round(tw * BAND_WIN));
        int ch = Math.max(8, (int) Math.round(th * BAND_WIN));
        int cx = (mnx + mxx) / 2, cy = (mny + mxy) / 2;
        int wx0 = Math.max(0, cx - cw / 2), wy0 = Math.max(0, cy - ch / 2);
        int wx1 = Math.min(f.w, wx0 + cw), wy1 = Math.min(f.h, wy0 + ch);
        int ww = wx1 - wx0, wh = wy1 - wy0;
        if (ww < 8 || wh < 8) return null;
        int[] px = new int[ww * wh];
        for (int y = 0; y < wh; y++)
            System.arraycopy(f.px, (wy0 + y) * f.w + wx0, px, y * ww, ww);
        Vision.Frame crop = new Vision.Frame(px, ww, wh);
        return crop.scaleXY(BAND_W / (double) ww, BAND_H / (double) wh);
    }

    /** 四周边缘复制 padding (等价 cv2.copyMakeBorder BORDER_REPLICATE) —— 给匹配留平移容错。 */
    static Vision.Frame pad(Vision.Frame f, int n) {
        int w = f.w + n * 2, h = f.h + n * 2;
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++) {
            int sy = Math.min(f.h - 1, Math.max(0, y - n));
            for (int x = 0; x < w; x++) {
                int sx = Math.min(f.w - 1, Math.max(0, x - n));
                px[y * w + x] = f.px[sy * f.w + sx];
            }
        }
        return new Vision.Frame(px, w, h);
    }

    /** TODO(ocr): 火框内倍率 ('x1.5'/'X2')。读不到返回 NaN。 */
    /** TODO(ocr): 战力 (卡左上数字, '28.1k'/'9,713')。读不到返回 NaN (仍按 PC 的二级排序兜底)。 */
    private double readPower(Vision.Frame f, int[] roi) {
        return Double.NaN;
    }

    private static String fmt(double v) { return Double.isNaN(v) ? "?" : String.format("%.1f", v); }

    // ==================================================================
    //  编队 —— pf_bot.fix_team / drag_best_to_slot / refill_rule_slot
    // ==================================================================

    /**
     * 把能量不足的槽位换成候选区里能量达标的角色 (战力优先 = 从左往右)。
     *
     * @return false = 候选耗尽 / 规则无法满足 (调用方停)
     */
    boolean fixTeam(PfFlow owner, boolean replacing) {
        flow.setStepText("编队(" + (replacing ? "替换能量不足" : "初始化") + ")");
        energyWaits = 0;            // 每次进入编队重新计"等能量"的轮数
        noEnergy = false;

        /* ⚠️⚠️ 读能量前**先确保筛选面板是关的** (2026-10-16 实测踩到):
         * 面板一旦开着就会盖住顶部的三个出战槽和底部候选列 ⇒ 钉条读数全 0
         * ⇒ bot 判"三个槽都要换人"却一个都拖不动 ⇒ 白等 30 轮能量恢复 (每轮 60s = 半小时)。
         * 现场日志: 「槽位能量 [0,0,0] / 候选区能量 [0,0,0,0,0,0,0,0]」刷屏, 而截图里
         * 面板明明开着、选人区被压暗。面板开/关的判据本身没错, 错在没人**保证它关着**。 */
        if (panelOpen()) {
            flow.log("筛选面板还开着 — 先关掉再读能量");
            int guard = 0;
            while (panelOpen() && guard++ < 3) {
                Bitmap bb = sh.capture();
                if (bb == null) break;
                tapResident(bb, Pf.FILTER_CLOSE);
                PfFlow.sleep_(1500);
            }
        }

        // 首次编队: 按设置归位筛选 (清残留 + 喜爱芯片) —— 游戏内筛选跨运行持久。
        if (!owner.isFilterCleared()) {
            owner.markFilterCleared();
            List<int[]> fav = favChips();
            flow.log("首次编队归位筛选: 喜爱筛选" + (fav.isEmpty() ? "关" : "开"));
            setFilter(fav);
        }
        // 归零滚动: 连续右滑, 回到候选列表起点。
        Bitmap b0 = sh.capture();
        if (b0 != null) {
            for (int i = 0; i < 4; i++) {
                flow.swipe(b0, Pf.X(420), Pf.Y(570), Pf.X(1150), Pf.Y(570), 400);
                PfFlow.sleep_(600);
            }
        }

        // 规则槽: 只有 element 规则才进出战队编队路径 —— class 规则只约束防守队
        // (PC 2026-10-13 口径: 旧实现把 CLASS_CHIPS 插进出战队筛选链 ⇒ 池内只剩该角色
        //  0 能量变体 ⇒ 空烧 80s「规则补人失败」停机, 已废弃)。
        List<int[]> ruleChips = new ArrayList<>(favChips());
        int ruleSlot = -1;
        if ("element".equals(ruleType)) {
            int[] chip = Pf.elementChip(ruleValue);
            if (chip != null) ruleChips.add(0, chip);
            if (owner.ruleDoneFight() == owner.fightNo()) {
                ruleSlot = 0;
            } else {
                Bitmap bj = sh.capture();
                if (bj != null && judgeRule(Vision.toWork(bj))) {
                    flow.log("队伍已满足规则");
                } else {
                    flow.log("队伍不满足规则 element=" + ruleValue + ", 筛选替换");
                    int placed = 0;
                    boolean ok = true;
                    while (placed < 3) {
                        if (!refillRuleSlot(owner, placed, ruleChips, favChips())) { ok = false; break; }
                        placed++;
                        Bitmap bp = sh.capture();
                        if (bp != null && judgeRule(Vision.toWork(bp))) break;
                    }
                    Bitmap bf = sh.capture();
                    if (!ok || bf == null || !judgeRule(Vision.toWork(bf))) {
                        flow.log("放入 3 人后 FIGHT 仍灰 / 补人失败, 规则无法满足, 停止");
                        return false;
                    }
                    flow.log("规则替换完成, 剩余槽位做能量替换");
                }
                owner.setRuleDone(owner.fightNo());
                ruleSlot = 0;
            }
        }

        int pages = 0;
        int[] slotFails = new int[3];
        final int[][] offsets = {{0, 0}, {14, 6}, {-14, 10}, {0, 18}};
        while (true) {
            Bitmap b = sh.capture();
            if (b == null) return false;
            Vision.Frame w = Vision.toWork(b);

            if (match(w, Pf.T_DETAIL, Pf.ROI_FULL, Pf.TH_FIGHT) != null) {
                flow.log("误入角色详情页, 返回");
                flow.tap(b, Pf.BACK_BTN[0], Pf.BACK_BTN[1]);
                PfFlow.sleep_(2000);
                continue;
            }
            int[] slots = readSlotEnergy(w);
            if (ruleSlot == 0 && slots[0] < energyCost) {
                flow.log("槽位能量 " + arr(slots) + ", 规则槽1能量不足, 优先筛选替换合规角色");
                if (!refillRuleSlot(owner, 0, ruleChips, favChips())) return false;
                continue;
            }
            List<Integer> bad = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                if (slots[i] < energyCost && i != ruleSlot) bad.add(i);
            }
            if (bad.isEmpty()) {
                flow.log("槽位能量 " + arr(slots) + ", 全部达标 (门槛 " + energyCost + ")");
                return true;
            }
            flow.log("槽位能量 " + arr(slots) + ", 需替换槽位 " + list(bad));

            int[] roster = readRosterEnergy(w);
            flow.log("候选区能量 " + arr(roster));
            /* ⚠️ 重试要**换一张卡**, 不能只挪偏移量 (2026-10-16 实栽: 槽3 连拖 4 次全失败,
             * 每次都是同一张候选[4] + 不同偏移 ⇒ 偏移改了也没用, 白等 4 轮然后整个运行停掉。
             * 换卡的成功面比换偏移大得多 —— 某些卡面(已在队里被压暗/刚拖过还没复位)
             * 本来就拖不动, 换一张立刻就好。偏移仍保留, 只是排在换卡之后当微调。 */
            List<Integer> cands = new ArrayList<>();
            for (int i = 0; i < roster.length; i++) {
                if (roster[i] >= energyCost) cands.add(i);
            }
            int src = -1;
            if (!cands.isEmpty()) {
                int fails = slotFails[bad.get(0)];
                src = cands.get(fails % cands.size());      // 每次失败换下一张
            }
            if (src < 0) {
                pages++;
                if (pages > pagesMax) {
                    /* ⚠️⚠️ "没能量" ≠ "失败" (2026-10-16 为「跑 200 场」改):
                     * 旧实现直接 return false ⇒ 上层立刻 `fatal = "编队失败: 无可用能量角色"`
                     * **结束整轮**。但 PF 的能量是**随时间恢复**的 (每槽黄钉会涨回来),
                     * 打不了 5 场就必然全体见底 —— 那样根本跑不到 200 场, 而这不是故障,
                     * 是游戏机制。正确做法是**等**: 把候选列归零、睡一个恢复周期、重新找。
                     * 只在连续等了 ENERGY_WAIT_MAX 轮仍无可用角色时才真判定失败
                     * (那时多半是配置/判定出了问题, 而不是单纯没能量)。 */
                    if (++energyWaits > ENERGY_WAIT_MAX) {
                        flow.log("候选区翻页超限, 已等待 " + energyWaits + " 轮仍无可用能量的角色 — 停止");
                        noEnergy = true;
                        return false;
                    }
                    flow.log("候选区无可换角色 (第 " + energyWaits + "/" + ENERGY_WAIT_MAX
                            + " 轮等待能量恢复, 每轮 " + (ENERGY_WAIT_MS / 1000) + "s)");
                    flow.setStepText("等待能量恢复 " + energyWaits + "/" + ENERGY_WAIT_MAX);
                    PfFlow.sleep_(ENERGY_WAIT_MS);
                    pages = 0;                                  // 归零重试
                    Bitmap bw = sh.capture();
                    if (bw != null) {
                        for (int i = 0; i < 4; i++) {
                            flow.swipe(bw, Pf.X(420), Pf.Y(570), Pf.X(1150), Pf.Y(570), 400);
                            PfFlow.sleep_(400);
                        }
                    }
                    continue;
                }
                flow.swipe(b, Pf.X(1150), Pf.Y(570), Pf.X(420), Pf.Y(570), 400);
                PfFlow.sleep_(900);
                continue;
            }
            int slotI = bad.get(0);
            int sx = Pf.ROSTER_CENTERS[src][0], sy = Pf.ROSTER_CENTERS[src][1];
            int dx = Pf.SLOT_DROP_X[slotI], dy = Pf.DRAG_DY;
            int fails = slotFails[slotI];
            // 偏移只作为"换卡之外"的微调, 且要跳过已经用过的档位 (见上面换卡的注释)
            int[] off = offsets[Math.min(fails / Math.max(1, cands.size()),
                    offsets.length - 1)];
            flow.log("拖拽 候选[" + src + "](能量" + roster[src] + ") -> 槽位" + (slotI + 1)
                    + ((off[0] != 0 || off[1] != 0) ? " (偏移" + off[0] + "," + off[1] + ")" : ""));
            flow.drag(b, sx, sy, dx + off[0], dy + off[1]);
            /* ⚠️⚠️ 2026-10-16 实测破案: 落点坐标根本没错 —— 手工把同一张卡拖到槽1 的
             * 四个不同落点 (165/232/200/130, y=192), **每一次都成功**(槽1 能量 4↔5 来回变)。
             * bot 失败的原因是**只等 1400ms 就读数**, 而换人动画(卡换进去 + 钉条重新填充)
             * 还没播完 ⇒ 读到的是旧卡 ⇒ 判"拖拽未生效" ⇒ 白白重试 4 次然后放弃。
             * 表现就是日志里槽 1 反复「拖拽未生效 → 放弃 → 回主循环 → 再来」, 单场拖 40+ 次。
             * 现在: 先等 2500ms; 若读数仍不达标, 再补等 1800ms 复读一次, 两次都不过才算失败。 */
            PfFlow.sleep_(2500);

            Bitmap b2 = sh.capture();
            if (b2 == null) return false;
            int[] slots2 = readSlotEnergy(Vision.toWork(b2));
            if (slots2[slotI] < energyCost) {
                PfFlow.sleep_(1800);                       // 补读: 动画可能还没完
                Bitmap b2b = sh.capture();
                if (b2b != null) slots2 = readSlotEnergy(Vision.toWork(b2b));
            }
            if (slots2[slotI] < energyCost) {
                slotFails[slotI] = fails + 1;
                /* ⚠️ 必须**有上限** (2026-10-15 用户当场看到的现象: "为什么一直在拉拽最底下那排"):
                 * 旧实现只把偏移量封顶, 失败计数照涨、循环照转 ⇒ 拖不中就**永远拖下去**,
                 * 日志每 3s 刷一行「拖拽 -> 槽位N / 拖拽未生效」刷到 83 行不停, 也不退还主循环。
                 * 拖不中有两种可能 (坐标错 / 该卡不可拖), 两种都不该靠无限重试解决。 */
                if (slotFails[slotI] >= DRAG_MAX_FAILS) {
                    flow.log("槽位" + (slotI + 1) + " 连续 " + DRAG_MAX_FAILS
                            + " 次拖拽未生效 — 放弃该槽 (坐标或卡面不可拖), 回主循环");
                    return false;
                }
                flow.log("槽位" + (slotI + 1) + " 拖拽未生效 (" + slotFails[slotI] + "/"
                        + DRAG_MAX_FAILS + "), 归零候选列重试");
                for (int i = 0; i < 4; i++) {
                    flow.swipe(b2, Pf.X(420), Pf.Y(570), Pf.X(1150), Pf.Y(570), 400);
                    PfFlow.sleep_(400);
                }
            } else {
                slotFails[slotI] = 0;
            }
        }
    }

    /** 单个槽位拖拽连续失败多少次就放弃 (防止"一直拉拽最底下那排"的死循环)。 */
    private static final int DRAG_MAX_FAILS = 4;

    /**
     * 找不到够能量的角色时, **等待能量恢复**的轮数与每轮时长。
     *
     * <p>PF 的黄钉随时间恢复, 连续打几十场必然见底。要跑满 200 场就必须等, 而不是停机。
     * 30 轮 x 60s ≈ 30 分钟的容忍度: 够撑过正常恢复周期, 又不至于在真出问题时无限挂机。
     */
    private static final int ENERGY_WAIT_MAX = 30;
    private static final long ENERGY_WAIT_MS = 60_000L;

    /** 最近一次 fixTeam 失败是不是"真的没能量"(供上层区分: 等 vs 停)。 */
    private boolean noEnergy;

    /** 本轮 fixTeam 已等了多少轮能量恢复。 */
    private int energyWaits;

    boolean lastFailWasNoEnergy() { return noEnergy; }

    /** 从当前 (可能已筛选) 候选区拖左一个能量达标的角色到指定槽位, 带校验重试。 */
    private boolean dragBestToSlot(int slotI) {
        int pages = 0;
        int fails = 0;
        while (true) {
            Bitmap b = sh.capture();
            if (b == null) return false;
            Vision.Frame w = Vision.toWork(b);
            int[] roster = readRosterEnergy(w);
            flow.log("筛选后候选区能量 " + arr(roster));
            int src = -1;
            for (int i = 0; i < roster.length; i++) {
                if (roster[i] >= energyCost) { src = i; break; }
            }
            if (src < 0) {
                pages++;
                if (pages > pagesMax) {
                    noEnergy = true;     // 筛选后整排都不够能量 ⇒ 同样是"没能量", 不是故障
                    return false;
                }
                flow.swipe(b, Pf.X(1150), Pf.Y(570), Pf.X(420), Pf.Y(570), 400);
                PfFlow.sleep_(900);
                continue;
            }
            flow.drag(b, Pf.ROSTER_CENTERS[src][0], Pf.ROSTER_CENTERS[src][1],
                    Pf.SLOT_DROP_X[slotI], Pf.DRAG_DY);
            /* 同 fixTeam: 1400ms 不够等换人动画, 读到旧卡会误判失败。 */
            PfFlow.sleep_(2500);
            Bitmap b2 = sh.capture();
            int e = b2 == null ? 0 : readSlotEnergy(Vision.toWork(b2))[slotI];
            if (e < energyCost) {
                PfFlow.sleep_(1800);
                Bitmap b2b = sh.capture();
                e = b2b == null ? 0 : readSlotEnergy(Vision.toWork(b2b))[slotI];
            }
            if (e >= energyCost) return true;
            /* 同上: 拖不中必须收敛, 不能无限重试 (用户 2026-10-15 看到的就是这个死循环)。 */
            if (++fails >= DRAG_MAX_FAILS) {
                flow.log("筛选槽拖拽连续 " + DRAG_MAX_FAILS + " 次未生效 — 放弃");
                return false;
            }
            for (int i = 0; i < 4; i++) {
                flow.swipe(b, Pf.X(420), Pf.Y(570), Pf.X(1150), Pf.Y(570), 400);
                PfFlow.sleep_(400);
            }
        }
    }

    /**
     * 按 规则芯片+喜爱 筛选, 拖一个能量达标的合规角色进指定槽, 再还原筛选。
     *
     * @return false = 筛选后无达标角色
     */
    private boolean refillRuleSlot(PfFlow owner, int slotI, List<int[]> chips, List<int[]> favChips) {
        String wantEl = "element".equals(ruleType) ? ruleValue : null;
        boolean ok = false;
        for (int attempt = 0; attempt < 2; attempt++) {
            setFilter(chips);
            Bitmap bs = sh.capture();
            if (bs != null) {
                for (int i = 0; i < 4; i++) {
                    flow.swipe(bs, Pf.X(420), Pf.Y(570), Pf.X(1150), Pf.Y(570), 400);
                    PfFlow.sleep_(500);
                }
            }
            Bitmap bchk = sh.capture();
            if (bchk != null && candidateIsElement(Vision.toWork(bchk), wantEl)) {
                ok = true;
                break;
            }
            flow.log("筛选后首卡元素不符 (清空/点亮可能被吃), 重开面板再筛");
        }
        if (!ok) {
            flow.log("筛选复验连续失败, 放弃本次补人");
            return false;
        }
        boolean dragged = dragBestToSlot(slotI);
        setFilter(favChips);                          // 取消规则筛选, 保留喜爱
        Bitmap br = sh.capture();
        if (br != null) {
            for (int i = 0; i < 4; i++) {
                flow.swipe(br, Pf.X(420), Pf.Y(570), Pf.X(1150), Pf.Y(570), 400);
                PfFlow.sleep_(500);
            }
        }
        return dragged;
    }

    /**
     * 打开筛选面板: 清空 → 依次点亮 chips → 关闭 (验证失败自动重试)。
     *
     * <p>⚠️ 全部用<b>驻留式点击</b> —— 筛选面板会吃掉零时长的点击 (爱心/关闭按钮实测无效),
     * 必须给 Unity 一两帧指针停留。
     */
    void setFilter(List<int[]> chips) {
        Bitmap b = sh.capture();
        if (b == null) return;
        /* 漏斗位置**用模板找**, 不硬编码: 它锚在 20:9 屏幕右缘, PC 的换算给 work x=1100 而
         * 实测 1240 (差 140px ⇒ 点下去什么都没发生)。找不到就退回实测坐标兜底。 */
        int[] btn = flow.funnel(Vision.toWork(b));
        if (btn == null) btn = Pf.FILTER_BTN;
        /* ⚠️⚠️ 漏斗必须用**真 tap** (`input tap`), 不能用 tapResident ——
         * tapResident 走的是"零距离 180ms 滑动", 对漏斗这种小图标实测**点不开面板**
         * (同坐标连点 3 次都不开, 日志「漏斗=0.03 ✕=0.20」), 而 `input tap` 一下就成了。
         * 2026-10-16 实栽: 坐标/ROI/模板/等待时长全查过一轮, 最后发现是**手势类型**不对。 */
        flow.tap(b, btn[0], btn[1]);
        /* ⚠️⚠️ 2500ms 不能短 (2026-10-16 实测): 原来 1200ms —— 手工点漏斗后等 4s 面板确实开了
         * (✕ 模板 0.985), 但 bot 等 1200ms 就去判"面板呢", 动画没播完 ⇒ panelOpen() 返回 false
         * ⇒ 重试一次仍然 false ⇒ **放弃筛选**。日志里那句「筛选面板打不开」有一半是这么来的,
         * 不是坐标错、也不是模板错。 */
        PfFlow.sleep_(2500);
        /* ⚠️ 必须**验证面板真的开了**再往下点 (2026-10-15 实栽): 漏斗坐标曾经是错的,
         * 于是"清空/芯片/关闭"三下全打在游戏画面上 —— 面板从没打开过, 而函数**静默返回**,
         * 上层以为筛选已生效 ⇒ 拖人完全没有筛选条件。这就是"选角色不加筛选条件"的真身。 */
        if (!panelOpen()) {
            flow.tap(b, btn[0], btn[1]);
            PfFlow.sleep_(2500);
            if (!panelOpen()) {
                /* 再给一次机会: 有的机型/画质下漏斗图标的渲染会变 (2026-10-16 实测: 旧模板
                 * 在同一台机器上从 1.000 掉到 0.44, 就是因为图标背景多了一圈青色辉光)。
                 * 与其直接放弃筛选, 不如按"点了没反应"再试一轮, 并把分数写进日志。 */
                Bitmap b3 = sh.capture();
                if (b3 != null) {
                    flow.tap(b3, btn[0], btn[1]);
                    PfFlow.sleep_(2500);
                }
            }
            if (!panelOpen()) {
                /* 诊断: 记下漏斗/✕ 的**实际最分**再放弃 —— 只说"打不开"没法分辨三种情况:
                 * ① 漏斗没找到(坐标错) ② 漏斗点了但游戏没响应 ③ 面板开了只是 ✕ 模板没匹配上。
                 * 这三种的修法完全不同, 日志必须给出分数 (2026-10-15 教训)。 */
                Bitmap bd = sh.capture();
                flow.log("筛选面板打不开 — 本次放弃筛选 (别静默当成功) | 诊断: "
                        + (bd == null ? "无帧" :
                           "漏斗=" + best(Vision.toWork(bd), Pf.T_FILTER_BTN, Pf.ROI_FILTER_BTN)
                           + " ✕=" + best(Vision.toWork(bd), Pf.T_FILTER_X, Pf.ROI_FILTER_X)
                           + " 点过=(" + btn[0] + "," + btn[1] + ")"));
                return;
            }
        }
        tapResident(b, Pf.FILTER_CLEAR);
        PfFlow.sleep_(400);
        for (int[] c : chips) {
            tapResident(b, c);
            PfFlow.sleep_(400);
        }
        flow.log("筛选面板: 清空 + 点亮 " + chips.size() + " 个芯片");
        for (int attempt = 0; attempt < 3; attempt++) {
            tapResident(b, Pf.FILTER_CLOSE);
            PfFlow.sleep_(1000);
            if (!panelOpen()) {
                flow.log("筛选面板已关闭 (第 " + (attempt + 1) + " 次尝试)");
                return;
            }
            flow.log("筛选面板未关闭, 重试 " + (attempt + 1) + "/2");
        }
        flow.log("筛选面板关闭失败, 带面板继续 (后续读数会异常)");
    }

    /**
     * 筛选面板是否开着 —— 用面板右上那个 ✕ 当锚点。
     *
     * <p>比"看候选列变没变"可靠得多: 候选列本来就可能是筛选后的样子 (游戏内筛选跨运行持久),
     * 而 ✕ 只在面板打开时存在。
     */
    /** 该模板在 ROI 内的最高分 (只报分数, 不看阈值 —— 用于诊断)。 */
    private double best(Vision.Frame work, String tplName, int[] roi) {
        Vision.Frame t = tpls.get(tplName);
        if (t == null) return -9;                     // 模板缺失
        /* 只看分数 ⇒ SCAN_ALL (见 Vision.SCAN_ALL 注释: 传 0.0 会拿到"第一个非负补丁") */
        double[] r = Vision.findScored(work, t, roi, Vision.SCAN_ALL, -1, 0, 1);
        return r == null ? -1 : r[2];
    }

    private boolean panelOpen() {
        Bitmap b = sh.capture();
        return b != null && matchFull(Vision.toWork(b), Pf.T_FILTER_X, Pf.ROI_FILTER_X,
                Pf.TH_FILTER_X) != null;
    }

    /** 驻留式点击: 按下-停-抬起 (同 PC 的 _tap)。 */
    private void tapResident(Bitmap bmp, int[] p) {
        flow.swipe(bmp, p[0], p[1], p[0], p[1], 180);
    }

    /**
     * 候选列第一张卡是否目标元素 (用于确认筛选面板的清空/点亮确实生效)。
     *
     * <p>取样点 = 首卡**左上角的元素图钉** (金环包彩色小图标, 2026-10-16 真机实测
     * work ≈ (12-54, 364-406))。旧值 {@code Pf.R(37,470,49,620)} 换算后是
     * work (158,376)-(168,496) —— 落在首卡**右缘**之外, 采到的几乎全是卡缝背景,
     * 什么元素都判"不符" ⇒ 规则补人永远「筛选后首卡元素不符」白转 (2026-10-16 实栽,
     * 现场日志连续「重开面板再筛 → 复验连续失败」)。
     *
     * <p>light/neutral 无法靠颜色分辨 ⇒ 返回 true (不验证)。
     */
    private boolean candidateIsElement(Vision.Frame f, String element) {
        if (element == null || element.isEmpty()) return true;
        float[] range = elementHue(element);
        if (range == null) return true;               // light/neutral: 不做颜色复验
        int[] box = {12, 364, 42, 42};                // work [x,y,w,h] — 首卡元素图钉
        int ok = 0, n = 0;
        float[] hsv = new float[3];
        for (int y = box[1]; y < box[1] + box[3] && y < f.h; y++) {
            for (int x = box[0]; x < box[0] + box[2] && x < f.w; x++) {
                hsv(f.px[y * f.w + x], hsv);
                if (hsv[1] >= 80 / 255f && hsv[2] >= 90 / 255f
                        && inHue(hsv[0], range[0], range[1])) ok++;
                n++;
            }
        }
        // 图钉在 42x42 窗口里占一半左右, 卡面底色偶尔入镜: 阈值 15% 两侧余量都够。
        return n > 0 && ok >= 0.15 * n;
    }

    /** 元素色相区间 (度, 0-360)。light/neutral 返回 null (不可颜色分辨)。 */
    private static float[] elementHue(String element) {
        if ("fire".equals(element)) return new float[]{0, 18};        // 含 340-360 的下半段另判
        if ("water".equals(element)) return new float[]{192, 260};
        if ("wind".equals(element)) return new float[]{100, 194};
        if ("dark".equals(element)) return new float[]{262, 292};
        return null;
    }

    private static boolean inHue(float h, float lo, float hi) {
        if (lo <= hi) return h >= lo && h <= hi;
        return h >= lo || h <= hi;
    }

    /** 元素色相区间 (fire 的红跨 0 度, 用两段)。 */
    private static boolean hueHit(float h, String element) {
        if ("fire".equals(element)) return h <= 18 || h >= 340;
        float[] r = elementHue(element);
        return r != null && inHue(h, r[0], r[1]);
    }

    /** RGB → HSV (h 0-360, s/v 0-1)。 */
    static void hsv(int argb, float[] out) {
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        int mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
        float d = mx - mn, h;
        if (d == 0) h = 0;
        else if (mx == r) h = 60 * (((g - b) / d) % 6);
        else if (mx == g) h = 60 * ((b - r) / d + 2);
        else h = 60 * ((r - g) / d + 4);
        if (h < 0) h += 360;
        out[0] = h;
        out[1] = mx == 0 ? 0 : d / mx;
        out[2] = mx / 255f;
    }

    // ==================================================================
    //  规则合规性 —— pf_bot.judge_rule
    // ==================================================================

    /**
     * 当前<b>出战队</b>是否满足规则。
     *
     * <p>游戏自身以 FIGHT 按钮颜色提示合规性: 满足 = 橙色 (高饱和), 不满足 = 灰色。
     * ⚠️ 角色 (class) 规则只约束<b>防守队</b>, 出战队不受限 ⇒ 恒 true (PC 同口径)。
     * 找不到 FIGHT 按钮 (不在编队页) 也返回 true, 不触发筛选。
     */
    boolean judgeRule(Vision.Frame w) {
        if (!"element".equals(ruleType)) return true;
        double[] fight = match(w, Pf.T_FIGHT, Pf.ROI_TOPRIGHT, Pf.TH_FIGHT);
        if (fight == null) return true;
        int tw = tplW(Pf.T_FIGHT), th = tplH(Pf.T_FIGHT);
        int cx = (int) fight[0] + tw / 2, cy = (int) fight[1] + th / 2;
        int x0 = Math.max(cx - 65, 0), x1 = Math.min(cx + 65, w.w);
        int y0 = Math.max(cy - 18, 0), y1 = Math.min(cy + 18, w.h);
        int lit = 0, n = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                if (litColor(w.px[y * w.w + x])) lit++;
                n++;
            }
        }
        double ratio = n == 0 ? 0 : lit / (double) n;
        flow.log(String.format("规则判定: FIGHT 按钮高饱和占比 %.0f%% -> %s",
                ratio * 100, ratio >= Pf.TH_RULE_LIT ? "满足" : "不满足"));
        return ratio >= Pf.TH_RULE_LIT;
    }

    /** 高饱和亮色 (PC inRange(hsv,(0,100,120),(180,255,255))): S≥100/255 且 V≥120/255。 */
    private static boolean litColor(int argb) {
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        int mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
        return mx >= 120 && (mx - mn) * 255 >= 100 * mx;
    }

    // ==================================================================
    //  战斗速度 / 自动战斗 —— pf_bot.ensure_battle_auto
    // ==================================================================

    /**
     * 战斗速度泡: 0 = 无泡 (auto 未开或未渲染), 1/2/3 = 当前倍率。
     *
     * <h3>⚠️⚠️ 2026-10-18 重写 (用户: 「自动的 *3 没点上, 都只有 *1」)</h3>
     * 旧实现 = 按 3x→2x→1x 顺序找**第一个**过 {@code TH_SPD(0.85)} 的模板。两处都错:
     * <ul>
     *   <li><b>阈值 0.85 根本过不了</b>: 6 张真机帧灰度 NCC 实测 1x 最高 0.818、3x 最高 0.745
     *       ⇒ 恒返回 0, 于是"速度泡未识别, 跳过提速", 永远 1x。</li>
     *   <li><b>金字塔 + 命中早退</b>: 48~56px 的小模板在 0.5x/0.25x 上峰值被削
     *       (同 MEMORY 里"金字塔只对全屏大按钮成立")。改走 {@link #matchFull}。</li>
     * </ul>
     * 现口径: 三个模板**全搜**, 取最高分定档, 要求 ≥{@link Pf#TH_SPD} 且领先次高
     * {@link Pf#SPD_LEAD} —— 1x 帧领先 0.29 / 3x 帧领先 0.12, 余量充足。
     */
    int battleSpeedLevel(Vision.Frame w) {
        int[] b = speedBest(w);
        return b == null ? 0 : b[2];
    }

    /**
     * 速度泡定点: {@code {cx, cy, level, score}}; 认不出返回 null。
     *
     * <p>顺带给出**泡的实际中心** —— 升档时点它而不是常量, HUD 抖动/不同分辨率都不会点空。</p>
     */
    int[] speedBest(Vision.Frame w) {
        String[] names = {Pf.T_SPD_1X, Pf.T_SPD_2X, Pf.T_SPD_3X};
        int[] lv = {1, 2, 3};
        double best = -1, second = -1;
        int bx = 0, by = 0, bw = 0, bh = 0, bl = 0;
        for (int i = 0; i < names.length; i++) {
            Vision.Frame t = tpls.get(names[i]);
            if (t == null) continue;
            double[] r = matchFull(w, names[i], Pf.ROI_SPD, Pf.TH_SPD);
            lastSpeedScores[i] = r == null ? -1 : r[2];
            if (r == null) continue;
            if (r[2] > best) {
                second = best;
                best = r[2]; bl = lv[i];
                bx = (int) r[0]; by = (int) r[1]; bw = t.w; bh = t.h;
            } else if (r[2] > second) {
                second = r[2];
            }
        }
        if (best < Pf.TH_SPD) return null;
        /* 领先要求: 次高可能是 0 (只有一片过阈值), 0.04 的余量只用来挡"两片几乎并列" */
        if (second > 0 && best - second < Pf.SPD_LEAD) return null;
        return new int[]{bx + bw / 2, by + bh / 2, bl, (int) Math.round(best * 100)};
    }

    /**
     * 最近一次 {@link #speedBest} 里三片模板的原始分 {@code [1x, 2x, 3x]}。
     *
     * <p>⚠️ 只留在日志里用: 这个 bug 类 ("*3 没点上") 光看结论分不清是"没认出来"还是
     * "认错档", 必须能一眼看到三片各多少分。别拿它做判据。</p>
     */
    private final double[] lastSpeedScores = {-1, -1, -1};

    /** 升档要用: 泡中心 (work); 认不出退回 {@link Pf#SPD_BTN}。 */
    private int[] speedTapPoint(Vision.Frame w) {
        int[] b = speedBest(w);
        return b == null ? Pf.SPD_BTN : new int[]{b[0], b[1]};
    }

    /** 三片分数格式化 (0.80 / -1 = 没跑). */
    private String sc(int i) {
        double v = lastSpeedScores[i];
        return v < 0 ? "?" : String.valueOf(Math.round(v * 100) / 100.0);
    }

    /** 脑子图标亮度的 V 均值 (亮 ~101 / 灭 ~49), 判据见 {@link Pf#TH_BRAIN_V}。 */
    double brainValue(Vision.Frame w) {
        int[] box = Pf.BRAIN_BOX;
        return Vision.sampleMean(w, box, new Vision.Chan() {
            public double of(int argb) {
                return Math.max((argb >> 16) & 0xFF, Math.max((argb >> 8) & 0xFF, argb & 0xFF));
            }
        });
    }

    /** 脑子图标亮 = 自动战斗开。 */
    boolean brainAutoOn(Vision.Frame w) {
        return brainValue(w) >= Pf.TH_BRAIN_V;
    }

    /**
     * 战斗开场: 等 HUD → 开自动 → 升到 3x。
     *
     * <h3>⚠️ 2026-10-18 重写</h3>
     * 旧版: 打完 FIGHT 固定等 1.8s → 看一眼 → 扑空就"跳过", 且调用方有**一次性 latch**
     * ⇒ 第一次扑空之后整轮再也不会试 (用户看到的就是"都只有 *1")。
     * 新版: ① 最多 {@link Pf#SPD_WAIT_MS} 轮询等泡出现; ② 每点一次都重新定位泡中心并复读;
     * ③ 返回**是否确认 3x**, 由调用方决定下一场要不要再试。
     *
     * @return true = 已确认 3x (调用方可以不再重试)
     */
    boolean ensureBattleAuto() {
        Bitmap b = null;
        Vision.Frame w = null;
        long t0 = System.currentTimeMillis();
        while (System.currentTimeMillis() - t0 < Pf.SPD_WAIT_MS) {
            PfFlow.sleep_(600);
            b = sh.capture();
            if (b == null) continue;
            w = Vision.toWork(b);
            if (speedBest(w) != null) break;
        }
        if (w == null || speedBest(w) == null) {
            /* ⚠️⚠️ 2026-10-21 用户质疑: 「没检测出来? 不可能」。他是对的 —— 读数是硬数据:
             * 真机 1x 帧上 battle_spd_1x 模板 0.805~0.818, 3x 帧 0.727~0.745, 存在性阈值
             * 只有 0.62 ⇒ **只要画面里有泡就必然认得出**。所以"等了 N 秒没认出"的真正含义
             * 是**那段时间不在战斗画面**(FIGHT 后还在加载/开场动画), 不是识别失败。
             * 之前没留现场, 只能靠猜 —— 现在失败时**必存图 + 打三片峰值**。 */
            flow.log("战斗速度: 等了 " + (Pf.SPD_WAIT_MS / 1000) + "s 仍没有速度泡"
                    + " (画面可能还在加载/开场动画) — 本场先跳过, 战斗中会补测", b);
            return false;
        }

        /* ① 自动战斗没开就先点脑子 (只影响打不打, 不影响速度) */
        double bv = brainValue(w);
        if (bv < Pf.TH_BRAIN_V) {
            flow.log("自动战斗未开 (脑子亮度 " + Math.round(bv) + " < " + Math.round(Pf.TH_BRAIN_V) + "), 点一下");
            flow.tap(b, Pf.BRAIN_BTN[0], Pf.BRAIN_BTN[1]);
            PfFlow.sleep_(700);
            Bitmap b2 = sh.capture();
            if (b2 != null) { b = b2; w = Vision.toWork(b2); }
        }

        /* ② 升档: 每次点之前重新定位泡中心, 点完复读 */
        int[] cur = speedBest(w);
        int spd = cur == null ? 0 : cur[2];
        flow.log("速度泡读数: 1x=" + sc(0) + " 2x=" + sc(1) + " 3x=" + sc(2)
                + " → " + (spd == 0 ? "认不出" : spd + "x"));
        if (spd == 3) {
            flow.log("自动战斗已开, 速度 3x");
            return true;
        }
        for (int i = 0; i < 4 && spd > 0 && spd < 3; i++) {
            int[] p = speedTapPoint(w);
            flow.log("战斗速度 " + spd + "x → 点速度泡 (" + p[0] + "," + p[1] + ")");
            flow.tap(b, p[0], p[1]);
            PfFlow.sleep_(550);
            b = sh.capture();
            if (b == null) break;
            w = Vision.toWork(b);
            int[] nx = speedBest(w);
            if (nx == null) {
                flow.log("速度校验: 泡暂时认不出 (动画帧), 再读一轮");
                continue;
            }
            flow.log("速度校验: " + nx[2] + "x | 1x=" + sc(0) + " 2x=" + sc(1) + " 3x=" + sc(2));
            if (nx[2] == spd) {
                flow.log("⚠ 点了没升档 — 可能点到泡外或动画中");
            }
            spd = nx[2];
        }
        if (spd == 3) {
            flow.log("自动战斗已开, 速度 3x");
            return true;
        }
        flow.log("⚠ 速度没升到 3x (当前 " + spd + "x) — 下一场再试");
        return false;
    }

    // ==================================================================
    //  无 X 模态弹窗的判据 (PC 用正文 OCR; 这里用几何+颜色兜底)
    // ==================================================================

    /**
     * 难度锁定确认弹窗 (CANCEL/CONTINUE, 无 X)。
     *
     * <p>PC 靠正文含 {@code LOCKEDINTO}/{@code OTHERDIFFICULT} 认; 手机端没有 OCR ⇒
     * 用<b>情境 + 颜色</b>兜底: 只在"点完 FIGHT 之后"调用, 判 CONTINUE 位置是不是绿色按钮。
     * 该弹窗的两个按钮是整块纯色, 占比远高于普通界面上的零散绿像素。
     */
    boolean findLockPopup(Vision.Frame w) {
        return greenRatio(w, Pf.LOCK_CONTINUE[0], Pf.LOCK_CONTINUE[1], 42, 15) >= 0.45;
    }

    /**
     * 防守队说明弹窗 (正文 DEFENSETEAM, 按钮 OK, 无 X)。
     *
     * <p>PC 注释: 该弹窗<b>叠在选对手页之上</b> —— 同一帧里 REFRESH 仍然 0.95 命中,
     * 所以判据不能靠"没有已知界面", 只能认弹窗本身。手机端兜底:
     * <b>REFRESH 命中 + OK 位置是绿色按钮</b> (选对手页自身没有绿色按钮)。
     */
    boolean findDefensePopup(Vision.Frame w) {
        if (match(w, Pf.T_REFRESH, Pf.ROI_REFRESH, Pf.TH_REFRESH) == null) return false;
        return greenRatio(w, Pf.DEFENSE_OK[0], Pf.DEFENSE_OK[1], 42, 13) >= 0.45;
    }

    /** 以 (cx,cy) 为中心、半宽 hw/半高 hh 的区域内"绿色像素"占比。 */
    /* 去掉 private: PfFlow 的存档确认弹窗判据也要用它 (两个颜色同时对上才算, 见 confirmSaveTeam) */
    double greenRatio(Vision.Frame f, int cx, int cy, int hw, int hh) {
        int x0 = Math.max(0, cx - hw), x1 = Math.min(f.w, cx + hw);
        int y0 = Math.max(0, cy - hh), y1 = Math.min(f.h, cy + hh);
        if (x1 <= x0 || y1 <= y0) return 0;
        int ok = 0, n = 0;
        float[] hsv = new float[3];
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                hsv(f.px[y * f.w + x], hsv);
                // PC: inRange(hsv,(40,90,90),(85,255,255)) —— H 40-85 (0-180 刻度) = 80°-170°
                if (hsv[0] >= 80 && hsv[0] <= 170 && hsv[1] >= 90 / 255f && hsv[2] >= 90 / 255f) ok++;
                n++;
            }
        }
        return n == 0 ? 0 : ok / (double) n;
    }

    // ==================================================================
    //  小工具
    // ==================================================================

    private double[] match(Vision.Frame work, String tplName, int[] roi, double th) {
        Vision.Frame t = tpls.get(tplName);
        if (t == null) return null;
        double[] r = Vision.findScored(work, t, roi, th);
        return r != null && r[2] >= th ? r : null;
    }

    /**
     * 同 match, 但**强制全搜** —— 小模板 (≤64px) 一律走这条。
     *
     * <p>⚠️ 金字塔在 0.5x/0.25x 上会把小模板的峰值削到 0.15~0.18 (见 MEMORY「金字塔只对
     * 全屏搜大按钮成立」); 速度泡模板只有 48~56px, 走金字塔等于永远搜不到。</p>
     */
    private double[] matchFull(Vision.Frame work, String tplName, int[] roi, double th) {
        Vision.Frame t = tpls.get(tplName);
        if (t == null) return null;
        /* ⚠️⚠️ 传 {@link Vision#SCAN_ALL} 而不是 th: 传 th 是"命中早退", 拿回的是**第一个
         * 过线位置**而不是峰值。速度要拿三个模板的峰值比大小定档, 早退会让"次高"变成噪声
         * (同 MEMORY: 凡"只看分数"的调用一律 SCAN_ALL + step=1)。 */
        double[] r = Vision.findScored(work, t, roi, Vision.SCAN_ALL, -1, 1);
        return r != null && r[2] >= th ? r : null;
    }

    private int tplW(String name) {
        Vision.Frame t = tpls.get(name);
        return t == null ? 0 : t.w;
    }

    private int tplH(String name) {
        Vision.Frame t = tpls.get(name);
        return t == null ? 0 : t.h;
    }

    private List<int[]> favChips() {
        List<int[]> out = new ArrayList<>();
        if (favorite) out.add(Pf.FILTER_HEART);
        return out;
    }

    private static String arr(int[] a) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < a.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(a[i]);
        }
        return sb.append(']').toString();
    }

    private static String list(List<Integer> a) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < a.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(a.get(i) + 1);
        }
        return sb.append(']').toString();
    }
}
