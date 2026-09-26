/* Theme metadata is kept separate from automation and chart behavior.
   The original IDs preserve saved preferences from earlier versions. */
const THEMES = [
  {id:'midnight', name:'Canopy Noir', label:'冠层黑金', description:'ART DECO / 黄铜 · 深墨', scheme:'dark', sw:['#1a2528','#263437','#e7c68b','#e7c68b'], font:'Georgia,serif', radius:'2px'},
  {id:'aurora', name:'Emerald Glass', label:'翡翠之境', description:'BOTANICAL / 绿意 · 柔光', scheme:'dark', sw:['#102e2a','#31584c','#a5e7c3','#9be3b0'], font:'system-ui,sans-serif', radius:'15px'},
  {id:'ember', name:'Foundry', label:'铸造工坊', description:'INDUSTRIAL / 铜橙 · 硬边', scheme:'dark', sw:['#2a2723','#423124','#ffbb75','#ffbb75'], font:'Impact,sans-serif', radius:'0px'},
  {id:'neon', name:'Neon Arcade', label:'霓虹街机', description:'ARCADE / 网格 · 紫电', scheme:'dark', sw:['#231b3d','#362254','#d7b0ff','#82edd8'], font:'monospace', radius:'6px'},
  {id:'sakura', name:'Sakura Studio', label:'樱花画室', description:'PASTEL / 花粉 · 曲线', scheme:'light', sw:['#fffafb','#f7dce5','#a0446b','#b26986'], font:'Georgia,serif', radius:'18px'},
  {id:'paper', name:'Atelier Paper', label:'纸上工作室', description:'EDITORIAL / 暖纸 · 油墨', scheme:'light', sw:['#fcfaf4','#e7e0d2','#355f69','#8c641c'], font:'Georgia,serif', radius:'0px'},
];
const THEME_KEY = 'sgm-theme';
function cssVar(n){ return getComputedStyle(document.documentElement).getPropertyValue(n).trim(); }
function rgbaOf(hex, a) {                      // CSS 变量取回的是 #rrggbb, canvas 要 rgba
  const h = (hex || '').replace('#','').trim();
  if (h.length !== 6 && h.length !== 3) return hex;
  const s = h.length === 3 ? h.split('').map(c => c + c).join('') : h;
  const n = parseInt(s, 16);
  return `rgba(${(n>>16)&255},${(n>>8)&255},${n&255},${a})`;
}
function applyTheme(id) {
  const t = THEMES.find(x => x.id === id) || THEMES[0];
  document.documentElement.dataset.theme = t.id;
  const m = document.querySelector('meta[name="color-scheme"]');
  if (m) m.content = t.scheme;
  try { localStorage.setItem(THEME_KEY, t.id); } catch (e) {}
  const b = document.getElementById('theme-btn');
  if (b) b.textContent = '◑ ' + t.label;
  document.getElementById('theme-caption').textContent = t.name.toUpperCase();
  applyChartTheme();
  renderThemeMenu();
  if (pfSub === 'chart') pollHistory();     // 图表数据集重建时才会吃新配色
}
function renderThemeMenu() {
  const box = document.getElementById('theme-menu');
  if (!box) return;
  const cur = document.documentElement.dataset.theme;
  box.innerHTML = '<div class="tm-head">选择你的工作台<span>6 THEMES</span></div><div class="theme-options">' + THEMES.map(t =>
    `<button class="tm-item${t.id === cur ? ' on' : ''}" data-theme-id="${t.id}" aria-pressed="${t.id === cur}" onclick="selectTheme('${t.id}')">
      <span class="tm-sw" aria-hidden="true" style="background:linear-gradient(120deg,${t.sw[0]},${t.sw[1]});color:${t.sw[2]};font-family:${t.font};border-radius:${t.radius}"></span>
      <span class="tm-name">${t.name}<span class="tm-tick">${t.id === cur ? '✓' : ''}</span></span>
      <span class="tm-description">${t.description}</span>
    </button>`).join('') + '</div><div class="tm-foot">深色 × 4 · 浅色 × 2 / 自动保存到此浏览器</div>';
}
function selectTheme(id) {
  applyTheme(id);
  closeThemeMenu(true);
}
function toggleThemeMenu(e) {
  if (e) e.stopPropagation();
  const menu = document.getElementById('theme-menu');
  const open = menu.style.display === 'none';
  menu.style.display = open ? 'block' : 'none';
  document.getElementById('theme-btn').setAttribute('aria-expanded', String(open));
  if (open) menu.querySelector('.tm-item.on').focus();
}
function closeThemeMenu(restoreFocus = false) {
  const menu = document.getElementById('theme-menu');
  if (!menu) return;
  menu.style.display = 'none';
  document.getElementById('theme-btn').setAttribute('aria-expanded', 'false');
  if (restoreFocus) document.getElementById('theme-btn').focus();
}
document.addEventListener('click', e => {
  if (!e.target.closest('.theme-wrap')) closeThemeMenu();
});
document.addEventListener('keydown', e => {
  const menu = document.getElementById('theme-menu');
  if (menu.style.display === 'none') return;
  if (e.key === 'Escape') { closeThemeMenu(true); e.preventDefault(); }
  if (!['ArrowDown', 'ArrowUp', 'ArrowRight', 'ArrowLeft'].includes(e.key)) return;
  const items = [...menu.querySelectorAll('.tm-item')];
  const index = items.indexOf(document.activeElement);
  const direction = ['ArrowDown', 'ArrowRight'].includes(e.key) ? 1 : -1;
  items[(index + direction + items.length) % items.length].focus();
  e.preventDefault();
});
window.addEventListener('storage', e => {
  if (e.key === THEME_KEY) applyTheme(e.newValue || 'midnight');
});
/* canvas 不吃 CSS 变量: 把当前主题色读出来喂给 Chart.js */
function applyChartTheme() {
  refreshPalette();
  if (typeof Chart === 'undefined') return;
  Chart.defaults.color = cssVar('--dim') || Chart.defaults.color;
  Chart.defaults.borderColor = cssVar('--line') || Chart.defaults.borderColor;
  const tt = { backgroundColor: cssVar('--panel2'), borderColor: cssVar('--line'),
               titleColor: cssVar('--txt'), bodyColor: cssVar('--txt2') };
  Object.assign(ttStyle, tt);
  for (const ch of [chScore, chDelta, chStreak]) {
    if (!ch) continue;
    ch.options.plugins.tooltip = Object.assign(ch.options.plugins.tooltip || {}, tt);
    for (const k of ['x', 'y']) {
      const s = ch.options.scales && ch.options.scales[k];
      if (!s) continue;
      s.ticks = Object.assign(s.ticks || {}, { color: cssVar('--dim') });
      if (s.grid) s.grid.color = cssVar('--line');
    }
    ch.update('none');
  }
}

