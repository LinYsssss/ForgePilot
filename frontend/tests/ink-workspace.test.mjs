import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { test } from 'node:test'
import {
  AGENT_STAGE_META,
  approvalModel,
  brushModel,
  classifyLoadError,
  completedStepCount,
  confidencePct,
  defaultSelectedId,
  describeActionError,
  evidenceAnchor,
  filterFindings,
  findingAnchor,
  findingCounts,
  findingFilterChips,
  findingLocation,
  findingStatusText,
  gateLabel,
  isRejected,
  railNotes,
  runPresentation,
  runStatusLabel,
  severityText,
  severityTone,
  sortFindings,
} from '../src/features/workspace/workspaceModel.js'
import { splitRows, unifiedRows } from '../src/features/workspace/diffModel.js'

// ---------------------------------------------------------------------------
// 墨境纵向切片(重构步骤 6)测试:
//   1. 工作台展示模型 —— 真实 Timeline/Finding/Patch → 呈现的派生纯逻辑;
//   2. Diff 双视图行模型 —— unified 锚点保真 + split 配对;
//   3. 数据接线的源码锚点 —— /ink 页面纳入 SSE/轮询活跃判定、
//      citation 定位属性在墨境组件中逐字保留。
// ---------------------------------------------------------------------------

/* ---------- 1. Brush progress from the real timeline ---------- */

test('brushModel walks settled steps and parks on the first unsettled one', () => {
  const timeline = [
    { id: 1, sequenceNo: 1, stepType: 'PREPARING_REPOSITORY', status: 'SUCCEEDED', finishedAt: '2026-08-13T02:00:00Z' },
    { id: 2, sequenceNo: 2, stepType: 'ANALYZING_CHANGE', status: 'SKIPPED' },
    { id: 3, sequenceNo: 3, stepType: 'PLANNING', status: 'RUNNING' },
    { id: 4, sequenceNo: 4, stepType: 'EXECUTING_TOOLS', status: 'PENDING' },
  ]
  const model = brushModel(timeline)
  assert.equal(model.current, 2) // SUCCEEDED 与 SKIPPED 均视为已了结
  assert.equal(model.failed, false)
  assert.deepEqual(model.steps.map((s) => s.label), ['备库', '析变', '规划', '工具'])
  assert.equal(model.steps[0].glyph, '备')
  assert.notEqual(model.steps[0].meta, '-') // 完成步骤带时间(本地格式,不断言字面)
  assert.equal(model.steps[1].meta, '已跳过')
  assert.equal(model.steps[2].meta, '进行中')
  assert.equal(model.steps[3].meta, '待执行')
})

test('brushModel treats a failed step as the current stop and flags failure', () => {
  const model = brushModel([
    { sequenceNo: 1, stepType: 'PREPARING_REPOSITORY', status: 'SUCCEEDED' },
    { sequenceNo: 2, stepType: 'ANALYZING_CHANGE', status: 'FAILED' },
    { sequenceNo: 3, stepType: 'PLANNING', status: 'PENDING' },
  ])
  assert.equal(model.current, 1)
  assert.equal(model.failed, true)
  assert.equal(model.steps[1].meta, '失败')
  assert.equal(model.steps[0].meta, '已完成') // 无 finishedAt 时退化为文本
})

test('brushModel fills the stroke when everything settled and survives odd input', () => {
  const done = brushModel([
    { sequenceNo: 1, stepType: 'PUBLISHING_RESULT', status: 'SUCCEEDED' },
  ])
  assert.equal(done.current, 1)

  assert.deepEqual(brushModel(null), { steps: [], current: 0, failed: false })
  const unknown = brushModel([{ sequenceNo: 1, stepType: 'SOMETHING_NEW', status: 'RUNNING' }])
  assert.equal(unknown.steps[0].label, 'SOMETHING_NEW') // 未知阶段不丢文本
  assert.equal(unknown.steps[0].glyph, '步')
  // 管线阶段元数据齐全:每个阶段都有印记字 + 短标签
  for (const meta of Object.values(AGENT_STAGE_META)) {
    assert.ok(meta.label && meta.glyph)
  }
})

test('completedStepCount keeps the legacy summary semantics', () => {
  const timeline = [
    { status: 'SUCCEEDED' }, { status: 'COMPLETED' }, { status: 'SUCCESS' },
    { status: 'SKIPPED' }, { status: 'RUNNING' }, { status: 'FAILED' },
  ]
  assert.equal(completedStepCount(timeline), 3) // SKIPPED 不算完成(旧口径)
  assert.equal(completedStepCount(undefined), 0)
})

