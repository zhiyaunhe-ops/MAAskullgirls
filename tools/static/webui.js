// Keep every UI request under its mount path, including hosted previews.
const UI_BASE = new URL('.', window.location.href).pathname;
function uiFetch(path, options) {
  return fetch(path.startsWith('/') ? UI_BASE + path.slice(1) : path, options);
}

let lastLog = -1, lastShot = -1;
let currentLogs = [], shotReceivedAt = 0, statePolling = false;
let sessions = [], activeSess = null, running = false, viewIds = [], sessInputsFor;
const logPanes = ['logpane', 'daily-log'].map(id => {   // 运行页与每日任务页共用一股日志流
  const el = document.getElementById(id);
  el._stick = true;
  el.addEventListener('scroll', () => {
    el._stick = el.scrollTop + el.clientHeight >= el.scrollHeight - 30;
    if (id === 'logpane') updateFollowButton();
  });
  return el;
});
function esc(s){ return String(s ?? '').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;').replace(/'/g,'&#39;'); }

/* ================= 模拟器 (MuMu) 控制 =================
   启停都是秒级动作, 后端丢后台线程执行, 这里只发指令 + 轮询状态点。 */
async function mumuCmd(url) {
  const dot = document.getElementById('mumu-dot');
  if (dot) { dot.className = 'dev-dot busy'; dot.title = '指令已发送…'; }
  try { await api(url, {}); } catch (e) {}
  pollMumu();
}
/* 「关机」是破坏性的: 关掉后这个 WebUI 什么都干不了。2026-09-22 早上被误点过一次,
   之后的「开始」全无声无息 —— 加二次确认, 免得再踩。 */
function onMumuOffClick() {
  if (!confirm('关闭 MuMu 模拟器？\n\n关闭后 bot 无法运行（会先自动暂停）。\n需要时点「开始」会自动重新拉起模拟器。')) return;
  mumuCmd('/api/mumu/shutdown');
}
async function pollMumu() {
  try {
    const d = await (await uiFetch('/api/mumu')).json();
    const dot = document.getElementById('mumu-dot');
    dot.className = 'dev-dot' + (d.busy ? ' busy' : (d.running ? ' on' : ''));
    document.getElementById('mumu-label').textContent = d.busy ? 'MuMu 操作中' : (d.running ? 'MuMu 已就绪' : 'MuMu 离线');
    dot.title = d.busy ? ('MuMu ' + d.busy + ' 中…')
                       : (d.running ? 'MuMu 已就绪' : 'MuMu 未运行');
    document.getElementById('btn-mumu').disabled = !!d.busy;
    document.getElementById('btn-game').disabled = !!d.busy;
    document.getElementById('btn-mumu-off').disabled = !!d.busy || !d.running;
    const es = document.getElementById('emu-status');
    if (es) {
      es.textContent = d.busy ? '操作中' : (d.running ? '已就绪' : '离线');
      es.className = 'pill ' + (d.running || d.busy ? 'RUNNING' : 'IDLE');
    }
  } catch (e) {}
}
/* ================= ADB 连接设置 (config.json 四件套, 重启生效) =================
   后端 GET/POST /api/adb_config: 现读现写 config.json, pf_env 是 import 期读,
   所以保存后必须重启服务才生效 —— 前端提示里明说。 */
async function loadAdbConfig() {
  try {
    const d = await (await uiFetch('/api/adb_config')).json();
    document.getElementById('in-adb-path').value = d.adb_path || '';
    document.getElementById('in-adb-addr').value = d.address || '';
    document.getElementById('in-adb-port').value = d.adb_server_port || 0;
    document.getElementById('in-mumu-dir').value = d.mumu_dir || '';
  } catch (e) {}
}
async function saveAdbConfig() {
  const body = {
    adb_path: document.getElementById('in-adb-path').value.trim(),
    address: document.getElementById('in-adb-addr').value.trim(),
    adb_server_port: parseInt(document.getElementById('in-adb-port').value, 10) || 0,
    mumu_dir: document.getElementById('in-mumu-dir').value.trim(),
  };
  try {
    const r = await api('/api/adb_config', body);
    const warns = ((r && r.warnings) || []).join('；');
    showNotice('连接设置已保存' + (warns ? '（注意：' + warns + '）' : '') + '，重启服务后生效。');
  } catch (e) {}
}
/* 自动检测: 后端找运行中的 MuMu/常见安装位置 + 试连探地址, 只回填不落盘 */
async function autodetectAdbConfig() {
  try {
    const r = await api('/api/adb_config/autodetect', {});
    if (r.mumu_dir) document.getElementById('in-mumu-dir').value = r.mumu_dir;
    if (r.adb_path) document.getElementById('in-adb-path').value = r.adb_path;
    if (r.address) document.getElementById('in-adb-addr').value = r.address;
    const notes = (r.notes || []).join('；');
    showNotice('检测完成' + (r.mumu_dir ? '：' + r.mumu_dir : '：未找到 MuMu 目录') +
               (notes ? '。' + notes : '') + '。确认无误后点「保存连接设置」。');
  } catch (e) {}
}
let pfSub = 'run';
function switchTab(t) {
  document.body.dataset.tab = t;
  for (const n of ['pf','daily','jjc']) {
    document.getElementById('tab-'+n).classList.toggle('on', n===t);
    if (n === t) document.getElementById('tab-'+n).setAttribute('aria-current', 'page');
    else document.getElementById('tab-'+n).removeAttribute('aria-current');
    document.getElementById('page-'+n).classList.toggle('on', n===t);
  }
  if (t === 'daily') loadDaily();
  if (t === 'jjc') loadJJC();
}
function switchPfSub(s) {
  pfSub = s;
  for (const n of ['run','chart','chain']) {
    document.getElementById('subtab-'+n).classList.toggle('on', n===s);
    document.getElementById('pf-'+n).classList.toggle('on', n===s);
  }
  if (s === 'chart') pollHistory();
  if (s === 'chain') loadChain();
}
/* 目标 ETA: 速率来自 /api/summary (本场记分点, 相邻间隔>180s 的空闲段不计入),
   剩余时间在前端用最新 state 的 score 现算 —— 比记分点最后一点新鲜。
   未设目标分时只显示速率; 服务端 eta_sec 的 150M 兜底是小组件口径, 页面不沿用。 */
const fmtRate = v => {
  const sgn = v >= 0 ? '+' : '-';
  const a = Math.abs(v);
  return sgn + (a >= 10000 ? (a / 10000).toFixed(1) + '万' : fmtN(a));
};
const fmtDurCn = sec => {
  let h = Math.floor(sec / 3600), m = Math.round(sec % 3600 / 60);
  if (m === 60) { h++; m = 0; }        // 59m59s 四舍五入成 60 分时进位成小时
  if (!h) return Math.max(1, m) + '分钟';
  return m ? h + '小时' + m + '分' : h + '小时';
};
function renderEta(d, sum) {
  const el = document.getElementById('goal-eta');
  if (!el) return;
  const perMin = sum && Number(sum.per_min) > 0 ? Number(sum.per_min) : null;
  const target = Number(d.score_target) || 0;
  const score = Number(d.score) || 0;
  if (!perMin) {
    el.textContent = target > 0 ? '目标 ' + fmtN(target) + ' · 速率统计中 (需活跃满 30 秒)' : '—';
    return;
  }
  const rate = fmtRate(perMin) + '/分';
  if (target > 0 && score < target) {
    const etaSec = Math.max(0, Math.round((target - score) / perMin * 60));
    const t = new Date(Date.now() + etaSec * 1000);
    el.textContent = '预计 ' + ('0' + t.getHours()).slice(-2) + ':' + ('0' + t.getMinutes()).slice(-2)
      + ' 完成 · 剩约 ' + fmtDurCn(etaSec) + ' · ' + rate;
  } else if (target > 0) {
    el.textContent = '已达标 · ' + rate;
  } else {
    el.textContent = rate + ' · 未设目标分';
  }
}
async function pollState() {
  if (statePolling) return;
  statePolling = true;
  try {
    const d = await requestJSON('/api/state');
    if (!d || typeof d.status !== 'string') throw new Error('状态数据不可用');
    let sum = null;                      // /api/summary: 本场速率 (ETA 用); 拿不到不拖累状态渲染
    try { sum = await requestJSON('/api/summary'); } catch (_) { }
    const connection = document.getElementById('connection');
    connection.className = 'connection online';
    connection.textContent = d.demo ? '演示预览 · 示例数据' : '实时连接';
    document.body.classList.toggle('demo-mode', !!d.demo);
    document.getElementById('demo-banner').hidden = !d.demo;
    document.getElementById('session-heading').textContent = d.session_name || '选择场次，准备出发';
    const goal = Number(d.score_target);
    const score = Number(d.score) || 0;
    document.getElementById('goal-label').textContent = goal > 0
      ? Math.min(100, score / goal * 100).toFixed(1) + '% · ' + goal.toLocaleString() : '未设上限';
    document.getElementById('goal-progress').value = goal > 0 ? Math.max(0, Math.min(100, score / goal * 100)) : 0;
    renderEta(d, sum);
    const st = document.getElementById('status');
    st.textContent = d.status; st.className = 'pill ' + d.status;
    document.getElementById('fight').innerHTML = d.fight_no ? ('<b>' + d.fight_no + '</b>') : '<b>0</b>';
    document.getElementById('h-score').innerHTML = '总分 <b>' + (d.score != null ? d.score.toLocaleString() : '-') + '</b>';
    document.getElementById('h-streak').innerHTML = '连胜 <b>' + (d.streak != null ? d.streak : '-') + '</b>';
    document.getElementById('step').textContent = d.step;
    const startBtn = document.getElementById('startbtn');
    startBtn.style.display = d.status === 'RUNNING' ? 'none' : 'inline-block';
    startBtn.textContent = '开始';             // 无暂停态: 只有 开始 / 结束 两种
    document.getElementById('stopbtn').style.display = d.status === 'RUNNING' ? 'inline-block' : 'none';
    const inT = document.getElementById('in-target'), inE = document.getElementById('in-energy');
    running = d.status === 'RUNNING';
    activeSess = d.session_id;
    updateSessChip(d);
    navPicker.setEnabled(!!d.session_name && !running);
    navPicker.set(d.pf_rule);
    setRuleCollapsed(running, d.pf_rule);
    const rn = document.getElementById('in-restn'), rm = document.getElementById('in-restm');
    const inScene = document.getElementById('in-scene');
    const sessLocked = !activeSess || running;   // 能量/上界/休息/规则/场地绑定随场次
    inT.disabled = inE.disabled = rn.disabled = rm.disabled = inScene.disabled = sessLocked;
    if (sessInputsFor !== activeSess) {          // 选中场次变了 -> 回填该场次的能量/上界/休息/场地
      sessInputsFor = activeSess;
      if (document.activeElement !== inE) inE.value = (d.energy_cost != null ? d.energy_cost : 4);
      if (document.activeElement !== inT) inT.value = (d.score_target != null ? d.score_target : '');
      if (document.activeElement !== rn) rn.value = (d.rest_every || 0);
      if (document.activeElement !== rm) rm.value = (d.rest_minutes || 0);
      if (document.activeElement !== inScene) inScene.value = (d.scene || '');
    }
    renderQueueChip(d.queue);
    renderArenas(d.arenas);
    stateArenas = d.arenas;
    renderChainStatus();
    if (pfSub === 'chain') renderArenaPool();
    const fav = document.getElementById('in-fav');
    if (!fav.dataset.touched) fav.checked = !!d.filter_favorite;
    const cg = document.getElementById('in-closegoal');
    if (cg && !cg.dataset.touched) cg.checked = !!d.close_on_goal;
    if (d.rest_until * 1000 > Date.now()) {
      const m = Math.ceil((d.rest_until * 1000 - Date.now()) / 60000);
      document.getElementById('step').textContent = '休息中 (剩 ~' + m + ' 分钟, 回能)';
    }
    if (d.log_total !== lastLog) {
      lastLog = d.log_total;
      currentLogs = Array.isArray(d.logs) ? d.logs : [];
      renderLogs();
    }
    if (d.shot_ver !== lastShot) {
      lastShot = d.shot_ver;
      if (d.shot_ver > 0) {
        document.getElementById('shot').src = UI_BASE + 'shot.jpg?v=' + d.shot_ver;
        document.getElementById('sideinfo').textContent = '采集于 ' + (d.shot_time || '—') + ' · ' + d.status;
      } else {
        document.getElementById('shot').hidden = true;
        document.getElementById('shot-empty').hidden = false;
      }
    }
    if (shotReceivedAt) {
      const age = Math.floor((Date.now() - shotReceivedAt) / 1000);
      document.getElementById('shot-freshness').textContent = age < 5 ? '刚刚更新' : age + ' 秒前更新';
    }
  } catch (e) {
    const connection = document.getElementById('connection');
    connection.className = 'connection offline';
    connection.textContent = '连接中断 · 自动重试';
    connection.title = e.message;
  } finally {
    statePolling = false;
  }
}
function renderLogs() {
  const term = document.getElementById('log-search').value.toLowerCase();
  const level = document.getElementById('log-level').value;
  for (const el of logPanes) {
    const filtered = el.id === 'logpane' ? currentLogs.filter(l =>
      (!term || String(l[2]).toLowerCase().includes(term)) &&
      (level === 'all' || (level === 'warn' ? ['warn','err'].includes(l[1]) : l[1] === level))) : currentLogs;
    el.innerHTML = filtered.map(l =>
      `<div class="${esc(l[1])}"><span class="t">${esc(l[0])}</span>${esc(l[2])}</div>`).join('');
    if (el._stick) el.scrollTop = el.scrollHeight;
    if (el.id === 'logpane') {
      document.getElementById('log-count').textContent = filtered.length + ' 条';
      document.getElementById('log-empty').hidden = filtered.length > 0;
      document.getElementById('log-empty').textContent = currentLogs.length ? '没有匹配的日志' : '等待运行日志…';
      el.hidden = filtered.length === 0;
    }
  }
}
function updateFollowButton() {
  const following = document.getElementById('logpane')._stick;
  const button = document.getElementById('log-follow');
  button.setAttribute('aria-pressed', String(following));
  button.textContent = following ? '跟随最新 ↓' : '已暂停跟随 ↓';
}
document.getElementById('log-search').addEventListener('input', renderLogs);
document.getElementById('log-level').addEventListener('change', renderLogs);
document.getElementById('log-follow').addEventListener('click', () => {
  const el = document.getElementById('logpane');
  el._stick = !el._stick;
  if (el._stick) el.scrollTop = el.scrollHeight;
  updateFollowButton();
});
document.getElementById('shot').addEventListener('load', () => {
  document.getElementById('shot').hidden = false;
  document.getElementById('shot-empty').hidden = true;
  shotReceivedAt = Date.now();
  document.getElementById('shot-freshness').textContent = '刚刚更新';
});
document.getElementById('shot').addEventListener('error', () => {
  document.getElementById('shot').hidden = true;
  document.getElementById('shot-empty').hidden = false;
  document.getElementById('shot-freshness').textContent = '截图暂不可用';
  shotReceivedAt = 0;
});

