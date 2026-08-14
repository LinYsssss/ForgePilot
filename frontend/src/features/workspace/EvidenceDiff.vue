<template>
  <div class="ink-evidence">
    <div class="evidence-heading">
      <div>
        <span class="ink-eyebrow">证据与 Diff</span>
        <h3 v-if="finding">F-{{ finding.id }} · {{ finding.title }}</h3>
        <h3 v-else>证据与 Diff</h3>
      </div>
      <div class="view-switch" role="group" aria-label="Diff 模式">
        <button
          type="button"
          :class="{ active: view === 'unified' }"
          :aria-pressed="String(view === 'unified')"
          @click="view = 'unified'"
        >统一</button>
        <button
          type="button"
          :class="{ active: view === 'split' }"
          :aria-pressed="String(view === 'split')"
          @click="view = 'split'"
        >并排</button>
      </div>
    </div>

    <template v-if="finding">
      <div class="evidence-meta">
        <code>{{ findingLocation(finding) }}</code>
        <span>置信度 <strong>{{ confidencePct(finding.confidence) }}</strong></span>
        <span v-if="finding.category">{{ finding.category }}</span>
        <span v-if="isRejected(finding)" class="plain">已否决</span>
        <span v-else-if="finding.blocking" class="blocking">可阻断</span>
        <span v-else class="plain">不阻断</span>
        <span v-if="finding.decisionReason" class="plain">{{ finding.decisionReason }}</span>
      </div>

      <p class="evidence-description" :class="{ clamped: descriptionClamped }">{{ finding.description }}</p>
      <button
        v-if="descriptionLong"
        type="button"
        class="expand-toggle"
        :aria-expanded="String(!descriptionClamped)"
        @click="descriptionExpanded = !descriptionExpanded"
      >
        {{ descriptionClamped ? '展开完整说明' : '收起说明' }}
      </button>

      <p v-if="isRejected(finding)" class="ink-warning">
        验证器已否决：{{ finding.rejectionReason || '未给出原因' }}——不参与阻断。
      </p>
      <p v-if="!evidenceList.length && !isRejected(finding)" class="ink-warning">
        缺少证据：该 Finding 不应阻断合并。
      </p>

      <details
        v-if="evidenceList.length"
        class="evidence-drawer"
        :open="evidenceList.length <= 3 || undefined"
        @keydown.esc="closeEvidenceDrawer"
      >
        <summary>证据与 citation（{{ evidenceList.length }} 条）</summary>
        <ul>
          <li
            v-for="item in evidenceList"
            :key="item.contentHash || item.reference || item.evidenceId"
            :data-evidence-path="item.filePath || item.path || ''"
          >
            <code>{{ item.reference || item.filePath }}</code>
            <span>{{ item.excerpt || item.sourceVersion || '已记录 citation' }}</span>
            <button
              v-if="evidenceAnchor(item)"
              type="button"
              class="locate-link"
              @click="emit('locate', evidenceAnchor(item))"
            >定位到 diff</button>
          </li>
        </ul>
      </details>
    </template>
    <p v-else class="evidence-empty">当前没有可展示的 Finding；下方 Patch Diff 仍可独立审阅。</p>

    <div class="diff-block">
      <div class="diff-block-head">
        <span>Patch Diff</span>
        <a v-if="patch && patch.downloadUrl" :href="patch.downloadUrl" class="download-link" download>下载 Patch</a>
      </div>

      <div
        v-if="view === 'unified' && uRows.length"
        ref="diffEl"
        class="ink-diff"
        role="region"
        aria-label="Patch unified diff"
        tabindex="0"
      >
        <div
          v-for="(line, index) in uRows"
          :key="index"
          class="diff-row"
          :class="line.cls"
          :data-diff-file="line.file || null"
          :data-diff-line="line.newNo || null"
        >
          <span class="diff-no" aria-hidden="true">{{ line.oldNo }}</span>
          <span class="diff-no" aria-hidden="true">{{ line.newNo }}</span>
          <code>{{ line.text || ' ' }}</code>
        </div>
      </div>

      <div
        v-else-if="view === 'split' && sRows.length"
        ref="diffEl"
        class="ink-diff is-split"
        role="region"
        aria-label="Patch split diff"
        tabindex="0"
      >
        <div class="split-head" aria-hidden="true"><span>原始</span><span>候选修复</span></div>
        <template v-for="row in sRows" :key="row.key">
          <div v-if="row.type === 'hunk' || row.type === 'meta'" class="split-full" :class="row.type">
            <code>{{ row.text || ' ' }}</code>
          </div>
          <div
            v-else
            class="split-row"
            :data-diff-file="(row.right && row.right.file) || null"
            :data-diff-line="(row.right && row.right.no) || null"
          >
            <span class="diff-no" aria-hidden="true">{{ row.left ? row.left.no : '' }}</span>
            <code :class="row.left ? row.left.cls : 'void'">{{ row.left ? (row.left.text || ' ') : ' ' }}</code>
            <span class="diff-no" aria-hidden="true">{{ row.right ? row.right.no : '' }}</span>
            <code :class="row.right ? row.right.cls : 'void'">{{ row.right ? (row.right.text || ' ') : ' ' }}</code>
          </div>
        </template>
      </div>

      <p v-else class="diff-empty">暂无 Patch：Agent 尚未生成候选修复,Finding 与证据仍可正常复核。</p>

      <details v-if="patch" class="validation-log">
        <summary>验证日志</summary>
        <pre>{{ patch.validationLog || '暂无验证日志' }}</pre>
      </details>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { splitRows, unifiedRows } from './diffModel.js'
