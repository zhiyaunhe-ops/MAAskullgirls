package com.zhiyaunhe.sgmbot;

import android.graphics.Bitmap;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * adb 控制面之 HTTP 通道 — 本地调试服务 (只绑 127.0.0.1, 对外不可达):
 *   PC: adb forward tcp:8791 tcp:8791  然后即可
 *     GET  /status    — 引擎状态 JSON (running/mode/计数/state.json 内容)
 *     GET  /log       — app.log 全文
 *     GET  /screencap — 当前帧 JPEG (PC 侧直接看手机画面)
 *     GET  /tap?x=&y= — work 坐标(1280x576)点击 (PC 遥控"手", 验证/接管用)
 *     POST /reload    — 热读 config.json
 *     POST /trigger?action=start_nav|start|stop|reload — 同广播
 */
public final class DebugHttp {
    private static final int PORT = 8791;
    private volatile Thread worker;

    public void start() {
        if (worker != null) return;
        worker = new Thread(this::serve, "debug-http");
        worker.setDaemon(true);
        worker.start();
        SgmLog.i("http", "debug server on 127.0.0.1:" + PORT);
    }

    private void serve() {
        try {
            ServerSocket ss = new ServerSocket(PORT, 4,
                    java.net.InetAddress.getByName("127.0.0.1"));
            while (true) {
                Socket s = ss.accept();
                try {
                    handle(s);
                } catch (Exception e) {
                    SgmLog.i("http", "req err: " + e);
                } finally {
                    try { s.close(); } catch (Exception ignored) { }
                }
            }
        } catch (Exception e) {
            SgmLog.i("http", "serve stopped: " + e);
        }
    }

    private void handle(Socket s) throws Exception {
        String req = readRequest(s);
        String[] parts = req.split(" ");
        String method = parts.length > 0 ? parts[0] : "";
        String path = parts.length > 1 ? parts[1] : "/";
        String body;

        try {
            if (path.startsWith("/status")) {
                body = status();
            } else if (path.startsWith("/log")) {
                body = readFile(Paths.logDir() + "app.log", "(empty)");
            } else if (path.startsWith("/screencap")) {
                Bitmap b = BotService.sh() != null ? BotService.sh().capture() : null;
                if (b == null) {
                    body = "capt fail";
                } else {
                    Bitmap w = Bitmap.createScaledBitmap(b,
                            Math.round(b.getWidth() * 576f / b.getHeight()), 576, true);
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    w.compress(Bitmap.CompressFormat.JPEG, 70, bo);
                    s.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: "
                            + bo.size() + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                    s.getOutputStream().write(bo.toByteArray());
                    s.getOutputStream().flush();
                    return;
                }
            } else if (path.startsWith("/tap") && "GET".equals(method)) {
                float wx = Float.parseFloat(query(path, "x"));
                float wy = Float.parseFloat(query(path, "y"));
                Bitmap b = BotService.sh() != null ? BotService.sh().capture() : null;
                if (b == null) { reply(s, "capt fail"); return; }
                BotService.sh().tapWork(b, wx, wy);
                body = "tapped " + wx + "," + wy;
                SgmLog.i("http", body);
            } else if (path.startsWith("/reload")) {
                BotService.trigger("reload");
                body = "reloaded";
            } else if (path.startsWith("/trigger")) {
                String action = query(path, "action");
                BotService.trigger(action);
                body = "trigger:" + action;
            } else {
                body = "routes: /status /log /screencap /tap?x=&y= /reload /trigger?action=";
            }
        } catch (Exception e) {
            body = "ERR: " + e;
        }
        reply(s, body);
    }

    private static void reply(Socket s, String body) throws Exception {
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        s.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\n"
                + "Content-Length: " + out.length + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        s.getOutputStream().write(out);
        s.getOutputStream().flush();
    }

    /** 读请求首行 (简单协议, 忽略头/body) */
    private static String readRequest(Socket s) throws Exception {
        InputStream in = s.getInputStream();
        StringBuilder sb = new StringBuilder(128);
        int prev = -1, c;
        while ((c = in.read()) != -1) {
            if (prev == '\r' && c == '\n') break;   // 首行结束 (\r\n)
            if (c != '\r') sb.append((char) c);
            prev = c;
            if (sb.length() > 2048) break;
        }
        return sb.toString();
    }

    private static String status() throws Exception {
        JSONObject st = readFileJson(Paths.logDir() + "state.json");
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
