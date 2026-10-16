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

    /** 默认屏幕尺寸 — 用 WindowManager 的显示指标 (不依赖任何 Service 方法) */
    private static Size defaultSize(final Context c) {
        return new Size() {
            @Override public int w() {
                try {
                    android.util.DisplayMetrics m = new android.util.DisplayMetrics();
                    ((WindowManager) c.getSystemService(Context.WINDOW_SERVICE))
                            .getDefaultDisplay().getMetrics(m);
                    return m.widthPixels;
                } catch (Throwable t) { return 1080; }
            }
            @Override public int h() {
                try {
                    android.util.DisplayMetrics m = new android.util.DisplayMetrics();
                    ((WindowManager) c.getSystemService(Context.WINDOW_SERVICE))
                            .getDefaultDisplay().getMetrics(m);
                    return m.heightPixels;
                } catch (Throwable t) { return 2340; }
            }
        };
    }

    public void setOnLayoutChange(Runnable r) { this.onLayoutChange = r; }

    public boolean isShown() { return root != null; }

    @SuppressLint("ClickableViewAccessibility")
    public void show() {
        if (root != null) return;

        /* ---- 面板: 按键行 + 统计 ---- */
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        btnStart = addBtn(row, startText, "start");     // 暂停后变「继续」, 见 refreshStartLabel
        addBtn(row, "导航", "start_nav");
        addBtn(row, "暂停", "stop");                    // 只停循环, 窗口留着可续 (动作名仍是 stop)
        addBtn(row, "监控", "monitor");                 // 长跑看进度: 当前界面/场次/时长/日志
        addBtn(row, "结束", "end");                     // 连服务/窗口一起收 = 退出挂机
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

        root.setOnTouchListener(dragListener());
        btnMini.setOnTouchListener(dragListener());   // 小箭头也能拖 (点击=展开)

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
            if ("start".equals(act) || "start_nav".equals(act)) {
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

    /** 按住空白处拖动; 松手 clamp 拉回屏内 (JS clampPos 同款, 无贴边缩进) */
    @SuppressLint("ClickableViewAccessibility")
    private View.OnTouchListener dragListener() {
        final int[] down = new int[2];
        final int[] moved = {0};
        return (v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down[0] = (int) e.getRawX() - lp.x;
                    down[1] = (int) e.getRawY() - lp.y;
                    moved[0] = 0;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    int nx = (int) e.getRawX() - down[0], ny = (int) e.getRawY() - down[1];
                    if (Math.abs(nx - lp.x) + Math.abs(ny - lp.y) > 4) moved[0] = 1;
                    lp.x = nx; lp.y = ny;
                    try { wm.updateViewLayout(root, lp); } catch (Exception ignored) { }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    if (v == btnMini && moved[0] == 0) setFold(false);   // 点箭头未拖 = 展开
                    clampPos();
                    try { wm.updateViewLayout(root, lp); } catch (Exception ignored) { }
                    OverlayCtl.rememberPos(lp.x, lp.y);      // 记住位置, 下次独立启动复原
                    if (onLayoutChange != null) onLayoutChange.run();
                    return true;
            }
            return false;
        };
    }

    /** 松手拉回屏内: 面板/小箭头都不能被拖丢在屏外 (JS clampPos 同款) */
    private void clampPos() {
        int w = root.getWidth(), h = root.getHeight(), sw = size.w(), sh = size.h();
        if (w <= 0 || h <= 0) return;
        if (lp.x < 0) lp.x = 0;
        if (lp.y < 0) lp.y = 0;
        if (lp.x + w > sw) lp.x = sw - w;
        if (lp.y + h > sh) lp.y = sh - h;
    }

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
                setHidden(true);          // 挂机时**整个隐藏**, 不只折叠 (见 setHidden 注释)
            } else {
                boolean expand = preRunFold != null && !preRunFold;
                preRunFold = null;
                setHidden(false);         // 先恢复可见
                if (expand) setFold(false);
            }
            SgmLog.i("overlay", "foldForRun 应用后 hidden=" + hiddenForRun);
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
            savedW = 0;
        }
        root.setVisibility(View.VISIBLE);   // ⚠️ 永远 VISIBLE —— 理由见上
        clampPos();
        try { wm.updateViewLayout(root, lp); } catch (Exception ignored) { }
    }

    /** 隐藏前的窗口尺寸/透明度 (恢复用; {@code WindowManager.LayoutParams} 没有拷贝构造)。 */
    private int savedW, savedH;
    private float savedAlpha = -1f;

    /** 挂机期间窗口是否被整体隐藏 (监控页/主页可用它提示"悬浮窗已藏, 回 App 操作")。 */
    public boolean hiddenForRun() { return hiddenForRun; }

    public void destroy() {        if (root != null) {
            try { wm.removeView(root); } catch (Exception ignored) { }
            root = null;
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
