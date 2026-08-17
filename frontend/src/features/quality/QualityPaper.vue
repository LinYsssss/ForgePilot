<template>
  <div class="quality-layout">
    <section class="ink-panel quality-list">
      <header class="panel-head quality-head">
        <div>
          <span class="ink-eyebrow">Finding 闭环</span>
          <h1>质量中心</h1>
          <p>按生命周期跟进 Agent 发现，人工确认、修复与验证。</p>
        </div>
        <button class="ink-text-button" type="button" :disabled="loading" @click="$emit('refresh')">刷新</button>
      </header>

      <div class="quality-toolbar">
        <label>
          <span>生命周期</span>
          <select class="ink-field" :value="filter" @change="$emit('filter', $event.target.value)">
            <option value="">全部状态</option>
            <option v-for="state in lifecycles" :key="state" :value="state">{{ lifecycleLabel(state) }}</option>
          </select>
        </label>
        <span class="quality-count">共 {{ total }} 条</span>
      </div>

      <p v-if="loading" class="quality-empty">正在整理案卷…</p>
      <p v-else-if="!items.length" class="quality-empty">当前筛选下暂无 Finding。</p>
      <ul v-else class="quality-rows">
        <li v-for="finding in items" :key="finding.id">
          <button
            class="quality-row"
            :class="{ 'is-active': String(finding.id) === String(selectedId) }"
            type="button"
            :aria-current="String(finding.id) === String(selectedId) ? 'true' : undefined"
            @click="$emit('select', finding)"
          >
            <span class="quality-row-top">
              <span class="quality-severity" :data-tone="severityTone(finding.severity)">{{ finding.severity }}</span>
              <span class="quality-lifecycle" :data-lifecycle="finding.lifecycle">{{ lifecycleLabel(finding.lifecycle) }}</span>
            </span>
            <strong>{{ finding.title || ('Finding #' + finding.id) }}</strong>
            <span class="quality-location">{{ locationLabel(finding) }}</span>
            <span v-if="finding.resolutionSuggestion" class="quality-suggestion" :data-tone="suggestionTone(finding.resolutionSuggestion)">
              {{ suggestionLabel(finding.resolutionSuggestion) }}
            </span>
          </button>
        </li>
      </ul>

      <nav v-if="totalPages > 1" class="quality-pagination" aria-label="Finding 分页">
        <button class="ink-text-button" type="button" :disabled="page <= 0 || loading" @click="$emit('page', page - 1)">上一页</button>
        <span>第 {{ page + 1 }} / {{ totalPages }} 页</span>
        <button class="ink-text-button" type="button" :disabled="page + 1 >= totalPages || loading" @click="$emit('page', page + 1)">下一页</button>
      </nav>
    </section>

    <section class="ink-panel quality-detail">
      <p v-if="!selected" class="quality-empty">选择一条 Finding 查看证据与处理动作。</p>
      <template v-else>
        <header class="detail-head">
          <div>
            <span class="ink-eyebrow">Finding #{{ selected.id }} · Run #{{ selected.agentRunId || '-' }}</span>
            <h2>{{ selected.title }}</h2>
          </div>
          <span class="quality-lifecycle" :data-lifecycle="selected.lifecycle">{{ lifecycleLabel(selected.lifecycle) }}</span>
        </header>

        <div class="detail-tags">
          <span class="quality-severity" :data-tone="severityTone(selected.severity)">{{ selected.severity }}</span>
          <span>{{ selected.category || '未分类' }}</span>
          <span v-if="selected.blocking" class="is-blocking">门禁阻断</span>
          <span v-if="selected.resolutionSuggestion" class="quality-suggestion" :data-tone="suggestionTone(selected.resolutionSuggestion)">
            {{ suggestionLabel(selected.resolutionSuggestion) }}
          </span>
        </div>

        <dl class="detail-meta">
          <div><dt>位置</dt><dd>{{ locationLabel(selected) }}</dd></div>
          <div><dt>修复负责人</dt><dd>{{ memberName(selected.assigneeId) }}</dd></div>
          <div><dt>Fix SHA</dt><dd><code>{{ selected.fixCommitSha || '-' }}</code></dd></div>
          <div><dt>验证</dt><dd>{{ selected.verifiedAt ? fmtTime(selected.verifiedAt) : '尚未验证' }}</dd></div>
        </dl>

        <section class="detail-block">
          <h3>问题说明</h3>
          <p>{{ selected.description || '暂无说明。' }}</p>
        </section>

        <details class="evidence-drawer">
          <summary>证据链 · {{ selected.evidence?.length || 0 }} 条</summary>
          <p v-if="!selected.evidence?.length" class="quality-empty">暂无可展示证据。</p>
          <ol v-else class="evidence-list">
            <li v-for="evidence in selected.evidence" :key="evidence.evidenceId || evidence.reference">
              <code>{{ evidence.reference || locationLabel(evidence) }}</code>
              <p>{{ evidence.excerpt || '无摘录' }}</p>
            </li>
          </ol>
        </details>

        <section v-if="canAssign && !terminal" class="detail-block action-block">
          <h3>指派修复</h3>
          <select class="ink-field assign-select" :value="selected.assigneeId ?? ''" @change="assignSelected($event.target.value)">
            <option value="" disabled>选择项目成员</option>
            <option v-for="member in members" :key="member.userId" :value="member.userId">{{ memberLabel(member) }}</option>
          </select>
        </section>

        <section v-if="actions.length" class="detail-block action-block">
          <h3>生命周期动作</h3>
          <label v-if="actions.includes('FIXED')" class="fix-field">
            <span>Fix commit SHA（可选）</span>
            <input v-model="fixShaDrafts[selected.id]" class="ink-field" maxlength="80" placeholder="例如 3f8a1d2" />
          </label>
          <div class="action-buttons">
            <button
              v-for="action in actions"
              :key="action"
              class="ink-secondary-button"
              :class="{ 'is-danger-outline': action === 'REJECTED' }"
              type="button"
              :disabled="mutating || !canSend(selected, action, fixShaDrafts[selected.id])"
              @click="$emit('action', selected, action, fixShaDrafts[selected.id] || '')"
            >{{ actionLabel(action) }}</button>
          </div>
        </section>
      </template>
    </section>
  </div>
