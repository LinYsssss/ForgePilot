<template>
  <div class="ink-dialog-backdrop" @click.self="onCancel">
    <section
      ref="dialogEl"
      class="ink-dialog ink-reveal"
      role="dialog"
      aria-modal="true"
      :aria-labelledby="titleId"
      :aria-describedby="bodyId"
    >
      <span v-if="glyph" class="dialog-seal" aria-hidden="true">{{ glyph }}</span>
      <h2 :id="titleId">{{ title }}</h2>
      <p :id="bodyId">{{ body }}</p>
      <div class="dialog-actions">
        <button ref="cancelEl" class="ink-outline-button" type="button" :disabled="busy" @click="onCancel">
          {{ cancelLabel }}
        </button>
        <button
          class="ink-primary-button"
          type="button"
          :disabled="busy"
          :aria-busy="String(!!busy)"
          @click="emit('confirm')"
        >
          {{ busy ? '处理中…' : confirmLabel }}
        </button>
      </div>
    </section>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { nextTrapIndex } from './drawerController.js'

// 墨境确认对话框(冻结对话框合同,来源:已批准原型 confirmDialog 行为):
//   - 打开时焦点进「返回/取消」钮;Escape/遮罩点击关闭;
//   - Tab 在对话框内循环(复用 drawerController.nextTrapIndex 纯逻辑);
//   - 关闭后焦点归还打开前的触发元素;打开期间锁定 body 滚动;
//   - keydown 走捕获阶段并阻断传播 —— 保证 Escape 优先级:对话框 > 抽屉
//     (与原型全局键序一致,InkShell 的抽屉监听不会先吃掉 Escape)。
// 展示组件:文案与动作全部来自 props/emits,不触碰业务单例。
const props = defineProps({
  title: { type: String, required: true },
  body: { type: String, default: '' },
  confirmLabel: { type: String, default: '确认' },
  cancelLabel: { type: String, default: '返回' },
  glyph: { type: String, default: '' },   // 印记字(如「准」);空则不渲染印记
  busy: { type: Boolean, default: false },
})
const emit = defineEmits(['confirm', 'cancel'])

const uid = `ink-dialog-${Math.random().toString(36).slice(2, 8)}`
const titleId = `${uid}-title`
const bodyId = `${uid}-body`

const dialogEl = ref(null)
const cancelEl = ref(null)
let lastFocus = null
let prevOverflow = ''

function onCancel() {
  if (props.busy) return
  emit('cancel')
}

function onKeydown(event) {
  if (event.key === 'Escape') {
    event.stopPropagation()
    event.preventDefault()
    onCancel()
    return
  }
  if (event.key !== 'Tab') return
  event.stopPropagation() // 抽屉的 Tab 循环不得越过模态边界
  const root = dialogEl.value
  if (!root) return
  const focusables = [...root.querySelectorAll('button:not(:disabled), input:not(:disabled), textarea:not(:disabled), a[href], [tabindex]:not([tabindex="-1"])')]
    .filter((el) => !el.hidden)
  const next = nextTrapIndex(focusables.length, focusables.indexOf(document.activeElement), event.shiftKey)
  if (next !== null) {
    event.preventDefault()
    focusables[next]?.focus()
  }
}

onMounted(() => {
  lastFocus = document.activeElement
  prevOverflow = document.body.style.overflow
  document.body.style.overflow = 'hidden'
  document.addEventListener('keydown', onKeydown, true)
  cancelEl.value?.focus()
})

onBeforeUnmount(() => {
  document.removeEventListener('keydown', onKeydown, true)
  document.body.style.overflow = prevOverflow
  if (lastFocus && typeof lastFocus.focus === 'function') lastFocus.focus()
})
</script>

<style scoped>
.ink-dialog-backdrop {
  position: fixed;
  inset: 0;
  z-index: 60;
  display: grid;
  place-items: center;
  padding: var(--ink-sp-24);
  background: var(--ink-wash-dialog);
  backdrop-filter: blur(5px);
}

.ink-dialog {
  width: min(440px, 100%);
  padding: var(--ink-sp-32);
  text-align: center;
  background: var(--surface-raised);
  border: 1px solid var(--line-strong);
  border-radius: var(--ink-radius-layer);
  box-shadow: var(--ink-shadow-layer);
  /* 入场复用主题层 .ink-reveal 一次性动画:静态墨境与 reduced/coarse
     的降级由 ink-base.css 统一接管,此处不再自造动效 */
}

.dialog-seal {
  display: grid;
  place-items: center;
  width: 54px;
  height: 54px;
  margin: 0 auto var(--ink-sp-16);
  color: var(--cinnabar);
  border: 2px solid currentColor;
  font-family: var(--ink-font-display);
  font-size: 25px;
  font-weight: 700;
  transform: rotate(-4deg);
}

.ink-dialog h2 {
  margin: 0;
  font-family: var(--ink-font-display);
  font-size: var(--ink-fs-20);
}
.ink-dialog p {
  margin: var(--ink-sp-12) 0 var(--ink-sp-24);
  color: var(--ink-muted);
  font-size: var(--ink-fs-13);
  line-height: 1.6;
}
.dialog-actions {
  display: flex;
  justify-content: center;
  gap: var(--ink-sp-8);
}

@media (max-width: 560px) {
  .ink-dialog { padding: var(--ink-sp-24) var(--ink-sp-16); }
  .dialog-actions { display: grid; grid-template-columns: 1fr 1.25fr; width: 100%; }
}
</style>
