// 第二轮：补上第一轮没测完或测法有误的三项。
//  - T3：详情页「当前修订」里的验收条件编号（第一轮选择器把版本历史里的同一列表也算进去了）。
//  - T8：超长文档的提示。用本地新构建的前端产物顶替线上资产，后端仍是线上的——用来在部署前验证修复。
//  - T12：1440 / 768 / 390 × 正常 / 减动效，表单无横向溢出。
const path = require('node:path');
const { chromium, fs, OUT, BASE, recorder, watch, loginOn, until } = require('./lib.cjs');

const PROJECT = JSON.parse(fs.readFileSync(`${OUT}/project.json`, 'utf8')).projectId;
const first = JSON.parse(fs.readFileSync(`${OUT}/draft-flow.json`, 'utf8'));
const LIST = `${BASE}/requirements?project=${PROJECT}`;
const DRAFT_PATH = `/api/projects/${PROJECT}/requirements/draft`;
const DIST = '/root/ForgePilot/frontend/dist';
const TYPES = { '.js': 'text/javascript', '.css': 'text/css', '.html': 'text/html', '.png': 'image/png', '.svg': 'image/svg+xml', '.ico': 'image/x-icon', '.woff2': 'font/woff2' };

/** 页面与静态资产取本地构建，/api 仍走线上后端。 */
async function serveLocalBuild(context) {
  await context.route((url) => url.origin === BASE && !url.pathname.startsWith('/api/'), (route) => {
    const pathname = new URL(route.request().url()).pathname;
    const file = path.join(DIST, pathname);
    const target = pathname !== '/' && file.startsWith(DIST) && fs.existsSync(file) && fs.statSync(file).isFile() ? file : path.join(DIST, 'index.html');
    return route.fulfill({ status: 200, contentType: TYPES[path.extname(target)] || 'application/octet-stream', body: fs.readFileSync(target) });
  });
}

