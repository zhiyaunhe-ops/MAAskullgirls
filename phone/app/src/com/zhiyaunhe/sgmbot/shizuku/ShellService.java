package com.zhiyaunhe.sgmbot.shizuku;

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

    @Override
    public String exec(String cmd) throws RemoteException {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
            StringBuilder sb = new StringBuilder(4096);
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), "UTF-8"), 8192);
            String line;
            while ((line = r.readLine()) != null) {
                if (sb.length() < 4_000_000) sb.append(line).append('\n');
            }
            r.close();
            p.waitFor(15, TimeUnit.SECONDS);
            if (sb.length() == 0) return "";
            return sb.toString();
        } catch (Exception e) {
            return "ERR: " + e;
        }
    }

    @Override
    public String ping() throws RemoteException {
        return "pong";
    }
}
