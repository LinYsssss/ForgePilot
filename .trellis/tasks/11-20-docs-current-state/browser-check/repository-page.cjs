// 复查仓库接入页的修复：页面与静态资产取本地新构建，/api 仍走线上后端。
//  - 非负责人打开仓库接入页，不应再请求只有负责人可读的通知配置。
//  - 负责人打开时仍应请求并看到通知配置区。
const path = require('node:path');
const { chromium, fs, OUT, BASE, recorder, watch, loginOn } = require('./lib.cjs');

const DIST = '/root/ForgePilot/frontend/dist';
const TYPES = { '.js': 'text/javascript', '.css': 'text/css', '.html': 'text/html', '.png': 'image/png', '.svg': 'image/svg+xml', '.ico': 'image/x-icon', '.woff2': 'font/woff2' };

async function serveLocalBuild(context) {
  await context.route((url) => url.origin === BASE && !url.pathname.startsWith('/api/'), (route) => {
    const pathname = new URL(route.request().url()).pathname;
    const file = path.join(DIST, pathname);
    const target = pathname !== '/' && file.startsWith(DIST) && fs.existsSync(file) && fs.statSync(file).isFile() ? file : path.join(DIST, 'index.html');
    return route.fulfill({ status: 200, contentType: TYPES[path.extname(target)] || 'application/octet-stream', body: fs.readFileSync(target) });
  });
}

(async () => {
  const { result, check, save } = recorder('repository-page');
  const self = JSON.parse(fs.readFileSync(`${OUT}/project.json`, 'utf8')).projectId;
  const cases = [
    { user: 'rev01', project: 2, leader: false },   // 审查人，非负责人
    { user: 'dev01', project: 1, leader: false },   // 开发，非负责人
    { user: 'dev02', project: self, leader: true }, // 自测项目的负责人
  ];
  let tag = 'start';
  const browser = await chromium.launch({ headless: true, args: ['--no-sandbox'] });
  try {
    for (const item of cases) {
      const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, locale: 'zh-CN', reducedMotion: 'reduce' });
      await serveLocalBuild(context);
      const page = await context.newPage();
      watch(page, result, () => tag);
      tag = `login ${item.user}`;
      await loginOn(page, item.user);
      tag = `${item.user} project ${item.project}`;
      const failuresBefore = result.failedResponses.length;
      const notifications = [];
      page.on('response', (response) => {
        if (new URL(response.url()).pathname.endsWith('/notifications/dingtalk')) notifications.push(response.status());
      });
      await page.goto(`${BASE}/repositories?project=${item.project}`, { waitUntil: 'domcontentloaded' });
      await page.waitForLoadState('networkidle', { timeout: 20000 }).catch(() => {});
      await page.locator('main h1, main h2').first().waitFor();
      const editor = await page.locator('.notification-editor').count();
      const failed = result.failedResponses.slice(failuresBefore).map((entry) => `${entry.status} ${entry.path}`);
      if (item.leader) {
        check(`${item.user}（负责人）：请求通知配置一次且成功，通知配置区可见`,
          notifications.length === 1 && notifications[0] === 200 && editor === 1 && failed.length === 0, { notifications, editor, failed });
      } else {
        check(`${item.user}（非负责人）：不请求通知配置，页面没有任何失败的请求`,
          notifications.length === 0 && editor === 0 && failed.length === 0, { notifications, editor, failed });
      }
      await context.close();
    }
  } catch (error) {
    check('脚本整体执行', false, String(error.message).split('\n')[0]);
  } finally {
    save();
    await browser.close();
  }
  const bad = result.checks.filter((entry) => !entry.ok).length;
  console.log(`\n${result.checks.length - bad} passed, ${bad} failed; pageErrors=${result.pageErrors.length}`);
})();
