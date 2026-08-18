import { ref } from 'vue'
import { api } from '../api/client.js'
import { useSession } from './useSession.js'

const { activeProject } = useSession()
const workbench = ref(null)
const workbenchLoading = ref(false)
const workbenchError = ref('')
let requestGeneration = 0

function isCurrentRequest(generation, projectId) {
  return generation === requestGeneration && activeProject.value?.projectId === projectId
}

async function loadWorkbench(limit = 6) {
  const projectId = activeProject.value?.projectId
  const generation = ++requestGeneration
  if (!projectId) { reset(); return null }
  workbenchLoading.value = true
  workbenchError.value = ''
  try {
    const data = await api(`/projects/${projectId}/workbench?limit=${limit}`)
    if (!isCurrentRequest(generation, projectId)) return null
    workbench.value = data
    return data
  } catch (error) {
    if (!isCurrentRequest(generation, projectId)) return null
    workbenchError.value = error?.message || '工作台加载失败'
    throw error
  } finally {
    if (isCurrentRequest(generation, projectId)) workbenchLoading.value = false
  }
}

function reset() {
  requestGeneration += 1
  workbench.value = null
  workbenchError.value = ''
  workbenchLoading.value = false
}

export function useWorkbench() {
  return { workbench, workbenchLoading, workbenchError, loadWorkbench, reset }
}
