<template>
  <div class="ink-brush-progress">
    <svg class="ink-brush-track" viewBox="0 0 100 4" preserveAspectRatio="none" aria-hidden="true">
      <path class="track" d="M2 2 H98" pathLength="1" />
      <path class="ink-brush-stroke" d="M2 2 H98" pathLength="1" :style="{ 'stroke-dashoffset': 1 - model.fraction }" />
    </svg>
    <ol class="ink-brush-steps">
      <li v-for="step in model.steps" :key="step.key" :class="step.status" :aria-current="step.status === 'current' ? 'step' : undefined">
        <i aria-hidden="true">{{ step.status === 'done' ? '✓' : step.glyph }}</i>
        <strong>{{ step.label }}</strong>
        <small>{{ step.meta }}</small>
      </li>
    </ol>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { progressModel } from './progressModel.js'

// 笔触进度(合同 §5 BrushProgress / §7 Brush progress):SVG 描线表达
// 阶段推进,描线只在 current 变化时过渡一次(CSS transition 天然
// 只响应变化,320ms ∈ 合同 240–420ms);reduced/static 下即时呈现。
// 每个步骤都有印记字 + 文本 + meta(时间/状态),不依赖颜色单独编码。
const props = defineProps({
  steps: { type: Array, default: () => [] }, // [{key,label,glyph,meta}]
  current: { type: Number, default: 0 },     // 进行中步骤下标;=steps.length 表示全部完成
})

const model = computed(() => progressModel(props.steps, props.current))
</script>

<style scoped>
.ink-brush-progress { position: relative; }

.ink-brush-track {
  position: absolute;
  top: 17px;
  left: 7%;
  right: 7%;
  width: 86%;
  height: 4px;
}
.ink-brush-track path {
  fill: none;
  stroke-width: 2.6;
  stroke-linecap: round;
}
.ink-brush-track .track { stroke: var(--line-strong); }
.ink-brush-stroke {
  stroke: var(--success-ink);
  stroke-dasharray: 1;
  transition: stroke-dashoffset var(--ink-t-brush) var(--ink-ease-out);
}

.ink-brush-steps {
  position: relative;
  z-index: 1;
  display: grid;
  grid-auto-flow: column;
  grid-auto-columns: 1fr;
  margin: 0;
  padding: 0;
  list-style: none;
}
.ink-brush-steps li {
  display: grid;
  justify-items: center;
  gap: 5px;
  min-width: 0;
  text-align: center;
}
.ink-brush-steps i {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  color: var(--ink-muted);
  background: var(--surface-paper);
  border: 1px solid var(--line-strong);
  border-radius: 50%;
  font-family: var(--ink-font-display);
  font-style: normal;
  font-size: var(--ink-fs-13);
}
.ink-brush-steps li.done i {
  color: var(--ink-on-accent);
  background: var(--success-ink);
  border-color: var(--success-ink);
}
.ink-brush-steps li.current i {
  color: var(--ink-on-accent);
  background: var(--mineral-cyan);
  border-color: var(--mineral-cyan);
  box-shadow: 0 0 0 5px var(--ink-glow-cyan);
}
.ink-brush-steps strong {
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--ink-strong);
  font-size: var(--ink-fs-12);
}
.ink-brush-steps li.pending strong { color: var(--ink-muted); font-weight: 500; }
.ink-brush-steps small { color: var(--ink-muted); font-size: var(--ink-fs-12); }

/* 窄屏:横向滚动而不是挤压(合同 §6 long-content) */
@media (max-width: 880px) {
  .ink-brush-progress { overflow-x: auto; padding-bottom: var(--ink-sp-8); }
  .ink-brush-steps { grid-auto-columns: minmax(92px, 1fr); min-width: 560px; }
  .ink-brush-track { min-width: 480px; }
}
</style>
