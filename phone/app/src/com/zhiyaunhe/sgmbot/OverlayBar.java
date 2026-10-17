package com.zhiyaunhe.sgmbot;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 悬浮控制条 — 移植自 autojs settle_bot.js 最新悬浮面板 (2026-10-07 口径):
 *   五键一行 (开始/导航/暂停/结束/◀) + 三行统计; ◀ 折成贴边小箭头, 点箭头展开;
 *   拖动按住空白处, 松手 clamp 拉回屏内 (无贴边缩进);
 *   暂停 = 停引擎可再开; 结束 = 连服务/悬浮窗/通知一并收掉 (退出脚本同义)。
 *
 * ⚠️ 2026-10-11 **「停止」改名「暂停」** (用户: 「按钮的名字有歧义, 停止应该指的是暂停」)。
 *    这颗键的行为一直就是暂停 (BotService.stop() 只停循环, 服务/窗口留着, 再点开始可续),
 *    名字却叫"停止" —— 旁边还有一颗真正收窗口的「结束」, 两颗都像"停", 用户按错就
 *    以为程序坏了。现在名字与语义对齐:
 *      暂停 = 停循环、窗口留着、可续;  结束 = 退出挂机、连窗口一起收。
 *    「开始」那颗键在暂停后显示**「继续」** ({@link #refreshStartLabel}) —— 名字也得跟着状态走。
 *
 * ⚠️ **动作名 (`start`/`start_nav`/`stop`/`end`) 一个都没动** —— 那是 adb 广播
 *    (`am broadcast … -e action stop`) 与 HTTP `/trigger?action=stop` 的接口契约,
 *    改文案不能改协议。只有人看的字变了。
 *
 * ⚠️ 2026-10-08 改造: **不再依赖 BotService 实例** —— 用户要求"悬浮窗可以单独启动"。
 *    之前构造要传 BotService, 而窗口的 Context/WindowManager/屏幕尺寸全从它拿,
 *    等于"服务没起就没悬浮条", 与 autojs 版(floaty.window 在脚本里就能开)的体验
 *    不一致。现在改成只收 Context (Activity 也行) + 一组尺寸回调, 详见 OverlayCtl。
 *    窗口类型仍是 TYPE_APPLICATION_OVERLAY, 所以**谁是 Context 都不影响挂载**。
 */
public final class OverlayBar {
    private final Context ctx;
    private final WindowManager wm;
    /** 屏幕尺寸提供者 — Activity 侧用 DisplayMetrics, Service 侧同款; 默认走 wm */
    private final Size size;
    private WindowManager.LayoutParams lp;
    private FrameLayout root;
    private LinearLayout panel;
    private Button btnMini;
    private Button btnStart;
    private TextView tvStat;
    private boolean folded;
    /** 挂机期间整个窗口被藏起来了 (见 {@link #setHidden})。 */
    private boolean hiddenForRun;

    /** 挂机开始**之前**用户的折叠状态 (null = 本次挂机还没动过), 停手时据此还原。 */
    private Boolean preRunFold;
    /** 「暂停」小浮标的窗口 (挂机期间主窗整个藏起来, 只剩它 —— 见 setFabVisible)。 */
    private FrameLayout fabRoot;
    private WindowManager.LayoutParams fabLp;
    private boolean fabShown;
    /** 浮标上次响应点击的时刻 (防连点: 挂机中手滑点到两下会"暂停→又继续→又暂停") */
    private long fabLastTapMs;
    /** 统计文案 (单独启动时由 OverlayCtl 周期刷新; 服务跑时由 SettleLoop 推) */
    private volatile String statText = "待机";
    /** 「开始 / 继续」当前文案 —— 记住它而不是回读 Button (回读要依赖 View.getText, 探针桩也得跟着长) */
    private String startText = "开始";
    /** 折叠/位置变化回调 (托盘侧要持久化记忆位置时用) */
    private Runnable onLayoutChange;

    public interface Size { int w(); int h(); }

    public OverlayBar(Context ctx) {
        this(ctx, null);
    }

    public OverlayBar(Context ctx, Size size) {
        this.ctx = ctx;
        this.wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        this.size = size != null ? size : defaultSize(ctx);
    }

    /**
     * 默认屏幕尺寸 — {@code getRealSize} (不是 getMetrics)。
     *
     * <p>⚠️⚠️ 2026-10-17 实栽 (用户: 「横屏的时候有问题, 不在边角」): getMetrics 返回的是
     * **应用可用区** (扣除状态栏/导航条), 且**在旋转后不一定跟着转** —— 竖屏时创建的悬浮窗,
     * 拿到横屏 display 上会算出竖屏尺寸 ⇒ 右下角坐标直接飞出屏幕, 或者 clamp 到一个
     * 莫名其妙的位置 (用户看到的就是"不在边角")。
     * getRealSize 返回**当前旋转下的真实显示尺寸** (不含窗口装饰扣除), 横竖屏都对。 */
    private static Size defaultSize(final Context c) {
        return new Size() {
            private void real(android.graphics.Point p) {
                try {
                    ((WindowManager) c.getSystemService(Context.WINDOW_SERVICE))
                            .getDefaultDisplay().getRealSize(p);
                } catch (Throwable t) { p.set(1080, 2340); }
            }
            @Override public int w() {
                android.graphics.Point p = new android.graphics.Point(); real(p); return p.x;
            }
            @Override public int h() {
                android.graphics.Point p = new android.graphics.Point(); real(p); return p.y;
            }
        };
    }

    public void setOnLayoutChange(Runnable r) { this.onLayoutChange = r; }

    public boolean isShown() { return root != null; }

    @SuppressLint("ClickableViewAccessibility")
    public void show() {
        if (root != null) return;

        /* ---- 面板: 按键行 + 统计 ----
         * ⚠️ 2026-10-17 用户: 「切换到 PF 刷的模式之后, 悬浮窗应该只有一个开始,
         *    然后 (人自己) 切换到 PF 界面, 再点那个开始」。
         *    所以按键表**按模式变** (BotService.MODE_APP_*) —— 别再摆一堆用不上的键让人犹豫:
         *      pf        : 开始 / 暂停            (开始 = 就地开始, 不导航; 见 MODE_NAV_HERE)
         *      event_*   : 开始 / 暂停 / 监控 / 结束
         *    「暂停」在两种模式下都保留 —— 它是唯一的**停机手段**, 少了它跑起来就没法收
         *    (「结束」会连服务与悬浮窗一起收掉, 比"暂停"重得多)。 */
        String appMode = BotService.appMode(ctx);
        boolean pfMode = BotService.MODE_APP_PF.equals(appMode);
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        btnStart = addBtn(row, startText, "start_mode");   // 暂停后变「继续」, 见 refreshStartLabel
        if (!pfMode) addBtn(row, "监控", "monitor");        // 长跑看进度: 当前界面/场次/时长/日志
        addBtn(row, "暂停", "stop");                        // 只停循环, 窗口留着可续 (动作名仍是 stop)
        if (!pfMode) addBtn(row, "结束", "end");            // 连服务/窗口一起收 = 退出挂机
        Button fold = new Button(ctx);
        fold.setText("◀");
        fold.setTextSize(12f);
        fold.setPadding(0, 0, 0, 0);
        fold.setOnClickListener(v -> setFold(true));
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(64, 66);
        fp.leftMargin = 6;
        row.addView(fold, fp);

        tvStat = new TextView(ctx);
        tvStat.setTextSize(15f);
        tvStat.setTextColor(0xFFFFFFFF);
        tvStat.setGravity(Gravity.CENTER);
        tvStat.setText(statText);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 210);
        sp.topMargin = 6;

        panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.addView(row);
        panel.addView(tvStat, sp);

        /* ---- 折叠小箭头 (初始隐藏) ---- */
        btnMini = new Button(ctx);
        btnMini.setText("◀");
        btnMini.setTextSize(12f);
        btnMini.setPadding(0, 0, 0, 0);
        btnMini.setVisibility(View.GONE);
        btnMini.setOnClickListener(v -> setFold(false));

        root = new FrameLayout(ctx);
        root.setBackgroundColor(0xCC1E1E1E);
        root.setPadding(14, 14, 14, 14);
        root.addView(panel);
        root.addView(btnMini, new FrameLayout.LayoutParams(96, 66));

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = 24;
        lp.y = 90;
        /* 单独启动时没有服务给它记位置 ⇒ 从上次关掉的地方恢复 (OverlayCtl 存盘) */
        int[] pos = OverlayCtl.savedPos();
        if (pos != null) { lp.x = pos[0]; lp.y = pos[1]; }

        root.setOnTouchListener(dragListener(lp, root, CLICK_NONE));
        btnMini.setOnTouchListener(dragListener(lp, root, CLICK_FOLD));   // 小箭头也能拖 (点击=展开)

        try {
            wm.addView(root, lp);
            SgmLog.i("overlay", "悬浮条已挂载 (独立于服务)");
        } catch (Exception e) {
            root = null;
            SgmLog.i("overlay", "挂载失败 (缺悬浮窗权限?): " + e);
            throw new IllegalStateException("挂载失败: " + e.getMessage(), e);
        }
    }

    private Button addBtn(LinearLayout row, String name, String act) {
        Button b = new Button(ctx);
        b.setText(name);
        b.setTextSize(12f);
        b.setPadding(0, 0, 0, 0);
        b.setOnClickListener(v -> {
            /* 悬浮条上点按钮也要有反馈 —— 之前点了没动静让人以为是按钮坏了 */
            String why = Ui.blockReason(null, Ui.NEED_SHIZUKU_SERVER);
            /* ⚠️ 每个"开始"类键都要前置检查 —— 新加的 start_mode 别忘了挂上, 否则它会
             *    绕过 Shizuku 检查直接起循环, 再在截屏那一步失败 (表现是"点了没反应")。 */
            if ("start".equals(act) || "start_nav".equals(act) || "start_here".equals(act)
                    || "start_mode".equals(act)) {
                if (why != null) {
                    setText(why);
                    SgmLog.i("overlay", "开始被拦: " + why);
                    /* 送用户去指引页 (悬浮窗没法跳 Activity 之外的设置, 但可以说明) */
                    return;
                }
            }
            setText("已发送: " + name);
            BotService.trigger(act);
            /* 状态是**同步**变的 (start→spawn 里置 running; stop→立刻置 false), 所以这里
             * 立刻刷一次标签就能对上, 不用等 OverlayCtl 那个 5s 心跳。 */
            refreshStartLabel();
            SgmLog.i("overlay", "按钮 " + name + " → trigger(" + act + ")");
        });
        row.addView(b, new LinearLayout.LayoutParams(96, 66));
        return b;
    }

    /**
     * 「开始」那颗键的文案 = 当前该做什么: 暂停过就显示**「继续」**。
     *
     * ⚠️ 名字必须跟着状态走, 否则又回到"名字有歧义"那个坑: 暂停之后按钮还写"开始",
     *    用户会以为要从头跑一遍 (甚至以为刚才白跑了)。
     * 由两处调用: 点任意键之后 (立刻) + OverlayCtl 的 5s 心跳 (兜底, 比如引擎跑完
     * 每日上限自己停了)。
     */
    public void refreshStartLabel() {
        String lb = BotService.paused() ? "继续" : "开始";
        if (lb.equals(startText)) return;
        startText = lb;
        final Button b = btnStart;
        if (b != null) post(() -> b.setText(lb));
    }

    /** 当前「开始/继续」键上的文案 (探针断言用) */
    public String startLabel() { return startText; }

    /** 拖动监听里"松手算点击"时的三种语义 (主窗空白 / 小箭头展开 / 浮标暂停)。 */
    private static final int CLICK_NONE = 0, CLICK_FOLD = 1, CLICK_PAUSE = 2;

    /** 按住空白处拖动; 松手 clamp 拉回屏内 (JS clampPos 同款, 无贴边缩进)
     *  @param targetLp  要改的那个窗口的 LayoutParams (主窗或浮标)
     *  @param targetRoot 要 updateViewLayout 的那个根 view
     *  @param clickMode 松手且没拖动时的语义 */
    @SuppressLint("ClickableViewAccessibility")
    private View.OnTouchListener dragListener(final WindowManager.LayoutParams targetLp,
                                              final View targetRoot, final int clickMode) {
        final int[] down = new int[2];
        final int[] moved = {0};
        return (v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down[0] = (int) e.getRawX() - targetLp.x;
                    down[1] = (int) e.getRawY() - targetLp.y;
                    moved[0] = 0;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    int nx = (int) e.getRawX() - down[0], ny = (int) e.getRawY() - down[1];
                    /* ⚠️ 阈值 12 不是 4 (2026-10-17 用户: 「点不了」): 4px 时手指微抖就被
                     * 判成"拖动", 松手不触发点击 —— 52dp 的小浮标上极易发生, 表现为
                     * "点了没反应"。12px 仍远小于"真的想拖"的幅度 (拖动通常 >30px)。 */
                    if (Math.abs(nx - targetLp.x) + Math.abs(ny - targetLp.y) > 12) moved[0] = 1;
                    targetLp.x = nx; targetLp.y = ny;
                    try { wm.updateViewLayout(targetRoot, targetLp); } catch (Exception ignored) { }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    if (moved[0] == 0) {
                        if (clickMode == CLICK_FOLD) setFold(false);            // 点箭头未拖 = 展开
                        else if (clickMode == CLICK_PAUSE) tapPauseFab();       // 点浮标 = 暂停
                    }
                    clampPos(targetLp, targetRoot);
                    try { wm.updateViewLayout(targetRoot, targetLp); } catch (Exception ignored) { }
                    /* 位置记忆按窗口分开记 —— 两窗不同时可见, 共用一把 key 会互相覆盖。 */
                    if (clickMode == CLICK_PAUSE) OverlayCtl.rememberFabPos(targetLp.x, targetLp.y);
                    else OverlayCtl.rememberPos(targetLp.x, targetLp.y);
                    if (onLayoutChange != null) onLayoutChange.run();
                    return true;
            }
            return false;
        };
    }

    /** 松手拉回屏内: 面板/小箭头都不能被拖丢在屏外 (JS clampPos 同款) */
    private void clampPos(WindowManager.LayoutParams p, View r) {
        int w = r.getWidth(), h = r.getHeight(), sw = size.w(), sh = size.h();
        if (w <= 0 || h <= 0) return;
        if (p.x < 0) p.x = 0;
        if (p.y < 0) p.y = 0;
        if (p.x + w > sw) p.x = sw - w;
        if (p.y + h > sh) p.y = sh - h;
    }

    /** 主窗版 clamp (供老调用点用)。 */
    private void clampPos() { clampPos(lp, root); }

    /**
     * 折叠: 面板 ↔ 小箭头; 折叠时窗口变小, 把窗口拉回屏内防箭头消失在屏外。
     * ⚠️ 折叠态也必须 clamp —— 小箭头比面板小得多, 不 clamp 的话原本贴右下的面板
     *    折起来后箭头会留在屏外 (JS 版踩过)。
     */
    private void setFold(boolean on) {
        folded = on;
        panel.setVisibility(on ? View.GONE : View.VISIBLE);
        btnMini.setVisibility(on ? View.VISIBLE : View.GONE);
        clampPos();
        try { wm.updateViewLayout(root, lp); } catch (Exception ignored) { }
        OverlayCtl.rememberFold(on);
    }

    /**
     * 挂机期间自动折叠悬浮条, 停手时还原用户原来的样子。
     *
     * <p>⚠️ 2026-10-15 真机实栽 (这是最讽刺的一个坑): **bot 自己的悬浮条挡住 bot 自己的眼睛**。
     * 面板被拖到画面左侧后覆盖 work y≈200~330, 正好压住大厅的 PRIZE FIGHTS 节点 ——
     * 模板匹配从 **0.98 掉到 0.55** ⇒ 判不出大厅 ⇒ 按返回键 ⇒ 弹出 QUIT GAME? ⇒ 死锁。
     * 折叠后只剩右上角那个 96x66 的小箭头 (work 约 13~79 x 48~98), 不再压任何关键 UI。
     *
     * <p>用户点小箭头仍可随时展开 (要按"结束"时), 展开的优先级高于自动折叠 ——
     * 这里只在**状态切换的那一刻**动一次, 不跟用户抢。
     */
    public void foldForRun(final boolean running) {
        SgmLog.i("overlay", "foldForRun(" + running + ") folded=" + folded + " pre=" + preRunFold);
        post(() -> {
            if (running) {
                if (preRunFold == null) preRunFold = folded;
                setFabVisible(true);         // 先把「暂停」浮标挂上 (用户唯一的停机入口)
                setHidden(true);             // 再藏主窗 (见 setHidden 注释)
            } else {
                setHidden(false);            // 先恢复主窗 (任何时刻至少有一个可见窗口)
                setFabVisible(false);        // 再收浮标
                boolean expand = preRunFold != null && !preRunFold;
                preRunFold = null;
                if (expand) setFold(false);
            }
            SgmLog.i("overlay", "foldForRun 应用后 hidden=" + hiddenForRun + " fab=" + fabShown);
        });
    }

    /**
     * 挂机期间把悬浮窗**视觉上完全消失**。
     *
     * <p>⚠️⚠️ 不能用 {@code root.setVisibility(GONE)} —— 2026-10-16 实栽: 那样 App 就
     * **一个可见窗口都没有了**, MIUI 的后台冻结立刻把它冻住 (实测: 启动 8 秒后整个进程
     * 无响应, 日志/HTTP/看门狗全部沉默; 而之前 8 小时长跑窗口一直可见就没事)。
     *
     * <p>做法: 把窗口缩成 **1x1 像素、alpha=0**、贴在 (0,0) —— 用户看不见也点不到,
     * 但对系统来说它仍是一个可见窗口, 冻结器不动它。暂停/结束时按备份的 LayoutParams 恢复。
     */
    private void setHidden(boolean on) {
        hiddenForRun = on;
        if (on) {
            if (savedW == 0) {                 // 只备份一次 (重复开始不会把 1x1 存回去)
                savedW = lp.width;
                savedH = lp.height;
                savedAlpha = lp.alpha;
                savedX = lp.x;
                savedY = lp.y;
            }
            lp.width = 1;
            lp.height = 1;
            lp.alpha = 0f;
            lp.x = 0;
            lp.y = 0;
        } else if (savedW != 0) {
            lp.width = savedW;
            lp.height = savedH;
            lp.alpha = savedAlpha;
            /* ⚠️ x/y 也必须还原 (2026-10-17 修): 藏窗口时把它们按成了 (0,0), 只还原尺寸
             * 的话暂停后主窗会蹦到左上角 —— 而左上角正是游戏 HUD (房子/金币) 那一带,
             * 浮标让暂停变频繁之后这个 bug 天天能撞见。 */
            lp.x = savedX;
            lp.y = savedY;
            savedW = 0;
        }
        root.setVisibility(View.VISIBLE);   // ⚠️ 永远 VISIBLE —— 理由见上
        /* ⚠️ 这里不 clamp: 此刻 root 的实际尺寸可能还是 1x1 (view 要等下一帧才重新布局),
         * 拿旧尺寸 clamp 会把位置夹到右上角去。用户拖过的位置本来就是屏内的。 */
        try { wm.updateViewLayout(root, lp); } catch (Exception ignored) { }
    }

    /* ================= 挂机期间的「暂停」小浮标 =================
     * 为什么非要它 (2026-10-17 用户: 「你做一个自定义键, 我可以快速暂停」):
     *   主窗挂机时被 setHidden 缩成 1x1 alpha=0 (为了不被 MIUI 冻住), **人点不到**
     *   ⇒ 想暂停只能切回 App 主页, 而游戏切出去再切回来要好几秒, 挂机时很不顺手。
     * 做法: 另开一个 52dp 的小窗口 (主窗藏起来时它可见, 主窗回来时它消失), 点一下
     *   = trigger("stop"), 与悬浮条上那颗「暂停」键**走同一个动作名**, 不造第二套协议。
     * ⚠️ 位置默认贴**右下角底部**: 游戏 HUD (FIGHT!/倍率徽章/CONTINUE) 全在右侧中上部,
     *   work y≥540 (全屏 y≥1012) 是结算按钮行以下的空白带, 浮标 52dp 放进去不挡判定;
     *   但每台机的 UI 不完全一样 ⇒ **可拖动**, 位置单独存盘 (overlay_fab_pos)。 */

    /** 显示/隐藏浮标 (幂等; 懒创建 —— App 启动时不白占一个窗口)。
     *  ⚠️ 这里必须**同步**做 (在 foldForRun 的主线程 post 里被调): 再套一层 post 会让
     *  「先挂浮标、再藏主窗」的顺序倒过来, 中间出现一帧"两个窗口都没有" —— 正是
     *  2026-10-16 想避开的 MIUI 后台冻结场景。所以非主线程调用时才转 post。 */
    public void setFabVisible(final boolean on) {
        if (on == fabShown) return;
        fabShown = on;
        main(() -> {
            if (on) {
                try {
                    if (fabRoot == null) buildFab();
                    if (fabRoot == null) return;          // 建失败 (极少见: 权限被收回)
                    /* ⚠️ 每次显示都按**当前**屏幕尺寸重算默认位置 (用户没拖过时):
                     *   竖屏建的窗拿到横屏 display 上, 旧坐标会飞出屏幕 —— 2026-10-17
                     *   用户报「横屏的时候有问题, 不在边角」就是这么来的。 */
                    refreshFabPos();
                    wm.addView(fabRoot, fabLp);
                    /* 兜底 clamp: buildFab 时 root 还没布局 (getWidth()=0) ⇒ 那次 clamp 是
                     * 空转; 这里尺寸有了, 再夹一次保证浮标一定在屏内 (横竖屏/metrics 取错
                     * 值时不会飞到屏幕外面去)。 */
                    clampPos(fabLp, fabRoot);
                    try { wm.updateViewLayout(fabRoot, fabLp); } catch (Exception ignored) { }
                    SgmLog.i("overlay", "暂停浮标已挂载 @(" + fabLp.x + "," + fabLp.y + ") 屏幕 "
                            + size.w() + "x" + size.h());
                } catch (Throwable t) {
                    fabShown = false;
                    SgmLog.i("overlay", "暂停浮标挂载失败 (不影响主链): " + t);
                }
            } else {
                if (fabRoot == null) return;
                try { wm.removeView(fabRoot); } catch (Exception ignored) { }
                SgmLog.i("overlay", "暂停浮标已收起");
            }
        });
    }

    /** 主线程同步执行 (已在主线程就直接跑, 保住调用方要求的顺序)。 */
    private void main(Runnable r) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) { r.run(); return; }
        new android.os.Handler(android.os.Looper.getMainLooper()).post(r);
    }

    /**
     * 浮标默认位置 = 右下角, 但**离底留一条手势带** (MIUI 全面屏手势)。
     * ⚠️ 2026-10-17 用户: 「不仅点不了」—— 贴死底边时手指按在系统手势区里,
     *   轻触会被"上滑回桌面"吃掉, 表现为"点了没反应"。留 56dp 就落在可点区。
     * 用户拖过的位置 (savedFabPos) 不动; 只有从没拖过才重算。 */
    private void refreshFabPos() {
        if (OverlayCtl.savedFabPos() != null) return;
        int dp = (int) (52 * ctx.getResources().getDisplayMetrics().density);
        int pad = (int) (56 * ctx.getResources().getDisplayMetrics().density);
        fabLp.x = size.w() - dp - (int) (12 * ctx.getResources().getDisplayMetrics().density);
        fabLp.y = size.h() - dp - pad;
    }

    /** 建浮标窗口 (只在第一次要显示时跑)。 */
    private void buildFab() {
        final int dp = (int) (52 * ctx.getResources().getDisplayMetrics().density);
        final Button b = new Button(ctx);
        b.setText("暂停");
        b.setTextSize(9f);
        b.setTextColor(0xFFFFFFFF);
        b.setPadding(0, 0, 0, 0);
        b.setAllCaps(false);
        /* ⚠️⚠️ 2026-10-17 实栽 (用户: 「我暂停还是没反应, 为啥你的悬浮窗的按钮就有反应？」):
         * Button **默认 clickable=true** ⇒ 它自己把 DOWN/UP 全消费掉 (只有按下高亮,
         * 没有任何动作), 父容器 fabRoot 的 OnTouchListener **一个事件都收不到** ——
         * 而点击判定 (tapPauseFab) 就写在那边。浮标里这个 Button 又铺满整个窗口,
         * 连一条空白区都没给父容器 ⇒ 表现为"点了完全没反应"。
         * 主窗的按钮有反应是因为它们带 setOnClickListener, 走 Button 自己的标准点击路径。
         * 修法: 关掉它的 clickable, 把事件让给父容器的 dragListener (它同时管点击和拖动)。 */
        b.setClickable(false);
        b.setFocusable(false);
        /* 圆角红底 —— 用代码画, 不引 drawable 资源 (手工构建没有 res 合并) */
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp / 2f);
        bg.setColor(0xCCB71C1C);
        bg.setStroke(1, 0x66FFFFFF);
        b.setBackground(bg);

        FrameLayout f = new FrameLayout(ctx);
        f.addView(b, new FrameLayout.LayoutParams(dp, dp));
        fabRoot = f;

        fabLp = new WindowManager.LayoutParams(
                dp, dp,
                Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        fabLp.gravity = Gravity.TOP | Gravity.START;
        fabLp.alpha = 0.92f;
        int[] pos = OverlayCtl.savedFabPos();
        if (pos != null) {
            fabLp.x = pos[0]; fabLp.y = pos[1];
        } else {
            /* 默认右下角贴底 (游戏底部是空白带, 见类注释) */
            fabLp.x = size.w() - dp - 12;
            fabLp.y = size.h() - dp - 12;
        }
        /* ⚠️ 只挂 Button 自己身上 (不重复挂 fabRoot): Button 的 OnTouchListener 一定会被调用
         * (与 clickable 无关), 而 fabRoot 那条依赖"事件从子 view 冒泡上来" —— 两条都挂会
         * 让同一次点击触发两遍 tapPauseFab (第二遍被防连点拦下, 但会打出误导性的日志)。 */
        b.setOnTouchListener(dragListener(fabLp, f, CLICK_PAUSE));
        clampPos(fabLp, f);
    }

    /**
     * 点了浮标 = 暂停 (与悬浮条上「暂停」键同一个动作名 stop)。
     * ⚠️ 防连点: 点下去到循环真正退出有几百 ms~几秒 (工作线程要跑完当前这步),
     *   这期间再点一下就会"停了又开" —— 1.5s 内忽略后续点击。
     * ⚠️ 立刻收起浮标做**即时反馈**: 光 trigger(stop) 的话, 从点击到循环退出之间
     *   ( sleep 分段后 ≤100ms, 但赶上长搜索时要几秒) 浮标还挂在原处, 用户会以为
     *   "点了没反应"又去点 —— 2026-10-17 实测过这种观感。 */
    void tapPauseFab() {
        long now = System.currentTimeMillis();
        if (now - fabLastTapMs < 1500) {
            SgmLog.i("overlay", "暂停浮标: 连点忽略 (距上次 " + (now - fabLastTapMs) + "ms)");
            return;
        }
        fabLastTapMs = now;
        SgmLog.i("overlay", "暂停浮标被点 → trigger(stop)");
        setFabVisible(false);            // 即时反馈 (foldForRun(false) 里那次是幂等空操作)
        BotService.trigger("stop");
    }

    /** 隐藏前的窗口尺寸/透明度/位置 (恢复用; {@code WindowManager.LayoutParams} 没有拷贝构造)。 */
    private int savedW, savedH, savedX, savedY;
    private float savedAlpha = -1f;

    /** 挂机期间窗口是否被整体隐藏 (监控页/主页可用它提示"悬浮窗已藏, 回 App 操作")。 */
    public boolean hiddenForRun() { return hiddenForRun; }

    public void destroy() {
        if (root != null) {
            try { wm.removeView(root); } catch (Exception ignored) { }
            root = null;
        }
        /* 浮标也要收 —— 「结束」挂机时它是唯一还挂着的窗口, 不收就留在屏幕上点不动了。
         * ⚠️ 同步摘 fabRoot 引用: setFabVisible 的幂等判断靠 fabShown 标志,
         *    这里不重置它的话下次 setFabVisible(true) 会以为"已显示"而跳过 addView。 */
        fabShown = false;
        if (fabRoot != null) {
            try { wm.removeView(fabRoot); } catch (Exception ignored) { }
            fabRoot = null;
        }
    }

    /** 三行统计: 前缀+时长 / 今日场数 / 本次胜负 — 同 autojs setStat 文案 */
    public void setStat(String prefix, String elapsed, String daily, int wins, int loses) {
        setText(prefix + " " + elapsed + "\n" + daily + "\n" + wins + "胜" + loses + "负");
    }

    public void setText(String s) {
        if (s == null) return;
        statText = s;
        final TextView t = tvStat;
        if (t == null) return;
        post(() -> t.setText(s));
    }

    /** UI 线程 post — Activity 与 Service 都能用 (Context 不保证是 Service) */
    private void post(Runnable r) {
        try {
            if (ctx instanceof android.app.Service) {
                new android.os.Handler(android.os.Looper.getMainLooper()).post(r);
            } else {
                new android.os.Handler(android.os.Looper.getMainLooper()).post(r);
            }
        } catch (Throwable t) {
            /* 没有 Looper 时直接跑 (极罕见) */
            r.run();
        }
    }

    /** 当前统计文案 (给 OverlayCtl 的周期刷新用) */
    public String statText() { return statText; }

    /** 窗口是否处于折叠态 */
    public boolean folded() { return folded; }
}
