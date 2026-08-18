import { computed, reactive, ref } from 'vue'
import { api } from '../api/client.js'
import { fmtDate } from '../utils/format.js'
import { relativeDay } from '../utils/labels.js'
import { useSession } from './useSession.js'

// AI 调用日志(单例):项目/任务两个维度,按日期→任务分组展示;信封分页。
const { activeProject } = useSession()

const aiLogs = ref([])
const aiLogScope = ref('项目维度')
const selectedAiLog = ref(null)
const collapsedDates = reactive({})
const aiLogPage = ref(0)
const aiLogTotalPages = ref(1)
const aiLogsLoading = ref(false)
const aiLogsError = ref('')
let currentTaskId = null // 翻页时沿用当前维度
let requestGeneration = 0

const groupedAiLogs = computed(() => {
  const byDate = new Map()
  for (const log of aiLogs.value) {
    const date = fmtDate(log.createdAt)
    if (!byDate.has(date)) byDate.set(date, [])
    byDate.get(date).push(log)
  }
  return [...byDate.entries()].map(([date, items]) => {
    const byTask = new Map()
    for (const l of items) {
      const key = l.taskId == null ? 'none' : String(l.taskId)
      if (!byTask.has(key)) byTask.set(key, { key, taskId: l.taskId ?? null, items: [] })
      byTask.get(key).items.push(l)
    }
    const taskGroups = [...byTask.values()].sort((a, b) => (b.taskId || 0) - (a.taskId || 0))
    return { date, relative: relativeDay(date), items, taskGroups }
  })
})

function isCurrentRequest(generation, projectId) {
  return generation === requestGeneration && activeProject.value?.projectId === projectId
}

async function loadAiLogs(taskId = null, page = 0) {
  const projectId = activeProject.value?.projectId
  const generation = ++requestGeneration
  if (!projectId) { reset(); return null }
  currentTaskId = taskId
  aiLogsLoading.value = true
  aiLogsError.value = ''
  try {
    const taskQuery = taskId == null || taskId === '' ? '' : `&taskId=${encodeURIComponent(taskId)}`
    const data = await api(`/ai/logs?projectId=${encodeURIComponent(projectId)}${taskQuery}&page=${page}&size=100`)
    if (!isCurrentRequest(generation, projectId)) return null
    if (data && Array.isArray(data.items)) {
      aiLogs.value = data.items
      aiLogPage.value = data.page ?? 0
      aiLogTotalPages.value = data.totalPages ?? 1
    } else {
      aiLogs.value = Array.isArray(data) ? data : []
      aiLogPage.value = 0
      aiLogTotalPages.value = 1
    }
    aiLogScope.value = taskId == null || taskId === '' ? '项目维度' : `任务 #${taskId} 维度`
    return data
  } catch (error) {
    if (!isCurrentRequest(generation, projectId)) return null
    aiLogsError.value = error?.message || 'AI 日志加载失败'
    throw error
  } finally {
    if (isCurrentRequest(generation, projectId)) aiLogsLoading.value = false
  }
}

async function nextAiLogPage() {
  if (aiLogPage.value + 1 < aiLogTotalPages.value) await loadAiLogs(currentTaskId, aiLogPage.value + 1)
}
async function prevAiLogPage() {
  if (aiLogPage.value > 0) await loadAiLogs(currentTaskId, aiLogPage.value - 1)
}

function toggleDate(date) { collapsedDates[date] = !collapsedDates[date] }

function reset() {
  requestGeneration += 1
  aiLogs.value = []
  selectedAiLog.value = null
  aiLogScope.value = '项目维度'
  aiLogPage.value = 0
  aiLogTotalPages.value = 1
  currentTaskId = null
  aiLogsLoading.value = false
  aiLogsError.value = ''
  for (const date of Object.keys(collapsedDates)) delete collapsedDates[date]
}

export function useAiLogs() {
  return {
    aiLogs, aiLogScope, selectedAiLog, collapsedDates, groupedAiLogs,
    aiLogPage, aiLogTotalPages, aiLogsLoading, aiLogsError, loadAiLogs, nextAiLogPage, prevAiLogPage, toggleDate, reset,
  }
}
