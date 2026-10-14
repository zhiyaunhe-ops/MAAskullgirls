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
        Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(
                new ComponentName(ctx, com.zhiyaunhe.sgmbot.shizuku.ShellService.class))
                // UserService 进程名后缀 (<pkg>:sgmbot); 缺它 bindUserService 抛
                // NPE "process name suffix must not be null" (Shizuku.java forAdd)
                .processNameSuffix("sgmbot")
                .version(1).tag("v1");
        Shizuku.bindUserService(args, conn);
        SgmLog.i("shizuku", "bindUserService...");
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

    /** sh -c 执行, 返回 stdout (UserService 未绑定/异常返回 "") */
    public static String exec(String cmd) {
        IShellService s = svc;
        if (s == null) return "";
        try { return s.exec(cmd); }
        catch (Throwable t) {
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
        if (rawOK) {                                   // ① 裸 RGBA (省 PNG 编解码)
            for (String d : dirs) {
                String p = d + "/frame.raw";
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
            String p = d + "/frame.png";
            // mkdir -p: 目录不存在时 screencap 报 "Error opening file" 且不自建目录
            String out = exec("mkdir -p \"" + d + "\" && screencap -p \"" + p + "\"");
            Bitmap b = decode(p);
            if (b != null) { capFails = 0; capOk = d; lastScale = b.getHeight() / (float) WORK_H; return b; }
            capOut = out;
        }
        if (capFails++ % 20 == 0) {   // 每 20 次报一次, 别刷屏
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
        exec("input tap " + Math.round(wx * s) + " " + Math.round(wy * s));
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