(async () => {
  const { result, check, save } = recorder('followup');
  let phase = 'login';
  const browser = await chromium.launch({ headless: true, args: ['--no-sandbox'] });
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, locale: 'zh-CN' });
  await serveLocalBuild(context);
  const page = await context.newPage();
  watch(page, result, () => phase);
  page.on('dialog', (dialog) => dialog.accept());
  const step = async (name, body) => {
    phase = name;
    try { await body(); } catch (error) { check(`${name}：步骤执行出错`, false, String(error.message).split('\n')[0]); }
  };

  try {
    await loginOn(page, 'dev02');
    const built = fs.readdirSync(`${DIST}/assets`).find((name) => name.endsWith('.js'));
    const loaded = await page.evaluate(() => [...document.scripts].map((script) => script.src).join(' '));
    check('页面加载的是本地新构建的前端', loaded.includes(built), { built });

    await step('T3 详情页编号', async () => {
      await page.goto(`${BASE}/requirements/${first.requirementId}?project=${PROJECT}`, { waitUntil: 'domcontentloaded' });
      await page.locator('.current-revision .criteria-list li').first().waitFor();
      const shown = await page.locator('.current-revision .criteria-list li')
        .evaluateAll((items) => items.map((item) => item.innerText.replace(/\s+/g, ' ').trim()));
      check('T3 详情页当前修订的验收条件按顺序编号 AC-1…AC-n',
        shown.length >= 5 && shown.every((text, index) => text.startsWith(`AC-${index + 1} `)), { count: shown.length, first: shown[0], last: shown[shown.length - 1] });
      check('T3 人工修改与新增的两条都在', shown[0].includes('（已人工修改）') && shown[shown.length - 1].includes('library_id'));
    });

    await step('T8 超长文档', async () => {
      await page.goto(LIST, { waitUntil: 'domcontentloaded' });
      await page.locator('details.requirement-create-disclosure summary').click();
      const form = page.locator('form.requirement-create');
      const box = page.locator('#requirement-document');
      const button = form.getByRole('button', { name: '提取草稿' });
      let calls = 0;
      page.on('request', (request) => { if (request.method() === 'POST' && new URL(request.url()).pathname === DRAFT_PATH) calls += 1; });
      const big = '读者可以预约图书。'.repeat(7000);
      await form.locator('input[type=file]').setInputFiles({ name: '超长.txt', mimeType: 'text/plain', buffer: Buffer.from(big, 'utf8') });
      await until(() => box.evaluate((element) => element.value.length), (value) => value === big.length, 5000);
      await button.click();
      const alert = form.locator('[role=alert]').first();
      await alert.waitFor();
      const text = await alert.innerText();
      check('T8 超长文档给出字符数与上限，且不发请求', text.includes('63000') && text.includes('60000') && calls === 0, { text, calls });
      await form.screenshot({ path: `${OUT}/t8-oversize-fixed.png`, animations: 'disabled' });

      const exact = '读'.repeat(60000);
      await form.locator('input[type=file]').setInputFiles({ name: '刚好.txt', mimeType: 'text/plain', buffer: Buffer.from(exact, 'utf8') });
      await until(() => box.evaluate((element) => element.value.length), (value) => value === 60000, 5000);
      const answered = page.waitForResponse((response) => new URL(response.url()).pathname === DRAFT_PATH, { timeout: 150000 });
      await button.click();
      const response = await answered;
      check('T8 刚好 60000 字符不被前端拦下，请求发到后端', calls === 1, { status: response.status() });
      // 60000 字符的文档加上指令后必然超过 Prompt 预算：后端按契约答 422 ai_prompt_too_large。
      const body = await response.json().catch(() => ({}));
      const shown = await form.locator('[role=alert]').first().innerText().catch(() => '');
      check('T8 文档加指令超出 Prompt 预算时，页面显示后端给出的原因', response.status() !== 422 || shown.includes(body.message), { code: body.code, shown });
    });

    await step('T12 三档宽度', async () => {
      const state = await context.storageState();
      for (const width of [1440, 768, 390]) {
        for (const motion of ['no-preference', 'reduce']) {
          const sized = await browser.newContext({ viewport: { width, height: 900 }, locale: 'zh-CN', reducedMotion: motion, storageState: state });
          await serveLocalBuild(sized);
          const view = await sized.newPage();
          watch(view, result, () => `${phase} ${width}/${motion}`);
          view.setDefaultTimeout(30000);
          await view.goto(LIST, { waitUntil: 'domcontentloaded' });
          await view.locator('details.requirement-create-disclosure summary').click();
          await view.locator('#requirement-document').fill('# 读者证挂失\n\n读者可以在线挂失读者证。挂失后该证不能再借书。');
          await view.locator('form.requirement-create input[type=file]').setInputFiles({ name: '一份名字比较长的需求文档-第三版-最终稿-修订.md', mimeType: 'text/markdown', buffer: Buffer.from('# 逾期提醒\n\n到期前 3 天提醒一次。', 'utf8') });
          await view.waitForTimeout(400);
          const measured = await view.evaluate(() => {
            const root = document.documentElement;
            const rect = (element) => element.getBoundingClientRect();
            const file = rect(document.querySelector('form.requirement-create input[type=file]'));
            const button = rect([...document.querySelectorAll('form.requirement-create button')].find((item) => item.textContent.trim() === '提取草稿'));
            const box = rect(document.querySelector('#requirement-document'));
            return { viewport: root.clientWidth, overflow: root.scrollWidth - root.clientWidth, fileLeft: Math.round(file.left), fileRight: Math.round(file.right),
              buttonLeft: Math.round(button.left), buttonRight: Math.round(button.right), buttonHeight: Math.round(button.height), boxWidth: Math.round(box.width) };
          });
          check(`T12 ${width}px / ${motion === 'reduce' ? '减动效' : '正常动效'}：无页面级横向溢出，控件都在视口内`,
            measured.overflow <= 0 && measured.fileLeft >= 0 && measured.fileRight <= measured.viewport
              && measured.buttonLeft >= 0 && measured.buttonRight <= measured.viewport && measured.boxWidth >= 200, measured);
          if (motion === 'reduce') {
            const target = await view.locator('#requirement-document').boundingBox();
            await view.screenshot({ path: `${OUT}/t12-form-${width}.png`, animations: 'disabled',
              clip: { x: 0, y: Math.max(0, target.y - 60), width, height: 560 } });
          }
          await sized.close();
        }
      }
    });
  } catch (error) {
    check('脚本整体执行', false, String(error.message).split('\n')[0]);
  } finally {
    save();
    await browser.close();
  }
  const failed = result.checks.filter((item) => !item.ok).length;
  console.log(`\n${result.checks.length - failed} passed, ${failed} failed; pageErrors=${result.pageErrors.length}`);
})();
