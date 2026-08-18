import assert from 'node:assert/strict'
import { beforeEach, mock, test } from 'node:test'

// ---------------------------------------------------------------------------
// 行为测试:直接驱动单例 composable,不渲染组件。
// 前置桩:fetch(按 URL 路由)、document.cookie、EventSource。
// 必须在 import 业务模块之前就位,composable 模块级代码会立即执行。
// ---------------------------------------------------------------------------

const fetchRoutes = new Map()
function stubJson(pathPart, data) { fetchRoutes.set(pathPart, () => data) }
function stubFn(pathPart, fn) { fetchRoutes.set(pathPart, fn) }
function deferred() {
  let resolve
  const promise = new Promise(r => { resolve = r })
  return { promise, resolve }
}

// @vue/runtime-dom 在模块加载时会 createElement 探测能力,给足最小面
const fakeEl = () => ({
  style: {}, children: [], firstChild: null, innerHTML: '',
  setAttribute() {}, removeAttribute() {}, appendChild() {}, remove() {},
  cloneNode() { return fakeEl() }, addEventListener() {}, removeEventListener() {},
})
globalThis.document = {
  cookie: '',
  createElement: fakeEl,
  createComment: () => ({}),
  createTextNode: () => ({}),
  querySelector: () => null,
}
globalThis.fetch = async url => {
  for (const [part, fn] of fetchRoutes) {
    if (String(url).includes(part)) {
      return {
        ok: true, status: 200,
        headers: { get: () => null },
        json: async () => ({ code: 0, data: await fn() }),
      }
    }
  }
  throw new Error(`no fetch stub for ${url}`)
}

class FakeEventSource {
  static instances = []
  constructor(url) { this.url = url; this.closed = false; this.listeners = {}; FakeEventSource.instances.push(this) }
  addEventListener(name, fn) { this.listeners[name] = fn }
  close() { this.closed = true }
}
globalThis.EventSource = FakeEventSource

const { ApiError } = await import('../src/api/apiError.js')
const { useSession } = await import('../src/composables/useSession.js')
const { useToast } = await import('../src/composables/useToast.js')
const { useBusy } = await import('../src/composables/useBusy.js')
const { useConfirm } = await import('../src/composables/useConfirm.js')
const { useKnowledge } = await import('../src/composables/useKnowledge.js')
const { useReviews } = await import('../src/composables/useReviews.js')
const { useAgentWorkspace } = await import('../src/composables/useAgentWorkspace.js')
const { useAiLogs } = await import('../src/composables/useAiLogs.js')
const { useWorkbench } = await import('../src/composables/useWorkbench.js')
const { useDevelopmentMetrics } = await import('../src/composables/useDevelopmentMetrics.js')

const { activeProject } = useSession()
const { toast } = useToast()
const { busy, run } = useBusy()
const { reset: resetAiLogs } = useAiLogs()
const { reset: resetWorkbench } = useWorkbench()
const { reset: resetMetrics } = useDevelopmentMetrics()

beforeEach(() => {
  resetAiLogs()
  resetWorkbench()
  resetMetrics()
  activeProject.value = { projectId: 7, name: 'demo', defaultBranch: 'main' }
  toast.text = ''
  fetchRoutes.clear()
})

test('run() surfaces errors as toast and always clears the busy flag', async () => {
  await run(async () => { throw new Error('后端炸了') }, 'x')
  assert.equal(toast.text, '后端炸了')
  assert.equal(busy.x, false)
})

test('run() stays silent on 401 — the global handler owns session loss', async () => {
  await run(async () => { throw new ApiError('登录已过期', { status: 401 }) }, 'y')
  assert.equal(toast.text, '')
  assert.equal(busy.y, false)
})

