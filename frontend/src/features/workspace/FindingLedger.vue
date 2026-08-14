<template>
  <div v-if="findings.length" v-list-nav class="ink-finding-ledger" role="listbox" aria-label="Finding 列表">
    <button
      v-for="finding in findings"
      :key="finding.id"
      type="button"
      role="option"
      class="ledger-row list-row"
      :class="{ selected: finding.id === selectedId, rejected: isRejected(finding) }"
      :aria-selected="String(finding.id === selectedId)"
      @click="emit('select', finding.id)"
    >
      <SealBadge class="row-seal" :tone="severityTone(finding.severity)" :label="severityText(finding.severity)" />
      <span class="row-main">
        <strong>{{ finding.title }}</strong>
        <small>
          {{ findingLocation(finding) }} · {{ (finding.evidence || []).length }} 条证据
          <template v-if="finding.confidence != null"> · 置信度 {{ confidencePct(finding.confidence) }}</template>
        </small>
      </span>
      <span class="row-status">{{ findingStatusText(finding) }}</span>
    </button>
  </div>
  <p v-else class="ledger-empty">{{ emptyText }}</p>
</template>

<script setup>
import SealBadge from '../../shared/ui/SealBadge.vue'
import {
  confidencePct,
  findingLocation,
  findingStatusText,
  isRejected,
  severityText,
  severityTone,
} from './workspaceModel.js'

// 朱批清单(合同 §5 FindingLedger):桌面为紧凑行表,窄屏由 CSS 重排为
// 分组卡片(同一 DOM,不复制业务逻辑)。选中行不改变位置,以左侧冷青
// 边线 + 浅底 + 标题加粗表达;行为 role=option 的原生 button,
// ↑/↓ 走全局 v-list-nav roving focus。展示组件:数据进、动作出。
defineProps({
  findings: { type: Array, default: () => [] },   // 已过 filterFindings/sortFindings 的展示列表
  selectedId: { type: Number, default: null },
  emptyText: { type: String, default: '当前 head 没有 Finding。' },
})
const emit = defineEmits(['select'])
</script>

<style scoped>
.ink-finding-ledger {
  border-top: 1px solid var(--line-soft);
}

.ledger-row {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: var(--ink-sp-12);
  width: 100%;
  min-height: 64px;
  padding: var(--ink-sp-8) var(--ink-sp-12);
  text-align: left;
  color: var(--ink-default);
  background: transparent;
  border: 0;
  border-bottom: 1px solid var(--line-soft);
  transition: background var(--ink-t-hover) var(--ink-ease-out);
}
.ledger-row:hover { background: var(--surface-muted); }
/* 选中行合同:位置不动,边线 + 浅底 + 标题权重(合同 §5) */
.ledger-row.selected {
  background: var(--mineral-cyan-soft);
  box-shadow: inset 3px 0 var(--mineral-cyan);
}
.ledger-row.selected .row-main strong { font-weight: 700; }

.row-seal { flex: 0 0 auto; min-width: 104px; }

.row-main { min-width: 0; display: grid; gap: 4px; }
.row-main strong {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--ink-strong);
  font-size: var(--ink-fs-13);
  font-weight: 600;
}
.row-main small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--ink-muted);
  font-size: var(--ink-fs-12);
}

.row-status {
  color: var(--ink-muted);
  font-size: var(--ink-fs-12);
  white-space: nowrap;
}

/* 否决降噪(与旧 AgentFindings 同义:压透明度 + 标题删除线) */
.ledger-row.rejected { opacity: 0.62; }
.ledger-row.rejected .row-main strong {
  text-decoration: line-through;
  text-decoration-color: var(--ink-muted);
}

.ledger-empty {
  margin: 0;
  padding: var(--ink-sp-16) 0 var(--ink-sp-4);
  color: var(--ink-muted);
  font-size: var(--ink-fs-13);
  line-height: 1.7;
}

/* ---- 窄屏:行重排为分组卡片(合同 §5 FindingLedger 移动形态) ---- */
@media (max-width: 560px) {
  .ink-finding-ledger { border-top: 0; display: grid; gap: var(--ink-sp-8); }
  .ledger-row {
    grid-template-columns: minmax(0, 1fr) auto;
    align-items: start;
    padding: var(--ink-sp-12);
    background: var(--ink-wash-panel-faint);
    border: 1px solid var(--line-soft);
  }
  .row-seal { grid-column: 1; }
  .row-status { grid-column: 2; grid-row: 1; }
  .row-main { grid-column: 1 / -1; }
  .row-main strong,
  .row-main small { white-space: normal; }
}
</style>
