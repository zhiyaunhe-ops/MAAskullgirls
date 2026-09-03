"""PF bot WebUI：stdlib http.server，运行日志/截图 + 图表页签（Chart.js 本地托管）。

GET  /                  页面（运行 / 图表 两个页签）
GET  /api/state         {status, step, fight_no, score, streak, logs, shot_ver, shot_time,
                         session_id, session_name, log_total}
GET  /api/sessions      {sessions: [{id,name,rule,count,last_ts}], active, running}
GET  /api/summary       {per_min, last_delta}  当前场次轻量统计 (小组件用)
GET  /api/history       ?sessions=a,b,c -> {series: [{id,name,rule,points}]}
GET  /static/...        静态文件（chart.umd.min.js）
GET  /sgm/...           sgm 素材（元素/角色图标等）
GET  /shot.jpg          最新截图
POST /api/start         {session_id} 开始指定场次（运行中不可换）
POST /api/stop          请求停止
POST /api/sessions/select  仅绑定当前场次不开始（运行中不可换）
POST /api/sessions/create|update|delete   场次管理（运行中禁改当前场次; Default 不可删）
"""
import json
import mimetypes
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

from pf_env import STATE
from pf_store import STORE, UNSET, clean_rest, clean_target

STATIC_DIR = Path(__file__).resolve().parent / "static"
SGM_DIR = Path(__file__).resolve().parent.parent / "sgm"

