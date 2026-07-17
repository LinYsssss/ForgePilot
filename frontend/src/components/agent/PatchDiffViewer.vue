<template>
  <section class="panel patch-viewer">
    <div class="panel-head"><h3>Patch Diff</h3><a v-if="downloadUrl" :href="downloadUrl" download>下载 Patch</a></div>
    <div v-if="lines.length" class="diff numbered-diff" role="region" aria-label="Patch unified diff" tabindex="0">
      <div v-for="line in lines" :key="line.number" class="diff-row" :class="line.cls"><span class="diff-number" aria-hidden="true">{{ line.number }}</span><code>{{ line.text || ' ' }}</code></div>
    </div>
    <p v-else class="empty compact">暂无 Patch</p>
    <details><summary>验证日志</summary><pre>{{ validationLog || '暂无验证日志' }}</pre></details>
  </section>
</template>
<script setup>
import { computed } from 'vue'
const props = defineProps({ patchContent: { type: String, default: '' }, validationLog: { type: String, default: '' }, downloadUrl: { type: String, default: '' } })
const lines = computed(() => (props.patchContent || '').split(/\r?\n/).map((text, index) => ({
  number: index + 1,
  text,
  cls: text.startsWith('@@') ? 'hunk' : (text.startsWith('+') ? 'add' : (text.startsWith('-') ? 'del' : (text.startsWith('diff ') || text.startsWith('index ') || text.startsWith('---') || text.startsWith('+++') ? 'meta' : '')))
})))
</script>
