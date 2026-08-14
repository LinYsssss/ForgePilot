<template>
  <aside class="ink-case-index" aria-label="案卷与主导航">
    <div class="index-head">
      <span class="ink-eyebrow">案卷索引</span>
      <button
        v-if="closable"
        ref="closeEl"
        class="ink-icon-button index-close"
        aria-label="关闭案卷导航"
        @click="emit('close')"
      >
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6 6l12 12M18 6 6 18" /></svg>
      </button>
    </div>

    <label class="index-search">
      <span class="ink-sr-only">筛选导航入口</span>
      <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="11" cy="11" r="7" /><path d="m16 16 4 4" /></svg>
      <input v-model="query" type="search" placeholder="筛选导航入口…" />
    </label>

    <nav class="index-nav" aria-label="主导航">
      <button
        v-for="item in filteredItems"
        :key="item.key"
        :class="{ active: item.key === activeKey }"
        :disabled="item.disabled"
        :aria-current="item.key === activeKey ? 'page' : undefined"
        @click="emit('navigate', item.key)"
      >
        <span class="nav-glyph" aria-hidden="true">{{ item.glyph }}</span>
        <span class="nav-label">{{ item.label }}</span>
        <i v-if="item.badge" class="nav-badge">{{ item.badge }}</i>
      </button>
      <p v-if="!filteredItems.length" class="index-empty">无匹配入口,清空筛选可查看全部。</p>
    </nav>

    <slot />

    <div class="index-foot"><slot name="foot" /></div>
  </aside>
</template>

<script setup>
import { computed, ref } from 'vue'

// 案卷索引(合同 §5 CaseIndex):搜索 + 主导航 + 案卷区(slot)。
// 展示组件:数据从 props 进、动作从 emits 出,不触碰单例/HTTP。
// 当前项用冷青细线 + 加粗表达,不做整块高亮填充(合同 §5)。
const props = defineProps({
  items: { type: Array, default: () => [] }, // [{key,glyph,label,disabled,badge}]
  activeKey: { type: String, default: '' },
  closable: { type: Boolean, default: false }, // 抽屉形态(≤767)时显示关闭钮
})
const emit = defineEmits(['navigate', 'close'])

const query = ref('')
const filteredItems = computed(() => {
  const keyword = query.value.trim()
  if (!keyword) return props.items
  return props.items.filter((item) => String(item.label || '').includes(keyword))
})

const closeEl = ref(null)
function focusClose() {
  closeEl.value?.focus()
}
defineExpose({ focusClose })
</script>

<style scoped>
.ink-case-index {
  display: flex;
  flex-direction: column;
  gap: var(--ink-sp-16);
  min-height: 0;
  padding: var(--ink-sp-24) var(--ink-sp-16) var(--ink-sp-16);
  background: var(--ink-wash-index);
  border-right: 1px solid var(--line-soft);
  backdrop-filter: blur(8px);
}

.index-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ink-sp-12);
  min-height: 24px;
}

.index-search {
  display: flex;
  align-items: center;
  gap: var(--ink-sp-8);
  min-height: 44px;
  padding: 0 var(--ink-sp-12);
  background: var(--ink-wash-field);
  border: 1px solid var(--line-soft);
  border-radius: var(--ink-radius-control);
}
.index-search svg {
  width: 16px;
  height: 16px;
  fill: none;
  stroke: var(--ink-muted);
  stroke-width: 1.6;
}
.index-search input {
  width: 100%;
  min-width: 0;
  border: 0;
  outline: 0;
  background: transparent;
  color: var(--ink-strong);
  font-size: var(--ink-fs-13);
}
.index-search input::placeholder { color: var(--ink-muted); }

.index-nav { display: grid; gap: 3px; }
.index-nav button {
  display: grid;
  grid-template-columns: 27px 1fr auto;
  align-items: center;
  gap: var(--ink-sp-8);
  min-height: 44px;
  padding: 0 var(--ink-sp-12);
  border: 0;
  border-left: 2px solid transparent;
  background: transparent;
  color: var(--ink-default);
  text-align: left;
  font-size: var(--ink-fs-14);
  transition: color var(--ink-t-hover) var(--ink-ease-out), background var(--ink-t-hover) var(--ink-ease-out);
}
.index-nav button:hover:not(:disabled) { color: var(--mineral-cyan); background: var(--ink-wash-panel-faint); }
.index-nav button.active {
  color: var(--ink-strong);
  border-left-color: var(--mineral-cyan);
  background: var(--ink-wash-panel-strong);
  font-weight: 700;
}
.index-nav button:disabled { opacity: 0.62; filter: saturate(0.4); }
.nav-glyph {
  display: grid;
  place-items: center;
  width: 24px;
  height: 24px;
  border: 1px solid var(--line-strong);
  border-radius: 50%;
  font-family: var(--ink-font-display);
  font-size: var(--ink-fs-12);
}
.index-nav button.active .nav-glyph { border-color: var(--mineral-cyan); color: var(--mineral-cyan); }
.nav-label { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.nav-badge {
  min-width: 20px;
  padding: 2px 5px;
  text-align: center;
  color: var(--cinnabar);
  background: var(--cinnabar-soft);
  border-radius: 10px;
  font-size: var(--ink-fs-12);
  font-style: normal;
}
.index-empty { margin: var(--ink-sp-8) 0 0; padding: 0 var(--ink-sp-12); color: var(--ink-muted); font-size: var(--ink-fs-12); }

.index-foot {
  margin-top: auto;
  padding: var(--ink-sp-12) var(--ink-sp-8) 0;
  border-top: 1px solid var(--line-soft);
  color: var(--ink-muted);
  font-size: var(--ink-fs-12);
}
</style>
