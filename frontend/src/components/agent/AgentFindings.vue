<template>
  <el-card class="rs-findings" shadow="never">
    <template #header>
      <div class="rs-card-head"><h3>Findings 与证据</h3><span class="muted">仅持久化证据可用于阻断</span></div>
    </template>
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
    <el-empty v-if="!findings.length" description="当前 head 没有 Finding" :image-size="60" />
  </el-card>
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
<style scoped>
/* Precision Workbench Findings:el-card 外壳 + finding 子卡 tokens 手铸。
   行为锚点零改动:data-evidence-path、details/summary 展开语义、
   #/agent?evidence= 定位链路、.evidence-focus 高亮钩子。
   scoped 同名重定义清单:.finding(.rejected 降噪态)/.finding-head/
   .badge(.plain)/.sev-*(六级全家)/.confidence/.evidence-count/.warning/
   .evidence-drawer/.evidence-link/.evidence-focus/.muted */
.rs-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-3);
  flex-wrap: wrap;
}
.rs-card-head h3 {
  margin: 0;
  font-family: var(--rs-font-body);
  font-size: var(--rs-fs-md);
  font-weight: 700;
  letter-spacing: -0.01em;
  color: var(--rs-text);
}
.muted { color: var(--rs-text-dim); font-size: var(--rs-fs-sm); font-weight: 400; }

/* ---- Finding 子卡 ---- */
.finding {
  padding: var(--sp-4);
  background: var(--rs-surface);
  border: 1px solid var(--rs-border-faint);
  border-radius: var(--rs-radius-sm);
  transition: opacity var(--rs-t-base);
}
.finding + .finding { margin-top: var(--sp-3); }
.finding p {
  margin: var(--sp-2) 0;
  color: var(--rs-text-soft);
  font-size: var(--rs-fs-base);
  line-height: 1.6;
}
.finding-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-3);
}
.finding-head b {
  display: inline-flex;
  align-items: center;
  gap: var(--sp-2);
  flex-wrap: wrap;
  color: var(--rs-text);
  font-weight: 700;
}
/* 否决降噪态:整卡压透明度 + 标题删除线(语义与冻结版逐点一致) */
.finding.rejected { opacity: 0.62; }
.finding.rejected .finding-head b { text-decoration: line-through; text-decoration-color: var(--rs-text-dim); }

.confidence, .evidence-count { font-size: var(--rs-fs-xs); color: var(--rs-text-dim); }
.confidence { white-space: nowrap; }
.finding p.evidence-count { margin-bottom: 0; color: var(--rs-text-dim); font-size: var(--rs-fs-xs); }

/* ---- 严重度徽标(sev-* 同名重定义;CRITICAL/INFO 两端扩展色此前
   styles.css 缺类,tokens 已补齐,此处一并钉死) ---- */
.badge {
  display: inline-flex;
  align-items: center;
  gap: var(--sp-1);
  padding: 3px 10px;
  border: 1px solid transparent;
  border-radius: var(--rs-radius-round);
  font-size: var(--rs-fs-xs);
  font-weight: 700;
  letter-spacing: 0.02em;
  line-height: 1.5;
  white-space: nowrap;
}
.badge::before { content: ""; width: 6px; height: 6px; border-radius: 50%; background: currentColor; box-shadow: none; }
.badge.plain::before { content: none; display: none; }
.badge.plain { background: var(--rs-fill); color: var(--rs-text-soft); font-weight: 600; }
.sev-CRITICAL { color: var(--rs-sev-critical-text); background: var(--rs-sev-critical-bg); border-color: var(--rs-sev-critical-border); }
.sev-HIGH { color: var(--rs-risk-high-text); background: var(--rs-risk-high-bg); border-color: var(--rs-risk-high-border); }
.sev-MEDIUM { color: var(--rs-risk-medium-text); background: var(--rs-risk-medium-bg); border-color: var(--rs-risk-medium-border); }
.sev-LOW { color: var(--rs-risk-low-text); background: var(--rs-risk-low-bg); border-color: var(--rs-risk-low-border); }
.sev-INFO { color: var(--rs-sev-info-text); background: var(--rs-sev-info-bg); border-color: var(--rs-sev-info-border); }
.sev-NONE { color: var(--rs-risk-none-text); background: var(--rs-risk-none-bg); border-color: var(--rs-risk-none-border); }

/* ---- 页内告警行(否决原因/缺证据;el-alert 留给全局错误态,
   卡内提示保留轻量左条形式) ---- */
.finding p.warning {
  margin: var(--sp-2) 0;
  padding: var(--sp-2) var(--sp-3);
  color: var(--rs-risk-medium-text);
  background: var(--rs-risk-medium-bg);
  border-left: 3px solid var(--rs-risk-medium-solid);
  border-radius: var(--rs-radius-sm);
  font-size: var(--rs-fs-sm);
}

/* ---- 证据抽屉(原生 details/summary,展开语义与 esc 折叠不动) ---- */
.evidence-drawer { margin-top: var(--sp-2); }
.evidence-drawer summary {
  cursor: pointer;
  color: var(--rs-primary);
  font-size: var(--rs-fs-sm);
  font-weight: 600;
}
.evidence-drawer summary:hover { color: var(--rs-primary-dark); text-decoration: underline; }
.evidence-drawer summary:focus-visible { outline: 2px solid var(--rs-primary); outline-offset: 2px; border-radius: var(--rs-radius-sm); }
.evidence-drawer ul { list-style: none; margin: var(--sp-2) 0 0; padding: 0; }
.finding li {
  display: grid;
  gap: 4px;
  margin: 8px 0;
  padding: var(--sp-2) var(--sp-3);
  background: var(--rs-fill-lighter);
  border: 1px solid var(--rs-border-faint);
  border-radius: var(--rs-radius-sm);
}
.finding li code {
  word-break: break-all;
  font-family: var(--rs-font-mono);
  font-feature-settings: "liga" 0;
  font-size: var(--rs-fs-sm);
  color: var(--rs-text);
}
.finding li span { color: var(--rs-text-soft); font-size: var(--rs-fs-sm); line-height: 1.5; }
.evidence-link {
  justify-self: start;
  margin-left: 0;
  font-size: var(--rs-fs-xs);
  font-weight: 600;
  color: var(--rs-primary);
  text-decoration: none;
}
.evidence-link:hover { color: var(--rs-primary-dark); text-decoration: underline; }
.evidence-link:focus-visible { outline: 2px solid var(--rs-primary); outline-offset: 2px; border-radius: 2px; }

/* ---- 证据锚点高亮(focusEvidenceAnchor 运行时挂类;teal 焦点环,
   偏移/圆角与冻结版一致) ---- */
.evidence-focus { outline: 2px solid var(--rs-primary); outline-offset: 4px; border-radius: 4px; }
</style>
