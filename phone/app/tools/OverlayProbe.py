#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
悬浮条生命周期离线回归 —— 纯 JVM, 用一套最小 Android 桩跑真的 OverlayBar/OverlayCtl/BotService。

为什么值得专门做一个 (2026-10-11 用户报障):
    「我点结束应该是退出悬浮窗, 咋只是退出服务呢」
    根因不在 UI, 在**所有权判定**: onDestroy 只收「服务名下」的悬浮条
    (OverlayCtl.ownedByService()), 而最常见顺序是
      「先单独显示悬浮窗 → 再点开始」⇒ show() 把 svcOwned 置 false,
      之后 startService 时 attachForService 复用已有窗口、**不会**改 svcOwned
    ⇒ 服务退出时那条悬浮窗被"保留" ⇒ 点「结束」只看到服务停、窗口赖着不走。
    这条链没法在真机上稳定复现 (要连点 + 权限 + Shizuku), 所以钉成离线断言。

断言清单:
  A. 挂载/复用/所有权: show() 后 svcOwned=false; 再 attachForService 复用且**不改**所有权
  B. 结束收窗口 (核心): 上述状态下 BotService.end() ⇒ 悬浮条必须消失
  C. 暂停不收窗口: BotService.stop() ⇒ 悬浮条留着 (「暂停」≠「结束」)
  C2. 键名跟着状态走: 暂停后「开始」那颗键要变「继续」, 结束后变回「开始」
     (2026-10-11 用户: 「按钮的名字有歧义, 停止应该指的是暂停」)
  D. 服务自挂的窗口: attachForService 新建时 svcOwned=true ⇒ end() 也收
  E. hide() 语义: **同步**摘引用 (isShown 立刻 false) + removeView 延后一拍
     (避免在悬浮条自己的 onClick 里拆自己的窗口), 且幂等
  F. 无权限时 show() 给一句人话错误且不挂窗口
  G. 后台线程挂载不炸 (HTTP /trigger?action=overlay_show 走的就是这条)
  H. 源码级: 键名必须是「暂停/继续」, 动作名必须仍是 start/start_nav/stop/end
     (动作名是 adb 广播与 HTTP 的接口契约 —— 改文案不能改协议)

