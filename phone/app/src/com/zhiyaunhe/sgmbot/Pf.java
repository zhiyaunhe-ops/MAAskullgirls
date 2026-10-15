package com.zhiyaunhe.sgmbot;

/**
 * PF (Prize Fight) 主链的几何/阈值常量与坐标换算。
 *
 * <p>移植自电脑端 tools/pf_bot.py + pf_nav.py + pf_vision.py。常量**保留 PC 原值**
 * (1280x720 基准) 并在此处一次性换算到真机 work (1280x576) —— 这样 Java 代码与 PC
 * 代码可以逐条对照, 排查时不用在两套数字之间来回心算。
 *
 * <h3>为什么能直接换算 (2026-10-15 真机实测)</h3>
 * 游戏按<b>高度</b>等比适配, 16:9 内容在 20:9 屏上<b>横向居中</b>:
 * <pre>
 *   x_work = 128 + x_pc * 0.8      0.8 = 576/720
 *   y_work = y_pc * 0.8            128 = (1280 - 1280*0.8) / 2
 * </pre>
 * 实证: {@code hub_play} ×0.8 真机 0.937 命中、{@code hall_prize_fights} 0.979; 反算命中
 * 中心与 PC {@code ROI_HUB_PLAY} 中心吻合 ±3px。模板同理 ——
 * {@code assets/templates/pf/*.png} 已是 PC 原模板 ×0.8 的产物
 * ({@link TplStore} 硬约束: 模板必须 576 基准且<b>不缩放</b>)。
 *
 * <p>⚠️ 例外: <b>HUD 铺满 20:9 整宽</b>(顶栏返回/房子/金币), 不适用上式 —— 这类元素
 * 一律走模板匹配定位, 不要硬编码坐标。
 *
 * <p>⚠️ ROI 格式: PC 是 {@code (x0,y0,x1,y1)}, {@link Vision#find} 要的是
 * {@code [x,y,w,h]} —— 一律用 {@link #R} 转换, 别手抄。
 */
public final class Pf {
    private Pf() { }

    // ---------------- 基准与换算 ----------------

    public static final int WORK_W = 1280;
    public static final int WORK_H = Vision.WORK_H;          // 576
    /** 真机 work 高度 / PC 基准高度。 */
    public static final double K = WORK_H / 720.0;           // 0.8
    /** 16:9 内容在 20:9 屏上横向居中的左偏移。 */
    public static final int OFF_X = Math.round(WORK_W * (1 - (float) K) / 2f);   // 128

    /** PC x → work x。 */
    public static int X(double xPc) { return OFF_X + (int) Math.round(xPc * K); }
    /** PC y → work y。 */
    public static int Y(double yPc) { return (int) Math.round(yPc * K); }
    /** PC 长度 → work 长度。 */
    public static int S(double v) { return (int) Math.round(v * K); }

    /** PC (x0,y0,x1,y1) → work {@code [x,y,w,h]}。 */
    public static int[] R(int x0, int y0, int x1, int y1) {
        return new int[]{X(x0), Y(y0), S(x1 - x0), S(y1 - y0)};
    }

    /** PC 点 → work 点 {@code [x,y]} (float, 供 tapWork 用)。 */
    public static float[] P(int xPc, int yPc) { return new float[]{X(xPc), Y(yPc)}; }

    /** PC 点 → work 点 {@code [x,y]} (int)。 */
    public static int[] PI(int xPc, int yPc) { return new int[]{X(xPc), Y(yPc)}; }

    // ---------------- 模板名 (assets/templates/pf/) ----------------
    // 全部由 assets/resource/base/image/pf/*.png 等比 ×0.8 生成, 见 TplStore 注释。

