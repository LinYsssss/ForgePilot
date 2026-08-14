<template>
  <div class="ink-taiji" :class="variantClass" aria-hidden="true"><span></span></div>
</template>

<script setup>
import { computed } from 'vue'

// 太极环境符号(合同 §5 TaijiAmbientMark):纯 CSS,不承载状态、不遮挡
// 表单、不取代品牌文字。两种用法:
//   watermark —— 工作台低透明水印(呼吸 32s);
//   anchor    —— 登录页主视觉锚点(呼吸 22s,合同区间 22–32s)。
// 呼吸只动 opacity/transform(合同 §7 实现规则;原型的 margin-top 呼吸
// 是布局属性,此处按合同以等价 translateY 实现,幅度不变)。
// 静态/减动效由 ink-base.css 统一冻结。
const props = defineProps({
  variant: { type: String, default: 'watermark' }, // watermark | anchor
})

const variantClass = computed(() => (props.variant === 'anchor' ? 'is-anchor' : 'is-watermark'))
</script>

<style scoped>
.ink-taiji {
  position: relative;
  aspect-ratio: 1;
  border-radius: 50%;
  pointer-events: none;
  animation: ink-taiji-breathe 32s ease-in-out infinite;
}
.ink-taiji.is-anchor { animation-duration: 22s; }

.ink-taiji > span {
  position: absolute;
  inset: 0;
  overflow: hidden;
  border: 1px solid var(--ink-taiji-ink);
  border-radius: 50%;
  background: linear-gradient(90deg, var(--ink-taiji-ink) 50%, var(--ink-taiji-paper) 50%);
  transform: rotate(-18deg);
}
.ink-taiji > span::before,
.ink-taiji > span::after {
  content: "";
  position: absolute;
  left: 25%;
  width: 50%;
  height: 50%;
  border-radius: 50%;
}
.ink-taiji > span::before {
  top: 0;
  background: var(--ink-taiji-paper);
  box-shadow: inset 0 0 0 clamp(10px, 4vw, 48px) var(--ink-taiji-ink);
}
.ink-taiji > span::after {
  bottom: 0;
  background: var(--ink-taiji-ink);
  box-shadow: inset 0 0 0 clamp(10px, 4vw, 48px) var(--ink-taiji-paper);
}

/* 呼吸动的是相对透明度(0.63–1),绝对强度由外层放置容器决定:
   工作台水印 ≈0.072、登录环境 ≈0.105,对应原型 0.045–0.072 的实际观感 */
@keyframes ink-taiji-breathe {
  0%, 100% { opacity: 0.63; transform: translateY(-5px); }
  50% { opacity: 1; transform: translateY(8px); }
}
</style>