test('confirm modal closes after a successful action and survives a failing one', async () => {
  const { confirmModal, ask, confirmAction } = useConfirm()
  let done = 0
  ask({ title: 't', body: 'b', onConfirm: async () => { done += 1 } })
  await confirmAction()
  assert.equal(done, 1)
  assert.equal(confirmModal.value, null)
  assert.equal(busy.confirm, false)

  ask({ title: 't2', body: 'b2', onConfirm: async () => { throw new Error('no') } })
  await assert.rejects(confirmAction)
  // 失败时模态保留(用户可重试或取消),busy 一定复位
  assert.notEqual(confirmModal.value, null)
  assert.equal(busy.confirm, false)
  confirmModal.value = null
})

test('loadDocuments prunes review/pr selections down to documents that still exist', async () => {
  const { documents, reviewDocs, prDocs, loadDocuments } = useKnowledge()
  reviewDocs.value = new Set([1, 2, 3])
  prDocs.value = new Set([2, 9])
  stubJson('/knowledge/documents', { items: [{ documentId: 2 }, { documentId: 3 }], page: 0, size: 20, totalElements: 2, totalPages: 1 })
  await loadDocuments()
  assert.deepEqual([...reviewDocs.value], [2, 3])
  assert.deepEqual([...prDocs.value], [2])
  assert.equal(documents.value.length, 2)
})

test('review polling stops itself and hands the newest report to the completion handler', async ctx => {
  ctx.mock.timers.enable({ apis: ['setInterval'] })
  const { tasks, pollingActive, loadReviews, maybeStartPolling, setCompletionHandler, stopPolling } = useReviews()

  let phase = 'running'
  stubFn('/reviews/tasks', () => ({ items: [{ taskId: 1, status: phase === 'running' ? 'RUNNING' : 'SUCCESS' }], page: 0, size: 20, totalElements: 1, totalPages: 1 }))
  stubFn('/reviews/reports', () => ({ items: phase === 'running' ? [] : [{ reportId: 42, taskId: 1 }], page: 0, size: 20, totalElements: 1, totalPages: 1 }))

  const opened = []
  setCompletionHandler(async id => opened.push(id))

  await loadReviews()
  assert.equal(tasks.value[0].status, 'RUNNING')
  maybeStartPolling()
  assert.equal(pollingActive.value, true)

  phase = 'done'
  ctx.mock.timers.tick(2500)
  // interval 回调是异步的:让微任务与桩 fetch 全部落定
  for (let i = 0; i < 20; i++) await Promise.resolve()

  assert.deepEqual(opened, [42])
  assert.equal(pollingActive.value, false)
  assert.equal(toast.text, '审查已完成')
  stopPolling()
  setCompletionHandler(null)
})

test('agent workspace opens one SSE per run and reset() tears everything down', async () => {
  const { agentRuns, agentRunId, agentPolling, startAgentPolling, reset } = useAgentWorkspace()
  agentRunId.value = 11
  agentRuns.value = [{ id: 11, status: 'PLANNING', headSha: 'abc' }]

  const before = FakeEventSource.instances.length
  startAgentPolling()
  assert.equal(FakeEventSource.instances.length, before + 1)
  assert.equal(agentPolling.value, true)
  const es = FakeEventSource.instances.at(-1)
  assert.match(es.url, /\/agent-runs\/11\/events$/)
  assert.ok(es.listeners['agent-run'] && es.listeners['agent-step'], 'named SSE events are subscribed')

  // 同一 run 再次 start 不应重复开连接
  startAgentPolling()
  assert.equal(FakeEventSource.instances.length, before + 1)

  reset()
  assert.equal(es.closed, true)
  assert.equal(agentPolling.value, false)
  assert.deepEqual(agentRuns.value, [])
  assert.equal(agentRunId.value, null)
})

test('diffLines derives real old/new gutter numbers from hunk headers', async () => {
  const { diffLines } = await import('../src/utils/labels.js')
  const diff = ['@@ -3,3 +3,4 @@', ' ctx', '-removed', '+added-1', '+added-2', ' ctx2'].join('\n')
  const rows = diffLines(diff)
  assert.deepEqual(rows.map(r => [r.cls, r.oldNo, r.newNo]), [
    ['hunk', '', ''],
    ['', '3', '3'],
    ['del', '4', ''],
    ['add', '', '4'],
    ['add', '', '5'],
    ['', '5', '6'],
  ])
})

