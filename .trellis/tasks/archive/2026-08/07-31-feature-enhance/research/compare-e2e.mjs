// 对比审查端到端:建项目 → 绑演示仓库(容器内路径) → 传 4 篇知识文档 → 一键对比 → 截图。
// 运行于 Playwright 容器(--network host);演示文档挂载在 /repos。
import { chromium } from 'playwright'

const base = process.argv[2] || 'http://localhost:5173'
const out = process.argv[3] || '/out'
const repoPathInBackend = process.argv[4] || '/ws/demo-repos/mall-order-service'
const docsDir = '/repos/mall-order-service/docs'
const pageErrors = []

const browser = await chromium.launch()
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
page.on('pageerror', err => pageErrors.push(String(err)))
// 诊断:记录所有 /api 响应状态,失败时能看到 401/403 来自哪个调用
page.on('response', async res => {
  if (res.url().includes('/api/')) {
    pageErrors.push(`HTTP ${res.status()} ${res.request().method()} ${res.url().replace(base, '')}`)
    if (res.url().includes('/api/auth/')) {
      const sc = (await res.headersArray()).filter(h => h.name.toLowerCase() === 'set-cookie').map(h => h.value.slice(0, 90))
      pageErrors.push(`SET-COOKIE[${res.url().replace(base, '')}]: ${JSON.stringify(sc)}`)
    }
  }
})
page.on('request', req => {
  if (req.method() !== 'GET' && req.url().includes('/api/')) {
    pageErrors.push(`REQ ${req.method()} ${req.url().replace(base, '')} XSRF=${(req.headers()['x-xsrf-token'] || 'NONE').slice(0, 13)} COOKIE=${(req.headers().cookie || '').replace(/reposage_auth=[^;]*/, 'auth=*').slice(0, 120)}`)
  }
})

async function main() {

async function nav(label) { await page.click(`.sidebar nav button:has-text("${label}")`); await page.waitForTimeout(800) }

// 登录
await page.goto(`${base}/#/dashboard`, { waitUntil: 'networkidle' })
await page.waitForSelector('.auth-card')
await page.fill('.auth-card input[autocomplete="username"]', process.env.RS_USER)
await page.fill('.auth-card input[type="password"]', process.env.RS_PASS)
await page.click('.auth-card button')
// 冷启动后端的首次登录(bcrypt + JIT + 登录后 initCsrf 重引导)可能超过 15s,放宽到 30s
await page.waitForSelector('.app-shell', { timeout: 30000 })
await page.waitForTimeout(800)


// 建项目
await nav('项目')
console.log('CTX-COOKIES', JSON.stringify(await page.context().cookies()))
console.log('DOC-COOKIE', await page.evaluate(() => document.cookie))
await page.fill('input[placeholder="mall-order-service"]', '对比审查演示')
await page.fill('input[placeholder="电商订单服务"]', '带/不带知识库对比')
await page.click('button:has-text("创建项目")')
await page.waitForTimeout(1500)
await page.screenshot({ path: `${out}/c0-projects.png` })
const toast = page.locator('.toast')
if (await toast.count()) console.log('toast after create:', await toast.first().innerText())
// 项目卡片没出现就没必要继续
await page.waitForSelector('.proj-card', { timeout: 10000 })

// 绑定演示仓库(后端容器内路径)
await nav('仓库')
await page.fill('input[placeholder="https://… 或本地演示路径"]', repoPathInBackend)
await page.selectOption('.panel select', 'LOCAL')
await page.click('button:has-text("绑定仓库")')
await page.waitForTimeout(2500)
await page.screenshot({ path: `${out}/c1-repository.png` })

// 上传 4 篇知识文档
await nav('知识库')
for (const doc of ['order-flow.md', 'db-schema.md', 'bug-history.md', 'security-policy.md']) {
  await page.setInputFiles('input[type="file"]', `${docsDir}/${doc}`)
  await page.click('button:has-text("上传并入库")')
  // 等该文档出现在「已入库文档」列表再传下一篇;固定 sleep 会在入库进行中吞掉下一次点击
  await page.waitForSelector(`.doc-card:has-text("${doc}")`, { timeout: 20000 })
}
await page.screenshot({ path: `${out}/c2-knowledge.png` })

// 一键对比审查(dev 后端 inline 同步完成)
await nav('审查')
await page.click('button:has-text("对比审查")')
await page.waitForTimeout(4000)
// 若报告到位但面板还没装载,点一次刷新
const refresh = page.locator('button:has-text("刷新对比数据")')
if (await refresh.count()) { await refresh.click(); await page.waitForTimeout(1500) }
await page.waitForSelector('.compare-summary, .compare-panel .empty', { timeout: 20000 })
await page.screenshot({ path: `${out}/c3-compare.png`, fullPage: true })
}

try {
  await main()
  console.log('E2E OK')
} catch (err) {
  await page.screenshot({ path: `${out}/c9-failure.png`, fullPage: true }).catch(() => {})
  console.error('E2E FAILED:', String(err).split('\n')[0])
  process.exitCode = 1
} finally {
  console.log(JSON.stringify({ log: pageErrors }, null, 2))
  await browser.close()
}