    public static final String T_FIGHT        = "pf/vs_fight_btn";      // VS/地图页右上 FIGHT!
    public static final String T_FILTER_X     = "pf/pf_filter_x";       // 筛选面板关闭 X
    public static final String T_DRAGHINT     = "pf/drag_hint";         // 编队页 DRAG 提示
    public static final String T_DEF_CONFIRM  = "pf/defense_confirm";   // 防守队编辑器右上 CONFIRM
    public static final String T_ENERGY_X     = "pf/energy_x";          // 能量不足弹窗 X
    public static final String T_REFRESH      = "pf/pf_refresh_btn";    // 选对手页 REFRESH
    public static final String T_STREAK_X     = "pf/streak_x";          // 连胜弹窗 X
    public static final String T_DETAIL       = "pf/detail_stats";      // 误入角色详情页
    public static final String T_OPTIONS_X    = "pf/options_x";         // OPTIONS 弹窗 X
    public static final String T_SRV_OK       = "pf/srv_ok";            // 服务器错误 OK
    public static final String T_SRV_RETRY    = "pf/srv_retry";         // 服务器错误 RETRY
    public static final String T_SPD_1X       = "pf/battle_spd_1x";
    public static final String T_SPD_2X       = "pf/battle_spd_2x";
    public static final String T_SPD_3X       = "pf/battle_spd_3x";
    public static final String T_HUB_PLAY     = "pf/hub_play";          // PF hub 居中卡 PLAY!
    public static final String T_HALL_PRIZE   = "pf/hall_prize_fights"; // 大厅 PRIZE FIGHTS 菱形
    public static final String T_SCENE_X      = "pf/scene_popup_x";     // 大厅促销弹窗 X
    public static final String T_MODAL_X      = "pf/popup_close_x";     // 弹窗通用方形 X
    public static final String T_CONTINUE     = "pf/pf_continue_btn";   // 结算 CONTINUE (残局脱离)
    public static final String T_RESULT_CONT  = "pf/result_continue";   // VICTORY 结算页底部 CONTINUE
    public static final String T_HOME         = "home";                 // 顶栏房子 (HUD: 走匹配不硬编码)
    /**
     * 「缺 Moves / Guest Star」无 X 弹窗里的 FIGHT! 按钮。
     *
     * <p>2026-10-15 真机首次遇到（PC 代码里没有这个判据）：正文问
     * "At least one of your Fighters is missing Moves or a Guest Star! ... FIGHT anyway
     * or EQUIP..."，两按钮 EQUIP(紫) / FIGHT!(橙) 在**画面中央**。
     * 它挡在编队页之上，而编队页**右上**也有一个 FIGHT! ⇒ 两个同字不同位的小部件，
     * 实测互不混淆（本模板在右上区只有 0.295，vs_fight_btn 在中央 0.782）。
     */
    public static final String T_MOVES_FIGHT  = "pf/moves_fight";

    // ---------------- ROI (PC 基准, 经 R() 换算) ----------------
    // 用 [x0,y0,x1,y1] 写, 与实际调用处的 Vision 口径无关。

    /**
     * 弹窗右上 X 区域。
     *
     * <p>⚠️ PC 的 {@code (860,160,1060,340)} 套公式只到 work x=976/y=272, **够不着真机的 X**
     * （2026-10-15 实测: OPTIONS 的关闭 X 在 work (1075,27)、PROFILE 的在 (1223,32)）。
     * 真机 HUD 铺满 20:9，**X 的位置随弹窗宽度浮动** ⇒ 只能靠模板匹配定位, ROI 必须
     * 一直覆盖到屏幕右缘 (第一版放宽到 x=1180 仍然漏了 PROFILE 那个)。
     */
    public static final int[] ROI_POPUP      = {840, 0, 440, 320};
    /** 结算页底部 CONTINUE 行。 */
    public static final int[] ROI_RESULT     = R(400, 570, 980, 700);
    /**
     * 右上按钮通用区 (FIGHT! / CONTINUE)。
     *
     * <p>⚠️ <b>不能套居中换算</b> —— 这是 HUD 元素, 铺满 20:9 整宽, 比内容区更靠右。
     * 真机实测 FIGHT! 在 work (1116,30)-(1240,73); 而公式 {@code R(1040,15,1260,115)}
     * 只覆盖到 x=1136 ⇒ 模板 (124 宽) 在 ROI 内**放不下**, 直接漏检。
     * 2026-10-15 真机现场: 点完 PLAY 进了编队页却一直判"未知界面", 在编队页上按返回键。
     * 这里用实测值 (左右各留余量)。
     */
    public static final int[] ROI_TOPRIGHT = {1080, 0, 200, 130};

