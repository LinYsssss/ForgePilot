import { computed, reactive, ref } from 'vue'
import { api, apiDownload } from '../api/client.js'
import { unwrapPage } from '../api/page.js'
import { useBusy } from './useBusy.js'
import { useConfirm } from './useConfirm.js'
import { useKnowledge } from './useKnowledge.js'
import { useRepository } from './useRepository.js'
import { useSession } from './useSession.js'
import { useToast } from './useToast.js'

// 审查任务与报告(单例):创建/列表/详情/MQ 日志/导出/删除 + 运行中任务的完成轮询。
// 轮询完成后的"打开最新报告并跳转"由 useWorkspace 通过 setCompletionHandler 注入,
// 保持依赖方向 workspace → reviews,不反向引用。
const { activeProject } = useSession()
const { busy } = useBusy()
const { ask } = useConfirm()
const { toastMsg } = useToast()
const { reviewDocs, documents } = useKnowledge()
const { selectedCommit } = useRepository()

const form = reactive({ commitId: '', baseCommitId: '', branch: '' })

const tasks = ref([])
const reports = ref([])
const activeTask = ref(null)
const reportDetail = ref(null)
const mqLogs = ref([])
const pollingActive = ref(false)
let pollTimer = null
let completionHandler = null

const highRiskCount = computed(() => reports.value.filter(r => r.overallRisk === 'HIGH').length)
const SEV_ORDER = { HIGH: 0, MEDIUM: 1, LOW: 2, NONE: 3 }
const sortedIssues = computed(() => {
  if (!reportDetail.value) return []
  return [...reportDetail.value.issues].sort((a, b) => (SEV_ORDER[a.severity] ?? 9) - (SEV_ORDER[b.severity] ?? 9))
})

async function createReview() {
  busy.review = true
  try {
    const commitId = form.commitId || selectedCommit.value?.commitId || ''
    const documentIds = Array.from(reviewDocs.value)
    await api(`/projects/${activeProject.value.projectId}/reviews/tasks`, { method: 'POST', body: JSON.stringify({ ...form, commitId, documentIds }) })
    await loadReviews()
    activeTask.value = tasks.value[0] || null
    if (reports.value[0]) await loadReport(reports.value[0].reportId)
    toastMsg('审查任务已创建', 'success')
    maybeStartPolling()
  } finally { busy.review = false }
}

async function loadReviews() {
  busy.reviews = true
  try {
    tasks.value = unwrapPage(await api(`/projects/${activeProject.value.projectId}/reviews/tasks?size=100`))
    reports.value = unwrapPage(await api(`/projects/${activeProject.value.projectId}/reviews/reports?size=100`))
    if (activeTask.value) activeTask.value = tasks.value.find(t => t.taskId === activeTask.value.taskId) || null
  } finally { busy.reviews = false }
}

/* ---------- 对比审查(带/不带知识库) ---------- */
// comparePair: { commitId, withTaskId, withoutTaskId, withReport?, withoutReport? }
const comparePair = ref(null)

// 同一提交连发两次创建:全部 INDEXED 文档 vs 空集。后端幂等键含文档集指纹,两任务可共存。
async function createCompareReview() {
  const commitId = form.commitId || selectedCommit.value?.commitId || ''
  const indexedDocIds = documents.value.filter(d => d.status === 'INDEXED').map(d => d.documentId)
  if (!indexedDocIds.length) return toastMsg('知识库没有已索引(INDEXED)的文档,无法对比', 'error')
  busy.compare = true
  try {
    const base = { ...form, commitId }
    const withDocs = await api(`/projects/${activeProject.value.projectId}/reviews/tasks`,
      { method: 'POST', body: JSON.stringify({ ...base, documentIds: indexedDocIds }) })
    const withoutDocs = await api(`/projects/${activeProject.value.projectId}/reviews/tasks`,
      { method: 'POST', body: JSON.stringify({ ...base, documentIds: [] }) })
    comparePair.value = { commitId: withDocs.commitId, withTaskId: withDocs.taskId, withoutTaskId: withoutDocs.taskId }
    await loadReviews()
    toastMsg('对比审查已创建：知识库全选 + 不带知识库 两个任务', 'success')
    maybeStartPolling()
    await loadComparePairReports()
  } finally { busy.compare = false }
}

