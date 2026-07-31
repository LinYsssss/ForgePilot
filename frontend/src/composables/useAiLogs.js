import { computed, reactive, ref } from 'vue'
import { api } from '../api/client'
import { fmtDate } from '../utils/format'
import { relativeDay } from '../utils/labels'
import { useSession } from './useSession'

// AI 调用日志(单例):项目/任务两个维度,按日期→任务分组展示。
const { activeProject } = useSession()

const aiLogs = ref([])
const aiLogScope = ref('项目维度')
const selectedAiLog = ref(null)
const collapsedDates = reactive({})

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

async function loadAiLogs(taskId = null) {
  if (!activeProject.value) return
  const query = taskId ? `taskId=${taskId}&limit=100` : `projectId=${activeProject.value.projectId}&limit=100`
  aiLogs.value = await api(`/ai/logs?${query}`)
  aiLogScope.value = taskId ? `任务 #${taskId} 维度` : '项目维度'
}

function toggleDate(date) { collapsedDates[date] = !collapsedDates[date] }

function reset() {
  aiLogs.value = []
  selectedAiLog.value = null
  aiLogScope.value = '项目维度'
}

export function useAiLogs() {
  return { aiLogs, aiLogScope, selectedAiLog, collapsedDates, groupedAiLogs, loadAiLogs, toggleDate, reset }
}