/* ---------- 2. Run status presentation ---------- */

test('run presentation groups statuses without touching filter semantics', () => {
  assert.equal(runStatusLabel('EXECUTING_TOOLS'), '执行工具')
  assert.equal(runStatusLabel('UNKNOWN_STATE'), 'UNKNOWN_STATE')
  assert.deepEqual(runPresentation('RECEIVED'), { tone: 'running', glyph: '行' })
  assert.deepEqual(runPresentation('WAITING_APPROVAL'), { tone: 'info', glyph: '待' })
  assert.deepEqual(runPresentation('FAILED'), { tone: 'high', glyph: '败' })
  assert.deepEqual(runPresentation('COMPLETED'), { tone: 'success', glyph: '成' })
  assert.deepEqual(runPresentation('CANCELED'), { tone: 'neutral', glyph: '止' })
  // 朱砂语气不参与 Run 状态编码(合同 §5:critical 独占朱砂)
  for (const status of ['RECEIVED', 'WAITING_APPROVAL', 'FAILED', 'COMPLETED', 'CANCELED', 'TIMED_OUT', 'DEAD']) {
    assert.notEqual(runPresentation(status).tone, 'critical')
  }
})

/* ---------- 3. Finding derivations ---------- */

const FINDINGS = [
  { id: 1, severity: 'HIGH', title: 'H1', blocking: true, evidence: [{ filePath: 'a.java', lineStart: 12 }] },
  { id: 2, severity: 'CRITICAL', title: 'C1', blocking: true, filePath: 'b.java', lineStart: 9, evidence: [] },
  { id: 3, severity: 'LOW', title: 'L1', blocking: false, evidence: [] },
  { id: 4, severity: 'CRITICAL', title: 'C2-rejected', status: 'rejected', rejectionReason: 'no proof', blocking: true, evidence: [] },
]

test('findingCounts excludes rejected findings from active/severity/blocking', () => {
  const counts = findingCounts(FINDINGS)
  assert.equal(counts.total, 4)
  assert.equal(counts.active, 3)
  assert.equal(counts.rejected, 1)
  assert.equal(counts.blocking, 2)
  assert.deepEqual(counts.bySeverity, { CRITICAL: 1, HIGH: 1, MEDIUM: 0, LOW: 1, INFO: 0 })
})

test('sortFindings ranks by severity with rejected sinking to the bottom', () => {
  assert.deepEqual(sortFindings(FINDINGS).map((f) => f.id), [2, 1, 3, 4])
})

test('filterFindings and chips follow the ALL/severity/REJECTED contract', () => {
  assert.deepEqual(filterFindings(FINDINGS, 'ALL').map((f) => f.id), [2, 1, 3, 4])
  assert.deepEqual(filterFindings(FINDINGS, 'CRITICAL').map((f) => f.id), [2]) // 否决的 C2 不算 Critical
  assert.deepEqual(filterFindings(FINDINGS, 'REJECTED').map((f) => f.id), [4])
  assert.deepEqual(findingFilterChips(FINDINGS), [
    { key: 'ALL', label: '全部', count: 4 },
    { key: 'CRITICAL', label: 'Critical', count: 1 },
    { key: 'HIGH', label: 'High', count: 1 },
    { key: 'LOW', label: 'Low', count: 1 },
    { key: 'REJECTED', label: '已否决', count: 1 },
  ])
})

test('severity tones keep cinnabar exclusive to CRITICAL', () => {
  assert.equal(severityTone('CRITICAL'), 'critical')
  for (const severity of ['HIGH', 'MEDIUM', 'LOW', 'INFO', 'WHATEVER', undefined]) {
    assert.notEqual(severityTone(severity), 'critical')
  }
  assert.equal(severityTone('BOGUS'), 'neutral')
  assert.equal(severityText('MEDIUM'), 'Medium')
  assert.equal(confidencePct(0.955), '96%')
  assert.equal(confidencePct(null), '-')
})

test('selection keeps the current finding and falls back to the top-ranked one', () => {
  assert.equal(defaultSelectedId(FINDINGS, 3), 3)
  assert.equal(defaultSelectedId(FINDINGS, 999), 2)
  assert.equal(defaultSelectedId([], 1), null)
})