test('compareReports buckets issues by file+title and flags knowledge signals', async () => {
  const { compareReports, hasKnowledgeSignal } = await import('../src/utils/compareReports.js')
  const withReport = { issues: [
    { issueId: 1, filePath: 'a.java', title: '批量发货未校验支付状态', description: '这与 BUG-001 是同类问题', severity: 'HIGH' },
    { issueId: 2, filePath: 'b.java', title: 'SQL 拼接', description: '注入', severity: 'HIGH' },
    { issueId: 3, filePath: 'c.java', title: '费率与文档不符', description: '根据 settlement-rules.md 第 3 节', severity: 'MEDIUM' },
  ] }
  const withoutReport = { issues: [
    { issueId: 9, filePath: 'b.java', title: 'SQL拼接', description: '注入', severity: 'HIGH' },
    { issueId: 10, filePath: 'd.java', title: '幻觉问题', description: 'x', severity: 'LOW' },
  ] }
  const r = compareReports(withReport, withoutReport, ['settlement-rules.md'])
  // 标题归一化让 "SQL 拼接" 与 "SQL拼接" 对上
  assert.equal(r.both.length, 1)
  assert.deepEqual(r.onlyWith.map(i => i.issueId), [1, 3])
  assert.deepEqual(r.onlyWithout.map(i => i.issueId), [10])
  assert.equal(r.summary.extraFromKnowledge, 2)
  assert.equal(r.summary.knowledgeSignals, 2) // BUG-001 事故编号 + 文档名引用
  assert.equal(hasKnowledgeSignal({ description: '普通建议' }, ['x.md']), false)
})

test('switching runs closes the previous SSE connection', async () => {
  const { agentRunId, startAgentPolling, reset } = useAgentWorkspace()
  agentRunId.value = 21
  startAgentPolling()
  const first = FakeEventSource.instances.at(-1)
  agentRunId.value = 22
  startAgentPolling()
  const second = FakeEventSource.instances.at(-1)
  assert.notEqual(first, second)
  assert.equal(first.closed, true)
  assert.match(second.url, /\/agent-runs\/22\/events$/)
  reset()
  assert.equal(second.closed, true)
})


test('P7 project loaders scope requests and ignore stale responses after a project switch', async () => {
  const { loadAiLogs, aiLogs } = useAiLogs()
  const { loadWorkbench, workbench } = useWorkbench()
  const { loadMetrics, metrics } = useDevelopmentMetrics()
  const oldAi = deferred()
  const oldWorkbench = deferred()
  const oldMetrics = deferred()

  stubFn('/ai/logs?projectId=7&taskId=99', () => oldAi.promise)
  stubFn('/projects/7/workbench', () => oldWorkbench.promise)
  stubFn('/projects/7/metrics', () => oldMetrics.promise)
  const oldAiRequest = loadAiLogs(99)
  const oldWorkbenchRequest = loadWorkbench()
  const oldMetricsRequest = loadMetrics('30d')

  activeProject.value = { projectId: 8, name: 'next', defaultBranch: 'main' }
  resetAiLogs()
  resetWorkbench()
  resetMetrics()
  stubJson('/ai/logs?projectId=8&taskId=99', { items: [{ id: 8 }], page: 0, totalPages: 1 })
  stubJson('/projects/8/workbench', { requirements: [{ requirementId: 8 }] })
  stubJson('/projects/8/metrics', { window: '7d' })

  await Promise.all([loadAiLogs(99), loadWorkbench(), loadMetrics('7d')])
  oldAi.resolve({ items: [{ id: 7 }], page: 0, totalPages: 1 })
  oldWorkbench.resolve({ requirements: [{ requirementId: 7 }] })
  oldMetrics.resolve({ window: '30d' })
  await Promise.all([oldAiRequest, oldWorkbenchRequest, oldMetricsRequest])

  assert.deepEqual(aiLogs.value, [{ id: 8 }])
  assert.deepEqual(workbench.value, { requirements: [{ requirementId: 8 }] })
  assert.deepEqual(metrics.value, { window: '7d' })
})
