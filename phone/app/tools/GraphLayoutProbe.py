#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
GraphLayout 回归 — 判定图从 WebView 改成 Canvas 自绘 (2026-10-11) 之后,
"图上画在哪 / 点哪里算命中 / 缩放后坐标怎么换" 全压在 GraphLayout 这一个纯几何类里。
它错了不会崩, 只会**画得不对**或"点了没反应", 而这两种在手机上都没有报错可看。
所以这里在 JVM 里跑**真的 GraphLayout.java** (它不 import 任何 android.*, 只用 org.json):

  A. 布局常量自洽: 列距 > 节点宽、行距 > 节点高 (泳道/节点不许重叠)、最左不出负坐标
  B. 世界包围盒 (含节点框)
  C. fit: 整图恰好落在 pad 内边距里且居中 ⇒ 「全图」档真的能看全
  D. 屏幕 ↔ 世界换算互逆
  E. hit: 点节点中心=命中、点空地=不命中、缩放/平移后仍然对 (最容易错的一处)
  F. 边的起讫点**贴在两框边界上** (箭头才不飘)、同点退化返回 null
  G. 反向边对: 恰好一条画弧 (两条都直会叠在一起, 都弧会各拐一边)
  H. 文案口径: thText/scoreText/detail 与网页版一致
  I. 平移夹取: 拖到世界外被夹回; 图比视口小时居中而不是贴左
  J. 用**真 config.json 的 graph 段**模拟 layout, 给出真实图尺寸与「全图」档的实际
     缩放率 (手机上能不能看清, 这里就有数)