/* ================= Chart.js 图表 ================= */
let GOLD = '#f6c960', GREEN = '#5ee08a', RED = '#ff7b8b';
function refreshPalette() {                 // 主题切换后重读: canvas 不继承 CSS 变量
  GOLD  = cssVar('--gold')  || GOLD;
  GREEN = cssVar('--green') || GREEN;
  RED   = cssVar('--red')   || RED;
}
const fmtN = v => Math.round(v).toLocaleString();
const fmtTs = ts => { const d = new Date(ts*1000);
  return ('0'+d.getHours()).slice(-2)+':'+('0'+d.getMinutes()).slice(-2)+':'+('0'+d.getSeconds()).slice(-2); };
Chart.defaults.color = cssVar('--dim') || '#75819a';
Chart.defaults.borderColor = cssVar('--line') || '#232a3c';
Chart.defaults.font.family = "Consolas, 'JetBrains Mono', monospace";
Chart.defaults.animation = false;

let chScore = null, chDelta = null, chStreak = null;
let ptsMeta = [];   // 与标签平行的采样点元数据

const ttStyle = {
  backgroundColor: cssVar('--panel2') || 'rgba(14,17,24,.95)',
  borderColor: cssVar('--line') || '#262e40', borderWidth: 1,
  titleColor: cssVar('--txt') || '#dde4ee', bodyColor: cssVar('--txt2') || '#c4cde0', padding: 10,
  displayColors: false, cornerRadius: 8, titleFont: {weight: '600'},
};
const axisX = {
  ticks: { maxTicksLimit: 7, maxRotation: 0 }, grid: { display: false },
};
const axisYn = { ticks: { callback: v => Number(v).toLocaleString() },
                 grid: { color: cssVar('--line') || '#20283a' } };

