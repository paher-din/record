async (page) => {
  const failures = [];
  const requests = [];
  const browserErrors = [];
  page.on('request', request => requests.push(request.url()));
  page.on('pageerror', error => browserErrors.push(error.message));
  await page.setViewportSize({ width: 1440, height: 1120 });
  await page.reload();
  await page.waitForFunction(() => window.review?.getTokens());
  const catalog = await page.evaluate(() => Object.fromEntries(Object.entries(window.review.pages).map(([key, value]) => [key, Object.keys(value.states)])));
  async function closeDialog() { await page.evaluate(() => { const d = document.getElementById('taskDialog'); if (d.open) d.close(); }); }
  async function select(id, value) { await closeDialog(); await page.locator('#' + id).selectOption(String(value)); }
  async function show(key, scenario = 'ready') { await select('page', key); await select('scenario', scenario); }
  async function inspect(label) {
    const result = await page.evaluate(() => {
      const native = document.getElementById('native');
      const app = document.getElementById('app');
      const scroll = app.querySelector('.scroll');
      const controls = [...app.querySelectorAll('button,input:not([type=checkbox])')].filter(el => el.getClientRects().length && getComputedStyle(el).visibility !== 'hidden');
      const small = controls.map(el => ({ label: el.getAttribute('aria-label') || el.textContent.trim(), w: el.getBoundingClientRect().width, h: el.getBoundingClientRect().height })).filter(r => r.w < 47 || r.h < 47);
      return { text: app.innerText.length, overflow: Math.max(native.scrollWidth - native.clientWidth, scroll ? scroll.scrollWidth - scroll.clientWidth : 0), small };
    });
    if (!result.text || result.overflow > 2 || result.small.length) failures.push({ label, ...result });
  }
  let rendered = 0;
  for (const theme of ['light', 'dark']) {
    await select('theme', theme);
    for (const [key, scenarios] of Object.entries(catalog)) {
      for (const scenario of scenarios) {
        await show(key, scenario);
        await inspect(`${theme}/${key}/${scenario}/390/1`);
        rendered++;
      }
    }
  }
  let adaptive = 0;
  for (const theme of ['light', 'dark']) for (const width of [320, 840]) for (const scale of [1.3, 2]) {
    await select('theme', theme); await select('width', width); await select('scale', scale);
    for (const key of Object.keys(catalog)) { await show(key); await inspect(`${theme}/${key}/ready/${width}/${scale}`); adaptive++; }
  }
  await select('theme', 'light'); await select('width', 390); await select('scale', 1);
  const flow = [];
  function check(ok, name) { flow.push({ name, pass: !!ok }); if (!ok) failures.push({ flow: name }); }
  await show('finance');
  await page.getByLabel('说明', { exact: true }).fill('午饭 <> & " 原输入');
  await page.getByRole('button', { name: '关闭', exact: true }).click();
  await page.getByRole('button', { name: '保留草稿并离开', exact: true }).click();
  await page.getByRole('button', { name: '继续草稿', exact: true }).click();
  check((await page.getByLabel('说明', { exact: true }).inputValue()) === '午饭 <> & " 原输入', 'finance input survives draft leave/return');
  await show('noteEdit');
  const body = '正文 <> & "\n第二行，保留符号。';
  await page.getByLabel('笔记正文').fill(body);
  await select('scenario', 'conflict'); await select('theme', 'dark');
  check((await page.getByLabel('笔记正文').inputValue()) === body, 'note buffer survives conflict/theme');
  await show('links');
  await page.locator('input[data-choice="1"]').check();
  await page.getByRole('button', { name: '查看', exact: true }).first().click();
  await page.getByRole('button', { name: '返回关联选择', exact: true }).click();
  check(await page.locator('input[data-choice="1"]').isChecked(), 'link preview preserves selection');
  await show('agent');
  await page.getByLabel('给Record的指令').fill('午饭改成68元。');
  await select('scenario', 'clarify');
  check(await page.getByRole('dialog').isVisible(), 'clarification uses dialog');
  check(!(await page.locator('#app').innerText()).includes('改哪笔午饭'), 'question does not create workspace chat bubble');
  await page.getByRole('button', { name: '暂不处理', exact: true }).click();
  check((await page.getByLabel('给Record的指令').inputValue()) === '午饭改成68元。', 'dismiss preserves original input');
  await select('theme', 'light');
  check(!(await page.getByRole('dialog').isVisible()), 'dismissed question does not reprompt on render');
  await select('scenario', 'clarify');
  await page.getByRole('button', { name: '今天 · ¥58.00', exact: true }).click();
  check((await page.locator('#app').innerText()).includes('已修改金额') && !(await page.getByRole('dialog').isVisible()), 'choice continues without second confirmation');
  check(!/检查提案|确认并保存/.test(await page.locator('#app').innerText()), 'agent has no per-action proposal confirmation');
  await show('backup', 'candidate');
  check(await page.getByRole('button', { name: '替换并恢复', exact: true }).isVisible(), 'destructive restore remains explicit');
  await select('motion', 'reduce'); await show('agent', 'running');
  check((await page.locator('.spinner').evaluate(el => getComputedStyle(el).animationName)) === 'none', 'reduced motion stops loop');
  const captures = [
    ['home', 'ready', 'light', 390, 1, 'home-light'], ['home', 'ready', 'dark', 390, 1, 'home-dark'],
    ['finance', 'ready', 'light', 390, 1, 'finance'], ['noteEdit', 'conflict', 'dark', 320, 2, 'note-conflict-large'],
    ['authorization', 'ready', 'light', 390, 1, 'authorization'], ['backup', 'candidate', 'light', 390, 1, 'restore'],
    ['agent', 'clarify', 'light', 390, 1, 'clarification'], ['settings', 'ready', 'dark', 390, 1, 'settings-dark']
  ];
  const screenshotPaths = [];
  for (const [key, scenario, theme, width, scale, name] of captures) {
    await select('theme', theme); await select('width', width); await select('scale', scale); await show(key, scenario);
    const path = `record2/build/f0-c3/${name}.png`;
    await page.locator('#device').screenshot({ path, animations: 'disabled' });
    screenshotPaths.push(path);
  }
  const externalRequests = requests.filter(url => !url.startsWith('http://127.0.0.1:8766/') && !url.startsWith('data:'));
  await select('theme', 'light'); await select('width', 390); await select('scale', 1); await show('home');
  return { pages: Object.keys(catalog).length, scenarios: Object.values(catalog).reduce((n, list) => n + list.length, 0), rendered, adaptive, flow, failures, browserErrors, externalRequests, screenshots: screenshotPaths };
}