    /** 画面中央 (「缺 Moves/Guest Star」弹窗的 EQUIP/FIGHT! 行所在)。 */
    public static final int[] ROI_CENTER_FIGHT = {600, 360, 300, 120};

    /**
     * 顶栏"房子"(回大厅)的搜索区 —— ⚠️ **必须排除左上角的齿轮**。
     *
     * <p>2026-10-15 实栽: 真机**大厅顶栏没有房子, 那个位置是齿轮(设置)**; 而 PF hub 里
     * 才有房子。两者相邻、都是"圆形金环包一个图形", `home.png` 以 0.723 稳稳匹配到齿轮
     * ⇒ gotoHub 每轮点它打开 OPTIONS, 下一轮再点 X 关掉, **关了又开自激循环**
     * (现场日志: 每 3 秒交替 `work(1078,29)`/`work(32,33)`, 永不前进)。
     * 齿轮实测 work x 11~45, 房子 x 64~92 ⇒ 搜索区从 x=50 起即可分离。
     */
    public static final int[] ROI_HOME = {50, 0, 110, 62};
    /** PF hub 居中卡 PLAY! */
    public static final int[] ROI_HUB_PLAY   = R(500, 400, 780, 530);
    /** 大厅促销弹窗右上 X。 */
    public static final int[] ROI_SCENE_X    = R(880, 30, 1240, 180);
    /** 模态弹窗方形 X (上限 1060 挡 OPTIONS X)。 */
    public static final int[] ROI_MODAL_X    = R(700, 0, 1060, 300);
    /** 选对手页 REFRESH。 */
    public static final int[] ROI_REFRESH    = R(700, 15, 960, 115);
    /** 编队页 DRAG 提示行。 */
    public static final int[] ROI_DRAGHINT   = R(430, 405, 850, 465);
    /** 居中卡 "SCORE: n"。 */
    public static final int[] ROI_CARD_SCORE = R(505, 138, 775, 185);
    /** 居中卡场地名 (可两行)。 */
    public static final int[] ROI_CARD_TITLE = R(500, 290, 780, 378);
    /** 标题兜底 ROI (版式不统一, 标题高度不固定)。 */
    public static final int[] ROI_TITLE_FB   = R(520, 255, 780, 310);
    /** 筛选面板关闭 X 搜索区。 */
    public static final int[] ROI_FILTER_X   = R(1150, 30, 1280, 140);
    /** 战斗速度泡 (battle_speed_level)。 */
    public static final int[] ROI_SPD        = R(600, 565, 690, 645);
    /** 结算页 CONTINUE (handle_results / wait_battle_end)。 */
    public static final int[] ROI_RESULT_C   = R(400, 570, 980, 700);
    /** 全屏 (等价 PC 的 (0,0,0,0))。 */
    public static final int[] ROI_FULL       = new int[]{0, 0, WORK_W, WORK_H};

    // ---------------- 阈值 ----------------