function ensureCharts() {
  if (chScore) return;
  chScore = new Chart(document.getElementById('ch-score'), {
    type: 'line',
    data: { labels: [], datasets: [{
      data: [], borderColor: GOLD, borderWidth: 2, fill: true,
      backgroundColor: rgbaOf(GOLD, .10), tension: .35,
      pointRadius: 2.5, pointHoverRadius: 5.5, pointBackgroundColor: GOLD,
    }]},
    options: {
      responsive: true, maintainAspectRatio: false,
      interaction: { mode: 'nearest', intersect: false },
      plugins: { legend: { display: false },
        tooltip: { ...ttStyle,
          callbacks: {
            title: items => { const p = pointAt(items[0]); return p ? fmtTs(p.ts) : ''; },
            label: ctx => {
              const p = pointAt(ctx); if (!p) return '';
              let s = compareMode
                ? chScore.data.datasets[ctx.datasetIndex].label + '　总分 ' + p.score.toLocaleString()
                : '总分 ' + p.score.toLocaleString();
              if (p.streak != null) s += '　连胜 ' + p.streak;
              if (p.fight) s += '　第 ' + p.fight + ' 场后';
              return s;
            } } } },
      scales: { x: axisX, y: axisYn },
    }
  });
  chDelta = new Chart(document.getElementById('ch-delta'), {
    type: 'bar',
    data: { labels: [], datasets: [{
      data: [], backgroundColor: ctx => {
        const v = ctx.parsed && ctx.parsed.y !== undefined && ctx.parsed.y !== null ? ctx.parsed.y : ctx.raw;
        return (v >= 0) ? GREEN : RED;
      }, borderRadius: 3, barPercentage: .7,
    }]},
    options: {
      responsive: true, maintainAspectRatio: false, animation: false,
      interaction: { mode: 'nearest', intersect: false },
      plugins: { legend: { display: false },
        tooltip: { ...ttStyle,
          callbacks: {
            title: items => (ptsMeta[items[0].dataIndex] ? fmtTs(ptsMeta[items[0].dataIndex].ts) : ''),
            label: ctx => '本场 ' + (ctx.parsed.y >= 0 ? '+' : '') + Number(ctx.parsed.y).toLocaleString() } } },
      scales: { x: axisX, y: { ...axisYn, ticks: { callback: v => (v>0?'+':'') + Number(v).toLocaleString() } } },
    }
  });
  chStreak = new Chart(document.getElementById('ch-streak'), {
    type: 'line',
    data: { labels: [], datasets: [{
      data: [], borderColor: GREEN, borderWidth: 2, stepped: true,
      pointRadius: 2.5, pointHoverRadius: 5.5, pointBackgroundColor: GREEN,
    }]},
    options: {
      responsive: true, maintainAspectRatio: false, animation: false,
      interaction: { mode: 'nearest', intersect: false },
      plugins: { legend: { display: false },
        tooltip: { ...ttStyle,
          callbacks: {
            title: items => (ptsMeta[items[0].dataIndex] ? fmtTs(ptsMeta[items[0].dataIndex].ts) : ''),
            label: ctx => (compareMode ? chStreak.data.datasets[ctx.datasetIndex].label + '　' : '')
              + '连胜 ' + ctx.parsed.y } } },
      scales: { x: axisX, y: { ...axisYn, ticks: { precision: 0 } } },
    }
  });
}
function computeStats(pts) {
  const deltas = [];
  for (let i = 1; i < pts.length; i++)
    if (pts[i].score != null && pts[i-1].score != null)
      deltas.push(pts[i].score - pts[i-1].score);
  if (!deltas.length) return null;
  const total = deltas.reduce((a,b)=>a+b, 0);
  const wins = deltas.filter(d => d > 0).length;
  // 每分钟收益: 只累计活跃时段 (相邻采样间隔 <=3 分钟, 排除停机/空闲),
  // ts 单位是秒。时段过长视为间隔, 其间收益也不计入 (等效整体跳过该空档)
  let activeSec = 0, gainSum = 0;
  for (let i = 1; i < pts.length; i++) {
    const dt = pts[i].ts - pts[i-1].ts;
    if (dt < 0 || dt > 180) continue;
    activeSec += dt;
    if (pts[i].score != null && pts[i-1].score != null)
      gainSum += pts[i].score - pts[i-1].score;
  }
  const perMin = activeSec > 30 ? gainSum / (activeSec / 60) : 0;
  const streaks = pts.map(p => p.streak).filter(s => s != null);
  let last = null;
  for (let i = pts.length - 1; i >= 0; i--)
    if (pts[i].score != null) { last = pts[i].score; break; }
  return { fights: deltas.length, winr: wins / deltas.length,
           winrate: (wins/deltas.length*100).toFixed(0) + '%',
           total, avg: total / deltas.length, perMin, last,
           curStreak: streaks.length ? streaks[streaks.length-1] : '-',
           maxStreak: streaks.length ? Math.max(...streaks) : '-',
           avgStreak: streaks.length ? (streaks.reduce((a,b)=>a+b,0)/streaks.length).toFixed(1) : '-' };
}
function renderStats(pts) {
  const st = computeStats(pts);
  const row = document.getElementById('stats-row');
  if (!st) { row.innerHTML = ''; return; }
  const fmtD = v => (v>=0?'+':'') + fmtN(v);
  const cards = [
    ['结算场次', st.fights, 'v-blue'],
    ['胜率', st.winrate, st.winr >= .5 ? 'v-green' : 'v-red'],
    ['总收益', fmtD(st.total), st.total>=0 ? 'v-green' : 'v-red'],
    ['场均收益', fmtD(st.avg), st.avg>=0 ? 'v-green' : 'v-red'],
    ['每分钟收益', fmtD(st.perMin), st.perMin>=0 ? 'v-green' : 'v-red'],
    ['当前连胜', st.curStreak, 'v-green'],
    ['最高连胜', st.maxStreak, 'v-blue'],
    ['平均连胜', st.avgStreak, 'v-gold'],
  ];
  if (st.last != null) cards.unshift(['当前总分', fmtN(st.last), 'v-gold']);
  row.innerHTML = cards.map(c =>
    `<div class="stat-card"><div class="k">${c[0]}</div><div class="v ${c[2]}">${c[1]}</div></div>`).join('');
}
const PALETTE = ['#7fb2ff','#f6c960','#5ee08a','#ff7b8b','#b78bff','#63e0dc','#ff9f6b','#c9d4ff'];
const sessColor = sid => {
  const i = sessions.findIndex(s => s.id === sid);
  return PALETTE[(i < 0 ? 0 : i) % PALETTE.length];
};
let compareMode = false, compareMeta = [];
function pointAt(ctx) {
  if (compareMode) return (compareMeta[ctx.datasetIndex] || [])[ctx.dataIndex];
  return ptsMeta[ctx.dataIndex];
}
function renderViewChips() {
  const box = document.getElementById('view-chips');
  if (!sessions.length) { box.innerHTML = ''; return; }
  viewIds = viewIds.filter(id => sessions.some(s => s.id === id));
  if (!viewIds.length)
    viewIds = [activeSess && sessions.some(s => s.id === activeSess) ? activeSess : sessions[0].id];
  box.innerHTML = sessions.map(s => {
    const on = viewIds.includes(s.id);
    return `<button class="vc${on ? ' on' : ''}" style="--c:${sessColor(s.id)}" data-id="${s.id}">
      <span class="dot"></span>${esc(s.name)}<span class="add" data-add="${s.id}">${on ? '－' : '＋'}</span></button>`;
  }).join('') + (sessions.length > 1 ? '<span class="hint">点名称=只看该场次 · 点＋/－=加入/移出对比</span>' : '');
}
document.getElementById('view-chips').addEventListener('click', e => {
  const btn = e.target.closest('.vc');
  if (!btn) return;
  const add = e.target.closest('[data-add]');
  const id = add ? add.dataset.add : btn.dataset.id;
  if (add) {
    if (viewIds.includes(id)) { if (viewIds.length > 1) viewIds = viewIds.filter(x => x !== id); }
    else viewIds = viewIds.concat(id);
  } else {
    viewIds = [id];
  }
  pollHistory();
});
async function pollHistory() {
  try {
    const sd = await api('/api/sessions');
    sessions = sd.sessions; activeSess = sd.active;
    renderViewChips();
    const ids = viewIds.length ? viewIds
      : [activeSess || (sessions[0] && sessions[0].id)].filter(Boolean);
    const d = await api('/api/history' + (ids.length ? '?sessions=' + ids.join(',') : ''));
    const series = d.series || [];
    document.getElementById('empty').style.display =
      series.some(s => s.points.length) ? 'none' : 'block';
    ensureCharts();
    if (series.length > 1) renderCompare(series);
    else renderSingle(series[0]);
  } catch (e) {}
}
function renderSingle(s) {
  compareMode = false;
  const pts = s ? s.points : [];
  renderStats(pts);
  const scored = pts.filter(p => p.score != null);
  const dts = [];
  for (let i = 1; i < scored.length; i++)
    dts.push(scored[i].score - scored[i-1].score);
  ptsMeta = scored;
  document.getElementById('delta-card').style.display = '';
  document.getElementById('cmp-card').style.display = 'none';
  document.getElementById('t-score').textContent = '总 分';
  document.getElementById('sub-score').textContent = '悬浮查看每个采样点';
  chScore.data.labels = scored.map(p => fmtTs(p.ts));
  chScore.data.datasets = [{
    data: scored.map(p => p.score), borderColor: GOLD, borderWidth: 2, fill: true,
    backgroundColor: rgbaOf(GOLD, .10), tension: .4,
    pointRadius: 0, pointHitRadius: 10, pointHoverRadius: 5, pointBackgroundColor: GOLD,
  }];
  chScore.update('none');
  const dl = document.getElementById('v-delta');
  if (dts.length) {
    const lastD = dts[dts.length-1];
    dl.textContent = (lastD>=0?'+':'') + fmtN(lastD);
    dl.style.color = lastD>=0 ? GREEN : RED;
  } else dl.textContent = '';
  chDelta.data.labels = scored.slice(1).map(p => fmtTs(p.ts));
  chDelta.data.datasets = [{ data: dts, borderRadius: 3, barPercentage: .7,
    backgroundColor: ctx => {
      const v = ctx.parsed && ctx.parsed.y !== undefined && ctx.parsed.y !== null ? ctx.parsed.y : ctx.raw;
      return (v >= 0) ? GREEN : RED;
    } }];
  chDelta.update('none');
  const streaks = pts.filter(p => p.streak != null);
  document.getElementById('v-streak').textContent = streaks.length ? streaks[streaks.length-1].streak : '';
  chStreak.data.labels = streaks.map(p => fmtTs(p.ts));
  chStreak.data.datasets = [{ data: streaks.map(p => p.streak), borderColor: GREEN,
    borderWidth: 2, tension: .4, pointRadius: 0, pointHitRadius: 10,
    pointHoverRadius: 5, pointBackgroundColor: GREEN }];
  chStreak.update('none');
}
function renderCompare(series) {
  compareMode = true;
  const st = series.map(s => ({ ...s, st: computeStats(s.points),
                                scored: s.points.filter(p => p.score != null) }));
  document.getElementById('stats-row').innerHTML = '';
  document.getElementById('delta-card').style.display = 'none';
  document.getElementById('cmp-card').style.display = '';
  document.getElementById('t-score').textContent = '收益累计对比';
  document.getElementById('sub-score').textContent = '各场次以其首个采样为 0 起点';
  document.getElementById('v-delta').textContent = '';
  document.getElementById('v-streak').textContent = '';
  const fmtD = v => v == null ? '-' : (v>=0?'+':'') + fmtN(v);
  const head = '<tr><th>指标</th>' + st.map(s =>
    `<th style="color:${sessColor(s.id)}">${esc(s.name)}</th>`).join('') + '</tr>';
  const rows = [
    ['总分', s => { const sc = s.scored;
      return sc.length ? fmtN(sc[sc.length-1].score) : '-'; }],
    ['结算场次', s => s.st ? s.st.fights : '-'],
    ['胜率', s => s.st ? s.st.winrate : '-'],
    ['总收益', s => s.st ? fmtD(s.st.total) : '-'],
    ['场均收益', s => s.st ? fmtD(s.st.avg) : '-'],
    ['每分钟收益', s => s.st ? fmtD(s.st.perMin) : '-'],
    ['最高连胜', s => s.st ? s.st.maxStreak : '-'],
    ['平均连胜', s => s.st ? s.st.avgStreak : '-'],
  ];
  document.getElementById('cmp-table').innerHTML = head + rows.map(r =>
    '<tr><td>' + r[0] + '</td>' + st.map(s => '<td>' + r[1](s) + '</td>').join('') + '</tr>').join('');
  compareMeta = st.map(s => s.scored);
  chScore.data.labels = (compareMeta[0] || []).map((p, i) => i + 1);
  chScore.data.datasets = st.map(s => {
    const base = s.scored.length ? s.scored[0].score : 0;
    return { label: s.name, data: s.scored.map(p => p.score - base),
             borderColor: sessColor(s.id), backgroundColor: sessColor(s.id),
             borderWidth: 2, tension: .4, pointRadius: 0, pointHitRadius: 10,
             pointHoverRadius: 5 };
  });
  chScore.update('none');
  chStreak.data.labels = [];
  chStreak.data.datasets = st.map(s => {
    const pts = s.points.filter(p => p.streak != null);
    return { label: s.name, data: pts.map(p => p.streak), borderColor: sessColor(s.id),
             backgroundColor: sessColor(s.id), borderWidth: 2, tension: .4,
             pointRadius: 0, pointHitRadius: 10 };
  });
  chStreak.update('none');
}

/* ================= 场次 & PF规则 ================= */
async function requestJSON(url, body) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 10000);
  try {
    const response = await uiFetch(url, {
      signal:controller.signal,
      ...(body !== undefined ? { method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify(body) } : {}),
    });
    const data = await response.json();
    if (!response.ok && !data.error) data.error = '请求失败 (' + response.status + ')';
    if (body === undefined && data.error) throw new Error(data.error);
    return data;
  } finally { clearTimeout(timer); }
}
let noticeTimer;
function showNotice(message) {
  const el = document.getElementById('notice');
  el.textContent = message;
  el.hidden = false;
  clearTimeout(noticeTimer);
  noticeTimer = setTimeout(() => { el.hidden = true; }, 6000);
}
async function api(url, body) {
  try {
    const data = await requestJSON(url, body);
    if (data.error) showNotice(data.error);
    return data;
  } catch (e) {
    showNotice('请求失败：' + (e.name === 'AbortError' ? '连接超时，请重试' : e.message));
    throw e;
  }
}
const RULE_ELEMENTS = [
  ['fire','火'], ['water','水'], ['wind','风'], ['light','光'], ['dark','暗'], ['neutral','中性'],
];
const RULE_CLASSES = [
  ['c1','Annie'],['c2','Beowulf'],['c3','Big Band'],['c4','Black Dahlia'],
  ['c5','Cerebella'],['c6','Double'],['c7','Eliza'],['c8','Filia'],['c9','Fukua'],
  ['c10','Marie'],['c11','Ms. Fortune'],['c12','Painwheel'],
];
function ruleLabel(rule) {
  if (!rule || !rule.type) return '无规则';
  if (rule.type === 'element') {
    const el = RULE_ELEMENTS.find(x => x[0] === rule.value);
    return '元素·' + (el ? el[1] : rule.value);
  }
  const c = RULE_CLASSES.find(x => x[0] === rule.value);
  return '类别·' + (c ? c[1] : rule.value);
}
/* 场次 tag (2026-10-06 起条件按 tag 取, 见 tools/pf_artag.py):
   场次列表要显示"这一场属于哪一类", 否则用户只看到数字条件, 不知道它按什么
   归类来的; hover 看tag_basis(判定依据) —— 判不出的场次是 unknown+最保守条件,
   看到unknown 才知道该人工确认。*/