import {
  confidencePct,
  evidenceAnchor,
  findingLocation,
  isRejected,
} from './workspaceModel.js'

// 证据与 Diff(合同 §5 EvidenceDiff):
//   - 固定宽度代码字体、稳定行高;纸纹不穿透(码面为近实色 code-surface);
//   - unified/split 双视图是同一 patch 的等价投影(diffModel 纯逻辑);
//   - 行号 + 增删符号 + 底色三重编码;窄屏保留语义完整的局部横向滚动;
//   - data-diff-file/data-diff-line 与 data-evidence-path 锚点逐字保留,
//     useAgentWorkspace.focusEvidenceAnchor 在墨境同样命中;
//   - 长说明可展开(合同 §6 long-content),证据抽屉沿用原生 details + Esc。
const props = defineProps({
  finding: { type: Object, default: null },
  patch: { type: Object, default: null },
})
const emit = defineEmits(['locate'])

const view = ref('unified')
const diffEl = ref(null)

const uRows = computed(() => unifiedRows(props.patch?.patchContent || ''))
const sRows = computed(() => splitRows(uRows.value))

const evidenceList = computed(() => props.finding?.evidence || [])

/* ---- 长说明截断与展开(阈值只影响呈现,不动数据) ---- */
const DESCRIPTION_CLAMP_AT = 160
const descriptionExpanded = ref(false)
const descriptionLong = computed(() => String(props.finding?.description || '').length > DESCRIPTION_CLAMP_AT)
const descriptionClamped = computed(() => descriptionLong.value && !descriptionExpanded.value)
watch(() => props.finding?.id, () => {
  descriptionExpanded.value = false
})

function closeEvidenceDrawer(event) {
  event.target.closest('details')?.removeAttribute('open')
}

/** 供页面在「定位证据」后把键盘焦点交进码面(与原型 $('.diff').focus 同义) */
function focusDiff() {
  diffEl.value?.focus({ preventScroll: true })
}
defineExpose({ focusDiff })
</script>

<style scoped>
.ink-evidence { display: grid; gap: var(--ink-sp-12); }

.evidence-heading {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--ink-sp-16);
}
.evidence-heading h3 {
  margin: 5px 0 0;
  font-family: var(--ink-font-display);
  font-size: var(--ink-fs-16);
  letter-spacing: 0.02em;
  overflow-wrap: anywhere;
}

