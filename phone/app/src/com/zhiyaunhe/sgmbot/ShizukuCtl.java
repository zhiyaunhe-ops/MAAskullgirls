package com.zhiyaunhe.sgmbot;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.IBinder;

import com.zhiyaunhe.sgmbot.shizuku.IShellService;

import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;

import rikka.shizuku.Shizuku;

/**
 * 截屏/点击的单一入口 — 全部经 Shizuku UserService (shell uid) 执行。
 *
 * 生命周期: App 进程内常驻; binder 收到后 + 权限 GRANTED 后自动 bindUserService,
 * 绑定完成前 exec() 返回 "" (上层按帧丢失/无操作处理, 不炸)。
 *
 * 权限流: MainActivity 里 checkSelfPermission() != GRANTED → requestPermission(REQ) →
 *         addRequestPermissionResultListener 回调 → tryBind()。
 */
public final class ShizukuCtl {
    public static final int WORK_H = Vision.WORK_H;   // 真身归 Vision (离线探针好单独编译)
    /* 截屏落盘路径不写死 — 见 Paths (分区存储下 /sdcard 根目录默认读不到) */
    public static final int REQ_PERMISSION = 7001;

    private static volatile IShellService svc;
    private static volatile boolean bound;
    /** 上次发出 bindUserService 的时刻 (给 {@link #ensureBound} 判超时重发用)。 */
    private static volatile long bindAtMs;
    /** bind 发出后多久还没拿到 UserService 就重发。 */
    private static final long BIND_RETRY_MS = 6000;
    private static ServiceConnection conn;

    /* ---- 状态机 (MainActivity/BotService 轮询显示) ---- */

    /** Shizuku server 在不在 (binder 拿没拿到) */
    public static boolean serverUp() {
        try { return Shizuku.pingBinder(); } catch (Throwable t) { return false; }
    }

    /** 权限是否已授 */
    public static boolean granted() {
        try { return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED; }
        catch (Throwable t) { return false; }
    }

    /** UserService 是否绑定完成 (exec 可用) */
    public static boolean ready() { return svc != null; }