const TAG_LABELS = {
  character: '角色场', element: '元素场', rift: '裂缝元素',
  monthly_character: '角色月场', monthly_element: '元素月场',
  gold: '金币场', assist: '星场', move: '招式场', unknown: '未归类',
};
function tagLabel(tag) { return TAG_LABELS[tag] || (tag || '未归类'); }
function buildRulePicker(container, opts = {}) {
  const sizeCls = opts.compact ? ' compact' : '';
  let html = `<button class="rbtn${sizeCls}" data-type="" data-value="">关</button><span class="vdiv"></span>`;
  for (const [v, n] of RULE_ELEMENTS) {
    const en = 'ElementalIcon' + v[0].toUpperCase() + v.slice(1);
    html += `<button class="rbtn el-${v}${sizeCls}" data-type="element" data-value="${v}"><img src="${UI_BASE}static/icons/sgm/${en}.png" alt="" onerror="this.hidden=true">${n}</button>`;
  }
  html += '<span class="vdiv"></span>';
  for (const [v, n] of RULE_CLASSES) {
    const fn = n.replace(/[^A-Za-z]/g, '');
    html += `<button class="rbtn cls-btn${sizeCls}" data-type="class" data-value="${v}" title="类别${v.slice(1)} · ${n}" aria-label="${n} 类别"><img src="${UI_BASE}static/icons/sgm/${fn}_MasteryIcon.png" alt="" onload="this.nextElementSibling.hidden=true" onerror="this.hidden=true"><span class="class-fallback">${v.slice(1).padStart(2,'0')}</span></button>`;
  }
  container.innerHTML = html;
  let rule = { type:'', value:'' }, locked = false;
  function render() {
    for (const b of container.querySelectorAll('.rbtn')) {
      const on = b.dataset.type
        ? rule.type === b.dataset.type && rule.value === b.dataset.value
        : !rule.type;
      b.classList.toggle('on', on);
      b.classList.toggle('disabled', locked);
      b.disabled = locked;
      b.setAttribute('aria-pressed', String(on));
    }
  }
  container.addEventListener('click', e => {
    const b = e.target.closest('.rbtn');
    if (!b || locked) return;
    rule = (b.dataset.type && !(rule.type === b.dataset.type && rule.value === b.dataset.value))
      ? { type: b.dataset.type, value: b.dataset.value } : { type:'', value:'' };
    render();
    if (opts.onchange) opts.onchange({ ...rule });
  });
  render();
  return {
    get: () => ({ ...rule }),
    set: r => { rule = r && r.type ? { type:r.type, value:r.value } : { type:'', value:'' }; render(); },
    setEnabled: v => { locked = !v; render(); },
  };
}
const navPicker = buildRulePicker(document.getElementById('ruleSeg'), {
  onchange: rule => {
    if (!activeSess || running) return;      // 规则与场次绑定: 只改当前选中场次
    api('/api/sessions/update', { id: activeSess, rule: rule.type ? rule : null });
  },
});
function updateSessChip(d) {
  const chip = document.getElementById('sess-chip');
  if (d.session_name) {
    chip.className = 'sess-chip pf-only' + (running ? ' locked' : '');
    chip.innerHTML = '场次 <b>' + esc(d.session_name) + '</b>' + (running ? ' 🔒' : '');
  } else {
    chip.className = 'sess-chip none pf-only';
    chip.textContent = '场次 未选';
  }
}

/* ---------- 今日场地 (2026-10-02: 每次「开始」进 PF 自动扫描录入) ---------- */
function renderArenas(arenas) {
  const dl = document.getElementById('arena-dl');
  const strip = document.getElementById('arena-strip');
  const list = (arenas && Array.isArray(arenas.arenas)) ? arenas.arenas : [];
  dl.innerHTML = list.filter(a => a.title)
    .map(a => `<option value="${esc(a.title)}">`).join('');
  if (!list.length) { strip.hidden = true; strip.innerHTML = ''; return; }
  strip.hidden = false;
  strip.innerHTML = '<span class="qs-label">今日场地</span>' + list.map(a =>
    `<span class="qs-item" data-arena="${esc(a.title)}" role="button"` +
    ` title="点击填入新场次的场地绑定 (也可手填 #${a.idx + 1} 按位置)">` +
    `#${a.idx + 1} ${esc(a.title || '(未识别)')}` +
    (a.score >= 0 ? ' · ' + fmtN(a.score) : '') + '</span>').join('');
}
document.getElementById('arena-strip').addEventListener('click', e => {
  const chip = e.target.closest('[data-arena]');
  if (!chip) return;
  const inp = document.getElementById('new-sess-scene');
  inp.value = chip.dataset.arena;
  inp.focus();
});

/* ---------- 连刷编排 (2026-10-02): 今日场地连成链, 勾选启动连刷 ---------- */
// 方块=扫描录入的场地 (pos=轮播位, 绑定走 #N 位置法); 链条=接力队列的顺序。
// 槽位场次由服务端 sync_chain 按 sid 复用, 每天场地轮换时标题自动跟新场走。
let chainBlocks = [], chainEnabled = false, stateArenas = null;

async function loadChain() {
  try {
    const d = await api('/api/chain');
    chainBlocks = Array.isArray(d.blocks) ? d.blocks : [];
    chainEnabled = !!d.enabled;
    renderChainUI();
    renderChainStatus();
  } catch (e) {}
}
function renderArenaPool() {
  const pool = document.getElementById('arena-pool');
  if (!pool) return;
  const list = (stateArenas && Array.isArray(stateArenas.arenas)) ? stateArenas.arenas : [];
  if (!list.length) {
    pool.innerHTML = '<div class="pool-hint" style="padding:8px 2px;">还没有扫描录入 —— 点「立即扫描场地」, 或正常开跑一次 (每天首次进 PF 自动扫描, 刷新线 01:00)。</div>';
    return;
  }
  pool.innerHTML = list.map(a => {
    const pos = a.idx + 1;
    const chained = chainBlocks.some(b => b.pos === pos);
    return `<span class="arena-block${chained ? ' in-chain' : ''}" data-pos="${pos}"` +
      ` data-title="${esc(a.title || '')}" role="button"` +
      ` title="${chained ? '已在链条中' : '点击接入连刷链条 (也可绑 ' + '#' + pos + ' 按位置)'}">` +
      `<span class="ab-pos">#${pos}${a.idx === 0 ? ' · 月场位' : ''}</span>` +
      `<b>${esc(a.title || '(未识别)')}</b>` +
      (a.score >= 0 ? `<span class="ab-score">${fmtN(a.score)}</span>` : '') + '</span>';
  }).join('');
}
function renderChainUI() {
  const box = document.getElementById('chain-list');
  const cb = document.getElementById('chain-on');
  if (cb.checked !== chainEnabled) cb.checked = chainEnabled;
  renderArenaPool();
  if (!chainBlocks.length) {
    box.innerHTML = '<div class="pool-hint" style="padding:8px 2px;">链条为空 —— 从左侧点场地方块接入, 勾选「连刷模式」启动。</div>';
    return;
  }
  box.innerHTML = chainBlocks.map((b, i) => {
    const link = i ? '<div class="chain-link" aria-hidden="true"></div>' : '';
    // target 只读显示 (2026-10-08): 分数上限唯一入口是「场次」页签。
    // 原来这里也有个 number 输入框, 保存链条时会静默覆盖场次的 score_target,
    // 同一字段两个可写入口必然分叉 (用户质疑 → 收敛为单一入口)。
    const tgt = b.target != null ? '≤' + fmtN(b.target) : '≤∞';
    return link + `<div class="chain-node" data-i="${i}">` +
      `<span class="cn-step">${i + 1}</span>` +
      `<span class="cn-pos">#${b.pos}</span><b class="cn-title">${esc(b.title)}</b>` +
      `<span class="cn-target" title="分数上限由「场次」页签设置 (留空=无上限); ` +
      `链条只读显示">${tgt}</span>` +
      `<button class="s-act" data-cup="${i}" title="上移">▲</button>` +
      `<button class="s-act" data-cdown="${i}" title="下移">▼</button>` +
      `<button class="s-act" data-cdel="${i}" title="从链条断开">✕</button></div>`;
  }).join('');
}
function renderChainStatus() {
  const el = document.getElementById('chain-status');
  if (!el) return;
  if (chainEnabled && sessQueue.length) {
    el.textContent = '连刷中 · 队列: ' + sessQueue.map(id => {
      const s = sessions.find(x => x.id === id);
      return s ? s.name : id;
    }).join(' → ');
  } else {
    el.textContent = '';
  }
}
async function saveChain() {
  const d = await api('/api/chain/save', { blocks: chainBlocks, enabled: chainEnabled });
  if (d && d.ok && Array.isArray(d.blocks)) chainBlocks = d.blocks;   // 回填 sid/最新标题
  renderChainUI();
  renderChainStatus();
}
async function toggleChain(on) {
  chainEnabled = on;
  if (on) {
    await saveChain();                     // 服务端: 槽位同步场次 + 按链设接力队列
    if (!running) {
      const first = chainBlocks[0];
      if (first && first.sid) {
        await api('/api/start', { session_id: first.sid });
        showNotice('连刷已启动: 从「' + first.title + '」开跑');
      } else {
        showNotice('链条为空 —— 先从「今日场地」点方块接入');
      }
    } else {
      showNotice('连刷队列已更新: 当前场次打完后按链条接续');
    }
  } else {
    await saveChain();                     // enabled=false: 服务端清接力队列
    await api('/api/end', {});
    showNotice('连刷已关闭, 本场次结束回待命 (链条配置保留)');
  }
  pollState();
}
async function requestScan() {
  await api('/api/scan', {});
  showNotice('已请求扫描: bot 待命中会去 PF hub 扫一遍录入 (几秒到几十秒)');
}
document.getElementById('arena-pool').addEventListener('click', e => {
  const blk = e.target.closest('.arena-block');
  if (!blk || blk.classList.contains('in-chain')) return;
  // target 不再由前端提供: 回填值由服务端按所绑场次的 score_target 给出。
  chainBlocks.push({ pos: Number(blk.dataset.pos), title: blk.dataset.title || '',
                     sid: null });
  saveChain();
});
document.getElementById('chain-list').addEventListener('click', e => {
  const up = e.target.closest('[data-cup]'), down = e.target.closest('[data-cdown]');
  const del = e.target.closest('[data-cdel]');
  if (up) {
    const i = Number(up.dataset.cup);
    if (i > 0) { [chainBlocks[i - 1], chainBlocks[i]] = [chainBlocks[i], chainBlocks[i - 1]]; saveChain(); }
  } else if (down) {
    const i = Number(down.dataset.cdown);
    if (i >= 0 && i < chainBlocks.length - 1) { [chainBlocks[i + 1], chainBlocks[i]] = [chainBlocks[i], chainBlocks[i + 1]]; saveChain(); }
  } else if (del) {
    chainBlocks.splice(Number(del.dataset.cdel), 1);
    saveChain();
  }
});
/* target 输入框已移除 (2026-10-08): 分数上限唯一入口 = 「场次」页签。
   原 change 监听随输入框一并删除, 链条 target 只读显示。 */
