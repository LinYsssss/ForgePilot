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
