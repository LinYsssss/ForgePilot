<template>
  <section class="ink-run-cases" aria-labelledby="ink-run-cases-title">
    <div class="cases-label">
      <span id="ink-run-cases-title">案卷 · Agent Run</span>
      <b v-if="loading">装载中…</b>
      <b v-else>{{ total }} 卷</b>
    </div>

    <div class="run-chips" role="group" aria-label="Run 状态筛选">
      <button
        v-for="chip in chips"
        :key="chip.key"
        type="button"
        :class="{ active: filter === chip.key }"
        :aria-pressed="String(filter === chip.key)"
        @click="emit('filter', chip.key)"
      >
        {{ chip.label }} {{ chip.count }}
      </button>
    </div>

    <div v-if="runs.length" v-list-nav class="run-list" role="list">
      <button
        v-for="item in runs"
        :key="item.id"
        type="button"
        class="run-row list-row"
        role="listitem"
        :class="{ active: item.id === selectedId }"
        :aria-current="item.id === selectedId ? 'true' : undefined"
        @click="emit('select', item.id)"
      >
        <span class="run-line">
          <strong>#{{ item.id }}</strong>
          <i :class="'rt-' + runPresentation(item.status).tone">{{ runStatusLabel(item.status) }}</i>
        </span>
        <small><code>{{ shortCommit(item.headSha) }}</code> · {{ fmtTime(item.createdAt) }}</small>
      </button>
    </div>
    <p v-else-if="total && !loading" class="run-empty">
      当前筛选没有匹配项，<button type="button" class="inline-reset" @click="emit('filter', 'ALL')">显示全部</button>
    </p>
    <p v-else-if="!loading" class="run-empty">该项目还没有 Agent Run。</p>
  </section>
</template>

<script setup>
import { computed } from 'vue'
import { fmtTime, shortCommit } from '../../utils/format.js'
import { runPresentation, runStatusLabel } from './workspaceModel.js'

// 案卷清单(CaseIndex 的 #case 槽内容,合同 §5 CaseIndex「当前案卷」区):
// 展示组件 —— 列表/计数/筛选值全部来自 props(useAgentWorkspace 的
// filteredAgentRuns / agentRunCounts / agentRunFilter),动作从 emits 出。
// 筛选口径不在此重算:与旧 AgentView 完全同源。
const props = defineProps({
  runs: { type: Array, default: () => [] },        // filteredAgentRuns
  counts: { type: Object, default: () => ({}) },   // agentRunCounts {active,waiting,failed,done}
  total: { type: Number, default: 0 },             // agentRuns.length
  filter: { type: String, default: 'ALL' },
  selectedId: { type: Number, default: null },
  loading: { type: Boolean, default: false },
})
const emit = defineEmits(['select', 'filter'])

const chips = computed(() => [
  { key: 'ALL', label: '全部', count: props.total },
  { key: 'ACTIVE', label: '运行', count: props.counts.active ?? 0 },
  { key: 'WAITING', label: '等待', count: props.counts.waiting ?? 0 },
  { key: 'FAILED', label: '失败', count: props.counts.failed ?? 0 },
  { key: 'DONE', label: '完成', count: props.counts.done ?? 0 },
])
</script>

<style scoped>
.ink-run-cases {
  display: grid;
  gap: var(--ink-sp-12);
  padding: var(--ink-sp-12);
  background: var(--ink-wash-panel-faint);
  border: 1px solid var(--line-soft);
}

.cases-label {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ink-sp-12);
  color: var(--ink-muted);
  font-size: var(--ink-fs-12);
}
.cases-label b { color: var(--mineral-cyan); font-size: var(--ink-fs-12); }

.run-chips { display: flex; flex-wrap: wrap; gap: 4px; }
.run-chips button {
  min-height: 30px;
  padding: 0 var(--ink-sp-8);
  color: var(--ink-muted);
  background: transparent;
  border: 1px solid var(--line-soft);
  border-radius: var(--ink-radius-control);
  font-size: var(--ink-fs-12);
  transition: color var(--ink-t-hover) var(--ink-ease-out), background var(--ink-t-hover) var(--ink-ease-out), border-color var(--ink-t-hover) var(--ink-ease-out);
}
.run-chips button:hover:not(.active) { color: var(--mineral-cyan); border-color: var(--mineral-cyan); }
.run-chips button.active {
  color: var(--ink-on-accent);
  background: var(--ink-default);
  border-color: var(--ink-default);
}

.run-list {
  display: grid;
  gap: 3px;
  max-height: 300px;
  overflow: auto;
}
.run-row {
  display: grid;
  gap: 3px;
  min-height: 44px;
  padding: 6px var(--ink-sp-8);
  text-align: left;
  color: var(--ink-default);
  background: transparent;
  border: 0;
  border-left: 2px solid transparent;
  transition: background var(--ink-t-hover) var(--ink-ease-out);
}
.run-row:hover { background: var(--ink-wash-panel-strong); }
.run-row.active {
  border-left-color: var(--mineral-cyan);
  background: var(--ink-wash-panel-strong);
}
.run-line { display: flex; align-items: baseline; gap: var(--ink-sp-8); min-width: 0; }
.run-line strong { color: var(--ink-strong); font-size: var(--ink-fs-13); }
.run-row.active .run-line strong { font-weight: 700; }
.run-line i {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-style: normal;
  font-size: var(--ink-fs-12);
  color: var(--ink-muted);
}
.run-line i.rt-running { color: var(--mineral-cyan); }
.run-line i.rt-info { color: var(--amber-ink); }
.run-line i.rt-high { color: var(--amber-ink); font-weight: 700; }
.run-line i.rt-success { color: var(--success-ink); }
.run-row small { display: flex; gap: 6px; color: var(--ink-muted); font-size: var(--ink-fs-12); }
.run-row small code { font-family: var(--ink-font-code); font-size: var(--ink-fs-12); }

.run-empty { margin: 0; color: var(--ink-muted); font-size: var(--ink-fs-12); line-height: 1.6; }
.inline-reset {
  display: inline;
  min-height: 0;
  padding: 0;
  border: 0;
  background: none;
  color: var(--mineral-cyan);
  font: inherit;
  font-weight: 700;
  text-decoration: underline;
}
</style>
