import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { test } from 'node:test'
import config from '../vite.config.js'
import { canApprovePatch } from '../src/components/agent/patchApprovalPolicy.js'
import { unwrapPage } from '../src/api/page.js'
import { ApiError } from '../src/api/apiError.js'
import { APP_TITLE, DOWNLOAD_FALLBACK_NAME, PRODUCT_NAME, PRODUCT_TAGLINE } from '../src/shared/brand.js'
import {
  STACK_MAX_CHARS,
  createClientErrorReporter,
} from '../src/shared/telemetry/clientErrorReporter.js'

test('frontend declares the supported Node runtime', async () => {
  const packageJson = JSON.parse(
    await readFile(new URL('../package.json', import.meta.url), 'utf8')
  )

  assert.equal(packageJson.engines?.node, '>=22 <23')
})

test('vite build output is deterministic', () => {
  assert.equal(config.build?.outDir, 'dist')
  assert.equal(config.build?.emptyOutDir, true)
})

test('index declares the Vue entrypoint', async () => {
  const html = await readFile(new URL('../index.html', import.meta.url), 'utf8')

  assert.match(html, /src="\/src\/main\.js"/)
  assert.match(html, /<title>ForgePilot 智能代码审查平台<\/title>/)
})

test('ForgePilot branding stays centralized across visible frontend surfaces', async () => {
  assert.equal(PRODUCT_NAME, 'ForgePilot')
  assert.equal(PRODUCT_TAGLINE, '墨境审查院')
  assert.equal(APP_TITLE, 'ForgePilot 智能代码审查平台')
  assert.equal(DOWNLOAD_FALLBACK_NAME, 'forgepilot-download')

  const main = await readFile(new URL('../src/main.js', import.meta.url), 'utf8')
  const login = await readFile(new URL('../src/features/auth/LoginGate.vue', import.meta.url), 'utf8')
  const shell = await readFile(new URL('../src/features/shell/InkShell.vue', import.meta.url), 'utf8')
  const rail = await readFile(new URL('../src/features/workspace/AnnotationRail.vue', import.meta.url), 'utf8')
  const client = await readFile(new URL('../src/api/client.js', import.meta.url), 'utf8')

  assert.match(main, /document\.title = APP_TITLE/)
  assert.match(login, /\{\{ PRODUCT_NAME \}\}/)
  assert.match(shell, /\{\{ PRODUCT_NAME \}\}/)
  assert.match(rail, /\{\{ PRODUCT_NAME \}\} 守门规范/)
  assert.match(client, /DOWNLOAD_FALLBACK_NAME/)
  assert.doesNotMatch(login + shell + rail + client, /RepoSage/)
})

test('invalid or stale patches disable human approval', () => {
  assert.equal(canApprovePatch({ applyStatus: 'FAILED', targetDisappeared: true }), false)
  assert.equal(canApprovePatch({ applyStatus: 'SUCCEEDED', targetDisappeared: false }), false)
  assert.equal(canApprovePatch({ applyStatus: 'SUCCEEDED', targetDisappeared: true, stale: true }), false)
  assert.equal(canApprovePatch({ applyStatus: 'SUCCEEDED', targetDisappeared: true, stale: false }), true)
})

test('agent workspace keeps run filtering, live refresh, and citation navigation', async () => {
  const agent = await readFile(new URL('../src/composables/useAgentWorkspace.js', import.meta.url), 'utf8')
  const routerSource = await readFile(new URL('../src/router.js', import.meta.url), 'utf8')
  const findings = await readFile(new URL('../src/components/agent/AgentFindings.vue', import.meta.url), 'utf8')
  assert.match(agent, /filteredAgentRuns/)
  assert.match(agent, /startAgentPolling/)
  // 旧 "#agent-evidence=" 外链由路由层转成 /agent?evidence=,定位逻辑读 route query。
  assert.match(routerSource, /agent-evidence=/)
  assert.match(agent, /query\(\)\.evidence/)
  assert.match(agent, /\/cancel/)
  assert.match(agent, /\/retry/)
  assert.match(findings, /data-evidence-path/)
})

