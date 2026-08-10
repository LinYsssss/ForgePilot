<template>
  <div v-if="reports.length" class="viz-row">
    <el-card class="viz-card" shadow="never">
      <template #header>
        <div class="viz-head"><div><h2>风险分布</h2><div class="viz-sub">按报告总体风险</div></div></div>
      </template>
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
    </el-card>
    <el-card class="viz-card" shadow="never">
      <template #header>
        <div class="viz-head">
          <div><h2>审查活动</h2><div class="viz-sub">近 {{ activitySeries.length }} 次审查 · 问题数</div></div>
          <span class="viz-count mono">累计 {{ totalIssues }} 问题</span>
        </div>
      </template>
      <div class="cols">
        <div v-for="(a, i) in activitySeries" :key="a.reportId" class="col-cell"
          @mouseenter="showTip($event, `${a.when} · ${a.issues} 问题`)" @mousemove="moveTip" @mouseleave="hideTip">
          <div class="col-bar" :style="{ height: Math.max(a.h, 6) + '%', animationDelay: (i * 45) + 'ms' }"></div>
        </div>
      </div>
    </el-card>
    <div v-show="vizTip.show" class="viz-tip" :style="{ left: vizTip.x + 'px', top: vizTip.y + 'px' }">{{ vizTip.text }}</div>
  </div>
</template>

<script setup>
// 概览仪表盘可视化(风险分布环形 + 审查活动柱)。纯展示:只吃 reports prop,无副作用。
// 手绘 SVG 实现保留(PRD 禁止引入图表库),配色走 design tokens;
// 外壳为 el-card,图形与提示样式收在本组件 scoped。
import { computed, onMounted, reactive, ref } from 'vue'
import { fmtTime } from '../utils/format.js'

const props = defineProps({ reports: { type: Array, default: () => [] } })

// 环形段先以 0 长度渲染,mount 后置位真实弧长 → CSS transition 完成"绘制入场"。
// reduced-motion 下 transition 压到 0.001ms,等价直达终态。
const ready = ref(false)
onMounted(() => { requestAnimationFrame(() => { ready.value = true }) })

const RISK_META = [
  { key: 'HIGH', label: '高', color: 'var(--rs-risk-high-solid)' },
  { key: 'MEDIUM', label: '中', color: 'var(--rs-risk-medium-solid)' },
  { key: 'LOW', label: '低', color: 'var(--rs-risk-low-solid)' },
  { key: 'NONE', label: '无', color: 'var(--rs-risk-none-solid)' },
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

<style scoped>
.viz-row { display: grid; grid-template-columns: minmax(230px, 0.85fr) 1.4fr; gap: var(--sp-4); margin-bottom: var(--sp-5); }
@media (max-width: 760px) { .viz-row { grid-template-columns: 1fr; } }

.viz-head { display: flex; align-items: center; justify-content: space-between; gap: var(--sp-3); flex-wrap: wrap; }
.viz-head h2 { margin: 0; font-family: var(--rs-font-body); font-size: var(--rs-fs-lg); font-weight: 700; color: var(--rs-text); }
.viz-sub { margin-top: 3px; color: var(--rs-text-dim); font-size: var(--rs-fs-sm); }
.viz-count { display: inline-flex; align-items: center; padding: 3px 10px; border-radius: var(--rs-radius-round); background: var(--rs-fill); color: var(--rs-text-soft); font-size: var(--rs-fs-xs); font-weight: 600; white-space: nowrap; }

/* ---- 风险分布环形(手绘 SVG) ---- */
.donut-wrap { display: flex; align-items: center; gap: var(--sp-5); flex-wrap: wrap; }
.donut { width: 128px; height: 128px; flex: none; animation: donut-in var(--rs-t-slow) var(--rs-ease-out) both; }
.donut-track { fill: none; stroke: var(--rs-fill); stroke-width: 12; }
.donut-seg { fill: none; stroke-width: 12; stroke-linecap: butt; cursor: default; transition: stroke-width var(--rs-t-fast) var(--rs-ease-out), stroke-dasharray 0.9s var(--rs-ease-out); }
.donut-seg:hover { stroke-width: 15; }
.donut-total { fill: var(--rs-text); font-family: var(--rs-font-body); font-size: 27px; font-weight: 800; text-anchor: middle; letter-spacing: -0.02em; }
.donut-cap { fill: var(--rs-text-dim); font-size: 10.5px; text-anchor: middle; letter-spacing: 0.14em; }

/* 图例为原生 button(悬浮联动提示);Observatory 元素级 button 皮肤
   迁移期仍全局生效,泄漏属性逐项定值 */
.legend { display: flex; flex-direction: column; gap: 2px; min-width: 118px; flex: 1; }
.legend-item {
  display: flex; align-items: center; gap: var(--sp-2);
  min-height: 0; padding: 5px 7px;
  background: none; border: 0; border-radius: var(--rs-radius-sm); box-shadow: none;
  color: var(--rs-text-soft); font-family: inherit; font-size: var(--rs-fs-sm); font-weight: 500; letter-spacing: normal;
  cursor: default; transition: background var(--rs-t-fast);
}
.legend-item:hover { background: var(--rs-fill-lighter); box-shadow: none; }
.legend-item:active { transform: none; }
.legend-item:focus-visible { outline: 2px solid var(--rs-primary); outline-offset: 1px; box-shadow: none; }
.legend .dot { width: 10px; height: 10px; border-radius: 3px; flex: none; }
.legend .lg-label { flex: 1; text-align: left; }
.legend .lg-count { color: var(--rs-text); font-weight: 700; }

/* ---- 审查活动柱(手绘) ---- */
.cols { display: flex; align-items: flex-end; gap: 5px; height: 128px; padding-top: var(--sp-3); }
.col-cell { flex: 1 1 0; max-width: 46px; height: 100%; display: flex; align-items: flex-end; justify-content: center; cursor: default; }
.col-bar { width: 100%; min-height: 4px; background: var(--el-color-primary); border-radius: 3px 3px 0 0; transition: filter var(--rs-t-fast); animation: col-grow var(--rs-t-slow) var(--rs-ease-out) both; transform-origin: bottom; }
.col-cell:hover .col-bar { filter: brightness(1.15); }

/* ---- 悬浮提示(深面反白,tokens 直供) ---- */
.viz-tip {
  position: fixed; z-index: var(--rs-z-toast);
  transform: translate(-50%, calc(-100% - 12px));
  background: var(--rs-text); color: var(--rs-surface);
  border-radius: var(--rs-radius-sm); padding: 5px 9px;
  font-size: var(--rs-fs-sm); font-family: var(--rs-font-mono);
  white-space: nowrap; pointer-events: none; box-shadow: var(--rs-shadow-md);
}

@keyframes donut-in { from { opacity: 0; transform: scale(0.93) rotate(-8deg); } to { opacity: 1; transform: none; } }
@keyframes col-grow { from { transform: scaleY(0.02); opacity: 0.25; } to { transform: scaleY(1); opacity: 1; } }
@media (prefers-reduced-motion: reduce) {
  .donut, .col-bar { animation: none; }
  .donut-seg { transition-duration: 0.001ms; }
}
</style>
