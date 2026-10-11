#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
重写 config.json 的 graph 段 —— 从「8 个运行时锚点」扩到「全程序判定图」。

设计要点:
  1. 每个节点除 id/label/kind 外, 带 lane(泳道=所处阶段) 和 cond(判定条件人话)
  2. **阈值不写死数字**, 改用 cfg 路径记下来, 由 Graph.load 运行时回填当前值。
     避免「图上写 0.72 / 实际配置是 0.75」这种图与实不符。
  3. escape 链的 6 项用 expand:'escape.items' 占位, 运行时按 config.escape.items
     动态展开 —— 改逃生链表不必同步改 graph, 图自动跟着变。
  4. 只做**文本级替换** graph 段, 其余段落原样保留 (保 CRLF 与缩进风格)。
"""
import io, json, re, sys

P = 'config.json'
src = io.open(P, 'rb').read().decode('utf-8')
crlf = '\r\n' in src
assert crlf, 'config.json 应为 CRLF'
src = src.replace('\r\n', '\n')

# ---------- 节点与边 ----------
N = lambda i, lb, k, lane, cond, **kw: dict(id=i, label=lb, kind=k, lane=lane, cond=cond, **kw)
E = lambda a, b, on=None: dict(**({'from': a, 'to': b} | ({'on': on} if on else {})))

nodes = [
    # lane 0 — 开跑门槛
    N('tpl',    '模板就绪',     'guard', 0, 'victory/defeat 都取到才开跑', src='assets/templates'),
    N('roisc',  '模板按屏幕缩放','roi',   0, '按当前屏幕取 x/y 缩放倍数',  cfg='screen.roi_mode'),
    N('cap0',   '每日上限',     'guard', 0, '今日场数已达上限则不开跑',    cfg='daily_cap',
      note='0 = 不限'),
    # lane 1 — 主循环守卫
    N('run',    '循环运行中',   'loop',  1, 'run 标志为真'),
    N('pause',  '夜间暂停',     'loop',  1, '当前时间在暂停时段内则不截屏不点击',
      cfg='run.pause_from~pause_to', note='跨夜也支持'),
    N('maxmin', '单次最长',     'loop',  1, '已跑超过单次上限则停',        cfg='run.max_minutes',
      note='0 = 不限'),
    # lane 2 — 取帧与 ROI
    N('shot',   '截屏',         'scan',  2, 'Shizuku shell 截屏; 失败下一轮重试'),
    N('roimap', 'ROI 多分辨率映射','roi', 2, 'work 宽高变了才重算 ROI',    cfg='screen.profiles',
      note='abs/center/rel/fit 四选一'),
    N('popup',  '弹窗守卫',     'chain', 2, '距上次动作 > after_sec 就周期性剥弹窗 (任何相位都跑)',
      cfg='popup.after_sec', note='间隔 every_sec; guard=false 可关。'
      'JS 版这条逻辑在 pf_nav.waitHall 里每帧跑, 这里挂在结算循环上 (没有导航链)'),
    N('phase',  '相位选择',     'branch',2, 'phase==0 走战斗监测, 否则走结算页'),
    # lane 3 — 战斗阶段
    N('battle', '战斗监测',     'scan',  3, '在 title_roi 内查 VICTORY/DEFEAT 大字',
      cfg='thresholds.min_sim', roi='title_roi', tpl=['victory', 'defeat']),
    N('bigword','大字首次出现', 'match', 3, '本局第一次见到大字 → 记胜负、转结算',
      note='大字不计场, 计场在点按钮那一刻'),
    N('first_tap','先手点右槽',   'tap',   3, '按固定坐标先手一按 (免匹配)',
      cfg='first_tap', note='胜→CONTINUE / 负→REMATCH'),
    N('lose1',  '败局计场',     'count', 3, '负局的先手即 REMATCH — 当场计一场'),
    # lane 4 — 结算阶段
    N('result', '结算页',       'scan',  4, '在 btn_rois 三槽内查 REMATCH/CONTINUE',
      cfg='thresholds.min_sim', roi='btn_rois', tpl=['btn_rematch', 'btn_continue']),
    N('rematch','REMATCH 命中', 'tap',   4, 'REMATCH 峰值过阈 → 点它并计一场',
      note='一局恰计一次'),
    N('continue','CONTINUE 命中','tap',   4, 'CONTINUE 峰值过阈 → 点它略过这一页',
      note='留在结算阶段, 可能还有下一页'),
    N('fallbk', '连续未命中',   'heal',  4, '连续无命中到上限 → 转回战斗监测',
      cfg='result_fallback'),
    # lane 5 — stall 逃生
    N('stall',  '无动作超时',   'heal',  5, '距上次有效动作超过阈值 → 走逃生链',
      cfg='timing.stall_sec'),
    # expand 指向**段本身** (escape), 由 Graph.load 自己取 .items ——
    # ⚠️ 别写成 'escape.items': 那个路径解析出来是 JSONArray, 而 valueAsObj
    #    只认 JSONObject ⇒ 返回 null ⇒ 整条链退化成单个占位节点 (2026-10-11 实测踩到:
    #    上一轮手改过 config 的 expand 值却没同步改本脚本, 重新生成时又写回了错值)。
    N('escape', '弹窗逃生链',   'chain', 5, '按配置顺序逐个试; 命中即点',
      expand='escape', note='每项的 th 各不相同, 见链上标注'),
    N('escfb',  '三槽兜底',     'heal',  5, '链上全不中 → 用结算按钮位置兜底点一下',
      cfg='btn_rois'),
    N('bstl',   '战斗超时',     'heal',  5, '入战斗起超过阈值仍无结果 → 计一次超时跳',
      cfg='timing.battle_stall_sec'),
    N('heal',   '自愈',         'heal',  5, '连续 3 次同类超时 → selfHeal()',
      note='PLAY 拉回待 NavChain (M2), 缺它不盲点'),
    # lane 6 — 计场与落盘
    N('count',  '计一场',       'count', 6, '胜负按上一局记下的 VICTORY/DEFEAT'),
    N('save',   '落盘',         'count', 6, '每场写完 store.json — 崩溃/被杀不丢'),
    N('capend', '到上限停车',   'guard', 6, '计完这场打到上限 → run=false'),
]

edges = [
    E('tpl', 'roisc'), E('roisc', 'cap0'), E('cap0', 'run'),
    E('run', 'pause', '是'), E('pause', 'run', '不在暂停时段'),
    E('run', 'maxmin', '是'), E('maxmin', 'shot', '未超时'),
    E('shot', 'roimap'), E('roimap', 'popup'), E('popup', 'phase'),
    # 逃生链有两个入口: 守卫 (任意相位, 无动作 after_sec 后) 与 stall (超时告警)。
    # 两者都复用 escape.items —— 加一条配置两边同时生效。
    E('popup', 'escape', '命中即点'),
    E('phase', 'battle', 'phase=0'), E('phase', 'result', 'phase=1'),
    E('battle', 'bigword', '命中'),
    E('battle', 'stall', '未命中'),
    E('bigword', 'first_tap'), E('first_tap', 'lose1', '负局'), E('first_tap', 'result', '胜局'),
    E('result', 'rematch', 'REMATCH 过阈'), E('result', 'continue', 'CONTINUE 过阈'),
    E('result', 'fallbk', '两者都不过阈'),
    E('rematch', 'count'), E('lose1', 'count'), E('continue', 'stall', '略过这页'),
    E('fallbk', 'battle', '转回战斗'),
    E('stall', 'escape'), E('escape', 'escfb', '全不中'), E('escfb', 'stall'),
    E('stall', 'bstl', 'phase=0'), E('bstl', 'heal', '连 3 次'),
    E('heal', 'run'),
    E('count', 'save'), E('save', 'capend', '达上限'), E('save', 'run', '未达'),
]

lanes = [
    {'id': 0, 'name': '开跑门槛'},
    {'id': 1, 'name': '主循环守卫'},
    {'id': 2, 'name': '取帧 / ROI 映射'},
    {'id': 3, 'name': '战斗阶段 (phase 0)'},
    {'id': 4, 'name': '结算阶段 (phase 1)'},
    {'id': 5, 'name': 'stall 逃生 / 自愈'},
    {'id': 6, 'name': '计场与落盘'},
]

graph = {
    'layout': 'lane',
    'lanes': lanes,
    'nodes': nodes,
    'edges': edges,
    'title': 'SGM 结算循环 — 全程序判定图',
    'note': '阈值按 cfg 路径从当前 config 实时回填; 改了 config 图跟着变。'
            'escape 链节点由 Graph.load 按 escape.items 动态展开。',
}

# ---------- 文本级替换 graph 段 ----------
# 找到顶层 "graph": 的起始, 用括号配对定位其结束
i = src.index('\n "graph":')
j = i + len('\n "graph":')
assert src[j] in ' \t'
while src[j].isspace():
    j += 1
assert src[j] == '{', repr(src[j:j + 20])

depth, k = 0, j
instr = False
esc = False
while k < len(src):
    c = src[k]
    if instr:
        if esc:
            esc = False
        elif c == '\\':
            esc = True
        elif c == '"':
            instr = False
    else:
        if c == '"':
            instr = True
        elif c == '{':
            depth += 1
        elif c == '}':
            depth -= 1
            if depth == 0:
                break
    k += 1
end = k + 1

body_txt = json.dumps(graph, ensure_ascii=False, indent=2)
# 缩进对齐: 顶层键位于 1 空格处, 其内容再深 2 格
body_txt = '\n'.join((' ' + ln) if ln.strip() else ln for ln in body_txt.split('\n'))
new_src = src[:i + 1] + ' "graph": ' + body_txt.lstrip() + src[end:]

out = new_src.replace('\n', '\r\n')
io.open(P, 'wb').write(out.encode('utf-8'))
print('graph 段已重写: %d 节点 / %d 边 / %d 泳道' % (len(nodes), len(edges), len(lanes)))
print('文件大小 %d → %d 字节' % (len(src), len(out)))
