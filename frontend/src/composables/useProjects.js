import { reactive } from 'vue'
import { api } from '../api/client.js'
import { useBusy } from './useBusy.js'
import { useConfirm } from './useConfirm.js'
import { useSession } from './useSession.js'
import { useToast } from './useToast.js'

// 项目 CRUD(单例)。选中项目后的跨域清理与跳转在 useWorkspace.selectProject。
const { projects, activeProject } = useSession()
const { busy } = useBusy()
const { ask } = useConfirm()
const { toastMsg } = useToast()

const projectForm = reactive({ projectId: null, name: '', description: '', defaultBranch: 'main' })

async function loadProjects() {
  busy.projects = true
  try {
    projects.value = await api('/projects')
    if (activeProject.value) {
      activeProject.value = projects.value.find(p => p.projectId === activeProject.value.projectId) || null
    }
    if (!activeProject.value && projects.value.length) activeProject.value = projects.value[0]
  } finally { busy.projects = false }
}

function resetProjectForm() { Object.assign(projectForm, { projectId: null, name: '', description: '', defaultBranch: 'main' }) }

function editProject(p) {
  Object.assign(projectForm, { projectId: p.projectId, name: p.name, description: p.description || '', defaultBranch: p.defaultBranch })
  toastMsg('正在编辑：' + p.name)
}

async function saveProject() {
  if (!projectForm.name.trim()) return toastMsg('请填写项目名称', 'error')
  const body = JSON.stringify({ name: projectForm.name, description: projectForm.description, defaultBranch: projectForm.defaultBranch })
  if (projectForm.projectId) {
    await api(`/projects/${projectForm.projectId}`, { method: 'PUT', body })
    toastMsg('项目已更新', 'success')
  } else {
    const created = await api('/projects', { method: 'POST', body })
    activeProject.value = created
    toastMsg('项目已创建', 'success')
  }
  resetProjectForm()
  await loadProjects()
}

function askDeleteProject(p) {
  ask({
    title: `删除项目「${p.name}」？`,
    body: '将级联删除该项目的仓库绑定、知识库、审查任务、报告、问题与反馈，操作不可恢复。',
    onConfirm: async () => {
      await api(`/projects/${p.projectId}`, { method: 'DELETE' })
      if (activeProject.value && activeProject.value.projectId === p.projectId) activeProject.value = null
      await loadProjects()
      toastMsg('项目已删除', 'success')
    },
  })
}

export function useProjects() {
  return { projectForm, loadProjects, resetProjectForm, editProject, saveProject, askDeleteProject }
}
