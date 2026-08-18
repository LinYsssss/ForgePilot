<template>
  <section class="ink-panel queue-card ink-reveal">
    <header><h2>{{ title }}</h2><span>{{ items.length }}</span></header>
    <p v-if="!items.length" class="empty-copy">{{ empty }}</p>
    <ul v-else><li v-for="item in items" :key="item.requirementId || item.findingId || item.pullRequestId"><slot :item="item" /></li></ul>
  </section>
</template>
<script setup>
defineProps({ title: { type: String, required: true }, items: { type: Array, default: () => [] }, empty: { type: String, default: '暂无数据' } })
</script>
<style scoped>
.ink-panel { padding: var(--ink-sp-16); background: var(--ink-wash-panel); border: 1px solid var(--line-soft); border-radius: var(--ink-radius-paper); box-shadow: var(--ink-shadow-panel); }
header { display: flex; justify-content: space-between; align-items: center; gap: var(--ink-sp-8); }h2 { margin: 0; font-family: var(--ink-font-display); font-size: var(--ink-fs-18); }header span { color: var(--cinnabar); font-family: var(--ink-font-code); }ul { list-style: none; margin: var(--ink-sp-12) 0 0; padding: 0; display: grid; gap: var(--ink-sp-4); }.empty-copy { color: var(--ink-muted); line-height: var(--ink-lh-body); }
</style>
