<template>
  <div class="view">
      <DashboardStats :project-count="projects.length" :task-count="tasks.length" :report-count="reports.length" :high-risk="highRiskCount" />

      <DashboardViz v-if="activeProject" :reports="reports" />

      <div class="panel">
        <div class="panel-head">
          <div><h2>最近审查报告</h2><div class="sub">{{ activeProject ? activeProject.name : '请选择项目' }}</div></div>
          <button class="sm secondary" @click="goTab('reviews')" :disabled="!activeProject">前往审查 →</button>
        </div>
        <div v-if="!activeProject" class="empty"><div class="ico" aria-hidden="true">▤</div><p>还没有选择项目</p><p>去“项目”页创建或选择一个项目。</p></div>
        <div v-else-if="!reports.length" class="empty"><div class="ico" aria-hidden="true">✓</div><p>暂无审查报告</p><p>绑定仓库并触发一次代码审查吧。</p></div>
        <div v-else class="list">
          <button v-for="r in reports.slice(0,6)" :key="r.reportId" class="list-row row-reports" @click="openReport(r.reportId)">
            <span class="mono">#{{ r.reportId }}</span>
            <span class="grow"><span class="mono">{{ shortCommit(r.commitId) }}</span> · {{ fmtTime(r.createdAt) }}</span>
            <span class="badge" :class="'risk-' + r.overallRisk">{{ r.overallRisk }}</span>
            <span class="mono">{{ r.issueCount }} 问题</span>
          </button>
        </div>
      </div>
  </div>
</template>

<script setup>
import DashboardStats from '../components/DashboardStats.vue'
import DashboardViz from '../components/DashboardViz.vue'
import { fmtTime, shortCommit } from '../utils/format.js'
import { useSession } from '../composables/useSession.js'
import { useReviews } from '../composables/useReviews.js'
import { useWorkspace } from '../composables/useWorkspace.js'

const { projects, activeProject } = useSession()
const { tasks, reports, highRiskCount } = useReviews()
const { goTab, openReport } = useWorkspace()
</script>