    /** binder 收到 + 已授权 → 绑 UserService (幂等) */
    public static void tryBind(Context ctx) {
        if (bound || !serverUp() || !granted()) return;
        bound = true;
        bindAtMs = System.currentTimeMillis();
        conn = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName n, IBinder b) {
                svc = IShellService.Stub.asInterface(b);
                SgmLog.i("shizuku", "UserService bound — 截屏/点击可用");
            }
            @Override public void onServiceDisconnected(ComponentName n) {
                svc = null;
                SgmLog.i("shizuku", "UserService disconnected");
            }
        };
        /* ⚠️ version 必须**随代码更新**。官方 (RikkaApps/Shizuku-API):
         *   「若 version 不匹配, 会启动新的 User Service, 并对旧的调用 destroy 方法」
         * 反过来 —— tag + version 都不变时 Shizuku 会**直接复用旧进程**, 于是新 APK 的
         * App 连上去跑的还是**旧 APK 的 ShellService 代码**。
         * 2026-10-15 实栽: 写死 version(1), 重装后新 App 连到旧 ShellService, 首帧
         * screencap 永久挂死 (旧实现 readLine 阻塞且 waitFor 够不着)。
         * 改用 APK 的 lastUpdateTime (重装必变) ⇒ Shizuku 自动起新服务、销毁旧的。
         * 本地包 versionCode 恒为 9999, 不能拿它当 version。 */
        long ver;
        try {
            ver = ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0).lastUpdateTime;
        } catch (Exception e) {
            ver = System.currentTimeMillis();
        }
        Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(
                new ComponentName(ctx, com.zhiyaunhe.sgmbot.shizuku.ShellService.class))
                // UserService 进程名后缀 (<pkg>:sgmbot); 缺它 bindUserService 抛
                // NPE "process name suffix must not be null" (Shizuku.java forAdd)
                .processNameSuffix("sgmbot")
                .version((int) (ver & 0x7FFFFFFFL)).tag("v1");
        Shizuku.bindUserService(args, conn);
        SgmLog.i("shizuku", "bindUserService...");
    }

    /**
     * 绑定卡住时重发 —— 见 {@link #BIND_RETRY_MS} 的说明。
     *
     * <p>⚠️ 2026-10-15 实栽 (真机现场: 截屏连续 2 分钟全失败, 日志 `err=no-file | out=` 而
     * `out` 为空 ⇒ `exec` 根本没执行 ⇒ `svc == null`, 但日志里明明有 "bindUserService...")。
     *
     * <p>{@link #tryBind} 在**回调之前**就把 {@code bound} 置 true —— 这是"一次只绑一次"的
     * 幂等保护, 但它假设 bind 一定会回调。实测这个假设不成立: `install -r` 之后 version 变了,
     * Shizuku 要**先销毁旧 UserService 进程、再起新的**, 这个窗口里发出的 bind 请求会被静默
     * 丢弃 (无 onServiceConnected、也无 onServiceDisconnected)。于是 {@code bound} 永远 true、
     * 永远不再绑 ⇒ 整个 App 功能全废 (截屏/点击全部静默失败), 只有重启 App 才能恢复。
     *
     * <p>这里做超时重发: 超过 {@link #BIND_RETRY_MS} 还没拿到 {@code svc} 就当作"没绑成功",
     * 清掉 {@code bound} 重新发一次。调用点放在 {@link #exec} 的空手返回处 —— 那是唯一的
     * 汇聚点, 任何"该干活却没绑上"的场景都会经过它。
     */
    public static void ensureBound() {
        if (svc != null) return;
        if (bound && System.currentTimeMillis() - bindAtMs < BIND_RETRY_MS) return;
        if (App.inst == null || !serverUp() || !granted()) return;
        SgmLog.i("shizuku", "UserService 超时未绑上, 重发 bind");
        bound = false;
        tryBind(App.inst);
    }

    /**
     * 发授权请求 (幂等; 已授权则直接 tryBind) — 主页/悬浮条/指引页共用。
     * 之前只有 MainActivity 里一份内联逻辑, 别的页面想补授权只能自己抄一遍。
     */
    public static void requestPerm() {
        try {
            if (!serverUp()) {
                SgmLog.i("shizuku", "服务没在跑 — 授权请求发不出去");
                return;
            }
            if (granted()) { tryBind(App.inst); return; }
            Shizuku.requestPermission(REQ_PERMISSION);
        } catch (Throwable t) {
            SgmLog.i("shizuku", "requestPermission 失败: " + t);
        }
    }

    /* ---- 原语 ---- */

    /**
     * 跨进程执行 shell —— **带调用侧超时**。
     *
     * <p>⚠️⚠️ 2026-10-18 实栽 (这是"日志全停但 GC 不停"的真因): UserService 跑在
     * `:sgmbot` 独立进程 (`processNameSuffix`), 那个进程**没有任何可见窗口/前台服务**
     * ⇒ MIUI 后台冻结会把它停掉; 而 App 侧 `s.exec()` 是**同步 binder 调用, 没有超时**
     * ⇒ 主循环永久卡在 capture, 表现就是: 进程活着、线程在跑、GC 每几秒一次、**日志一条不出**。
     * UserService 里虽有自己的 6s 超时, 但那是在**对端能执行**的前提下才有效 —— 冻住的进程
     * 连代码都不跑。
     *
     * 现在: 调用丢到线程池, 主线程最多等 {@link #EXEC_CALL_TIMEOUT_MS}; 超时就判定
     * UserService 卡死, **丢弃连接并重绑** (新调用走新进程), 本次返回 "ERR: timeout"
     * (capture 解码失败 → 丢帧继续, 循环不再挂死)。 */
    private static final long EXEC_CALL_TIMEOUT_MS = 12_000;
    private static final java.util.concurrent.ExecutorService EXEC_POOL =
            java.util.concurrent.Executors.newCachedThreadPool();

    public static String exec(final String cmd) {
        final IShellService s = svc;
        if (s == null) { ensureBound(); return ""; }   // ⚠️ 空手返回前先试着重绑 (见 ensureBound)
        java.util.concurrent.Future<String> f = null;
        try {
            f = EXEC_POOL.submit(new java.util.concurrent.Callable<String>() {
                @Override public String call() throws Exception { return s.exec(cmd); }
            });
            return f.get(EXEC_CALL_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException te) {
            if (f != null) f.cancel(true);
            SgmLog.i("shizuku", "exec 超时 " + EXEC_CALL_TIMEOUT_MS + "ms — UserService 疑似被冻结,"
                    + " 重绑连接 | cmd=" + (cmd.length() > 70 ? cmd.substring(0, 70) + "…" : cmd));
            synchronized (ShizukuCtl.class) {
                svc = null;
                bound = false;
            }
            ensureBound();
            return "ERR: exec timeout";
        } catch (Throwable t) {
            SgmLog.i("shizuku", "exec err: " + t);
            return "";
        }
    }

    /**
     * 截屏 → 实机分辨率 Bitmap; 失败 null (上层丢帧继续)。
     *
     * 两个坑 (2026-10-07 实测):
     *  - 目录不存在时 screencap 直接 "Error opening file" 不自建目录 ⇒ 先 mkdir -p;
     *  - 分区存储: 文件存在且非 0, 但 App 侧 FileInputStream 抛 EACCES
     *    ⇒ 必须自己读字节才知道是真失败还是权限被拒 (decodeFile 只回 null, 不给原因)。
     */
    public Bitmap capture() {
        String[] dirs = dirs();
        /* ⚠️⚠️ 每次调用**唯一文件名** (2026-10-16 实栽): 旧实现用固定 frame.raw/frame.png,
         * 而截屏有两个并发调用方 —— bot 主循环 + HTTP /screencap (WebUI 实时画面/远程采集)。
         * 时序: A 发出 `rm -f && screencap` 正在写 → B 的 `rm -f` 把 A 正在写的文件删了
         * → A 的 decode 读不到 ⇒ "no-file"。实测 1.5s 一次的远程轮询就能把主循环打出
         * 每几秒一次「截屏失败」, 且失败是**间歇性**的 (两条调用线偶尔才撞上), 极难归因。
         * 唯一名后谁也不动谁的文件; "不吃陈旧帧"的修复依然成立 —— 新文件不存在就是失败,
         * 不存在"上一帧还在盘上"的问题 (decode 成功/失败都会 delete, 自清理)。 */
        String uniq = String.valueOf(System.nanoTime());
        if (rawOK) {                                   // ① 裸 RGBA (省 PNG 编解码)
            for (String d : dirs) {
                String p = d + "/frame_" + uniq + ".raw";
                String out = exec("mkdir -p \"" + d + "\" && screencap \"" + p + "\"");
                Bitmap b = decodeRaw(p);
                if (b != null) { capFails = 0; capOk = d; lastScale = b.getHeight() / (float) WORK_H; return b; }
                capOut = out;
                if (rawUnsupported) {                  // 口径级失败 (帧头/格式/尺寸) → 永久回退
                    rawOK = false;
                    SgmLog.i("cap", "裸 RGBA 截屏不适用 (" + capErr + ") → 永久回退 PNG");
                    break;
                }
            }
        }
        for (String d : dirs) {                        // ② PNG (回退路径, 逐字未改)
            String p = d + "/frame_" + uniq + ".png";
            /* (mkdir -p 仍必要: 目录不存在时 screencap 报 "Error opening file" 且不自建目录。) */
            String out = exec("mkdir -p \"" + d + "\" && screencap -p \"" + p + "\"");
            Bitmap b = decode(p);
            if (b != null) { capFails = 0; capOk = d; lastScale = b.getHeight() / (float) WORK_H; return b; }
            capOut = out;
        }
        if (capFails++ % 2 == 0) {    // 诊断期每 2 次报一次 (原 20 次: 真机排障时看不见)
            StringBuilder sb = new StringBuilder("截屏失败 尝试=");
            for (String d : dirs) sb.append(d).append(' ');
            SgmLog.i("cap", sb.append("| err=").append(capErr)
                    .append(" | out=").append(capOut == null ? "" : capOut.trim()).toString());
        }
        return null;
    }

    /**
     * 裸 RGBA 解码 —— `screencap` 不带 `-p` 时输出 **帧头 + w*h*4 字节 RGBA_8888**,
     * 省掉设备侧 PNG 编码 + App 侧 PNG 解码两趟重活。
     *
     * 实测 (K70 Pro, 2026-10-14; 20 次均值, 单次子进程 CPU, /proc cutime+cstime):
     *   `screencap -p` (PNG 1.43MB) = **0.640 s** / `screencap` (裸 10.37MB) = **0.177 s**
     *   / `input tap` = 0.040 s ⇒ 换口径每次省 0.46s (3.6x), 代价是写读盘 1.4MB→10.4MB。
     *
     * 帧头长度**按文件长度反推**, 不写死: Android ≤10 是 3 个 u32 (w,h,format),
     * 11+ 多一个 colorspace ⇒ 12 或 16 (K70 Pro 实测 16)。任一条对不上 → rawUnsupported
     * 回退 PNG。RGBA_8888 的字节序 (R,G,B,A) 在小端机上正好等于 ARGB_8888 的 uint32 布局,
     * 所以直接 copyPixelsFromBuffer 即可, 不用逐像素搬。
     */
    private Bitmap decodeRaw(String path) {
        try {
            File f = new File(path);
            if (!f.exists() || f.length() == 0) { capErr = "no-file"; return null; }
            int len = (int) f.length();
            byte[] data = new byte[len];
            FileInputStream in = new FileInputStream(f);
            int n = 0, off = 0;
            while (off < len && (n = in.read(data, off, len - off)) > 0) off += n;
            in.close();
            f.delete();
            if (off < 16) { capErr = "short"; rawUnsupported = true; return null; }
            int w = le32(data, 0), h = le32(data, 4), fmt = le32(data, 8);
            if (w <= 0 || h <= 0 || w > 8192 || h > 8192) {
                capErr = "dim " + w + "x" + h; rawUnsupported = true; return null;
            }
            if (fmt != 1) {                            // 1 = HAL_PIXEL_FORMAT_RGBA_8888
                capErr = "fmt " + fmt; rawUnsupported = true; return null;
            }
            int px = w * h * 4;
            int hdr = off - px;                        // 反推帧头长度
            if (hdr != 12 && hdr != 16) {
                capErr = "hdr " + hdr + " (n=" + off + " px=" + px + ")"; rawUnsupported = true; return null;
            }
            capErr = "";
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            b.copyPixelsFromBuffer(ByteBuffer.wrap(data, hdr, px));
            return b;
        } catch (Throwable t) {
            capErr = t.toString();
            return null;
        }
    }

    private static int le32(byte[] a, int o) {
        return (a[o] & 0xFF) | ((a[o + 1] & 0xFF) << 8)
                | ((a[o + 2] & 0xFF) << 16) | ((a[o + 3] & 0xFF) << 24);
    }

    /** 当前截屏口径 (日志/状态页用) */
    public static String modeText() { return rawOK ? "raw" : "png"; }

    private static volatile boolean rawOK = true;         // 裸 RGBA 可用 (口径级失败后永久 false)
    private boolean rawUnsupported;                       // 本次失败是否属"口径不支持"

    /**
     * 候选落盘目录: 上次成功的排最前 (免得每帧多跑一次注定失败的 screencap),
     * 然后 Paths 根 (私有目录, 或已授权时的 /sdcard/sgm_settle), 最后兜 /sdcard/sgm_settle。
     */
    private static String[] dirs() {
        java.util.List<String> l = new java.util.ArrayList<String>(2);
        if (capOk != null) l.add(capOk);
        String a = Paths.root();
        if (!l.contains(a)) l.add(a);
        if (!l.contains(Paths.LEGACY)) l.add(Paths.LEGACY);
        return l.toArray(new String[0]);
    }

    private static volatile String capOk;

    private String capErr = "";
    private String capOut = "";
    private int capFails;

    private Bitmap decode(String path) {
        try {
            File f = new File(path);
            if (!f.exists() || f.length() == 0) { capErr = "no-file"; return null; }
            capErr = "";
            byte[] data = new byte[(int) f.length()];
            FileInputStream in = new FileInputStream(f);
            int n = 0, off = 0;
            while (off < data.length && (n = in.read(data, off, data.length - off)) > 0) off += n;
            in.close();
            f.delete();
            return BitmapFactory.decodeByteArray(data, 0, off);
        } catch (Throwable t) {
            capErr = t.toString();     // 典型: "java.io.FileNotFoundException: ... EACCES (Permission denied)"
            return null;
        }
    }

    /** work 坐标点击 (1280x576 基准 → 实机 scale 换算, 同 autojs tapWork) */
    public void tapWork(Bitmap frame, float wx, float wy) {
        float s = frame.getHeight() / (float) WORK_H;
        int px = Math.round(wx * s), py = Math.round(wy * s);
        /* 诊断 (2026-10-15): 真机上出现过"判据/坐标都对但点了没反应"的现场,
         * 当时无法区分是坐标换算错了还是注入通道没生效 ⇒ 把实际下发值打出来。
         * 帧高不是 1080 时 s 会跑偏 (例如切后台拿到竖屏帧 → s=4.17), 坐标直接飞出屏幕。 */
        SgmLog.i("tap", String.format("work(%.0f,%.0f) -> 物理(%d,%d) [帧高%d s=%.3f]",
                wx, wy, px, py, frame.getHeight(), s));
        exec("input tap " + px + " " + py);
    }

    /** 最近一次成功截屏推断出的缩放倍数 (实机高 / WORK_H); 还没截过 = 0 */
    private static volatile float lastScale;

    /**
     * 无帧点击 —— 快速连点模式 (SettleLoop.runFast) 用。
     *
     * <p>那里**刻意不逐帧截屏** (截屏是整条链最贵的一步, 连点器省掉它才快),
     * 于是拿不到 {@link Bitmap} 来算缩放倍数。缩放改从两处取:
     * ① 观察线程最近一次截屏的高度 (它一直在截, 正常情况下一直是新值);
     * ② 一次都没截过时 (冷启动的头几百毫秒) 兜底查一次 `wm size`。
     * 两条都拿不到就按 1 倍 —— 宁可点偏也不抛异常打断循环。
     */
    public void tapWork(float wx, float wy) {
        float s = lastScale;
        if (s <= 0) s = lastScale = probeScale();
        exec("input tap " + Math.round(wx * s) + " " + Math.round(wy * s));
    }

    /**
     * `wm size` → 缩放倍数。
     * ⚠️ 输出形如 "Physical size: 1080x2400\nOverride size: 1080x2400" ——
     *    必须取**最后一个** size 行 (Override 才是实际生效的那个),
     *    且数值对之间以 'x' 分隔、与中文/说明文字混排, 所以用正则挑出所有 WxH 取末个。
     */
    private static float probeScale() {
        String out = exec("wm size");
        if (out == null) return 1f;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d+)\\s*x\\s*(\\d+)").matcher(out);
        float h = 0;
        while (m.find()) h = Float.parseFloat(m.group(2));
        return h > 0 ? h / WORK_H : 1f;
    }

    public void swipeWork(Bitmap frame, float x1, float y1, float x2, float y2, long ms) {
        float s = frame.getHeight() / (float) WORK_H;
        exec("input swipe " + Math.round(x1 * s) + " " + Math.round(y1 * s)
                + " " + Math.round(x2 * s) + " " + Math.round(y2 * s) + " " + ms);
    }
}
