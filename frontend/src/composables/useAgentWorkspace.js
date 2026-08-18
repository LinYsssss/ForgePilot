import { computed, nextTick, ref, watch } from 'vue'
import { API_BASE, api } from '../api/client.js'
import { unwrapPage } from '../api/page.js'
import { nav } from '../nav.js'
import { useBusy } from './useBusy.js'
import { useConfirm } from './useConfirm.js'
import { useSession } from './useSession.js'
import { useToast } from './useToast.js'

// Agent 工作台(单例):Run 列表/筛选、Timeline/Finding/Patch 装载、
// SSE 实时推送 + 轮询兜底(15s 退避)、取消/重试,以及证据锚点定位。
// 单例位于组件外,页面活跃判断以当前路由是否属 AGENT_WORKSPACE_PAGES
// (旧 /agent 与墨境 /ink)为准。
const { activeProject } = useSession()
const { busy } = useBusy()
const { ask } = useConfirm()
const { toastMsg } = useToast()

const agentRuns = ref([])
const agentRunId = ref(null)
const agentRunFilter = ref('ALL')
const agentHeadSha = ref('')
const agentTimeline = ref([])
const agentFindings = ref([])
const agentPatch = ref(null)
const agentRunDetail = ref(null)
const agentPolling = ref(false)

let agentPollTimer = null
let agentEventSource = null
let agentEventRunId = null   // 配对追踪当前 SSE 连接对应的 run(不往 EventSource 实例上挂属性)
let agentEventAt = 0         // 最近一次收到 SSE 事件的时间戳,用于让轮询退避
let agentEventDebounce = null

const ACTIVE_STATUSES = ['RECEIVED', 'PREPARING_REPOSITORY', 'ANALYZING_CHANGE', 'RETRIEVING_CONTEXT', 'PLANNING', 'EXECUTING_TOOLS', 'VERIFYING_FINDINGS', 'GENERATING_PATCH', 'VALIDATING_PATCH', 'PUBLISHING_RESULT']

const filteredAgentRuns = computed(() => agentRuns.value.filter(runItem => {
  if (agentRunFilter.value === 'ALL') return true
  if (agentRunFilter.value === 'ACTIVE') return ACTIVE_STATUSES.includes(runItem.status)
  if (agentRunFilter.value === 'WAITING') return ['WAITING_APPROVAL', 'WAITING_EXTERNAL'].includes(runItem.status)
  if (agentRunFilter.value === 'FAILED') return ['FAILED', 'TIMED_OUT', 'DEAD'].includes(runItem.status)
  return ['SUCCEEDED', 'COMPLETED', 'CANCELED'].includes(runItem.status)
}))

const agentRunCounts = computed(() => ({
  active: agentRuns.value.filter(item => ACTIVE_STATUSES.includes(item.status)).length,
  waiting: agentRuns.value.filter(item => ['WAITING_APPROVAL', 'WAITING_EXTERNAL'].includes(item.status)).length,
  failed: agentRuns.value.filter(item => ['FAILED', 'TIMED_OUT', 'DEAD'].includes(item.status)).length,
  done: agentRuns.value.filter(item => ['SUCCEEDED', 'COMPLETED', 'CANCELED'].includes(item.status)).length,
}))

// 承载本工作台的页面:旧 /agent 视图与墨境 /ink 纸面共用同一单例,
// SSE/轮询触发的刷新在这两个路由上都要生效(语义不变:仅"页面活跃"判定)。
const AGENT_WORKSPACE_PAGES = ['agent']
function onAgentPage() { return AGENT_WORKSPACE_PAGES.includes(nav.name()) }

// 证据定位:外链 /agent?evidence=path:line 进入时,内容可能尚未渲染,
// 装载完成处会补调一次;query 变化(点击 citation)时也触发。
// 优先定位 Patch diff 里的具体行(file+line),否则回退到 Finding 证据条目。
function focusEvidenceAnchor() {
  const location = nav.query().evidence
  if (typeof location !== 'string' || !location) return
  const separator = location.lastIndexOf(':')
  const path = separator > 0 ? location.slice(0, separator) : location
  const line = separator > 0 ? location.slice(separator + 1) : ''
  const diffRow = line
    ? document.querySelector(`[data-diff-file="${CSS.escape(path)}"][data-diff-line="${CSS.escape(line)}"]`)
    : null
  const target = diffRow || document.querySelector(`[data-evidence-path="${CSS.escape(path)}"]`)
  if (!target) return
  target.scrollIntoView({ behavior: 'smooth', block: 'center' })
  target.classList.add('evidence-focus')
  setTimeout(() => target.classList.remove('evidence-focus'), 1800)
}
watch(() => nav.query().evidence, () => nextTick(focusEvidenceAnchor))

async function loadAgentWorkspace() {
  if (!activeProject.value || !agentRunId.value) return
  busy.agent = true
  try {
    const [timeline, patches, findings] = await Promise.all([
      api(`/agent-runs/${agentRunId.value}/timeline`),
      api(`/projects/${activeProject.value.projectId}/agent-runs/${agentRunId.value}/patches`),
      api(`/projects/${activeProject.value.projectId}/agent-runs/${agentRunId.value}/findings?size=100`).then(unwrapPage).catch(() => []),
    ])
    agentTimeline.value = timeline.steps || []
    agentRunDetail.value = timeline.run || null
    agentPatch.value = patches.length ? { ...patches[patches.length - 1], downloadUrl: `data:text/x-diff;charset=utf-8,${encodeURIComponent(patches[patches.length - 1].patchContent || '')}` } : null
    agentFindings.value = findings || []
    if (!agentHeadSha.value) agentHeadSha.value = timeline.run?.headSha || agentPatch.value?.headSha || ''
    if (timeline.run?.terminal) stopAgentPolling()
    else startAgentPolling()
    // 工作台内容就位后补一次证据定位(外链进入时 DOM 此前不存在)。
    await nextTick()
    focusEvidenceAnchor()
  } finally { busy.agent = false }
}

