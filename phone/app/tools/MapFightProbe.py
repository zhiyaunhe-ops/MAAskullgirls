#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""MapFightProbe — FIGHT 追踪窗口 (fast.fight_wait_s) + 大保底 (map_check) 的回归。

两层:
  ① config 一致性: fast.fight / map_check 段齐全, 判据素材 (home.png / vs_fight.png)
     存在, map_check.roi 与 home_roi 同源, escape 链里有 vs_fight (th/roi 继承源),
     判定图有 fxfight / mapchk 节点且 edges 引用完整。
  ② 源码级 (剥注释后): 钉住运行期测不到的关键行为 —
     - fight 窗口: 剥弹窗后开启; 命中即点并**立刻**恢复连点; 超时/缺模板只恢复**不停车**;
     - map_check: 两个模式都挂; 命中先复检一帧; run.set(false) 只出现在两帧确认之后;
       缺模板不停车; 只停不点 (不盲点铁律)。

⚠️ 源码断言必须**先剥注释**: 这两个功能的注释里天然会提到"不停车""不点击"这些
   反义行为 (解释设计), 不剥就会把解释判成实现 (GraphProbe/OverlayProbe 栽过)。
"""
import io
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)                       # phone/app
SRC = os.path.join(ROOT, 'src', 'com', 'zhiyaunhe', 'sgmbot')

passed, failed = 0, 0


def ck(name, ok, detail=''):
    global passed, failed
    if ok:
        passed += 1
        print('  ok  %s' % name)
    else:
        failed += 1
        print('FAIL  %s%s' % (name, (' — ' + detail) if detail else ''))


def strip_comments(src):
    out = re.sub(r'/\*.*?\*/', '', src, flags=re.S)
    return re.sub(r'//[^\n]*', '', out)


def read_src(name):
    return io.open(os.path.join(SRC, name), encoding='utf-8').read()


def method_body(src, sig):
    """截取从 sig 所在行到下一个顶格缩进 '    }' 的方法体 (够用: SettleLoop 方法不嵌套类)。"""
    i = src.find(sig)
    if i < 0:
        return ''
    j = src.find('\n    }\n', i)
    return src[i:j if j > 0 else len(src)]


def main():
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    cfg = json.load(io.open(os.path.join(ROOT, 'assets', 'config.json'), encoding='utf-8'))

    print('== ① config 一致性 ==')
    fx = cfg.get('fast', {})
    ck('fast.fight_wait_s=15', fx.get('fight_wait_s') == 15)
    ck('fast.fight_scan_ms=500', fx.get('fight_scan_ms') == 500)
    ck('fast.fight.tpl=vs_fight', (fx.get('fight') or {}).get('tpl') == 'vs_fight')

    mc = cfg.get('map_check', {})
    ck('map_check.on=true', mc.get('on') is True)
    ck('map_check.every_s=300 (5min)', mc.get('every_s') == 300)
    ck('map_check.tpl=home', mc.get('tpl') == 'home')
    ck('map_check.th=0.85', mc.get('th') == 0.85)
    ck('map_check.roi 与顶层 home_roi 同源',
       mc.get('roi') == cfg.get('home_roi') == [0, 0, 180, 70],
       'map_check.roi=%s home_roi=%s' % (mc.get('roi'), cfg.get('home_roi')))

    for t in ('home.png', 'vs_fight.png'):
        ck('素材存在 %s' % t, os.path.isfile(os.path.join(ROOT, 'assets', 'templates', t)))
    esc = (cfg.get('escape') or {}).get('items') or []
    ck('escape 链里有 vs_fight (fast.fight th/roi 的继承源)',
       any(i.get('tpl') == 'vs_fight' for i in esc))

    g = cfg.get('graph') or {}
    ids = {n.get('id') for n in g.get('nodes', [])}
    ck('判定图有 fxfight 节点', 'fxfight' in ids)
    ck('判定图有 mapchk 节点', 'mapchk' in ids)
    edges = g.get('edges', [])
    pair = {(e.get('from'), e.get('to')) for e in edges}
    ck('边 fxpop→fxfight 存在', ('fxpop', 'fxfight') in pair)
    ck('边 fxfight→fxtap 存在', ('fxfight', 'fxtap') in pair)
    ck('边 roimap→mapchk 存在', ('roimap', 'mapchk') in pair)
    dangling = [(e.get('from'), e.get('to')) for e in edges
                if e.get('from') not in ids or e.get('to') not in ids]
    ck('edges 无悬空引用', not dangling, str(dangling))

    print('== ② FIGHT 追踪窗口 (SettleLoop 源码级) ==')
    sl = strip_comments(read_src('SettleLoop.java'))

    ck('剥弹窗命中后开启窗口 (fxFightUntil = t0 + fightWaitMs)',
       re.search(r'fxFightUntil = fightOk \? t0 \+ fightWaitMs : 0', sl) is not None)
    ck('缺模板 / fight_wait_s=0 时不窗口 (fightOk 判据)',
       'fightWaitMs > 0 && resolveFight(work)' in sl)
    body = method_body(sl, 'if (t0 < fxFightUntil)')
    # ⚠️ 2026-10-14: 召回关键匹配统一改走 findExact (强制全搜, 免被 pyramid 丢检),
    #    vs_fight 这条是其调用点之一 —— 锚点跟着改成 findExact。
    ck('窗口内做 vs_fight 匹配', 'findExact(work, tFight, fightR, fightTh)' in body)
    ck('命中即点 (tap 在 fight 分支里)', 'tap(bmp,' in body and 'fightTaps++' in body)
    ck('命中后立刻恢复连点 (清两个闸门)',
       'fxFightUntil = 0;' in body and 'fxGateUntil = 0;' in body,
       '少哪个都会让连点器多瞎点 pauseMs')
    over = re.search(r'\} else if \(fxFightUntil > 0\) \{(.*?)\}', sl, re.S)
    ck('超时分支存在', over is not None)
    if over:
        ck('超时只恢复连点, 绝不停车', 'run.set(false)' not in over.group(1))
    # 窗口段 = 从 "if (t0 < fxFightUntil)" 到大保底调用之前 (含缺模板 else 分支)
    seg = method_body(sl, 'if (t0 < fxFightUntil)').split('mapGuard(work, run)')[0]
    ck('缺模板/窗口段内绝不停车 (只 fxFightUntil = 0 恢复)',
       'fxFightUntil = 0;' in seg and 'run.set(false)' not in seg)
    ck('窗口内采样提到 fight_scan_ms',
       re.search(r't0 < fxFightUntil \? Math\.min\(obsMs, fightScanMs\) : obsMs', sl))
    ck('th/roi 缺省继承 escape 链同名项 (同一素材只标定一次)',
       'fightTpl.equals(it.optString("tpl"))' in sl and 'it.optDouble("th"' in sl)

    print('== ③ 大保底 map_check (源码级) ==')
    mg = method_body(sl, 'private boolean mapGuard')
    ck('mapGuard 方法存在', mg != '')
    ck('两个模式都挂 (run 主循环 + fast 观察线程各一次)',
       sl.count('mapGuard(work, run)') == 2,
       'count=%d' % sl.count('mapGuard(work, run)'))
    ck('every_s 闸门在 mapGuard 内部 (调用方每帧调也便宜)',
       'now - lastMapChk < mapEveryMs' in mg)
    ck('命中先复检: 有第二次截屏 (sh.capture) 与 sleep_(800)',
       'sh.capture()' in mg and 'sleep_(800)' in mg)
    ck('复检未过阈 → 判误报继续跑', re.search(r's2 < mapTh[^}]*return false', mg, re.S) is not None)
    nstop = mg.count('run.set(false)')
    ck('mapGuard 里 run.set(false) 恰好一次 (只在两帧确认之后)', nstop == 1, 'count=%d' % nstop)
    if nstop == 1:
        ck('停车点在复检判定之后 (不是首帧就停)',
           mg.find('run.set(false)') > mg.find('s2 < mapTh'))
    ck('只停不点 (大保底绝不盲点, 重进等 NavChain)',
       'tap(' not in mg)
    ck('缺 home 模板 → 大保底不可用但不停车', 'return tHome != null;' in sl)
    ck('每次检测都记峰值日志 (现场调 th 靠它)',
       'home 峰值' in mg and 'mapTh' in mg)
    ck('snapshot 带新状态字段',
       '"fast_fight"' in sl and '"map_checks"' in sl)

    print('== ④ 落点体检默认不拦车 (2026-10-14) ==')
    ck('fast.tap_check 存在且默认 false',
       'tap_check' in fx and fx.get('tap_check') is False, 'got=%r' % fx.get('tap_check'))
    ck('判定图有 fxchk 节点', 'fxchk' in ids)
    ck('边 fxbig→fxchk 与 fxchk→fxidle 存在',
       ('fxbig', 'fxchk') in pair and ('fxchk', 'fxidle') in pair)
    ck('fxTapCheck 从 config 读 (不是写死)',
       'fxTapCheck = fx.optBoolean("tap_check", false);' in sl)
    # 体检块: 从 "if (homeSlot >= 0" 到下一个 12 空格缩进的 "}"
    # ⚠️ 锚点只到条件开头 —— 2026-10-14 给该 if 加了 `&& homeSlot < rp.length`
    #    (三槽峰值改成节流计算后 rp 可能是上一帧的数组), 锚到 `{` 就会找不到块。
    #    下游 5 条断言 (只提示/run.set 一次/节流/fxchk) 一字未改。
    i = sl.find('if (homeSlot >= 0')
    j = sl.find('\n            }', i)
    blk = sl[i:j] if i >= 0 and j > 0 else ''
    ck('体检块存在', blk != '')
    nst = blk.count('run.set(false)')
    ck('体检块里 run.set(false) 恰好一次 (默认关 ⇒ 只有显式开才停)', nst == 1, 'count=%d' % nst)
    if nst == 1:
        ck('那次停车在 fxTapCheck 分支里 (默认关时不执行)',
           blk.find('run.set(false)') > blk.find('if (fxTapCheck) {'))
    ck('默认走"只提示"分支 (记日志, 不停车)',
       '只提示不拦车' in blk and blk.count('SgmLog.i("观"') >= 1)
    ck('提示有节流 (lastWarn, 免得每帧刷屏)', 'lastWarn' in blk)
    ck('Graph.cursor 用 fxchk (判定图与实现一致)', 'Graph.cursor("fxchk")' in blk)

    print('== %d passed, %d failed ==' % (passed, failed))
    sys.exit(1 if failed else 0)


if __name__ == '__main__':
    main()
