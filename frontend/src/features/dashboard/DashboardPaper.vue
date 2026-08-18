<template>
  <div class="workbench-paper">
    <section class="ink-panel workbench-head ink-reveal">
      <div><span class="ink-eyebrow">项目工作台</span><h1>{{ activeProject?.name || '未选择项目' }}</h1>
        <p>{{ activeProject ? '当前成员的有界执行队列、风险摘要与最近活动。' : '请先在项目页选择一个项目。' }}</p></div>
      <span v-if="data" class="role-stamp">{{ data.role }}</span>
    </section>

    <section v-if="error" class="ink-panel state-panel" role="alert">
      <strong>工作台加载失败</strong><p>{{ error }}</p><button class="ink-outline-button" @click="$emit('retry')">重试</button>
    </section>
    <section v-else-if="loading && !data" class="ink-panel state-panel" aria-busy="true">正在装订工作台卷宗…</section>
    <section v-else-if="!activeProject" class="ink-panel state-panel">尚未选择项目。</section>

    <template v-else-if="data">
      <section class="risk-grid ink-reveal" aria-label="风险摘要">
        <article class="ink-panel"><span>Gate 阻断</span><strong>{{ data.riskSummary.gateBlock }}</strong><small>警告 {{ data.riskSummary.gateWarn }} · 通过 {{ data.riskSummary.gatePass }}</small></article>
        <article class="ink-panel"><span>活跃高危 Finding</span><strong>{{ data.riskSummary.activeHighCriticalFindings }}</strong><small>未关闭 HIGH / CRITICAL</small></article>
        <article class="ink-panel"><span>覆盖风险</span><strong>{{ data.riskSummary.unresolvedCoverageWarnings }}</strong><small>NOT_FOUND + AT_RISK</small></article>
        <article class="ink-panel"><span>高风险报告</span><strong>{{ data.riskSummary.highRiskReviewReports }}</strong><small>最近 100 份报告</small></article>
      </section>

      <section class="queue-grid">
        <QueueCard title="我的研发任务" :items="data.requirements" empty="没有待处理研发任务">
          <template #default="{ item }"><button class="queue-row" @click="$emit('open', { ...item, type: 'REQUIREMENT' })"><code>{{ item.code }}</code><span>{{ item.title }}</span><b>{{ item.status }}</b></button></template>
        </QueueCard>
        <QueueCard title="待我处理 Finding" :items="data.findings" empty="没有指派给你的活跃 Finding">
          <template #default="{ item }"><button class="queue-row" @click="$emit('open', { ...item, type: 'FINDING' })"><code>#{{ item.findingId }}</code><span>{{ item.title }}</span><b>{{ item.severity }}</b></button></template>
        </QueueCard>
        <QueueCard title="待审 PR" :items="data.pullRequests" :empty="data.pullRequestQueueNote">
          <template #default="{ item }"><button class="queue-row" @click="$emit('open', { ...item, type: 'PULL_REQUEST' })"><code>#{{ item.prNumber || item.pullRequestId }}</code><span>{{ item.title }}</span><b>{{ item.reviewState }}</b></button></template>
        </QueueCard>
      </section>

      <section class="ink-panel activity-panel ink-reveal">
        <header><div><span class="ink-eyebrow">最近活动</span><h2>事实时间线</h2></div><small>{{ fmtTime(data.generatedAt) }} 生成</small></header>
        <p v-if="!data.recentActivity.length" class="empty-copy">窗口内暂无活动。</p>
        <ol v-else class="activity-list">
          <li v-for="item in data.recentActivity" :key="`${item.targetType}-${item.objectId}-${item.occurredAt}`">
            <button @click="$emit('open', item)"><span>{{ item.label }}</span><b>{{ item.state }}</b><time>{{ fmtTime(item.occurredAt) }}</time></button>
          </li>
        </ol>
      </section>
    </template>
  </div>
</template>

<script setup>
import QueueCard from './QueueCard.vue'
import { fmtTime } from '../../utils/format.js'
defineProps({
  activeProject: { type: Object, default: null }, data: { type: Object, default: null },
  loading: { type: Boolean, default: false }, error: { type: String, default: '' },
})
defineEmits(['retry', 'open'])
</script>

<style scoped>
.workbench-paper { display: grid; gap: var(--ink-sp-24); }
.ink-panel { padding: var(--ink-sp-24); background: var(--ink-wash-panel); border: 1px solid var(--line-soft); border-radius: var(--ink-radius-paper); box-shadow: var(--ink-shadow-panel); }
.workbench-head, .activity-panel header { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--ink-sp-16); }
h1, h2 { margin: var(--ink-sp-4) 0; font-family: var(--ink-font-display); color: var(--ink-strong); }
p { color: var(--ink-muted); }
.role-stamp { padding: var(--ink-sp-8) var(--ink-sp-12); border: 1px solid var(--cinnabar); color: var(--cinnabar); font-weight: 700; }
.risk-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: var(--ink-sp-12); }
.risk-grid article { display: grid; gap: var(--ink-sp-4); }
.risk-grid span, .risk-grid small { color: var(--ink-muted); }
.risk-grid strong { color: var(--ink-strong); font-family: var(--ink-font-display); font-size: var(--ink-fs-32); }
.queue-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: var(--ink-sp-16); }
.queue-row { width: 100%; min-height: 44px; display: grid; grid-template-columns: auto minmax(0, 1fr) auto; gap: var(--ink-sp-8); align-items: center; padding: var(--ink-sp-8); border: 1px solid var(--line-soft); background: var(--surface-paper); color: var(--ink-default); text-align: left; }
.queue-row:hover { border-color: var(--line-strong); }
.queue-row:focus-visible { outline: 2px solid var(--mineral-cyan); outline-offset: 1px; }
.queue-row code { color: var(--ink-strong); }.queue-row span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.queue-row b { color: var(--cinnabar); font-size: var(--ink-fs-12); }
.activity-list { list-style: none; margin: var(--ink-sp-16) 0 0; padding: 0; display: grid; gap: var(--ink-sp-4); }
.activity-list button { width: 100%; min-height: 44px; display: grid; grid-template-columns: 1fr auto auto; gap: var(--ink-sp-12); align-items: center; padding: var(--ink-sp-8) var(--ink-sp-12); border: 0; border-bottom: 1px solid var(--line-soft); background: transparent; color: var(--ink-default); text-align: left; }
.activity-list time, .activity-list b, .empty-copy { color: var(--ink-muted); font-size: var(--ink-fs-12); }
@media (max-width: 1000px) { .risk-grid { grid-template-columns: repeat(2, 1fr); } .queue-grid { grid-template-columns: 1fr; } }
@media (max-width: 560px) { .risk-grid { grid-template-columns: 1fr; } .workbench-head { display: grid; } .activity-list button { grid-template-columns: 1fr auto; }.activity-list time { grid-column: 1 / -1; } }
</style>
