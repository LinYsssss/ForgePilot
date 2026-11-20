// 只读冒烟：重跑后的数据（review-6 / gpt-6-luna、终局决定、账户绑定、已完成需求）在各页面是否正常呈现。
// 不点任何会写入的按钮；结束时断言除登录外没有写请求。
const { chromium, fs, OUT, BASE, recorder, watch, loginOn } = require('./lib.cjs');

const PAGES = {
  rev01: [
    { key: 'workspace', url: '/workspace?project=2', expect: ['模型置信度校准', 'AI 调用台账'] },
    { key: 'projects', url: '/projects', expect: ['tenant-user-center', 'halo-replay'] },
    { key: 'members', url: '/projects/2/members', expect: ['dev01', 'LinYsssss'] },
    { key: 'requirements', url: '/requirements?project=2', expect: ['已完成', '开发中'] },
    { key: 'requirement-done', url: '/requirements/8?project=2', expect: ['已完成', 'AC-1'] },
    { key: 'knowledge', url: '/knowledge?project=2', expect: ['可用于审查'] },
    { key: 'repository', url: '/repositories?project=2', expect: ['GitHub'] },
    { key: 'reviews', url: '/reviews?project=2', expect: ['review-6', 'gpt-6-luna', '已通过', '已退回'] },
    { key: 'review-approved', url: '/reviews/218?project=2', expect: ['已通过', 'review-6', 'gpt-6-luna'] },
    { key: 'review-returned-round1', url: '/reviews/183?project=2', expect: ['已退回', '已关闭'] },
    { key: 'review-rejected', url: '/reviews/190?project=3', expect: ['已拒绝并关闭', '已确认'] },
    { key: 'review-mall', url: '/reviews/221?project=1', expect: ['已退回', '已确认', 'PromotionDispatchJob.java'] },
    { key: 'review-halo-pending', url: '/reviews/208?project=4', expect: ['尚无终局决定', '已驳回'] },
    { key: 'review-halo-clean', url: '/reviews/197?project=4', expect: ['尚无终局决定', '已覆盖'] },
    { key: 'account', url: '/account', expect: ['rev01'] },
  ],
  dev01: [
    { key: 'dev-account', url: '/account', expect: ['LinYsssss', '演示仓作者账号'] },
    { key: 'dev-review-mall', url: '/reviews/221?project=1', expect: ['已退回', '认领'] },
    { key: 'dev-requirements', url: '/requirements?project=1', expect: ['开发中'] },
    { key: 'dev-workspace', url: '/workspace?project=1', expect: ['我的待办'] },
  ],
};
const SHOTS = new Set(['workspace', 'reviews', 'review-approved', 'review-halo-pending', 'requirement-done', 'dev-review-mall']);

(async () => {
  const { result, check, save } = recorder('smoke');
  let tag = 'login';
  const browser = await chromium.launch({ headless: true, args: ['--no-sandbox'] });
  try {
    for (const [user, pages] of Object.entries(PAGES)) {
      const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, locale: 'zh-CN', reducedMotion: 'reduce' });
      const page = await context.newPage();
      watch(page, result, () => tag);
      tag = `login ${user}`;
      await loginOn(page, user);
      for (const item of pages) {
        for (const width of [1440, 390]) {
          tag = `${item.key}@${width}`;
          const errorsBefore = result.pageErrors.length;
          const failuresBefore = result.failedResponses.length;
          try {
            await page.setViewportSize({ width, height: 1000 });
            await page.goto(BASE + item.url, { waitUntil: 'domcontentloaded' });
            await page.waitForLoadState('networkidle', { timeout: 20000 }).catch(() => {});
            await page.locator('main h1, main h2').first().waitFor({ timeout: 15000 });
            const state = await page.evaluate(() => {
              const root = document.documentElement;
              const visible = (element) => element.getClientRects().length > 0;
              return {
                path: location.pathname,
                overflow: root.scrollWidth - root.clientWidth,
                alerts: [...document.querySelectorAll('main [role=alert]')].filter(visible).map((element) => element.innerText.trim().slice(0, 80)),
                text: document.querySelector('main').innerText,
              };
            });
            const missing = width === 1440 ? item.expect.filter((text) => !state.text.includes(text)) : [];
            const newErrors = result.pageErrors.slice(errorsBefore).map((entry) => entry.message.slice(0, 120));
            const newFailures = result.failedResponses.slice(failuresBefore).map((entry) => `${entry.status} ${entry.path}`);
            const ok = missing.length === 0 && state.overflow <= 0 && state.alerts.length === 0 && newErrors.length === 0 && newFailures.length === 0
              && state.path === new URL(BASE + item.url).pathname;
            check(`${user} ${item.key} @${width}`, ok,
              ok ? undefined : { missing, overflow: state.overflow, alerts: state.alerts, pageErrors: newErrors, failed: newFailures, path: state.path });
            if (width === 1440 && SHOTS.has(item.key)) {
              await page.screenshot({ path: `${OUT}/smoke-${item.key}.png`, animations: 'disabled' });
            }
          } catch (error) {
            check(`${user} ${item.key} @${width}`, false, String(error.message).split('\n')[0]);
          }
        }
      }
      await context.close();
    }
    const writes = result.writes.filter((entry) => entry.path !== '/api/auth/login');
    check('整轮冒烟除登录外没有任何写请求', writes.length === 0, writes);
  } finally {
    save();
    await browser.close();
  }
  const failed = result.checks.filter((item) => !item.ok).length;
  console.log(`\n${result.checks.length - failed} passed, ${failed} failed; pageErrors=${result.pageErrors.length}`);
})();