.view-switch { display: flex; gap: 4px; flex: 0 0 auto; }
.view-switch button {
  min-height: 44px;
  padding: 0 var(--ink-sp-12);
  color: var(--ink-muted);
  background: transparent;
  border: 1px solid var(--line-soft);
  border-radius: var(--ink-radius-control);
  font-size: var(--ink-fs-12);
  transition: color var(--ink-t-hover) var(--ink-ease-out), background var(--ink-t-hover) var(--ink-ease-out);
}
.view-switch button:hover:not(.active) { color: var(--mineral-cyan); border-color: var(--mineral-cyan); }
.view-switch button.active {
  color: var(--ink-on-accent);
  background: var(--ink-default);
  border-color: var(--ink-default);
}

/* ---- 证据元信息条 ---- */
.evidence-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--ink-sp-8);
  color: var(--ink-muted);
  font-size: var(--ink-fs-12);
}
.evidence-meta > * {
  padding: 4px 7px;
  background: var(--surface-muted);
  border: 1px solid var(--line-soft);
}
.evidence-meta code { font-family: var(--ink-font-code); word-break: break-all; }
.evidence-meta strong { color: var(--ink-strong); }
.evidence-meta .blocking {
  color: var(--cinnabar);
  background: var(--cinnabar-soft);
  border-color: var(--line-strong);
  font-weight: 700;
}
.evidence-meta .plain { color: var(--ink-muted); }

.evidence-description {
  margin: 0;
  color: var(--ink-default);
  font-size: var(--ink-fs-13);
  line-height: 1.7;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.evidence-description.clamped {
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.expand-toggle {
  justify-self: start;
  min-height: 32px;
  padding: 0;
  border: 0;
  background: none;
  color: var(--mineral-cyan);
  font-size: var(--ink-fs-12);
  font-weight: 700;
}
.expand-toggle:hover { text-decoration: underline; }

/* ---- 行内告警(否决原因/缺证据;朱砂细框,合同 §6 error 形态) ---- */
.ink-warning {
  margin: 0;
  padding: var(--ink-sp-8) var(--ink-sp-12);
  color: var(--cinnabar-hover);
  background: var(--cinnabar-soft);
  border-left: 3px solid var(--cinnabar);
  font-size: var(--ink-fs-12);
  line-height: 1.6;
}

/* ---- 证据抽屉(原生 details;Esc 折叠,与旧 AgentFindings 同义) ---- */
.evidence-drawer summary {
  cursor: pointer;
  color: var(--mineral-cyan);
  font-size: var(--ink-fs-13);
  font-weight: 700;
}
.evidence-drawer summary:hover { text-decoration: underline; }
.evidence-drawer ul {
  margin: var(--ink-sp-8) 0 0;
  padding: 0;
  list-style: none;
  display: grid;
  gap: var(--ink-sp-8);
}
.evidence-drawer li {
  display: grid;
  gap: 4px;
  padding: var(--ink-sp-8) var(--ink-sp-12);
  background: var(--ink-wash-panel-faint);
  border: 1px solid var(--line-soft);
}
.evidence-drawer li code {
  font-family: var(--ink-font-code);
  font-size: var(--ink-fs-12);
  color: var(--ink-strong);
  word-break: break-all;
}
.evidence-drawer li span {
  color: var(--ink-default);
  font-size: var(--ink-fs-12);
  line-height: 1.6;
  overflow-wrap: anywhere;
}
.locate-link {
  justify-self: start;
  min-height: 32px;
  padding: 0;
  border: 0;
  background: none;
  color: var(--mineral-cyan);
  font-size: var(--ink-fs-12);
  font-weight: 700;
}
.locate-link:hover { text-decoration: underline; }

.evidence-empty {
  margin: 0;
  color: var(--ink-muted);
  font-size: var(--ink-fs-13);
  line-height: 1.7;
}

/* ---- Diff 平面(合同 §3.2 码面令牌;纸纹不穿透 → 近实色码面) ---- */
.diff-block { display: grid; gap: var(--ink-sp-8); }
.diff-block-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ink-sp-12);
  color: var(--ink-strong);
  font-size: var(--ink-fs-13);
  font-weight: 700;
}
.download-link {
  color: var(--mineral-cyan);
  font-size: var(--ink-fs-12);
  font-weight: 700;
  text-decoration: none;
}
.download-link:hover { text-decoration: underline; }

