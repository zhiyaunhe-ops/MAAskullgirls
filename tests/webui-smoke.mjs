/* Run with Node. All APIs are local fixtures; no bot or emulator is started. */
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { readFile, mkdir } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const modulePath = process.env.PLAYWRIGHT_MODULE || '/root/.local/share/pnpm/global/5/.pnpm/playwright-core@1.58.2/node_modules/playwright-core/index.mjs';
const { chromium } = await import(pathToFileURL(modulePath).href);
const executablePath = process.env.CHROMIUM_PATH || '/root/.cache/ms-playwright/chromium-1217/chrome-linux64/chrome';
const artifacts = resolve(process.env.WEBUI_ARTIFACTS || join(root, 'tests', 'artifacts'));
await mkdir(artifacts, { recursive: true });
const pixel = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScLbtAAAAABJRU5ErkJggg==', 'base64');
const sessions = [
  { id: 'default', name: 'Canopy practice', score: 320000, count: 3, energy_cost: 4, score_target: 1000000, rest_every: 0, rest_minutes: 0, rule: { type: 'element', value: 'fire' } },
  { id: 'weekend', name: 'Weekend <final>', score: 800000, count: 3, energy_cost: 3, score_target: 2000000, rest_every: 3, rest_minutes: 15, rule: null },
];
let daily = { queue: ['missions', 'social'], pool: { daily: ['missions', 'social'], guild: [] }, names: {} };
let state = {
  status: 'IDLE', step: 'Fixture ready', session_id: null, session_name: '', score: 320000, streak: 2,
  fight_no: 3, energy_cost: 4, score_target: null, filter_favorite: true, close_on_goal: false,
  rest_every: 0, rest_minutes: 0, rest_until: 0, shot_ver: -1, shot_time: '', log_total: 3,
  logs: [['12:00:00', 'info', 'Fixture connected'], ['12:00:01', 'warn', 'Energy low warning'], ['12:00:02', 'step', 'STEP: choose <team>']],
};
const calls = [];
const failures = new Set();
function select(id) {
  const s = sessions.find(s => s.id === id);
  Object.assign(state, { session_id: id, session_name: s.name, energy_cost: s.energy_cost,
    score_target: s.score_target, rest_every: s.rest_every, rest_minutes: s.rest_minutes, pf_rule: s.rule });
}
const server = createServer(async (request, response) => {
  const url = new URL(request.url, 'http://fixture');
  const send = (status, body, type = 'application/json') => {
    response.writeHead(status, { 'Content-Type': type, 'Cache-Control': 'no-store' });
    response.end(type === 'application/json' ? JSON.stringify(body) : body);
  };
  try {
    if (url.pathname.startsWith('/api/')) {
      let body = '';
      for await (const chunk of request) body += chunk;
      body = body ? JSON.parse(body) : undefined;
      calls.push({ path: url.pathname, method: request.method, body });
      if (failures.has(url.pathname)) return send(503, { error: 'Fixture service unavailable' });
      if (url.pathname === '/api/state') return send(200, state);
      if (url.pathname === '/api/mumu') return send(200, { running: true, busy: false });
      if (url.pathname === '/api/sessions') return send(200, { sessions, active: state.session_id });
      if (url.pathname === '/api/sessions/select') { select(body.id); return send(200, { ok: true }); }
      if (url.pathname === '/api/sessions/update') {
        Object.assign(sessions.find(s => s.id === body.id), body);
        if (state.session_id === body.id) select(body.id);
        return send(200, { ok: true });
      }
      if (url.pathname === '/api/start') { select(body.session_id); state.status = 'RUNNING'; return send(200, { ok: true }); }
      if (url.pathname === '/api/pause') { state.status = 'PAUSED'; return send(200, { ok: true }); }
      if (url.pathname === '/api/settings') {
        Object.assign(state, { filter_favorite: body.filter_favorite, close_on_goal: body.close_mumu_on_goal });
        return send(200, { ok: true });
      }
      if (url.pathname === '/api/daily') {
        if (body) daily = body;
        return send(200, { saved: true, data: daily });
      }
      if (url.pathname === '/api/history') {
        const ids = (url.searchParams.get('sessions') || 'default').split(',');
        return send(200, { series: ids.map(id => ({ id, name: sessions.find(s => s.id === id)?.name || id,
          points: [{ ts: 1000, score: 0, streak: 0, fight: 1 }, { ts: 1060, score: 200000, streak: 1, fight: 2 }, { ts: 1120, score: 320000, streak: 2, fight: 3 }] })) });
      }
      if (url.pathname === '/api/jjc' || url.pathname === '/api/jjc/refresh') return send(200, {
        snapshot: { day: '2026-09-26', revision: 1, fp: 'fixture', daily_events: ['Annie'], entries: [{ kind: 'element', label_cn: '元素竞技场', name: 'Fire', active: true }] }, versions: {}, session_names: {},
      });
      throw new Error(`Unexpected API: ${request.method} ${url.pathname}`);
    }
    if (url.pathname === '/shot.jpg' || url.pathname.startsWith('/sgm/image/')) return send(200, pixel, 'image/png');
    const name = url.pathname === '/' ? 'webui.html' : url.pathname.replace(/^\/static\//, '');
    if (!/^[\w.-]+$/.test(name)) return send(404, { error: 'Not found' });
    const content = await readFile(join(root, 'tools', 'static', name));
    const type = name.endsWith('.js') ? 'text/javascript' : name.endsWith('.css') ? 'text/css' : name.endsWith('.svg') ? 'image/svg+xml' : 'text/html';
    send(200, content, type);
  } catch (error) { send(500, { error: error.message }); }
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const origin = `http://127.0.0.1:${server.address().port}`;
let browser;
const results = [];
const errors = [];
async function check(name, fn) {
  try { await fn(); results.push(name); console.log(`PASS ${name}`); }
  catch (error) { errors.push({ name, error }); console.error(`FAIL ${name}: ${error.message}`); }
}
const wait = (page, fn, arg) => page.waitForFunction(fn, arg, { timeout: 7000 });
const text = (page, selector) => page.locator(selector).innerText();
try {
  browser = await chromium.launch({ executablePath, headless: true, args: ['--no-sandbox'] });
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
  const page = await context.newPage();
  const runtimeErrors = [];
  page.on('pageerror', error => runtimeErrors.push(error.message));
  page.on('dialog', dialog => dialog.dismiss());
  await page.goto(origin);
  await wait(page, () => document.getElementById('status').textContent === 'IDLE');

  await check('initial idle state, screenshot placeholder, escaped logs', async () => {
    assert.equal(await page.locator('#shot-empty').isVisible(), true);
    assert.equal(await page.locator('#shot').isVisible(), false);
    assert.match(await text(page, '#logpane'), /choose <team>/);
    assert.equal(await page.locator('#logpane team').count(), 0);
    assert.equal(await page.locator('#in-target').isDisabled(), true);
    await page.screenshot({ path: join(artifacts, 'desktop-empty.png'), fullPage: true });
  });
  await check('sessions selection, settings persistence, start and pause', async () => {
    await page.locator('#sess-chip').click();
    await page.locator('.sess-row[data-id="weekend"]').click();
    await page.locator('#sess-pick').click();
    await wait(page, () => document.getElementById('sess-chip').textContent.includes('Weekend <final>'));
    assert.match(await text(page, '#session-heading'), /Weekend <final>/);
    await page.locator('.settings-panel summary').click();
    assert.equal(await page.locator('#in-target').inputValue(), '2000000');
    await page.locator('#in-target').fill('1600000');
    await page.locator('#in-target').press('Tab');
    await wait(page, () => document.getElementById('goal-progress').value === 20);
    assert.equal(sessions[1].score_target, 1600000);
    await page.locator('#in-fav').uncheck();
    await wait(page, () => document.getElementById('in-fav').checked === false);
    assert.equal(state.filter_favorite, false);
    await page.locator('#startbtn').click();
    await wait(page, () => document.getElementById('status').textContent === 'RUNNING');
    assert.equal(await page.locator('#in-energy').isDisabled(), true);
    await page.locator('#pausebtn').click();
    await wait(page, () => document.getElementById('status').textContent === 'PAUSED');
    assert.equal(await text(page, '#startbtn'), '继续');
  });
  await check('loaded screenshot and log search/level/follow controls', async () => {
    state.shot_ver = 1; state.shot_time = '12:01:00';
    await page.evaluate(() => pollState());
    await wait(page, () => { const img = document.getElementById('shot'); return !img.hidden && img.complete && img.naturalWidth > 0; });
    assert.equal(await page.locator('#shot-empty').isVisible(), false);
    await page.locator('#log-search').fill('Energy');
    await wait(page, () => document.getElementById('logpane').textContent.includes('Energy') && !document.getElementById('logpane').textContent.includes('connected'));
    await page.locator('#log-search').fill('');
    await page.locator('#log-level').selectOption('step');
    assert.match(await text(page, '#logpane'), /choose <team>/);
    assert.doesNotMatch(await text(page, '#logpane'), /Energy/);
    await page.locator('#log-level').selectOption('all');
    await page.locator('#log-follow').click();
    assert.equal(await page.locator('#log-follow').getAttribute('aria-pressed'), 'false');
    await page.locator('#log-follow').click();
    assert.equal(await page.locator('#log-follow').getAttribute('aria-pressed'), 'true');
    await page.screenshot({ path: join(artifacts, 'desktop-loaded.png'), fullPage: true });
  });
  await check('daily task detail, queue add/remove/reorder and rename persistence', async () => {
    await page.locator('#tab-daily').click();
    await page.locator('.task-row[data-id="missions"] .t-gear').click();
    assert.match(await text(page, '#detail-body'), /MISSIONS/);
    await page.locator('#pool-clear').click();
    assert.equal(await page.locator('#queue-list .q-item').count(), 0);
    await page.locator('.task-row[data-id="missions"] input').click();
    await page.locator('.task-row[data-id="social"] input').click();
    await page.locator('.task-row[data-id="missions"] .t-rename').click();
    await page.locator('#pool-rn').fill('Claim <daily> "rewards"');
    await page.locator('#pool-rn').press('Enter');
    assert.match(await text(page, '#queue-list'), /Claim <daily> "rewards"/);
    const handle = await page.locator('.q-item[data-id="social"] .q-handle').boundingBox();
    const first = await page.locator('.q-item[data-id="missions"]').boundingBox();
    await page.mouse.move(handle.x + handle.width / 2, handle.y + handle.height / 2);
    await page.mouse.down();
    await page.mouse.move(first.x + 20, first.y + 1, { steps: 5 });
    await page.mouse.up();
    await wait(page, () => document.querySelector('#queue-list .q-item')?.dataset.id === 'social');
    await page.locator('.q-item[data-id="missions"] .q-del').click();
    assert.deepEqual(daily.queue, ['social']);
    await page.reload();
    await page.locator('#tab-daily').click();
    await wait(page, () => document.querySelector('#queue-list .q-item')?.dataset.id === 'social');
    assert.equal(await page.locator('#queue-list .q-item').count(), 1);
    assert.match(await text(page, '.task-row[data-id="missions"]'), /Claim <daily> "rewards"/);
  });
  await check('event schedule fixture and refresh', async () => {
    await page.locator('#tab-jjc').click();
    await wait(page, () => document.getElementById('jjc-src').textContent.includes('2026-09-26'));
    assert.match(await text(page, '#jjc-grid'), /Fire/);
    await page.locator('#jjc-refresh').click();
    await wait(page, () => !document.getElementById('jjc-refresh').disabled);
    assert(calls.some(call => call.path === '/api/jjc/refresh' && call.method === 'POST'));
  });
  await check('six themes, native schemes, distinct styling, charts and reload persistence', async () => {
    await page.locator('#tab-pf').click();
    await page.locator('#subtab-chart').click();
    await wait(page, () => Chart.getChart('ch-score')?.data.datasets[0].data.length === 3);
    const themes = await page.evaluate(() => THEMES.map(({ id, scheme }) => ({ id, scheme })));
    assert.equal(themes.length, 6);
    const appearances = new Set();
    for (const theme of themes) {
      await page.locator('#theme-btn').click();
      await page.locator(`.tm-item[data-theme-id="${theme.id}"]`).click();
      await wait(page, id => document.documentElement.dataset.theme === id && Chart.getChart('ch-score').data.datasets[0].borderColor === cssVar('--gold'), theme.id);
      const look = await page.evaluate(() => ({ scheme: getComputedStyle(document.documentElement).colorScheme,
        saved: localStorage.getItem('sgm-theme'), meta: document.querySelector('meta[name="color-scheme"]').content,
        bg: cssVar('--bg'), gold: cssVar('--gold'), panel: getComputedStyle(document.querySelector('.chart-card')).borderRadius,
        streak: Chart.getChart('ch-streak').data.datasets[0].borderColor, green: cssVar('--green') }));
      assert.equal(look.saved, theme.id); assert.equal(look.scheme, theme.scheme); assert.equal(look.meta, theme.scheme);
      assert.equal(look.streak, look.green); appearances.add(JSON.stringify([look.bg, look.gold, look.panel]));
      await page.screenshot({ path: join(artifacts, `theme-${theme.id}.png`), fullPage: true });
      await page.reload();
      assert.equal(await page.evaluate(() => document.documentElement.dataset.theme), theme.id);
      await page.locator('#subtab-chart').click();
      await wait(page, () => Chart.getChart('ch-score')?.data.datasets[0].data.length === 3);
    }
    assert.equal(appearances.size, 6);
  });
  await check('HTTP state failure reports connection loss and recovers', async () => {
    failures.add('/api/state');
    await page.evaluate(() => pollState());
    await wait(page, () => /中断|断开|失败|离线/.test(document.getElementById('connection').textContent));
    failures.delete('/api/state');
    await page.evaluate(() => pollState());
    await wait(page, () => document.getElementById('connection').classList.contains('online'));
  });
  await check('mobile tabs and session modal have no page overflow', async () => {
    await page.setViewportSize({ width: 390, height: 844 });
    await page.locator('#subtab-run').click();
    for (const tab of ['pf', 'daily', 'jjc']) {
      await page.locator(`#tab-${tab}`).click();
      await wait(page, () => document.documentElement.scrollWidth <= innerWidth + 1);
      await page.screenshot({ path: join(artifacts, `mobile-${tab}.png`), fullPage: true });
    }
    await page.locator('#tab-pf').click();
    await page.locator('#sess-chip').click();
    await wait(page, () => document.documentElement.scrollWidth <= innerWidth + 1);
    await page.screenshot({ path: join(artifacts, 'mobile-session.png'), fullPage: true });
    await page.locator('#sess-cancel').click();
  });
  await check('no runtime JavaScript errors or emulator command requests', async () => {
    assert.deepEqual(runtimeErrors, []);
    assert.equal(calls.some(call => /^\/api\/mumu\//.test(call.path)), false);
  });
} finally {
  if (browser) await browser.close();
  await new Promise(resolve => server.close(resolve));
}
console.log(`${results.length} smoke groups passed; ${errors.length} failed. Screenshots: ${artifacts}`);
if (errors.length) process.exitCode = 1;
