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
let currentTaskId = null // 翻页时沿用当前维度

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

async function loadAiLogs(taskId = null, page = 0) {
  if (!activeProject.value) return
  currentTaskId = taskId
  const scope = taskId ? `taskId=${taskId}` : `projectId=${activeProject.value.projectId}`
  const data = await api(`/ai/logs?${scope}&page=${page}&size=100`)
  if (data && Array.isArray(data.items)) {
    aiLogs.value = data.items
    aiLogPage.value = data.page ?? 0
    aiLogTotalPages.value = data.totalPages ?? 1
  } else {
    // 旧后端(信封化前)兜底
    aiLogs.value = Array.isArray(data) ? data : []
    aiLogPage.value = 0
    aiLogTotalPages.value = 1
  }
  aiLogScope.value = taskId ? `任务 #${taskId} 维度` : '项目维度'
}

async function nextAiLogPage() {
  if (aiLogPage.value + 1 < aiLogTotalPages.value) await loadAiLogs(currentTaskId, aiLogPage.value + 1)
}
async function prevAiLogPage() {
  if (aiLogPage.value > 0) await loadAiLogs(currentTaskId, aiLogPage.value - 1)
}

function toggleDate(date) { collapsedDates[date] = !collapsedDates[date] }

function reset() {
  aiLogs.value = []
  selectedAiLog.value = null
  aiLogScope.value = '项目维度'
  aiLogPage.value = 0
  aiLogTotalPages.value = 1
  currentTaskId = null
}

export function useAiLogs() {
  return {
    aiLogs, aiLogScope, selectedAiLog, collapsedDates, groupedAiLogs,
    aiLogPage, aiLogTotalPages, loadAiLogs, nextAiLogPage, prevAiLogPage, toggleDate, reset,
  }
}