test('anchors and location strings mirror the legacy citation format', () => {
  assert.equal(evidenceAnchor({ filePath: 'src/a.py', lineStart: 42 }), 'src/a.py:42')
  assert.equal(evidenceAnchor({ path: 'src/b.py' }), 'src/b.py:1') // 缺行号退回 1(旧 fileLink 语义)
  assert.equal(evidenceAnchor({}), '')
  assert.equal(findingAnchor(FINDINGS[0]), 'a.java:12')
  assert.equal(findingAnchor(FINDINGS[1]), 'b.java:9') // 无证据路径 → finding 自身位置
  assert.equal(findingAnchor({ id: 9 }), '')
  assert.equal(findingLocation(FINDINGS[1]), 'b.java:9')
  assert.equal(findingLocation({}), '未标注文件')
  assert.equal(findingStatusText(FINDINGS[0]), '可阻断')
  assert.equal(findingStatusText(FINDINGS[3]), '已否决')
  assert.equal(findingStatusText({ blocking: false }), '待复核')
  assert.equal(isRejected(FINDINGS[3]), true)
})

/* ---------- 4. Annotation rail derivation ---------- */

test('railNotes lists active findings, recent settled steps, then the case origin', () => {
  const notes = railNotes({
    findings: FINDINGS,
    steps: [
      { id: 11, sequenceNo: 1, stepType: 'PREPARING_REPOSITORY', status: 'SUCCEEDED', finishedAt: '2026-08-13T01:00:00Z' },
      { id: 12, sequenceNo: 2, stepType: 'ANALYZING_CHANGE', status: 'SUCCEEDED', finishedAt: '2026-08-13T01:05:00Z' },
      { id: 13, sequenceNo: 3, stepType: 'PLANNING', status: 'SUCCEEDED', finishedAt: '2026-08-13T01:09:00Z' },
      { id: 14, sequenceNo: 4, stepType: 'EXECUTING_TOOLS', status: 'RUNNING' },
    ],
    run: { id: 2048, headSha: '8af31c09deadbeef', createdAt: '2026-08-13T00:58:00Z' },
  })
  // 否决的 Finding 不上朱批栏;最近两个完成步骤(按 sequenceNo 倒序);案卷建立收尾
  assert.deepEqual(notes.map((n) => n.kind), ['finding', 'finding', 'finding', 'step', 'step', 'run'])
  assert.deepEqual(notes.slice(0, 3).map((n) => n.findingId), [2, 1, 3])
  assert.equal(notes[0].tone, 'critical')
  assert.equal(notes[0].anchor, 'b.java:9')
  assert.equal(notes[0].status, '可阻断')
  assert.deepEqual(notes.slice(3, 5).map((n) => n.title), ['规划已完成', '析变已完成'])
  assert.match(notes.at(-1).body, /8af31c09/)
  assert.deepEqual(railNotes(), [])
})

/* ---------- 5. Approval model (policy reused, not re-implemented) ---------- */

test('approvalModel defers to canApprovePatch and explains each blocked case', () => {
  const noPatch = approvalModel(null)
  assert.equal(noPatch.approvable, false)
  assert.match(noPatch.reason, /暂无候选 Patch/)
  assert.deepEqual(noPatch.gates.map((g) => g.label), ['未执行', '未执行', '未执行', '未执行'])

  const blocked = approvalModel({ applyStatus: 'FAILED', buildStatus: 'SUCCEEDED', testStatus: 'NOT_RUN', scanStatus: 'RUNNING', targetDisappeared: true, stale: false })
  assert.equal(blocked.approvable, false)
  assert.equal(blocked.reason, 'Patch 未通过 apply/目标消失校验或已过期，不能批准。') // 旧提示逐字保留
  assert.deepEqual(blocked.gates.map((g) => [g.key, g.pass, g.fail]), [
    ['Apply', false, true], ['Build', true, false], ['Test', false, false], ['Scan', false, false],
  ])

  const open = approvalModel({ applyStatus: 'SUCCEEDED', buildStatus: 'SUCCEEDED', testStatus: 'SUCCEEDED', scanStatus: 'SUCCEEDED', targetDisappeared: true, stale: false })
  assert.equal(open.approvable, true)
  assert.equal(open.reason, null)
  assert.equal(gateLabel('BOGUS_STATE'), 'BOGUS_STATE') // 未知门态不吞原值
})

/* ---------- 6. Structured error branching (no message sniffing) ---------- */