</template>

<script setup>
import { computed, reactive } from 'vue'
import {
  FINDING_LIFECYCLES,
  FINDING_LIFECYCLE_LABELS,
  FINDING_SUGGESTION_LABELS,
} from '../../api/finding.js'
import {
  availableFindingActions,
  canAssignFinding,
  canSendFindingAction,
  findingActionLabel,
  findingSeverityTone,
  suggestionTone as resolveSuggestionTone,
} from './findingPolicy.js'
import { fmtTime } from '../../utils/format.js'

const props = defineProps({
  items: { type: Array, default: () => [] },
  selected: { type: Object, default: null },
  selectedId: { type: [Number, String], default: null },
  members: { type: Array, default: () => [] },
  filter: { type: String, default: '' },
  page: { type: Number, default: 0 },
  total: { type: Number, default: 0 },
  totalPages: { type: Number, default: 0 },
  role: { type: String, default: '' },
  currentUserId: { type: [Number, String], default: null },
  loading: { type: Boolean, default: false },
  mutating: { type: Boolean, default: false },
})
const emit = defineEmits(['refresh', 'filter', 'page', 'select', 'assign', 'action'])
const lifecycles = FINDING_LIFECYCLES
const fixShaDrafts = reactive({})
const actions = computed(() => availableFindingActions(props.selected, props.role, props.currentUserId))
const canAssign = computed(() => canAssignFinding(props.role))
const terminal = computed(() => ['CLOSED', 'REJECTED'].includes(props.selected?.lifecycle))

function lifecycleLabel(value) { return FINDING_LIFECYCLE_LABELS[value] || value || '待确认' }
function actionLabel(value) { return findingActionLabel(value) }
function suggestionLabel(value) { return FINDING_SUGGESTION_LABELS[value] || value }
function severityTone(value) { return findingSeverityTone(value) }
function suggestionTone(value) { return resolveSuggestionTone(value) }
function canSend(finding, action, sha) { return canSendFindingAction(finding, action, sha) }
function locationLabel(item) {
  const file = item?.filePath || item?.reference || '未定位文件'
  if (!item?.lineStart) return file
  return file + ':' + item.lineStart + (item.lineEnd && item.lineEnd !== item.lineStart ? '-' + item.lineEnd : '')
}
function memberLabel(member) { return member.nickname || member.username || ('用户#' + member.userId) }
function memberName(userId) {
  if (userId === null || userId === undefined) return '未指派'
  const member = props.members.find(item => String(item.userId) === String(userId))
  return member ? memberLabel(member) : ('用户#' + userId)
}
function assignSelected(userId) {
  if (userId !== '') emit('assign', props.selected, userId)
}
</script>