_HTML = """<!doctype html>
<html lang="zh"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="color-scheme" content="dark">
<title>SGM PF Bot</title>
<script src="/static/chart.umd.min.js"></script>
<style>
  :root {
    --bg0:#0e1118; --bg1:#141824; --panel:#181e2a; --panel2:#1c2230; --line:#262e40;
    --txt:#dde4ee; --dim:#75819a; --faint:#4a5570;
    --gold:#f6c960; --green:#5ee08a; --red:#ff7b8b; --blue:#7fb2ff;
  }
  * { box-sizing: border-box; }
  html, body { height: 100%; }
  body {
    margin:0; color:var(--txt);
    font-family:"Segoe UI","Microsoft YaHei",system-ui,sans-serif;
    background:radial-gradient(1200px 500px at 50% -120px, #1b2336 0%, var(--bg1) 45%, var(--bg0) 100%);
    display:flex; flex-direction:column;
  }
  .mono { font-family:Consolas,"JetBrains Mono",monospace; }
  header {
    display:flex; align-items:center; gap:16px; padding:12px 20px;
    background:rgba(24,30,42,.75); backdrop-filter:blur(8px);
    border-bottom:1px solid var(--line);
  }
  header h1 { font-size:15px; margin:0; color:var(--blue); font-weight:600; letter-spacing:.5px; }
  .pill { padding:3px 12px; border-radius:999px; font-size:12px; font-weight:600; letter-spacing:.5px; }
  .RUNNING { background:rgba(94,224,138,.12); color:var(--green); box-shadow:inset 0 0 0 1px rgba(94,224,138,.35); }
  .STOPPED,.ERROR { background:rgba(255,123,139,.12); color:var(--red); box-shadow:inset 0 0 0 1px rgba(255,123,139,.35); }
  .IDLE { background:rgba(246,201,96,.12); color:var(--gold); box-shadow:inset 0 0 0 1px rgba(246,201,96,.35); }
  .PAUSED { background:rgba(127,178,255,.12); color:var(--blue); box-shadow:inset 0 0 0 1px rgba(127,178,255,.35); }
  .inp {
    width:104px; background:var(--panel2); color:var(--txt); border:1px solid var(--line);
    border-radius:6px; padding:4px 8px; font-size:12.5px; font-family:inherit;
  }
  .inp:focus { outline:none; border-color:var(--blue); }
  #startbtn {
    background:rgba(94,224,138,.08); color:var(--green); border:1px solid rgba(94,224,138,.35);
    border-radius:8px; padding:6px 18px; cursor:pointer; font-size:13px; transition:background .15s;
  }
  #startbtn:hover { background:rgba(94,224,138,.18); }
  .stat { font-size:13px; color:#aeb9cf; }
  .stat b { color:var(--txt); font-weight:600; }
  #h-score b { color:var(--gold); } #h-streak b { color:var(--green); }
  #step { font-size:12.5px; color:var(--dim); }
  #stopbtn {
    margin-left:auto; background:rgba(255,123,139,.08); color:var(--red);
    border:1px solid rgba(255,123,139,.35); border-radius:8px; padding:6px 18px;
    cursor:pointer; font-size:13px; transition:background .15s;
  }
  #stopbtn:hover { background:rgba(255,123,139,.18); }

  /* ---- PF规则 按钮组 / 场次 ---- */
  #rule-bar { position:relative; display:flex; align-items:center; gap:9px; }
  .rule-label { font-size:12px; color:var(--dim); letter-spacing:2px; }
  .rule-applied {
    display:none; align-items:center; padding:6px 16px;
    background:var(--panel); border:1px solid var(--line); border-radius:999px;
    font-size:12.5px; font-weight:600; letter-spacing:1px; white-space:nowrap;
  }
  .seg {
    display:flex; align-items:center; flex-wrap:wrap; gap:4px; padding:4px;
    background:var(--panel); border:1px solid var(--line); border-radius:10px;
  }
  .rbtn {
    display:inline-flex; align-items:center; gap:5px;
    background:transparent; border:1px solid transparent; border-radius:7px;
    color:var(--dim); font-size:12.5px; font-family:inherit; padding:4px 9px; cursor:pointer;
    transition:color .15s, background .15s, border-color .15s, box-shadow .15s;
  }
  .rbtn img { width:17px; height:17px; }
  .rbtn:hover { color:var(--txt); background:var(--panel2); border-color:var(--line); }
  .rbtn.on {
    color:var(--txt); border-color:var(--ac,#7fb2ff);
    background:var(--acbg,rgba(127,178,255,.10));
    box-shadow:0 0 9px var(--acsh,rgba(127,178,255,.22));
  }
  #btn-off    { --ac:#9aa6bf; --acbg:rgba(154,166,191,.10); --acsh:rgba(154,166,191,.18); }
  .el-fire    { --ac:#ff8a75; --acbg:rgba(255,123,110,.10); --acsh:rgba(255,123,110,.25); }
  .el-water   { --ac:#5ea8ff; --acbg:rgba(94,168,255,.10);  --acsh:rgba(94,168,255,.25); }
  .el-wind    { --ac:#63e08c; --acbg:rgba(99,224,140,.10);  --acsh:rgba(99,224,140,.25); }
  .el-light   { --ac:#f6c960; --acbg:rgba(246,201,96,.10);  --acsh:rgba(246,201,96,.25); }
  .el-dark    { --ac:#b78bff; --acbg:rgba(183,139,255,.10); --acsh:rgba(183,139,255,.25); }
  .el-neutral { --ac:#b9c3d8; --acbg:rgba(185,195,216,.10); --acsh:rgba(185,195,216,.22); }
  .vdiv { width:1px; height:18px; background:var(--line); margin:0 3px; align-self:center; }
  .cls-btn { --ac:#f6c960; --acbg:rgba(246,201,96,.10); --acsh:rgba(246,201,96,.25); padding:4px 6px; }
  .cls-btn img { width:22px; height:22px; }
  .rbtn.disabled { opacity:.35; pointer-events:none; }

  .sess-chip {
    margin-left:auto; display:inline-flex; align-items:center; gap:6px;
    background:var(--panel); border:1px solid var(--line); border-radius:999px;
    color:var(--dim); font-size:12.5px; padding:6px 16px; cursor:pointer; font-family:inherit;
    transition:border-color .15s, color .15s; white-space:nowrap;
  }
  .sess-chip:hover { border-color:var(--blue); color:var(--txt); }
  .sess-chip b { color:var(--txt); font-weight:600; }
  .sess-chip.locked, .sess-chip.locked:hover { cursor:default; border-color:var(--line); color:var(--dim); }
  .sess-chip.none { border-style:dashed; }

  .modal-mask {
    position:fixed; inset:0; background:rgba(5,8,14,.66); z-index:100;
    display:flex; align-items:center; justify-content:center;
  }
  .modal {
    width:560px; max-width:94vw; max-height:86vh; overflow-y:auto;
    background:var(--panel2); border:1px solid var(--line); border-radius:14px;
    padding:18px 20px; box-shadow:0 18px 60px rgba(0,0,0,.6);
  }
  .modal-title { font-size:14px; color:var(--txt); font-weight:600; letter-spacing:2px; margin-bottom:12px; }
  .sess-row {
    display:flex; align-items:center; gap:10px; padding:9px 12px; margin-bottom:7px;
    border:1px solid var(--line); border-radius:10px; background:var(--panel);
    cursor:pointer; transition:border-color .12s, background .12s;
  }
  .sess-row:hover { border-color:#33405c; }
  .sess-row.sel { border-color:var(--blue); background:rgba(127,178,255,.08);
                  box-shadow:0 0 8px rgba(127,178,255,.15); }
  .sess-row .s-name { color:var(--txt); font-size:13px; font-weight:600; }
  .sess-row .s-name input { width:170px; }
  .sess-row .s-meta { color:var(--faint); font-size:11.5px; margin-left:auto; white-space:nowrap; }
  .sess-row .s-act {
    color:var(--faint); background:none; border:none; cursor:pointer;
    font-size:12px; padding:2px 5px; font-family:inherit;
  }
  .sess-row .s-act:hover { color:var(--txt); }
  .sess-row .s-act.arm { color:var(--red); }
  .rule-badge { font-size:11px; color:var(--dim); border:1px solid var(--line);
                border-radius:999px; padding:2px 8px; white-space:nowrap; }
  .sess-new { display:flex; align-items:center; gap:8px; margin-top:12px; padding-top:12px;
              border-top:1px dashed var(--line); flex-wrap:wrap; }
  .sess-new .inp { width:150px; }
  .rule-mini { display:flex; gap:3px; flex-wrap:wrap; }
  .rule-mini .rbtn { padding:3px 7px; font-size:12px; }
  .rule-mini .rbtn img { width:15px; height:15px; }
  .rule-mini .cls-btn img { width:18px; height:18px; }
  .mini-btn {
    background:var(--panel); color:var(--dim); border:1px solid var(--line); border-radius:8px;
    padding:6px 14px; cursor:pointer; font-size:12.5px; font-family:inherit; transition:all .12s;
  }
  .mini-btn:hover { color:var(--txt); border-color:var(--blue); }
  .mini-btn.primary { color:var(--green); border-color:rgba(94,224,138,.4); background:rgba(94,224,138,.08); }
  .mini-btn.primary:disabled { opacity:.35; cursor:not-allowed; }
  .modal-foot { display:flex; justify-content:flex-end; gap:8px; margin-top:14px; }

  nav { display:flex; align-items:flex-end; gap:6px; padding:12px 20px 0; }
  nav button {
    background:var(--panel); color:var(--dim); border:1px solid var(--line);
    padding:7px 22px; font-size:13px; cursor:pointer; font-family:inherit;
    border-radius:9px 9px 0 0; border-bottom:none; letter-spacing:2px; transition:color .15s;
  }
  nav button.on { color:var(--txt); background:var(--panel2); box-shadow:inset 0 2px 0 var(--blue); }
  .page { display:none; }
  .page.on { display:flex; flex-direction:column; flex:1; min-height:0; }
  #runwrap { display:flex; flex:1; min-height:0; padding:0 20px 16px; gap:14px; }
  #logpane {
    flex:1 1 44%; overflow-y:auto; padding:12px 16px; font-size:12.5px; line-height:1.6;
    background:var(--panel); border:1px solid var(--line); border-radius:12px;
  }
  #logpane .info { color:#c4cde0; } #logpane .warn { color:var(--gold); } #logpane .err { color:var(--red); }
  #logpane .step { color:var(--blue); font-weight:600; margin-top:5px; }
  #logpane .t { color:var(--faint); margin-right:6px; }
  #shotpane {
    flex:1 1 56%; display:flex; gap:14px; align-items:center; justify-content:center;
    background:var(--panel); border:1px solid var(--line); border-radius:12px; padding:12px;
  }
  #shot { max-width:100%; max-height:calc(100vh - 150px); border-radius:8px; box-shadow:0 6px 24px rgba(0,0,0,.4); }
  #sideinfo { font-size:13px; color:#aeb9cf; line-height:2.1; }
  #sideinfo b { color:var(--txt); }

  #charts { padding:16px 20px 24px; flex:1; min-height:0; overflow-y:auto; }
  #view-chips { display:flex; gap:6px; flex-wrap:wrap; margin-bottom:12px; align-items:center; }
  #view-chips .vc {
    display:inline-flex; align-items:center; gap:6px; background:var(--panel);
    border:1px solid var(--line); border-radius:999px; padding:4px 12px;
    font-size:12px; color:var(--dim); cursor:pointer; font-family:inherit; transition:all .12s;
  }
  #view-chips .vc .dot { width:8px; height:8px; border-radius:50%; background:var(--c,#7fb2ff); }
  #view-chips .vc.on { color:var(--txt); border-color:var(--c,#7fb2ff); }
  #view-chips .vc .add { font-size:11px; opacity:.75; }
  #view-chips .hint { font-size:11.5px; color:var(--faint); margin-left:4px; }
  .cmp-table { width:100%; border-collapse:collapse; font-size:12.5px; }
  .cmp-table th, .cmp-table td { padding:7px 10px; border-bottom:1px solid var(--line); text-align:right; }
  .cmp-table th:first-child, .cmp-table td:first-child { text-align:left; color:var(--dim); }
  .cmp-table th { color:var(--txt); font-weight:600; }
  #stats-row { display:grid; grid-template-columns:repeat(auto-fit, minmax(126px, 1fr));
               gap:10px; margin-bottom:16px; }
  .stat-card {
    background:linear-gradient(160deg, var(--panel2), var(--panel));
    border:1px solid var(--line); border-radius:12px; padding:12px 14px 10px;
  }
  .stat-card .k { font-size:11.5px; color:var(--dim); letter-spacing:1px; margin-bottom:6px; }
  .stat-card .v { font-size:20px; font-weight:600; }
  .v-gold { color:var(--gold); } .v-green { color:var(--green); }
  .v-red { color:var(--red); } .v-blue { color:var(--blue); }
  .chart-card {
    background:linear-gradient(160deg, var(--panel2), var(--panel));
    border:1px solid var(--line); border-radius:12px; padding:16px 18px 12px; margin-bottom:16px;
  }
  .chart-head { display:flex; align-items:baseline; gap:12px; margin-bottom:8px; }
  .chart-title { font-size:13px; color:#aeb9cf; letter-spacing:3px; font-weight:600; }
  .chart-val { font-size:16px; font-weight:600; }
  .chart-sub { font-size:11.5px; color:var(--faint); margin-left:auto; }
  .chart-body { position:relative; height:250px; }
  #empty { color:var(--dim); padding:60px; text-align:center; font-size:14px; }
  ::-webkit-scrollbar { width:8px; height:8px; }
  ::-webkit-scrollbar-thumb { background:#2a3348; border-radius:4px; }
  ::-webkit-scrollbar-track { background:transparent; }

  /* ---- 移动端适配 (桌面 .hdr-ctl 不产生盒子, 布局不变) ---- */
  .hdr-ctl { display: contents; }
  @media (max-width: 900px) {
    header { flex-wrap: wrap; row-gap: 6px; padding: 10px 14px; }
    .hdr-ctl { display: flex; flex-basis: 100%; flex-wrap: wrap; gap: 6px 16px; }
    nav { flex-wrap: wrap; row-gap: 0; padding: 10px 14px 0; }
    #sess-chip { padding: 7px 16px; }
    #rule-bar { flex-basis: 100%; margin-top: 8px; }
    .rbtn { padding: 7px 11px; font-size: 13.5px; }
    .rbtn img { width: 20px; height: 20px; }
    .cls-btn img { width: 24px; height: 24px; }
    nav button { padding: 9px 20px; font-size: 13.5px; }
    #startbtn, #stopbtn { padding: 8px 22px; font-size: 14px; }
    .inp { font-size: 16px; }   /* >=16px 防 iOS 聚焦自动放大 */
    #runwrap { flex-direction: column; padding: 0 12px 12px; gap: 10px; overflow-y: auto; }
    #logpane { flex: 0 0 auto; max-height: 32vh; }
    #shotpane { flex: 1 1 auto; flex-wrap: wrap; }
    #shot { max-height: 56vh; }
    #charts { padding: 12px 12px 20px; }
    .cmp-table { display: block; overflow-x: auto; }
  }
  @media (max-width: 480px) {
    header { gap: 8px; }
    .rule-label { display: none; }
    .hdr-ctl .inp { width: 84px; }
    .modal { padding: 12px; }
    .sess-row { flex-wrap: wrap; }
    .sess-row .s-meta { flex-basis: 100%; margin-left: 0; order: 9; }
  }
</style></head>
<body>
<header>
  <h1>SGM PF Bot</h1>
  <span id="status" class="pill IDLE">IDLE</span>
  <span id="fight" class="stat"></span>
  <span id="h-score" class="stat">总分 <b>-</b></span>
  <span id="h-streak" class="stat">连胜 <b>-</b></span>
  <span id="step"></span>
  <span class="hdr-ctl">
    <span class="stat">目标总分 <input id="in-target" class="inp" type="number" min="0" step="100000" placeholder="不限"></span>
    <span class="stat">能量门槛 <input id="in-energy" class="inp" type="number" min="1" max="10" step="1"></span>
    <span class="stat"><label><input type="checkbox" id="in-fav" checked> 喜爱</label></span>
    <span class="stat">每 <input id="in-restn" class="inp" type="number" min="0" step="1" value="0" style="width:64px;"> 场
    休 <input id="in-restm" class="inp" type="number" min="0" step="5" value="0" style="width:64px;"> 分钟</span>
  </span>
  <button id="startbtn" onclick="onStartClick()" style="display:none;">开始</button>
  <button id="stopbtn" onclick="fetch('/api/stop',{method:'POST'})">停止</button>
</header>
<nav>
  <button id="tab-run" class="on" onclick="switchTab('run')">运行</button>
  <button id="tab-chart" onclick="switchTab('chart')">图表</button>
  <button id="sess-chip" class="sess-chip none" onclick="openSessionModal()">场次 未选</button>
  <div id="rule-bar">
    <span class="rule-label">PF规则</span>
    <div class="seg" id="ruleSeg"></div>
    <span id="rule-applied" class="rule-applied"></span>
  </div>
</nav>
<div id="page-run" class="page on">
  <div id="runwrap">
    <div id="logpane" class="mono"></div>
    <div id="shotpane">
      <img id="shot"><div id="sideinfo"></div>
    </div>
  </div>
</div>
<div id="page-chart" class="page">
  <div id="charts">
    <div id="view-chips"></div>
    <div id="stats-row"></div>
    <div id="empty" style="display:none;">暂无数据 —— 等第一场结算后这里会长出曲线</div>
    <div class="chart-card"><div class="chart-head">
        <span class="chart-title" id="t-score">总 分</span><span id="v-score" class="chart-val v-gold"></span>
        <span class="chart-sub" id="sub-score">悬浮查看每个采样点</span></div>
      <div class="chart-body"><canvas id="ch-score"></canvas></div></div>
    <div class="chart-card" id="delta-card"><div class="chart-head">
        <span class="chart-title">单场收益</span><span id="v-delta" class="chart-val"></span>
        <span class="chart-sub">相邻结算点差值</span></div>
      <div class="chart-body"><canvas id="ch-delta"></canvas></div></div>
    <div class="chart-card" id="cmp-card" style="display:none;"><div class="chart-head">
        <span class="chart-title">场次对比</span>
        <span class="chart-sub">总分对比为各场次收益累计（起点=0），更直观</span></div>
      <table class="cmp-table" id="cmp-table"></table></div>
    <div class="chart-card"><div class="chart-head">
        <span class="chart-title">连 胜</span><span id="v-streak" class="chart-val v-green"></span>
        <span class="chart-sub" id="sub-streak">推进曲线</span></div>
      <div class="chart-body"><canvas id="ch-streak"></canvas></div></div>
  </div>
</div>
<div id="sess-modal" class="modal-mask" style="display:none;">
  <div class="modal">
    <div class="modal-title">选择 PF 场次</div>
    <div id="sess-list"></div>
    <div class="sess-new">
      <input id="new-sess-name" class="inp" placeholder="新场次名称" style="width:130px;">
      <input id="new-sess-target" class="inp" type="number" min="0" step="1000000" placeholder="分数上界" style="width:92px;">
      <input id="new-sess-restn" class="inp" type="number" min="0" step="1" placeholder="每N场" style="width:60px;">
      <input id="new-sess-restm" class="inp" type="number" min="0" step="5" placeholder="休M分" style="width:60px;">
      <div id="new-sess-rule" class="rule-mini"></div>
      <button id="new-sess-btn" class="mini-btn">创建</button>
    </div>
    <div class="modal-foot">
      <button id="sess-cancel" class="mini-btn">取消</button>
      <button id="sess-pick" class="mini-btn" disabled>仅选择</button>
      <button id="sess-child" class="mini-btn" disabled>建子场次</button>
      <button id="sess-start" class="mini-btn primary" disabled>开始</button>
    </div>
  </div>
</div>
<script>
let lastLog = 0, lastShot = -1, stick = true;
let sessions = [], activeSess = null, running = false, viewIds = [], sessInputsFor;
const pane = document.getElementById('logpane');
pane.addEventListener('scroll', () => {
  stick = pane.scrollTop + pane.clientHeight >= pane.scrollHeight - 30;
});
function esc(s){ return s.replace(/&/g,'&amp;').replace(/</g,'&lt;'); }
function switchTab(t) {
  for (const n of ['run','chart']) {
    document.getElementById('tab-'+n).classList.toggle('on', n===t);
    document.getElementById('page-'+n).classList.toggle('on', n===t);
  }
  if (t === 'chart') pollHistory();
}
async function pollState() {
  try {
    const r = await fetch('/api/state');
    const d = await r.json();
    const st = document.getElementById('status');
    st.textContent = d.status; st.className = 'pill ' + d.status;
    document.getElementById('fight').innerHTML = d.fight_no ? ('第 <b>' + d.fight_no + '</b> 场') : '';
    document.getElementById('h-score').innerHTML = '总分 <b>' + (d.score != null ? d.score.toLocaleString() : '-') + '</b>';
    document.getElementById('h-streak').innerHTML = '连胜 <b>' + (d.streak != null ? d.streak : '-') + '</b>';
    document.getElementById('step').textContent = d.step;
    const startBtn = document.getElementById('startbtn');
    startBtn.style.display = d.status === 'RUNNING' ? 'none' : 'inline-block';
    document.getElementById('stopbtn').style.display = d.status === 'RUNNING' ? 'inline-block' : 'none';
    const inT = document.getElementById('in-target'), inE = document.getElementById('in-energy');
    if (document.activeElement !== inE && inE.value === '' && d.energy_cost != null) inE.value = d.energy_cost;
    running = d.status === 'RUNNING';
    activeSess = d.session_id;
    updateSessChip(d);
    navPicker.setEnabled(!!d.session_name && !running);
    navPicker.set(d.pf_rule);
    setRuleCollapsed(running, d.pf_rule);
    const rn = document.getElementById('in-restn'), rm = document.getElementById('in-restm');
    const sessLocked = !activeSess || running;   // 上界/休息/规则随场次
    inT.disabled = rn.disabled = rm.disabled = sessLocked;
    if (sessInputsFor !== activeSess) {          // 选中场次变了 -> 回填该场次的上界/休息
      sessInputsFor = activeSess;
      if (document.activeElement !== inT) inT.value = (d.score_target != null ? d.score_target : '');
      if (document.activeElement !== rn) rn.value = (d.rest_every || 0);
      if (document.activeElement !== rm) rm.value = (d.rest_minutes || 0);
    }
    const fav = document.getElementById('in-fav');
    if (!fav.dataset.touched) fav.checked = !!d.filter_favorite;
    if (d.rest_until * 1000 > Date.now()) {
      const m = Math.ceil((d.rest_until * 1000 - Date.now()) / 60000);
      document.getElementById('step').textContent = '休息中 (剩 ~' + m + ' 分钟, 回能)';
    }
    if (d.log_total !== lastLog) {
      lastLog = d.log_total;
      pane.innerHTML = d.logs.map(l =>
        `<div class="${l[1]}"><span class="t">${l[0]}</span>${esc(l[2])}</div>`).join('');
      if (stick) pane.scrollTop = pane.scrollHeight;
    }
    if (d.shot_ver !== lastShot) {
      lastShot = d.shot_ver;
      document.getElementById('shot').src = '/shot.jpg?v=' + d.shot_ver;
      document.getElementById('sideinfo').innerHTML =
        '<b>' + (d.shot_time || '-') + '</b><br>状态 <b>' + d.status + '</b><br>' + esc(d.step);
    }
  } catch (e) {}
}

/* ================= Chart.js 图表 ================= */
const GOLD = '#f6c960', GREEN = '#5ee08a', RED = '#ff7b8b';
const fmtN = v => Math.round(v).toLocaleString();
const fmtTs = ts => { const d = new Date(ts*1000);
  return ('0'+d.getHours()).slice(-2)+':'+('0'+d.getMinutes()).slice(-2)+':'+('0'+d.getSeconds()).slice(-2); };
Chart.defaults.color = '#75819a';
Chart.defaults.borderColor = '#232a3c';
Chart.defaults.font.family = "Consolas, 'JetBrains Mono', monospace";
Chart.defaults.animation = false;

let chScore = null, chDelta = null, chStreak = null;
let ptsMeta = [];   // 与标签平行的采样点元数据

const ttStyle = {
  backgroundColor: 'rgba(14,17,24,.95)', borderColor: '#262e40', borderWidth: 1,
  titleColor: '#dde4ee', bodyColor: '#c4cde0', padding: 10,
  displayColors: false, cornerRadius: 8, titleFont: {weight: '600'},
};
const axisX = {
  ticks: { maxTicksLimit: 7, maxRotation: 0 }, grid: { display: false },
};
const axisYn = { ticks: { callback: v => Number(v).toLocaleString() }, grid: { color: '#20283a' } };

function ensureCharts() {
  if (chScore) return;
  chScore = new Chart(document.getElementById('ch-score'), {
    type: 'line',
    data: { labels: [], datasets: [{
      data: [], borderColor: GOLD, borderWidth: 2, fill: true,
      backgroundColor: 'rgba(246,201,96,.10)', tension: .35,
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
    backgroundColor: 'rgba(246,201,96,.10)', tension: .4,
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
async function api(url, body) {
  const r = await fetch(url, body !== undefined
    ? { method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify(body) }
    : undefined);
  return r.json();
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
function buildRulePicker(container, opts = {}) {
  const sizeCls = opts.compact ? ' compact' : '';
  let html = `<button class="rbtn${sizeCls}" data-type="" data-value="">关</button><span class="vdiv"></span>`;
  for (const [v, n] of RULE_ELEMENTS) {
    const en = 'ElementalIcon' + v[0].toUpperCase() + v.slice(1);
    html += `<button class="rbtn el-${v}${sizeCls}" data-type="element" data-value="${v}"><img src="/sgm/image/official/${en}.png" alt="">${n}</button>`;
  }
  html += '<span class="vdiv"></span>';
  for (const [v, n] of RULE_CLASSES) {
    const fn = n.replace(/[^A-Za-z]/g, '');
    html += `<button class="rbtn cls-btn${sizeCls}" data-type="class" data-value="${v}" title="类别${v.slice(1)} · ${n}"><img src="/sgm/image/official/${fn}_MasteryIcon.png" alt="${n}"></button>`;
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
    chip.className = 'sess-chip' + (running ? ' locked' : '');
    chip.innerHTML = '场次 <b>' + esc(d.session_name) + '</b>' + (running ? ' 🔒' : '');
  } else {
    chip.className = 'sess-chip none';
    chip.textContent = '场次 未选';
  }
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
let modalSel = null, renamingId = null, delArmId = null, delArmTimer = null;
const modalPicker = buildRulePicker(document.getElementById('new-sess-rule'), { compact: true });
function fmtDayTs(ts) { const d = new Date(ts*1000);
  return (d.getMonth()+1) + '/' + d.getDate() + ' ' + fmtTs(ts); }
function openSessionModal() {
  if (running) return;
  modalSel = activeSess || (sessions.length ? sessions[0].id : null);
  renamingId = null; delArmId = null;
  renderModal();
  modalMask.style.display = 'flex';
}
function closeModal() { modalMask.style.display = 'none'; }
// 开始: 已有选中场次直接开, 没选过才弹选择弹窗
async function onStartClick() {
  if (running) return;
  if (activeSess) {
    await api('/api/start', { session_id: activeSess });
    pollState();
    return;
  }
  openSessionModal();
}
async function renderModal() {
  try {
    const d = await api('/api/sessions');
    sessions = d.sessions; activeSess = d.active;
  } catch (e) {}
  if (!sessions.some(s => s.id === modalSel)) modalSel = sessions.length ? sessions[0].id : null;
  const list = document.getElementById('sess-list');
  list.innerHTML = sessions.map(s => {
    const rest = (s.rest_every > 0 && s.rest_minutes > 0) ? ` · 休${s.rest_every}场×${s.rest_minutes}分` : '';
    const scoreTxt = (s.score != null && s.count) ? fmtN(s.score) + ' · ' : '';
    const meta = scoreTxt + (s.count ? s.count + ' 条 · ' : '无数据') + (s.last_ts ? fmtDayTs(s.last_ts) : '') + rest;
    const del = s.id === 'default' ? '' :
      `<button class="s-act${delArmId === s.id ? ' arm' : ''}" data-del="${s.id}">${delArmId === s.id ? '确认?' : '✕'}</button>`;
    const badges = `<span class="rule-badge">${ruleLabel(s.rule)}</span>` +
      (s.score_target != null ? `<span class="rule-badge">≤${fmtN(s.score_target)}</span>` : '');
    const indent = s.parent && sessions.some(x => x.id === s.parent) ? '└ ' : '';
    const nameHtml = renamingId === s.id
      ? `<input id="rn-input" class="inp" value="${esc(s.name)}">`
      : `<span class="s-name">${esc(indent + s.name)}</span>`;
    return `<div class="sess-row${s.id === modalSel ? ' sel' : ''}" data-id="${s.id}">
      ${nameHtml}${badges}
      <span class="s-meta">${meta}</span>
      <button class="s-act" data-rename="${s.id}" title="重命名">✎</button>${del}</div>`;
  }).join('') || '<div style="color:var(--faint);padding:20px;text-align:center;">还没有场次</div>';
  document.getElementById('sess-start').disabled = !modalSel;
  document.getElementById('sess-pick').disabled = !modalSel;
  document.getElementById('sess-child').disabled = !modalSel;
  const inp = document.getElementById('rn-input');
  if (inp) { inp.focus(); inp.select(); }
}
document.getElementById('sess-list').addEventListener('click', async e => {
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
  if (row) { modalSel = row.dataset.id; renamingId = null; renderModal(); }
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
  const rn = document.getElementById('new-sess-restn').value.trim();
  const rm = document.getElementById('new-sess-restm').value.trim();
  const d = await api('/api/sessions/create', { name, rule: rule.type ? rule : null,
    score_target: t === '' ? null : Number(t),
    rest_every: rn === '' ? 0 : Number(rn), rest_minutes: rm === '' ? 0 : Number(rm) });
  document.getElementById('new-sess-name').value = '';
  document.getElementById('new-sess-target').value = '';
  document.getElementById('new-sess-restn').value = '';
  document.getElementById('new-sess-restm').value = '';
  modalPicker.set(null);
  modalSel = d.id;
  renderModal();
});
document.getElementById('sess-start').addEventListener('click', async () => {
  if (!modalSel) return;
  await api('/api/start', { session_id: modalSel });
  closeModal();
  pollState();
});
document.getElementById('sess-pick').addEventListener('click', async () => {
  if (!modalSel) return;
  await api('/api/sessions/select', { id: modalSel });
  closeModal();
  pollState();
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
async function saveSettings() {               // 全局: 能量门槛/喜爱
  const e = document.getElementById('in-energy').value.trim();
  await fetch('/api/settings', { method:'POST', headers:{'Content-Type':'application/json'},
    body: JSON.stringify({ energy_cost: e === '' ? 4 : Number(e),
                           filter_favorite: document.getElementById('in-fav').checked }) });
}
async function saveSessionSettings() {        // 随场次: 分数上界/休息 (编辑当前选中场次)
  if (!activeSess || running) return;
  const t = document.getElementById('in-target').value.trim();
  const rn = document.getElementById('in-restn').value.trim();
  const rm2 = document.getElementById('in-restm').value.trim();
  await api('/api/sessions/update', { id: activeSess,
    score_target: t === '' ? null : Number(t),
    rest_every: rn === '' ? 0 : Number(rn), rest_minutes: rm2 === '' ? 0 : Number(rm2) });
}
document.getElementById('in-target').addEventListener('change', saveSessionSettings);
document.getElementById('in-energy').addEventListener('change', saveSettings);
document.getElementById('in-restn').addEventListener('change', saveSessionSettings);
document.getElementById('in-restm').addEventListener('change', saveSessionSettings);
setInterval(pollState, 1500);
setInterval(() => { if (document.getElementById('page-chart').classList.contains('on')) pollHistory(); }, 2500);
pollState();
</script></body></html>"""


