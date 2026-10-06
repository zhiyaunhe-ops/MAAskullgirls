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
    public static final int WORK_H = 576;
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
        for (String d : dirs) {
            String p = d + "/frame.png";
            // mkdir -p: 目录不存在时 screencap 报 "Error opening file" 且不自建目录
            String out = exec("mkdir -p \"" + d + "\" && screencap -p \"" + p + "\"");
            Bitmap b = decode(p);
            if (b != null) { capFails = 0; return b; }
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

    /** 候选落盘目录: 先 Paths 根 (私有目录或已授权的 /sdcard/sgm_settle), 再兜 /sdcard/sgm_settle */
    private static String[] dirs() {
        String a = Paths.root();
        return a.equals(Paths.LEGACY) ? new String[]{a} : new String[]{a, Paths.LEGACY};
    }

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

    public void swipeWork(Bitmap frame, float x1, float y1, float x2, float y2, long ms) {
        float s = frame.getHeight() / (float) WORK_H;
        exec("input swipe " + Math.round(x1 * s) + " " + Math.round(y1 * s)
                + " " + Math.round(x2 * s) + " " + Math.round(y2 * s) + " " + ms);
    }
}
