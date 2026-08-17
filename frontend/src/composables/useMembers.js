import { ref } from 'vue'
import { api } from '../api/client.js'
import { useConfirm } from './useConfirm.js'
import { useSession } from './useSession.js'
import { useToast } from './useToast.js'

// 项目成员管理(P1a,单例)。列表跟随 activeProject 加载;写操作只有 LEADER 会看到入口
// (myRole 裁剪),后端仍是最终裁决——403 会以 toast 呈现而不是静默失败。
const { activeProject } = useSession()
const { ask } = useConfirm()
const { toastMsg } = useToast()

const members = ref([])
const membersLoading = ref(false)

async function loadMembers() {
  const project = activeProject.value
  if (!project) { members.value = []; return }
  membersLoading.value = true
  try {
    members.value = await api(`/projects/${project.projectId}/members`)
  } finally { membersLoading.value = false }
}

async function addMember(username, role) {
  const project = activeProject.value
  if (!project) return
  if (!username || !username.trim()) return toastMsg('请填写用户名', 'error')
  await api(`/projects/${project.projectId}/members`, {
    method: 'POST',
    body: JSON.stringify({ username: username.trim(), role }),
  })
  toastMsg('成员已添加', 'success')
  await loadMembers()
}

async function updateMemberRole(member, role) {
  const project = activeProject.value
  if (!project) return
  await api(`/projects/${project.projectId}/members/${member.userId}`, {
    method: 'PUT',
    body: JSON.stringify({ role }),
  })
  toastMsg('角色已更新', 'success')
  await loadMembers()
}

function askRemoveMember(member) {
  const project = activeProject.value
  if (!project) return
  ask({
    title: `移除成员「${member.username || member.userId}」？`,
    body: '移除后该用户将无法访问本项目。',
    onConfirm: async () => {
      await api(`/projects/${project.projectId}/members/${member.userId}`, { method: 'DELETE' })
      toastMsg('成员已移除', 'success')
      await loadMembers()
    },
  })
}

function askTransferOwner(member, onDone) {
  const project = activeProject.value
  if (!project) return
  ask({
    title: `移交负责人给「${member.username || member.userId}」？`,
    body: '移交后你将降为开发人员，项目设置与成员管理将由新负责人掌管。此操作立即生效。',
    onConfirm: async () => {
      await api(`/projects/${project.projectId}/members/transfer`, {
        method: 'POST',
        body: JSON.stringify({ userId: member.userId }),
      })
      toastMsg('负责人已移交', 'success')
      await loadMembers()
      if (onDone) await onDone()
    },
  })
}

export function useMembers() {
  return { members, membersLoading, loadMembers, addMember, updateMemberRole, askRemoveMember, askTransferOwner }
}