/* ---------- B9 前端最小改造(P1-13/14、分页信封、401、CSRF、SSE 生命周期) ---------- */

test('page envelope adapter accepts both pre- and post-merge shapes', () => {
  // 合流前:裸数组原样返回
  assert.deepEqual(unwrapPage([1, 2]), [1, 2])
  // 合流后:冻结契约 { items, page, size, totalElements, totalPages }
  assert.deepEqual(
    unwrapPage({ items: ['a'], page: 0, size: 20, totalElements: 1, totalPages: 1 }),
    ['a']
  )
  // 异常形状不炸列表渲染
  assert.deepEqual(unwrapPage(null), [])
  assert.deepEqual(unwrapPage({ code: 500 }), [])
})

test('ApiError carries status, code and traceId for branching', () => {
  const error = new ApiError('boom', { status: 403, code: 40301, traceId: 't-1' })
  assert.ok(error instanceof Error)
  assert.equal(error.status, 403)
  assert.equal(error.code, 40301)
  assert.equal(error.traceId, 't-1')
  // 默认值:网络层失败没有 HTTP 状态
  assert.equal(new ApiError('x').status, 0)
})

test('the four paginated endpoints go through the envelope adapter', async () => {
  const reviews = await readFile(new URL('../src/composables/useReviews.js', import.meta.url), 'utf8')
  const knowledge = await readFile(new URL('../src/composables/useKnowledge.js', import.meta.url), 'utf8')
  const adapted = [...(reviews.match(/unwrapPage\(await api\(/g) || []), ...(knowledge.match(/unwrapPage\(await api\(/g) || [])]
  assert.equal(adapted.length, 4, 'knowledge documents / review tasks / review reports / mq logs')
  assert.match(knowledge, /knowledge\/documents\?size=100/)
  assert.match(reviews, /reviews\/tasks\?size=100/)
  assert.match(reviews, /reviews\/reports\?size=100/)
  assert.match(reviews, /mq\/logs\?taskId=\$\{taskId\}&size=100/)
})

test('no hardcoded demo account and no token in web storage', async () => {
  const login = await readFile(new URL('../src/features/auth/LoginGate.vue', import.meta.url), 'utf8')
  const client = await readFile(new URL('../src/api/client.js', import.meta.url), 'utf8')
  const session = await readFile(new URL('../src/composables/useSession.js', import.meta.url), 'utf8')
  assert.doesNotMatch(login, /ysainlin/)
  assert.match(login, /username: ''/)
  // 会话凭据只活在 HttpOnly Cookie 里;主题偏好之外(useTheme)不允许碰 Web Storage。
  for (const source of [login, client, session]) {
    assert.doesNotMatch(source, /localStorage|sessionStorage/)
  }
})

test('session loss is handled once, centrally', async () => {
  const app = await readFile(new URL('../src/App.vue', import.meta.url), 'utf8')
  const client = await readFile(new URL('../src/api/client.js', import.meta.url), 'utf8')
  // run() 的 401 短路已抽到共享 useBusy composable,所有视图共用同一处。
  const busyRunner = await readFile(new URL('../src/composables/useBusy.js', import.meta.url), 'utf8')
  assert.match(client, /setUnauthorizedHandler/)
  assert.match(client, /status === 401/)
  assert.match(app, /setUnauthorizedHandler\(/)
  assert.match(busyRunner, /error instanceof ApiError && error\.status === 401/)
  // 旧实现靠中文提示串嗅探 401,禁止回潮
  assert.doesNotMatch(app, /msg\.includes\('401'\)/)
  assert.doesNotMatch(busyRunner, /msg\.includes\('401'\)/)
})

test('csrf tokens are bootstrapped, read from the cookie, and never cached', async () => {
  const app = await readFile(new URL('../src/App.vue', import.meta.url), 'utf8')
  const client = await readFile(new URL('../src/api/client.js', import.meta.url), 'utf8')
  assert.match(client, /initCsrf/)
  assert.match(client, /XSRF-TOKEN/)
  assert.match(client, /X-XSRF-TOKEN/)
  assert.match(client, /document\.cookie/)
  assert.match(app, /await initCsrf\(\)/)
})

test('agent SSE and pollers are torn down with the session', async () => {
  const workspace = await readFile(new URL('../src/composables/useWorkspace.js', import.meta.url), 'utf8')
  const agent = await readFile(new URL('../src/composables/useAgentWorkspace.js', import.meta.url), 'utf8')
  // 项目切换/退出统一走 workspace.resetForProject → agent.reset(关 SSE、停轮询、清状态)。
  const reset = workspace.slice(workspace.indexOf('function resetForProject'), workspace.indexOf('function selectProject'))
  assert.match(reset, /agent\.reset\(\)/)
  const agentReset = agent.slice(agent.indexOf('function reset'))
  assert.match(agentReset, /stopAgentPolling\(\)/)
  assert.match(agentReset, /agentRuns\.value = \[\]/)
})

/* ---------- 前端错误上报(生产加固 R4 前端侧) ---------- */

test('client error reporter dedupes, caps, and never throws out of the handler', () => {
  const sent = []
  const reporter = createClientErrorReporter({
    send: (payload) => sent.push(payload),
    now: () => 1_700_000_000_000,
    currentUrl: () => 'https://example.test/ink',
    maxReports: 3,
  })

  const err = new Error('boom')
  err.stack = 'Error: boom\n    at renderRow (App.vue:12:3)\n    at patch (vue.js:9:1)'

  assert.equal(reporter.report(err.message, err.stack), true)
  // 同一处反复抛出(渲染循环)必须收敛成一条,否则会打满服务端 10/分 的预算
  assert.equal(reporter.report(err.message, err.stack), false)
  assert.equal(sent.length, 1)
  assert.deepEqual(Object.keys(sent[0]).sort(), ['message', 'stack', 'ts', 'url'])
  assert.equal(sent[0].ts, 1_700_000_000_000)
  assert.equal(sent[0].url, 'https://example.test/ink')

  // message 是服务端 @NotBlank 字段:空值本地丢弃,不去换一个 400
  assert.equal(reporter.report('   '), false)
  assert.equal(reporter.report(null), false)

  // 单页会话封顶
  assert.equal(reporter.report('second'), true)
  assert.equal(reporter.report('third'), true)
  assert.equal(reporter.report('fourth'), false)
  assert.equal(reporter.stats().sent, 3)
})

test('reporter swallows transport failure instead of feeding window.onerror', () => {
  const reporter = createClientErrorReporter({
    send: () => { throw new Error('beacon exploded') },
  })
  // 若这里抛出,window.onerror 会再次捕获并再次上报,形成自激循环
  assert.doesNotThrow(() => reporter.report('transport dies'))
})

test('reporter normalizes both error events and non-Error rejections', () => {
  const sent = []
  const reporter = createClientErrorReporter({ send: (p) => sent.push(p), currentUrl: () => '/x' })

  reporter.onError({ message: 'from message', error: undefined })
  reporter.onRejection({ reason: new Error('rejected error') })
  reporter.onRejection({ reason: 'plain string reason' })
  reporter.onRejection({ reason: { code: 500 } })

  assert.deepEqual(sent.map((p) => p.message), [
    'from message',
    'rejected error',
    'plain string reason',
    'Unhandled rejection: [object Object]',
  ])
})

test('long stacks are truncated before they leave the browser', () => {
  const sent = []
  const reporter = createClientErrorReporter({ send: (p) => sent.push(p) })
  reporter.report('huge', 'x'.repeat(STACK_MAX_CHARS + 500))
  assert.ok(sent[0].stack.length < STACK_MAX_CHARS + 20)
  assert.match(sent[0].stack, /\[truncated\]$/)
})

test('the entrypoint installs error reporting before the app mounts', async () => {
  const main = await readFile(new URL('../src/main.js', import.meta.url), 'utf8')
  assert.match(main, /installClientErrorReporter\(/)
  // 必须早于 mount:挂载过程自身抛出的错误也要能被捕获
  assert.ok(main.indexOf('installClientErrorReporter(`') < main.indexOf('.mount('))
  // 端点走统一 API_BASE,不得另起一个硬编码地址
  assert.match(main, /API_BASE/)
})
