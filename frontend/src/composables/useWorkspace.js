import { api, initCsrf } from '../api/client.js'
import { nav } from '../nav.js'
import { useAgentWorkspace } from './useAgentWorkspace.js'
import { useAiLogs } from './useAiLogs.js'
import { useBusy } from './useBusy.js'
import { useKnowledge } from './useKnowledge.js'
import { useProjects } from './useProjects.js'
import { usePullRequests } from './usePullRequests.js'
import { useRepository } from './useRepository.js'
import { useReviews } from './useReviews.js'
import { useSession } from './useSession.js'
import { useToast } from './useToast.js'

// 跨域编排(单例):凡是"涉及两个以上领域"或"要跳转路由"的动作都在这里,
// 领域 composable 之间不互相调用(仅允许既有单向依赖),依赖方向始终 workspace → 域。
const { authenticated, me, projects, activeProject } = useSession()
const { busy, run } = useBusy()
const { toastMsg } = useToast()
const projectsDomain = useProjects()
const repository = useRepository()
const reviews = useReviews()
const pullRequests = usePullRequests()
const knowledge = useKnowledge()
const agent = useAgentWorkspace()
const aiLogs = useAiLogs()

function goto(name) { nav.push({ name }) }

/* ---------- 会话 ---------- */
async function loadMe() {
  try {
    const data = await api('/auth/me')
    Object.assign(me, data)
  } catch {
    Object.assign(me, { userId: null, username: '', nickname: '', role: '' })
    throw new Error('未登录')
  }
}

async function afterLogin() {
  await loadMe()
  await refreshAll()
  goto('dashboard')
  toastMsg('登录成功', 'success')
}

async function logout(callApi = true) {
  if (callApi) {
    try { await api('/auth/logout', { method: 'POST' }) } catch { /* ignore */ }
  }
  authenticated.value = false
  Object.assign(me, { userId: null, username: '', nickname: '', role: '' })
  projects.value = []
  activeProject.value = null
  resetForProject()
  reviews.stopPolling()
  // 退出同样轮换 CSRF;重新引导,让下一次登录的写请求直接可用
  await initCsrf().catch(() => {})
}

/* ---------- 刷新与项目切换 ---------- */
async function refreshAll() {
  if (!authenticated.value) return
  busy.refresh = true
  try {
    await projectsDomain.loadProjects()
    if (activeProject.value) {
      await Promise.allSettled([repository.loadRepository(), knowledge.loadDocuments(), reviews.loadReviews(), pullRequests.loadPullRequests(), aiLogs.loadAiLogs()])
    }
  } finally { busy.refresh = false }
}

function goTab(name) { goto(name); if (activeProject.value) run(refreshAll) }

// 项目切换/退出时的全量清理(等价原 App.vue resetReviewState,顺序保持)。
function resetForProject() {
  repository.reset()          // commits/selectedCommit/diffFiles + repoForm(依赖 activeProject 的默认分支)
  reviews.reset()             // tasks/reports/activeTask/reportDetail/mqLogs
  pullRequests.reset()        // PR 列表/选中/动作 + 表单
  agent.reset()               // 关 SSE、停轮询、清 Run 状态
  knowledge.chosenDocsReset() // 两个文档选择集
}

function selectProject(p) {
  activeProject.value = p
  resetForProject()
  goto('repository')
  run(refreshAll)
}

/* ---------- 跨域动作 ---------- */
// 选中提交:仓库域记录选中,并预填审查表单(跨域,故在此)。
function selectCommit(c) {
  repository.selectedCommit.value = c
  repository.diffFiles.value = []
  reviews.reviewForm.commitId = c.commitId
  reviews.reviewForm.baseCommitId = c.parentCommitId || ''
  reviews.reviewForm.branch = activeProject.value?.defaultBranch || 'main'
}

// 查看变更:diff 的 base 取自审查表单(保持原行为)。
function loadDiff() { return repository.loadDiff(reviews.reviewForm.baseCommitId) }

function reviewSelectedCommit() {
  reviews.reviewForm.commitId = repository.selectedCommit.value.commitId
  goto('reviews')
}

function fillPrFromSelectedCommit() {
  if (!repository.selectedCommit.value) return
  pullRequests.fillPrFromCommit(repository.selectedCommit.value, reviews.reviewForm.branch)
  goto('pullRequests')
}

async function openReport(reportId) {
  await reviews.loadReport(reportId)
  goto('reviews')
}

async function openAgentWorkspace() {
  goto('agent')
  await run(agent.loadAgentRuns)
  if (agent.agentRunId.value) await run(agent.loadAgentWorkspace)
}

// PR 行直达对应 Agent Run:按 head SHA 匹配(Webhook 创建的 Run 与 PR 以 head 对齐)。
async function openAgentRunForPr(pr) {
  goto('agent')
  await run(agent.loadAgentRuns)
  const match = agent.agentRuns.value.find(item => item.headSha && item.headSha === pr.headSha)
  if (!match) {
    toastMsg(`该 PR(head ${String(pr.headSha || '').slice(0, 10)})尚无 Agent Run`, 'error')
    return
  }
  agent.agentRunId.value = match.id
  agent.agentHeadSha.value = match.headSha || ''
  await run(agent.loadAgentWorkspace)
}

async function openProjectAiLogs() {
  if (!activeProject.value) return
  await aiLogs.loadAiLogs()
  goto('aiLogs')
}

async function openTaskAiLogs(taskId) {
  await aiLogs.loadAiLogs(taskId)
  goto('aiLogs')
}

// 审查轮询完成 → 打开最新报告(依赖注入,避免 reviews → workspace 反向引用)。
reviews.setCompletionHandler(openReport)

export function useWorkspace() {
  return {
    goto, loadMe, afterLogin, logout, refreshAll, goTab, resetForProject, selectProject,
    selectCommit, loadDiff, reviewSelectedCommit, fillPrFromSelectedCommit,
    openReport, openAgentWorkspace, openAgentRunForPr, openProjectAiLogs, openTaskAiLogs,
  }
}