.ink-diff {
  overflow: auto;
  max-height: 520px;
  background: var(--code-surface);
  border: 1px solid var(--line-strong);
  font-family: var(--ink-font-code);
  font-size: var(--ink-fs-12);
  line-height: var(--ink-lh-code);
  scrollbar-color: var(--line-strong) transparent;
}

/* unified:双行号 gutter + 原文行(含 +/- 前缀) */
.diff-row {
  display: grid;
  grid-template-columns: 42px 42px minmax(max-content, 1fr);
  white-space: pre;
  color: var(--code-text);
}
.diff-no {
  padding: 2px 8px 2px 2px;
  color: var(--ink-muted);
  background: var(--code-gutter);
  border-right: 1px solid var(--line-soft);
  text-align: right;
  font-size: 11px;
  line-height: inherit;
  user-select: none;
}
.diff-row code { padding: 2px 10px; background: none; font-family: inherit; }
.diff-row.add { background: var(--code-added-bg); }
.diff-row.add code { color: var(--code-added-text); }
.diff-row.del { background: var(--code-removed-bg); }
.diff-row.del code { color: var(--code-removed-text); }
.diff-row.hunk { background: var(--surface-muted); }
.diff-row.hunk code { color: var(--ink-muted); }
.diff-row.meta code { color: var(--ink-muted); }

/* split:42/1fr/42/1fr;窄屏保持 720px 最小宽做局部横向滚动(冻结合同) */
.split-head,
.split-row,
.split-full {
  min-width: 720px;
  display: grid;
  grid-template-columns: 42px minmax(300px, 1fr) 42px minmax(300px, 1fr);
}
.split-head {
  position: sticky;
  top: 0;
  z-index: 1;
  grid-template-columns: 1fr 1fr;
  color: var(--ink-muted);
  background: var(--code-gutter);
  font-family: var(--ink-font-body);
  font-weight: 700;
}
.split-head span { padding: 8px 12px; border-right: 1px solid var(--line-strong); }
.split-full { grid-template-columns: minmax(720px, 1fr); }
.split-full code { padding: 2px 10px; white-space: pre; color: var(--ink-muted); background: none; font-family: inherit; }
.split-full.hunk { background: var(--surface-muted); }
.split-row { white-space: pre; color: var(--code-text); }
.split-row code {
  min-height: 22px;
  padding: 2px 10px;
  border-right: 1px solid var(--line-soft);
  background: none;
  font-family: inherit;
}
.split-row code.add { background: var(--code-added-bg); color: var(--code-added-text); }
.split-row code.del { background: var(--code-removed-bg); color: var(--code-removed-text); }
.split-row code.void { background: var(--surface-muted); }

.diff-empty {
  margin: 0;
  padding: var(--ink-sp-16);
  color: var(--ink-muted);
  background: var(--code-surface);
  border: 1px dashed var(--line-strong);
  font-size: var(--ink-fs-13);
  line-height: 1.7;
}

/* 证据锚点高亮(focusEvidenceAnchor 运行时挂 .evidence-focus) */
.diff-row.evidence-focus,
.split-row.evidence-focus {
  outline: 2px solid var(--code-focus);
  outline-offset: -2px;
}
.evidence-drawer li.evidence-focus {
  outline: 2px solid var(--mineral-cyan);
  outline-offset: 2px;
}

/* ---- 验证日志(原生 details) ---- */
.validation-log summary {
  cursor: pointer;
  color: var(--mineral-cyan);
  font-size: var(--ink-fs-13);
  font-weight: 700;
}
.validation-log summary:hover { text-decoration: underline; }
.validation-log pre {
  margin: var(--ink-sp-8) 0 0;
  padding: var(--ink-sp-12);
  overflow: auto;
  max-height: 300px;
  color: var(--code-text);
  background: var(--code-surface);
  border: 1px solid var(--line-soft);
  font-family: var(--ink-font-code);
  font-size: var(--ink-fs-12);
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}

@media (max-width: 880px) {
  .evidence-heading { display: grid; align-items: start; gap: var(--ink-sp-8); }
}
</style>
