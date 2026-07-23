<template>
  <div class="stat-grid">
    <div class="stat"><span class="spark" aria-hidden="true">▤</span><div class="label">项目总数</div><div class="value">{{ projectCount }}</div></div>
    <div class="stat"><span class="spark" aria-hidden="true">✓</span><div class="label">审查任务</div><div class="value">{{ taskCount }}</div></div>
    <div class="stat"><span class="spark" aria-hidden="true">▦</span><div class="label">审查报告</div><div class="value">{{ reportCount }}</div></div>
    <div class="stat">
      <span class="spark" aria-hidden="true">⚠</span>
      <div class="label">高风险报告</div>
      <div class="value" :class="highRisk ? 'tinted-high' : 'tinted-ok'">{{ highRisk }}</div>
    </div>
  </div>
</template>

<script setup>
// 概览 KPI 磁贴(纯展示,数值经 props 传入)。样式沿用全局 styles.css 的 .stat-grid/.stat。
defineProps({
  projectCount: { type: Number, default: 0 },
  taskCount: { type: Number, default: 0 },
  reportCount: { type: Number, default: 0 },
  highRisk: { type: Number, default: 0 },
})
</script>
