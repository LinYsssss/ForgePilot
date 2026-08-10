<template>
  <div class="kb-select" :class="{ compact }">
    <div class="kb-head">
      <span class="section-title" style="margin:0">{{ title }}</span>
      <div class="kb-tools">
        <el-button size="small" :disabled="!documents.length" @click="selectAll">全选</el-button>
        <el-button size="small" :disabled="!documents.length" @click="clear">清空</el-button>
      </div>
    </div>
    <div v-if="!documents.length" class="hint">{{ emptyHint }}</div>
    <div v-else class="kb-chips">
      <label v-for="d in documents" :key="d.documentId" class="kb-chip" :class="{ on: modelValue.has(d.documentId) }">
        <input type="checkbox" :checked="modelValue.has(d.documentId)" @change="toggle(d.documentId)" />
        <span class="kb-name"><template v-if="showMeta">📄 </template>{{ d.fileName }}</span>
        <span v-if="showMeta" class="badge plain">{{ d.docType }}</span>
      </label>
    </div>
    <p v-if="showCount && documents.length" class="hint">已选 {{ modelValue.size }} / {{ documents.length }} 篇。不选 = 使用全部知识库。</p>
  </div>
</template>

<script setup>
// 知识库文档选择器。审查页与 PR 页各持有自己的选中集合(v-model),
// 此前两处共用同一份 state,在一处勾选会静默改掉另一处将要提交的 documentIds。
const props = defineProps({
  modelValue: { type: Set, required: true },
  documents: { type: Array, default: () => [] },
  title: { type: String, default: '参与审查的知识库' },
  emptyHint: { type: String, default: '该项目暂无知识库文档。' },
  compact: { type: Boolean, default: false },
  showMeta: { type: Boolean, default: false },
  showCount: { type: Boolean, default: false },
})
const emit = defineEmits(['update:modelValue'])

function toggle(id) {
  const next = new Set(props.modelValue)
  next.has(id) ? next.delete(id) : next.add(id)
  emit('update:modelValue', next)
}
function selectAll() {
  emit('update:modelValue', new Set(props.documents.map(d => d.documentId)))
}
function clear() {
  emit('update:modelValue', new Set())
}
</script>

<style scoped>
/* Precision Workbench 重铸(随 PR 页迁移;ReviewsView 复用同一契约)。
   chips 保留「label + 原生 checkbox」结构不换 el-check-tag:可见勾选框
   与真实 checkbox 语义(读屏/键盘)保真度更高。
   scoped 同名重定义清单(脱离 styles.css 供视觉):.kb-select(.compact)、
   .kb-head、.kb-tools、.kb-chips、.kb-chip(.on/.kb-name)、.section-title、
   .hint、.badge(.plain) */
.kb-select {
  margin-top: var(--sp-4);
  padding: var(--sp-4);
  border: 1px solid var(--rs-border);
  border-radius: var(--rs-radius-sm);
  background: var(--rs-fill-lighter);
  font-family: var(--rs-font-body);
  color: var(--rs-text);
}
.kb-select.compact { margin-top: 0; }

.kb-head { display: flex; align-items: center; justify-content: space-between; gap: var(--sp-3); flex-wrap: wrap; }
.kb-tools { display: flex; gap: var(--sp-2); }
.kb-tools .el-button + .el-button { margin-left: 0; }

.section-title {
  margin: var(--sp-1) 0 var(--sp-3);
  font-size: var(--rs-fs-xs);
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.09em;
  color: var(--rs-text-dim);
}

.hint {
  display: flex; align-items: center; gap: var(--sp-2);
  margin: var(--sp-3) 0 0;
  color: var(--rs-text-soft);
  font-size: var(--rs-fs-sm);
  line-height: 1.5;
}

.kb-chips { display: flex; flex-wrap: wrap; gap: var(--sp-2); margin-top: var(--sp-3); }
.kb-chip {
  display: inline-flex; align-items: center; gap: var(--sp-2);
  padding: 8px 13px;
  border: 1px solid var(--rs-border-strong);
  border-radius: var(--rs-radius-round);
  background: var(--rs-surface);
  color: var(--rs-text-soft);
  font-size: var(--rs-fs-sm); font-weight: 600;
  cursor: pointer;
  transition: border-color var(--rs-t-fast), background var(--rs-t-fast), color var(--rs-t-fast);
}
.kb-chip:hover { border-color: var(--el-color-primary-light-3); color: var(--rs-text); }
.kb-chip.on { border-color: var(--rs-primary); background: var(--el-color-primary-light-9); color: var(--rs-primary); }
.kb-chip input {
  width: auto; min-height: 0; margin: 0; padding: 0; flex: none;
  accent-color: var(--rs-primary);
}
.kb-chip input:focus-visible { outline: 2px solid var(--rs-primary); outline-offset: 2px; }
.kb-chip .kb-name { word-break: break-all; }

/* 文档类型徽标(badge plain 同名重定义,无辉光圆点) */
.badge {
  display: inline-flex; align-items: center; gap: var(--sp-1);
  padding: 2px 9px;
  border: 1px solid transparent;
  border-radius: var(--rs-radius-round);
  font-size: var(--rs-fs-xs); font-weight: 700; letter-spacing: 0.02em;
  line-height: 1.5; white-space: nowrap;
}
.badge::before { content: none; display: none; }
.badge.plain { background: var(--rs-fill); border-color: transparent; color: var(--rs-text-soft); font-family: var(--rs-font-mono); font-weight: 600; }
</style>
