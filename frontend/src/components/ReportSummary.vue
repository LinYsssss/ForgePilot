<template>
  <div class="report-summary">
    <div class="risk-dial" :class="'r-' + report.overallRisk">{{ report.overallRisk }}</div>
    <div class="rs-body">
      <h3>{{ report.summary || '审查完成' }}</h3>
      <div class="rs-meta">
        <span class="sev-tally risk-NONE">共 {{ report.issues.length }} 问题</span>
        <span v-if="severityTally.HIGH" class="sev-tally risk-HIGH">{{ severityTally.HIGH }} 高危</span>
        <span v-if="severityTally.MEDIUM" class="sev-tally risk-MEDIUM">{{ severityTally.MEDIUM }} 中危</span>
        <span v-if="severityTally.LOW" class="sev-tally risk-LOW">{{ severityTally.LOW }} 低危</span>
      </div>
      <div class="sev-strip" v-if="severityStrip.length" role="img" aria-label="严重度分布">
        <span v-for="seg in severityStrip" :key="seg.key" class="sev-seg" :style="{ width: seg.pct + '%', background: seg.color }" :title="`${seg.label}危 ${seg.count}`"></span>
      </div>
    </div>
  </div>
</template>

<script setup>
// 审查报告摘要(纯展示):风险表盘 + 严重度统计 + 分布条。样式沿用全局 styles.css。
import { computed } from 'vue'

const props = defineProps({ report: { type: Object, required: true } })

const SEV_META = [
  { key: 'HIGH', label: '高', color: 'var(--risk-high)' },
  { key: 'MEDIUM', label: '中', color: 'var(--risk-medium)' },
  { key: 'LOW', label: '低', color: 'var(--risk-low)' },
]
const severityTally = computed(() => {
  const t = { HIGH: 0, MEDIUM: 0, LOW: 0, NONE: 0 }
  for (const i of (props.report.issues || [])) t[i.severity] = (t[i.severity] || 0) + 1
  return t
})
const severityStrip = computed(() => {
  const t = severityTally.value
  const total = SEV_META.reduce((s, m) => s + (t[m.key] || 0), 0) || 1
  return SEV_META.map(m => ({ ...m, count: t[m.key] || 0, pct: (t[m.key] || 0) / total * 100 })).filter(m => m.count > 0)
})
</script>