用法: python3 tools/OverlayProbe.py
"""
import io, os, re, shutil, subprocess, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, 'src', 'com', 'zhiyaunhe', 'sgmbot')
JSON_REAL = os.path.join(ROOT, '..', '..', 'vendor', 'jsonreal', 'build')
JAVA = r'C:\Program Files\Java\jdk-1.8\bin\javac.exe'
JAVA_RUN = r'C:\Program Files\Java\jdk-1.8\bin\java.exe'
OUT = os.path.join(os.environ.get('TEMP', r'C:\Windows\Temp'), 'sgmbot-overlay')

# --------------------------------------------------------------------------
# 最小 Android 桩。原则: 只放被真正引用到的成员, 窗口调用改成计数器好断言。
# --------------------------------------------------------------------------
STUBS = {

'android/annotation/SuppressLint.java': '''\
package android.annotation;
public @interface SuppressLint { String[] value(); }
''',

'android/util/DisplayMetrics.java': '''\
package android.util;
public class DisplayMetrics { public int widthPixels = 1080, heightPixels = 2340; }
''',

'android/graphics/PixelFormat.java': '''\
package android.graphics;
public class PixelFormat { public static final int TRANSLUCENT = -3; }
''',

'android/graphics/Bitmap.java': '''\
package android.graphics;
public class Bitmap { }
''',

'android/os/Build.java': '''\
package android.os;
public class Build { public static class VERSION { public static int SDK_INT = 34; } }
''',

'android/os/IBinder.java': '''\
package android.os;
public interface IBinder { }
''',

'android/os/Looper.java': '''\
package android.os;
public final class Looper {
    public static final Looper MAIN = new Looper();
    /** 探针控制: true = 假装在主线程 (myLooper()==getMainLooper()) */
    public static boolean onMain = true;
    /** 探针控制: Handler.post 是否立刻执行 (模拟"主线程闲着的", false = 留在队列里) */
    public static boolean autoRun = true;
    private Looper() { }
    public static Looper getMainLooper() { return MAIN; }
    public static Looper myLooper() { return onMain ? MAIN : null; }
}
''',

'android/os/Handler.java': '''\
package android.os;
import java.util.ArrayDeque; import java.util.ArrayList; import java.util.List;
public class Handler {
    public static final ArrayDeque<Runnable> QUEUE = new ArrayDeque<Runnable>();
    public Handler() { }
    public Handler(Looper l) { }
    public boolean post(Runnable r) { if (Looper.autoRun) r.run(); else QUEUE.add(r); return true; }
    /** 定时任务只入队不自动跑 —— tick 会自我重投, 自动跑会把 drain 变成死循环 */
    public boolean postDelayed(Runnable r, long ms) { QUEUE.add(r); return true; }
    public void removeCallbacks(Runnable r) { QUEUE.remove(r); }
    /** 跑掉**当前**队列里的任务 (快照式: 执行期间新投的下一轮再说) */
    public static int drain() {
        List<Runnable> cur = new ArrayList<Runnable>(QUEUE);
        QUEUE.clear();
        for (Runnable r : cur) r.run();
        return cur.size();
    }
    public static void reset() { QUEUE.clear(); }
}
''',

'android/content/SharedPreferences.java': '''\
package android.content;
public interface SharedPreferences {
    Editor edit();
    String getString(String k, String def);
    boolean getBoolean(String k, boolean def);
    interface Editor {
        Editor putString(String k, String v);
        Editor putBoolean(String k, boolean v);
        void apply();
    }
}
''',

'android/content/ComponentName.java': '''\
package android.content;
public class ComponentName { }
''',

'android/content/Intent.java': '''\
package android.content;
import android.net.Uri;
public class Intent {
    /* ⚠️ 探针把发出去的 intent **记下来**: APK 兜底那条路的核心断言就是"到底给系统发了
     *   什么" (action / data / type / flags) —— 这些在真机上完全不可见, 只有在这里才能
     *   变成可数的东西。真值取自 android 官方常量。 */
    public static Intent last;
    public String action;
    public Uri data;
    public String type;
    public int flags;
    public java.util.Map<String, Object> extras = new java.util.HashMap<String, Object>();

    public static final String ACTION_VIEW = "android.intent.action.VIEW";
    public static final String ACTION_INSTALL_PACKAGE = "android.intent.action.INSTALL_PACKAGE";
    public static final String ACTION_MAIN = "android.intent.action.MAIN";
    public static final String ACTION_BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED";
    public static final int FLAG_ACTIVITY_NEW_TASK = 0x10000000;
    public static final int FLAG_GRANT_READ_URI_PERMISSION = 0x00000001;
    public static final String EXTRA_NOT_UNKNOWN_SOURCE = "android.intent.extra.NOT_UNKNOWN_SOURCE";

    public Intent() { }
    public Intent(Context c, Class<?> k) { }
    public Intent(String a) { action = a; }
    public Intent(String a, Uri d) { action = a; data = d; }
    public Intent setData(Uri d) { data = d; return this; }
    public Intent setDataAndType(Uri d, String t) { data = d; type = t; return this; }
    public Intent addFlags(int f) { flags |= f; return this; }
    public Intent putExtra(String k, String v) { extras.put(k, v); return this; }
    public Intent putExtra(String k, boolean v) { extras.put(k, Boolean.valueOf(v)); return this; }
    public String getAction() { return action; }
    public Uri getData() { return data; }
    public String getType() { return type; }
    public boolean hasExtra(String k) { return extras.containsKey(k); }
    public String getStringExtra(String k) {
        Object v = extras.get(k);
        return v == null ? null : String.valueOf(v);
    }
}
''',

'android/net/Uri.java': '''\
package android.net;
public class Uri {
    private final String s;
    private Uri(String s) { this.s = s; }
    public static Uri parse(String x) { return new Uri(x); }
    public String getLastPathSegment() {
        int i = s.lastIndexOf('/');
        return i < 0 ? s : s.substring(i + 1);
    }
    public String toString() { return s; }
}
''',

'android/content/pm/PackageInfo.java': '''\
package android.content.pm;
public class PackageInfo {
    public String packageName = "com.zhiyaunhe.sgmbot";
    public int versionCode = 1;
    public String versionName = "0.1";
    public long firstInstallTime = 0;
    /** 本包最后一次被替换的时刻 (ms) —— "系统安装器那条路装上了没有"就靠它认账 */
    public long lastUpdateTime = 0;
}
''',

'android/content/pm/PackageManager.java': '''\
package android.content.pm;
/** 探针版包管理器: 两个可编程开关, 对应真机上问不到答案的两个状态。 */
public class PackageManager {
    /** >0 ⇒ getPackageInfo().lastUpdateTime 回这个值 (模拟"某时刻被替换过") */
    public static long lastUpdateTime = 0;
    /** 「安装未知应用」是否已授权 (API 26+) */
    public static boolean canRequestPackageInstalls = true;
    public PackageInfo getPackageInfo(String pkg, int flags) {
        PackageInfo pi = new PackageInfo();
        pi.packageName = pkg;
        pi.lastUpdateTime = lastUpdateTime;
        return pi;
    }
    public boolean canRequestPackageInstalls() { return canRequestPackageInstalls; }
}
''',

'android/app/Activity.java': '''\
package android.app;
import android.content.Context;
public class Activity extends Context { }
''',

'android/content/res/Resources.java': '''\
package android.content.res;
import android.util.DisplayMetrics;
public class Resources {
    public DisplayMetrics getDisplayMetrics() { return new DisplayMetrics(); }
}
''',

'android/content/Context.java': '''\
package android.content;
import android.app.NotificationManager;
import android.content.pm.PackageManager;
import android.view.WindowManager;
import java.util.HashMap; import java.util.Map;
public class Context {
    public static final String WINDOW_SERVICE = "window";
    public static final String NOTIFICATION_SERVICE = "notification";
    public static final int MODE_PRIVATE = 0;

    public WindowManager wm = new WindowManager();
    public NotificationManager nm = new NotificationManager();
    public PackageManager pm = new PackageManager();
    public String packageName = "com.zhiyaunhe.sgmbot";
    public int startServiceCount = 0, stopServiceCount = 0, startActivityCount = 0;
    /** 最后一次 startActivity 的 intent (APK 兜底那条路的断言就落在这上面) */
    public static Intent lastStarted;
    /** >0 ⇒ 接下来这么多次 startActivity 抛异常 (模拟"这台机器上没人认领这个 intent");
     *  置 1 = 只让主 intent 失败 (好验证退回老动作那条路), 置大值 = 两条都失败 */
    public static int failStart;

    public Object getSystemService(String name) {
        if (WINDOW_SERVICE.equals(name)) return wm;
        if (NOTIFICATION_SERVICE.equals(name)) return nm;
        return null;
    }
    public ComponentName startService(Intent i) { startServiceCount++; return null; }
    public boolean stopService(Intent i) { stopServiceCount++; return true; }

    public String getPackageName() { return packageName; }
    public PackageManager getPackageManager() { return pm; }
    public void startActivity(Intent i) {
        if (failStart > 0) { failStart--; throw new RuntimeException("probe: 没有界面认领 " + i.getAction()); }
        startActivityCount++;
        lastStarted = i;
        Intent.last = i;
    }

    private final Map<String, SharedPreferences> prefs = new HashMap<String, SharedPreferences>();
    public SharedPreferences getSharedPreferences(String name, int mode) {
        SharedPreferences p = prefs.get(name);
        if (p == null) { p = new MemPrefs(); prefs.put(name, p); }
        return p;
    }
    static class MemPrefs implements SharedPreferences {
        final Map<String, String> s = new HashMap<String, String>();
        final Map<String, Boolean> b = new HashMap<String, Boolean>();
        public Editor edit() {
            return new Editor() {
                public Editor putString(String k, String v) { s.put(k, v); return this; }
                public Editor putBoolean(String k, boolean v) { b.put(k, v); return this; }
                public void apply() { }
            };
        }
        public String getString(String k, String def) { String v = s.get(k); return v == null ? def : v; }
        public boolean getBoolean(String k, boolean def) { Boolean v = b.get(k); return v == null ? def : v; }
    }
}
''',

'android/app/Notification.java': '''\
package android.app;
import android.content.Context;
public class Notification {
    public static class Builder {
        public Builder(Context c) { }
        public Builder(Context c, String ch) { }
        public Builder setContentTitle(CharSequence t) { return this; }
        public Builder setContentText(CharSequence t) { return this; }
        public Builder setSmallIcon(int i) { return this; }
        public Builder setOngoing(boolean b) { return this; }
        public Notification build() { return new Notification(); }
    }
}
''',

'android/app/NotificationChannel.java': '''\
package android.app;
public class NotificationChannel {
    public NotificationChannel(String id, CharSequence name, int imp) { }
}
''',

'android/app/NotificationManager.java': '''\
package android.app;
public class NotificationManager {
    public static final int IMPORTANCE_LOW = 2;
    public int channelCount = 0;
    public void createNotificationChannel(NotificationChannel c) { channelCount++; }
}
''',

'android/R.java': '''\
package android;
public final class R {
    public static final class drawable { public static final int ic_media_play = 1; }
}
''',

'android/app/Service.java': '''\
package android.app;
import android.content.Context; import android.content.Intent; import android.content.res.Resources;
import android.os.IBinder;
public class Service extends Context {
    public static final int START_STICKY = 1;
    public int startForegroundCount = 0;
    public void onCreate() { }
    public void onDestroy() { }
    public int onStartCommand(Intent i, int f, int id) { return 0; }
    public IBinder onBind(Intent i) { return null; }
    public void startForeground(int id, Notification n) { startForegroundCount++; }
    public Resources getResources() { return new Resources(); }
}
''',

'android/provider/Settings.java': '''\
package android.provider;
import android.content.Context;
public class Settings {
    public static boolean allowOverlay = true;
    public static boolean canDrawOverlays(Context c) { return allowOverlay; }
    /** APK 兜底那条路缺权限时跳的就是这个页 (API 26+) */
    public static final String ACTION_MANAGE_UNKNOWN_APP_SOURCES =
            "android.settings.MANAGE_UNKNOWN_APP_SOURCES";
}
''',

'android/view/Gravity.java': '''\
package android.view;
public class Gravity {
    public static final int TOP = 0x30, START = 0x00800003, CENTER = 0x11;
}
''',

'android/view/MotionEvent.java': '''\
package android.view;
public class MotionEvent {
    public static final int ACTION_DOWN = 0, ACTION_UP = 1, ACTION_MOVE = 2;
    private final int action; private final float rx, ry;
    public MotionEvent(int a, float x, float y) { action = a; rx = x; ry = y; }
    public int getActionMasked() { return action; }
    public float getRawX() { return rx; }
    public float getRawY() { return ry; }
}
''',

'android/view/View.java': '''\
package android.view;
public class View {
    public static final int VISIBLE = 0, INVISIBLE = 4, GONE = 8;
    public int visibility = VISIBLE, width = 0, height = 0;
    public void setVisibility(int v) { visibility = v; }
    public void setOnClickListener(OnClickListener l) { }
    public void setOnTouchListener(OnTouchListener l) { }
    public void setBackgroundColor(int c) { }
    public void setPadding(int a, int b, int c, int d) { }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public interface OnClickListener { void onClick(View v); }
    public interface OnTouchListener { boolean onTouch(View v, MotionEvent e); }
}
''',

'android/view/WindowManager.java': '''\
package android.view;
import android.util.DisplayMetrics;
public class WindowManager {
    /** 探针断言用的计数器 —— 真机上这些是不可见的, 这里把它们变成可数的 */
    public static int addCount = 0, removeCount = 0, updateCount = 0;
    public static View lastAdded, lastRemoved;
    public static void reset() { addCount = removeCount = updateCount = 0; lastAdded = lastRemoved = null; }
    public void addView(View v, LayoutParams lp) { addCount++; lastAdded = v; }
    public void updateViewLayout(View v, LayoutParams lp) { updateCount++; }
    public void removeView(View v) { removeCount++; lastRemoved = v; }
    public Display getDefaultDisplay() { return new Display(); }

    public static class Display {
        public void getMetrics(DisplayMetrics m) { m.widthPixels = 1080; m.heightPixels = 2340; }
    }
    public static class LayoutParams {
        public static final int WRAP_CONTENT = -2;
        public static final int TYPE_PHONE = 2002;
        public static final int TYPE_APPLICATION_OVERLAY = 2038;
        public static final int FLAG_NOT_FOCUSABLE = 8;
        public int width, height, type, flags, gravity, x, y;
        public LayoutParams(int w, int h, int t, int f, int pf) {
            width = w; height = h; type = t; flags = f;
        }
    }
}
''',

'android/widget/TextView.java': '''\
package android.widget;
import android.content.Context; import android.view.View;
public class TextView extends View {
    public String text;
    public TextView(Context c) { }
    public void setText(CharSequence t) { text = t == null ? null : t.toString(); }
    public void setTextSize(float s) { }
    public void setTextColor(int c) { }
    public void setGravity(int g) { }
}
''',

'android/widget/Button.java': '''\
package android.widget;
import android.content.Context;
public class Button extends TextView { public Button(Context c) { super(c); } }
''',

'android/widget/LinearLayout.java': '''\
package android.widget;
import android.content.Context; import android.view.View;
public class LinearLayout extends View {
    public static final int HORIZONTAL = 0, VERTICAL = 1;
    public static final int MATCH_PARENT = -1, WRAP_CONTENT = -2;
    public LinearLayout(Context c) { }
    public void setOrientation(int o) { }
    public void addView(View v) { }
    public void addView(View v, LayoutParams lp) { }
    public static class LayoutParams {
        public static final int MATCH_PARENT = -1, WRAP_CONTENT = -2;
        public int width, height, leftMargin, topMargin;
        public LayoutParams(int w, int h) { width = w; height = h; }
    }
}
''',

'android/widget/FrameLayout.java': '''\
package android.widget;
import android.content.Context; import android.view.View;
public class FrameLayout extends View {
    public FrameLayout(Context c) { }
    public void addView(View v) { }
    public void addView(View v, LayoutParams lp) { }
    public static class LayoutParams {
        public int width, height;
        public LayoutParams(int w, int h) { width = w; height = h; }
    }
}
''',

# ---- 同包应用层桩 (只放被引用的成员) ----
'com/zhiyaunhe/sgmbot/App.java': '''\
package com.zhiyaunhe.sgmbot;
import android.content.Context;
public class App { public static Context inst; public static BotService service; }
''',

'com/zhiyaunhe/sgmbot/Store.java': '''\
package com.zhiyaunhe.sgmbot;
public class Store {
    public int rounds = 7;
    public static Store load() { return new Store(); }
}
''',

'com/zhiyaunhe/sgmbot/SgmLog.java': '''\
package com.zhiyaunhe.sgmbot;
public class SgmLog {
    public static int lines = 0;
    public static void i(String t, String m) { lines++; }
}
''',

'com/zhiyaunhe/sgmbot/Ui.java': '''\
package com.zhiyaunhe.sgmbot;
public class Ui {
    public static final int NEED_SHIZUKU_SERVER = 1;
    public static String blockReason(android.app.Activity a, int need) { return null; }
    public static String human(Throwable t) { return String.valueOf(t); }
}
''',

'com/zhiyaunhe/sgmbot/Config.java': '''\
package com.zhiyaunhe.sgmbot;
import android.content.Context; import org.json.JSONObject;
public class Config {
    public Config() { }
    public Config(Context c) { }
    public JSONObject read() { return new JSONObject(); }
}
''',

'com/zhiyaunhe/sgmbot/Vision.java': '''\
package com.zhiyaunhe.sgmbot;
import org.json.JSONObject;
public class Vision {
    public static void configure(JSONObject c) { }
    public static String modeText() { return "stub"; }
}
''',

'com/zhiyaunhe/sgmbot/Graph.java': '''\
package com.zhiyaunhe.sgmbot;
import org.json.JSONObject;
public class Graph {
    public static int invalidated = 0;
    public static void invalidate() { invalidated++; }
    public static void load(JSONObject c) { }
}
''',

'com/zhiyaunhe/sgmbot/ShizukuCtl.java': '''\
package com.zhiyaunhe.sgmbot;
import android.content.Context; import android.graphics.Bitmap;
public class ShizukuCtl {
    public static boolean ready() { return true; }
    public static boolean serverUp() { return true; }
    public static void tryBind(Context c) { }
    public Bitmap capture() { return null; }
}
''',

'com/zhiyaunhe/sgmbot/TplStore.java': '''\
package com.zhiyaunhe.sgmbot;
import android.content.Context;
public class TplStore {
    public TplStore(Context c) { }
    public void clear() { }
}
''',

'com/zhiyaunhe/sgmbot/TouchCtl.java': '''\
package com.zhiyaunhe.sgmbot;
public class TouchCtl { public TouchCtl(ShizukuCtl sh) { } }
''',

'com/zhiyaunhe/sgmbot/SettleLoop.java': '''\
package com.zhiyaunhe.sgmbot;
import java.util.concurrent.atomic.AtomicBoolean;
public class SettleLoop {
    public SettleLoop(ShizukuCtl sh, TplStore t, OverlayBar b) { }
    public void run(AtomicBoolean stop, Config cfg) { }
}
''',

'com/zhiyaunhe/sgmbot/Update.java': '''\
package com.zhiyaunhe.sgmbot;
import android.content.Context; import org.json.JSONObject;
public class Update {
    public static void autoCheck(Context c, Config cfg, boolean b) { }
    public static JSONObject check(Config cfg) { return new JSONObject(); }
    public static JSONObject apply(Context c, Config cfg, boolean all) { return new JSONObject(); }
}
''',
}

PROBE = r'''
package com.zhiyaunhe.sgmbot;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.WindowManager;

/**
 * 悬浮条生命周期回归。手驱 Service 生命周期 (onCreate/onDestroy 都是 public),
 * 用 WindowManager 桩的 add/remove 计数当判据 —— 真机上"悬浮窗还在不在"看不见摸不着,
 * 这里把它变成可数的。
 */
public class Probe {
    static int pass = 0, fail = 0;

    static void ck(String name, boolean ok, Object got) {
        if (ok) { pass++; System.out.println("  ok   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "  got=" + got); }
    }

    /** 新的一轮: 清空窗口计数 + 队列 + 单例状态 */
    static Context fresh() {
        OverlayCtl.hide();          // 清干净 (可能留了个窗口)
        Handler.reset();
        WindowManager.reset();
        Settings.allowOverlay = true;
        Looper.onMain = true;
        Looper.autoRun = true;
        Context c = new Context();
        App.inst = c;
        App.service = null;
        return c;
    }

    /** 手驱一次"服务起来" (不改 mode, 所以不会真起工作线程) */
    static BotService bootService(Context c) {
        BotService s = new BotService();
        s.onCreate();
        s.onStartCommand(null, 0, 1);     // in==null ⇒ 立刻 START_STICKY 返回, 不起线程
        return s;
    }

    /**
     * 直接摆布 BotService 的运行态私有字段 —— 只为把"暂停过没有"这个状态摆出来。
     * ⚠️ 不走 start()/onStartCommand 是因为那两条路都会**真的起引擎线程** (runBot),
     *    而探针里没有 SettleLoop, 结果不可控。反射改字段是这里唯一确定的做法。
     */
    static void setRunState(boolean running, boolean everRan) throws Exception {
        java.lang.reflect.Field fr = BotService.class.getDeclaredField("running");
        fr.setAccessible(true);
        fr.setBoolean(null, running);
        java.lang.reflect.Field fe = BotService.class.getDeclaredField("everRan");
        fe.setAccessible(true);
        fe.setBoolean(null, everRan);
    }

    public static void main(String[] a) throws Exception {
        Context c;

        // ============ A. 挂载 / 复用 / 所有权 ============
        System.out.println("== A 挂载/复用/所有权 ==");
        c = fresh();
        String e = OverlayCtl.show(c);
        ck("show() 成功 (独立于服务)", e == null, e);
        ck("窗口已挂载 addCount=1", WindowManager.addCount == 1, WindowManager.addCount);
        ck("isShown()=true", OverlayCtl.isShown(), null);
        ck("独立启动 ⇒ ownedByService()=false", !OverlayCtl.ownedByService(), null);

        BotService s1 = bootService(c);
        ck("服务起来时复用既有窗口 (不重复挂) addCount 仍 1",
                WindowManager.addCount == 1, WindowManager.addCount);
        // ★ 这一条就是用户报障的根因: 复用路径**不会**把所有权改成"服务名下"
        ck("复用后所有权仍是 false (根因锁定)", !OverlayCtl.ownedByService(), null);

        // ============ B. 结束必须收窗口 (核心) ============
        System.out.println("== B 结束 ⇒ 收悬浮窗 ==");
        BotService.end(c);
        ck("end() 后 isShown()=false (用户报的 bug)", !OverlayCtl.isShown(), null);
        ck("end() 后 ownedByService()=false", !OverlayCtl.ownedByService(), null);
        ck("end() 收到了 stopService", c.stopServiceCount == 1, c.stopServiceCount);
        int rmAfterEnd = WindowManager.removeCount;
        ck("removeView 已发生 ≥1", rmAfterEnd >= 1, rmAfterEnd);
        s1.onDestroy();                       // 服务紧接着被销毁: 不该再碰已收的窗口
        ck("onDestroy 不重复 removeView", WindowManager.removeCount == rmAfterEnd,
                WindowManager.removeCount);

        // ============ C. 暂停 ≠ 结束 ============
        System.out.println("== C 暂停不收窗口 ==");
        c = fresh();
        OverlayCtl.show(c);
        BotService s2 = bootService(c);
        BotService.stop(c);
        ck("stop() 后悬浮条仍在 (暂停≠结束)", OverlayCtl.isShown(), null);
        ck("stop() 不动窗口 removeCount=0", WindowManager.removeCount == 0, WindowManager.removeCount);
        ck("stop() 不收服务", c.stopServiceCount == 0, c.stopServiceCount);
        BotService.end(c);                    // 收尾
        ck("收尾 end() 后 isShown()=false", !OverlayCtl.isShown(), null);

        // ============ C2. 「暂停」与「继续」的键名 (2026-10-11 用户: 名字有歧义) ============
        // 那颗键的行为一直是"暂停" (只停循环, 窗口留着可续), 名字却叫"停止", 旁边还有一颗
        // 真正收窗口的「结束」—— 两颗都像"停"。现在名字与语义对齐, 且「开始」在暂停后变「继续」。
        System.out.println("== C2 「暂停」/「继续」键名 ==");
        c = fresh();
        OverlayCtl.show(c);
        OverlayBar bar = OverlayCtl.bar();
        ck("悬浮条已挂 (才能看键名)", bar != null && bar.isShown(), null);
        setRunState(false, false);             // 从没跑过
        bar.refreshStartLabel();
        ck("没跑过 ⇒ 键名「开始」", "开始".equals(bar.startLabel()), bar.startLabel());
        ck("没跑过 ⇒ paused()=false", !BotService.paused(), null);
        setRunState(true, true);               // 正在跑
        bar.refreshStartLabel();
        ck("在跑 ⇒ 仍是「开始」(点它是提醒'已在跑', 不是'继续')",
                "开始".equals(bar.startLabel()), bar.startLabel());
        setRunState(false, true);              // ★ 用户点了「暂停」
        bar.refreshStartLabel();
        ck("暂停后 ⇒ 键名变「继续」(名字跟着状态走)", "继续".equals(bar.startLabel()), bar.startLabel());
        ck("paused() 认得出暂停 (不是'没跑过')", BotService.paused(), null);
        setRunState(false, false);             // 用户点了「结束」/ 服务退出
        bar.refreshStartLabel();
        ck("结束后 ⇒ 回到「开始」(要重来, 不该写'继续')",
                "开始".equals(bar.startLabel()), bar.startLabel());
        BotService.end(c);
        Handler.drain();

        // ============ D. 服务自挂的窗口也归 end 收 ============
        System.out.println("== D 服务名下窗口 ==");
        c = fresh();
        BotService s3 = bootService(c);       // 没先开悬浮条 ⇒ 服务自己挂
        ck("服务自挂 addCount=1", WindowManager.addCount == 1, WindowManager.addCount);
        ck("服务自挂 ⇒ ownedByService()=true", OverlayCtl.ownedByService(), null);
        BotService.end(c);
        ck("end() 后 isShown()=false", !OverlayCtl.isShown(), null);
        s3.onDestroy();

        // ============ D2. 服务"意外"退出不该弄丢用户摆的条 ============
        System.out.println("== D2 服务意外退出保留用户窗口 ==");
        c = fresh();
        OverlayCtl.show(c);
        BotService s4 = bootService(c);
        s4.onDestroy();                        // 不是 end(), 是服务自己没了 (系统回收等)
        ck("服务意外退出后用户那条悬浮条仍在", OverlayCtl.isShown(), null);
        BotService.end(c);

        // ============ E. hide() 语义 ============
        System.out.println("== E hide() 语义 ==");
        c = fresh();
        OverlayCtl.show(c);
        ck("挂载 addCount=1", WindowManager.addCount == 1, WindowManager.addCount);
        Looper.autoRun = false;                // 让 post 留在队列里, 观察"延后一拍"
        OverlayCtl.hide();
        ck("hide() 同步摘引用: isShown() 立刻 false", !OverlayCtl.isShown(), null);
        ck("hide() 同步清 ownedByService()", !OverlayCtl.ownedByService(), null);
        ck("hide() 不同步 removeView (留给下一拍)", WindowManager.removeCount == 0,
                WindowManager.removeCount);
        int ran = Handler.drain();
        ck("drain 后 removeView=1 (窗口真的拆了)", WindowManager.removeCount == 1,
                WindowManager.removeCount + " ran=" + ran);
        OverlayCtl.hide();
        Handler.drain();
        ck("hide() 幂等: 不重复 removeView", WindowManager.removeCount == 1,
                WindowManager.removeCount);

        // ============ F. 无权限 ============
        System.out.println("== F 无悬浮窗权限 ==");
        c = fresh();
        Settings.allowOverlay = false;
        String e2 = OverlayCtl.show(c);
        ck("show() 返回人话错误", e2 != null && e2.contains("权限"), e2);
        ck("无权限不挂窗口", WindowManager.addCount == 0, WindowManager.addCount);

        // ============ G. 后台线程挂载 (HTTP /trigger 走的路径) ============
        System.out.println("== G 后台线程挂载 ==");
        c = fresh();
        Looper.onMain = false;                 // 模拟 debug-http worker 线程
        Looper.autoRun = true;                 // 主线程空闲 ⇒ 投过去立刻执行
        String e3 = OverlayCtl.show(c);
        ck("后台线程 show() 也能返回结果 (不再抛 Looper 错)", e3 == null, e3);
        ck("后台线程 show() 挂上了窗口", WindowManager.addCount == 1, WindowManager.addCount);
        Looper.onMain = true;
        OverlayCtl.hide();

        System.out.println("== " + pass + " passed, " + fail + " failed ==");
        System.exit(fail == 0 ? 0 : 1);
    }
}
'''


def strip_comments(src):
    """剥掉注释再做关键词断言。

    ⚠️ 这一条是踩出来的: 源码级断言**必须**先剥注释 —— 改完名字之后, javadoc 里
       天然会留下「「停止」改名「暂停」」这类说明文字, 不剥就会把**解释**判成回退,
       断言永远红 = 等于没有断言 (GraphProbe 那边也栽过同一个坑)。
    """
    out = re.sub(r'/\*.*?\*/', '', src, flags=re.S)
    return re.sub(r'//[^\n]*', '', out)


def src_check():
    """源码级回归 —— 钉住"用户明确要求过、又最容易被改回去"的东西。

    这些约定没有运行时特征: 键名改了功能照跑, 只是用户又要猜哪个键是哪个。
    """
    fails = []
    raw = strip_comments(io.open(os.path.join(SRC, 'OverlayBar.java'), encoding='utf-8').read())
    if '"暂停"' not in raw:
        fails.append('OverlayBar 没有「暂停」键 (名字与语义没对齐?)')
    if '"停止"' in raw:
        fails.append('OverlayBar 又出现了「停止」键 (那是"暂停"的旧名, 会与「结束」撞车)')
    if '"继续"' not in raw:
        fails.append('OverlayBar 没有「继续」文案 (暂停后键名不跟着状态走)')
    if 'paused()' not in raw:
        fails.append('「继续」的判据不是 BotService.paused() (拿 running 分辨不出"没跑过")')
    # 动作名是 adb 广播 / HTTP 的接口契约, 改文案绝不能顺手改它
    for act in ('"start"', '"start_nav"', '"stop"', '"end"'):
        if act not in raw:
            fails.append('动作名 %s 被动过 (那是 am broadcast / HTTP 的接口契约)' % act)
    return fails


def main():
    # Windows 下 python 默认 cp936 编 stdout, java 侧吐的是 UTF-8 ⇒ 断言文案乱码
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    shutil.rmtree(OUT, ignore_errors=True)
    for rel, body in STUBS.items():
        p = os.path.join(OUT, 'stubs', rel.replace('/', os.sep))
        os.makedirs(os.path.dirname(p), exist_ok=True)
        io.open(p, 'w', encoding='utf-8').write(body)
    p = os.path.join(OUT, 'src', 'com', 'zhiyaunhe', 'sgmbot', 'Probe.java')
    os.makedirs(os.path.dirname(p), exist_ok=True)
    io.open(p, 'w', encoding='utf-8').write(PROBE)

    srcs = [p,
            os.path.join(SRC, 'OverlayBar.java'),
            os.path.join(SRC, 'OverlayCtl.java'),
            os.path.join(SRC, 'BotService.java')]
    for r, _, fs in os.walk(os.path.join(OUT, 'stubs')):
        for f in fs:
            if f.endswith('.java'):
                srcs.append(os.path.join(r, f))

    classes = os.path.join(OUT, 'classes')
    os.makedirs(classes, exist_ok=True)
    r = subprocess.run([JAVA, '-J-Dfile.encoding=UTF-8', '-encoding', 'UTF-8', '-nowarn',
                        '-d', classes, '-cp', JSON_REAL] + srcs, capture_output=True)
    if r.returncode != 0:
        sys.stdout.write(r.stderr.decode('utf-8', 'replace'))
        sys.exit(1)
    r = subprocess.run([JAVA_RUN, '-Dfile.encoding=UTF-8', '-cp',
                        os.pathsep.join([classes, JSON_REAL]),
                        'com.zhiyaunhe.sgmbot.Probe'], capture_output=True)
    sys.stdout.write((r.stdout + r.stderr).decode('utf-8', 'replace'))

    print("== 源码级 (键名/动作名不许改回去) ==")
    fails = src_check()
    for f in fails:
        print("  FAIL " + f)
    if not fails:
        print("  ok   OverlayBar: 键名是「暂停/继续」, 动作名仍是 start/start_nav/stop/end")

    sys.exit(0 if (r.returncode == 0 and not fails) else 1)


if __name__ == '__main__':
    main()
