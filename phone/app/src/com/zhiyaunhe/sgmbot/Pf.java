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
    public static final String T_FILTER_X     = "pf/pf_filter_x";       // 筛选面板关闭 X (蓝底金叉)
    /** 编队编辑器右下角的筛选漏斗 —— 同时是"我在编辑器而不是编队预览页"的判据。 */
    public static final String T_FILTER_BTN   = "pf/filter_btn";
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
    /**
     * 大厅「PRIZE FIGHTS」**文字条**模板 (2026-10-18, 用户: 「首页是可以拉动的, 你得要么
     * 拉最左边, 然后 ocr 读 prize fighter 才行」)。
     *
     * <p>⚠️ 为什么不能再用整块菱形 ({@link #T_HALL_PRIZE}): ① 菱形里带**倒计时数字**
     * (23:49:07) 每几秒就变; ② 大厅三个菱形 (PRIZE FIGHTS / STORY / EVENTS) 版式几乎一样,
     * 整块模板会互相抢分 —— 实测本次就匹配到了 STORY 那片, 点下去落在 GUILDS 上。
     * 只取文字条后区分度是 1.00 vs 0.20 / 0.14 (同一帧实测), 等价于"读文字"。</p>
     */
    public static final String T_HALL_PRIZE_TEXT = "pf/hall_prize_text";
    /**
     * 大厅「PRIZE FIGHTS」横幅的点击点 —— **相对文字条中心**的偏移 (work 坐标)。
     *
     * <p>2026-10-18 实测标定 (K70 Pro / work 1280x576 / 大厅拉到最左):
     * 文字条命中框 (2,380,148,36) → 中心 (76,398); 横幅 (金腰带 + 摔跤手立绘) 的可点区域中心
     * ≈ (65,320) ⇒ 偏移 **(-11,-78)**。按此点下去 `hub_play` 从 0.19 → 0.95, 成功进 hub。</p>
     *
     * <p>⚠️ 用**相对偏移**而不是绝对坐标: 横幅在**可拖动地图**上, 每次位置都不一样; 文字条一旦
     * 命中, 点击点就跟着走, 不需要"先拉最左"这个前提。⚠️ 别改成"文字上方 35px"那种没标定过
     * 的魔数 —— 会落在横幅边缘外, 表现为"点了没反应"。</p>
     */
    public static final int HALL_PRIZE_DX = -11, HALL_PRIZE_DY = -78;

    /**
     * 大厅「STORY」**文字**模板 —— {@link #inHall} 的判据之一。
     *
     * <p>⚠️⚠️ 2026-10-18 实栽: 原判据 (整块 PRIZE FIGHTS / EVENTS 菱形) 里带**倒计时数字**
     * (23:49:07), 每几秒就变 ⇒ 同一画面只有 **0.25**, 认不出大厅 ⇒ gotoHub 永远停在
     * "未知界面", `step` 卡死"回到 PF hub" (用户报的就是"导航不进去")。
     * STORY 是居中大字、无倒计时、不随地图横向拖动消失 —— 实测大厅 **1.000** / hub 0.358。</p>
     */
    public static final String T_HALL_STORY_TEXT = "pf/hall_story_text";

    /**
     * 大厅 EVENTS 菱形 —— 大厅的**第二判据**。
     *
     * <p>⚠️ 2026-10-15 真机实栽: 只用 {@code hall_prize_fights} 判大厅会翻车。
     * 本 App 自己的悬浮条被拖到画面左侧后会**压住 PRIZE FIGHTS 节点**, 模板从 0.98 掉到 0.55
     * ⇒ 判不出大厅 ⇒ 按返回键 ⇒ 弹出 QUIT GAME? ⇒ 死锁。
     * EVENTS 在画面右半边, 悬浮条盖不到; 实测大厅 0.87~1.00、其它界面 ≤0.42, 分离度足够。
     */
    public static final String T_HALL_EVENTS  = "hall_events";
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

    /**
     * 「QUIT GAME?」确认弹窗的橙色标题栏。
     *
     * <p>2026-10-15 真机实栽: bot 的**系统返回键**把已经到大堂的画面顶出了这个弹窗
     * （"Are you sure you want to quit the game?" QUIT / CANCEL），画面随即被压暗 ⇒
     * 大堂模板失配 ⇒ bot 判"未知界面" ⇒ 再按返回 ⇒ 自我死锁。
     * 它**没有 X**，只能认正文/标题栏再点 CANCEL。裁自真机现场帧，
     * 自匹配 1.0、其它画面 ≤0.26 ⇒ 阈值 0.85 很安全。
     */
    public static final String T_QUIT         = "pf/quit_title";

    /** CANCEL 按钮相对标题栏中心的偏移 (work 像素; 由真机现场帧量出)。 */
    public static final int[] QUIT_CANCEL_OFF = {90, 185};

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
    /**
     * 选对手页 REFRESH。
     *
     * <p>⚠️⚠️ 2026-10-16 实栽 —— **这是"选队逻辑从未生效"的总根因**。旧值
     * {@code R(700,15,960,115)} 展开是 <b>{688,12,208,80}</b>, 而 REFRESH 模板 (164x53)
     * 真机实测命中在 <b>(844,26)</b> ⇒ 844+164=1008 &gt; 688+208=896, **模板在 ROI 里放不下**
     * ⇒ {@code PfScene} 的 OPPONENT 判据永远落空 ⇒ **选对手页一直被判 UNKNOWN**:
     * 结算 CONTINUE 后游戏明明回到选对手页, bot 却按返回键退到编队页、
     * 对着同一个待战对手反复打, 倍率比较根本没机会跑 (现场: 同一对手连打 6 场)。
     * 与 {@link #ROI_FILTER_X} 同类错误 (ROI 比模板窄, 静默漏检), 且同属
     * **右缘锚定**元素 —— 套 PC 居中换算必偏, 一律实测。
     */
    public static final int[] ROI_REFRESH = {820, 0, 280, 110};
    /** 编队页 DRAG 提示行。 */
    public static final int[] ROI_DRAGHINT   = R(430, 405, 850, 465);
    /** 居中卡 "SCORE: n"。 */
    public static final int[] ROI_CARD_SCORE = R(505, 138, 775, 185);
    /** 居中卡场地名 (可两行)。 */
    public static final int[] ROI_CARD_TITLE = R(500, 290, 780, 378);
    /** 标题兜底 ROI (版式不统一, 标题高度不固定)。 */
    public static final int[] ROI_TITLE_FB   = R(520, 255, 780, 310);
    /** 筛选面板关闭 X 搜索区。 */
    /**
     * 筛选面板关闭 ✕ 的搜索区。
     *
     * <p>⚠️⚠️ 2026-10-16 实栽 —— **这是"筛选一直打不开"的真凶**。
     * 旧值 {@code R(1150,30,1280,140)} 展开是 <b>{1048,24,104,88}</b>, 右边界 x=1152;
     * 而 ✕ 模板(51x51) 真机落在 <b>work 1112..1163</b> ⇒ **模板右缘超出 ROI 11px,
     * 匹配器直接找不到** ⇒ {@code panelOpen()} 永远 false ⇒ 上层判"面板没开"
     * ⇒ 明明点开了也当没开, 最终放弃筛选。
     * 排查时先后怀疑过 坐标 / 漏斗模板 / 手势类型 / 等待时长, 全是冤枉的 ——
     * **ROI 比模板还窄**这种错不会报错, 只会静默返回"没找到"。
     * 教训: 定 ROI 时先算一遍 `x0 + 模板宽 <= x0 + w`, 不过就写宽一点。
     */
    public static final int[] ROI_FILTER_X   = {1030, 12, 150, 110};

    /**
     * 筛选漏斗的搜索区。
     *
     * <p>⚠️ 漏斗**锚在 20:9 屏幕右缘**, 不适用居中换算 (PC 的 {@code PI(1215,395)} 给
     * work x=1100, 实测 1240 —— 差 140px)。所以这里既不硬编码坐标也不用换算,
     * 而是**模板定位**: 真机实测编辑器内 1.00 / 关掉筛选面板后 0.88 / 编队预览页 0.05。
     *
     * <p>⚠️ 且 ROI 必须给宽 —— 2026-10-15 实测**同一台机器不同场次漏斗的 y 不一样**
     * (第一局在 work y≈315, 另一局在别的行), 窄 ROI 直接 0.15 分漏检。
     * 漏斗是黄色漏斗形图标, 全右缘搜索 (x1120-1280) 不会误报, 代价只是 ~0.1s 全搜。
     */
    public static final int[] ROI_FILTER_BTN = {1120, 100, 160, 440};
    /** 战斗速度泡 (battle_speed_level)。 */
    /**
     * 战斗速度泡 ROI。
     *
     * <p>⚠️⚠️ 2026-10-16 实栽: 旧值 {@code R(600,565,690,645)} 换算成 work x480~570,
     * 而真机速度泡在 **work(616,454)** (56x56) ⇒ **整个 ROI 都够不着它**, 于是日志长期
     * 报「自动战斗已开但速度泡未识别」—— 不是模板不像, 是压根没在搜的地方搜。
     * 顺带把 {@code battle_spd_3x} 模板改裁真机帧 (自匹配 1.000 / 非战斗帧 ≤0.300;
     * PC 裁的那个只有 0.732, 贴着阈值, 一有抖动就丢)。
     */
    public static final int[] ROI_SPD        = {590, 430, 130, 90};
    /** 结算页 CONTINUE (handle_results / wait_battle_end)。 */
    public static final int[] ROI_RESULT_C   = R(400, 570, 980, 700);
    /** 全屏 (等价 PC 的 (0,0,0,0))。 */
    public static final int[] ROI_FULL       = new int[]{0, 0, WORK_W, WORK_H};

    // ---------------- 阈值 ----------------

    public static final double TH_HUB_PLAY   = 0.70;
    public static final double TH_HALL_PRIZE = 0.72;
    /** 文字条阈值: 正例 1.00, 异菱形 ≤0.20 ⇒ 0.70 留足余量。 */
    public static final double TH_HALL_PRIZE_TEXT = 0.70;
    /** 大厅文字判据阈值 (正例 1.00 / hub 0.36)。 */
    public static final double TH_HALL_STORY_TEXT = 0.70;
    /** 大厅通用判据阈值 (EVENTS 等) —— 实测大厅 ≥0.87、其它界面 ≤0.42。 */
    public static final double TH_HALL        = 0.72;
    public static final double TH_REFRESH    = 0.70;
    public static final double TH_DRAGHINT   = 0.60;
    public static final double TH_FIGHT      = 0.70;
    public static final double TH_RESULT_C   = 0.70;
    public static final double TH_CONTINUE   = 0.70;
    public static final double TH_FILTER_X   = 0.70;
    /** 筛选漏斗 (编辑器 1.00 / 编队预览页 0.05, 分离极大)。 */
    public static final double TH_FILTER_BTN = 0.70;
    /**
     * 速度泡**存在性**阈值 —— 取到之后再用 {@code argmax} 定档位。
     *
     * <p>⚠️⚠️ 2026-10-18 实栽 (用户: 「自动的 *3 没点上, 都只有 *1」): 旧值 **0.85 永远过不了**。
     * 6 张真机战斗帧的灰度 NCC 实测:</p>
     * <pre>
     *   1x 帧: 1x = 0.805 / 0.818 / 0.818, 2x/3x 串扰 ≤0.52
     *   3x 帧: 3x = 0.727 / 0.745 / 0.733, 1x 串扰 ≤0.608
     *   非战斗帧: ≤0.14
     * </pre>
     * <p>⇒ 最高分也才 0.82, 卡在 0.85 下面 ⇒ {@code battleSpeedLevel()} 恒返回 0 ⇒
     * 日志一直是「自动战斗已开但速度泡未识别, 跳过提速」(设备日志 20 次里 16 次), 速度永远 1x。
     * 现口径: 0.62 过存在性, 再在三个模板里**取最高分**定档 (1x 帧领先 0.29, 3x 帧领先 0.12,
     * 都有充足余量)。⚠️ 别再退回"单片绝对阈值"的写法 —— 三片模板互相只差 0.12。</p>
     */
    public static final double TH_SPD        = 0.62;
    /** 定档位时第一名至少要比第二名高这么多 (实测 3x 帧余量 0.12 起)。 */
    public static final double SPD_LEAD      = 0.04;
    /**
     * 战斗开场等速度泡出现的最长时间。
     * ⚠️ 原来是"打完 FIGHT 固定等 1.8s 再看一眼" —— VS 动画/加载还没结束, HUD 没渲染,
     * 一眼扑空就再也不试 (旧代码还有一次性 latch), 于是整场 1x。
     */
    public static final long SPD_WAIT_MS     = 18_000L;
    public static final double TH_BRAIN_V    = 75.0;   // 脑子图标亮 V 均值 (亮~101 / 灭~49)
    public static final double TH_RULE_LIT   = 0.15;   // FIGHT 按钮高饱和占比 (橙~55% / 灰~0%)
    public static final double TH_FIRE_BOX   = 0.04;   // 对手卡右上火框红占比 (火 0.17-0.26 / 无 0.03)
    public static final double MIN_SIM       = 0.72;   // 大字/按钮通用相似度

    // ---------------- 点击坐标 (PC 基准, 经 PI() 换算) ----------------
    // ⚠️ HUD 类 (顶栏返回/房子/右上 X) 铺满 20:9 整宽, **不套换算**, 用真机实测值。
    //    内容区类 (编队槽位/筛选面板/对手卡) 可以套 —— 见下面分组。

    /** 编队页入口 TEAM 菱形 (内容区, 可套换算)。 */
    /**
     * TEAM 菱形 —— PC 换算的**兜底坐标**。
     *
     * <p>⚠️⚠️ 2026-10-16 实栽: 这个菱形的**位置随场次变**。用固定坐标点它, 在新场次会落到
     * 旁边角色头像上 ⇒ 打开角色详情页 ⇒ 「点 TEAM 菱形 → 误入角色详情页」来回循环。
     * 现在改成**模板定位** {@link #T_TEAM_DIAMOND} 优先, 这里只作模板也没命中时的兜底。
     */
    public static final int[] TEAM_DIAMOND  = PI(637, 555);

    /** TEAM 菱形模板 (真机裁, 自匹配 1.000 / 其它界面 ≤0.234)。 */
    public static final String T_TEAM_DIAMOND = "pf/team_diamond";
    /**
     * TEAM 菱形阈值。
     *
     * <p>⚠️ 0.62 而不是 0.70: 从选对手页点 CONTINUE 进来时预览页是**淡入**的, bot 抓到的
     * 那一帧常常是半透明的过渡帧, 实测同一位置从 0.79 掉到 0.6x ⇒ 0.70 判"没命中"
     * (日志连续 3 次「菱形模板没命中」)。反例上限只有 **0.234**, 掉到 0.62 仍留一倍余量。
     */
    public static final double TH_TEAM_DIAMOND = 0.62;
    /**
     * TEAM 菱形的搜索区。⚠️ 定 ROI 的铁律: **x0+模板宽 &lt;= x0+w 且 y0+模板高 &lt;= y0+h**
     * (2026-10-16 踩过 ROI 比模板还窄的坑, 匹配器不报错、只静默返回"没找到")。
     * 模板 96x80 ⇒ 这里 340x220 绰绰有余。
     */
    public static final int[] ROI_TEAM_DIAMOND = {400, 340, 340, 220};

    /**
     * 大厅 PRIZE FIGHTS 的**兜底**点击点 (work 坐标) —— 只在文字条也读不到时才用。
     *
     * <p>2026-10-18 重新标定: 大厅拉到最左时横幅可点中心 ≈ **(65,320)**。
     * 旧值 (240,310) 已作废 —— 它其实是 **GUILDS 菱形**的位置: 实测连点 21 次全部无效,
     * gotoHub 原地空转到 300s 超时 (日志 "在大厅, 点 PRIZE FIGHTS" ×21)。
     * ⚠️ 该结论推翻了这里原来"已被一次真实成功点击验证"的注释。</p>
     */
    public static final int[] HALL_PRIZE_TAP = {65, 320};

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
    /**
     * 战斗速度泡 (点一下升一档) —— **兜底值**, 正常走模板定位 (见 PfTeam.speedTapPoint)。
     *
     * <p>PI(640,605) → work **(640,484)**。2026-10-18 在真机帧 wt3.png 上用细网格复核:
     * 速度泡实体 work x610~690 / y442~512、圆心 ≈ (650,477) ⇒ 这个兜底点确实落在泡内,
     * **坐标没问题**, 当初显示不出来是阈值问题 (见 {@link #TH_SPD})。</p>
     */
    public static final int[] SPD_BTN       = PI(640, 605);
    /**
     * 自动战斗脑子开关。PI(640,690) → work **(640,552)**。
     *
     * <p>真机帧实测: 速度泡正下方那颗粉色脑子 x600~710 / y515~576(截到帧底),
     * 亮灯区约 (624,536)-(656,568) ⇒ 兜底点落在脑子上。</p>
     */
    public static final int[] BRAIN_BTN     = PI(640, 690);
    /**
     * 脑子亮灯区的取样框 (work) —— PI(620,670)-(660,710) ⇒ **(624,536,32,32)**。
     *
     * <p>2026-10-18 复核: 速度泡正下方的粉色脑子在 work x600~710 / y515~576(截到帧底),
     * 亮灯核心就是这块。判据 = 该框内 max(R,G,B) 均值 ≥ {@link #TH_BRAIN_V}。</p>
     */
    public static final int[] BRAIN_BOX     = R(620, 670, 660, 710);

    /**
     * 筛选面板的四个固定点。
     *
     * <p>⚠️⚠️ 2026-10-15 真机逐项实测 (此前**整组是坏的**, 所以"选角色根本没加筛选" ——
     * 面板压根没被打开过, setFilter 静默失败, 于是拖人完全无筛选):
     * <ul>
     *   <li>{@link #FILTER_BTN} —— 漏斗图标锚在 20:9 屏幕的**右缘**, 不适用居中换算:
     *       PC {@code PI(1215,395)} 给 work(1100,316), 实测在 <b>work(1237,312)</b>, x 偏 137px
     *       (早先的"点完 PLAY 判未知界面"同款坑)。</li>
     *   <li>{@link #FILTER_CLEAR} —— ⊘ **只在已有筛选生效时才出现**, 实测 work(325,82)。</li>
     *   <li>{@link #FILTER_CLOSE} / {@link #FILTER_HEART} —— 面板自身是**居中内容面板**,
     *       PC 的 {@code PI()} 换算成立: 爱心实测果然在 work(621,190) 生效
     *       (点它之后面板左上 ⊘ 出现 = 筛选被激活)。</li>
     * </ul>
     */
    public static final int[] FILTER_BTN    = {1240, 315};   // 仅作模板未命中的兜底
    public static final int[] FILTER_CLEAR  = {325, 82};
    /** ✕ 是"蓝底金叉", 实测 work(1138,50); PC PI(1228,85) 给 (1110,68), 偏 28px 点不动。 */
    public static final int[] FILTER_CLOSE  = {1138, 50};

    /**
     * 筛选面板的图标网格 —— 真机实测 (2026-10-15)。
     *
     * <p>⚠️ **不是** PC 的 {@code PI()} 换算: PC {@code FILTER_COLS=[197,302,…]} → work
     * {286,370,454,…}, 而实测列心是 {263,345,427,509,591,673} (间距 82, 比 PC 小 2px/列,
     * 首列整体偏左 23px)。行心 {178, 297, 415, 500}。
     *
     * <p>每行 6 格, 顺序 (真机目视 + 逐格点测核对):
     * <ol>
     *   <li>y=178 稀有度/属性: 铜牌·银牌·金牌·粉钻·**红心(喜爱)**·白星</li>
     *   <li>y=297 元素: 火·水·风·光·暗·中性</li>
     *   <li>y=415 角色 1~6</li>
     *   <li>y=500 角色 7~12</li>
     * </ol>
     * ⚠️ 第 1 行**红心在第 5 格、白星在第 6 格** —— 差一格就会"想筛喜爱、结果按成了星星"。
     * (2026-10-15 实栽: 就是这么点错的。)
     */
    public static final int[] FILTER_COL_X = {263, 345, 427, 509, 591, 673};
    public static final int[] FILTER_ROW_Y = {178, 297, 415, 500};

    /** 喜爱芯片 —— 第 1 行**第 5 格**的红色爱心 (第 6 格是白色星星, 别点错)。 */
    public static final int[] FILTER_HEART  = {FILTER_COL_X[4], FILTER_ROW_Y[0]};

    /** 难度锁定弹窗 CONTINUE (无 X 的 CANCEL/CONTINUE)。 */
    public static final int[] LOCK_CONTINUE = PI(748, 576);
    /** 「Save changes to your Team?」YES (绿, NO 在 527 —— 绝不能点)。 */
    public static final int[] SAVE_TEAM_YES = PI(751, 441);

    /**
     * 「Save changes to your Team?」**NO / 取消** —— 用户 2026-10-16 明确要求:
     * 「第一次进来需要点取消」。坐标来自 PC 源码注释 (NO 在 PC x=527, 与 YES 同一行 y=441)。
     *
     * <p>⚠️ 这个弹窗**无 X**, 挡在编队页前面, 不点掉就进不去 —— 而手机端原先的
     * {@code confirmSaveTeam()} 是个 `return false` 的空壳, 等于完全没处理。
     */
    public static final int[] SAVE_TEAM_NO = PI(527, 441);

    /** 存档确认弹窗的正文带 (PC: 400,305 → 870,375)。判据只在带内取颜色, 不做全文搜索。 */
    public static final int[] ROI_SAVE_TEAM = R(400, 305, 870, 375);
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

    /**
     * 能量门槛的上限 (自适应封顶)。
     *
     * <p>各场次每场消耗: **character 5 钉** / element·misc·monthly 4 钉 (见项目 MEMORY 的
     * 场次条件表) ⇒ 5 就是全局最大值。自适应只在这个区间里升, 不会越调越离谱。
     */
    public static final int ENERGY_MAX_COST = 5;

    /**
     * 三个出战槽: 钉条中心 x / 钉条行带 / 窗口半宽。
     *
     * <p>⚠️⚠️ 2026-10-15 真机实测 (8.11.1 编辑器页)：**x 比 PC 换算整体小 68px**。
     * 实测钉条簇心 = {165, 319, 472}；PC {@code X(132/324/516)} 给 {233, 387, 540}。
     * 间距两者都是 154 (一致)，**只有整体偏移不同**。
     * 后果 (用户当场看到的现象)：窗口错位 ⇒ 槽 2 的能量被读成 2，于是本来够能量的队伍
     * 被反复判"需替换"、拖拽又拖不动，空转到"无可用能量角色"。
     * 行带 y 反而对得上 (PC 换算 279~295，实测黄钉在 286~289)。
     */
    public static final int[] SLOT_PIP_CX  = {165, 319, 472};
    public static final int[] SLOT_BAND    = {Y(349), Y(369)};
    public static final int   SLOT_HALF    = S(75);
    /** 拖拽落点 x (与钉条同轴 —— 卡面铭牌中心)。 */
    public static final int[] SLOT_DROP_X  = {165, 319, 472};

    /**
     * 候选位: 钉条行带 / 半宽 / 卡中心。
     *
     * <p>⚠️ 真机是 **8 张卡** (PC 是 6)，间距 **162** (PC 158)，首卡 x=93。
     * 实测钉条簇心 {93,255,417,579,741,903,1065,1227}；行带 y 与 PC 换算吻合
     * (543~561，实测黄钉 550~553)。卡中心 y 取 452 —— 与 PC 的相对关系一致
     * (PC 钉带心 690 → 拖点 565，高 125px；真机钉带心 551 → 452)。
     */
    public static final int[] ROSTER_BAND  = {Y(679), Y(701)};
    public static final int   ROSTER_HALF  = S(66);
    public static final int   ROSTER_CARD_Y = 452;
    /** 真机可见候选卡 x 中心 (8 张)。 */
    public static final int[] ROSTER_CX = {93, 255, 417, 579, 741, 903, 1065, 1227};
    public static final int[][] ROSTER_CENTERS = new int[ROSTER_CX.length][];
    static {
        for (int i = 0; i < ROSTER_CX.length; i++) {
            ROSTER_CENTERS[i] = new int[]{ROSTER_CX[i], ROSTER_CARD_Y};
        }
    }

    /** 对手三卡: click 点 / 战力 ROI / 火框固定 ROI / 火框动态搜索窗。 */
    public static final int[][] OPP_CLICK   = {PI(1000, 235), PI(1000, 412), PI(1000, 590)};
    public static final int[][] OPP_POWER   = {R(800, 166, 884, 198), R(775, 342, 860, 374),
                                               R(798, 514, 900, 554)};
    public static final int[][] OPP_FIRE    = {R(1136, 156, 1206, 222), R(1136, 332, 1206, 398),
                                               R(1136, 508, 1206, 574)};
    /**
     * 对手三卡火框搜索窗。
     *
     * <p>⚠️⚠️ 2026-10-16 真机实测重标 —— 徽章锚在各卡**右上角**且右缘锚定
     * (x2.5 徽章红域实测 x1214-1258), 三卡卡顶 y = {88, 264, 440} (间距 176),
     * 徽章向上探出卡沿约 38px。旧值 {@code R(1100,…)} 换算后 x1008-1101,
     * **整个搜索区都够不着徽章** ⇒ readMult 恒 NaN, 倍率比较形同虚设
     * (与 {@link #ROI_REFRESH} 同一批"右缘锚定"元素, 同一天一并修)。
     */
    public static final int[][] FIRE_SEARCH = {
            {1180, 30, 100, 120}, {1180, 206, 100, 120}, {1180, 382, 100, 120}};

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
            if (names[i].equals(element)) return new int[]{FILTER_COL_X[i], FILTER_ROW_Y[1]};
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
                return new int[]{FILTER_COL_X[i % 6], FILTER_ROW_Y[i < 6 ? 2 : 3]};
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
