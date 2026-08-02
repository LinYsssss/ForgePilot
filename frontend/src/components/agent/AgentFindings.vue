<template>
  <section class="panel">
    <div class="panel-head"><h3>Findings 与证据</h3><span class="muted">仅持久化证据可用于阻断</span></div>
    <article v-for="finding in findings" :key="finding.id" class="finding" :class="{ rejected: isRejected(finding) }">
      <div class="finding-head">
        <b>
          <span class="badge" :class="'sev-' + finding.severity">{{ finding.severity }}</span>
          <span v-if="isRejected(finding)" class="badge plain">已否决</span>
          {{ finding.title }}
        </b>
        <span class="confidence">置信度 {{ pct(finding.confidence) }}</span>
      </div>
      <p>{{ finding.description }}</p>
      <p v-if="isRejected(finding)" class="warning">验证器已否决：{{ finding.rejectionReason || '未给出原因' }}——不参与阻断。</p>
      <p class="evidence-count">{{ (finding.evidence || []).length }} 条证据 · {{ finding.blocking ? '可阻断' : '不阻断' }}<span v-if="finding.decisionReason"> · {{ finding.decisionReason }}</span></p>
      <details v-if="(finding.evidence || []).length" class="evidence-drawer" @keydown.esc="closeDrawer"><summary>展开证据与 citation</summary><ul><li v-for="e in finding.evidence || []" :key="e.contentHash || e.reference" :data-evidence-path="e.filePath || e.path || ''"><code>{{ e.reference || e.filePath }}</code><span>{{ e.excerpt || e.sourceVersion || '已记录 citation' }}</span><a v-if="e.filePath || e.path" :href="fileLink(e)" class="evidence-link">定位到 diff</a></li></ul></details>
      <p v-if="!(finding.evidence || []).length && !isRejected(finding)" class="warning">缺少证据：该 Finding 不应阻断合并。</p>
    </article>
    <div v-if="!findings.length" class="empty compact">当前 head 没有 Finding</div>
  </section>
</template>
<script setup>
defineProps({ findings: { type: Array, default: () => [] } })
function pct(v) { return v == null ? '-' : Math.round(v * 100) + '%' }
// 被独立验证器否决的 Finding 仍会返回(便于追溯原因),但不能和已验证的长得一样。
function isRejected(f) { return f.status === 'rejected' }
function fileLink(e) {
  const path = e.filePath || e.path || ''
  const line = e.lineStart || e.line || 1
  // hash 路由下的 query 形式;旧 "#agent-evidence=" 外链仍由 router 重定向兼容。
  return `#/agent?evidence=${encodeURIComponent(path)}:${line}`
}
function closeDrawer(event) { event.target.closest('details')?.removeAttribute('open') }
</script>
