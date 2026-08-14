<template>
  <span class="ink-seal-badge" :class="[tone.className, { 'ink-seal-stamping': stamping }]">
    <span
      class="ink-seal-mark"
      :class="markShape"
      aria-hidden="true"
      @animationend="stamping = false"
      @animationcancel="stamping = false"
    >{{ markGlyph }}</span>
    <span class="ink-seal-label">{{ label }}</span>
  </span>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { resolveSealTone } from './sealTone.js'

// 印记徽章(合同 §5 SealBadge):圆/方印记 + 文本双编码,印记字为第三重
// 编码;不接业务数据,语义由调用方给定。`stamp` 置 true 时播放一次
// 120ms 下压 + 220ms 光晕的落印反馈(合同 §7 Seal confirmation),
// 播完自动归静;reduced/static 下由主题层压为即时呈现。
const props = defineProps({
  label: { type: String, required: true },       // 文本编码,必填
  glyph: { type: String, default: '' },          // 印记字;缺省取语气默认字
  tone: { type: String, default: 'neutral' },    // critical|high|medium|low|running|info|success|neutral
  shape: { type: String, default: '' },          // circle|square;缺省取语气默认形状
  stamp: { type: Boolean, default: false },      // 置 true 触发一次落印
})

const tone = computed(() => resolveSealTone(props.tone))
const markGlyph = computed(() => props.glyph || tone.value.glyph)
const markShape = computed(() => ((props.shape || tone.value.shape) === 'square' ? 'is-square' : 'is-circle'))

const stamping = ref(false)
watch(() => props.stamp, (next) => {
  if (next) stamping.value = true
})
</script>

<style scoped>
.ink-seal-badge {
  display: inline-flex;
  align-items: center;
  gap: var(--ink-sp-8);
  color: var(--ink-default);
  font-size: var(--ink-fs-13);
}

.ink-seal-mark {
  display: grid;
  place-items: center;
  flex: 0 0 auto;
  width: 30px;
  height: 30px;
  border: 1.5px solid var(--line-strong);
  color: var(--ink-muted);
  background: transparent;
  font-family: var(--ink-font-display);
  font-size: var(--ink-fs-13);
  font-weight: 600;
  line-height: 1;
}
.is-circle { border-radius: 50%; }
.is-square { border-radius: var(--ink-radius-paper); transform: rotate(-3deg); }

.ink-seal-label { color: var(--ink-default); }

/* 语气 → 冻结令牌;朱砂只出现在 critical(合同 §5) */
.tone-critical .ink-seal-mark { color: var(--cinnabar); border-color: var(--cinnabar); background: var(--cinnabar-soft); }
.tone-critical .ink-seal-label { color: var(--cinnabar); font-weight: 700; }
.tone-high .ink-seal-mark { color: var(--amber-ink); border-color: var(--amber-ink); }
.tone-medium .ink-seal-mark { color: var(--ink-default); border-color: var(--line-strong); }
.tone-low .ink-seal-mark { color: var(--success-ink); border-color: var(--success-ink); }
.tone-running .ink-seal-mark { color: var(--mineral-cyan); border-color: var(--mineral-cyan); background: var(--mineral-cyan-soft); }
.tone-info .ink-seal-mark { color: var(--mineral-cyan); border-color: var(--mineral-cyan); }
.tone-success .ink-seal-mark { color: var(--success-ink); border-color: var(--success-ink); background: var(--code-added-bg); }
.tone-neutral .ink-seal-mark { color: var(--ink-muted); border-color: var(--line-strong); }

/* 一次性落印:120ms 下压 + 220ms 光晕(合同 §7),播完即静 */
.ink-seal-stamping .ink-seal-mark {
  animation: ink-seal-press 340ms var(--ink-ease-out) both;
}
@keyframes ink-seal-press {
  0% { transform: scale(1.35); opacity: 0; filter: blur(2px); }
  35% { transform: scale(0.94); opacity: 1; filter: blur(0); box-shadow: 0 0 0 0 var(--cinnabar-soft); }
  100% { transform: scale(1); box-shadow: 0 0 0 6px transparent; }
}
</style>
