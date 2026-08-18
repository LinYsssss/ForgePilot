<template>
  <InkPageFrame active-key="dashboard" context-label="项目工作台" rail-title="队列口径">
    <DashboardPaper
      :active-project="activeProject"
      :data="workbench"
      :loading="workbenchLoading"
      :error="workbenchError"
      @retry="run(loadWorkbench, 'workbench')"
      @open="openWorkbenchTarget"
    />
    <template #rail>
      <p class="rail-note">研发任务与 Finding 来自当前用户的真实 assignee；待审 PR 只按项目角色与 reviewState 汇总。</p>
      <p class="rail-note">所有队列由服务端有界 projection 生成，浏览器不会拉取全量列表再拼接。</p>
    </template>
  </InkPageFrame>
</template>

<script setup>
import { watch } from 'vue'
import InkPageFrame from '../features/shell/InkPageFrame.vue'
import DashboardPaper from '../features/dashboard/DashboardPaper.vue'
import { useBusy } from '../composables/useBusy.js'
import { useSession } from '../composables/useSession.js'
import { useWorkbench } from '../composables/useWorkbench.js'
import { useWorkspace } from '../composables/useWorkspace.js'

const { run } = useBusy()
const { activeProject } = useSession()
const { workbench, workbenchLoading, workbenchError, loadWorkbench, reset } = useWorkbench()
const { openWorkbenchTarget } = useWorkspace()

watch(() => activeProject.value?.projectId, () => {
  reset()
  if (activeProject.value) run(loadWorkbench, 'workbench')
}, { immediate: true })
</script>

<style scoped>
.rail-note { margin: 0 0 var(--ink-sp-12); color: var(--ink-muted); line-height: var(--ink-lh-body); }
</style>
