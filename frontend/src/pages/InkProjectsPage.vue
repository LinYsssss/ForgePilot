<template>
  <InkPageFrame active-key="projects" context-label="当前项目" rail-title="项目备注">
    <ProjectsPaper
      :projects="projects"
      :active-project="activeProject"
      :form="projectForm"
      :loading="!!busy.projects"
      :saving="!!busy.project"
      @select="selectProject"
      @edit="editProject"
      @remove="askDeleteProject"
      @save="run(saveProject, 'project')"
      @reset="resetProjectForm"
    />

    <MembersPaper
      v-if="activeProject"
      :project-name="activeProject.name"
      :members="members"
      :loading="membersLoading"
      :can-manage="activeProject.myRole === 'LEADER'"
      @add="payload => run(() => addMember(payload.username, payload.role), 'memberAdd')"
      @change-role="(member, role) => run(() => updateMemberRole(member, role), 'memberRole')"
      @transfer="member => askTransferOwner(member, loadProjects)"
      @remove="askRemoveMember"
    />

    <template #rail>
      <p class="rail-note">
        选中项目后,仓库、PR、审查记录等页面才会解锁——它们都以当前项目为作用域。
      </p>
      <p class="rail-note">
        成员分三种角色:负责人掌管设置与成员,开发人员可触发审查与上传知识,审查人员只读与裁定。
      </p>
    </template>
  </InkPageFrame>
</template>

<script setup>
import { watch } from 'vue'
import InkPageFrame from '../features/shell/InkPageFrame.vue'
import ProjectsPaper from '../features/projects/ProjectsPaper.vue'
import MembersPaper from '../features/projects/MembersPaper.vue'
import { useBusy } from '../composables/useBusy.js'
import { useSession } from '../composables/useSession.js'
import { useProjects } from '../composables/useProjects.js'
import { useMembers } from '../composables/useMembers.js'
import { useWorkspace } from '../composables/useWorkspace.js'

// 墨境项目页(实施步骤 8 第二页;P1a 增成员名册)。与旧 ProjectsView 取同一批 composable 引用,
// 表单与增删改查语义原样保留;删除确认由页框统一渲染的 InkDialog 承接
// (askDeleteProject 走 useConfirm 单例)。
const { busy, run } = useBusy()
const { projects, activeProject } = useSession()
const { projectForm, loadProjects, resetProjectForm, editProject, saveProject, askDeleteProject } = useProjects()
const { members, membersLoading, loadMembers, addMember, updateMemberRole, askRemoveMember, askTransferOwner } = useMembers()
const { selectProject } = useWorkspace()

// 成员名册跟随选中项目;immediate 覆盖「进入页面时已有选中项目」的路径。
watch(() => activeProject.value && activeProject.value.projectId, () => { run(loadMembers, 'members') }, { immediate: true })
</script>

<style scoped>
.rail-note { margin: 0 0 var(--ink-sp-12); color: var(--ink-muted); line-height: var(--ink-lh-body); }
.rail-note:last-child { margin-bottom: 0; }
</style>
