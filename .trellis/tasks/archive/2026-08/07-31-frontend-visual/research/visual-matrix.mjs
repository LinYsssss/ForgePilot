// 视觉升级验收矩阵:登录页 + 仪表盘(暗/亮 × 1440/860)+ 行号 diff 页截图。
// 用法同 walkthrough.mjs;凭据经 RS_USER/RS_PASS。
import { chromium } from 'playwright'

const base = process.argv[2] || 'http://localhost:5173'
const out = process.argv[3] || '/out'
const pageErrors = []

const browser = await chromium.launch()

async function session(viewport, theme, tag) {
  const page = await browser.newPage({ viewport })
  page.on('pageerror', err => pageErrors.push(`${tag}: ${err}`))
  await page.goto(`${base}/#/dashboard`, { waitUntil: 'networkidle' })
  await page.waitForSelector('.auth-card', { timeout: 10000 })
  if (tag === 'dark-desktop') await page.screenshot({ path: `${out}/m-login.png` })
  await page.fill('.auth-card input[autocomplete="username"]', process.env.RS_USER)
  await page.fill('.auth-card input[type="password"]', process.env.RS_PASS)
  await page.click('.auth-card button')
  await page.waitForSelector('.app-shell', { timeout: 15000 })
  await page.waitForTimeout(1500)
  if (theme === 'light') { await page.click('.theme-toggle'); await page.waitForTimeout(600) }
  await page.screenshot({ path: `${out}/m-dashboard-${tag}.png` })
  if (tag === 'dark-desktop') {
    // 行号 diff:进仓库页,选第一个 commit,看变更
    await page.click('.sidebar nav button:has-text("仓库")')
    await page.waitForTimeout(900)
    const rows = page.locator('.row-commits')
    if (await rows.count()) {
      await rows.first().click()
      await page.click('button:has-text("查看变更")')
      await page.waitForTimeout(1200)
      await page.screenshot({ path: `${out}/m-diff-numbered.png`, fullPage: false })
    }
    // 报告详情(置信度/证据卡)
    await page.click('.sidebar nav button:has-text("审查")')
    await page.waitForTimeout(800)
    const reports = page.locator('.row-reports')
    if (await reports.count()) {
      await reports.first().click()
      await page.waitForTimeout(900)
      await page.screenshot({ path: `${out}/m-report.png`, fullPage: false })
    }
  }
  await page.close()
}

await session({ width: 1440, height: 900 }, 'dark', 'dark-desktop')
await session({ width: 1440, height: 900 }, 'light', 'light-desktop')
await session({ width: 860, height: 900 }, 'dark', 'dark-narrow')

await browser.close()
console.log(JSON.stringify({ pageErrors }, null, 2))
if (pageErrors.length) process.exit(1)
