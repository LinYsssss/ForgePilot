// 浏览器自测的公共部分：启动、登录、记录。只用测试账号；口令从本机文件读取，不打印。
const { chromium } = require('/root/forgepilot-qa/node_modules/playwright-core');
const fs = require('node:fs');

process.umask(0o077);
const OUT = __dirname;
const BASE = process.env.FP_BASE || 'https://yasinlin.com';

function password() {
  const line = fs.readFileSync('/root/forgepilot-demo-docs/测试账号.txt', 'utf8')
    .split('\n').find((item) => item.includes('统一密码'));
  return line.replace(/.*[:：]/, '').trim();
}

function recorder(name) {
  const result = {
    name, base: BASE, startedAt: new Date().toISOString(),
    checks: [], pageErrors: [], consoleErrors: [], failedResponses: [], writes: [],
  };
  const check = (label, ok, detail) => {
    result.checks.push({ label, ok: Boolean(ok), detail: detail === undefined ? null : detail });
    const extra = detail === undefined || detail === null ? '' : ' — ' + (typeof detail === 'string' ? detail : JSON.stringify(detail));
    console.log((ok ? 'PASS ' : 'FAIL ') + label + extra.slice(0, 400));
  };
  const save = () => {
    result.finishedAt = new Date().toISOString();
    fs.writeFileSync(`${OUT}/${name}.json`, JSON.stringify(result, null, 2));
  };
  return { result, check, save };
}

/** 记录页面错误、控制台 error、4xx/5xx 的 API 响应，以及全部写请求。 */
function watch(page, result, tag) {
  page.on('pageerror', (error) => result.pageErrors.push({ tag: tag(), message: error.message }));
  page.on('console', (message) => {
    if (message.type() === 'error') result.consoleErrors.push({ tag: tag(), text: message.text().slice(0, 300) });
  });
  page.on('response', (response) => {
    const url = new URL(response.url());
    if (!url.pathname.startsWith('/api/')) return;
    const method = response.request().method();
    if (response.status() >= 400) {
      result.failedResponses.push({ tag: tag(), method, path: url.pathname, status: response.status() });
    }
    if (method !== 'GET') result.writes.push({ tag: tag(), method, path: url.pathname, status: response.status() });
  });
}

async function loginOn(page, username) {
  page.setDefaultTimeout(30000);
  await page.goto(BASE + '/login', { waitUntil: 'domcontentloaded' });
  await page.locator('#login-username').fill(username);
  await page.locator('#login-password').fill(password());
  await Promise.all([
    page.waitForURL((url) => !url.pathname.startsWith('/login'), { timeout: 30000 }),
    page.locator('button.login-submit').click(),
  ]);
}

async function until(read, accept, timeoutMs = 20000) {
  const deadline = Date.now() + timeoutMs;
  let value;
  for (;;) {
    value = await read();
    if (accept(value) || Date.now() > deadline) return value;
    await new Promise((resolve) => setTimeout(resolve, 400));
  }
}

module.exports = { chromium, fs, OUT, BASE, recorder, watch, loginOn, until };
