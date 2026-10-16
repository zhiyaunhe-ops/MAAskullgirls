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
    int[] findFireBadge(Vision.Frame f, int[] search) {
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
            if (area < 400 || area <= bestArea) continue;
            // 角部约束: 右缘接近窗右, 顶部接近窗顶
            if (sx + maxX + 1 < x1 - 60 || sy + minY > sy + 60) continue;
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
        int fireCount = 0, firstFire = -1;
        for (int i = 0; i < 3; i++) {
            int[] badge = findFireBadge(work, Pf.FIRE_SEARCH[i]);
            boolean fire = badge != null;
            double power = readPower(work, Pf.OPP_POWER[i]);
            double mult = fire ? readMult(work, badge) : Double.NaN;
            if (fire) {
                fireCount++;
                if (firstFire < 0) firstFire = i;
            }
            flow.log("对手卡" + (i + 1) + ": 战力=" + fmt(power) + " 倍率=" + fmt(mult)
                    + " 火框=" + fire);
        }
        int chosen;
        if (fireCount > 0) {
            chosen = firstFire;
            flow.log("选对手: 火框卡" + (chosen + 1) + " (倍率/战力均不可读, 退化取首个火框卡)"
                    + " — TODO(ocr)");
        } else {
            chosen = 0;
            flow.log("选对手: 无火框, 退化取第 1 张 (战力不可读) — TODO(ocr)");
        }
        int[] p = Pf.OPP_CLICK[chosen];
        flow.tap(bmp, p[0], p[1]);
        PfFlow.sleep_(900);

        // 点 CONTINUE 进编队, 并等对手页消失 (防重复选人)。
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

    /** TODO(ocr): 战力 (卡左上数字, '28.1k'/'9,713')。读不到返回 NaN。 */
    private double readPower(Vision.Frame f, int[] roi) {
        return Double.NaN;
    }

    /** TODO(ocr): 火框内倍率 ('x1.5'/'X2')。读不到返回 NaN。 */
    private double readMult(Vision.Frame f, int[] badge) {
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
            PfFlow.sleep_(1400);

            Bitmap b2 = sh.capture();
            if (b2 == null) return false;
            int[] slots2 = readSlotEnergy(Vision.toWork(b2));
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
            PfFlow.sleep_(1400);
            Bitmap b2 = sh.capture();
            if (b2 != null && readSlotEnergy(Vision.toWork(b2))[slotI] >= energyCost) return true;
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
        tapResident(b, btn);
        PfFlow.sleep_(1200);
        /* ⚠️ 必须**验证面板真的开了**再往下点 (2026-10-15 实栽): 漏斗坐标曾经是错的,
         * 于是"清空/芯片/关闭"三下全打在游戏画面上 —— 面板从没打开过, 而函数**静默返回**,
         * 上层以为筛选已生效 ⇒ 拖人完全没有筛选条件。这就是"选角色不加筛选条件"的真身。 */
        if (!panelOpen()) {
            tapResident(b, btn);
            PfFlow.sleep_(1200);
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
        double[] r = Vision.findScored(work, t, roi, 0.0, -1, 0);
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
     * <p>取首卡左缘框条 (PC x37-49 / y470-620) 数元素色占比; light/neutral 无法靠颜色分辨
     * ⇒ 返回 true (不验证)。
     */
    private boolean candidateIsElement(Vision.Frame f, String element) {
        if (element == null || element.isEmpty()) return true;
        float[] range = elementHue(element);
        if (range == null) return true;               // light/neutral: 不做颜色复验
        int[] box = Pf.R(37, 470, 49, 620);
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
        // 实测风首卡绿色 ~43%, 错元素卡为 0: 阈值 15% 两侧余量都足够。
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
     * th=0.85: 同位实测 ≥0.95, 1x/3x 串扰 ≤0.76, 无泡画面 ≤0.14。
     */
    int battleSpeedLevel(Vision.Frame w) {
        String[] tpls3 = {Pf.T_SPD_3X, Pf.T_SPD_2X, Pf.T_SPD_1X};
        int[] lv = {3, 2, 1};
        for (int i = 0; i < tpls3.length; i++) {
            if (match(w, tpls3[i], Pf.ROI_SPD, Pf.TH_SPD) != null) return lv[i];
        }
        return 0;
    }

    /** 脑子图标亮 = 自动战斗开。实测亮 V 均值 ~101, 灯灭 ~49, 阈值 75。 */
    boolean brainAutoOn(Vision.Frame w) {
        int[] box = Pf.R(620, 670, 660, 710);
        double v = Vision.sampleMean(w, box, new Vision.Chan() {
            public double of(int argb) {
                return Math.max((argb >> 16) & 0xFF, Math.max((argb >> 8) & 0xFF, argb & 0xFF));
            }
        });
        return v >= Pf.TH_BRAIN_V;
    }

    /** 战斗开场检查自动战斗 + 3x 速度; 失败只告警, 不影响运行。 */
    void ensureBattleAuto() {
        PfFlow.sleep_(1800);
        try {
            Bitmap b = sh.capture();
            if (b == null) return;
            Vision.Frame w = Vision.toWork(b);
            int spd = battleSpeedLevel(w);
            if (spd == 0 && !brainAutoOn(w)) {
                flow.log("自动战斗未开启, 点脑子");
                flow.tap(b, Pf.BRAIN_BTN[0], Pf.BRAIN_BTN[1]);
                PfFlow.sleep_(600);
                Bitmap b2 = sh.capture();
                if (b2 != null) {
                    w = Vision.toWork(b2);
                    spd = battleSpeedLevel(w);
                }
            }
            if (spd == 0) {
                flow.log(brainAutoOn(w) ? "自动战斗已开但速度泡未识别, 跳过提速"
                        : "速度泡与脑子均未识别 (界面未就绪?), 跳过");
                return;
            }
            if (spd < 3) {
                flow.log("战斗速度 " + spd + "x, 升档到 3x");
                Bitmap bb = sh.capture();
                for (int i = 0; i < 3 - spd; i++) {
                    if (bb != null) flow.tap(bb, Pf.SPD_BTN[0], Pf.SPD_BTN[1]);
                    PfFlow.sleep_(450);
                }
                Bitmap b3 = sh.capture();
                int spd2 = b3 == null ? 0 : battleSpeedLevel(Vision.toWork(b3));
                flow.log(spd2 == 3 ? "速度校验: 3x" : "速度校验: " + (spd2 == 0 ? "未识别" : spd2 + "x"));
            } else {
                flow.log("自动战斗已开, 速度 3x");
            }
        } catch (Exception e) {
            flow.log("自动战斗检查异常 (不影响运行): " + e);
        }
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
    private double greenRatio(Vision.Frame f, int cx, int cy, int hw, int hh) {
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

    /** 同 match, 但**强制全搜** (小模板/召回关键一律走这条, 理由见 PfFlow.funnel 注释)。 */
    private double[] matchFull(Vision.Frame work, String tplName, int[] roi, double th) {
        Vision.Frame t = tpls.get(tplName);
        if (t == null) return null;
        double[] r = Vision.findScored(work, t, roi, th, -1, 0);
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
