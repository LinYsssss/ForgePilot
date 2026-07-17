<template>
  <section class="panel">
    <div class="panel-head"><h3>Findings 与证据</h3><span class="muted">仅持久化证据可用于阻断</span></div>
    <article v-for="finding in findings" :key="finding.id" class="finding">
      <div class="finding-head"><b>{{ finding.severity }} · {{ finding.title }}</b><span class="confidence">置信度 {{ finding.confidence ?? '-' }}</span></div>
      <p>{{ finding.description }}</p>
      <p class="evidence-count">{{ (finding.evidence || []).length }} 条证据 · {{ finding.blocking ? '可阻断' : '不阻断' }}</p>
      <ul><li v-for="e in finding.evidence || []" :key="e.contentHash || e.reference"><code>{{ e.reference || e.filePath }}</code><span>{{ e.excerpt || e.sourceVersion || '已记录 citation' }}</span></li></ul>
      <p v-if="!(finding.evidence || []).length" class="warning">缺少证据：该 Finding 不应阻断合并。</p>
    </article>
    <div v-if="!findings.length" class="empty compact">当前 head 没有 Finding</div>
  </section>
</template>
<script setup>defineProps({ findings: { type: Array, default: () => [] } })</script>
