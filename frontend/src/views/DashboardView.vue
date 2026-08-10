<template>
  <div class="view">
    <DashboardStats :project-count="projects.length" :task-count="tasks.length" :report-count="reports.length" :high-risk="highRiskCount" />

    <DashboardViz v-if="activeProject" :reports="reports" />

    <el-card class="report-card" shadow="never">
      <template #header>
        <div class="card-head">
          <div><h2>最近审查报告</h2><div class="card-sub">{{ activeProject ? activeProject.name : '请选择项目' }}</div></div>
          <el-button :disabled="!activeProject" @click="goTab('reviews')">前往审查 →</el-button>
        </div>
      </template>
      <el-empty v-if="!activeProject" description="还没有选择项目"><p class="empty-extra">去“项目”页创建或选择一个项目。</p></el-empty>
      <el-empty v-else-if="!reports.length" description="暂无审查报告"><p class="empty-extra">绑定仓库并触发一次代码审查吧。</p></el-empty>
      <div v-else class="report-list">
        <button v-for="r in reports.slice(0,6)" :key="r.reportId" class="report-row" @click="openReport(r.reportId)">
          <span class="mono">#{{ r.reportId }}</span>
          <span class="grow-cell"><span class="mono">{{ shortCommit(r.commitId) }}</span> · {{ fmtTime(r.createdAt) }}</span>
          <el-tag round class="report-risk" :class="'risk-' + r.overallRisk">{{ r.overallRisk }}</el-tag>
          <span class="mono">{{ r.issueCount }} 问题</span>
        </button>
      </div>
    </el-card>
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

<style scoped>
/* 本页亮色工作台基线(迁移期 AppShell 仍是 Observatory,基线只挂本视图根) */
.view {
  font-family: var(--rs-font-body);
  color: var(--rs-text);
}

.card-head { display: flex; align-items: center; justify-content: space-between; gap: var(--sp-3); flex-wrap: wrap; }
.card-head h2 { margin: 0; font-family: var(--rs-font-body); font-size: var(--rs-fs-lg); font-weight: 700; color: var(--rs-text); }
.card-sub { margin-top: 3px; color: var(--rs-text-dim); font-size: var(--rs-fs-sm); }

.empty-extra { margin: 0; color: var(--rs-text-dim); font-size: var(--rs-fs-sm); }

.report-list { display: grid; gap: var(--sp-2); }
/* 行承载体保留原生 button(键盘可达);Observatory 元素级 button 皮肤
   迁移期仍全局生效,泄漏属性(底色/阴影/内边距/字重/位移)逐项定值 */
.report-row {
  display: grid; grid-template-columns: 58px 1fr auto auto; align-items: center; gap: var(--sp-3);
  min-height: 0; padding: 11px 14px;
  background: var(--rs-fill-lighter);
  border: 1px solid var(--rs-border-faint);
  border-radius: var(--rs-radius-sm);
  box-shadow: none;
  color: var(--rs-text); font-family: inherit; font-size: var(--rs-fs-base); font-weight: 500; letter-spacing: normal;
  text-align: left; cursor: pointer;
  transition: border-color var(--rs-t-fast) var(--rs-ease-out), background var(--rs-t-fast), box-shadow var(--rs-t-fast);
}
.report-row:hover:not(:disabled) { border-color: var(--el-color-primary-light-5); background: var(--rs-surface); box-shadow: var(--rs-shadow-sm); }
.report-row:active:not(:disabled) { transform: translateY(1px); }
.report-row:focus-visible { outline: 2px solid var(--rs-primary); outline-offset: 1px; box-shadow: none; }
.report-row .mono { font-size: var(--rs-fs-sm); color: var(--rs-text-soft); }
.grow-cell { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

/* 风险徽标:el-tag 外壳 + 站级 risk-* 语义类。迁移期 styles.css 的未分层
   同名类与 el-tag 主题都可能抢色,以 scoped 特异性把四级色钉死在 tokens 上
   (styles.css 退役后与 tokens.css @layer 工具类同值,可随收尾移除)。 */
.report-risk { font-weight: 700; }
.report-risk.risk-HIGH { color: var(--rs-risk-high-text); background: var(--rs-risk-high-bg); border-color: var(--rs-risk-high-border); }
.report-risk.risk-MEDIUM { color: var(--rs-risk-medium-text); background: var(--rs-risk-medium-bg); border-color: var(--rs-risk-medium-border); }
.report-risk.risk-LOW { color: var(--rs-risk-low-text); background: var(--rs-risk-low-bg); border-color: var(--rs-risk-low-border); }
.report-risk.risk-NONE { color: var(--rs-risk-none-text); background: var(--rs-risk-none-bg); border-color: var(--rs-risk-none-border); }
</style>
