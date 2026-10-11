#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把 Graph.load + 真实 config.json 在 **纯 JVM** 上跑一遍, 断言:
  - escape 链真的展开成 6 个子节点 (不是丢干净, 也不是重复)
  - 每个节点的 val = 当前 config 的实际取值 (不是写死数字)
  - 布局有 x/y/lane/col
  - 运行时 cursor 锚点在节点表里找得到
用法: python3 tools/GraphProbe.py
"""
import io, json, os, re, subprocess, sys, shutil

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, 'src', 'com', 'zhiyaunhe', 'sgmbot')
JSON_REAL = os.path.join(ROOT, '..', '..', 'vendor', 'jsonreal', 'build')
PLAT = os.path.join(ROOT, '..', '..', '..', 'vendor', 'android-build',
                    'platforms', 'android-34', 'android.jar')
JAVA = r'C:\Program Files\Java\jdk-1.8\bin\javac.exe'
JAVA_RUN = r'C:\Program Files\Java\jdk-1.8\bin\java.exe'
OUT = os.path.join(os.environ.get('TEMP', r'C:\Windows\Temp'), 'sgmgraph')


def runtime_anchors():
    """从 SettleLoop.java 提取运行时打过的 Graph.cursor("...") 锚点。

    ⚠️ 别硬编码这份清单 —— 代码里新打一个锚点而图上没有, 是"图与实不符"的
       典型漂移 (实时高亮会静默失效)。这里直接扫源码, 加了锚点就自动纳入断言。
    """
    txt = io.open(os.path.join(SRC, 'SettleLoop.java'), encoding='utf-8').read()
    seen, out = set(), []
    for m in re.finditer(r'Graph\.cursor\("([^"]+)"\)', txt):
        if m.group(1) not in seen:
            seen.add(m.group(1))
            out.append(m.group(1))
    return out


def write_probe():
    """生成探针: 一张 JSON + Graph + 一个不依赖 Android 的最小 SgmLog/BotService"""
    anchors = runtime_anchors()
    if not anchors:
        print('[!] 未从 SettleLoop.java 提取到任何 Graph.cursor 锚点 — 正则或路径变了?')
        sys.exit(2)
    anchors_src = '{' + ', '.join('"%s"' % a for a in anchors) + '}'
    if not os.path.isdir(JSON_REAL):
        print('[!] 缺少真 org.json (%s) — 先跑 tools/build_jsonreal.sh' % JSON_REAL)
        sys.exit(2)
    shutil.rmtree(OUT, ignore_errors=True)
    os.makedirs(os.path.join(OUT, 'src'), exist_ok=True)

    # 桩: 只提供 Graph 会碰到的两个静态类
    open(os.path.join(OUT, 'src', 'SgmLog.java'), 'w', encoding='utf-8').write(
        'package com.zhiyaunhe.sgmbot;\n'
        'public class SgmLog { public static void i(String t, String m) { } }\n')
    open(os.path.join(OUT, 'src', 'BotService.java'), 'w', encoding='utf-8').write(
        'package com.zhiyaunhe.sgmbot;\n'
        'public class BotService { public static boolean isRunning() { return false; } }\n')
    open(os.path.join(OUT, 'src', 'Trace.java'), 'w', encoding='utf-8').write(
        'package com.zhiyaunhe.sgmbot;\n'
        'import org.json.JSONObject;import org.json.JSONArray;\n'
        'public class Trace { public static JSONObject byNode() { return new JSONObject(); }\n'
        '  public static JSONArray recent(int n) { return new JSONArray(); } }\n')
    # Graph.ensureLoaded(Config) 是"引擎没跑也要能看图"的入口 ⇒ 桩要能喂 config
    open(os.path.join(OUT, 'src', 'Config.java'), 'w', encoding='utf-8').write(
        'package com.zhiyaunhe.sgmbot;\n'
        'import org.json.JSONObject;\n'
        'public class Config { public static JSONObject MOCK = new JSONObject();\n'
        '  public JSONObject read() { return MOCK; } }\n')
    probe = r'''
package com.zhiyaunhe.sgmbot;
import org.json.*;
import java.nio.file.*;
public class Probe {
    static int pass = 0, fail = 0;
    static void ck(String name, boolean ok, Object got) {
        if (ok) { pass++; System.out.println("  ok   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "  got=" + got); }
    }
    public static void main(String[] a) throws Exception {
        String cfgPath = a[0];
        String txt = new String(Files.readAllBytes(Paths.get(cfgPath)), "UTF-8");
        JSONObject cfg = new JSONObject(txt);
        JSONObject raw = cfg.getJSONObject("graph");
        int rawN = raw.getJSONArray("nodes").length();
        // 静态节点里有 1 个是 expand 占位 ⇒ 实际 = (rawN-1) + escape 子项
        JSONArray items = cfg.getJSONObject("escape").getJSONArray("items");
        int wantN = rawN - 1 + items.length();

        // ---- 引擎没跑也要能看图 (Graph.ensureLoaded, javadoc 承诺的"静态全图") ----
        // 此前 load() 只在 BotService/SettleLoop 起跑时调用 ⇒ 没跑就没图, 与
        // "判定图指的是整个程序的, 不是运行到哪" (用户 2026-10-08) 直接冲突。
        System.out.println("== ensureLoaded (静态装载) ==");
        Config.MOCK = cfg;
        Graph.ensureLoaded(new Config());                 // 首次: 应把整张图装上
        int n1 = Graph.toJson().getJSONArray("nodes").length();
        Graph.ensureLoaded(new Config());                 // 再次: 幂等, 不重复不翻倍
        int n2 = Graph.toJson().getJSONArray("nodes").length();
        ck("未跑时也能装出整张图 nodes=" + n1, n1 == wantN, n1);
        ck("幂等, 重复调用不翻倍 nodes=" + n2, n2 == n1, n2);

        // 改配置 -> invalidate -> 再取图必须重建 (否则界面印的还是旧阈值 = 图与实不符)
        Graph.invalidate();
        int n3 = Graph.toJson().getJSONArray("nodes").length();
        ck("invalidate 后图作废", n3 == 0, n3);
        Graph.ensureLoaded(new Config());
        int n4 = Graph.toJson().getJSONArray("nodes").length();
        ck("作废后能按新配置重建 nodes=" + n4, n4 == wantN, n4);

        Graph.load(cfg);
        JSONObject j = Graph.toJson();
        JSONArray ns = j.getJSONArray("nodes");
        JSONArray es = j.getJSONArray("edges");
        System.out.println("== load ==");
        System.out.println("  nodes=" + ns.length() + " edges=" + es.length()
                + " lanes=" + j.getJSONArray("lanes").length());
        ck("node count = static(" + (rawN - 1) + ") + escape 子项(" + items.length() + ")",
                ns.length() == wantN, ns.length());

        // escape 链展开: 每个子项一个 esc_<tpl> 节点, 且 th 取自 escape.items
        for (int i = 0; i < items.length(); i++) {
            String name = items.getJSONObject(i).optString("tpl");
            JSONObject found = null;
            for (int k = 0; k < ns.length(); k++)
                if (ns.getJSONObject(k).optString("id").equals("esc_" + name))
                    found = ns.getJSONObject(k);
            ck("escape expanded: " + name, found != null, "null");
            if (found != null) {
                double want = items.getJSONObject(i).optDouble("th", -1);
                ck("  th backfilled: " + name + "=" + want,
                        Math.abs(found.optDouble("val", -9) - want) < 1e-9,
                        found.opt("val"));
            }
        }

        // 阈值回填: 关键节点的 val 必须等于 config 当前值
        JSONObject id_of = new JSONObject();
        for (int k = 0; k < ns.length(); k++) id_of.put(ns.getJSONObject(k).optString("id"), ns.getJSONObject(k));
        ck("battle.val = " + cfg.getJSONObject("thresholds").optDouble("min_sim"),
                Math.abs(id_of.getJSONObject("battle").optDouble("val", -9)
                        - cfg.getJSONObject("thresholds").optDouble("min_sim")) < 1e-9,
                id_of.getJSONObject("battle").opt("val"));
        ck("stall.val = " + cfg.getJSONObject("timing").optInt("stall_sec"),
                id_of.getJSONObject("stall").optInt("val", -9)
                        == cfg.getJSONObject("timing").optInt("stall_sec"),
                id_of.getJSONObject("stall").opt("val"));
        ck("fallbk.val = " + cfg.optInt("result_fallback"),
                id_of.getJSONObject("fallbk").optInt("val", -9) == cfg.optInt("result_fallback"),
                id_of.getJSONObject("fallbk").opt("val"));
        ck("cap0.val = " + cfg.optInt("daily_cap"),
                id_of.getJSONObject("cap0").optInt("val", -9) == cfg.optInt("daily_cap"),
                id_of.getJSONObject("cap0").opt("val"));
        ck("maxmin.val = " + cfg.getJSONObject("run").optInt("max_minutes"),
                id_of.getJSONObject("maxmin").optInt("val", -9)
                        == cfg.getJSONObject("run").optInt("max_minutes"),
                id_of.getJSONObject("maxmin").opt("val"));
        ck("bstl.val = " + cfg.getJSONObject("timing").optInt("battle_stall_sec"),
                id_of.getJSONObject("bstl").optInt("val", -9)
                        == cfg.getJSONObject("timing").optInt("battle_stall_sec"),
                id_of.getJSONObject("bstl").opt("val"));
        // 暂停时段两个都空 ⇒ 显示未启用
        ck("pause not set -> 未启用",
                "未启用".equals(id_of.getJSONObject("pause").optString("val", "")),
                id_of.getJSONObject("pause").opt("val"));

        // 布局
        int noXY = 0;
        for (int k = 0; k < ns.length(); k++) {
            JSONObject n = ns.getJSONObject(k);
            if (!n.has("x") || !n.has("y") || !n.has("lane")) noXY++;
        }
        ck("all nodes have x/y/lane", noXY == 0, noXY + " missing");

        // 同泳道内 col 不重复
        JSONObject seen = new JSONObject();
        boolean dup = false;
        for (int k = 0; k < ns.length(); k++) {
            JSONObject n = ns.getJSONObject(k);
            String key = n.optInt("lane") + "|" + n.optInt("col");
            if (seen.has(key)) dup = true;
            seen.put(key, 1);
        }
        ck("no duplicate lane|col", !dup, "dup");

        // 边不能悬空
        int dangling = 0;
        for (int k = 0; k < es.length(); k++) {
            JSONObject e = es.getJSONObject(k);
            if (!id_of.has(e.optString("from")) || !id_of.has(e.optString("to"))) dangling++;
        }
        ck("no dangling edge", dangling == 0, dangling);

        // 运行时锚点必须都在图里 (否则实时高亮失效)
        String[] anchors = __ANCHORS__;
        int miss = 0;
        for (String s : anchors) if (!id_of.has(s)) { miss++; System.out.println("     missing anchor " + s); }
        ck("__N__ runtime anchors present", miss == 0, miss);

        // ---- 快速连点落点 (2026-10-11 用户报障「会点到 x3 那里」) ----
        // 修法 = 落点绕开 HUD 挡块。两件事都要钉住:
        //   ① config.fast.avoid 与代码常量 WorkPoint.DEFAULT_AVOID 不许漂移
        //      (漂了会"配置里有、代码兜底是另一套", 谁生效看运气);
        //   ② WorkPoint.pick 的**行为**确实把落点从中槽中心挪出去了, 且仍在槽内。
        System.out.println("== fast 落点 (绕开挡块) ==");
        JSONObject fast = cfg.getJSONObject("fast");
        JSONArray av = fast.getJSONArray("avoid");
        ck("fast.avoid 条数 = WorkPoint.DEFAULT_AVOID",
                av.length() == WorkPoint.DEFAULT_AVOID.length, av.length());
        for (int i = 0; i < Math.min(av.length(), WorkPoint.DEFAULT_AVOID.length); i++) {
            JSONArray e = av.getJSONArray(i);
            int[] d = WorkPoint.DEFAULT_AVOID[i];
            boolean same = e.length() >= 4 && (int) e.optDouble(0) == d[0] && (int) e.optDouble(1) == d[1]
                    && (int) e.optDouble(2) == d[2] && (int) e.optDouble(3) == d[3];
            ck("fast.avoid[" + i + "] = 代码常量 " + d[0] + "," + d[1] + "," + d[2] + "," + d[3], same, e);
        }
        JSONArray slots = cfg.getJSONArray("btn_rois");
        int[] mid = new int[]{slots.getJSONArray(1).optInt(0), slots.getJSONArray(1).optInt(1),
                slots.getJSONArray(1).optInt(2), slots.getJSONArray(1).optInt(3)};
        float mx = mid[0] + mid[2] / 2f, my = mid[1] + mid[3] / 2f;
        java.util.List<int[]> defAv = WorkPoint.defaultAvoid();
        ck("中槽中心 (" + (int) mx + "," + (int) my + ") 本来落在挡块里 (= 被修的那个 bug)",
                WorkPoint.inside(defAv, mx, my), "不在");
        float[] p = WorkPoint.pick(mid, defAv, fast.optInt("avoid_gap", WorkPoint.DEFAULT_GAP));
        ck("pick 后落点 (" + (int) p[0] + "," + (int) p[1] + ") 已离开挡块  [" + WorkPoint.describe((int) p[2]) + "]",
                !WorkPoint.inside(defAv, p[0], p[1]), p[0] + "," + p[1]);
        ck("pick 后落点仍在结算中槽内", WorkPoint.inside(mid, p[0], p[1]), p[0] + "," + p[1]);

        System.out.println("== " + pass + " passed, " + fail + " failed ==");
        System.exit(fail == 0 ? 0 : 1);
    }
}
'''
    probe = probe.replace('__ANCHORS__', anchors_src).replace('__N__', str(len(anchors)))
    open(os.path.join(OUT, 'src', 'Probe.java'), 'w', encoding='utf-8').write(probe)
    return os.path.join(OUT, 'src', 'Probe.java')


def src_check():
    """源码级静态检查 —— 钉住两件**用户明确要求过、最容易被改回去**的事:

    ① **判定图不许再用 WebView**: 用户原话「别再跳转什么网页了 真的很丑」
       (2026-10-11)。它换成 GraphView 自绘之后, 一旦有人图省事改回 loadUrl,
       手机端立刻打回"网页字号 + 白底 + 一排外链"的老样子。
    ② **主页不许再有运行控制键**: 用户原话「这都是在悬浮窗里面可以单独点的呀,
       你就不用在那里再弄一次了」。加回来的直接后果是同一个动作两条路, 而两条路
       的前置检查不同 (Ui.gate vs Ui.blockReason) ⇒ "主页点不了、悬浮条能点"。

    这两条**没有运行时特征** (纯粹是"那段代码在不在"), 所以只能扫源码。
    """
    fails = 0

    def ck(name, cond, got=''):
        nonlocal fails
        if cond:
            print('  ok   ' + name)
        else:
            fails += 1
            print('  FAIL ' + name + '    ' + str(got))

    def rd(p):
        """读源码并**剥掉注释**再查。

        这一步是必需的: 新代码的注释里几乎一定会提到旧实现 (比如"旧实现是内嵌
        WebView 打开 http://127.0.0.1:8791/"), 不剥就会把注释判成"回退",
        于是这条断言永远红 —— 永远红的断言等于没有断言 (没人再信它)。
        """
        raw = io.open(os.path.join(SRC, p), encoding='utf-8').read()
        raw = re.sub(r'/\*.*?\*/', '', raw, flags=re.S)
        raw = re.sub(r'//[^\n]*', '', raw)
        return raw

    print('== 源码检查: 用户要求过的改动不许回退 ==')
    ga = rd('GraphActivity.java')
    ck('GraphActivity 不再用 WebView', 'WebView' not in ga)
    ck('GraphActivity 不再 loadUrl', 'loadUrl' not in ga)
    # 只查"不再自己连 8791" —— 帮助页签里**解释** 8791 是什么是应该的
    ck('GraphActivity 不再自己连 8791 (无 Socket/InetSocketAddress)',
       'InetSocketAddress' not in ga and 'new java.net.Socket' not in ga)
    ck('GraphActivity 用原生 GraphView 画', 'new GraphView(' in ga)
    ck('GraphActivity 直接读同进程 Graph.toJson()', 'Graph.toJson()' in ga)
    ma = rd('MainActivity.java')
    for kw in ('启动服务', '停止引擎', '结束服务'):
        ck('主页没有「%s」按钮' % kw, kw not in ma)
    ck('主页有「悬浮窗」启停键', 'overlayBtn' in ma)
    gv = rd('GraphView.java')
    ck('GraphView 继承 View (自绘, 非 WebView)', 'extends View' in gv)
    ck('GraphView 的几何全部走 GraphLayout', 'GraphLayout.' in gv and '185' not in gv)
    return fails


def main():
    # Windows 下 python 默认按 cp936 编 stdout, 而 java 侧吐的是 UTF-8 ⇒ 断言文案乱码,
    # 用户看结论只能靠猜。统一按 UTF-8 出。
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    static_fails = src_check()
    probe = write_probe()
    srcs = [probe,
            os.path.join(SRC, 'Graph.java'),
            os.path.join(SRC, 'WorkPoint.java'),      # fast 落点几何 (纯 java.util, 无 android 依赖)
            os.path.join(SRC, 'GraphLayout.java'),    # 判定图几何 (同上) — Graph.layout 引它算 x/y
            os.path.join(OUT, 'src', 'SgmLog.java'),
            os.path.join(OUT, 'src', 'BotService.java'),
            os.path.join(OUT, 'src', 'Trace.java'),
            os.path.join(OUT, 'src', 'Config.java')]
    cp = os.pathsep.join([JSON_REAL, PLAT])
    # ⚠️ -Dfile.encoding=UTF-8 是必需的: Windows 上 JVM 的 System.out 默认按 cp936 码页
    #    输出, 而这边按 UTF-8 解码 ⇒ 断言文案全成乱码, 用户根本读不出结论。
    r = subprocess.run([JAVA, '-J-Dfile.encoding=UTF-8', '-encoding', 'UTF-8', '-nowarn',
                        '-d', OUT, '-cp', cp] + srcs, capture_output=True)
    if r.returncode != 0:
        sys.stdout.write(r.stderr.decode('utf-8', 'replace'))
        sys.exit(1)
    cfg = os.path.join(ROOT, 'assets', 'config.json')
    r = subprocess.run([JAVA_RUN, '-Dfile.encoding=UTF-8', '-cp',
                        os.pathsep.join([OUT, JSON_REAL]),
                        'com.zhiyaunhe.sgmbot.Probe', cfg], capture_output=True)
    out = (r.stdout + r.stderr).decode('utf-8', 'replace')
    sys.stdout.write(out)
    sys.exit(0 if (r.returncode == 0 and static_fails == 0) else 1)


if __name__ == '__main__':
    main()
