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
    N('fmode',  '模式选择',     'branch',0, 'fast.enable 为真则走快速连点, 否则走常规判定',
      cfg='fast.enable', note='快速连点 = 盲点固定坐标换速度, 前提是「一直胜利 + 无额外奖励页」; 默认关'),
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
    N('bigword','大字出现',     'match', 3, '见到大字 → 记胜负(本局首次)并转结算; 点哪个按钮交给 phase 1 匹配',
      note='大字不计场, 计场在 phase 1 点 REMATCH 那一刻'),
    # ⚠️ 这里原有两个节点 first_tap(先手盲点右槽) 与 lose1(败局先手即计场), 2026-10-11 退役。
    #    理由: 右槽在**有 REMATCH 的结算页**上正好是 CONTINUE —— 离线实测 (tools/SlotProbe)
    #    页B 上 btn_continue 全帧最佳 0.951 @(810,494), 模板 188x36 ⇒ 按钮中心 (904,512),
    #    与 first_tap(903,512) 重合 ⇒ 盲点它 = 回大地图。且 seen 会被过场帧复位 ⇒
    #    同一结算流程先手两次, 第二下落在有 REMATCH 的页上 (用户报障「莫名其妙连点两次」)。
    #    现在大字只负责"进结算", 点击统一走 result 节点的按钮匹配 (REMATCH 优先, 找不到才 CONTINUE),
    #    败局计场也随之挪到 phase 1 点 REMATCH 那一刻 (原 lose1 节点删除)。
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
    # lane 7 — 快速连点 (fast)
    #   2026-10-11 用户提出: 结算页第一个界面的 CONTINUE 与第二个界面的 REMATCH
    #   **落在同一个点** (都是按钮行里居中的那颗, K70 真机帧实测中心 (640,512),
    #   见 tools/SlotProbe 输出) ⇒ 一直点它就能 页A 前进 / 页B 重开, 不必逐帧匹配。
    N('fxpt',   '落点解析',     'entry', 7, '中槽中心 + **绕开 HUD 挡块** (config.fast.avoid) → 可点坐标',
      cfg='fast.avoid', note='2026-10-11 用户报障「会点到 x3 那里」: 战斗界面的 1x/3x 速度泡'
                             ' (work x608~672/y450~518) 正好压在中槽中心 ⇒ 落点右移出挡块 (WorkPoint.pick)'),
    N('fxtap',  '连点器 (主线程)', 'tap',  7, '按 tap_ms 盲点落点',
      cfg='fast.tap_ms', note='不截屏不匹配 —— 这是快的地方。战斗中点到那里无害'),
    N('fxobs',  '观察线程',     'loop',  7, '独立线程每 observe_ms 截一帧, 干弹窗/计场/停车三件事',
      cfg='fast.observe_ms', note='与连点器并行; 只有它在截屏'),
    N('fxpop',  '弹窗逃生',     'chain', 7, '复用同一条 escape 链; 命中后连点暂停让画面稳定',
      cfg='fast.popup_pause_ms', note='保留弹窗检测, 但搬出热路径'),
    N('fxbig',  '大字计场',     'count', 7, '大字 + latch + 时间下限 round_gap_s → 一局恰计一次',
      cfg='fast.round_gap_s', note='1s 采样下换页闪断一般采不到; 双保险防重复计场'),
    N('fxidle', '无进展停车',   'heal',  7, 'idle_stop_s 内一次都没计到场 → 停车 (多半已脱离战斗流程)',
      cfg='fast.idle_stop_s', note='0 = 关'),
]

edges = [
    E('tpl', 'roisc'), E('roisc', 'cap0'), E('cap0', 'fmode'),
    E('fmode', 'run', '否 (常规)'), E('fmode', 'fxpt', '是 (fast)'),
    E('fxpt', 'fxtap'),          # 落点先算出来 (要同步一帧 ROI), 连点器才开点
    # fast 子图: 连点器与观察线程**并行**跑, 观察线程串起弹窗/计场/停车
    E('fxtap', 'fxobs', '并行'), E('fxobs', 'fxtap', '循环'),
    E('fxobs', 'fxpop'), E('fxpop', 'fxbig'), E('fxbig', 'fxidle'),
    E('fxidle', 'fxobs', '继续'), E('fxidle', 'capend', '停车'),
    E('run', 'pause', '是'), E('pause', 'run', '不在暂停时段'),
    E('run', 'maxmin', '是'), E('maxmin', 'shot', '未超时'),
    E('shot', 'roimap'), E('roimap', 'popup'), E('popup', 'phase'),
    # 逃生链有两个入口: 守卫 (任意相位, 无动作 after_sec 后) 与 stall (超时告警)。
    # 两者都复用 escape.items —— 加一条配置两边同时生效。
    E('popup', 'escape', '命中即点'),
    E('phase', 'battle', 'phase=0'), E('phase', 'result', 'phase=1'),
    E('battle', 'bigword', '命中'),
    E('battle', 'stall', '未命中'),
    E('bigword', 'result', '进结算 (不盲点)'),
    E('result', 'rematch', 'REMATCH 过阈'), E('result', 'continue', 'CONTINUE 过阈'),
    E('result', 'fallbk', '两者都不过阈'),
    E('rematch', 'count'), E('continue', 'stall', '略过这页'),
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
    {'id': 7, 'name': '快速连点 (fast)'},
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