document.getElementById('chain-on').addEventListener('change', e => toggleChain(e.target.checked));

/* ---------- 接力队列 (2026-10-02): 打完一场自动接下一场 ---------- */
// /api/state 给 [{id,name}], /api/sessions 给 [id]; 归一成 id 数组存 sessQueue。
let sessQueue = [];
function normQueue(q) {
  return (Array.isArray(q) ? q : []).map(x => (x && x.id) ? x.id : x).filter(Boolean);
}
function renderQueueChip(queue) {
  if (Array.isArray(queue)) sessQueue = normQueue(queue);
  const el = document.getElementById('queue-chip');
  if (!sessQueue.length) { el.hidden = true; el.textContent = ''; return; }
  const names = sessQueue.map(id => {
    const s = sessions.find(x => x.id === id);
    return s ? s.name : id;
  });
  el.hidden = false;
  el.textContent = '接力 ' + names.length + ' 场: ' + names.join(' → ');
}
async function queueApply(ids) {
  const d = await api('/api/queue/set', { ids });
  if (d && d.ok) sessQueue = normQueue(d.queue);
  renderModal();
}
function ruleColor(rule) {
  if (!rule || !rule.type) return '#9aa6bf';
  if (rule.type === 'element') {
    const m = { fire:'#ff8a75', water:'#5ea8ff', wind:'#63e08c',
                light:'#f6c960', dark:'#b78bff', neutral:'#b9c3d8' };
    return m[rule.value] || '#7fb2ff';
  }
  return '#f6c960';   // 角色类别
}
// 运行中把规则按钮组缩成"已生效"徽章, 省出空间给表格/数据
function setRuleCollapsed(collapsed, rule) {
  document.getElementById('ruleSeg').style.display = collapsed ? 'none' : '';
  const badge = document.getElementById('rule-applied');
  badge.style.display = collapsed ? 'inline-flex' : 'none';
  if (collapsed) {
    const c = ruleColor(rule);
    badge.textContent = '规则 ' + (rule && rule.type ? ruleLabel(rule) : '关');
    badge.style.color = c;
    badge.style.borderColor = c;
  }
}

/* ---------- 开始弹窗: 选/建/改名/删 场次 ---------- */
const modalMask = document.getElementById('sess-modal');
let modalSel = null, renamingId = null, delArmId = null, delArmTimer = null, modalOpener = null;
const modalPicker = buildRulePicker(document.getElementById('new-sess-rule'), { compact: true });
function fmtDayTs(ts) { const d = new Date(ts*1000);
  return (d.getMonth()+1) + '/' + d.getDate() + ' ' + fmtTs(ts); }
function openSessionModal() {
  if (running) return;
  modalSel = activeSess || (sessions.length ? sessions[0].id : null);
  renamingId = null; delArmId = null;
  modalOpener = document.activeElement;
  renderModal();
  modalMask.style.display = 'flex';
  document.getElementById('new-sess-name').focus();
}
function closeModal() {
  modalMask.style.display = 'none';
  if (modalOpener) modalOpener.focus();
}
// 开始: 已有选中场次直接开, 没选过才弹选择弹窗
async function onStartClick() {
  if (running) return;
  if (!activeSess) { openSessionModal(); return; }
  const st = document.getElementById('status'), sp = document.getElementById('step');
  const prevSt = st.textContent, prevSp = sp.textContent;
  st.textContent = 'STARTING'; st.className = 'pill IDLE';
  sp.textContent = '正在启动…';
  let d = null;
  try {
    d = await api('/api/start', { session_id: activeSess });
  } catch (e) {
    d = { error: '请求失败: ' + e.message };
  }
  if (!d || d.error) {
    st.textContent = prevSt; st.className = 'pill ' + prevSt;
    sp.textContent = '启动失败: ' + ((d && d.error) || '无响应');
    alert('启动失败：' + ((d && d.error) || '无响应'));
    return;
  }
  // 模拟器没开时后端会拉起它 (秒级, 期间状态仍是 IDLE), 这里给个进度反馈,
  // 否则那十几秒看起来还是"没反应" —— 2026-09-22 的原始抱怨就是这个观感。
  if (d.starting_mumu) sp.textContent = '模拟器未开机, 正在拉起 MuMu…';
  pollState();
  pollMumu();
}
// 结束: 场次收尾回 IDLE 待命, 进程与 WebUI 保留; 再点「开始」同场续跑。
// 进程退出不走这里 —— /api/stop 语义未动, 托盘/调度按"等进程退出"依赖它。
async function onEndClick() {
  try { await api('/api/end', {}); } catch (e) {}
  loadChain();      // 结束会暂停连刷模式 (服务端改 chain.json), 回读勾选状态
  pollState();
}
async function renderModal() {
  try {
    const d = await api('/api/sessions');
    sessions = d.sessions; activeSess = d.active;
    sessQueue = normQueue(d.queue);
  } catch (e) {}
  if (!sessions.some(s => s.id === modalSel)) modalSel = sessions.length ? sessions[0].id : null;
  // 母子分组: 子场次紧跟父场次 (缩进展示), 顶级场次按创建顺序
  const kids = {}, isChild = new Set();
  sessions.forEach(s => {
    if (s.parent && sessions.some(x => x.id === s.parent)) {
      (kids[s.parent] = kids[s.parent] || []).push(s);
      isChild.add(s.id);
    }
  });
  const ordered = [];
  const walk = list => list.forEach(s => { ordered.push(s); if (kids[s.id]) walk(kids[s.id]); });
  walk(sessions.filter(s => !isChild.has(s.id)));
  const list = document.getElementById('sess-list');
  list.innerHTML = ordered.map(s => {
    const rest = (s.rest_every > 0 && s.rest_minutes > 0) ? ` · 休${s.rest_every}场×${s.rest_minutes}分` : '';
    const scoreTxt = (s.score != null && s.count) ? fmtN(s.score) + ' · ' : '';
    const meta = scoreTxt + (s.count ? s.count + ' 条 · ' : '无数据') + (s.last_ts ? fmtDayTs(s.last_ts) : '') + rest;
    const del = s.id === 'default' ? '' :
      `<button class="s-act${delArmId === s.id ? ' arm' : ''}" data-del="${s.id}">${delArmId === s.id ? '确认?' : '✕'}</button>`;
    const badges = `<span class="rule-badge tag-badge${s.tag ? '' : ' unknown'}"` +
      ` title="归类依据: ${esc(s.tag_basis || '未记录(未迁移或人工建场)')}">${esc(tagLabel(s.tag))}</span>` +
      `<span class="rule-badge">${ruleLabel(s.rule)}</span>` +
      `<span class="rule-badge">能量${s.energy_cost != null ? s.energy_cost : 4}</span>` +
      // score_target=null 是「无上限」(月场口径, 打到手动停), 不是漏填 ——
      // 两种情况都显示徽章, 否则用户分不清"无上限"和"没填"。
      `<span class="rule-badge${s.score_target == null ? ' nolimit' : ''}"` +
      ` title="${s.score_target == null ? '无上限: 一直打到手动停' : '分数上限: 达标即停场'}">` +
      `${s.score_target != null ? '≤' + fmtN(s.score_target) : '≤∞'}</span>` +
      (s.scene ? `<span class="rule-badge" title="场地绑定: 开始时自动识别并居中该场">📍${esc(s.scene)}</span>` : '') +
      (kids[s.id] ? `<span class="rule-badge parent-badge">${kids[s.id].length}期</span>` : '');
    const isChildRow = isChild.has(s.id);
    const indent = isChildRow ? '└ ' : '';
    const nameHtml = renamingId === s.id
      ? `<input id="rn-input" class="inp" value="${esc(s.name)}">`
      : `<span class="s-name">${esc(indent + s.name)}</span>`;
    const inQ = sessQueue.includes(s.id);
    const qbtn = `<button class="s-act${inQ ? ' onq' : ''}" data-qtoggle="${s.id}"` +
      ` title="${inQ ? '移出接力队列' : '加入接力队列 (打完一场自动接这一场)'}">${inQ ? '⛓' : '☰'}</button>`;
    return `<div class="sess-row${isChildRow ? ' child' : ''}${s.id === modalSel ? ' sel' : ''}" data-id="${s.id}">
      ${nameHtml}${badges}
      <span class="s-meta">${meta}</span>
      ${qbtn}<button class="s-act" data-rename="${s.id}" title="重命名">✎</button>${del}</div>`;
  }).join('') || '<div style="color:var(--faint);padding:20px;text-align:center;">还没有场次</div>';
  renderQueueStrip();
  document.getElementById('sess-start').disabled = !modalSel;
  document.getElementById('sess-pick').disabled = !modalSel;
  document.getElementById('sess-child').disabled = !modalSel;
  const inp = document.getElementById('rn-input');
  if (inp) { inp.focus(); inp.select(); }
}
function renderQueueStrip() {
  const strip = document.getElementById('queue-strip');
  if (!sessQueue.length) { strip.hidden = true; strip.innerHTML = ''; return; }
  strip.hidden = false;
  strip.innerHTML = '<span class="qs-label">接力队列</span>' + sessQueue.map((id, i) => {
    const s = sessions.find(x => x.id === id);
    const nm = s ? s.name : id;
    return `<span class="qs-item"><b>${i + 1}</b>. ${esc(nm)}` +
      `<button class="s-act" data-qup="${id}" title="上移">▲</button>` +
      `<button class="s-act" data-qdown="${id}" title="下移">▼</button>` +
      `<button class="s-act" data-qdel="${id}" title="移出队列">✕</button></span>`;
  }).join('') + '<span class="hint">打完当前场后按此顺序自动接续</span>';
}
document.getElementById('sess-list').addEventListener('click', async e => {
  const qt = e.target.closest('[data-qtoggle]');
  if (qt) {
    const id = qt.dataset.qtoggle;
    const ids = sessQueue.includes(id) ? sessQueue.filter(x => x !== id)
                                       : sessQueue.concat(id);
    await queueApply(ids);
    return;
  }
  const ren = e.target.closest('[data-rename]');
  if (ren) { renamingId = ren.dataset.rename; renderModal(); return; }
  const del = e.target.closest('[data-del]');
  if (del) {
    const id = del.dataset.del;
    if (delArmId === id) {          // 两段式删除: 第二次点确认
      delArmId = null; clearTimeout(delArmTimer);
      await api('/api/sessions/delete', { id });
      if (modalSel === id) modalSel = null;
    } else {
      delArmId = id;
      clearTimeout(delArmTimer);
      delArmTimer = setTimeout(() => { delArmId = null; renderModal(); }, 2500);
    }
    renderModal(); return;
  }
  if (e.target.closest('#rn-input')) return;   // 改名输入中, 不切换选择
  const row = e.target.closest('.sess-row');
  // 单击 = 选中**并立即落盘**(2026-10-06 用户口径: 选中后关掉界面也该保持选中)。
  // 改前这里只改前端 modalSel, 实际场次没变 —— 关掉弹窗再开又回到旧的。
  if (row) { await selectSession(row.dataset.id, { keepModal: true }); }
});
/* 双击 = 选中并直接开始 (省一次点「开始」)。用dblclick 而非两次 click:
   两次 click 会先触发上面的单击落盘 + renderModal 重绘, 行元素被替换后
   dblclick 未必落在同一行上 —— 所以这里靠 dblclick 的原生语义, 元素不变。*/
