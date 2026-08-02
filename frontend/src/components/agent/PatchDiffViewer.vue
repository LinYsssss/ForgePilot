<template>
  <section class="panel patch-viewer">
    <div class="panel-head"><h3>Patch Diff</h3><a v-if="downloadUrl" :href="downloadUrl" download>下载 Patch</a></div>
    <div v-if="lines.length" class="diff numbered-diff" role="region" aria-label="Patch unified diff" tabindex="0">
      <div v-for="(line, i) in lines" :key="i" class="diff-row" :class="line.cls"
        :data-diff-file="line.file || null" :data-diff-line="line.newNo || null">
        <span class="diff-number" aria-hidden="true">{{ line.newNo }}</span><code>{{ line.text || ' ' }}</code>
      </div>
    </div>
    <p v-else class="empty compact">暂无 Patch</p>
    <details><summary>验证日志</summary><pre>{{ validationLog || '暂无验证日志' }}</pre></details>
  </section>
</template>
<script setup>
import { computed } from 'vue'
import { diffLines } from '../../utils/labels.js'
const props = defineProps({ patchContent: { type: String, default: '' }, validationLog: { type: String, default: '' }, downloadUrl: { type: String, default: '' } })
// 复用 labels.diffLines 的 hunk 行号推导,并跟踪 "+++ b/<path>" 得到行所属文件,
// 供 citation 定位(useAgentWorkspace.focusEvidenceAnchor 按 file+line 查找)。
const lines = computed(() => {
  let file = ''
  return diffLines(props.patchContent || '').map(line => {
    if (line.text.startsWith('+++ b/')) file = line.text.slice(6).trim()
    else if (line.text.startsWith('+++ ')) file = line.text.slice(4).trim()
    return { ...line, file: line.cls === 'meta' || line.cls === 'hunk' ? '' : file }
  })
})
</script>
