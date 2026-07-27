<template>
  <div class="kb-select" :class="{ compact }">
    <div class="kb-head">
      <span class="section-title" style="margin:0">{{ title }}</span>
      <div class="kb-tools">
        <button class="sm secondary" :disabled="!documents.length" @click="selectAll">全选</button>
        <button class="sm secondary" :disabled="!documents.length" @click="clear">清空</button>
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
