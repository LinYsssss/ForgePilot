<template>
  <section class="panel agent-timeline">
    <div class="panel-head"><h3>Agent Timeline</h3><span class="muted">{{ steps.length }} 个步骤</span></div>
    <ol>
      <li v-for="step in steps" :key="step.id || step.sequenceNo" :class="'timeline-' + String(step.status || '').toLowerCase()">
        <span class="timeline-dot" aria-hidden="true"></span>
        <div><b>{{ statusLabel(step.status) }}</b> <span class="mono">{{ step.stepType }}</span><small>{{ step.outputSummary || '暂无输出摘要' }}</small></div>
      </li>
    </ol>
    <div v-if="!steps.length" class="empty compact">暂无步骤记录</div>
  </section>
</template>
<script setup>
defineProps({ steps: { type: Array, default: () => [] } })
function statusLabel(value) { return { SUCCEEDED: '已完成', COMPLETED: '已完成', SUCCESS: '成功', RUNNING: '运行中', WAITING_EXTERNAL: '等待外部', FAILED: '失败', CANCELED: '已取消' }[value] || value || '未知' }
</script>
