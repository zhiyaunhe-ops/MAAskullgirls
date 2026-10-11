#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
GRAPH_HTML 回归 — 判定图页面此前**没有任何自动化验证**。

页面是一大坨 Java 字符串拼出来的 HTML/JS, 一旦拼错就是"白屏但没报错"
(整个 <script> 是原子解析: 一处语法错 = 全页失效)。而且手机上没控制台,
只能靠肉眼。所以这里补三道:

  1. 提取  — 按 Java 字面量把 GRAPH_HTML 拼回来, 断言"不含反斜杠"
             (Java 层不吞字符 / 浏览器侧不因转义炸掉整个 script 块)
  2. 语法  — node --check 整段内联 JS
  3. 真跑  — 用 stub DOM 在 node 里执行, 调 draw() 并断言:
             · draw() 不抛 (白屏类故障在这里就会被抓住)
             · 每个节点都渲染出一个 rect
             · 节点第 3 行 = 当前阈值 (thText), 即"阈值看得见"
             · 点击节点后 info 栏出现条件 + cfg 路径 + 实测分数

用法: python3 tools/GraphPageProbe.py
"""
import io
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEBUGHTTP = os.path.join(ROOT, 'src', 'com', 'zhiyaunhe', 'sgmbot', 'DebugHttp.java')

NODE_CANDIDATES = [
    shutil.which('node'),
    r'C:\Users\zhiya\.workbuddy\binaries\node\versions\22.22.2-6\node.exe',
]


def find_node():
    for p in NODE_CANDIDATES:
        if p and os.path.isfile(p):
            return p
    print('[!] 找不到 node — 跳过页面真跑 (仅做结构断言)')
    return None


def extract_html():
    src = io.open(DEBUGHTTP, encoding='utf-8').read()
    i = src.index('private static final String GRAPH_HTML =')
    end = '</script></body></html>";'
    j = src.index(end, i) + len(end)
    seg = src[i:j]
    lits = re.findall(r'"((?:[^"\\]|\\.)*)"', seg)
    return ''.join(lits), len(lits)


# node 侧: stub DOM 真跑页面脚本。断言写在这里, 输出 "ok/FAIL 名称" 供 python 汇总。
NODE_PROBE = r'''
const fs = require('fs'), vm = require('vm');
const [jsPath, dataPath] = process.argv.slice(2);
let pass = 0, fail = 0;
function ck(name, ok, got) {
  if (ok) { pass++; console.log('  ok   ' + name); }
  else { fail++; console.log('  FAIL ' + name + '  got=' + JSON.stringify(got)); }
}
function mkEl(tag) {
  return {
    tagName: tag, attrs: {}, children: [], style: {}, textContent: '',
    // drawTrace() 要动 <table> 的行 API —— 不给就会以 TypeError 伪装成"页面 bug"
    rows: [], deleteRow(i) { this.rows.splice(i, 1); },
    insertRow() { const r = mkEl('tr'); this.rows.push(r); return r; },
    insertCell() { return mkEl('td'); },
    setAttribute(k, v) { this.attrs[k] = v; },
    appendChild(c) { this.children.push(c); return c; },
    addEventListener(t, f) { this['on' + t] = f; }
  };
}
const store = {};
const document = {
  createElementNS(ns, t) { return mkEl(t); },
  createElement(t) { return mkEl(t); },
  getElementById(id) { if (!store[id]) store[id] = mkEl('div'); return store[id]; }
};
function XMLHttpRequest() { this.open = function () {}; this.send = function () {}; }
const sandbox = { document, XMLHttpRequest, setInterval() { return 0; }, console, Date, Math, JSON };
vm.createContext(sandbox);
vm.runInContext(fs.readFileSync(jsPath, 'utf8'), sandbox);

const data = JSON.parse(fs.readFileSync(dataPath, 'utf8'));
sandbox.D.nodes = data.nodes;
sandbox.D.edges = data.edges;
sandbox.D.byNode = data.byNode;
sandbox.D.cursor = data.cursor;
sandbox.D.trace = data.trace || [];

// ① draw() 不抛 —— "白屏类"故障的唯一判据
let drew = true, err = '';
try { vm.runInContext('draw()', sandbox); }
catch (e) { drew = false; err = String(e); }
ck('draw() 不抛异常', drew, err);

// ①b drawTrace() 不抛 + 流水行数对 (它碰的是 DOM 表格 API, 最容易在真实浏览器外漏测)
let trOk = true, trErr = '';
try { vm.runInContext('drawTrace()', sandbox); }
catch (e) { trOk = false; trErr = String(e); }
ck('drawTrace() 不抛异常', trOk, trErr);
const tbody = store['tr'] || { rows: [] };
ck('流水表行数 = trace 条数 (' + tbody.rows.length + ')',
   tbody.rows.length === (data.trace || []).length, tbody.rows.length);

const svg = store['g'] || mkEl('svg');
const rects = svg.children.filter(c => c.tagName === 'rect');
ck('每个节点渲染出一个 rect (' + rects.length + ')', rects.length === data.nodes.length, rects.length);

// ② 节点第 3 行 = 当前阈值 (阈值看得见)
const thr = vm.runInContext('thText({val:0.75})', sandbox);
ck("thText(有阈值) === '阈 0.75'", thr === '阈 0.75', thr);
const noThr = vm.runInContext('thText({kind:"scan"})', sandbox);
ck('thText(无阈值) 退回类别', noThr === 'scan', noThr);

// ③ cfg 路径要显示出来 (改了 config 图跟着变, 得让人看到取自哪)
const path = vm.runInContext('pathOf({cfg:"thresholds.min_sim",val:0.72})', sandbox);
ck('pathOf 显示 cfg 路径', path.indexOf('thresholds.min_sim') > 0 && path.indexOf('0.72') > 0, path);

// ④ 点节点 -> info 栏给出条件 + 路径 + 实测分数
const esc = data.nodes.filter(n => n.of)[0] || {};
const plain = data.nodes.filter(n => n.cond && !n.of)[0] || {};
vm.runInContext('info(' + JSON.stringify(plain) + ')', sandbox);
const t1 = (store['info'] || {}).textContent || '';
ck('点击后 info 含条件', t1.indexOf(plain.cond || '@@') >= 0, t1);
ck('点击后 info 含 cfg 路径', t1.indexOf(plain.cfg || '@@') >= 0, t1);
ck('点击后 info 含节点名', t1.indexOf(plain.label || '@@') >= 0, t1);
vm.runInContext('info(' + JSON.stringify(esc) + ')', sandbox);
const t2 = (store['info'] || {}).textContent || '';
ck('展开链节点 info 用 of 路径', t2.indexOf(esc.of || '@@') >= 0, t2);

// ⑤ 每个 rect 都挂了点击处理 + title 提示 (否则手机上点不动 / 看不到条件)
const noClick = rects.filter(r => !r.onclick).length;
ck('所有 rect 都绑了 click', noClick === 0, noClick);
const noTip = rects.filter(r => !r.children.some(c => c.tagName === 'title')).length;
ck('所有 rect 都带 <title> 提示', noTip === 0, noTip);

console.log('== ' + pass + ' passed, ' + fail + ' failed ==');
process.exit(fail === 0 ? 0 : 1);
'''


def main():
    if hasattr(sys.stdout, 'reconfigure'):     # 同 GraphProbe: 别让 cp936 把结论编成乱码
        sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    html, nlit = extract_html()
    print('== 提取 ==')
    print('  Java 字面量 %d 块, 拼出 HTML %d 字符' % (nlit, len(html)))
    bad = []
    if '\\' in html:
        bad.append('HTML 里出现反斜杠 (Java/浏览器转义风险)')
    if "id='info'" not in html:
        bad.append("缺少详情条 id='info'")
    if '<script>' not in html or '</script>' not in html:
        bad.append('script 块不完整')
    for b in bad:
        print('  FAIL ' + b)
    if bad:
        sys.exit(1)
    print("  ok   无反斜杠 / 详情条存在 / script 块完整")

    d = tempfile.mkdtemp(prefix='sgmgraphpage')
    m = re.search(r'<script>([\s\S]*)</script>', html)
    js_path = os.path.join(d, 'page.js')
    io.open(js_path, 'w', encoding='utf-8').write(m.group(1))
    data_path = os.path.join(d, 'nodes.json')
    io.open(data_path, 'w', encoding='utf-8').write(json.dumps({
        'nodes': [
            {'id': 'battle', 'label': '战斗监测', 'kind': 'scan', 'x': 110, 'y': 70,
             'cond': '在 title_roi 内查 VICTORY/DEFEAT', 'cfg': 'thresholds.min_sim', 'val': 0.72},
            {'id': 'esc_srv_ok', 'label': 'srv_ok', 'kind': 'chain', 'x': 295, 'y': 70,
             'cond': '命中即点', 'of': 'escape.items[0].th', 'val': 0.75},
            {'id': 'tpl', 'label': '模板就绪', 'kind': 'guard', 'x': 480, 'y': 70,
             'cond': 'victory/defeat 都取到才开跑'},
            {'id': 'cursor_here', 'label': '落盘', 'kind': 'count', 'x': 665, 'y': 70},
        ],
        'edges': [
            {'from': 'battle', 'to': 'esc_srv_ok', 'on': '未命中'},
            {'from': 'tpl', 'to': 'cursor_here'},
        ],
        'byNode': {'battle': {'score': 0.642, 'hit': False, 'tpl': 'victory'}},
        'cursor': 'cursor_here',
        'trace': [
            {'t': 1760164800000, 'node': 'battle', 'tpl': 'victory', 'score': 0.642,
             'hit': False, 'act': ''},
            {'t': 1760164801200, 'node': 'rematch', 'tpl': 'btn_rematch', 'score': 0.913,
             'hit': True, 'act': 'tap'},
        ],
    }, ensure_ascii=False))

    node = find_node()
    if node:
        print('== 语法 (node --check) ==')
        r = subprocess.run([node, '--check', js_path], capture_output=True,
                           encoding='utf-8', errors='replace')
        if r.returncode != 0:
            print('  FAIL JS 语法: ' + (r.stderr or '')[:400])
            sys.exit(1)
        print('  ok   JS 语法可编译')

        print('== 真跑 (stub DOM) ==')
        node_js = os.path.join(d, 'probe.js')
        io.open(node_js, 'w', encoding='utf-8').write(NODE_PROBE)
        r = subprocess.run([node, node_js, js_path, data_path], capture_output=True,
                           encoding='utf-8', errors='replace')
        sys.stdout.write(r.stdout or '')
        if r.stderr:
            sys.stdout.write(r.stderr)
        sys.exit(r.returncode)
    shutil.rmtree(d, ignore_errors=True)


if __name__ == '__main__':
    main()
