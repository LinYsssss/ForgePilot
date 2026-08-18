<template>
  <section class="ink-panel ai-log-paper">
    <header><div><span class="ink-eyebrow">调用明细</span><h2>{{ scope }}</h2></div><button class="ink-outline-button" :disabled="loading" @click="$emit('refresh')">刷新</button></header>
    <p v-if="error" class="state error" role="alert">{{ error }}</p>
    <p v-else-if="loading && !logs.length" class="state">正在读取 AI 调用日志…</p>
    <p v-else-if="!logs.length" class="state">该范围暂无 AI 调用。</p>
    <div v-else class="log-layout">
      <ul class="log-list">
        <li v-for="log in logs" :key="log.logId || log.id"><button :class="{ selected: selected === log }" @click="$emit('select', log)"><span>{{ log.requestType }}</span><code>{{ log.provider }}/{{ log.model }}</code><b>{{ log.status }}</b><small>{{ log.latencyMs }} ms · {{ log.totalTokens }} tokens</small></button></li>
      </ul>
      <article v-if="selected" class="log-detail">
        <h3>调用 #{{ selected.logId || selected.id }}</h3>
        <dl><div><dt>任务</dt><dd>{{ selected.taskId ?? '项目级' }}</dd></div><div><dt>输入字符</dt><dd>{{ selected.promptChars }}</dd></div><div><dt>输出字符</dt><dd>{{ selected.responseChars }}</dd></div><div><dt>Prompt tokens</dt><dd>{{ selected.promptTokens }}</dd></div><div><dt>Completion tokens</dt><dd>{{ selected.completionTokens }}</dd></div><div><dt>时间</dt><dd>{{ fmtTime(selected.createdAt) }}</dd></div></dl>
      </article>
    </div>
    <footer><button class="ink-outline-button" :disabled="page <= 0" @click="$emit('prev')">上一页</button><span>{{ page + 1 }} / {{ Math.max(totalPages, 1) }}</span><button class="ink-outline-button" :disabled="page + 1 >= totalPages" @click="$emit('next')">下一页</button></footer>
  </section>
</template>
<script setup>
import { fmtTime } from '../../utils/format.js'
defineProps({ logs: { type: Array, default: () => [] }, scope: { type: String, default: '' }, selected: { type: Object, default: null }, page: { type: Number, default: 0 }, totalPages: { type: Number, default: 1 }, loading: { type: Boolean, default: false }, error: { type: String, default: '' } })
defineEmits(['select', 'refresh', 'prev', 'next'])
</script>
<style scoped>
.ink-panel { padding: var(--ink-sp-24); background: var(--ink-wash-panel); border: 1px solid var(--line-soft); border-radius: var(--ink-radius-paper); box-shadow: var(--ink-shadow-panel); }header, footer { display: flex; align-items: center; justify-content: space-between; gap: var(--ink-sp-12); }h2,h3 { margin: var(--ink-sp-4) 0; font-family: var(--ink-font-display); }.state { color: var(--ink-muted); }.error { color: var(--cinnabar); }.log-layout { display: grid; grid-template-columns: minmax(0, 1fr) minmax(260px, .6fr); gap: var(--ink-sp-16); margin-top: var(--ink-sp-16); }.log-list { list-style: none; padding: 0; margin: 0; display: grid; gap: var(--ink-sp-4); }.log-list button { width: 100%; min-height: 44px; display: grid; grid-template-columns: 130px 1fr auto; gap: var(--ink-sp-8); padding: var(--ink-sp-8) var(--ink-sp-12); border: 1px solid var(--line-soft); background: var(--surface-paper); color: var(--ink-default); text-align: left; }.log-list button.selected { border-color: var(--mineral-cyan); box-shadow: 0 0 0 3px var(--ink-glow-cyan); }.log-list small { grid-column: 2 / -1; color: var(--ink-muted); }.log-list b { color: var(--cinnabar); }.log-detail { padding: var(--ink-sp-16); border-left: 2px solid var(--cinnabar); background: var(--surface-muted); }.log-detail dl { display: grid; grid-template-columns: 1fr 1fr; gap: var(--ink-sp-8); }.log-detail dt { color: var(--ink-muted); font-size: var(--ink-fs-12); }.log-detail dd { margin: 0; word-break: break-word; }footer { margin-top: var(--ink-sp-16); justify-content: center; }footer span { color: var(--ink-muted); }@media(max-width:800px){.log-layout{grid-template-columns:1fr}.log-list button{grid-template-columns:1fr auto}.log-list code,.log-list small{grid-column:1 / -1}.log-detail dl{grid-template-columns:1fr}}
</style>