    public static final double TH_HUB_PLAY   = 0.70;
    public static final double TH_HALL_PRIZE = 0.72;
    public static final double TH_REFRESH    = 0.70;
    public static final double TH_DRAGHINT   = 0.60;
    public static final double TH_FIGHT      = 0.70;
    public static final double TH_RESULT_C   = 0.70;
    public static final double TH_CONTINUE   = 0.70;
    public static final double TH_FILTER_X   = 0.70;
    public static final double TH_SPD        = 0.85;   // 同位实测 ≥0.95, 串扰 ≤0.76, 无泡 ≤0.14
    public static final double TH_BRAIN_V    = 75.0;   // 脑子图标亮 V 均值 (亮~101 / 灭~49)
    public static final double TH_RULE_LIT   = 0.15;   // FIGHT 按钮高饱和占比 (橙~55% / 灰~0%)
    public static final double TH_FIRE_BOX   = 0.04;   // 对手卡右上火框红占比 (火 0.17-0.26 / 无 0.03)
    public static final double MIN_SIM       = 0.72;   // 大字/按钮通用相似度

    // ---------------- 点击坐标 (PC 基准, 经 PI() 换算) ----------------
    // ⚠️ HUD 类 (顶栏返回/房子/右上 X) 铺满 20:9 整宽, **不套换算**, 用真机实测值。
    //    内容区类 (编队槽位/筛选面板/对手卡) 可以套 —— 见下面分组。

    /** 编队页入口 TEAM 菱形 (内容区, 可套换算)。 */
    public static final int[] TEAM_DIAMOND  = PI(637, 555);

    /**
     * 未知界面的兜底返回 —— ⚠️ **真机上 (25,26) 是"设置齿轮"不是返回键**
     * (2026-10-15 实栽: 点它打开 OPTIONS 菜单, 之后永远出不去, gotoHub 空转到 300s 超时)。
     * 优先走系统返回键 {@code input keyevent 4}，见 {@code PfFlow} 未知界面分支。
     */
    public static final int[] BACK_BTN      = {25, 26};
    /** 顶栏房子 (仅作兜底; 优先用 {@link #T_HOME} 模板定位)。HUD 实测 work ≈ (78,26)。 */
    public static final int[] HOME_BTN      = {78, 26};
    /** 未知界面右上关闭 X (HUD 实测 work ≈ (1255,26), 待真机复核)。 */
    public static final int[] CLOSE_X       = {1255, 26};
    /** 战斗速度泡 (点一下升一档)。 */
    public static final int[] SPD_BTN       = PI(640, 605);
    /** 自动战斗脑子开关。 */
    public static final int[] BRAIN_BTN     = PI(640, 690);

    /** 筛选面板: 打开按钮 / 清空 / 关闭 / 喜爱(爱心)。 */
    public static final int[] FILTER_BTN    = PI(1215, 395);
    public static final int[] FILTER_CLEAR  = PI(272, 126);
    public static final int[] FILTER_CLOSE  = PI(1228, 85);
    public static final int[] FILTER_HEART  = PI(616, 237);

    /** 筛选面板列中心 x (6 列, PC 值)。 */
    private static final int[] FILTER_COLS_PC = {197, 302, 407, 512, 616, 721};

    /** 难度锁定弹窗 CONTINUE (无 X 的 CANCEL/CONTINUE)。 */
    public static final int[] LOCK_CONTINUE = PI(748, 576);
    /** 「Save changes to your Team?」YES (绿, NO 在 527 —— 绝不能点)。 */
    public static final int[] SAVE_TEAM_YES = PI(751, 441);
    /** 防守队说明弹窗 OK (绿按钮心)。 */
    public static final int[] DEFENSE_OK    = PI(751, 541);

    /** 防守队编辑器: 三槽落点 (y≈190) / 候选列拖拽源 (整排同角色, 取正中即可)。 */
    public static final int[][] DEF_SLOT_DROP = {
            PI(440, 190), PI(640, 190), PI(840, 190)};
    public static final int[] DEF_DROP_SRC = PI(640, 555);

    /** 出战队拖拽落点 y (drag_card 的 dy)。 */
    public static final int DRAG_DY = Y(240);

    // ---------------- 能量与对手 (pf_vision) ----------------

    /** 出战一场每个槽位角色需要的黄钉数。 */
    public static final int ENERGY_COST = 4;

