import { reactive, ref } from 'vue'
import { api } from '../api/client.js'
import { useSession } from './useSession.js'
import { useToast } from './useToast.js'

// 研发任务(Requirement)域(P1b,单例)。列表/详情/表单跟随 activeProject;
// 状态边与后端 RequirementStatus.canTransition 保持同一张图(前端只做展示裁剪,后端裁决)。
const { activeProject } = useSession()
const { toastMsg } = useToast()

const requirements = ref([])
const requirementsTotal = ref(0)
const requirementsLoading = ref(false)
const requirementFilter = ref('')
const requirementDetail = ref(null)
const checkReports = ref([])
const checkReportsLoading = ref(false)
const requirementLinks = ref([])

const requirementForm = reactive({
  requirementId: null,
  title: '',
  background: '',
  description: '',
  priority: 'MEDIUM',
  acceptanceCriteria: [{ text: '' }],
})

// 与后端状态图同源的展示用边表(仅用于渲染可用按钮;真源在后端)。
export const REQUIREMENT_EDGES = {
  DRAFT: ['NEEDS_IMPROVEMENT', 'READY', 'CANCELED'],
  NEEDS_IMPROVEMENT: ['READY', 'CANCELED'],
  READY: ['NEEDS_IMPROVEMENT', 'IN_DEVELOPMENT', 'CANCELED'],
  IN_DEVELOPMENT: ['IN_REVIEW', 'READY', 'CANCELED'],
  IN_REVIEW: ['DONE', 'IN_DEVELOPMENT', 'CANCELED'],
  DONE: [],
  CANCELED: [],
}

export const REQUIREMENT_STATUS_LABELS = {
  DRAFT: '草稿',
  NEEDS_IMPROVEMENT: '待完善',
  READY: '就绪',
  IN_DEVELOPMENT: '开发中',
  IN_REVIEW: '待审查',
  DONE: '已完成',
  CANCELED: '已取消',
}

function projectIdOrNull() {
  return activeProject.value ? activeProject.value.projectId : null
}

async function loadRequirements() {
  const projectId = projectIdOrNull()
  if (!projectId) { requirements.value = []; requirementsTotal.value = 0; return }
  requirementsLoading.value = true
  try {
    const query = requirementFilter.value ? `?status=${encodeURIComponent(requirementFilter.value)}&size=50` : '?size=50'
    const page = await api(`/projects/${projectId}/requirements${query}`)
    requirements.value = page.items || []
    requirementsTotal.value = page.totalElements ?? requirements.value.length
  } finally { requirementsLoading.value = false }
}

async function openRequirement(summary) {
  const projectId = projectIdOrNull()
  if (!projectId) return
  requirementDetail.value = await api(`/projects/${projectId}/requirements/${summary.requirementId}`)
  await loadCheckReports()
  await loadLinks()
}

async function loadLinks() {
  const projectId = projectIdOrNull()
  const detail = requirementDetail.value
  if (!projectId || !detail) { requirementLinks.value = []; return }
  requirementLinks.value = await api(`/projects/${projectId}/requirements/${detail.requirementId}/links`)
}

async function addLink(type, ref) {
  const projectId = projectIdOrNull()
  const detail = requirementDetail.value
  if (!projectId || !detail) return
  if (!ref || !ref.trim()) return toastMsg('请填写关联引用', 'error')
  await api(`/projects/${projectId}/requirements/${detail.requirementId}/links`, {
    method: 'POST', body: JSON.stringify({ type, ref: ref.trim() }),
  })
  toastMsg('关联已添加', 'success')
  await loadLinks()
}

async function removeLink(link) {
  const projectId = projectIdOrNull()
  const detail = requirementDetail.value
  if (!projectId || !detail) return
  await api(`/projects/${projectId}/requirements/${detail.requirementId}/links/${link.linkId}`, { method: 'DELETE' })
  toastMsg('关联已移除', 'success')
  await loadLinks()
}

