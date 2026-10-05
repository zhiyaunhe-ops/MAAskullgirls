package com.zhiyaunhe.sgmbot;

import android.graphics.Bitmap;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * adb 控制面之 HTTP 通道 — 本地调试服务 (只绑 127.0.0.1, 对外不可达):
 *   PC: adb forward tcp:8791 tcp:8791  然后即可
 *     GET  /status    — 引擎状态 JSON (running/mode/计数/state.json 内容)
 *     GET  /log       — app.log 全文
 *     GET  /screencap — 当前帧 JPEG (PC 侧直接看手机画面, 不用截图)
 *     POST /reload    — 热读 config.json
 *     POST /trigger?action=start_nav — 同广播
 * 我 (ZCode) 调试时经 adb forward 完全接管, 不再需要点悬浮条。
 */
public final class DebugHttp {
    private static final int PORT = 8791;
    private volatile Thread worker;

    public void start() {
        if (worker != null) return;
        worker = new Thread(new Runnable() {
            @Override public void run() { serve(); }
        }, "debug-http");
        worker.setDaemon(true);
        worker.start();
        SgmLog.i("http", "debug server on 127.0.0.1:" + PORT);
    }

    private void serve() {
        try {
            ServerSocket ss = new ServerSocket(PORT, 4, java.net.InetAddress.getByName("127.0.0.1"));
            while (true) {
                Socket s = ss.accept();
                try {
                    handle(s);
                } catch (Exception e) {
                    SgmLog.i("http", "req err: " + e);
                } finally {
                    s.close();
                }
            }
        } catch (Exception e) {
            SgmLog.i("http", "serve stopped: " + e);
        }
    }

    private void handle(Socket s) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));
        String line = in.readLine();
        if (line == null) return;
        String[] parts = line.split(" ");
        String method = parts.length > 0 ? parts[0] : "";
        String path = parts.length > 1 ? parts[1] : "";
        // 读完请求头 (简单协议不需要 body)
        while ((line = in.readLine()) != null && !line.isEmpty()) { }

        String body;
        if (path.startsWith("/status")) {
            body = status();
        } else if (path.startsWith("/log")) {
            body = readFile(SgmLog.DIR + "app.log", "(empty)");
        } else if (path.startsWith("/screencap")) {
            Bitmap b = BotService.sh().capture();
            if (b == null) { body = "capt fail"; }
            else {
                Bitmap w = Bitmap.createScaledBitmap(b,
                        Math.round(b.getWidth() * 576f / b.getHeight()), 576, true);
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                w.compress(Bitmap.CompressFormat.JPEG, 70, bo);
                s.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: "
                        + bo.size() + "\r\n\r\n").getBytes());
                s.getOutputStream().write(bo.toByteArray());
                s.getOutputStream().flush();
                return;
            }
        } else if (path.startsWith("/reload")) {
            BotService.cfg().read();
            body = "reloaded";
        } else if (path.startsWith("/trigger")) {
            String action = query(path, "action");
            body = "trigger:" + action;
            SgmLog.i("http", "trigger " + action);
            BotService.trigger(action);
        } else {
            body = "routes: /status /log /screencap /reload /trigger?action=";
        }
        byte[] out = body.getBytes("UTF-8");
        s.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\n"
                + "Content-Length: " + out.length + "\r\n\r\n").getBytes());
        s.getOutputStream().write(out);
        s.getOutputStream().flush();
    }

    private static String status() throws Exception {
        JSONObject st = readFileJson(SgmLog.DIR + "state.json");
        st.put("http_ts", System.currentTimeMillis());
        return st.toString();
    }

    private static String query(String path, String key) {
        int q = path.indexOf('?');
        if (q < 0) return "";
        for (String kv : path.substring(q + 1).split("&")) {
            int eq = kv.indexOf('=');
            if (eq > 0 && key.equals(kv.substring(0, eq))) return kv.substring(eq + 1);
        }
        return "";
    }

    static String readFile(String p, String dflt) {
        try {
            File f = new File(p);
            if (!f.exists()) return dflt;
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            byte[] b = new byte[(int) f.length()];
            int n = in.read(b);
            in.close();
            return n <= 0 ? dflt : new String(b, "UTF-8");
        } catch (Exception e) {
            return dflt;
        }
    }

    static JSONObject readFileJson(String p) {
        try { return new JSONObject(readFile(p, "{}")); }
        catch (Exception e) { return new JSONObject(); }
    }
}