document.getElementById('sess-list').addEventListener('dblclick', async e => {
  if (e.target.closest('#rn-input')) return;
  const row = e.target.closest('.sess-row');
  if (row) { await selectSession(row.dataset.id); await startSession(); }
});
document.getElementById('queue-strip').addEventListener('click', async e => {
  const up = e.target.closest('[data-qup]'), down = e.target.closest('[data-qdown]');
  const del = e.target.closest('[data-qdel]');
  let ids = null;
  if (up) {
    const i = sessQueue.indexOf(up.dataset.qup);
    if (i > 0) { ids = sessQueue.slice(); [ids[i - 1], ids[i]] = [ids[i], ids[i - 1]]; }
  } else if (down) {
    const i = sessQueue.indexOf(down.dataset.qdown);
    if (i >= 0 && i < sessQueue.length - 1) { ids = sessQueue.slice(); [ids[i + 1], ids[i]] = [ids[i], ids[i + 1]]; }
  } else if (del) {
    ids = sessQueue.filter(x => x !== del.dataset.qdel);
  }
  if (ids) await queueApply(ids);
});
document.getElementById('sess-list').addEventListener('keydown', e => {
  if (e.target.id === 'rn-input' && e.key === 'Enter') saveRename();
});
document.getElementById('sess-list').addEventListener('focusout', e => {
  if (e.target.id === 'rn-input') saveRename();
});
async function saveRename() {
  const inp = document.getElementById('rn-input');
  if (!inp) return;
  const name = inp.value.trim(), sid = renamingId;
  renamingId = null;
  if (name && sid) await api('/api/sessions/update', { id: sid, name });
  renderModal();
}
document.getElementById('new-sess-btn').addEventListener('click', async () => {
  const name = document.getElementById('new-sess-name').value.trim();
  if (!name) return;
  const rule = modalPicker.get();
  const t = document.getElementById('new-sess-target').value.trim();
  const en = document.getElementById('new-sess-energy').value.trim();
  const rn = document.getElementById('new-sess-restn').value.trim();
  const rm = document.getElementById('new-sess-restm').value.trim();
  const scene = document.getElementById('new-sess-scene').value.trim();
  const d = await api('/api/sessions/create', { name, rule: rule.type ? rule : null,
    score_target: t === '' ? null : Number(t),
    energy_cost: en === '' ? 4 : Number(en),
    rest_every: rn === '' ? 0 : Number(rn), rest_minutes: rm === '' ? 0 : Number(rm),
    scene });
  document.getElementById('new-sess-name').value = '';
  document.getElementById('new-sess-target').value = '';
  document.getElementById('new-sess-energy').value = '';
  document.getElementById('new-sess-restn').value = '';
  document.getElementById('new-sess-restm').value = '';
  document.getElementById('new-sess-scene').value = '';
  modalPicker.set(null);
  modalSel = d.id;
  renderModal();
});
document.getElementById('sess-start').addEventListener('click', async () => {
  // 先落盘选中再开始: 否则 /api/start 跑的是服务端**旧 active** 场次,
  // 与用户在这弹窗里点选的那场不一致。
  await selectSession(modalSel, { keepModal: true });
  await startSession();
});
/* 选中场次**并落盘**(2026-10-06): 单击行即走这里, 关掉弹窗后仍是选中状态。
   改前单击只改前端 modalSel, 服务端 active没动 —— 关窗再开就弹回旧场次。
   keepModal=true 时不关窗(单击只高亮); 双击走默认 false, 顺带开跑。
   ⚠️ 错误提示交给 api() 统一弹(它见到 data.error 会自己 showNotice),
      这里不要重复判断/再弹一次。*/
async function selectSession(sid, opts = {}) {
  if (!sid || running) return;
  modalSel = sid; renamingId = null;
  const ok = await api('/api/sessions/select', { id: sid });   // 参数名是 id
  if (ok === undefined) return;                // api 抛异常(已提示过), 不往下走
  activeSess = sid;                       // 前端同步, 否则顶栏仍显示旧场次
  renderModal();
  if (!opts.keepModal) closeModal();
  pollState();
}
/* 双击=选中并直接开始。运行中不响应(后端也拒), 与「开始」按钮同语义。*/
async function startSession() {
  if (!modalSel || running) return;
  await api('/api/start', { session_id: modalSel });
  closeModal();
  pollState();
}
document.getElementById('sess-pick').addEventListener('click', async () => {
  await selectSession(modalSel);
});
document.getElementById('sess-child').addEventListener('click', async () => {
  if (!modalSel) return;
  const d = await api('/api/sessions/child', { id: modalSel });
  modalSel = d.id;          // 建好即选中子场次 (可改名/直接开始)
  renderModal();
});
document.getElementById('sess-cancel').addEventListener('click', closeModal);
modalMask.addEventListener('click', e => { if (e.target === modalMask) closeModal(); });
document.getElementById('in-fav').addEventListener('change', e => {
  e.target.dataset.touched = '1'; saveSettings();
});
document.getElementById('in-closegoal').addEventListener('change', e => {
  e.target.dataset.touched = '1'; saveSettings();
});
async function saveSettings() {               // 全局: 喜爱 / 达标关模拟器
  await uiFetch('/api/settings', { method:'POST', headers:{'Content-Type':'application/json'},
    body: JSON.stringify({ filter_favorite: document.getElementById('in-fav').checked,
      close_mumu_on_goal: document.getElementById('in-closegoal').checked }) });
}
async function saveSessionSettings() {        // 随场次: 能量/分数上界/休息/场地绑定
  if (!activeSess || running) return;
  const e = document.getElementById('in-energy').value.trim();
  const t = document.getElementById('in-target').value.trim();
  const rn = document.getElementById('in-restn').value.trim();
  const rm2 = document.getElementById('in-restm').value.trim();
  const scene = document.getElementById('in-scene').value.trim();
  await api('/api/sessions/update', { id: activeSess,
    energy_cost: e === '' ? 4 : Number(e),
    score_target: t === '' ? null : Number(t),
    rest_every: rn === '' ? 0 : Number(rn), rest_minutes: rm2 === '' ? 0 : Number(rm2),
    scene });   // 空串=清除绑定, 后端 clean_scene 归一
}
document.getElementById('in-target').addEventListener('change', saveSessionSettings);
document.getElementById('in-energy').addEventListener('change', saveSessionSettings);
document.getElementById('in-restn').addEventListener('change', saveSessionSettings);
document.getElementById('in-restm').addEventListener('change', saveSessionSettings);
document.getElementById('in-scene').addEventListener('change', saveSessionSettings);

/* ================= JJC 日程 (sgmnow 快照 / 场次×规则版本账本) =================
   数据来自 Krazete 的 SGM Score Cutoffs 表 now 页; 快照按 SGM reset 日归档,
   所以"今日"指的是游戏日而不是本机日历日。                                      */

// 元素 -> sgm 官方图标名 (sgmnow 的约定: ElementalIcon<Element>.png)
const JJC_EL_ICON = {Fire:'Fire', Water:'Water', Wind:'Wind', Light:'Light',
                     Dark:'Dark', Neutral:'Neutral'};

const CHARACTER_ICON_FILES = new Set([
  'Annie', 'Beowulf', 'BigBand', 'BlackDahlia', 'BrainDrain', 'Cerebella',
  'Double', 'Eliza', 'Filia', 'Fukua', 'Marie', 'Minette', 'MsFortune',
  'Painwheel', 'Parasoul', 'Peacock', 'Random', 'Robofortune', 'Squigly', 'Umbrella', 'Valentine',
]);
function characterIcon(name) {
  let file = String(name || '').replace(/[^A-Za-z]/g, '');
  if (file === 'RoboFortune') file = 'Robofortune';
  return CHARACTER_ICON_FILES.has(file) ? `${UI_BASE}static/icons/sgm/${file}_MasteryIcon.png` : '';
}
function jjcIcon(e) {
  if (e.kind === 'rift' || e.kind === 'element') {
    const name = (e.name || '').trim();
    if (JJC_EL_ICON[name]) return `${UI_BASE}static/icons/sgm/ElementalIcon${name}.png`;
  }
  if (e.kind === 'character') return characterIcon(e.name);
  return '';
}

function jjcBadge(e) {
  if (e.volatile) return '<span class="jbadge vol">计算中</span>';
  if (e.active) return '<span class="jbadge on">开放</span>';
  if (e.scope === 'last') return '<span class="jbadge last">最近一次</span>';
  return '<span class="jbadge last">未开</span>';
}

function renderJJC(d) {
  const snap = d.snapshot;
  const src = document.getElementById('jjc-src');
  if (!snap || snap.missing) {
    src.innerHTML = '<span class="stale">尚无 JJC 快照</span> —— 点「刷新快照」从 sgmnow 拉一次';
    document.getElementById('jjc-daily').innerHTML = '';
    document.getElementById('jjc-grid').innerHTML = '';
  } else {
    const seg = [];
    seg.push(`游戏日 <b>${esc(snap.day)}</b> · rev${snap.revision || 1} · fp=${esc(snap.fp || '')}`);
    if (snap.source && snap.source.last_edit) seg.push(`源更新 ${esc(snap.source.last_edit)}`);
    if (snap.stale) seg.push('<span class="stale">当日未取到，显示的是最近一次归档</span>');
    seg.push('<a href="https://krazete.github.io/sgmnow/" target="_blank" rel="noopener">sgmnow ↗</a>');
    src.innerHTML = seg.join(' · ');

    document.getElementById('jjc-daily').innerHTML =
      (snap.daily_events && snap.daily_events.length)
        ? snap.daily_events.map(n => {
            const icon = characterIcon(n);
            const ic = icon ? `<img src="${icon}" alt="" onerror="this.hidden=true">` : '';
            return `<span class="chip">${ic}${esc(n)}</span>`;
          }).join('')
        : '<span id="jjc-empty">未取到 daily 名单</span>';

    document.getElementById('jjc-grid').innerHTML = (snap.entries || []).map(e => {
      const ic = jjcIcon(e);
      return `<div class="jjc-card${e.active ? '' : ' off'}">
        ${ic ? `<img src="${ic}" alt="" onerror="this.hidden=true">` : '<span style="flex:0 0 34px;"></span>'}
        <div class="jtxt">
          <div class="jk">${esc(e.label_cn)}</div>
          <div class="jn">${e.name ? esc(e.name) : '—'}${jjcBadge(e)}</div>
        </div></div>`;
    }).join('');
  }
  renderVersions(d.versions, d.session_names || {});
}

