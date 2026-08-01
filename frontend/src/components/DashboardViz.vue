<template>
  <div v-if="reports.length" class="viz-row">
    <div class="panel viz">
      <div class="panel-head"><div><h2>风险分布</h2><div class="sub">按报告总体风险</div></div></div>
      <div class="donut-wrap">
        <svg class="donut" viewBox="0 0 120 120" role="img" :aria-label="`风险分布，共 ${reports.length} 份报告`">
          <circle class="donut-track" cx="60" cy="60" r="50" />
          <circle v-for="s in riskDonut" :key="s.key" class="donut-seg" cx="60" cy="60" r="50"
            :stroke-dasharray="ready ? `${s.dash} ${s.C - s.dash}` : `0.01 ${s.C}`" :stroke-dashoffset="s.offset"
            :style="{ stroke: s.color }" transform="rotate(-90 60 60)"
            @mouseenter="showTip($event, `${s.label}风险 · ${s.count} (${s.pct}%)`)" @mousemove="moveTip" @mouseleave="hideTip" />
          <text class="donut-total" x="60" y="57">{{ reports.length }}</text>
          <text class="donut-cap" x="60" y="71">报告</text>
        </svg>
        <div class="legend">
          <button v-for="s in riskDistribution" :key="s.key" class="legend-item"
            @mouseenter="showTip($event, `${s.label}风险 · ${s.count}`)" @mousemove="moveTip" @mouseleave="hideTip">
            <span class="dot" :style="{ background: s.color }"></span>
            <span class="lg-label">{{ s.label }}风险</span>
            <span class="lg-count mono">{{ s.count }}</span>
          </button>
        </div>
      </div>
    </div>
    <div class="panel viz">
      <div class="panel-head">
        <div><h2>审查活动</h2><div class="sub">近 {{ activitySeries.length }} 次审查 · 问题数</div></div>
        <span class="badge plain">累计 {{ totalIssues }} 问题</span>
      </div>
      <div class="cols">
        <div v-for="(a, i) in activitySeries" :key="a.reportId" class="col-cell"
          @mouseenter="showTip($event, `${a.when} · ${a.issues} 问题`)" @mousemove="moveTip" @mouseleave="hideTip">
          <div class="col-bar" :style="{ height: Math.max(a.h, 6) + '%', animationDelay: (i * 45) + 'ms' }"></div>
        </div>
      </div>
    </div>
    <div v-show="vizTip.show" class="viz-tip" :style="{ left: vizTip.x + 'px', top: vizTip.y + 'px' }">{{ vizTip.text }}</div>
  </div>
</template>

<script setup>
// 概览仪表盘可视化(风险分布环形 + 审查活动柱)。纯展示:只吃 reports prop,无副作用。
// 样式沿用全局 styles.css 的类(.viz-row/.donut/.cols…),故无需 scoped style。
import { computed, onMounted, reactive, ref } from 'vue'
import { fmtTime } from '../utils/format.js'

const props = defineProps({ reports: { type: Array, default: () => [] } })

// 环形段先以 0 长度渲染,mount 后置位真实弧长 → CSS transition 完成"绘制入场"。
// reduced-motion 下全局块把 transition 压到 0.001ms,等价直达终态。
const ready = ref(false)
onMounted(() => { requestAnimationFrame(() => { ready.value = true }) })

const RISK_META = [
  { key: 'HIGH', label: '高', color: 'var(--risk-high)' },
  { key: 'MEDIUM', label: '中', color: 'var(--risk-medium)' },
  { key: 'LOW', label: '低', color: 'var(--risk-low)' },
  { key: 'NONE', label: '无', color: 'var(--risk-none)' },
]
const totalIssues = computed(() => props.reports.reduce((s, r) => s + (r.issueCount || 0), 0))
const riskDistribution = computed(() => {
  const counts = {}
  for (const r of props.reports) counts[r.overallRisk] = (counts[r.overallRisk] || 0) + 1
  return RISK_META.map(m => ({ ...m, count: counts[m.key] || 0 })).filter(m => m.count > 0)
})
const riskDonut = computed(() => {
  const total = props.reports.length || 1
  const C = 2 * Math.PI * 50
  const GAP = riskDistribution.value.length > 1 ? (1.4 / 100) * C : 0 // 段间 ~2px 视觉间隙
  let cum = 0
  return riskDistribution.value.map(m => {
    const frac = m.count / total
    const seg = { ...m, pct: Math.round(frac * 100), dash: Math.max(frac * C - GAP, 2), offset: -cum, C }
    cum += frac * C
    return seg
  })
})
const activitySeries = computed(() => {
  const rs = [...props.reports]
    .sort((a, b) => new Date(a.createdAt) - new Date(b.createdAt))
    .slice(-14)
  const max = Math.max(1, ...rs.map(r => r.issueCount || 0))
  return rs.map(r => ({
    reportId: r.reportId,
    issues: r.issueCount || 0,
    when: fmtTime(r.createdAt),
    h: Math.round(((r.issueCount || 0) / max) * 100),
  }))
})
const vizTip = reactive({ show: false, x: 0, y: 0, text: '' })
function showTip(e, text) { vizTip.text = text; vizTip.x = e.clientX; vizTip.y = e.clientY; vizTip.show = true }
function moveTip(e) { vizTip.x = e.clientX; vizTip.y = e.clientY }
function hideTip() { vizTip.show = false }
</script>
