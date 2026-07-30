import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { test } from 'node:test'
import config from '../vite.config.js'
import { canApprovePatch } from '../src/components/agent/patchApprovalPolicy.js'
import { unwrapPage } from '../src/api/page.js'
import { ApiError } from '../src/api/apiError.js'

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
})

test('invalid or stale patches disable human approval', () => {
  assert.equal(canApprovePatch({ applyStatus: 'FAILED', targetDisappeared: true }), false)
  assert.equal(canApprovePatch({ applyStatus: 'SUCCEEDED', targetDisappeared: false }), false)
  assert.equal(canApprovePatch({ applyStatus: 'SUCCEEDED', targetDisappeared: true, stale: true }), false)
  assert.equal(canApprovePatch({ applyStatus: 'SUCCEEDED', targetDisappeared: true, stale: false }), true)
})

test('agent workspace keeps run filtering, live refresh, and citation navigation', async () => {
  const app = await readFile(new URL('../src/App.vue', import.meta.url), 'utf8')
  const findings = await readFile(new URL('../src/components/agent/AgentFindings.vue', import.meta.url), 'utf8')
  assert.match(app, /filteredAgentRuns/)
  assert.match(app, /startAgentPolling/)
  assert.match(app, /agent-evidence=/)
  assert.match(app, /\/cancel/)
  assert.match(app, /\/retry/)
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
  const app = await readFile(new URL('../src/App.vue', import.meta.url), 'utf8')
  const adapted = app.match(/unwrapPage\(await api\(/g) || []
  assert.equal(adapted.length, 4, 'knowledge documents / review tasks / review reports / mq logs')
  assert.match(app, /knowledge\/documents\?size=100/)
  assert.match(app, /reviews\/tasks\?size=100/)
  assert.match(app, /reviews\/reports\?size=100/)
  assert.match(app, /mq\/logs\?taskId=\$\{taskId\}&size=100/)
})

test('no hardcoded demo account and no token in web storage', async () => {
  const app = await readFile(new URL('../src/App.vue', import.meta.url), 'utf8')
  const client = await readFile(new URL('../src/api/client.js', import.meta.url), 'utf8')
  const session = await readFile(new URL('../src/composables/useSession.js', import.meta.url), 'utf8')
  assert.doesNotMatch(app, /ysainlin/)
  assert.match(app, /username: ''/)
  // 会话凭据只活在 HttpOnly Cookie 里;主题偏好之外(useTheme)不允许碰 Web Storage。
  for (const source of [app, client, session]) {
    assert.doesNotMatch(source, /localStorage|sessionStorage/)
  }
})

test('session loss is handled once, centrally', async () => {
  const app = await readFile(new URL('../src/App.vue', import.meta.url), 'utf8')
  const client = await readFile(new URL('../src/api/client.js', import.meta.url), 'utf8')
  assert.match(client, /setUnauthorizedHandler/)
  assert.match(client, /status === 401/)
  assert.match(app, /setUnauthorizedHandler\(/)
  assert.match(app, /error instanceof ApiError && error\.status === 401/)
  // 旧实现靠中文提示串嗅探 401,禁止回潮
  assert.doesNotMatch(app, /msg\.includes\('401'\)/)
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
  const app = await readFile(new URL('../src/App.vue', import.meta.url), 'utf8')
  const reset = app.slice(app.indexOf('function resetReviewState'), app.indexOf('async function createReview'))
  assert.match(reset, /stopAgentPolling\(\)/)
  assert.match(reset, /agentRuns\.value = \[\]/)
})
