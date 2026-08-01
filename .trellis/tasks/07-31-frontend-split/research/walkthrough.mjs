// RepoSage 前端拆分后的自动走查:登录 → 遍历 8 个路由页 → 主题切换 → 退出。
// 在 mcr.microsoft.com/playwright 容器内以 --network host 运行:
//   node walkthrough.mjs <baseURL> <outDir>
// 凭据经环境变量 RS_USER / RS_PASS 传入,不落盘不回显。
import { chromium } from 'playwright'

const base = process.argv[2] || 'http://localhost:5173'
const out = process.argv[3] || '/out'
const user = process.env.RS_USER
const pass = process.env.RS_PASS

const consoleErrors = []
const pageErrors = []

const browser = await chromium.launch()
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
page.on('console', msg => { if (msg.type() === 'error') consoleErrors.push(msg.text()) })
page.on('pageerror', err => pageErrors.push(String(err)))

async function shot(name) { await page.screenshot({ path: `${out}/${name}.png`, fullPage: false }) }

// 1. 未登录 → 登录页
await page.goto(`${base}/#/dashboard`, { waitUntil: 'networkidle' })
await page.waitForSelector('.auth-card', { timeout: 10000 })
await shot('01-login')

// 2. 登录
await page.fill('.auth-card input[autocomplete="username"]', user)
await page.fill('.auth-card input[type="password"]', pass)
await page.click('.auth-card button')
await page.waitForSelector('.app-shell', { timeout: 15000 })
await page.waitForTimeout(1200)
await shot('02-dashboard')

// 3. 遍历侧边导航(与路由名对应的顺序)
const navPages = [
  ['项目', 'projects'], ['仓库', 'repository'], ['PR 工作流', 'pull-requests'],
  ['知识库', 'knowledge'], ['审查', 'reviews'], ['Agent 审批', 'agent'], ['AI 日志', 'ai-logs'],
]
let idx = 3
for (const [label, path] of navPages) {
  await page.click(`.sidebar nav button:has-text("${label}")`)
  await page.waitForTimeout(900)
  const hash = new URL(page.url()).hash
  if (!hash.startsWith(`#/${path}`)) pageErrors.push(`nav ${label}: expected #/${path}, got ${hash}`)
  await shot(`${String(idx).padStart(2, '0')}-${path}`)
  idx += 1
}

// 4. 刷新保持路由(核对 vue-router 的核心收益)
await page.reload({ waitUntil: 'networkidle' })
await page.waitForTimeout(1200)
const afterReload = new URL(page.url()).hash
if (!afterReload.startsWith('#/ai-logs')) pageErrors.push(`reload lost route: ${afterReload}`)

// 5. 主题切换
await page.click('.theme-toggle')
await page.waitForTimeout(500)
await shot('10-theme-light')
await page.click('.theme-toggle')

// 6. 退出登录 → 回到登录页
await page.click('.sidebar-foot .ghost')
await page.waitForSelector('.auth-card', { timeout: 10000 })
await shot('11-after-logout')

await browser.close()

const report = { consoleErrors, pageErrors }
console.log(JSON.stringify(report, null, 2))
if (pageErrors.length) process.exit(1)
