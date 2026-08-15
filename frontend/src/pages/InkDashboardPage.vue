<template>
  <InkPageFrame active-key="dashboard" context-label="当前项目" rail-title="总览备注">
    <DashboardPaper
      :projects="projects"
      :tasks="tasks"
      :reports="reports"
      :high-risk-count="highRiskCount"
      :active-project="activeProject"
      @go="onGo"
      @open-report="openReport"
    />

    <template #rail>
      <p class="rail-note">
        总览只呈现已落库的审定结果。要追一条问题的证据链,请到墨境工作台按 finding 展开。
      </p>
    </template>
  </InkPageFrame>
</template>

<script setup>
import InkPageFrame from '../features/shell/InkPageFrame.vue'
import DashboardPaper from '../features/dashboard/DashboardPaper.vue'
import { useSession } from '../composables/useSession.js'
import { useReviews } from '../composables/useReviews.js'
import { useWorkspace } from '../composables/useWorkspace.js'

// 墨境总览页(实施步骤 8 首页迁移)。页面只做接线:数据全部来自既有 composable,
// 与旧 DashboardView 取的是同一批引用(useSession/useReviews/useWorkspace),
// 因此迁移不改变任何数据语义,只换表现层。
const { projects, activeProject } = useSession()
const { tasks, reports, highRiskCount } = useReviews()
const { goTab, openReport } = useWorkspace()

// 「前往审查记录」仍走旧壳层的 reviews 页(该页尚未迁移),语义与旧版一致。
function onGo(name) {
  goTab(name)
}
</script>

<style scoped>
.rail-note { margin: 0; color: var(--ink-muted); line-height: var(--ink-lh-body); }
</style>
