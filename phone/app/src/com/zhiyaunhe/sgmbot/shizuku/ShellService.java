package com.zhiyaunhe.sgmbot.shizuku;

import android.os.Parcel;
import android.os.RemoteException;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * 运行在 Shizuku server 进程 (shell uid) 里 — 这就是"手和眼":
 *   exec("screencap -p /sdcard/sgm_settle/frame.png")  截屏
 *   exec("input tap x y")                              点击
 * 由 Shizuku.bindUserService 装载, 生命周期归 Shizuku server 管。
 */
public class ShellService extends IShellService.Stub {

    /** 单条命令的墙钟上限。正常量级: screencap 0.2~0.7s、input tap 0.04s; 只拦真挂死。 */
    private static final long EXEC_TIMEOUT_S = 6;

    /**
     * Shizuku 的 UserService **destroy 约定事务码**。
     *
     * <p>官方文档: 用 {@code unbindUserService} 只是解绑, **进程不会自动退出** —— 必须自己实现
     * destroy 并 {@code System.exit()}。重装 APK 后 {@code UserServiceArgs.version} 变化时,
     * Shizuku 会起新服务并对旧的调这个码; tag/version 都没变则**直接复用旧进程**
     * (2026-10-15 实栽: 新 App 连到旧 APK 的服务, 首帧截屏永久挂死)。
     *
     * <p>文档给的码两处写法不一 (aidl 里 16777114 / Java 里 16777115), 两个都接。
     */
    private static final int TX_DESTROY_JAVA = 16777115;
    private static final int TX_DESTROY_AIDL = 16777114;

    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        if (code == TX_DESTROY_JAVA || code == TX_DESTROY_AIDL) {
            System.exit(0);          // ⚠️ 本进程是 shell uid 的 UserService, 不能碰 App 侧日志
            return true;
        }
        return super.onTransact(code, data, reply, flags);
    }

    /**
     * sh -c 执行, 返回 stdout+stderr (与 aidl 注释一致)。
     *
     * <p>⚠️⚠️ 2026-10-15 修的<b>挂死 bug</b>（真机现场: 主循环卡住 90s+, HTTP 调试口却仍响应）:
     * 旧实现先 {@code while ((line = r.readLine()) != null)} 把 stdout 读干净, **然后**才
     * {@code p.waitFor(15, TimeUnit.SECONDS)} —— 看着有超时, 其实**够不着**:
     * {@code readLine()} 本身就是阻塞点, 子进程不退出/不关 stdout 它永不返回
     * (实测场景: 息屏时 {@code screencap} 被 SurfaceFlinger 挂住)。
     * 于是 binder 调用无限等, App 主循环永久卡死。
     *
     * <p>修法两条: ① 读 stdout 搬到**独立线程**, 与 {@code waitFor} 解耦;
     * ② 超时即 {@code destroyForcibly()} 杀掉 sh 并返回 {@code ERR: timeout} ——
     * 宁可让上层丢这一帧 (capture 拿到非空串 → 解码失败 → 返回 null → 丢帧继续),
     * 也不能让调用方无限等。
     */
    @Override
    public String exec(String cmd) throws RemoteException {
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd);
            pb.redirectErrorStream(true);                     // 合并 stderr (aidl 的承诺)
            p = pb.start();
            final Process proc = p;
            final StringBuffer sb = new StringBuffer(4096);   // 读线程写、主线程读 ⇒ 用同步容器
            Thread reader = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        BufferedReader r = new BufferedReader(
                                new InputStreamReader(proc.getInputStream(), "UTF-8"), 8192);
                        String line;
                        while ((line = r.readLine()) != null) {
                            if (sb.length() < 4_000_000) sb.append(line).append('\n');
                        }
                        r.close();
                    } catch (Exception ignored) {
                    }
                }
            }, "exec-reader");
            reader.setDaemon(true);
            reader.start();

            if (!p.waitFor(EXEC_TIMEOUT_S, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return "ERR: timeout " + EXEC_TIMEOUT_S + "s";
            }
            reader.join(300);                                  // 让读线程收尾 (进程已退, 通常几 ms)
            return sb.length() == 0 ? "" : sb.toString();
        } catch (Exception e) {
            if (p != null) {
                try { p.destroyForcibly(); } catch (Throwable ignored) { }
            }
            return "ERR: " + e;
        }
    }

    @Override
    public String ping() throws RemoteException {
        return "pong";
    }
}