test('load/action errors branch on ApiError status fields only', () => {
  assert.equal(classifyLoadError({ status: 401 }), null) // 全局漏斗接管
  assert.equal(classifyLoadError({ status: 0 }).kind, 'offline')
  assert.equal(classifyLoadError({ status: 403 }).kind, 'permission')
  const generic = classifyLoadError({ status: 500, message: '后端炸了（trace t-1)' })
  assert.equal(generic.kind, 'error')
  assert.match(generic.message, /trace t-1/) // traceId 已并入 message,原样透传

  assert.equal(describeActionError({ status: 401 }), null)
  assert.equal(describeActionError({ status: 0 }).kind, 'offline')
  assert.equal(describeActionError({ status: 403 }).kind, 'permission')
  assert.equal(describeActionError({ status: 422, message: 'stale head' }).message, 'stale head')
})

/* ---------- 7. Diff view models ---------- */

const SAMPLE_DIFF = [
  'diff --git a/src/api/admin.py b/src/api/admin.py',
  'index 1111111..2222222 100644',
  '--- a/src/api/admin.py',
  '+++ b/src/api/admin.py',
  '@@ -137,5 +137,4 @@',
  ' def get_user_list():',
  '     user = get_current_user()',
  '-    if user.is_admin:',
  '-        return User.query.all()',
  '+    ensure_role(user, "admin")',
  '     return []',
].join('\n')

test('unifiedRows keeps hunk-derived numbers and tracks the owning file', () => {
  const rows = unifiedRows(SAMPLE_DIFF)
  assert.equal(rows[3].cls, 'meta')
  assert.equal(rows[3].file, '') // meta/hunk 行不带文件锚点(与旧 viewer 同构)
  assert.equal(rows[5].file, 'src/api/admin.py')
  assert.deepEqual([rows[5].oldNo, rows[5].newNo], ['137', '137'])
  assert.deepEqual([rows[7].cls, rows[7].oldNo, rows[7].newNo], ['del', '139', ''])
  assert.deepEqual([rows[9].cls, rows[9].newNo], ['add', '139'])
  assert.deepEqual(unifiedRows(''), [])
})

test('splitRows pairs deletion/addition runs and carries anchors on the new side', () => {
  const rows = splitRows(unifiedRows(SAMPLE_DIFF))
  assert.deepEqual(rows.slice(0, 4).map((r) => r.type), ['meta', 'meta', 'meta', 'meta'])
  assert.equal(rows[4].type, 'hunk')

  const context = rows[5]
  assert.equal(context.type, 'pair')
  assert.deepEqual([context.left.no, context.right.no], ['137', '137'])
  assert.equal(context.right.file, 'src/api/admin.py')

  const changed = rows[7] // 2 删 + 1 增:第一行成对,第二行左侧独存
  assert.deepEqual([changed.left.cls, changed.left.no], ['del', '139'])
  assert.deepEqual([changed.right.cls, changed.right.no], ['add', '139'])
  assert.equal(changed.right.file, 'src/api/admin.py')
  const leftover = rows[8]
  assert.deepEqual([leftover.left.cls, leftover.left.no], ['del', '140'])
  assert.equal(leftover.right, null)

  const tail = rows[9]
  assert.deepEqual([tail.left.no, tail.right.no], ['141', '140'])

  // 孤立新增行(前面没有删除段)也成为右侧单存的 pair
  const loneAdd = splitRows(unifiedRows(['@@ -1,1 +1,2 @@', ' ctx', '+new line'].join('\n')))
  const last = loneAdd.at(-1)
  assert.equal(last.left, null)
  assert.deepEqual([last.right.cls, last.right.no], ['add', '2'])
})

/* ---------- 8. Wiring anchors (source assertions) ---------- */

test('the ink page is a recognized agent-workspace host for SSE/poll refresh', async () => {
  const agent = await readFile(new URL('../src/composables/useAgentWorkspace.js', import.meta.url), 'utf8')
  assert.match(agent, /AGENT_WORKSPACE_PAGES = \['agent', 'inkAtelier'\]/)
})