    /** 三个出战槽: 钉条中心 x / 钉条行带 / 窗口半宽。 */
    public static final int[] SLOT_PIP_CX  = {X(132), X(324), X(516)};
    public static final int[] SLOT_BAND    = {Y(349), Y(369)};
    public static final int   SLOT_HALF    = S(75);
    /** 拖拽落点 x (卡面铭牌中心)。 */
    public static final int[] SLOT_DROP_X  = {X(130), X(355), X(557)};

    /** 六个候选位: 中心 (卡距 198, 首卡 x≈116, y=565) / 钉条行带 / 半宽。 */
    public static final int[] ROSTER_BAND  = {Y(679), Y(701)};
    public static final int   ROSTER_HALF  = S(66);
    public static final int[][] ROSTER_CENTERS = new int[6][];
    static {
        for (int i = 0; i < 6; i++) ROSTER_CENTERS[i] = new int[]{X(116 + 198 * i), Y(565)};
    }

    /** 对手三卡: click 点 / 战力 ROI / 火框固定 ROI / 火框动态搜索窗。 */
    public static final int[][] OPP_CLICK   = {PI(1000, 235), PI(1000, 412), PI(1000, 590)};
    public static final int[][] OPP_POWER   = {R(800, 166, 884, 198), R(775, 342, 860, 374),
                                               R(798, 514, 900, 554)};
    public static final int[][] OPP_FIRE    = {R(1136, 156, 1206, 222), R(1136, 332, 1206, 398),
                                               R(1136, 508, 1206, 574)};
    public static final int[][] FIRE_SEARCH = {R(1100, 138, 1216, 215), R(1100, 318, 1216, 400),
                                               R(1100, 493, 1216, 570)};

    /** 选对手页左侧总分 / 连胜层数。 */
    public static final int[] ROI_SCORE  = R(132, 336, 275, 370);
    public static final int[] ROI_STREAK = R(280, 200, 352, 238);

    /** 黄色闪电钉掩码 (严格高亮黄: 金卡淡黄底 (231,231,132) 不通过, 钉芯 (255,219,66) 通过)。 */
    public static boolean isBolt(int argb) {
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        return r >= 240 && g >= 200 && b <= 105;
    }

    /** 火框红色掩码 (含橙红)。 */
    public static boolean isRed(int argb) {
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        return r >= 150 && (r - g) >= 60 && (r - b) >= 60;
    }

    /** 元素芯片坐标 (fire/water/wind/light/dark/neutral), 筛选面板第 2 行。 */
    public static int[] elementChip(String element) {
        String[] names = {"fire", "water", "wind", "light", "dark", "neutral"};
        for (int i = 0; i < names.length; i++) {
            if (names[i].equals(element)) return PI(FILTER_COLS_PC[i], 370);
        }
        return null;
    }

    /**
     * 角色芯片坐标 (筛选面板第 3/4 行, 12 格)。
     *
     * <p>⚠️ 只覆盖 12/18 个角色 (缺 Parasoul/Peacock/Valentine/Squigly/Robo-Fortune/Umbrella)
     * —— 这几类角色场的防守约束在 PC 端也是**静默跳过**, 保持同口径别硬凑。
     */
    public static int[] classChip(String character) {
        String[] names = {"Annie", "Beowulf", "Big Band", "Black Dahlia", "Cerebella",
                          "Double", "Eliza", "Filia", "Fukua", "Marie", "Ms. Fortune",
                          "Painwheel"};
        for (int i = 0; i < names.length; i++) {
            if (names[i].equalsIgnoreCase(character)) {
                return PI(FILTER_COLS_PC[i % 6], i < 6 ? 505 : 594);
            }
        }
        return null;
    }

    /** 元素键白名单 (防脏值)。 */
    public static boolean validElement(String e) {
        return "fire".equals(e) || "water".equals(e) || "wind".equals(e)
                || "light".equals(e) || "dark".equals(e) || "neutral".equals(e);
    }
}