function renderVersions(vers, names) {
  const box = document.getElementById('jjc-versions');
  const ids = Object.keys(vers || {});
  if (!ids.length) { box.innerHTML = '<div id="jjc-empty">尚无版本记录</div>'; return; }
  box.innerHTML = ids.map(sid => {
    const hist = vers[sid] || [];
    const last = hist[hist.length - 1] || {};
    const rows = hist.slice().reverse().map(v => {
      const cfg = v.cfg || {};
      const jj = v.jjc;
      const jjTxt = jj ? `当日台上: ${[jj.char, jj.elem, jj.holi].filter(Boolean).join(' / ') || '—'}` : '';
      return `<div class="ver-row${v.op === 'delete' ? ' del' : ''}">
        <span class="ver-v">v${v.v}</span>
        <span class="ver-t">${esc(v.time)} · ${esc(v.op)}</span>
        <span class="ver-c">
          <div>改了 <b>${esc((v.changed || []).join('、'))}</b>
            <span style="color:var(--faint);">fp=${esc(v.fp)}</span></div>
          <div class="ver-cfg">${esc(ruleLabel(cfg.rule))} · 上界${cfg.score_target ?? '不限'} ·
            能量${cfg.energy_cost} · 休${cfg.rest_every}x${cfg.rest_minutes}${jjTxt ? ' · ' + esc(jjTxt) : ''}</div>
        </span></div>`;
    }).join('');
    return `<div style="margin-bottom:14px;">
      <div class="sect-title">${esc(names[sid] || last.session || sid)}
        <span style="letter-spacing:0;color:var(--faint);">${esc(sid)} · ${hist.length} 版</span></div>
      ${rows}</div>`;
  }).join('');
}

async function loadJJC(force) {
  try {
    // api() 直接返回已解析的 JSON; fetch 这条要自己解一层
    const d = force ? await api('/api/jjc/refresh', {})
                    : await (await uiFetch('/api/jjc')).json();
    renderJJC(d);
  } catch (e) {
    document.getElementById('jjc-src').innerHTML =
      '<span class="stale">读取失败: ' + esc(String(e)) + '</span>';
  }
  loadConditions();
}

/* ================= 场次默认条件 (可配置界面, 2026-10-06) =================
   条件表原本是代码常量, 改一次要动代码重启 bot; 现在落
   debug/pf/tag_conditions.json, 这里是它的编辑器。
   ⚠️ 改的是**新建场次**的取值; 已在跑的场次条件已写进 sessions.json,
      不受这里影响 (要改已建场次得去场次列表逐个改)。                      */
const COND_RULES = [['', '无规则'], ['class', '角色(防守队限定)'], ['element', '元素']];
let condTable = null, condDirty = false;

function renderConditions() {
  const box = document.getElementById('cond-table');
  if (!condTable) { box.innerHTML = '<div style="color:var(--faint);padding:12px;">读取中…</div>'; return; }
  const rows = Object.keys(condTable).sort().map(tag => {
    const e = condTable[tag];
    const unlimited = e.score_target == null;
    return `<div class="cond-row" data-tag="${esc(tag)}">
      <span class="cond-name">${esc(e.label || tag)}<span class="cond-key">${esc(tag)}</span></span>
      <input class="inp cond-tgt" type="number" min="0" step="1000000"
             placeholder="留空=无上限" value="${unlimited ? '' : e.score_target}">
      <input class="inp cond-ec" type="number" min="1" max="10" step="1" value="${e.energy_cost}">
      <select class="inp cond-rule">${COND_RULES.map(([v, n]) =>
        `<option value="${v}"${e.rule === (v || null) ? ' selected' : ''}>${n}</option>`).join('')}</select>
      <button class="s-act cond-del" title="删除该条(该类别将走未识别兜底)">✕</button>
    </div>`;
  }).join('');
  const u = condTable.unknown || { score_target: 40000000, energy_cost: 4, label: '未识别兜底' };
  box.innerHTML = `<div class="cond-head">
      <span>类别</span><span>分数上限</span><span>能量</span><span>队伍规则</span><span></span>
    </div>` + rows + `
    <div class="cond-row fixed">
      <span class="cond-name">${esc(u.label)}<span class="cond-key">固定</span></span>
      <span class="cond-val">${fmtN(u.score_target)}</span>
      <span class="cond-val">${u.energy_cost}</span>
      <span class="cond-val">无规则</span><span></span>
    </div>`;

  const names = document.getElementById('cond-names');
  if (names && window.__CN_RULES) {
    names.innerHTML = window.__CN_RULES.map(([pat, tag]) =>
      `<span class="chip" title="${esc(pat)}">${esc(pat)} → <b>${esc(tag)}</b></span>`).join('');
  }
}

function collectConditions() {
  const out = {};
  document.querySelectorAll('#cond-table .cond-row[data-tag]').forEach(row => {
    const tag = row.dataset.tag;
    const tgtRaw = row.querySelector('.cond-tgt').value.trim();
    out[tag] = {
      score_target: tgtRaw === '' ? null : Number(tgtRaw),
      energy_cost: Number(row.querySelector('.cond-ec').value) || 4,
      rule: row.querySelector('.cond-rule').value || null,
      label: (condTable[tag] || {}).label || tag,
    };
  });
  return out;
}
function markCondDirty(on) {
  condDirty = on;
  document.getElementById('cond-save').disabled = !on;
  document.getElementById('cond-note').textContent = on ? '有未保存的修改' : '';
}

async function loadConditions() {
  try {
    const d = await (await uiFetch('/api/conditions')).json();
    condTable = d.conditions;
    window.__CN_RULES = d.cn_rules || [];
    renderConditions();
    markCondDirty(false);
    document.getElementById('cond-note').textContent =
      '落盘: ' + (d.path || '');
  } catch (e) {
    document.getElementById('cond-table').innerHTML =
      '<div class="stale" style="padding:12px;">读取失败: ' + esc(String(e)) + '</div>';
  }
}
document.getElementById('cond-table').addEventListener('input', e => {
  if (e.target.closest('.cond-row[data-tag]')) markCondDirty(true);
});
document.getElementById('cond-table').addEventListener('click', e => {
  const b = e.target.closest('.cond-del');
  if (!b) return;
  const row = b.closest('.cond-row');
  if (!confirm(`删除「${row.querySelector('.cond-name').childNodes[0].textContent}」？该类别将走未识别兜底条件。`)) return;
  const tag = row.dataset.tag;
  const snap = condTable[tag];
  delete condTable[tag];
  renderConditions();
  markCondDirty(true);
  condTable['_undo_' + tag] = snap;          // 供本次会话内误删找回
});
document.getElementById('cond-reload').addEventListener('click', loadConditions);
document.getElementById('cond-save').addEventListener('click', async () => {
  const table = collectConditions();
  const r = await api('/api/conditions', { conditions: table });
  if (r && r.ok) {
    condTable = r.conditions;
    renderConditions();
    markCondDirty(false);
    document.getElementById('cond-note').textContent = r.message || '已保存';
  }
});
document.getElementById('jjc-refresh')
  .addEventListener('click', () => loadJJC(true));

/* ================= 每日任务 (任务库 / 详情 / 每日请求列表) =================
   任务池整理自 docs/explore/2026-09-05/REPORT.md; 可合并的日常动作已组合成单条。 */
const DAILY_TASKS = [
  { id:'missions', group:'daily', name:'任务与积分领取', ref:'REPORT §2.1',
    hint:'MISSIONS·DAILY OPS: CLAIM ALL + 积分轨里程碑箱 (20/40/60/80/100)。Win a Prize Fight Match / Log In / Open a Relic 等任务随日常自动完成, 无需单独跑。' },
  { id:'backstage', group:'daily', name:'通行证目标', ref:'REPORT §3.12',
    hint:'BACKSTAGE PASS·GOALS 页签: DAILY / WEEKLY GOALS 达成后领取 BP XP (如 Participate in 2 PF Matches、Open a Relic)。' },
  { id:'guild_ops', group:'daily', name:'公会任务领取', ref:'REPORT §2.2',
    hint:'GUILD OPS: CLAIMABLE OPS 立即领; DAILY OPS (35/60 GuildOps 积分随打竞技场推进) 完成后领。注意滚动会弹 NEW REWARD TIER 段位框, OK 关闭。' },
  { id:'social', group:'daily', name:'礼物收发', ref:'REPORT §3.11',
    hint:'组合任务 — SOCIAL HUB 三连: SEND ALL + CLAIM ALL + OPEN GIFTS, 同时覆盖 Send a Gift 日常任务。' },
  { id:'inbox', group:'daily', name:'邮箱领取', ref:'REPORT §3.11',
    hint:'INBOX / GUILDS 两子栏: 赛季结算、活动补偿等附件领取 (附件 29 天过期)。' },
  { id:'rewards', group:'daily', name:'登录奖励与广告', ref:'REPORT §3.11',
    hint:'组合任务 — REWARDS 页: LOGIN REWARDS 月历领取 + VIEWING PARLOR 看广告 (6 格, 30 分钟冷却刷新)。WEB REWARDS 需网页端, 仅提醒不执行。' },
  { id:'tickets', group:'daily', name:'活动票领取', ref:'REPORT §3.3',
    hint:'EVENTS 轮播顶部活动票 CLAIM (绿骷髅票 / 橙票两态)。' },
  { id:'store', group:'daily', name:'商店日常', ref:'REPORT §3.9',
    hint:'STORE: DAILY PASSES CLAIM + DAILY DEALS 浏览。注意 DAILY PASSES 的 CLAIM 曾实测点击无响应, 执行前需先验证可交互。' },
  { id:'cabinet', group:'daily', name:'奇物阁', ref:'REPORT §3.11',
    hint:'CABINET OF CURIOSITIES: 进入即完成 Open the Cabinet 日常; 4h 刷新, 顺路逛 TRINKETS / TREASURES / TRIBUTES。' },
  { id:'daily_event', group:'daily', name:'日常活动对战', ref:'REPORT §3.3',
    hint:'EVENTS 日常活动卡 (SWEATING BULLETS / DOUBLE FEATURE 等) 每天 3 次 PLAYS REMAINING, 完成 Win a Daily Event Match。' },
  { id:'story', group:'daily', name:'剧情对战', ref:'REPORT §3.2',
    hint:'STORY MODE 打一场, 完成 Win a Story Mode Match; 可顺路推 MAIN STORY / ORIGIN STORIES 章节星级。' },
  { id:'rift', group:'daily', name:'裂隙战', ref:'REPORT §3.4',
    hint:'组合任务 — RIFT BATTLES 打一场, 同时推进日任务 Complete a Rift Battle 与公会周任务 Win 1/3/5 Rift Battle Matches。注意 CONNECTING 加载页。' },
  { id:'nurture', group:'daily', name:'每日养成', ref:'REPORT §2.1',
    hint:'组合任务 — 三条日常一并完成: Level Up a Guest Star (或 Reroll) + Level Up a Move (或 Reroll) + Unlock a Skill Tree Node, 各做一次即可。' },
  { id:'relics', group:'daily', name:'开箱', ref:'REPORT §3.8',
    hint:'RELICS 开库存箱完成 Open a Relic (消耗箱体库存, 执行前确认数量); 结果页可 SELL ALL 清理。' },
  { id:'deployments', group:'daily', name:'派驻', ref:'REPORT §2.4',
    hint:'DEPLOYMENTS: 每天 5 次派驻机会, 15 分钟短任务性价比最高, 记得回收。' },
  { id:'guild_weekly', group:'guild', name:'公会周玩法', ref:'REPORT §2.2 / §3.3',
    hint:'WEEKLY OPS 指向的三大玩法入口 (都在 EVENTS 内): Undying Battle / Parallel Realms (Boss Node) / Accursed Experiments。' },
  { id:'rift_season', group:'guild', name:'裂隙赛季结算', ref:'REPORT §3.4',
    hint:'SEASON REWARDS 每周一 10am PT 结束发邮箱; 需打满 5 场且单场 ≥3000 分才有奖励。' },
];
const DAILY_GROUPS = [['daily','每日'], ['guild','公会 · 每周']];
let queueOrder = [], detailSel = null, dailyLoaded = false;
let poolOrder = { daily: [], guild: [] }, poolNames = {}, poolRenameId = null;
const taskById = id => DAILY_TASKS.find(t => t.id === id);
const dName = t => poolNames[t.id] || t.name;          // 自定义名优先
const defaultOrder = () => DAILY_TASKS.filter(t => t.group === 'daily').map(t => t.id);

