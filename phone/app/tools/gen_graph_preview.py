#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
离线生成判定图预览 — PC 上直接打开, 不用装 APK、不用起 8791 服务。

判定图页面本身是 App 进程内的 WebView 拉 `/graph` 画的。这个脚本把**同一套**
GRAPH_HTML 拿出来, 用当前 `assets/config.json` 跑一遍 `Graph` (纯 JVM, 与
GraphProbe 同机制), 把结果**内联**成自包含 HTML。

⚠️ 产物落在 `debug/` (已 gitignore): 它是快照, 改完 config 现生成即可。
   入库会过期 ⇒ 又变成"图与实不符"。

用法:
  python3 phone/app/tools/gen_graph_preview.py [输出路径]
默认:  <repo>/debug/graph_preview.html
"""
import io
import os
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))      # phone/app
REPO = os.path.dirname(os.path.dirname(ROOT))                           # repo 根
SRC = os.path.join(ROOT, 'src', 'com', 'zhiyaunhe', 'sgmbot')
JSON_REAL = os.path.join(REPO, 'vendor', 'jsonreal', 'build')
PLAT = os.path.join(REPO, 'vendor', 'android-build', 'platforms', 'android-34', 'android.jar')
JAVA = r'C:\Program Files\Java\jdk-1.8\bin\javac.exe'
JAVA_RUN = r'C:\Program Files\Java\jdk-1.8\bin\java.exe'
OUT = os.path.join(os.environ.get('TEMP', r'C:\Windows\Temp'), 'sgmgraphpreview')

STUBS = {
    'SgmLog.java': 'package com.zhiyaunhe.sgmbot;\n'
                   'public class SgmLog { public static void i(String t, String m) { } }\n',
    'BotService.java': 'package com.zhiyaunhe.sgmbot;\n'
                       'public class BotService { public static boolean isRunning() { return false; } }\n',
    'Trace.java': 'package com.zhiyaunhe.sgmbot;\n'
                  'import org.json.JSONObject;import org.json.JSONArray;\n'
                  'public class Trace { public static JSONObject byNode() { return new JSONObject(); }\n'
                  '  public static JSONArray recent(int n) { return new JSONArray(); } }\n',
    'Config.java': 'package com.zhiyaunhe.sgmbot;\n'
                   'import org.json.JSONObject;\n'
                   'public class Config { public static JSONObject MOCK = new JSONObject();\n'
                   '  public JSONObject read() { return MOCK; } }\n',
    'Dump.java': 'package com.zhiyaunhe.sgmbot;\n'
                 'import org.json.*;\nimport java.nio.file.*;\n'
                 'public class Dump {\n'
                 '  public static void main(String[] a) throws Exception {\n'
                 '    String txt = new String(Files.readAllBytes(Paths.get(a[0])), "UTF-8");\n'
                 '    Graph.load(new JSONObject(txt));\n'
                 '    System.out.println(Graph.toJson().toString());\n'
                 '  }\n'
                 '}\n',
}


def extract_html():
    src = io.open(os.path.join(SRC, 'DebugHttp.java'), encoding='utf-8').read()
    i = src.index('private static final String GRAPH_HTML =')
    end = '</script></body></html>";'
    j = src.index(end, i) + len(end)
    seg = src[i:j]
    import re
    return ''.join(re.findall(r'"((?:[^"\\]|\\.)*)"', seg))


def dump_graph_json(cfg_path):
    shutil.rmtree(OUT, ignore_errors=True)
    os.makedirs(OUT, exist_ok=True)
    srcs = [os.path.join(SRC, 'Graph.java')]
    for name, body in STUBS.items():
        p = os.path.join(OUT, name)
        io.open(p, 'w', encoding='utf-8').write(body)
        srcs.append(p)
    cp = os.pathsep.join([JSON_REAL, PLAT])
    r = subprocess.run([JAVA, '-J-Dfile.encoding=UTF-8', '-encoding', 'UTF-8', '-nowarn',
                        '-d', OUT, '-cp', cp] + srcs, capture_output=True)
    if r.returncode != 0:
        sys.stderr.write(r.stderr.decode('utf-8', 'replace'))
        sys.exit(1)
    r = subprocess.run([JAVA_RUN, '-Dfile.encoding=UTF-8', '-cp',
                        os.pathsep.join([OUT, JSON_REAL]),
                        'com.zhiyaunhe.sgmbot.Dump', cfg_path],
                       capture_output=True, encoding='utf-8', errors='replace')
    if r.returncode != 0 or not (r.stdout or '').strip():
        sys.stderr.write((r.stdout or '') + (r.stderr or ''))
        sys.exit(1)
    return r.stdout.strip().splitlines()[-1]


def main():
    out_path = sys.argv[1] if len(sys.argv) > 1 else os.path.join(REPO, 'debug', 'graph_preview.html')
    cfg = os.path.join(ROOT, 'assets', 'config.json')

    data = dump_graph_json(cfg)
    html = extract_html()

    # 页面原本每秒拉一次 /graph; 离线版把结果直接内联, 并停掉轮询。
    # ⚠️ 只用单引号、不含反斜杠 —— 与 GRAPH_HTML 同规矩 (script 原子解析)。
    patch = ("<script>(function(){"
             "clearInterval(timer);"
             "document.getElementById('auto').checked=false;"
             "D=" + data + ";"
             "document.getElementById('cnt').textContent='节点 '+D.nodes.length+' · 边 '+D.edges.length;"
             "document.getElementById('cur').textContent='当前: (离线快照 — 引擎未跑)';"
             "document.getElementById('ts').textContent='离线快照 · 源: assets/config.json';"
             "draw();drawTrace();"
             "})();</script>")
    if '</body></html>' not in html:
        print('[!] 页面结构变了: 找不到 </body></html>')
        sys.exit(1)
    html = html.replace('</body></html>', patch + '</body></html>')

    d = os.path.dirname(out_path)
    if d and not os.path.isdir(d):
        os.makedirs(d, exist_ok=True)
    io.open(out_path, 'w', encoding='utf-8').write(html)
    print('判定图预览: ' + out_path)
    print('  %d 字符 · %s' % (len(html), '内联成功' if data[:1] == '{' else '数据异常'))


if __name__ == '__main__':
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    main()
