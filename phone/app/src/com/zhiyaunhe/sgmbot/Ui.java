package com.zhiyaunhe.sgmbot;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 界面小工具 — 所有页面共用, 主要是**一句人话的点击反馈**。
 *
 * 为什么要有这个 (2026-10-08 用户反馈): "点击的时候需要弹提示说明没反应/错误原因"。
 * 之前的毛病是按钮点了没动静 —— 因为失败路径全部只写 SgmLog, UI 上什么都不说,
 * 看着像 App 卡住了。实测三类"没反应"最冤:
 *   ① 还没授权就去点功能键   → 应该直接告诉去哪授权, 顺手把人送过去
 *   ② 服务没起就点控制键     → 应该说"先点启动服务", 而不是静默丢弃
 *   ③ 后台线程失败了没人回显 → 应该把错误原文贴出来
 *
 * 所以这里定一条规矩: **任何按钮都必须先给人一句即时反馈** (Toast + 状态区),
 * 长任务再补一次结果回显。见 {@link #act} / {@link #gate}。
 */
public final class Ui {

    /** 主题色 — 挂在顶层 (UI 层不依赖 App.inst, 冷启动/工具类也能用) */
    public static final int BG = 0xFFF6F7F9;
    public static final int CARD = 0xFFFFFFFF;
    public static final int LINE = 0xFFE3E6EB;
    public static final int TXT = 0xFF1F2329;
    public static final int SUB = 0xFF6B7280;
    public static final int ACC = 0xFF2F6BFF;
    public static final int OK = 0xFF17964B;
    public static final int WARN = 0xFFD97706;
    public static final int ERR = 0xFFD93636;

    private Ui() { }

    /* ---------------- 点击反馈 ---------------- */

    /**
     * 包一层点击: **先弹提示说在做什么, 再执行**; 执行抛异常 → 提示错误原因。
     *
     * @param note    点击瞬间的反馈 (说清"点了会发生什么"), 传 null 则不弹
     * @param status  状态区 (可为 null) — 同时写一行, 免得 Toast 一闪而过没看清
     */
    public static View.OnClickListener act(final Activity a, final String note,
                                           final TextView status, final Runnable body) {
        return v -> {
            if (note != null) {
                toast(a, note);
                if (status != null) status.setText("› " + note);
            }
            if (body == null) return;
            try {
                body.run();
            } catch (Throwable t) {
                SgmLog.i("ui", "点击执行失败: " + t);
                toast(a, "执行失败: " + human(t));
                if (status != null) status.setText("✗ 执行失败: " + human(t));
            }
        };
    }

    /**
     * 前置条件闸门 — **"点了没反应"的头号原因**。
     *
     * 用法: `Ui.act(a, null, s, Ui.gate(a, s, NEED_SHIZUKU, () -> doStart()))`
     * 不满足时: 弹一句人话 + 状态区写红 + (能跳设置的就跳过去), 返回 null ⇒ act 不执行 body。
     *
     * @return 条件满足返回 body, 否则返回 null
     */
    public static Runnable gate(Activity a, TextView status, String need, Runnable body) {
        String why = blockReason(a, need);
        if (why == null) return body;
        toast(a, why);
        if (status != null) {
            status.setText("✗ " + why);
            status.setTextColor(ERR);
        }
        SgmLog.i("ui", "拦截 [" + need + "]: " + why);
        offerFix(a, need);
        return null;
    }

    /* 需要的东西 */
    public static final String NEED_SHIZUKU_SERVER = "shizuku_server";   // Shizuku 服务在线
    public static final String NEED_SHIZUKU_PERM = "shizuku_perm";       // Shizuku 已授权
    public static final String NEED_CHANNEL = "channel";                 // 通道就绪(可截屏点击)
    public static final String NEED_OVERLAY = "overlay";                 // 悬浮窗权限

    /**
     * 检查缺失的前置条件, 返回**人话原因**; 全满足返回 null。
     *
     * ⚠️ 顺序不能反: Shizuku 服务 → 授权 → 通道绑定, 是三级递进, 先报最前面那个
     *    缺的 (否则用户对着"通道未就绪"去翻授权, 翻半天发现服务根本没开)。
     */
    public static String blockReason(Activity a, String need) {
        if (need == null) return null;
        if (NEED_SHIZUKU_SERVER.equals(need) && !ShizukuCtl.serverUp())
            return "Shizuku 服务没在跑 — 先去「使用指引」按步骤启动 Shizuku";
        if (NEED_SHIZUKU_PERM.equals(need) && !ShizukuCtl.granted())
            return "还没给本 App Shizuku 授权 — 点主页「① 授权 Shizuku」允许一下";
        if (NEED_CHANNEL.equals(need)) {
            if (!ShizukuCtl.serverUp()) return "Shizuku 服务没在跑 — 先去「使用指引」按步骤启动";
            if (!ShizukuCtl.granted()) return "还没给本 App Shizuku 授权 — 点「① 授权 Shizuku」";
            if (!ShizukuCtl.ready()) return "通道还没就绪 — 授权弹窗点完「允许」后会自动接上, 稍等 2~3 秒再点";
        }
        if (NEED_OVERLAY.equals(need) && !Settings.canDrawOverlays(a))
            return "没有「显示在应用上层」权限 — 现在送你去设置页, 打开开关再回来";
        return null;
    }

    /** 能一步跳到设置页的, 直接跳 (别让用户自己去翻系统设置) */
    private static void offerFix(Activity a, String need) {
        try {
            if (NEED_OVERLAY.equals(need)) {
                a.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + a.getPackageName())));
            } else if (NEED_SHIZUKU_SERVER.equals(need)) {
                // Shizuku 未启动 ⇒ 送去指引页 (那里有官方下载 + 开启流程)
                a.startActivity(new Intent(a, HelpActivity.class));
            } else if ((NEED_SHIZUKU_PERM.equals(need) || NEED_CHANNEL.equals(need))
                    && ShizukuCtl.serverUp() && !ShizukuCtl.granted()) {
                ShizukuCtl.requestPerm();
            }
        } catch (Throwable t) {
            SgmLog.i("ui", "跳转失败: " + t);
        }
        // 所有文件访问: 装机/换模板要用, 但**不拦功能** (自动退私有目录), 所以不给提示
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            /* 只记一句日志, 不打扰用户 */
            SgmLog.i("ui", "所有文件访问未授 — adb push / APK 自更新会受限");
        }
    }

    /* ---------------- 基础控件 ---------------- */

    /**
     * 可滚动页面骨架 — 修「不能滚动」。
     *
     * 病根: MainActivity 把 10 个按钮直接塞进 LinearLayout, 小屏/横屏下最后几个
     * 按钮被挤到屏幕外, 且**没有任何滚动容器** ⇒ 用户看到的就是"下面点不到/滚动不了"。
     * 所有页面统一走这里: 外层 ScrollView + 内容 LinearLayout (垂直, 带内边距),
     * 底部留 48dp 免得被手势条挡。
     *
     * @return [page(放进 setContentView), content(往这里 addView)]
     */
    public static View[] page(Activity a) {
        LinearLayout outer = new LinearLayout(a);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(BG);

        ScrollView sv = new ScrollView(a);
        sv.setFillViewport(true);
        LinearLayout content = new LinearLayout(a);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(a, 18), dp(a, 20), dp(a, 18), dp(a, 48));
        sv.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        outer.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return new View[]{outer, content};
    }

    /** 页面大标题 */
    public static TextView title(Activity a, String s) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(21f);
        t.setTextColor(TXT);
        t.setPadding(0, 0, 0, dp(a, 4));
        return t;
    }

    /** 小标题 (分段) */
    public static TextView section(Activity a, String s) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(15f);
        t.setTextColor(ACC);
        t.setPadding(0, dp(a, 22), 0, dp(a, 6));
        return t;
    }

    /** 灰字说明 */
    public static TextView hint(Activity a, String s) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(12.5f);
        t.setTextColor(SUB);
        t.setLineSpacing(dp(a, 3), 1f);
        t.setPadding(0, 0, 0, dp(a, 6));
        return t;
    }

    /** 正文 */
    public static TextView text(Activity a, String s, int color, float size) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setLineSpacing(dp(a, 3), 1f);
        return t;
    }

    /** 卡片容器 (白底圆角) */
    public static LinearLayout card(Activity a) {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(a, 14), dp(a, 12), dp(a, 14), dp(a, 12));
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD);
        g.setCornerRadius(dp(a, 10));
        g.setStroke(dp(a, 1), LINE);
        l.setBackground(g);
        return l;
    }

    /** 主按钮 */
    public static Button button(Activity a, String label) {
        Button b = new Button(a);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(14.5f);
        b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        b.setPadding(dp(a, 14), dp(a, 10), dp(a, 14), dp(a, 10));
        return b;
    }

    /** 主按钮 + 点击反馈一体化 (最常用) */
    public static Button button(Activity a, String label, String note,
                                TextView status, Runnable body) {
        Button b = button(a, label);
        b.setOnClickListener(act(a, note, status, body));
        add(a, b);
        return b;
    }

    /** 状态区 (反馈统一落点) */
    public static TextView statusBar(Activity a) {
        TextView t = new TextView(a);
        t.setTextSize(13f);
        t.setTextColor(SUB);
        t.setPadding(dp(a, 12), dp(a, 10), dp(a, 12), dp(a, 10));
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFFEEF1F6);
        g.setCornerRadius(dp(a, 8));
        t.setBackground(g);
        t.setText("› 就绪");
        return t;
    }

    /* ---------------- 布局小工具 ---------------- */

    public static void add(Activity a, View v) { add(a, v, dp(a, 6)); }

    /** 把控件加进容器并给下间距 — 免得到处手写 LayoutParams */
    public static void add(Activity a, View v, int bottomMargin) {
        ViewGroup parent = pending;
        if (parent == null) throw new IllegalStateException("先 Ui.begin(content)");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = bottomMargin;
        parent.addView(v, lp);
    }

    /* 串行构建用: 页面代码按顺序 add, 这里记住当前容器 */
    private static ViewGroup pending;

    public static void begin(ViewGroup content) { pending = content; }

    public static void end() { pending = null; }

    public static int dp(Context c, int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics());
    }

    /* ---------------- 跳转 / 提示 ---------------- */

    public static void toast(Context c, String s) {
        try { Toast.makeText(c, s, Toast.LENGTH_LONG).show(); } catch (Throwable ignored) { }
    }

    /** 打开外部链接 (下载 Shizuku / 官网 / 仓库) — 失败给原因, 不静默 */
    public static void openUrl(Activity a, TextView status, String url, String what) {
        if (TextUtils.isEmpty(url)) {
            toast(a, what + " 链接未配置 (config.shizuku)");
            if (status != null) status.setText("✗ " + what + " 链接未配置 — 见 config.shizuku");
            return;
        }
        try {
            toast(a, "打开 " + what + " …");
            if (status != null) status.setText("› 打开 " + what + " …\n" + url);
            a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable t) {
            toast(a, "打不开浏览器: " + human(t));
            if (status != null) status.setText("✗ 打不开浏览器 — 手动复制链接:\n" + url);
        }
    }

    /** 复制到剪贴板 (有些机器没浏览器/链接点不动, 给条退路) */
    public static void copy(Activity a, TextView status, String s) {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    a.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("sgmbot", s));
            toast(a, "已复制: " + s);
            if (status != null) status.setText("› 已复制到剪贴板: " + s);
        } catch (Throwable t) {
            toast(a, "复制失败: " + human(t));
        }
    }

    /** 异常 → 一句短话 (Toast 放不下长堆栈) */
    public static String human(Throwable t) {
        if (t == null) return "未知错误";
        String m = t.getMessage();
        if (m == null || m.length() == 0) m = t.getClass().getSimpleName();
        if (m.length() > 120) m = m.substring(0, 120) + "…";
        return m;
    }
}
