// 连刷编排渲染链 桩 DOM 冒烟 (2026-10-02)。
//
// 起因: webui.js 里连刷编排的 renderPool 与每日任务页签的 renderPool 重名,
// 后者静默覆盖前者 → 「今日场地」面板永远空; node --check 与浏览器控制台
// 全绿, 只有真渲染才看得见。本文件用最小桩 DOM 直接执行真实 webui.js,
// 走 pollState -> 场地方块 与 点击接入 -> chain/save -> 链条渲染 两条真链路,
// 并断言 DOM 输出。纯 Node, 不依赖浏览器/playwright:
//     node tests/webui_chain_render.mjs
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';

const ROOT = path.resolve(import.meta.dirname, '..');

const ARENAS = [
  { idx: 0, title: 'A SHOT IN THE DARK', score: 0 },
  { idx: 1, title: 'COSTUMEPARTY', score: 151076446 },
  { idx: 2, title: 'MEDICI SHAKEDOWN', score: 5266761 },
  { idx: 3, title: 'ONES AND ZEROS', score: 34672590 },
];

/* ---------- 最小桩 DOM ---------- */
function mkEl(id) {
  const listeners = {};
  return {
    id, innerHTML: '', textContent: '', value: '', checked: false, disabled: false,
    hidden: false, src: '', style: {}, dataset: {},
    classList: {
      _s: new Set(),
      add(...c) { c.forEach((x) => this._s.add(x)); },
      remove(...c) { c.forEach((x) => this._s.delete(x)); },
      toggle(c, f) { if (f === undefined) { this._s.has(c) ? this._s.delete(c) : this._s.add(c); } else if (f) { this._s.add(c); } else { this._s.delete(c); } },
      contains(c) { return this._s.has(c); },
    },
    addEventListener(type, fn) { (listeners[type] ||= []).push(fn); },
    _fire(type, ev) { (listeners[type] || []).forEach((fn) => fn(ev || {})); },
    removeEventListener() {}, querySelectorAll() { return []; }, querySelector() { return null; },
    getClientRects() { return []; }, setAttribute() {}, getAttribute() { return null; },
    removeAttribute() {}, focus() {}, select() {}, click() {}, appendChild() {},
    closest() { return null; }, contains() { return false; },
  };
}
const els = new Map();
const documentStub = {
  getElementById(id) { if (!els.has(id)) els.set(id, mkEl(id)); return els.get(id); },
  querySelector() { return null; }, querySelectorAll() { return []; },
  addEventListener() {}, createElement() { return mkEl('tmp'); },
  documentElement: { dataset: {} }, body: mkEl('body'),
};

/* ---------- 桩 fetch: 按路径回灌数据 ---------- */
let lastChainSave = null;
function payload(url) {
  const u = url.split('?')[0];
  if (u.endsWith('/api/state')) {
    return { svc: 'test', status: 'IDLE', step: '-', fight_no: 0, score: null,
      streak: null, score_target: null, energy_cost: 4, pf_rule: null, scene: null,
      arenas: { day: '2026-10-02', arenas: ARENAS }, queue: [],
      filter_favorite: true, close_on_goal: true, rest_every: 0, rest_minutes: 0,
      rest_until: 0, session_id: null, session_name: null, log_total: 0, logs: [],
      shot_ver: 0, shot_time: '' };
  }
  if (u.endsWith('/api/chain')) {
    return { blocks: [], enabled: false, queue: [], running: false };
  }
  if (u.endsWith('/api/chain/save')) {
    return { ok: true, blocks: (lastChainSave || []).map((b, i) => ({ ...b, sid: 's' + (i + 1) })) };
  }
  if (u.endsWith('/api/summary')) return { per_min: null, last_delta: null, score: null, target: 0, eta_sec: null };
  if (u.endsWith('/api/sessions')) return { sessions: [], active: null, queue: [], running: false };
  return {};
}
const fetchStub = async (url, options) => {
  if (String(url).includes('/api/chain/save') && options && options.body) {
    lastChainSave = JSON.parse(options.body).blocks;
  }
  return { ok: true, status: 200, json: async () => payload(String(url)) };
};

