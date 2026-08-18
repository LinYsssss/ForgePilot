import { ref } from 'vue'
import { api } from '../api/client.js'
import { useSession } from './useSession.js'

export const METRICS_WINDOWS = ['7d', '30d', '90d']
const { activeProject } = useSession()
const metricsWindow = ref('30d')
const metrics = ref(null)
const metricsLoading = ref(false)
const metricsError = ref('')
let requestGeneration = 0

function normalizeMetricsWindow(value) {
  return METRICS_WINDOWS.includes(value) ? value : '30d'
}

function isCurrentRequest(generation, projectId) {
  return generation === requestGeneration && activeProject.value?.projectId === projectId
}

async function loadMetrics(value = metricsWindow.value) {
  const projectId = activeProject.value?.projectId
  const generation = ++requestGeneration
  if (!projectId) { reset(); return null }
  metricsWindow.value = normalizeMetricsWindow(value)
  metricsLoading.value = true
  metricsError.value = ''
  try {
    const data = await api(`/projects/${projectId}/metrics?window=${metricsWindow.value}`)
    if (!isCurrentRequest(generation, projectId)) return null
    metrics.value = data
    return data
  } catch (error) {
    if (!isCurrentRequest(generation, projectId)) return null
    metricsError.value = error?.message || '研发度量加载失败'
    throw error
  } finally {
    if (isCurrentRequest(generation, projectId)) metricsLoading.value = false
  }
}

function reset() {
  requestGeneration += 1
  metrics.value = null
  metricsError.value = ''
  metricsLoading.value = false
}

export function useDevelopmentMetrics() {
  return { metricsWindow, metrics, metricsLoading, metricsError, loadMetrics, reset }
}

export { normalizeMetricsWindow }
