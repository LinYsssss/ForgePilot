import { computed, reactive, ref } from 'vue'
import { api } from '../api/client.js'
import { shortCommit } from '../utils/format.js'
import { useBusy } from './useBusy.js'
import { useKnowledge } from './useKnowledge.js'
import { useReviews } from './useReviews.js'
import { useSession } from './useSession.js'
import { useToast } from './useToast.js'

// Pull Request 工作流(单例):登记/更新/选择、PR 审查触发与管理员动作。
// 依赖方向:pullRequests → reviews/knowledge(单向,不回引)。
const { activeProject } = useSession()
const { busy } = useBusy()
const { toastMsg } = useToast()
const { prDocs } = useKnowledge()
const { tasks, reports, loadReviews, maybeStartPolling } = useReviews()

const pullRequestForm = reactive({ pullRequestId: null, prNumber: null, title: '', authorName: '', sourceBranch: '', targetBranch: 'main', baseSha: '', headSha: '', provider: 'GITHUB', externalPrId: '', status: 'OPEN' })
const prActionForm = reactive({ actionType: 'REQUEST_CHANGES', reportId: null, reason: '', requirement: '' })
const pullRequests = ref([])
const activePullRequest = ref(null)
const prActions = ref([])
const actionReportDetail = ref(null)
const actionIssueIds = ref(new Set())

const prReports = computed(() => {
  if (!activePullRequest.value) return []
  const taskIds = new Set(tasks.value.filter(t => t.pullRequestId === activePullRequest.value.pullRequestId).map(t => t.taskId))
  return reports.value.filter(r => taskIds.has(r.taskId))
})

function resetPullRequestForm() {
  Object.assign(pullRequestForm, {
    pullRequestId: null,
    prNumber: null,
    title: '',
    authorName: '',
    sourceBranch: '',
    targetBranch: activeProject.value?.defaultBranch || 'main',
    baseSha: '',
    headSha: '',
    provider: 'GITHUB',
    externalPrId: '',
    status: 'OPEN',
  })
}

function fillPrFromCommit(commit, sourceBranch) {
  Object.assign(pullRequestForm, {
    title: commit.message || `Review ${shortCommit(commit.commitId)}`,
    authorName: commit.authorName || '',
    sourceBranch: sourceBranch || activeProject.value?.defaultBranch || 'main',
    targetBranch: activeProject.value?.defaultBranch || 'main',
    baseSha: commit.parentCommitId || '',
    headSha: commit.commitId,
  })
}

async function savePullRequest() {
  if (!pullRequestForm.title.trim()) return toastMsg('请填写 PR 标题', 'error')
  if (!pullRequestForm.sourceBranch.trim() || !pullRequestForm.targetBranch.trim()) return toastMsg('请填写源分支和目标分支', 'error')
  if (!pullRequestForm.baseSha.trim() || !pullRequestForm.headSha.trim()) return toastMsg('请填写 Base 和 Head', 'error')
  busy.pullRequest = true
  try {
    const payload = {
      prNumber: pullRequestForm.prNumber || null,
      title: pullRequestForm.title,
      authorName: pullRequestForm.authorName,
      sourceBranch: pullRequestForm.sourceBranch,
      targetBranch: pullRequestForm.targetBranch,
      baseSha: pullRequestForm.baseSha,
      headSha: pullRequestForm.headSha,
      provider: pullRequestForm.provider,
      externalPrId: pullRequestForm.externalPrId,
    }
    if (pullRequestForm.pullRequestId) {
      // status 仅 UpdatePullRequestRequest 有;创建端点(CreatePullRequestRequest)无该字段。
      const body = JSON.stringify({ ...payload, status: pullRequestForm.status })
      await api(`/projects/${activeProject.value.projectId}/pull-requests/${pullRequestForm.pullRequestId}`, { method: 'PUT', body })
      toastMsg('PR 已更新', 'success')
    } else {
      const body = JSON.stringify(payload)
      const created = await api(`/projects/${activeProject.value.projectId}/pull-requests`, { method: 'POST', body })
      activePullRequest.value = created
      toastMsg('PR 已登记', 'success')
    }
    await loadPullRequests()
    resetPullRequestForm()
  } finally { busy.pullRequest = false }
}

