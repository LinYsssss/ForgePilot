import { computed, reactive, ref } from 'vue'
import {
  assignProjectFinding,
  listProjectFindings,
  transitionProjectFinding,
} from '../api/finding.js'
import { useSession } from './useSession.js'
import { useToast } from './useToast.js'

const { activeProject } = useSession()
const { toastMsg } = useToast()

const findings = ref([])
const lifecycleFilter = ref('')
const selectedFindingId = ref(null)
const pageInfo = reactive({ page: 0, size: 12, totalElements: 0, totalPages: 0 })

const selectedFinding = computed(() => findings.value.find(
  finding => String(finding.id) === String(selectedFindingId.value),
) || null)

function resetQualityFindings() {
  findings.value = []
  selectedFindingId.value = null
  lifecycleFilter.value = ''
  Object.assign(pageInfo, { page: 0, size: 12, totalElements: 0, totalPages: 0 })
}

function keepSelection(items, preferredId) {
  const preferred = items.find(item => String(item.id) === String(preferredId))
  selectedFindingId.value = preferred?.id ?? items[0]?.id ?? null
}

async function loadQualityFindings({ preferredId = selectedFindingId.value } = {}) {
  const projectId = activeProject.value?.projectId
  if (!projectId) {
    findings.value = []
    selectedFindingId.value = null
    Object.assign(pageInfo, { page: 0, totalElements: 0, totalPages: 0 })
    return
  }

  const result = await listProjectFindings(projectId, {
    lifecycle: lifecycleFilter.value,
    page: pageInfo.page,
    size: pageInfo.size,
  })
  if (String(activeProject.value?.projectId) !== String(projectId)) return

  findings.value = result.items
  Object.assign(pageInfo, {
    page: result.page,
    size: result.size,
    totalElements: result.totalElements,
    totalPages: result.totalPages,
  })
  keepSelection(result.items, preferredId)
}

async function setLifecycleFilter(value) {
  lifecycleFilter.value = String(value || '').trim().toUpperCase()
  pageInfo.page = 0
  await loadQualityFindings({ preferredId: null })
}

async function goToQualityPage(page) {
  const lastPage = Math.max(0, pageInfo.totalPages - 1)
  pageInfo.page = Math.min(Math.max(0, Number(page) || 0), lastPage)
  await loadQualityFindings({ preferredId: null })
}

function selectQualityFinding(finding) {
  selectedFindingId.value = finding?.id ?? null
}

async function transitionQualityFinding(finding, action, fixCommitSha = '') {
  const projectId = activeProject.value?.projectId
  if (!projectId || !finding?.id) return
  const updated = await transitionProjectFinding(projectId, finding.id, action, fixCommitSha)
  toastMsg('问题状态已更新', 'success')
  await loadQualityFindings({ preferredId: updated?.id ?? finding.id })
}

async function assignQualityFinding(finding, userId) {
  const projectId = activeProject.value?.projectId
  if (!projectId || !finding?.id || userId === '' || userId === null || userId === undefined) return
  const updated = await assignProjectFinding(projectId, finding.id, userId)
  toastMsg('修复负责人已更新', 'success')
  await loadQualityFindings({ preferredId: updated?.id ?? finding.id })
}

export function useQualityFindings() {
  return {
    findings,
    lifecycleFilter,
    selectedFindingId,
    selectedFinding,
    pageInfo,
    resetQualityFindings,
    loadQualityFindings,
    setLifecycleFilter,
    goToQualityPage,
    selectQualityFinding,
    transitionQualityFinding,
    assignQualityFinding,
  }
}
