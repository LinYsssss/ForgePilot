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
const { reviewDocs } = useKnowledge()
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
        if (reports.value[0] && completionHandler) await completionHandler(reports.value[0].reportId)
        toastMsg('审查已完成', 'success')
      }
    }, 2500)
  }
}

function stopPolling() { if (pollTimer) { clearInterval(pollTimer); pollTimer = null } pollingActive.value = false }

function reset() {
  tasks.value = []; reports.value = []; activeTask.value = null; reportDetail.value = null; mqLogs.value = []
}

export function useReviews() {
  return {
    reviewForm: form, tasks, reports, activeTask, reportDetail, mqLogs, pollingActive,
    highRiskCount, sortedIssues,
    createReview, loadReviews, selectTask, loadReport, loadMqLogs, cancelTask,
    askDeleteTask, exportReport, askDeleteReport,
    setCompletionHandler, maybeStartPolling, stopPolling, reset,
  }
}