def _apply_session(sess: dict) -> None:
    """把场次配置同步进运行状态 (开始/仅选择/修改当前场次后调用)。"""
    STATE.pf_rule = dict(sess["rule"]) if sess.get("rule") else None
    STATE.score_target = clean_target(sess.get("score_target"))
    STATE.rest_every = clean_rest(sess.get("rest_every"))
    STATE.rest_minutes = clean_rest(sess.get("rest_minutes"))


class _Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):  # 静默访问日志
        pass

    def _send(self, code, ctype, body):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        if ctype.startswith("image/"):
            self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        parts = urlsplit(self.path)
        path, qs = parts.path, parse_qs(parts.query)
        if path == "/":
            self._send(200, "text/html; charset=utf-8", _HTML.encode("utf-8"))
        elif path == "/api/state":
            shot_time = ""
            with STATE._lock:
                shot_time = getattr(STATE, "shot_time", "")
            sess = STORE.get(STORE.session_id or "")
            body = json.dumps(
                {
                    "status": STATE.status,
                    "step": STATE.step,
                    "fight_no": STATE.fight_no,
                    "score": STATE.score,
                    "streak": STATE.streak,
                    "score_target": STATE.score_target,
                    "energy_cost": STATE.energy_cost,
                    "pf_rule": STATE.pf_rule,
                    "sess_rest_every": (STORE.get(STORE.session_id or "") or {}).get("rest_every") or 0,
                    "sess_rest_minutes": (STORE.get(STORE.session_id or "") or {}).get("rest_minutes") or 0,
                    "filter_favorite": STATE.filter_favorite,
                    "rest_every": STATE.rest_every,
                    "rest_minutes": STATE.rest_minutes,
                    "rest_until": STATE.rest_until,
                    "session_id": STORE.session_id,
                    "session_name": sess["name"] if sess else None,
                    "log_total": STATE.log_total(),
                    "logs": STATE.dump_logs()[-300:],
                    "shot_ver": STATE.shot_ver,
                    "shot_time": shot_time,
                },
                ensure_ascii=False,
            ).encode("utf-8")
            self._send(200, "application/json", body)
        elif path == "/api/summary":
            # 轻量统计 (小组件用): 每分钟收益 / 上一场收益 / 总分与到目标分的预计秒数
            sid = STORE.session_id or "default"
            pts = [p for p in STORE.history_by.get(sid, []) if p.get("score") is not None]
            last_delta = (pts[-1]["score"] - pts[-2]["score"]) if len(pts) >= 2 else None
            active_sec, gain = 0, 0
            for i in range(1, len(pts)):
                dt = pts[i]["ts"] - pts[i - 1]["ts"]
                if dt < 0 or dt > 180:      # 与 WebUI 图表口径一致: 只算活跃时段
                    continue
                active_sec += dt
                gain += pts[i]["score"] - pts[i - 1]["score"]
            per_min = (gain / (active_sec / 60)) if active_sec > 30 else None
            score_now = pts[-1]["score"] if pts else None
            target = STATE.score_target or 150_000_000
            eta_sec = None
            if per_min and per_min > 0 and score_now is not None and score_now < target:
                eta_sec = round((target - score_now) / per_min * 60)
            body = json.dumps({"per_min": per_min, "last_delta": last_delta,
                               "score": score_now, "target": target, "eta_sec": eta_sec},
                              ensure_ascii=False).encode("utf-8")
            self._send(200, "application/json", body)
        elif path == "/api/sessions":
            body = json.dumps(
                {"sessions": STORE.list_sessions(), "active": STORE.session_id,
                 "running": bool(STATE.running)},
                ensure_ascii=False).encode("utf-8")
            self._send(200, "application/json", body)
        elif path == "/api/history":
            ids = [s for s in (qs.get("sessions") or [""])[0].split(",") if s]
            if not ids:
                ids = [STORE.session_id or "default"]
            body = json.dumps({"series": STORE.series(ids), "active": STORE.session_id},
                              ensure_ascii=False).encode("utf-8")
            self._send(200, "application/json", body)
        elif path.startswith("/static/"):
            name = Path(path).name
            fp = STATIC_DIR / name
            if fp.is_file():
                ctype = mimetypes.guess_type(str(fp))[0] or "application/octet-stream"
                self._send(200, ctype, fp.read_bytes())
            else:
                self._send(404, "text/plain", b"not found")
        elif path.startswith("/sgm/"):
            rel = Path(path[len("/sgm/"):])
            try:
                fp = (SGM_DIR / rel).resolve()
                fp.relative_to(SGM_DIR.resolve())
            except (ValueError, OSError):
                fp = None
            if fp and fp.is_file():
                ctype = mimetypes.guess_type(str(fp))[0] or "application/octet-stream"
                self._send(200, ctype, fp.read_bytes())
            else:
                self._send(404, "text/plain", b"not found")
        elif path.startswith("/shot.jpg"):
            data = b""
            p = STATE.shot_path
            if p:
                try:
                    with open(p, "rb") as f:
                        data = f.read()
                except OSError:
                    pass
            self._send(200, "image/jpeg", data)
        else:
            self._send(404, "text/plain", b"not found")

    def _read_json(self) -> dict:
        length = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(length)
        try:
            text = raw.decode("utf-8", "replace")
        except Exception:  # noqa: BLE001
            text = "{}"
        return json.loads(text or "{}")

    def _json_err(self, code: int, msg: str) -> None:
        self._send(code, "application/json",
                   json.dumps({"error": msg}, ensure_ascii=False).encode("utf-8"))

    def do_POST(self):
        if self.path == "/api/stop":
            STATE.running = False
            STATE.log("收到 WebUI 停止(暂停)请求", "warn")
            self._send(200, "application/json", b'{"ok":true}')
        elif self.path == "/api/start":
            try:
                data = self._read_json()
                sess = STORE.set_session(str(data.get("session_id") or ""))
            except (ValueError, KeyError, json.JSONDecodeError):
                self._json_err(400, "需要有效的 session_id")
                return
            _apply_session(sess)
            STATE.running = True
            STATE.log(f"收到 WebUI 开始请求: 场次「{sess['name']}」", "warn")
            self._send(200, "application/json", b'{"ok":true}')
        elif self.path == "/api/sessions/select":
            # 仅绑定当前场次不开始; 之后可在主页改规则/上界/休息
            try:
                data = self._read_json()
                sid = str(data.get("id") or "")
            except json.JSONDecodeError as e:
                self._json_err(400, str(e))
                return
            sess = STORE.get(sid)
            if not sess:
                self._json_err(404, "场次不存在")
                return
            if STATE.running:
                self._json_err(409, "运行中不允许切换场次")
                return
            STORE.set_session(sid)
            _apply_session(sess)
            STATE.log(f"已选择场次「{sess['name']}」(未开始)", "warn")
            self._send(200, "application/json", b'{"ok":true}')
        elif self.path == "/api/sessions/create":
            try:
                data = self._read_json()
            except json.JSONDecodeError as e:
                self._json_err(400, str(e))
                return
            name = str(data.get("name") or "").strip()
            if not name:
                self._json_err(400, "场次名称不能为空")
                return
            sess = STORE.create(name, data.get("rule"),
                                data.get("rest_every") or 0,
                                data.get("rest_minutes") or 0,
                                data.get("score_target"))
            STATE.log(f"新建场次「{name}」")
            self._send(200, "application/json",
                       json.dumps({"ok": True, "id": sess["id"]}).encode())
        elif self.path == "/api/sessions/update":
            try:
                data = self._read_json()
            except json.JSONDecodeError as e:
                self._json_err(400, str(e))
                return
            sid = str(data.get("id") or "")
            sess = STORE.get(sid)
            if not sess:
                self._json_err(404, "场次不存在")
                return
            if sid == STORE.session_id and STATE.running:
                self._json_err(409, "运行中不允许修改当前场次")
                return
            name = str(data.get("name") or "").strip() or None
            rule = data["rule"] if "rule" in data else UNSET
            rest_e = data["rest_every"] if "rest_every" in data else UNSET
            rest_m = data["rest_minutes"] if "rest_minutes" in data else UNSET
            tgt = data["score_target"] if "score_target" in data else UNSET
            try:
                STORE.update(sid, name=name, rule=rule,
                             rest_every=rest_e, rest_minutes=rest_m,
                             score_target=tgt)
            except KeyError:
                self._json_err(404, "场次不存在")
                return
            if sid == STORE.session_id:
                _apply_session(sess)
            STATE.log(f"场次「{sess['name']}」已更新")
            self._send(200, "application/json", b'{"ok":true}')
        elif self.path == "/api/sessions/child":
            # 周期性分类的每一期: 建带日期的子场次, 继承父场次规则/上界/休息
            try:
                data = self._read_json()
                pid = str(data.get("id") or "")
            except json.JSONDecodeError as e:
                self._json_err(400, str(e))
                return
            try:
                sess = STORE.create_child(pid, data.get("name"))
            except KeyError:
                self._json_err(404, "父场次不存在")
                return
            STATE.log(f"新建子场次「{sess['name']}」(父: {pid})")
            self._send(200, "application/json",
                       json.dumps({"ok": True, "id": sess["id"]}).encode())
        elif self.path == "/api/sessions/delete":
            try:
                data = self._read_json()
            except json.JSONDecodeError as e:
                self._json_err(400, str(e))
                return
            sid = str(data.get("id") or "")
            sess = STORE.get(sid)
            if not sess:
                self._json_err(404, "场次不存在")
                return
            if sid == "default":
                self._json_err(400, "Default 场次收纳历史数据, 不可删除")
                return
            if sid == STORE.session_id and STATE.running:
                self._json_err(409, "运行中不允许删除当前场次")
                return
            STORE.delete(sid)
            if STORE.session_id is None:
                STATE.pf_rule = None
            STATE.log(f"场次「{sess['name']}」已删除", "warn")
            self._send(200, "application/json", b'{"ok":true}')
        elif self.path == "/api/settings":
            try:
                data = self._read_json()
                if "energy_cost" in data:
                    STATE.energy_cost = max(1, min(10, int(data["energy_cost"])))
                if "filter_favorite" in data:
                    STATE.filter_favorite = bool(data["filter_favorite"])
                # 目标总分/休息已随场次, 由 /api/sessions/update 维护
                rule_desc = f"{STATE.pf_rule['type']}={STATE.pf_rule['value']}" if STATE.pf_rule else "无"
                rest_desc = (f"每 {STATE.rest_every} 场休 {STATE.rest_minutes} 分钟"
                             if STATE.rest_every > 0 and STATE.rest_minutes > 0 else "不启用")
                STATE.log(f"设置已更新: 能量门槛={STATE.energy_cost}, 喜爱筛选={STATE.filter_favorite}, "
                          f"规则={rule_desc}, 休息={rest_desc}", "warn")
                self._send(200, "application/json", b'{"ok":true}')
            except (ValueError, json.JSONDecodeError) as e:
                self._json_err(400, str(e))
        else:
            self._send(404, "text/plain", b"not found")


def start_webui(port: int = 8787) -> ThreadingHTTPServer:
    server = ThreadingHTTPServer(("0.0.0.0", port), _Handler)
    t = threading.Thread(target=server.serve_forever, daemon=True)
    t.start()
    return server
