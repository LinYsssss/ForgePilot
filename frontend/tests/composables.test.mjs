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
        json: async () => ({ code: 0, data: fn() }),
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

const { activeProject } = useSession()
const { toast } = useToast()
const { busy, run } = useBusy()

beforeEach(() => {
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