async function loadDaily() {
  if (dailyLoaded) { renderPool(); renderQueue(); renderDetail(); return; }
  dailyLoaded = true;
  let data = {};
  try {
    const d = await api('/api/daily');
    if (d.saved && d.data) data = d.data;
  } catch (e) {}
  queueOrder = (data.queue || defaultOrder()).filter(id => taskById(id));
  const names = data.names || {};
  for (const k in names)
    if (taskById(k) && String(names[k]).trim()) poolNames[k] = String(names[k]).trim();
  for (const g of ['daily', 'guild']) {
    const saved = (data.pool && data.pool[g]) || [];
    poolOrder[g] = saved.filter(id => { const t = taskById(id); return t && t.group === g; });
    for (const t of DAILY_TASKS)
      if (t.group === g && !poolOrder[g].includes(t.id)) poolOrder[g].push(t.id);
  }
  renderPool(); renderQueue(); renderDetail();
}
function saveDaily() {
  api('/api/daily', { queue: queueOrder, pool: poolOrder, names: poolNames }).catch(() => {});
}
function toggleTask(id) {
  queueOrder = queueOrder.includes(id) ? queueOrder.filter(x => x !== id) : queueOrder.concat(id);
  renderPool(); renderQueue(); saveDaily();
}
function renderPool() {
  document.getElementById('pool-list').innerHTML = DAILY_GROUPS.map(([g, label]) => {
    const rows = poolOrder[g].map(id => {
      const t = taskById(id);
      if (!t) return '';
      const nameHtml = poolRenameId === id
        ? `<input id="pool-rn" class="inp" value="${esc(dName(t))}">`
        : `<span class="t-name" title="${esc(t.hint)}">${esc(dName(t))}</span>`;
      return `<div class="task-row${detailSel === id ? ' sel' : ''}" data-id="${id}">
        <span class="q-handle" title="拖动排序">☰</span>
        <input type="checkbox" ${queueOrder.includes(id) ? 'checked' : ''}>
        ${nameHtml}
        <button class="t-rename" title="重命名">✎</button>
        <button class="t-gear" title="查看详情">⚙</button></div>`;
    }).join('');
    return `<div class="pool-group">${label}</div><div class="pool-sec" data-group="${g}">${rows}</div>`;
  }).join('');
  const rn = document.getElementById('pool-rn');
  if (rn) { rn.focus(); rn.select(); }
}
function renderQueue() {
  document.getElementById('queue-count').textContent = queueOrder.length ? queueOrder.length + ' 项' : '';
  document.getElementById('queue-list').innerHTML = queueOrder.map((id, i) => {
    const t = taskById(id);
    return `<div class="q-item" data-id="${id}">
      <span class="q-idx">${i + 1}</span><span class="q-handle" title="拖动排序">☰</span>
      <span class="q-name">${esc(t ? dName(t) : id)}</span>
      <button class="q-del" title="移除">✕</button></div>`;
  }).join('') || '<div class="q-empty">空 —— 在左侧勾选任务加入列表</div>';
}
function renderDetail() {
  const body = document.getElementById('detail-body');
  const t = taskById(detailSel);
  if (!t) { body.innerHTML = '<div class="q-empty">点左侧任务行的 ⚙ 查看详情</div>'; return; }
  const g = DAILY_GROUPS.find(x => x[0] === t.group);
  body.innerHTML = `<div class="d-head">${esc(dName(t))}</div>
    <div class="d-badges"><span class="rule-badge">${g ? g[1] : ''}</span>
    <span class="rule-badge">出处 ${esc(t.ref)}</span></div>
    <div class="d-hint">${esc(t.hint)}</div>
    <div class="d-ph"><div class="d-ph-title">任务编排</div>
      <div class="d-ph-note">此任务暂无独立参数。当前仅保存请求顺序，自动执行逻辑尚未接入。</div></div>`;
}
document.getElementById('pool-list').addEventListener('click', e => {
  if (e.target.closest('.q-handle')) return;      // 拖动手柄不触发点击
  const row = e.target.closest('.task-row');
  if (!row) return;
  if (e.target.closest('.t-gear')) {              // ⚙ = 选中详情, 不切换勾选
    detailSel = detailSel === row.dataset.id ? null : row.dataset.id;
    renderPool(); renderDetail(); return;
  }
  if (e.target.closest('.t-rename')) {            // ✎ = 行内改名
    poolRenameId = row.dataset.id; renderPool(); return;
  }
  toggleTask(row.dataset.id);
});
function savePoolRename() {
  const inp = document.getElementById('pool-rn');
  if (!inp) return;
  const v = inp.value.trim(), id = poolRenameId;
  poolRenameId = null;
  if (v && id) {
    poolNames[id] = v;
    renderPool(); renderQueue(); renderDetail(); saveDaily();
  } else renderPool();
}
document.getElementById('pool-list').addEventListener('keydown', e => {
  if (e.target.id !== 'pool-rn') return;
  if (e.key === 'Enter') savePoolRename();
  if (e.key === 'Escape') { poolRenameId = null; renderPool(); }
});
document.getElementById('pool-list').addEventListener('focusout', e => {
  if (e.target.id === 'pool-rn') savePoolRename();
});
document.getElementById('pool-all').addEventListener('click', () => {
  queueOrder = queueOrder.concat(defaultOrder().filter(id => !queueOrder.includes(id)));
  renderPool(); renderQueue(); saveDaily();
});
document.getElementById('pool-clear').addEventListener('click', () => {
  queueOrder = []; renderPool(); renderQueue(); saveDaily();
});
document.getElementById('queue-list').addEventListener('click', e => {
  const del = e.target.closest('.q-del');
  if (!del) return;
  queueOrder = queueOrder.filter(x => x !== del.closest('.q-item').dataset.id);
  renderPool(); renderQueue(); saveDaily();
});
// 请求列表拖拽排序 (pointer 事件, 鼠标/触屏通用)
const queueBox = document.getElementById('queue-list');
queueBox.addEventListener('pointerdown', e => {
  const handle = e.target.closest('.q-handle');
  if (!handle) return;
  const item = handle.closest('.q-item');
  if (!item) return;
  e.preventDefault();
  item.classList.add('dragging');
  let moved = false;
  const onMove = ev => {
    const y = ev.clientY;
    const next = [...queueBox.querySelectorAll('.q-item:not(.dragging)')]
      .find(el => { const r = el.getBoundingClientRect(); return y < r.top + r.height / 2; });
    if (next) queueBox.insertBefore(item, next); else queueBox.appendChild(item);
    moved = true;
  };
  const onUp = () => {
    document.removeEventListener('pointermove', onMove);
    document.removeEventListener('pointerup', onUp);
    document.removeEventListener('pointercancel', onUp);
    item.classList.remove('dragging');
    if (moved) {
      queueOrder = [...queueBox.querySelectorAll('.q-item')].map(el => el.dataset.id);
      renderQueue(); saveDaily();
    }
  };
  document.addEventListener('pointermove', onMove);
  document.addEventListener('pointerup', onUp);
  document.addEventListener('pointercancel', onUp);
});
// 任务库拖动排序: ☰ 手柄, 限在所属组内移动
document.getElementById('pool-list').addEventListener('pointerdown', e => {
  const handle = e.target.closest('.q-handle');
  if (!handle) return;
  const item = handle.closest('.task-row');
  const sec = handle.closest('.pool-sec');
  if (!item || !sec) return;
  e.preventDefault();
  item.classList.add('dragging');
  const onMove = ev => {
    const y = ev.clientY;
    const next = [...sec.querySelectorAll('.task-row:not(.dragging)')]
      .find(el => { const r = el.getBoundingClientRect(); return y < r.top + r.height / 2; });
    if (next) sec.insertBefore(item, next); else sec.appendChild(item);
  };
  const onUp = () => {
    document.removeEventListener('pointermove', onMove);
    document.removeEventListener('pointerup', onUp);
    document.removeEventListener('pointercancel', onUp);
    item.classList.remove('dragging');
    poolOrder[sec.dataset.group] = [...sec.querySelectorAll('.task-row')].map(el => el.dataset.id);
    renderPool(); saveDaily();
  };
  document.addEventListener('pointermove', onMove);
  document.addEventListener('pointerup', onUp);
  document.addEventListener('pointercancel', onUp);
});

document.addEventListener('keydown', e => {
  if (modalMask.style.display !== 'flex') return;
  if (e.key === 'Escape') { closeModal(); e.preventDefault(); return; }
  if (e.key !== 'Tab') return;
  const focusable = [...modalMask.querySelectorAll('button:not(:disabled), input:not(:disabled), [tabindex="0"]')]
    .filter(el => el.getClientRects().length > 0);
  const first = focusable[0], last = focusable[focusable.length - 1];
  if (e.shiftKey && (document.activeElement === first || !modalMask.contains(document.activeElement))) {
    last.focus(); e.preventDefault();
  } else if (!e.shiftKey && document.activeElement === last) { first.focus(); e.preventDefault(); }
});
setInterval(pollState, 1500);
setInterval(pollMumu, 4000);
setInterval(() => { if (pfSub === 'chart' && document.getElementById('page-pf').classList.contains('on')) pollHistory(); }, 2500);
/* 初始化: 主题沿用 localStorage(首屏已由 head 脚本打好), 模拟器状态点立即取一次 */
applyTheme(document.documentElement.dataset.theme || 'midnight');
pollState();
pollMumu();
loadAdbConfig();