/* ---------- 执行真实 webui.js ---------- */
/* Chart 桩: webui.js 载入期就往 Chart.defaults 写颜色 (chart.umd.min.js 提供) */
const ChartStub = class { constructor() {} destroy() {} register() {} };
ChartStub.defaults = { font: {}, plugins: {}, scales: {} };

const ctx = {
  document: documentStub,
  window: { location: { href: 'http://127.0.0.1:8790/', pathname: '/' },
            addEventListener() {}, removeEventListener() {} },
  localStorage: { getItem: () => null, setItem() {}, removeItem() {} },
  getComputedStyle: () => ({ getPropertyValue: () => '#75819a' }),
  fetch: fetchStub,
  setInterval: () => 0, clearInterval() {}, setTimeout: () => 0, clearTimeout() {},
  Chart: ChartStub,
  alert() {}, confirm: () => true, AbortController, URL, URLSearchParams, console,
};
vm.createContext(ctx);
// 按页面真实加载顺序: themes.js (cssVar/applyTheme) -> webui.js
for (const f of ['tools/static/themes.js', 'tools/static/webui.js']) {
  vm.runInContext(fs.readFileSync(path.join(ROOT, f), 'utf8'), ctx, { filename: f });
}

const failures = [];
const check = (cond, msg) => { if (!cond) failures.push(msg); };
const settle = () => new Promise((r) => setTimeout(r, 30));   // 让 vm 内的异步链落地

/* ---------- 链路一: 引导 pollState 落地 -> 切连刷编排页签 -> 今日场地方块 ---------- */
await settle();                       // 等脚本尾部引导 pollState 完成 (statePolling 重入保护)
await ctx.pollState();                // 再跑一轮确保数据新鲜
check(typeof ctx.renderArenaPool === 'function',
      'renderArenaPool 未定义或被同名函数覆盖 (连刷编排面板会空)');
ctx.switchPfSub('chain');             // 用户真实路径: 打开连刷编排页签
await ctx.loadChain();
const poolHtml = documentStub.getElementById('arena-pool').innerHTML;
for (const a of ARENAS) {
  check(poolHtml.includes(a.title), `今日场地缺方块: ${a.title}`);
}
check(poolHtml.includes('#1') && poolHtml.includes('月场位'), '方块缺少 #序号/月场位标注');

/* ---------- 链路二: 点方块接入 -> chain/save -> 链条渲染 ---------- */
const chainHtml0 = documentStub.getElementById('chain-list').innerHTML;
check(chainHtml0.includes('链条为空'), '空链条提示未渲染');
// 模拟点击第 2 个方块
const fakeBlock = { classList: { contains: () => false }, dataset: { pos: '2', title: 'COSTUMEPARTY' } };
documentStub.getElementById('arena-pool')._fire('click', { target: { closest: () => fakeBlock } });
await settle();                       // saveChain 异步落地
const chainHtml1 = documentStub.getElementById('chain-list').innerHTML;
check(chainHtml1.includes('chain-node'), '接入后链条节点未渲染');
check(chainHtml1.includes('COSTUMEPARTY'), '链条节点缺场名');
check((lastChainSave || []).length === 1 && lastChainSave[0].pos === 2,
      `chain/save 载荷不对: ${JSON.stringify(lastChainSave)}`);
check(lastChainSave[0].target === undefined,
      'chain/save 不应再传 target (分数上限唯一入口 = 场次页签, 2026-10-08)');
check(/<span class="cn-target"/.test(chainHtml1) && !/cn-target[^>]*type="number"/.test(chainHtml1),
      '链条目标分须为只读 <span>, 不得是可编辑 <input type="number">');

if (failures.length) {
  console.error('连刷编排渲染冒烟失败:\n  - ' + failures.join('\n  - '));
  process.exit(1);
}
console.log('连刷编排渲染冒烟通过: 今日场地 4 方块 + 点击接入链条渲染正常');