async function loadPullRequests() {
  if (!activeProject.value) return
  busy.pullRequests = true
  try {
    pullRequests.value = await api(`/projects/${activeProject.value.projectId}/pull-requests`)
    if (activePullRequest.value) {
      activePullRequest.value = pullRequests.value.find(pr => pr.pullRequestId === activePullRequest.value.pullRequestId) || null
    }
    if (!activePullRequest.value && pullRequests.value.length) {
      await selectPullRequest(pullRequests.value[0])
    }
  } finally { busy.pullRequests = false }
}

async function selectPullRequest(pr) {
  activePullRequest.value = pr
  prActionForm.reportId = null
  actionReportDetail.value = null
  actionIssueIds.value = new Set()
  await loadPrActions()
}

function editPullRequest(pr) {
  Object.assign(pullRequestForm, {
    pullRequestId: pr.pullRequestId,
    prNumber: pr.prNumber,
    title: pr.title,
    authorName: pr.authorName || '',
    sourceBranch: pr.sourceBranch,
    targetBranch: pr.targetBranch,
    baseSha: pr.baseSha,
    headSha: pr.headSha,
    provider: pr.provider,
    externalPrId: pr.externalPrId || '',
    status: pr.status,
  })
  toastMsg('正在编辑 PR：' + (pr.prNumber ? '#' + pr.prNumber : '#' + pr.pullRequestId))
}

async function createPrReview() {
  if (!activePullRequest.value) return
  busy.prReview = true
  try {
    const documentIds = Array.from(prDocs.value)
    await api(`/projects/${activeProject.value.projectId}/pull-requests/${activePullRequest.value.pullRequestId}/review-task`, {
      method: 'POST',
      body: JSON.stringify({ documentIds }),
    })
    await loadReviews()
    if (prReports.value[0]) await selectPrReport(prReports.value[0].reportId)
    toastMsg('PR 审查任务已创建', 'success')
    maybeStartPolling()
  } finally { busy.prReview = false }
}

async function selectPrReport(reportId) {
  prActionForm.reportId = reportId
  await loadActionReport()
}

async function loadActionReport() {
  actionIssueIds.value = new Set()
  if (!prActionForm.reportId) {
    actionReportDetail.value = null
    return
  }
  actionReportDetail.value = await api(`/projects/${activeProject.value.projectId}/reviews/reports/${prActionForm.reportId}`)
}

function toggleActionIssue(id) {
  const next = new Set(actionIssueIds.value)
  next.has(id) ? next.delete(id) : next.add(id)
  actionIssueIds.value = next
}

function selectBlockingIssues() {
  if (!actionReportDetail.value) return
  actionIssueIds.value = new Set(actionReportDetail.value.issues
    .filter(i => i.severity === 'HIGH' || i.severity === 'MEDIUM')
    .map(i => i.issueId))
}

function clearActionIssues() { actionIssueIds.value = new Set() }

async function submitPrAction() {
  if (!activePullRequest.value) return
  busy.prAction = true
  try {
    await api(`/projects/${activeProject.value.projectId}/pull-requests/${activePullRequest.value.pullRequestId}/actions`, {
      method: 'POST',
      body: JSON.stringify({
        actionType: prActionForm.actionType,
        reportId: prActionForm.reportId,
        reason: prActionForm.reason,
        requirement: prActionForm.requirement,
        selectedIssueIds: Array.from(actionIssueIds.value),
      }),
    })
    Object.assign(prActionForm, { actionType: 'REQUEST_CHANGES', reportId: null, reason: '', requirement: '' })
    actionReportDetail.value = null
    actionIssueIds.value = new Set()
    await Promise.allSettled([loadPullRequests(), loadPrActions()])
    toastMsg('审核动作已提交', 'success')
  } finally { busy.prAction = false }
}

async function loadPrActions() {
  if (!activePullRequest.value) {
    prActions.value = []
    return
  }
  prActions.value = await api(`/projects/${activeProject.value.projectId}/pull-requests/${activePullRequest.value.pullRequestId}/actions`)
}

function reset() {
  pullRequests.value = []; activePullRequest.value = null; prActions.value = []
  actionReportDetail.value = null; actionIssueIds.value = new Set()
  resetPullRequestForm()
}

export function usePullRequests() {
  return {
    pullRequestForm, prActionForm, pullRequests, activePullRequest, prActions, actionReportDetail, actionIssueIds, prReports,
    resetPullRequestForm, fillPrFromCommit, savePullRequest, loadPullRequests, selectPullRequest, editPullRequest,
    createPrReview, selectPrReport, loadActionReport, toggleActionIssue, selectBlockingIssues, clearActionIssues,
    submitPrAction, loadPrActions, reset,
  }
}
