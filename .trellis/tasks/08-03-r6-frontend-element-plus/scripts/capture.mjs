// r6 截图工具:登录后按导航遍历各视图,输出整页截图到 ../screenshots/。
// 运行环境:mcr.microsoft.com/playwright:v1.48.0-noble 容器(--network host),
// 凭据经环境变量传入(RS_USER/RS_PASS,来源 deploy/.env,不落盘不打印)。
// 用法:BASE_URL=http://localhost PREFIX=before [VIEWS=login,projects] node capture.mjs
import { chromium } from 'playwright'
import { mkdirSync } from 'node:fs'

const BASE_URL = process.env.BASE_URL || 'http://localhost'
const PREFIX = process.env.PREFIX || 'shot'
const OUT = new URL('../screenshots/', import.meta.url).pathname
const WIDTH = Number(process.env.WIDTH || 1536)

// 导航按钮文本 → 截图文件名;顺序即演示动线。
const NAV_VIEWS = [
  ['概览', 'dashboard'],
  ['项目', 'projects'],
  ['仓库', 'repository'],
  ['PR 工作流', 'pull-requests'],
  ['知识库', 'knowledge'],
  ['审查', 'reviews'],
  ['Agent 审批', 'agent'],
  ['AI 日志', 'ai-logs'],
]
const only = process.env.VIEWS ? new Set(process.env.VIEWS.split(',')) : null
const wants = name => !only || only.has(name)

mkdirSync(OUT, { recursive: true })
const browser = await chromium.launch()
const page = await browser.newPage({ viewport: { width: WIDTH, height: 960 } })
// 冻结动画:styles.css 的 reduced-motion 块会把入场/漂移动画压到 0,避免截到半帧。
await page.emulateMedia({ reducedMotion: 'reduce' })

const settle = async () => {
  await page.waitForLoadState('networkidle').catch(() => {})
  await page.waitForTimeout(900)
}
const snap = async name => {
  await page.screenshot({ path: `${OUT}${PREFIX}-${name}.png`, fullPage: true })
  console.log(`saved ${PREFIX}-${name}.png`)
}

await page.goto(BASE_URL, { waitUntil: 'domcontentloaded' })
await page.waitForSelector('input[autocomplete="username"]', { timeout: 15000 })
await settle()
if (wants('login')) await snap('login')

await page.fill('input[autocomplete="username"]', process.env.RS_USER || '')
await page.fill('input[autocomplete="current-password"]', process.env.RS_PASS || '')
await page.getByRole('button', { name: '登录' }).click()
// 登录成功标志:退出登录按钮出现(Observatory 与 Element 壳皆有)。
await page.getByText('退出登录').waitFor({ timeout: 15000 })
// activeProject 异步装载后仓库等按钮才解禁,稍候。
await settle()

for (const [label, name] of NAV_VIEWS) {
  if (!wants(name)) continue
  // Observatory 导航是 button;Element 收尾后可能是 el-menu 的 menuitem——两种角色都认。
  let btn = page.getByRole('button', { name: label, exact: true })
  try {
    if (!(await btn.count())) btn = page.getByRole('menuitem', { name: label, exact: true })
    await btn.waitFor({ timeout: 8000 })
    for (let i = 0; i < 20 && (await btn.isDisabled()); i++) await page.waitForTimeout(500)
    await btn.click()
    await settle()
    await snap(name)
    // Agent 页深态:点「加载」拉起 run18 时间线 + findings,这是最复杂的对比面。
    if (name === 'agent') {
      await page.getByRole('button', { name: '加载', exact: true }).click()
      await settle()
      await snap('agent-loaded')
    }
  } catch (e) {
    console.error(`FAILED ${name}: ${e.message.split('\n')[0]}`)
  }
}

await browser.close()
