<template>
  <el-card class="rs-agent-timeline" shadow="never">
    <template #header>
      <div class="rs-card-head"><h3>Agent Timeline</h3><span class="muted">{{ steps.length }} 个步骤</span></div>
    </template>
    <el-timeline v-if="steps.length" class="rs-timeline">
      <el-timeline-item v-for="step in steps" :key="step.id || step.sequenceNo" :class="'timeline-' + String(step.status || '').toLowerCase()" hide-timestamp>
        <template #dot><span class="timeline-dot" aria-hidden="true"></span></template>
        <div class="step-body"><b>{{ statusLabel(step.status) }}</b> <span class="mono">{{ step.stepType }}</span><small>{{ step.outputSummary || '暂无输出摘要' }}</small></div>
      </el-timeline-item>
    </el-timeline>
    <el-empty v-else description="暂无步骤记录" :image-size="60" />
  </el-card>
</template>
<script setup>
defineProps({ steps: { type: Array, default: () => [] } })
function statusLabel(value) { return { SUCCEEDED: '已完成', COMPLETED: '已完成', SUCCESS: '成功', RUNNING: '运行中', WAITING_EXTERNAL: '等待外部', FAILED: '失败', CANCELED: '已取消' }[value] || value || '未知' }
</script>
<style scoped>
/* Precision Workbench 时间线:el-timeline/el-timeline-item 外壳承载
   (连线/节点槽位/内容排布交给 Element),节点用 #dot 插槽自绘——
   状态色映射与运行中脉冲是产品语义,Element 默认 node 不承载。
   状态类 timeline-* 挂在 el-timeline-item 根 li 上(attrs 透传),
   .timeline-dot 同名重定义压过 styles.css 冻结版。
   根类名弃用旧名 .agent-timeline:冻结版 ol/li 网格规则会击穿
   el-timeline 的 li 结构(18px 两列网格挤扁 wrapper),换 rs- 前缀隔离。 */
.rs-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-3);
  flex-wrap: wrap;
}
.rs-card-head h3 {
  margin: 0;
  font-family: var(--rs-font-body);
  font-size: var(--rs-fs-md);
  font-weight: 700;
  letter-spacing: -0.01em;
  color: var(--rs-text);
}
.muted { color: var(--rs-text-dim); font-size: var(--rs-fs-sm); font-weight: 400; }

/* ---- 时间线容器:收紧 Element 默认 40px 左缩进,连线色走官方变量
   (.el-timeline.is-start 为 (0,2,0),前挂卡片类抬到 (0,3,0) 保证与
   打包顺序无关) ---- */
.rs-agent-timeline .rs-timeline {
  --el-timeline-node-color: var(--rs-border-strong);
  padding-left: var(--sp-2);
}

/* ---- 状态节点(#dot 插槽自绘;尺寸/描边对齐 Observatory 版 14px 圆点,
   -2px 外移让圆心对准 tail 连线中轴 x=5px) ---- */
.timeline-dot {
  display: block;
  width: 14px;
  height: 14px;
  margin-left: -2px;
  border-radius: 50%;
  background: var(--rs-risk-none-solid);
  border: 3px solid var(--rs-surface);
  box-shadow: 0 0 0 1px var(--rs-border);
  transition: background var(--rs-t-base);
}
.timeline-success .timeline-dot,
.timeline-succeeded .timeline-dot,
.timeline-completed .timeline-dot { background: var(--rs-risk-low-solid); box-shadow: 0 0 0 1px var(--rs-risk-low-border); }
.timeline-running .timeline-dot {
  background: var(--rs-primary);
  box-shadow: 0 0 0 1px var(--rs-border), 0 0 0 4px var(--el-color-primary-light-8);
  animation: rs-dot-pulse 1.1s var(--rs-ease-in-out) infinite;
}
.timeline-failed .timeline-dot { background: var(--rs-risk-high-solid); box-shadow: 0 0 0 1px var(--rs-risk-high-border); }
.timeline-waiting_external .timeline-dot { background: var(--rs-risk-medium-solid); box-shadow: 0 0 0 1px var(--rs-risk-medium-border); }

@keyframes rs-dot-pulse {
  0%, 100% { opacity: 1; transform: scale(1); }
  50% { opacity: 0.3; transform: scale(0.82); }
}
@media (prefers-reduced-motion: reduce) {
  .timeline-dot { animation: none !important; }
}

/* ---- 步骤内容(结构与文案原样:状态词 + stepType + 输出摘要小字) ---- */
.step-body { line-height: 1.6; }
.step-body b { color: var(--rs-text); font-weight: 700; }
.step-body .mono {
  font-family: var(--rs-font-mono);
  font-feature-settings: "liga" 0;
  color: var(--rs-text-soft);
  font-size: var(--rs-fs-sm);
}
.step-body small {
  display: block;
  margin-top: 4px;
  color: var(--rs-text-dim);
  font-size: var(--rs-fs-sm);
}
</style>