// 两个任务的报告都出来后装载详情;报告按 taskId 关联。
async function loadComparePairReports() {
  if (!comparePair.value) return
  const pairReport = taskId => reports.value.find(r => r.taskId === taskId)
  const withMeta = pairReport(comparePair.value.withTaskId)
  const withoutMeta = pairReport(comparePair.value.withoutTaskId)
  if (!withMeta || !withoutMeta) return
  const [withReport, withoutReport] = await Promise.all([
    api(`/projects/${activeProject.value.projectId}/reviews/reports/${withMeta.reportId}`),
    api(`/projects/${activeProject.value.projectId}/reviews/reports/${withoutMeta.reportId}`),
  ])
  comparePair.value = { ...comparePair.value, withReport, withoutReport }
}

function clearCompare() { comparePair.value = null }

function selectTask(t) { activeTask.value = t; mqLogs.value = [] }

async function loadReport(reportId) {
  reportDetail.value = await api(`/projects/${activeProject.value.projectId}/reviews/reports/${reportId}`)
}

async function loadMqLogs(taskId) { mqLogs.value = unwrapPage(await api(`/mq/logs?taskId=${taskId}&size=100`)) }

async function cancelTask(t) {
  await api(`/projects/${activeProject.value.projectId}/reviews/tasks/${t.taskId}/cancel`, { method: 'POST' })
  await loadReviews()
  toastMsg('任务已停止', 'success')
}

function askDeleteTask(t) {
  ask({
    title: `删除审查任务 #${t.taskId}？`,
    body: '将一并删除该任务生成的报告、问题、反馈以及关联的 AI / MQ 日志，操作不可恢复。',
    onConfirm: async () => {
      await api(`/projects/${activeProject.value.projectId}/reviews/tasks/${t.taskId}`, { method: 'DELETE' })
      if (activeTask.value && activeTask.value.taskId === t.taskId) activeTask.value = null
      const reportForTask = reports.value.find(r => r.taskId === t.taskId)
      if (reportDetail.value && reportForTask && reportDetail.value.reportId === reportForTask.reportId) reportDetail.value = null
      await loadReviews()
      toastMsg('任务已删除', 'success')
    },
  })
}

async function exportReport(format) {
  if (!reportDetail.value) return
  const { blob, filename } = await apiDownload(
    `/projects/${activeProject.value.projectId}/reviews/reports/${reportDetail.value.reportId}/export?format=${format}`)
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  link.remove()
  URL.revokeObjectURL(url)
  toastMsg('报告已导出：' + filename, 'success')
}

function askDeleteReport(r) {
  ask({
    title: `删除审查报告 #${r.reportId}？`,
    body: '将删除该报告及其下的所有问题与反馈，对应的审查任务会保留，操作不可恢复。',
    onConfirm: async () => {
      await api(`/projects/${activeProject.value.projectId}/reviews/reports/${r.reportId}`, { method: 'DELETE' })
      if (reportDetail.value && reportDetail.value.reportId === r.reportId) reportDetail.value = null
      await loadReviews()
      toastMsg('报告已删除', 'success')
    },
  })
}

function setCompletionHandler(fn) { completionHandler = fn }

function maybeStartPolling() {
  const running = tasks.value.some(t => t.status === 'PENDING' || t.status === 'RUNNING')
  if (running && !pollTimer) {
    pollingActive.value = true
    pollTimer = setInterval(async () => {
      try { await loadReviews() } catch { /* ignore */ }
      if (!tasks.value.some(t => t.status === 'PENDING' || t.status === 'RUNNING')) {
        stopPolling()
        await loadComparePairReports().catch(() => {})
        if (reports.value[0] && completionHandler) await completionHandler(reports.value[0].reportId)
        toastMsg('审查已完成', 'success')
      }
    }, 2500)
  }
}

function stopPolling() { if (pollTimer) { clearInterval(pollTimer); pollTimer = null } pollingActive.value = false }

function reset() {
  tasks.value = []; reports.value = []; activeTask.value = null; reportDetail.value = null; mqLogs.value = []
  comparePair.value = null
}

export function useReviews() {
  return {
    reviewForm: form, tasks, reports, activeTask, reportDetail, mqLogs, pollingActive,
    highRiskCount, sortedIssues,
    createReview, loadReviews, selectTask, loadReport, loadMqLogs, cancelTask,
    askDeleteTask, exportReport, askDeleteReport,
    comparePair, createCompareReview, loadComparePairReports, clearCompare,
    setCompletionHandler, maybeStartPolling, stopPolling, reset,
  }
}