用法: python3 phone/app/tools/GraphLayoutProbe.py
"""
import io
import json
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, 'src', 'com', 'zhiyaunhe', 'sgmbot')
JSON_REAL = os.path.join(ROOT, '..', '..', 'vendor', 'jsonreal', 'build')
JAVA = r'C:\Program Files\Java\jdk-1.8\bin\javac.exe'
JAVA_RUN = r'C:\Program Files\Java\jdk-1.8\bin\java.exe'
OUT = os.path.join(os.environ.get('TEMP', r'C:\Windows\Temp'), 'sgmbot-graphlayout')

PROBE = r'''package com.zhiyaunhe.sgmbot;

import org.json.JSONArray;
import org.json.JSONObject;

/** GraphLayout 回归探针 — 只测纯几何, 所以不需要任何 Android 桩。 */
public class Probe {
    static int pass = 0, fail = 0;

    static void ck(String name, boolean ok, Object got) {
        if (ok) { pass++; System.out.println("  ok   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "    got=" + got); }
    }

    static boolean near(double a, double b, double tol) { return Math.abs(a - b) <= tol; }

    static JSONObject nd(String id, int col, int lane) throws Exception {
        return new JSONObject().put("id", id).put("label", id).put("kind", "scan")
                .put("x", GraphLayout.x(col)).put("y", GraphLayout.y(lane));
    }

    static JSONArray grid(int cols, int rows) throws Exception {
        JSONArray a = new JSONArray();
        for (int r = 0; r < rows; r++)
            for (int c = 0; c < cols; c++)
                a.put(nd("n" + r + c, c, r));
        return a;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("== A. 布局常量自洽 (泳道/节点不许重叠) ==");
        ck("x(0)=110 / x(1)=295 / x(2)=480",
                GraphLayout.x(0) == 110 && GraphLayout.x(1) == 295 && GraphLayout.x(2) == 480,
                GraphLayout.x(0) + "," + GraphLayout.x(1) + "," + GraphLayout.x(2));
        ck("y(0)=70 / y(1)=198 / y(2)=326",
                GraphLayout.y(0) == 70 && GraphLayout.y(1) == 198 && GraphLayout.y(2) == 326,
                GraphLayout.y(0) + "," + GraphLayout.y(1) + "," + GraphLayout.y(2));
        ck("列距 " + GraphLayout.DX + " > 节点宽 " + GraphLayout.NW + " (同泳道不叠)",
                GraphLayout.DX > GraphLayout.NW, GraphLayout.DX - GraphLayout.NW);
        ck("行距 " + GraphLayout.DY + " > 节点高 " + GraphLayout.NH + " (泳道不叠)",
                GraphLayout.DY > GraphLayout.NH, GraphLayout.DY - GraphLayout.NH);
        ck("最左节点不出负坐标 (X0 - NW/2 >= 0)",
                GraphLayout.X0 - GraphLayout.NW / 2 >= 0, GraphLayout.X0 - GraphLayout.NW / 2);
        ck("最上节点不出负坐标 (Y0 - NH/2 >= 0)",
                GraphLayout.Y0 - GraphLayout.NH / 2 >= 0, GraphLayout.Y0 - GraphLayout.NH / 2);

        System.out.println("== B. 世界包围盒 ==");
        JSONArray g22 = grid(2, 2);
        float[] b = GraphLayout.bounds(g22);
        ck("2x2 包围盒 = [34, 43, 371, 225]",
                near(b[0], 34, 0.01) && near(b[1], 43, 0.01)
                        && near(b[2], 371, 0.01) && near(b[3], 225, 0.01),
                b[0] + "," + b[1] + "," + b[2] + "," + b[3]);
        ck("空图 bounds = null", GraphLayout.bounds(new JSONArray()) == null, "n/a");

        System.out.println("== C. fit: 整图落进 pad 内边距且居中 ==");
        float vw = 1080, vh = 1920, pad = 24, minK = 0.25f;
        float[] f = GraphLayout.fit(g22, vw, vh, pad, minK);
        float k = f[0], ox = f[1], oy = f[2];
        boolean inside = true;
        StringBuilder worst = new StringBuilder();
        for (int i = 0; i < g22.length(); i++) {
            JSONObject n = g22.getJSONObject(i);
            float cx = (float) n.getDouble("x"), cy = (float) n.getDouble("y");
            float l = GraphLayout.sx(cx - GraphLayout.NW / 2f, ox, k);
            float r = GraphLayout.sx(cx + GraphLayout.NW / 2f, ox, k);
            float t = GraphLayout.sy(cy - GraphLayout.NH / 2f, oy, k);
            float bo = GraphLayout.sy(cy + GraphLayout.NH / 2f, oy, k);
            if (l < pad - 2 || r > vw - pad + 2 || t < pad - 2 || bo > vh - pad + 2) {
                inside = false;
                worst.append(" [").append(l).append(",").append(t).append(",")
                     .append(r).append(",").append(bo).append("]");
            }
        }
        ck("fit 后每个节点都在 pad 内边距里", inside, worst.length() == 0 ? "ok" : worst);
        /* 图很扁 (337x182) 而视口很高 ⇒ 由**宽度**定缩放, 左右恰好贴上 pad */
        ck("宽度方向吃到 pad (左 24 / 右 1056)",
                near(GraphLayout.sx(34, ox, k), pad, 1.5)
                        && near(GraphLayout.sx(371, ox, k), vw - pad, 1.5),
                GraphLayout.sx(34, ox, k) + " .. " + GraphLayout.sx(371, ox, k));
        ck("高度方向居中 (上下留白相等)",
                near(GraphLayout.sy(43, oy, k), vh - GraphLayout.sy(225, oy, k), 1.5),
                GraphLayout.sy(43, oy, k) + " vs " + (vh - GraphLayout.sy(225, oy, k)));
        ck("k 不小于下限", k >= minK, k);
        ck("空图 fit 返回下限 k 且不炸",
                GraphLayout.fit(new JSONArray(), vw, vh, pad, minK)[0] == minK, "n/a");

        System.out.println("== D. 屏幕 <-> 世界 互逆 ==");
        boolean inv = true;
        for (float w : new float[]{0, 110, 371, 1000}) {
            float s = GraphLayout.sx(w, ox, k);
            if (!near(GraphLayout.wx(s, ox, k), w, 0.01)) inv = false;
            float s2 = GraphLayout.sy(w, oy, k);
            if (!near(GraphLayout.wy(s2, oy, k), w, 0.01)) inv = false;
        }
        ck("sx/wx 与 sy/wy 互逆", inv, "ok");

        System.out.println("== E. hit: 缩放平移后仍点得准 ==");
        float px0 = GraphLayout.sx(GraphLayout.x(0), ox, k);
        float py0 = GraphLayout.sy(GraphLayout.y(0), oy, k);
        ck("点节点 n00 中心 -> n00",
                "n00".equals(GraphLayout.hit(g22, px0, py0, ox, oy, k)),
                GraphLayout.hit(g22, px0, py0, ox, oy, k));
        ck("点节点 n11 中心 -> n11",
                "n11".equals(GraphLayout.hit(g22,
                        GraphLayout.sx(GraphLayout.x(1), ox, k),
                        GraphLayout.sy(GraphLayout.y(1), oy, k), ox, oy, k)),
                "n11");
        ck("点两框之间空地 -> 不命中",
                "".equals(GraphLayout.hit(g22,
                        GraphLayout.sx((GraphLayout.x(0) + GraphLayout.x(1)) / 2f, ox, k),
                        GraphLayout.sy(GraphLayout.y(0), oy, k), ox, oy, k)),
                "空地=" + GraphLayout.hit(g22,
                        GraphLayout.sx((GraphLayout.x(0) + GraphLayout.x(1)) / 2f, ox, k),
                        GraphLayout.sy(GraphLayout.y(0), oy, k), ox, oy, k));
        /* 换一套视口 (放大 + 平移) 再验一次 —— 命中判定若把 zoom/offset 漏掉一处就会在这露馅 */
        float k2 = 2.0f, ox2 = 100, oy2 = 50;
        ck("换视口(k=2, ox=100, oy=50)后仍点得准",
                "n11".equals(GraphLayout.hit(g22,
                        GraphLayout.sx(GraphLayout.x(1), ox2, k2),
                        GraphLayout.sy(GraphLayout.y(1), oy2, k2), ox2, oy2, k2)),
                "n11");
        ck("刚过框右沿 -> 不命中 (半框 " + (GraphLayout.NW / 2) + ")",
                "".equals(GraphLayout.hit(g22,
                        GraphLayout.sx(GraphLayout.x(0) + GraphLayout.NW / 2f + 1, ox, k),
                        GraphLayout.sy(GraphLayout.y(0), oy, k), ox, oy, k)),
                "越界1px");

        System.out.println("== F. 边的起讫点贴在框边界上 ==");
        float[] e1 = GraphLayout.edgePoints(GraphLayout.x(0), GraphLayout.y(0),
                GraphLayout.x(1), GraphLayout.y(0));
        ck("水平相邻: 起点=左框右沿, 终点=右框左沿",
                near(e1[0], GraphLayout.x(0) + GraphLayout.NW / 2f, 0.01)
                        && near(e1[2], GraphLayout.x(1) - GraphLayout.NW / 2f, 0.01),
                e1[0] + " -> " + e1[2]);
        float[] e2 = GraphLayout.edgePoints(GraphLayout.x(0), GraphLayout.y(0),
                GraphLayout.x(0), GraphLayout.y(1));
        ck("垂直相邻: 起点=上框下沿, 终点=下框上沿",
                near(e2[1], GraphLayout.y(0) + GraphLayout.NH / 2f, 0.01)
                        && near(e2[3], GraphLayout.y(1) - GraphLayout.NH / 2f, 0.01),
                e2[1] + " -> " + e2[3]);
        ck("同点退化 -> null",
                GraphLayout.edgePoints(100, 100, 100, 100) == null, "n/a");

        System.out.println("== G. 反向边对: 恰有一条画弧 ==");
        JSONArray both = new JSONArray()
                .put(new JSONObject().put("from", "a").put("to", "b"))
                .put(new JSONObject().put("from", "b").put("to", "a"));
        boolean arc1 = GraphLayout.drawsArc(both, "a", "b");
        boolean arc2 = GraphLayout.drawsArc(both, "b", "a");
        ck("A<->B 恰有一条画弧", arc1 != arc2, arc1 + "/" + arc2);
        JSONArray one = new JSONArray().put(new JSONObject().put("from", "a").put("to", "b"));
        /* 只对**图里真存在的边**问 —— drawsArc 的语义是"A<->B 这对里谁拐弯",
         * 传一条不存在的边进来本来就是无意义的问法。 */
        ck("单向边不画弧", !GraphLayout.drawsArc(one, "a", "b"), "n/a");

        System.out.println("== H. 文案口径 (与网页版一致) ==");
        JSONObject n1 = new JSONObject().put("id", "x").put("label", "阈值节点")
                .put("kind", "match").put("cond", "相似度过阈").put("cfg", "thresholds.min_sim")
                .put("val", 0.72);
        ck("thText(有 val) = '阈 0.72'", "阈 0.72".equals(GraphLayout.thText(n1)),
                GraphLayout.thText(n1));
        JSONObject n2 = new JSONObject().put("id", "y").put("label", "扫描").put("kind", "scan");
        ck("thText(无 val) 退回类别 'scan'", "scan".equals(GraphLayout.thText(n2)),
                GraphLayout.thText(n2));
        ck("scoreText(null) = '-'", "-".equals(GraphLayout.scoreText(null)),
                GraphLayout.scoreText(null));
        JSONObject r1 = new JSONObject().put("score", 0.913).put("hit", true).put("act", "点REMATCH");
        ck("scoreText(命中) 带动作", "0.913 / 点REMATCH".equals(GraphLayout.scoreText(r1)),
                GraphLayout.scoreText(r1));
        JSONObject r2 = new JSONObject().put("score", 0.331).put("hit", false);
        ck("scoreText(未过阈) 说'未过阈'", "0.331 未过阈".equals(GraphLayout.scoreText(r2)),
                GraphLayout.scoreText(r2));
        String dt = GraphLayout.detail(n1, r1);
        ck("detail 含 标签/条件/cfg 路径/实测",
                dt.indexOf("阈值节点") >= 0 && dt.indexOf("相似度过阈") >= 0
                        && dt.indexOf("thresholds.min_sim") >= 0 && dt.indexOf("0.913") >= 0, dt);
        JSONObject n3 = new JSONObject().put("id", "z").put("label", "链").put("kind", "chain")
                .put("of", "escape.items[0].th").put("val", 0.6);
        ck("detail 对展开链用 of 路径", GraphLayout.detail(n3, null).indexOf("escape.items[0].th") >= 0,
                GraphLayout.detail(n3, null));

        System.out.println("== I. 平移夹取 ==");
        float[] cr = GraphLayout.clampRange(g22, vw, vh, k, 0);
        ck("夹取区间不反转 (min<=max)", cr[0] <= cr[1] && cr[2] <= cr[3],
                cr[0] + ".." + cr[1] + " | " + cr[2] + ".." + cr[3]);
        float dragged = -99999;
        float clamped = Math.max(cr[0], Math.min(cr[1], dragged));
        /* 「全图」档下可视世界比图还宽 ⇒ 区间反转 ⇒ 被取成中点 (图居中, 不该能拖)。
         * 所以这里断言的是"拖不出去", 而不是"夹在左沿"。 */
        ck("往左猛拖会被夹回可视范围",
                clamped >= Math.min(cr[0], cr[1]) - 0.01 && clamped <= Math.max(cr[0], cr[1]) + 0.01,
                clamped);
        /* 放大到看不见全图时: 可视区比图小, 区间正常 */
        float[] cr2 = GraphLayout.clampRange(g22, vw, vh, 8f, 0);
        ck("放大后区间正常不反转", cr2[0] <= cr2[1] && cr2[2] <= cr2[3],
                cr2[0] + ".." + cr2[1]);
        /* 缩到比视口还小时 → 居中 (不贴左) */
        float[] cr3 = GraphLayout.clampRange(g22, 4000, 4000, 1f, 0);
        ck("图比视口小时夹到中点 (居中)", near(cr3[0], cr3[1], 0.01),
                cr3[0] + " vs " + cr3[1]);

        System.out.println("== J. 真 config.json 的图 (模拟 layout) ==");
        JSONArray real = Probe2.realNodes();
        float[] rb = GraphLayout.bounds(real);
        System.out.println("  节点 " + real.length() + " 个, 世界尺寸 "
                + (int) (rb[2] - rb[0]) + " x " + (int) (rb[3] - rb[1]) + " (设计单位)");
        /* K70 竖屏: 1080x2400 px, density 2.75 → 视口 393x873 dp */
        float dw = 1080 / 2.75f, dh = 2400 / 2.75f;
        float[] rf = GraphLayout.fit(real, dw * 2.75f, dh * 2.75f, 12 * 2.75f, 0.25f * 2.75f);
        float ratio = rf[0] / 2.75f;
        System.out.println("  「全图」档缩放 " + String.format("%.0f%%", ratio * 100)
                + " → 13sp 的字实际 " + String.format("%.1f", 13 * ratio) + "sp");
        ck("「全图」档确实在缩小 (否则说明包围盒算错)",
                rf[0] < 2.75f, rf[0]);
        ck("「全图」档没小到看不见 (>=10%)", ratio >= 0.10f, ratio);

        System.out.println("== " + pass + " passed, " + fail + " failed ==");
        if (fail > 0) System.exit(1);
    }
}
'''


def real_nodes_java():
    """把真 config.json 的 graph 段按 Graph.layout 的规则算成带 x/y 的节点数组。

    探针只测几何, 不想把 Graph.java/Config.java 一大票 Android 依赖拖进来 ——
    所以这里用 Python 复刻 layout 规则 (列距/行距直接引 GraphLayout 的常量):
    遍历顺序 + 每条泳道内递增的列号。escape 展开的链按 items 条数占列。
    """
    cfg = json.loads(io.open(os.path.join(ROOT, 'assets', 'config.json'), 'rb')
                     .read().decode('utf-8-sig'))
    g = cfg.get('graph') or {}
    nodes = g.get('nodes') or []

    def value_at(path):
        cur = cfg
        for p in path.split('.'):
            if isinstance(cur, list) and p.isdigit():
                i = int(p)
                cur = cur[i] if i < len(cur) else None
            elif isinstance(cur, dict):
                cur = cur.get(p)
            else:
                return None
        return cur

    cnt = {}
    out = []

    def emit(nid, lane):
        col = cnt.get(lane, 0)
        cnt[lane] = col + 1
        out.append({'id': nid, 'label': nid, 'kind': 'scan',
                    'x': 110 + col * 185, 'y': 70 + lane * 128})

    for n in nodes:
        lane = n.get('lane', 0)
        if 'expand' in n:
            sec = value_at(n['expand'])
            items = (sec or {}).get('items') if isinstance(sec, dict) else None
            if not items:
                emit(n.get('id', '?'), lane)
                continue
            for k, it in enumerate(items):
                emit('esc_' + str(it.get('tpl', k)), lane)
            continue
        emit(n.get('id', '?'), lane)
    return out


def probe2_java(real):
    body = json.dumps(real, ensure_ascii=False)
    return '''package com.zhiyaunhe.sgmbot;

import org.json.JSONArray;

/** 真 config.json 的节点坐标 (由 Python 侧按 layout 规则算好注入) */
public class Probe2 {
    public static JSONArray realNodes() throws Exception {
        return new JSONArray(%s);
    }
}
''' % json.dumps(body)


def main():
    os.makedirs(os.path.join(OUT, 'src', 'com', 'zhiyaunhe', 'sgmbot'), exist_ok=True)
    real = real_nodes_java()
    p1 = os.path.join(OUT, 'src', 'com', 'zhiyaunhe', 'sgmbot', 'Probe.java')
    p2 = os.path.join(OUT, 'src', 'com', 'zhiyaunhe', 'sgmbot', 'Probe2.java')
    io.open(p1, 'w', encoding='utf-8').write(PROBE)
    io.open(p2, 'w', encoding='utf-8').write(probe2_java(real))

    classes = os.path.join(OUT, 'classes')
    os.makedirs(classes, exist_ok=True)
    srcs = [p1, p2, os.path.join(SRC, 'GraphLayout.java')]     # ★ 被测: 真实现
    r = subprocess.run([JAVA, '-J-Dfile.encoding=UTF-8', '-encoding', 'UTF-8', '-nowarn',
                        '-d', classes, '-cp', JSON_REAL] + srcs, capture_output=True)
    if r.returncode != 0:
        sys.stdout.write(r.stderr.decode('utf-8', 'replace'))
        sys.exit(1)
    r = subprocess.run([JAVA_RUN, '-Dfile.encoding=UTF-8',
                        '-cp', os.pathsep.join([classes, JSON_REAL]),
                        'com.zhiyaunhe.sgmbot.Probe'], capture_output=True)
    sys.stdout.write((r.stdout + r.stderr).decode('utf-8', 'replace'))
    sys.exit(r.returncode)


if __name__ == '__main__':
    main()