test('ink evidence components keep the frozen citation anchors verbatim', async () => {
  const evidenceDiff = await readFile(new URL('../src/features/workspace/EvidenceDiff.vue', import.meta.url), 'utf8')
  assert.match(evidenceDiff, /data-diff-file/)
  assert.match(evidenceDiff, /data-diff-line/)
  assert.match(evidenceDiff, /data-evidence-path/)
  assert.match(evidenceDiff, /evidence-focus/) // focusEvidenceAnchor 的高亮钩子

  const page = await readFile(new URL('../src/pages/InkAtelierPage.vue', import.meta.url), 'utf8')
  assert.match(page, /focusEvidenceAnchor/)
  assert.match(page, /query: \{ evidence: anchor \}/) // 与旧 #/agent?evidence= 同构的定位 query

  // 数据接线纪律:工作台组件不得绕过 composable/api 模块直发请求
  for (const file of [
    '../src/pages/InkAtelierPage.vue',
    '../src/features/workspace/PaperWorkspace.vue',
    '../src/features/workspace/ReviewActionBar.vue',
    '../src/features/workspace/EvidenceDiff.vue',
    '../src/features/workspace/FindingLedger.vue',
    '../src/features/workspace/RunCaseList.vue',
    '../src/features/workspace/AnnotationRail.vue',
  ]) {
    const source = await readFile(new URL(file, import.meta.url), 'utf8')
    assert.doesNotMatch(source, /fetch\(/, `${file} 不得裸用 fetch`)
    assert.doesNotMatch(source, /localStorage|sessionStorage/, `${file} 不得触碰 Web Storage`)
  }
})

/* ---------- 9. Dual-host lifecycle behavior (poll/SSE gate) ---------- */

// 行为钉:onAgentPage 谓词扩展后,SSE/轮询刷新必须在旧 /agent 与
// /ink 两个宿主上都生效,且在其它路由上保持拒绝(谓词仍然守门)。
// 桩(document/fetch/EventSource/nav)按 quality-guidelines 规则在
// import 业务单例之前就位;本文件独立进程,不污染其它测试文件。
test('SSE/poll refresh gate admits both /agent and /ink and rejects other routes', async (ctx) => {
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
  const hits = []
  globalThis.fetch = async (url) => {
    const path = String(url)
    hits.push(path)
    let data = null
    if (path.includes('/timeline')) data = { steps: [], run: { id: 31, terminal: false } }
    else if (path.includes('/patches')) data = []
    else if (path.includes('/findings')) data = { items: [], page: 0, size: 100, totalElements: 0, totalPages: 0 }
    return { ok: true, status: 200, headers: { get: () => null }, json: async () => ({ code: 0, data }) }
  }
  class FakeEventSource {
    static instances = []
    constructor(url) { this.url = url; this.closed = false; this.listeners = {}; FakeEventSource.instances.push(this) }
    addEventListener(name, fn) { this.listeners[name] = fn }
    close() { this.closed = true }
  }
  globalThis.EventSource = FakeEventSource

  const { registerNav } = await import('../src/nav.js')
  let route = 'inkAtelier'
  registerNav({ name: () => route, query: () => ({}) })

  const { useSession } = await import('../src/composables/useSession.js')
  const { useAgentWorkspace } = await import('../src/composables/useAgentWorkspace.js')
  const { activeProject } = useSession()
  activeProject.value = { projectId: 7, name: 'demo' }
  const { agentRuns, agentRunId, startAgentPolling, reset } = useAgentWorkspace()
  agentRunId.value = 31
  agentRuns.value = [{ id: 31, status: 'PLANNING', headSha: 'abc' }]

  ctx.mock.timers.enable({ apis: ['setInterval', 'setTimeout'] })
  const drain = async () => { for (let i = 0; i < 20; i++) await Promise.resolve() }
  const workspaceLoads = () => hits.filter((path) => path.includes('/timeline')).length

  startAgentPolling()
  const es = FakeEventSource.instances.at(-1)
  assert.match(es.url, /\/agent-runs\/31\/events$/)

  ctx.mock.timers.tick(8000) // /ink 是活跃宿主:轮询兜底照常刷新
  await drain()
  assert.equal(workspaceLoads(), 1)

  route = 'agent' // 旧 /agent 行为零变化
  ctx.mock.timers.tick(8000)
  await drain()
  assert.equal(workspaceLoads(), 2)

  route = 'dashboard' // 谓词仍守门:其它路由不产生后台拉取
  ctx.mock.timers.tick(8000)
  await drain()
  assert.equal(workspaceLoads(), 2)

  route = 'inkAtelier' // SSE 推送在 /ink 上同样经 300ms 防抖触发装载
  es.listeners['agent-step']()
  ctx.mock.timers.tick(300)
  await drain()
  assert.equal(workspaceLoads(), 3)

  reset()
  assert.equal(es.closed, true)
  activeProject.value = null
})
