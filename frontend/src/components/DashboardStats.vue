<template>
  <div class="stat-grid">
    <div class="stat"><span class="spark" aria-hidden="true">▤</span><div class="label">项目总数</div><div class="value">{{ projectDisplay }}</div></div>
    <div class="stat"><span class="spark" aria-hidden="true">✓</span><div class="label">审查任务</div><div class="value">{{ taskDisplay }}</div></div>
    <div class="stat"><span class="spark" aria-hidden="true">▦</span><div class="label">审查报告</div><div class="value">{{ reportDisplay }}</div></div>
    <div class="stat">
      <span class="spark" aria-hidden="true">⚠</span>
      <div class="label">高风险报告</div>
      <div class="value" :class="highRisk ? 'tinted-high' : 'tinted-ok'">{{ highRiskDisplay }}</div>
    </div>
  </div>
</template>

<script setup>
// 概览 KPI 磁贴(纯展示,数值经 props 传入,进场/变化时数字滚动)。
import { toRef } from 'vue'
import { useCountUp } from '../composables/useCountUp.js'

const props = defineProps({
  projectCount: { type: Number, default: 0 },
  taskCount: { type: Number, default: 0 },
  reportCount: { type: Number, default: 0 },
  highRisk: { type: Number, default: 0 },
})

const projectDisplay = useCountUp(toRef(props, 'projectCount'))
const taskDisplay = useCountUp(toRef(props, 'taskCount'))
const reportDisplay = useCountUp(toRef(props, 'reportCount'))
const highRiskDisplay = useCountUp(toRef(props, 'highRisk'))
</script>

<style scoped>
/* Precision Workbench 磁贴:tokens 手铸白卡(延续登录页克制描边风) */
.stat-grid { display: grid; gap: var(--sp-4); grid-template-columns: repeat(auto-fit, minmax(168px, 1fr)); margin-bottom: var(--sp-5); }
.stat {
  position: relative;
  overflow: hidden;
  padding: var(--sp-5);
  background: var(--rs-surface);
  border: 1px solid var(--rs-border);
  border-radius: var(--rs-radius-base);
  box-shadow: var(--rs-shadow-sm);
  animation: stat-in var(--rs-t-slow) var(--rs-ease-out) both;
  transition: border-color var(--rs-t-base) var(--rs-ease-out), box-shadow var(--rs-t-base) var(--rs-ease-out), transform var(--rs-t-base) var(--rs-ease-out);
}
.stat:nth-child(2) { animation-delay: 50ms; }
.stat:nth-child(3) { animation-delay: 100ms; }
.stat:nth-child(4) { animation-delay: 150ms; }
/* hover 微升只给指针设备;触屏上 hover 粘滞反而脏 */
@media (hover: hover) {
  .stat:hover { border-color: var(--rs-border-strong); box-shadow: var(--rs-shadow-md); transform: translateY(-3px); }
}

.label { color: var(--rs-text-dim); font-size: var(--rs-fs-xs); font-weight: 700; text-transform: uppercase; letter-spacing: 0.07em; }
.value { margin-top: var(--sp-2); font-family: var(--rs-font-body); font-size: var(--rs-fs-3xl); font-weight: 800; letter-spacing: -0.02em; line-height: 1; color: var(--rs-text); }
.value.tinted-high { color: var(--rs-risk-high-text); }
.value.tinted-ok { color: var(--rs-risk-low-text); }
.spark { position: absolute; right: var(--sp-4); top: var(--sp-4); font-size: var(--rs-fs-lg); opacity: 0.45; color: var(--rs-text-dim); }

@keyframes stat-in { from { opacity: 0; } to { opacity: 1; } }
@media (prefers-reduced-motion: reduce) {
  .stat { animation: none; }
}
</style>
