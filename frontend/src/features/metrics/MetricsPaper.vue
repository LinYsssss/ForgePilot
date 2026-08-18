<template>
  <div class="metrics-paper">
    <section class="ink-panel metrics-head">
      <div><span class="ink-eyebrow">固定窗口研发度量</span><h1>研发度量</h1><p>数据库事实源；零值、无样本、解析排除与请求失败分别呈现。</p></div>
      <div class="window-tabs" aria-label="度量窗口"><button v-for="value in windows" :key="value" :class="{ active: value === windowValue }" @click="$emit('window', value)">{{ value }}</button></div>
    </section>
    <section v-if="error" class="ink-panel state error" role="alert"><strong>度量加载失败</strong><p>{{ error }}</p><button class="ink-outline-button" @click="$emit('retry')">重试</button></section>
    <section v-else-if="loading && !data" class="ink-panel state" aria-busy="true">正在计算固定窗口指标…</section>
    <section v-else-if="!data" class="ink-panel state">选择项目后显示研发度量。</section>
    <template v-else>
      <nav class="section-tabs" aria-label="度量分区"><button v-for="item in sections" :key="item.key" :class="{ active: item.key === section }" @click="$emit('section', item.key)">{{ item.label }}</button></nav>
      <p class="meta-line">{{ fmtTime(data.from) }} — {{ fmtTime(data.to) }} · 排除 {{ data.excludedRecords }} 条<span v-if="data.truncated"> · 样本已截断</span></p>

      <section v-if="section === 'quality'" class="metric-grid">
        <MetricCard title="Gate" :sample="data.developmentQuality.sampleCount" :items="[['PASS', data.developmentQuality.gates.pass], ['WARN', data.developmentQuality.gates.warn], ['BLOCK', data.developmentQuality.gates.block], ['UNKNOWN', data.developmentQuality.gates.unknown]]" />
        <MetricCard title="Finding" :sample="data.developmentQuality.findings.totalVerified" :items="[['活跃', data.developmentQuality.findings.active], ['活跃高危', data.developmentQuality.findings.activeHighCritical], ['闭环', data.developmentQuality.findings.terminal], ['闭环率', formatRate(data.developmentQuality.findings.closureRate)]]" />
        <MetricCard title="AC Coverage" :sample="data.developmentQuality.coverage.covered + data.developmentQuality.coverage.notFound + data.developmentQuality.coverage.atRisk" :items="[['COVERED', data.developmentQuality.coverage.covered], ['NOT_FOUND', data.developmentQuality.coverage.notFound], ['AT_RISK', data.developmentQuality.coverage.atRisk], ['解析排除', data.developmentQuality.coverage.excludedRecords]]" />
      </section>

      <section v-else-if="section === 'requirements'" class="metric-grid">
        <MetricCard title="需求状态" :sample="data.requirementQuality.requirements.total" :items="metricEntries(data.requirementQuality.requirements.byStatus)" />
        <MetricCard title="验收标准" :sample="data.requirementQuality.requirements.total" :items="[['AC 总数', data.requirementQuality.requirements.totalAcs], ['平均 AC', data.requirementQuality.requirements.averageAcs ?? '无样本'], ['已体检需求', data.requirementQuality.requirements.checkedRequirements], ['体检覆盖率', formatRate(data.requirementQuality.requirements.checkCoverageRate)]]" />
        <MetricCard title="六维问题" :sample="data.requirementQuality.checks.latestReports" :items="metricEntries(data.requirementQuality.checks.itemsByDimension)" :footer="`解析排除 ${data.requirementQuality.checks.excludedRecords}`" />
        <MetricCard title="问题严重度" :sample="data.requirementQuality.checks.latestReports" :items="metricEntries(data.requirementQuality.checks.itemsBySeverity)" />
      </section>

      <section v-else-if="section === 'efficiency'" class="metric-grid">
        <DurationCard title="交互式审查" :metric="data.efficiency.interactiveReview" />
        <DurationCard title="Agent 端到端" :metric="data.efficiency.agentTurnaround" />
        <DurationCard title="Finding 验证" :metric="data.efficiency.findingVerification" />
      </section>

      <section v-else class="metric-grid ai-summary">
        <MetricCard title="AI 调用" :sample="data.ai.sampleCount" :items="[['调用', data.ai.calls], ['成功', data.ai.successes], ['失败', data.ai.failures], ['成功率', formatRate(data.ai.successRate)]]" />
        <MetricCard title="Token / 延迟" :sample="data.ai.sampleCount" :items="[['总 tokens', data.ai.totalTokens], ['平均延迟', formatDuration(data.ai.averageLatencyMs)], ['P95 延迟', formatDuration(data.ai.p95LatencyMs)], ['截断', data.ai.truncated ? '是' : '否']]" />
        <MetricCard title="请求类型" :sample="data.ai.sampleCount" :items="data.ai.byRequestType.map(item => [item.requestType, item.calls])" />
      </section>
      <slot name="ai-logs" v-if="section === 'ai'" />
    </template>
  </div>
</template>
<script setup>
import MetricCard from './MetricCard.vue'
import DurationCard from './DurationCard.vue'
import { formatDuration, formatRate, metricEntries } from './metricsModel.js'
import { fmtTime } from '../../utils/format.js'
defineProps({ data: { type: Object, default: null }, loading: Boolean, error: { type: String, default: '' }, windowValue: { type: String, default: '30d' }, windows: { type: Array, default: () => [] }, section: { type: String, default: 'quality' }, sections: { type: Array, default: () => [] } })
defineEmits(['retry','window','section'])
</script>
<style scoped>
.metrics-paper{display:grid;gap:var(--ink-sp-16)}.ink-panel{padding:var(--ink-sp-24);background:var(--ink-wash-panel);border:1px solid var(--line-soft);border-radius:var(--ink-radius-paper);box-shadow:var(--ink-shadow-panel)}.metrics-head{display:flex;justify-content:space-between;align-items:flex-start;gap:var(--ink-sp-16)}h1{margin:var(--ink-sp-4) 0;font-family:var(--ink-font-display)}p,.meta-line{color:var(--ink-muted)}.window-tabs,.section-tabs{display:flex;flex-wrap:wrap;gap:var(--ink-sp-4)}.window-tabs button,.section-tabs button{min-height:44px;padding:var(--ink-sp-8) var(--ink-sp-12);border:1px solid var(--line-soft);background:var(--surface-paper);color:var(--ink-default)}.window-tabs button.active,.section-tabs button.active{border-color:var(--cinnabar);color:var(--cinnabar);font-weight:700}.metric-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:var(--ink-sp-16)}.state.error{color:var(--cinnabar)}@media(max-width:1000px){.metric-grid{grid-template-columns:1fr 1fr}}@media(max-width:640px){.metrics-head{display:grid}.metric-grid{grid-template-columns:1fr}}
</style>