<style scoped>
.quality-layout { display: grid; grid-template-columns: minmax(280px, 0.9fr) minmax(0, 1.4fr); gap: var(--ink-sp-16); align-items: start; }
.ink-panel { padding: var(--ink-sp-24); background: var(--ink-wash-panel); border: 1px solid var(--line-soft); border-radius: var(--ink-radius-paper); box-shadow: var(--ink-shadow-panel); }
.panel-head, .detail-head { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--ink-sp-16); }
.panel-head h1, .detail-head h2 { margin: var(--ink-sp-4) 0 0; color: var(--ink-strong); font-family: var(--ink-font-display); }
.panel-head h1 { font-size: var(--ink-fs-24); }
.detail-head h2 { font-size: var(--ink-fs-20); }
.panel-head p { margin: var(--ink-sp-8) 0 0; color: var(--ink-muted); line-height: var(--ink-lh-body); }
.quality-toolbar { display: flex; align-items: end; justify-content: space-between; gap: var(--ink-sp-12); margin-top: var(--ink-sp-16); }
.quality-toolbar label, .fix-field { display: grid; gap: var(--ink-sp-4); color: var(--ink-muted); font-size: var(--ink-fs-12); }
.ink-field { width: 100%; padding: var(--ink-sp-8) var(--ink-sp-12); background: var(--ink-wash-field); border: 1px solid var(--line-soft); border-radius: var(--ink-radius-control); color: var(--ink-default); font: inherit; }
.ink-field:focus-visible { outline: 2px solid var(--mineral-cyan); outline-offset: 1px; }
.quality-count, .quality-location { color: var(--ink-muted); font-size: var(--ink-fs-12); }
.quality-rows { list-style: none; display: grid; gap: var(--ink-sp-8); margin: var(--ink-sp-16) 0 0; padding: 0; }
.quality-row { display: grid; gap: var(--ink-sp-8); width: 100%; padding: var(--ink-sp-12); background: var(--surface-paper); border: 1px solid var(--line-soft); border-radius: var(--ink-radius-control); color: var(--ink-default); font: inherit; text-align: left; cursor: pointer; }
.quality-row:hover, .quality-row.is-active { border-color: var(--mineral-cyan); box-shadow: 0 0 0 3px var(--ink-glow-cyan); }
.quality-row:focus-visible { outline: 2px solid var(--mineral-cyan); outline-offset: 2px; }
.quality-row-top, .detail-tags, .action-buttons { display: flex; align-items: center; gap: var(--ink-sp-8); flex-wrap: wrap; }
.quality-severity, .quality-lifecycle, .quality-suggestion, .detail-tags > span { padding: 2px var(--ink-sp-8); border: 1px solid var(--line-soft); border-radius: 999px; color: var(--ink-muted); font-size: var(--ink-fs-12); }
.quality-severity[data-tone='critical'], .quality-severity[data-tone='high'], .is-blocking { color: var(--cinnabar); border-color: var(--cinnabar); }
.quality-severity[data-tone='medium'], .quality-lifecycle[data-lifecycle='IN_PROGRESS'], .quality-lifecycle[data-lifecycle='FIXED'] { color: var(--mineral-cyan); border-color: var(--mineral-cyan); }
.quality-suggestion[data-tone='success'], .quality-lifecycle[data-lifecycle='VERIFIED'], .quality-lifecycle[data-lifecycle='CLOSED'] { color: var(--success-ink); border-color: var(--success-ink); }
.quality-suggestion[data-tone='critical'] { color: var(--cinnabar); border-color: var(--cinnabar); }
.quality-empty { color: var(--ink-muted); line-height: var(--ink-lh-body); }
.quality-pagination { display: flex; align-items: center; justify-content: space-between; gap: var(--ink-sp-8); margin-top: var(--ink-sp-16); color: var(--ink-muted); font-size: var(--ink-fs-12); }
.detail-tags { margin-top: var(--ink-sp-12); }
.detail-meta { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: var(--ink-sp-12); margin: var(--ink-sp-16) 0 0; }
.detail-meta div { min-width: 0; }
.detail-meta dt { color: var(--ink-muted); font-size: var(--ink-fs-12); }
.detail-meta dd { margin: var(--ink-sp-4) 0 0; overflow-wrap: anywhere; color: var(--ink-strong); }
.detail-block, .evidence-drawer { margin-top: var(--ink-sp-16); }
.detail-block h3 { margin: 0 0 var(--ink-sp-8); color: var(--ink-strong); font-family: var(--ink-font-display); font-size: var(--ink-fs-14); }
.detail-block p { margin: 0; color: var(--ink-default); line-height: var(--ink-lh-body); white-space: pre-wrap; }
.evidence-drawer { padding: var(--ink-sp-12); background: var(--surface-paper); border: 1px solid var(--line-soft); border-radius: var(--ink-radius-control); }
.evidence-drawer summary { color: var(--ink-strong); cursor: pointer; font-weight: 600; }
.evidence-list { display: grid; gap: var(--ink-sp-12); margin: var(--ink-sp-12) 0 0; padding-left: 1.4em; }
.evidence-list p { margin: var(--ink-sp-4) 0 0; color: var(--ink-muted); line-height: var(--ink-lh-body); white-space: pre-wrap; }
.assign-select { max-width: 360px; }
.fix-field { max-width: 520px; margin-bottom: var(--ink-sp-8); }
.is-danger-outline { color: var(--cinnabar); border-color: var(--cinnabar); }
@media (max-width: 1023px) { .quality-layout { grid-template-columns: 1fr; } }
@media (max-width: 560px) { .ink-panel { padding: var(--ink-sp-16); } .detail-meta { grid-template-columns: 1fr; } .panel-head, .detail-head { align-items: stretch; flex-direction: column; } }
</style>
