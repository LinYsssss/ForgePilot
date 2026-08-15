<template>
  <div class="ink-dashboard">
    <section class="ink-panel dash-head ink-reveal">
      <div>
        <span class="ink-eyebrow">书院总览</span>
        <h1>{{ activeProject ? activeProject.name : '未选择项目' }}</h1>
        <p class="head-sub">
          {{ activeProject ? '当前项目的卷宗规模与最近审定结果' : '请先在「项目」页选择或创建一个项目。' }}
        </p>
      </div>
      <dl class="dash-stats">
        <div><dt>项目</dt><dd>{{ projects.length }}</dd></div>
        <div><dt>审查任务</dt><dd>{{ tasks.length }}</dd></div>
        <div><dt>报告</dt><dd>{{ reports.length }}</dd></div>
        <div :class="{ 'is-alert': highRiskCount > 0 }"><dt>高风险</dt><dd>{{ highRiskCount }}</dd></div>
      </dl>
    </section>

    <section class="ink-panel dash-reports ink-reveal">
      <header class="reports-head">
        <div><span class="ink-eyebrow">最近审查</span><h2>审定卷宗</h2></div>
        <button class="ink-outline-button" :disabled="!activeProject" @click="$emit('go', 'reviews')">
          前往审查记录
        </button>
      </header>

      <p v-if="!activeProject" class="dash-empty">还没有选择项目——去「项目」页创建或选择一个。</p>
      <p v-else-if="!reports.length" class="dash-empty">暂无审查报告。绑定仓库并触发一次代码审查即可。</p>
      <ul v-else class="report-list">
        <li v-for="report in reports.slice(0, 6)" :key="report.reportId">
          <button class="report-row" @click="$emit('open-report', report.reportId)">
            <code class="report-id">#{{ report.reportId }}</code>
            <span class="report-meta">
              <code>{{ shortCommit(report.commitId) }}</code>
              <span class="report-time">{{ fmtTime(report.createdAt) }}</span>
            </span>
            <SealBadge :tone="resolveSealTone(report.overallRisk)" :label="report.overallRisk" />
            <span class="report-count">{{ report.issueCount }} 问题</span>
          </button>
        </li>
      </ul>
    </section>
  </div>
</template>

<script setup>
import SealBadge from '../../shared/ui/SealBadge.vue'
import { resolveSealTone } from '../../shared/ui/sealTone.js'
import { fmtTime, shortCommit } from '../../utils/format.js'

// 墨境总览(实施步骤 8 第一页)。纯展示:数据由页面从既有 composable 取好后传入,
// 风险色一律经 resolveSealTone 映射到印记色阶,不在本组件里另立一套风险配色——
// 旧 DashboardView 曾为此在 scoped 样式里钉死四级色以对抗 Element Plus 主题,
// 迁到墨境后该问题从根上消失(不再有 el-tag 参与)。
defineProps({
  projects: { type: Array, default: () => [] },
  tasks: { type: Array, default: () => [] },
  reports: { type: Array, default: () => [] },
  highRiskCount: { type: Number, default: 0 },
  activeProject: { type: Object, default: null },
})
defineEmits(['go', 'open-report'])
</script>

<style scoped>
/* 面板样式与 PaperWorkspace 同值(合同 §3 令牌;.ink-panel 是各组件各自声明的约定,
   不是全局类——这里保持同一组令牌,视觉才与工作台一致) */
.ink-panel {
  padding: var(--ink-sp-24);
  background: var(--ink-wash-panel);
  border: 1px solid var(--line-soft);
  border-radius: var(--ink-radius-paper);
  box-shadow: var(--ink-shadow-panel);
}

.ink-dashboard { display: grid; gap: var(--ink-sp-16); }

.dash-head { display: flex; flex-wrap: wrap; gap: var(--ink-sp-24); align-items: flex-start; justify-content: space-between; }
.dash-head h1 { margin: var(--ink-sp-4) 0 0; font-family: var(--ink-font-display); font-size: var(--ink-fs-28); color: var(--ink-strong); }
.head-sub { margin: var(--ink-sp-8) 0 0; color: var(--ink-muted); }

.dash-stats { display: flex; flex-wrap: wrap; gap: var(--ink-sp-24); margin: 0; }
.dash-stats div { min-width: 84px; }
.dash-stats dt { color: var(--ink-muted); font-size: var(--ink-fs-13); }
.dash-stats dd { margin: var(--ink-sp-4) 0 0; font-size: var(--ink-fs-28); font-variant-numeric: tabular-nums; color: var(--ink-strong); }
.dash-stats .is-alert dd { color: var(--cinnabar); }

.reports-head { display: flex; align-items: center; justify-content: space-between; gap: var(--ink-sp-12); flex-wrap: wrap; }
.reports-head h2 { margin: var(--ink-sp-4) 0 0; font-family: var(--ink-font-display); font-size: var(--ink-fs-20); color: var(--ink-strong); }

.dash-empty { margin: var(--ink-sp-16) 0 0; color: var(--ink-muted); }

.report-list { list-style: none; margin: var(--ink-sp-12) 0 0; padding: 0; display: grid; gap: var(--ink-sp-8); }
.report-row {
  width: 100%;
  display: grid;
  grid-template-columns: auto 1fr auto auto;
  align-items: center;
  gap: var(--ink-sp-12);
  padding: var(--ink-sp-8) var(--ink-sp-12);
  background: var(--surface-paper);
  border: 1px solid var(--line-soft);
  border-radius: var(--ink-radius-control);
  color: var(--ink-default);
  font: inherit;
  text-align: left;
  cursor: pointer;
  transition: border-color var(--ink-t-hover) var(--ink-ease-out), background var(--ink-t-hover);
}
.report-row:hover { border-color: var(--line-strong); background: var(--surface-raised); }
.report-row:focus-visible { outline: 2px solid var(--mineral-cyan); outline-offset: 2px; }
.report-id { color: var(--ink-muted); font-family: var(--ink-font-code); }
.report-meta { display: flex; gap: var(--ink-sp-8); align-items: baseline; min-width: 0; }
.report-meta code { font-family: var(--ink-font-code); }
.report-time { color: var(--ink-muted); font-size: var(--ink-fs-13); white-space: nowrap; }
.report-count { color: var(--ink-muted); font-size: var(--ink-fs-13); white-space: nowrap; }

@media (max-width: 767px) {
  .report-row { grid-template-columns: auto 1fr; row-gap: var(--ink-sp-8); }
}
</style>