async function loadAgentRuns() {
  if (!activeProject.value) return
  busy.agentRuns = true
  try {
    agentRuns.value = unwrapPage(await api(`/agent-runs/project/${activeProject.value.projectId}?size=100`))
    if (!agentRunId.value && agentRuns.value.length) {
      agentRunId.value = agentRuns.value[0].id
      agentHeadSha.value = agentRuns.value[0].headSha || ''
    }
  } finally { busy.agentRuns = false }
}

async function selectAgentRun() {
  const selected = agentRuns.value.find(item => item.id === agentRunId.value)
  if (selected) agentHeadSha.value = selected.headSha || ''
  if (agentRunId.value) await loadAgentWorkspace()
}

function startAgentPolling() {
  if (!agentRunId.value) return
  agentPolling.value = true
  openAgentEvents() // 优先 SSE 实时推送(每次同步到当前 run)
  if (agentPollTimer) return
  agentPollTimer = setInterval(async () => {
    // 轮询是兜底:SSE 近期推过就跳过这一轮,避免两条通道重复拉取。
    if (!onAgentPage() || busy.agent) return
    if (Date.now() - agentEventAt < 15000) return
    try { await loadAgentWorkspace() } catch { stopAgentPolling() }
  }, 8000)
}

function openAgentEvents() {
  if (!agentRunId.value) return
  if (agentEventSource && agentEventRunId === agentRunId.value) return
  if (agentEventSource) { agentEventSource.close(); agentEventSource = null }
  try {
    // 后端用具名事件推送(agent-run / agent-step),onmessage 只收无名事件,必须显式监听。
    const es = new EventSource(`${API_BASE}/agent-runs/${agentRunId.value}/events`)
    const onEvent = () => {
      agentEventAt = Date.now()
      if (!onAgentPage() || busy.agent) return
      clearTimeout(agentEventDebounce)
      agentEventDebounce = setTimeout(() => loadAgentWorkspace().catch(() => {}), 300)
    }
    es.addEventListener('agent-run', onEvent)
    es.addEventListener('agent-step', onEvent)
    es.onerror = () => { agentEventAt = 0 } // 断流则让轮询立刻接管
    agentEventSource = es
    agentEventRunId = agentRunId.value
  } catch { agentEventSource = null; agentEventRunId = null }
}

function stopAgentPolling() {
  if (agentPollTimer) clearInterval(agentPollTimer)
  agentPollTimer = null
  clearTimeout(agentEventDebounce)
  if (agentEventSource) { agentEventSource.close(); agentEventSource = null }
  agentEventRunId = null
  agentEventAt = 0
  agentPolling.value = false
}

async function onPatchDecided() { toastMsg('Patch 审批决定已记录', 'success'); await loadAgentWorkspace() }
function onPatchError(error) { toastMsg(error?.message || 'Patch 审批失败', 'error') }

async function cancelAgentRun() {
  if (!agentRunId.value) return
  busy.agentControl = true
  try { await api(`/agent-runs/${agentRunId.value}/cancel`, { method: 'POST' }); await loadAgentWorkspace(); toastMsg('Agent Run 已取消', 'success') }
  finally { busy.agentControl = false }
}

function askCancelAgentRun() {
  ask({
    title: `取消 Agent Run #${agentRunId.value}？`,
    body: '当前步骤将停止并进入 CANCELED。Timeline、模型调用、工具调用、Finding、Patch 和发布审计记录都会保留。',
    confirmLabel: '确认取消',
    onConfirm: cancelAgentRun,
  })
}

async function retryAgentRun() {
  if (!agentRunId.value) return
  busy.agentControl = true
  try { await api(`/agent-runs/${agentRunId.value}/retry`, { method: 'POST' }); await loadAgentWorkspace(); toastMsg('失败步骤已重新入队', 'success') }
  finally { busy.agentControl = false }
}

function askRetryAgentRun() {
  ask({
    title: `重试 Agent Run #${agentRunId.value} 的失败步骤？`,
    body: '系统会复用当前 Agent Run 和幂等边界重新入队失败步骤，可能再次调用模型、工具或 Sandbox 并产生额外耗时与成本。',
    confirmLabel: '确认重试',
    onConfirm: retryAgentRun,
  })
}

// 跟随项目/会话生命周期:关 SSE、停轮询、清跨项目状态,退出登录后不留半开连接。
function reset() {
  stopAgentPolling()
  agentRuns.value = []; agentRunId.value = null; agentHeadSha.value = ''; agentRunFilter.value = 'ALL'
  agentTimeline.value = []; agentFindings.value = []; agentPatch.value = null; agentRunDetail.value = null
}

export function useAgentWorkspace() {
  return {
    agentRuns, agentRunId, agentRunFilter, agentHeadSha, agentTimeline, agentFindings, agentPatch, agentRunDetail, agentPolling,
    filteredAgentRuns, agentRunCounts,
    loadAgentWorkspace, loadAgentRuns, selectAgentRun, startAgentPolling, stopAgentPolling,
    onPatchDecided, onPatchError, askCancelAgentRun, askRetryAgentRun, focusEvidenceAnchor, reset,
  }
}
