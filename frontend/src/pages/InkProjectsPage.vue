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

    <template #rail>
      <p class="rail-note">
        选中项目后,仓库、PR、审查记录等页面才会解锁——它们都以当前项目为作用域。
      </p>
    </template>
  </InkPageFrame>
</template>

<script setup>
import InkPageFrame from '../features/shell/InkPageFrame.vue'
import ProjectsPaper from '../features/projects/ProjectsPaper.vue'
import { useBusy } from '../composables/useBusy.js'
import { useSession } from '../composables/useSession.js'
import { useProjects } from '../composables/useProjects.js'
import { useWorkspace } from '../composables/useWorkspace.js'

// 墨境项目页(实施步骤 8 第二页)。与旧 ProjectsView 取同一批 composable 引用,
// 表单与增删改查语义原样保留;删除确认由页框统一渲染的 InkDialog 承接
// (askDeleteProject 走 useConfirm 单例)。
const { busy, run } = useBusy()
const { projects, activeProject } = useSession()
const { projectForm, resetProjectForm, editProject, saveProject, askDeleteProject } = useProjects()
const { selectProject } = useWorkspace()
</script>

<style scoped>
.rail-note { margin: 0; color: var(--ink-muted); line-height: var(--ink-lh-body); }
</style>
