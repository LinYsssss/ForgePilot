import { computed, reactive, ref } from 'vue'
import { api } from '../api/client.js'
import { useBusy } from './useBusy.js'
import { useSession } from './useSession.js'
import { useToast } from './useToast.js'

// 仓库绑定与提交浏览(单例)。selectCommit 对审查表单的预填由 useWorkspace 编排。
const { activeProject } = useSession()
const { busy } = useBusy()
const { toastMsg } = useToast()

const repoForm = reactive({ repoUrl: '', provider: 'GITHUB', defaultBranch: 'main', accessToken: '' })
const commits = ref([])
const selectedCommit = ref(null)
const diffFiles = ref([])
const demoRepoPath = import.meta.env?.VITE_DEMO_REPO_PATH || 'F:\\202605New\\demo-repos\\mall-order-service'

const repoBound = computed(() => commits.value.length > 0 || repoForm._bound)
const needsToken = computed(() => /^https?:\/\//i.test(repoForm.repoUrl.trim()))

function useDemoRepository() {
  repoForm.repoUrl = demoRepoPath
  repoForm.provider = 'LOCAL'
  repoForm.defaultBranch = activeProject.value?.defaultBranch || 'main'
  repoForm.accessToken = ''
}

async function loadRepository() {
  if (!activeProject.value) return
  try {
    const repo = await api(`/projects/${activeProject.value.projectId}/repository`)
    if (repo && repo.repoUrl) {
      repoForm.repoUrl = repo.repoUrl
      if (repo.provider) repoForm.provider = repo.provider
      repoForm.defaultBranch = repo.defaultBranch || repoForm.defaultBranch
      repoForm.accessToken = ''
      repoForm._bound = true
      repoForm._tokenConfigured = !!repo.tokenConfigured
    }
  } catch {
    // 未绑定(detail 返回 404)或读取失败:保留当前表单，不清空用户正在填写的内容
  }
}

async function bindRepository() {
  if (!repoForm.repoUrl.trim()) return toastMsg('请填写 Git 地址', 'error')
  busy.bind = true
  try {
    // 只发后端 BindRepositoryRequest 声明的字段,避免把 UI 内部标志(_bound/_tokenConfigured)一并 POST。
    const body = JSON.stringify({
      repoUrl: repoForm.repoUrl,
      provider: repoForm.provider,
      defaultBranch: repoForm.defaultBranch,
      accessToken: repoForm.accessToken,
    })
    await api(`/projects/${activeProject.value.projectId}/repository`, { method: 'POST', body })
    repoForm._bound = true
    repoForm.accessToken = ''
    toastMsg('仓库已绑定', 'success')
    await loadCommits()
  } finally { busy.bind = false }
}

async function unbindRepository() {
  await api(`/projects/${activeProject.value.projectId}/repository`, { method: 'DELETE' })
  commits.value = []; selectedCommit.value = null; diffFiles.value = []; repoForm._bound = false
  toastMsg('已解绑仓库', 'success')
}

async function loadCommits() {
  busy.commits = true
  try {
    commits.value = await api(`/projects/${activeProject.value.projectId}/repository/commits?limit=100`)
    repoForm._bound = true
  } finally { busy.commits = false }
}

async function loadDiff(baseCommitId) {
  busy.diff = true
  try {
    const data = await api(`/projects/${activeProject.value.projectId}/repository/commits/${selectedCommit.value.commitId}/diff` +
      (baseCommitId ? `?baseCommitId=${encodeURIComponent(baseCommitId)}` : ''))
    diffFiles.value = data.files || []
  } finally { busy.diff = false }
}

function reset() {
  commits.value = []; selectedCommit.value = null; diffFiles.value = []
  Object.assign(repoForm, { repoUrl: '', provider: 'GITHUB', defaultBranch: activeProject.value?.defaultBranch || 'main', accessToken: '', _bound: false, _tokenConfigured: false })
}

export function useRepository() {
  return {
    repoForm, commits, selectedCommit, diffFiles, repoBound, needsToken,
    useDemoRepository, loadRepository, bindRepository, unbindRepository, loadCommits, loadDiff, reset,
  }
}