async function loadCheckReports() {
  const projectId = projectIdOrNull()
  const detail = requirementDetail.value
  if (!projectId || !detail) { checkReports.value = []; return }
  checkReportsLoading.value = true
  try {
    checkReports.value = await api(`/projects/${projectId}/requirements/${detail.requirementId}/check-reports`)
  } finally { checkReportsLoading.value = false }
}

async function runCheck() {
  const projectId = projectIdOrNull()
  const detail = requirementDetail.value
  if (!projectId || !detail) return
  const report = await api(`/projects/${projectId}/requirements/${detail.requirementId}/check`, {
    method: 'POST', body: '{}',
  })
  checkReports.value = [report, ...checkReports.value]
  toastMsg(`体检完成（第 ${report.round} 轮）`, 'success')
}

function resetRequirementForm() {
  Object.assign(requirementForm, {
    requirementId: null, title: '', background: '', description: '',
    priority: 'MEDIUM', acceptanceCriteria: [{ text: '' }],
  })
}

function editRequirement(detail) {
  Object.assign(requirementForm, {
    requirementId: detail.requirementId,
    title: detail.title,
    background: detail.background || '',
    description: detail.description || '',
    priority: detail.priority,
    acceptanceCriteria: detail.acceptanceCriteria.length
      ? detail.acceptanceCriteria.map(ac => ({ text: ac.text }))
      : [{ text: '' }],
  })
}

async function saveRequirement() {
  const projectId = projectIdOrNull()
  if (!projectId) return
  if (!requirementForm.title.trim()) return toastMsg('请填写需求标题', 'error')
  const body = JSON.stringify({
    title: requirementForm.title,
    background: requirementForm.background,
    description: requirementForm.description,
    priority: requirementForm.priority,
    acceptanceCriteria: requirementForm.acceptanceCriteria.filter(ac => ac.text && ac.text.trim()),
  })
  if (requirementForm.requirementId) {
    requirementDetail.value = await api(
      `/projects/${projectId}/requirements/${requirementForm.requirementId}`, { method: 'PUT', body })
    toastMsg('需求已更新', 'success')
  } else {
    requirementDetail.value = await api(`/projects/${projectId}/requirements`, { method: 'POST', body })
    toastMsg('需求已创建', 'success')
  }
  resetRequirementForm()
  await loadRequirements()
}

async function assignRequirement(detail, userId) {
  const projectId = projectIdOrNull()
  if (!projectId) return
  requirementDetail.value = await api(
    `/projects/${projectId}/requirements/${detail.requirementId}/assign`,
    { method: 'POST', body: JSON.stringify({ userId }) })
  toastMsg('已指派', 'success')
  await loadRequirements()
}

async function transitionRequirement(detail, status) {
  const projectId = projectIdOrNull()
  if (!projectId) return
  requirementDetail.value = await api(
    `/projects/${projectId}/requirements/${detail.requirementId}/status`,
    { method: 'POST', body: JSON.stringify({ status }) })
  toastMsg(`已流转至「${REQUIREMENT_STATUS_LABELS[status] || status}」`, 'success')
  await loadRequirements()
}

export const CHECK_DIMENSION_LABELS = {
  COMPLETENESS: '完整性',
  CLARITY: '明确性',
  TESTABILITY: '可测试性',
  EXCEPTION_COVERAGE: '异常覆盖',
  RULE_CONFLICT: '规则冲突',
  RISK: '风险',
}

export function useRequirements() {
  return {
    requirements, requirementsTotal, requirementsLoading, requirementFilter,
    requirementDetail, requirementForm, checkReports, checkReportsLoading, requirementLinks,
    loadRequirements, openRequirement, resetRequirementForm, editRequirement,
    saveRequirement, assignRequirement, transitionRequirement,
    loadCheckReports, runCheck, loadLinks, addLink, removeLink,
  }
}
