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
 *     GET  /                 判定流程图页面 (节点/边全来自 config.graph)
 *     GET  /graph            graph+cursor+trace JSON (页面数据源)
 *     GET  /config           当前配置 (assets 默认 + 本地覆盖合并后)
 *     POST /config           body=JSON 写本地覆盖 + 热重载 (不用 adb push)
 *     GET  /update           查 GitHub latest release 有没有更新
 *     POST /update?apk=0|1   拉取并应用 (bundle 热更; apk=1 时再装 APK)
 *     GET  /status    — 引擎状态 JSON (running/mode/计数/state.json 内容)
 *     GET  /log       — app.log 全文
 *     GET  /screencap — 当前帧 JPEG (PC 侧直接看手机画面)
 *     GET  /tap?x=&y= — work 坐标(1280x576)点击 (PC 遥控"手", 验证/接管用)
 *     POST /reload    — 热读 config.json
 *     POST /trigger?action=start_nav|start|stop|reload|check_update|update — 同广播
 *
 * ⚠️ POST /config 会**改写**外部 config.json — 这是调试通道, 只绑回环地址;
 *    请求行/头逐字节读到空行为止, body 按 Content-Length 取 (不依赖 chunked)。
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
        String[] hp = readReq(s);            // [请求行, body]
        String req = hp[0], payload = hp[1];
        String[] parts = req.split(" ");
        String method = parts.length > 0 ? parts[0] : "";
        String path = parts.length > 1 ? parts[1] : "/";
        String body;

        try {
            if (path.startsWith("/graph.html") || path.equals("/") || path.equals("/index.html")) {
                replyHtml(s, GRAPH_HTML);
                return;
            }
            if (path.startsWith("/graph")) {
                body = Graph.toJson().toString(2);
            } else if (path.startsWith("/config")) {
                if ("POST".equals(method)) body = writeConfig(payload);
                else body = cfg().read().toString(2);
            } else if (path.startsWith("/update")) {
                if ("POST".equals(method)) {
                    boolean apk = !"0".equals(query(path, "apk"));
                    final boolean a = apk;
                    final StringBuilder sb = new StringBuilder();
                    Thread t = new Thread(() -> sb.append(
                            Update.apply(App.inst, cfg(), a)), "http-upd");
                    t.start();
                    t.join(60000);
                    body = sb.length() > 0 ? sb.toString() : "{\"ok\":false,\"msg\":\"超时\"}";
                } else {
                    body = Update.check(cfg()).toString(2);
                }
            } else if (path.startsWith("/status")) {
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
                body = "routes:\n"
                        + "  /                 判定流程图 (HTML, 节点/边全来自 config.graph)\n"
                        + "  /graph            graph+cursor+trace (JSON)\n"
                        + "  /config           GET 当前配置 (assets 默认 + 本地覆盖合并后)\n"
                        + "  /config           POST body=JSON 写本地覆盖并热重载\n"
                        + "  /update           GET 查 GitHub latest release; POST?apk=0|1 拉取\n"
                        + "  /status /log /screencap /tap?x=&y= /reload /trigger?action=";
            }
        } catch (Exception e) {
            body = "ERR: " + e;
        }
        reply(s, body);
    }

    /** 配置层: 服务没起过时 BotService.cfg() 是 null, 这里临时造一个 (读同一份文件) */
    private static Config cfg() {
        Config c = BotService.cfg();
        return c != null ? c : new Config(App.inst);
    }

    /** POST /config: 写本地覆盖 (adb 不用 push 也能改配置), 写完立刻热重载 */
    private static String writeConfig(String payload) {
        if (payload == null || payload.trim().length() == 0) return "empty body";
        try {
            JSONObject o = new JSONObject(payload);      // 非法 JSON 直接拒, 别写坏文件
            java.io.File f = new java.io.File(Paths.config());
            java.io.File p = f.getParentFile();
            if (p != null && !p.exists()) p.mkdirs();
            java.io.FileOutputStream os = new java.io.FileOutputStream(f);
            os.write(o.toString(2).getBytes(StandardCharsets.UTF_8));
            os.close();
            BotService.reload();
            return "written " + f.getAbsolutePath() + " (" + o.length() + " keys) + reloaded";
        } catch (Exception e) {
            return "ERR: " + e + " — body 必须是合法 JSON";
        }
    }

    private static void reply(Socket s, String body) throws Exception {
        reply(s, body, "text/plain; charset=utf-8");
    }

    private static void replyHtml(Socket s, String body) throws Exception {
        reply(s, body, "text/html; charset=utf-8");
    }

    private static void reply(Socket s, String body, String ct) throws Exception {
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        s.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: " + ct + "\r\n"
                + "Content-Length: " + out.length + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        s.getOutputStream().write(out);
        s.getOutputStream().flush();
    }

    /** 读请求行 + 头 + body (POST /config 要 body) */
    private static String[] readReq(Socket s) throws Exception {
        InputStream in = s.getInputStream();
        StringBuilder head = new StringBuilder(512);
        int c;
        while ((c = in.read()) != -1) {
            head.append((char) c);
            // 头结束 = 空行 (支持 \r\n\r\n 和 \n\n)
            if (head.length() >= 4
                    && (head.substring(head.length() - 4).equals("\r\n\r\n")
                        || head.substring(head.length() - 2).equals("\n\n"))) break;
            if (head.length() > 8192) break;
        }
        String h = head.toString();
        int nl = h.indexOf('\n');
        String line = nl > 0 ? h.substring(0, nl).trim() : h.trim();
        int len = -1;
        for (String ln : h.split("\n")) {
            int i = ln.indexOf(':');
            if (i > 0 && "content-length".equalsIgnoreCase(ln.substring(0, i).trim())) {
                try { len = Integer.parseInt(ln.substring(i + 1).trim()); } catch (Exception ignored) { }
            }
        }
        String body = "";
        if (len > 0) {
            byte[] b = new byte[Math.min(len, 1 << 20)];
            int off = 0;
            while (off < b.length) {
                int n = in.read(b, off, b.length - off);
                if (n <= 0) break;
                off += n;
            }
            body = new String(b, 0, off, "UTF-8");
        }
        return new String[]{line, body};
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

    /* ================= 判定流程图页面 (GET /) =================
     *
     * 节点/边/坐标**全部来自 config.graph**, 页面里没有一处写死"战斗/结算"——
     * 加一个判定点 = 在 config.json 的 graph.nodes 里加一条, 不必发版。
     * 高亮 = Graph.cursor() (循环当前走到哪), 节点下方数字 = 该节点最近一次判定的
     * NCC 峰值 (Trace.byNode), 差 0.02 卡在哪一眼可见。
     *
     * ⚠️ 这段是 Java 字符串里的 HTML/JS: 全文只用单引号 (免转义双引号),
     *    且**不含任何反斜杠** —— Java 层不会吞字符, 浏览器侧也不会因转义炸掉
     *    整个 <script> 块 (原子解析: 一处语法错 = 全页失效)。
     */
    private static final String GRAPH_HTML =
        "<!doctype html><html lang='zh'><head><meta charset='utf-8'>"
        + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
        + "<title>SGM 判定路径图</title><style>"
        + "*{box-sizing:border-box}"
        + "body{margin:0;background:#f6f7f9;color:#1c1e21;font:14px/1.5 system-ui,-apple-system,'Segoe UI',sans-serif}"
        + "header{padding:10px 16px;border-bottom:1px solid #d0d5dd;background:#fff;display:flex;gap:10px;align-items:center;flex-wrap:wrap}"
        + "h1{margin:0;font-size:16px;font-weight:600}"
        + ".pill{padding:2px 10px;border-radius:999px;background:#eef2ff;color:#3730a3;font-size:12px}"
        + ".cur{padding:2px 10px;border-radius:999px;background:#fff7ed;color:#9a3412;font-size:12px;font-weight:600}"
        + ".dim{color:#6b7280;font-size:12px}"
        + "main{display:flex;gap:14px;padding:14px;align-items:flex-start;flex-wrap:wrap}"
        + ".card{background:#fff;border:1px solid #d0d5dd;border-radius:10px;padding:12px}"
        + ".grow{flex:1 1 520px;min-width:320px}"
        + "svg{display:block;width:100%;height:auto}"
        + "rect.node{stroke:#aab2bd;stroke-width:1.5;rx:9;ry:9}"
        + "rect.cur{stroke:#f59e0b;stroke-width:3;animation:pulse 1.3s ease-in-out infinite}"
        + "@keyframes pulse{0%,100%{opacity:1}50%{opacity:.5}}"
        + "text.nl{font:600 13px system-ui,sans-serif;fill:#1c1e21;text-anchor:middle}"
        + "text.ns{font:11px ui-monospace,monospace;text-anchor:middle}"
        + "text.el{font:10px system-ui,sans-serif;fill:#6b7280;text-anchor:middle}"
        + "table{border-collapse:collapse;width:100%;font-size:12px}"
        + "td,th{padding:3px 6px;border-bottom:1px solid #eceef1;text-align:left;white-space:nowrap}"
        + ".ok{color:#16a34a}.no{color:#dc2626}"
        + "a{color:#2563eb}"
        + "label{font-size:12px;display:flex;gap:4px;align-items:center}"
        + "</style></head><body>"
        + "<header><h1>SGM 判定路径图</h1>"
        + "<span class='pill' id='cnt'>-</span>"
        + "<span class='cur' id='cur'>当前: -</span>"
        + "<span class='dim' id='ts'>-</span>"
        + "<label><input type='checkbox' id='auto' checked>自动刷新</label>"
        + "<span class='dim'><a href='/status'>/status</a> <a href='/log'>/log</a> "
        + "<a href='/config'>/config</a> <a href='/update'>/update</a></span></header>"
        + "<main><div class='card grow'><div id='empty' class='dim' style='display:none'>"
        + "图还没装载 (引擎没在跑, 或 config 里没有 graph 段)</div>"
        + "<svg id='g'></svg></div>"
        + "<div class='card' style='flex:0 1 380px;min-width:300px'>"
        + "<div class='dim' style='margin-bottom:6px'>最近判定流水 (新在下, 分数=NCC 峰值)</div>"
        + "<table id='tr'><tr><th>时间</th><th>节点</th><th>模板</th><th>分数</th><th>动作</th></tr></table>"
        + "</div></main>"
        + "<script>"
        + "var NS='http://www.w3.org/2000/svg';"
        + "var KIND={scan:'#e0e7ff',match:'#dcfce7',tap:'#fef3c7',chain:'#fce7f3',heal:'#fee2e2'};"
        + "var D={nodes:[],edges:[],by:{},trace:[]};"
        + "function el(t,a){var e=document.createElementNS(NS,t);for(var k in a){e.setAttribute(k,a[k]);}return e;}"
        + "function find(id){for(var i=0;i<D.nodes.length;i++){if(D.nodes[i].id===id)return D.nodes[i];}return null;}"
        + "function hhmm(t){var d=new Date(t);return ('0'+d.getHours()).slice(-2)+':'+('0'+d.getMinutes()).slice(-2)+':'+('0'+d.getSeconds()).slice(-2);}"
        + "function draw(){"
        + " var s=document.getElementById('g');s.innerHTML='';"
        + " var n=D.nodes||[];"
        + " document.getElementById('empty').style.display=n.length?'none':'block';"
        + " if(!n.length){return;}"
        + " var xs=[],ys=[],i;"
        + " for(i=0;i<n.length;i++){xs.push(+n[i].x||0);ys.push(+n[i].y||0);}"
        + " var x0=Math.min.apply(null,xs)-100,x1=Math.max.apply(null,xs)+100;"
        + " var y0=Math.min.apply(null,ys)-55,y1=Math.max.apply(null,ys)+55;"
        + " s.setAttribute('viewBox',x0+' '+y0+' '+(x1-x0)+' '+(y1-y0));"
        + " var defs=el('defs',{});var mk=el('marker',{id:'ar',viewBox:'0 0 10 10',refX:'9',refY:'5',markerWidth:'7',markerHeight:'7',orient:'auto-start-reverse'});"
        + " mk.appendChild(el('path',{d:'M0,0 L10,5 L0,10 z',fill:'#9aa4b2'}));defs.appendChild(mk);s.appendChild(defs);"
        + " var es=D.edges||[];"
        + " for(i=0;i<es.length;i++){var e=es[i];if(!e)continue;"
        + "  var a=find(e.from),b=find(e.to);if(!a||!b)continue;"
        + "  var rev=false,k;for(k=0;k<es.length;k++){if(es[k]&&es[k].from===e.to&&es[k].to===e.from){rev=true;break;}}"
        + "  var d;if(!rev){d='M'+a.x+','+a.y+' L'+b.x+','+b.y;}"
        + "  else{var mx=(+a.x + +b.x)/2,my=(+a.y + +b.y)/2,dx=b.x-a.x,dy=b.y-a.y,L=Math.sqrt(dx*dx+dy*dy)||1;d='M'+a.x+','+a.y+' Q'+(mx-dy/L*26)+','+(my+dx/L*26)+' '+b.x+','+b.y;}"
        + "  s.appendChild(el('path',{d:d,fill:'none',stroke:'#9aa4b2','stroke-width':'1.6','marker-end':'url(#ar)'}));"
        + "  if(e.on){var tx=(+a.x + +b.x)/2,ty=(+a.y + +b.y)/2;"
        + "   var tt=el('text',{x:tx,y:ty-4,class:'el'});tt.textContent=e.on;s.appendChild(tt);}}"
        + " for(i=0;i<n.length;i++){var o=n[i];"
        + "  var w=152,h=54,x=(+o.x)-w/2,y=(+o.y)-h/2;"
        + "  var isc=(D.cursor===o.id);"
        + "  s.appendChild(el('rect',{x:x,y:y,width:w,height:h,rx:9,ry:9,fill:KIND[o.kind]||'#eef2f7',class:isc?'node cur':'node'}));"
        + "  var t1=el('text',{x:o.x,y:(+o.y)-4,class:'nl'});t1.textContent=o.label||o.id;s.appendChild(t1);"
        + "  var b=D.byNode[o.id];var s2='-',cls='ns dim';"
        + "  if(b&&b.score>-2){s2=(b.score<0?'':(b.score).toFixed(3))+(b.hit?' / '+(b.act||'命中'):' 未过阈');cls=b.hit?'ns ok':'ns no';}"
        + "  var t2=el('text',{x:o.x,y:(+o.y)+14,class:cls});t2.textContent=s2;s.appendChild(t2);"
        + "  var t3=el('text',{x:o.x,y:(+o.y)+26,class:'el'});t3.textContent=o.kind||'';s.appendChild(t3);}}"
        + "function drawTrace(){"
        + " var tb=document.getElementById('tr');"
        + " while(tb.rows.length>1){tb.deleteRow(1);}"
        + " var tr=D.trace||[];"
        + " for(var i=tr.length-1;i>=0&&tb.rows.length<=40;i--){var o=tr[i];if(!o)continue;"
        + "  var r=tb.insertRow(-1);"
        + "  var c0=r.insertCell(0);c0.textContent=hhmm(o.t);"
        + "  var c1=r.insertCell(1);c1.textContent=o.node;"
        + "  var c2=r.insertCell(2);c2.textContent=o.tpl||'';"
        + "  var c3=r.insertCell(3);c3.textContent=o.score>-2?(o.score).toFixed(3):'-';c3.className=o.hit?'ok':'no';"
        + "  var c4=r.insertCell(4);c4.textContent=o.act||'';c4.className='dim';}}"
        + "function tick(){"
        + " var x=new XMLHttpRequest();x.open('GET','/graph',true);"
        + " x.onreadystatechange=function(){if(x.readyState!==4)return;"
        + "  if(x.status!==200){document.getElementById('ts').textContent='取 /graph 失败';return;}"
        + "  try{D=JSON.parse(x.responseText);}catch(e){return;}"
        + "  var n=(D.nodes&&D.nodes.length)||0;"
        + "  document.getElementById('cnt').textContent='节点 '+n+' · 边 '+((D.edges&&D.edges.length)||0);"
        + "  document.getElementById('cur').textContent='当前: '+(D.cursor||'(未跑)');"
        + "  document.getElementById('ts').textContent='更新于 '+hhmm(D.ts||Date.now());"
        + "  draw();drawTrace();};"
        + " x.send(null);}"
        + "var timer=setInterval(function(){if(document.getElementById('auto').checked){tick();}},1000);"
        + "tick();"
        + "</script></body></html>";
}
