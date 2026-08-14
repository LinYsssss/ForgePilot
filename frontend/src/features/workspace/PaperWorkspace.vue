<template>
  <div class="ink-paper-workspace">
    <!-- ============ 案卷头:标题 + 运行状态 + 主区动作 ============ -->
    <section class="ink-panel workspace-head ink-reveal">
      <div class="head-copy">
        <span class="ink-eyebrow">Agent 守门审查</span>
        <h1 v-if="runDetail">案卷 #{{ runId }}</h1>
        <h1 v-else>墨境审查台</h1>
        <div v-if="runDetail" class="run-status-line">
          <SealBadge
            :tone="runView.tone"
            :glyph="runView.glyph"
            :label="runStatusLabel(runDetail.status)"
          />
          <span class="status-note">{{ liveText }}</span>
          <code v-if="headSha" class="head-sha">head {{ shortCommit(headSha) }}</code>
        </div>
        <p v-else class="head-sub">
          从左侧案卷索引选择 Agent Run；风险、证据、Diff 与审批会在这张纸面上按序展开。
        </p>
      </div>
      <div class="head-actions">
        <button
          v-if="runDetail && !runDetail.terminal"
          type="button"
          class="ink-outline-button is-danger"
          :disabled="controlBusy"
          @click="emit('cancel-run')"
        >取消运行</button>
        <button
          v-if="runDetail && ['FAILED', 'TIMED_OUT'].includes(runDetail.status)"
          type="button"
          class="ink-outline-button"
          :disabled="controlBusy"
          @click="emit('retry-run')"
        >重试失败步骤</button>
        <button
          type="button"
          class="ink-outline-button"
          :disabled="loadingRuns || loadingWorkspace"
          @click="emit('refresh')"
        >{{ loadingRuns || loadingWorkspace ? '正在装载…' : '刷新案卷' }}</button>
      </div>
    </section>

    <!-- ============ 页面级状态条(offline 持久 / error 可重试 / permission) ============ -->
    <div
      v-if="loadState"
      class="state-banner"
      :class="'is-' + loadState.kind"
      :role="loadState.kind === 'offline' ? 'status' : 'alert'"
    >
      <div>
        <strong>{{ loadState.title }}</strong>
        <span>{{ loadState.message }}</span>
      </div>
      <button
        v-if="loadState.kind !== 'permission'"
        type="button"
        class="ink-outline-button"
        :disabled="loadingRuns || loadingWorkspace"
        @click="emit('retry')"
      >{{ loadState.kind === 'offline' ? '恢复连接' : '重试装载' }}</button>
      <button v-else type="button" class="ink-outline-button" @click="emit('go', 'projects')">切换项目</button>
    </div>

    <!-- ============ 空态:未选项目 / 无 Run(均带下一步动作) ============ -->
    <section v-if="!hasProject" class="ink-panel ink-reveal empty-panel">
      <h2>尚未选择项目</h2>
      <p>Agent 审查以项目为单位归卷。请先在项目页选择或创建项目，再回到墨境工作台。</p>
      <div class="empty-actions">
        <button type="button" class="ink-primary-button" @click="emit('go', 'projects')">前往项目页</button>
      </div>
    </section>

    <section
      v-else-if="!runsCount && !loadingRuns && !loadState"
      class="ink-panel ink-reveal empty-panel"
    >
      <h2>该项目还没有 Agent Run</h2>
      <p>
        Agent Run 由 PR Webhook 或 PR 工作流触发。创建 / 同步一个 Pull Request 后，
        守门流程会自动开卷并出现在左侧案卷索引。
      </p>
      <div class="empty-actions">
        <button type="button" class="ink-outline-button" @click="emit('refresh')">刷新列表</button>
        <button type="button" class="ink-primary-button" @click="emit('go', 'pullRequests')">前往 PR 工作流</button>
      </div>
    </section>

    <!-- ============ 装载骨架(稳定骨架 + 阶段文本,合同 §6 loading) ============ -->
    <section
      v-else-if="!runDetail && (loadingWorkspace || loadingRuns)"
      class="ink-panel ink-reveal skeleton-panel"
      aria-busy="true"
    >
      <p class="skeleton-note">正在装载案卷数据（Timeline · Finding · Patch）…</p>
      <div class="skeleton-bar w40"></div>
      <div class="skeleton-bar"></div>
      <div class="skeleton-bar w80"></div>
      <div class="skeleton-bar w60"></div>
    </section>

    <template v-else-if="runDetail">
      <!-- ============ 风险摘要(真实计数,不虚构评分) ============ -->
      <section class="ink-panel risk-strip ink-reveal" aria-label="风险摘要">
        <article class="risk-lead">
          <span>活跃 Finding</span>
          <strong>{{ counts.active }}</strong>
          <small>{{ counts.blocking }} 项可阻断<template v-if="counts.rejected"> · {{ counts.rejected }} 项已否决</template></small>
        </article>
        <article v-for="tile in severityTiles" :key="tile.key">
          <SealBadge :tone="tile.tone" :label="tile.label" />
          <strong>{{ tile.count }}</strong>
        </article>
        <article class="run-health">
          <span>当前状态</span>
          <strong>{{ runStatusLabel(runDetail.status) }}</strong>
          <small>Patch {{ patchStateText }} · 步骤 {{ doneSteps }}/{{ timeline.length }}</small>
        </article>
      </section>

      <!-- ============ 笔触进度(真实 Timeline) ============ -->
      <section class="ink-panel ink-reveal" aria-labelledby="ink-progress-title">
        <div class="section-heading">
          <div>
            <span class="ink-eyebrow">审查进度</span>
            <h2 id="ink-progress-title">守门流程</h2>
          </div>
          <span class="section-note">{{ doneSteps }} / {{ timeline.length }} 已完成</span>
        </div>
        <BrushProgress v-if="brush.steps.length" :steps="brush.steps" :current="brush.current" />
        <p v-else class="section-empty">暂无步骤记录。</p>
      </section>

      <!-- ============ 朱批清单(FindingLedger + 严重度筛选) ============ -->
      <section class="ink-panel ink-reveal" aria-labelledby="ink-finding-title">
        <div class="section-heading finding-heading">
          <div>
            <span class="ink-eyebrow">朱批清单</span>
            <h2 id="ink-finding-title">发现 {{ counts.active }} 项<template v-if="counts.blocking"> · {{ counts.blocking }} 项可阻断</template></h2>
          </div>
          <div v-if="findings.length" class="filter-chips" role="group" aria-label="严重度筛选">
            <button
              v-for="chip in chips"
              :key="chip.key"
              type="button"
              :class="{ active: findingFilter === chip.key }"
              :aria-pressed="String(findingFilter === chip.key)"
              @click="findingFilter = chip.key"
            >{{ chip.label }} {{ chip.count }}</button>
          </div>
        </div>
        <FindingLedger
          :findings="displayFindings"
          :selected-id="selectedFindingId"
          :empty-text="findings.length ? '当前筛选没有匹配的 Finding，切换筛选签可查看全部。' : '当前 head 没有 Finding。'"
          @select="emit('select-finding', $event)"
        />
      </section>

      <!-- ============ 证据与 Diff ============ -->
      <section class="ink-panel ink-reveal" aria-label="证据与 Diff">
        <EvidenceDiff
          ref="evidenceEl"
          :finding="selectedFinding"
          :patch="patch"
          @locate="emit('locate', $event)"
        />
      </section>

      <!-- ============ 人工落款 ============ -->
      <section class="ink-panel ink-reveal" aria-labelledby="ink-approval-title">
        <div class="section-heading">
          <div>
            <span class="ink-eyebrow">人工落款</span>
            <h2 id="ink-approval-title">Patch 审批</h2>
          </div>
        </div>
        <ReviewActionBar
          :project-id="projectId"
          :agent-run-id="runId"
          :patch="patch"
          :current-head-sha="headSha"
          :blocked="!!loadState"
          @decided="emit('decided', $event)"
          @error="emit('approval-error', $event)"
        />
      </section>
    </template>

    <!-- 运行列表已就位但未选中(极少数:列表为空选不中即上方空态) -->
    <section v-else-if="runsCount" class="ink-panel ink-reveal empty-panel">
      <h2>未选中案卷</h2>
      <p>从左侧案卷索引选择一个 Agent Run 开始复核。</p>
    </section>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import BrushProgress from '../../shared/ui/BrushProgress.vue'
import SealBadge from '../../shared/ui/SealBadge.vue'
import EvidenceDiff from './EvidenceDiff.vue'
import FindingLedger from './FindingLedger.vue'
import ReviewActionBar from './ReviewActionBar.vue'
import { shortCommit } from '../../utils/format.js'
import {
  brushModel,
  completedStepCount,
  filterFindings,
  findingCounts,
  findingFilterChips,
  runPresentation,
  runStatusLabel,
  severityText,
  severityTone,
} from './workspaceModel.js'

// 主审查纸面(合同 §5 PaperWorkspace):页面标题、风险摘要、Agent 进度、
// Finding 与证据的稳定信息层;自身不模糊、不响应指针视差。
// 展示组件:全部数据来自 props(页面从 useAgentWorkspace 单例取),
// 动作经 emits 交还页面;仅持有「严重度筛选」这一项纯呈现状态。
const props = defineProps({
  projectId: { type: Number, default: null },
  runId: { type: Number, default: null },
  headSha: { type: String, default: '' },
  runDetail: { type: Object, default: null },
  timeline: { type: Array, default: () => [] },
  findings: { type: Array, default: () => [] },
  patch: { type: Object, default: null },
  polling: { type: Boolean, default: false },
  loadingWorkspace: { type: Boolean, default: false },
  loadingRuns: { type: Boolean, default: false },
  controlBusy: { type: Boolean, default: false },
  hasProject: { type: Boolean, default: false },
  runsCount: { type: Number, default: 0 },
  loadState: { type: Object, default: null },   // classifyLoadError() 输出
  selectedFindingId: { type: Number, default: null },
})
const emit = defineEmits([
  'refresh', 'retry', 'select-finding', 'locate',
  'cancel-run', 'retry-run', 'decided', 'approval-error', 'go',
])

const findingFilter = ref('ALL')
const evidenceEl = ref(null)

const counts = computed(() => findingCounts(props.findings))
const chips = computed(() => findingFilterChips(props.findings))
const displayFindings = computed(() => filterFindings(props.findings, findingFilter.value))
const selectedFinding = computed(() =>
  props.findings.find((finding) => finding.id === props.selectedFindingId) || null)

const brush = computed(() => brushModel(props.timeline))
const doneSteps = computed(() => completedStepCount(props.timeline))
const runView = computed(() => runPresentation(props.runDetail?.status))
const patchStateText = computed(() => props.patch?.status || '暂无')

// 自动刷新提示:与旧 AgentView 的 agent-live-status 文案同口径
const liveText = computed(() => {
  if (props.polling) return '正在自动刷新持久化状态'
  return props.runDetail?.terminal ? '运行已结束' : '自动刷新已暂停'
})

const severityTiles = computed(() =>
  ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'].map((key) => ({
    key,
    tone: severityTone(key),
    label: severityText(key),
    count: counts.value.bySeverity[key] ?? 0,
  })))

// 朱批栏定位到被当前筛选隐藏的 Finding 时,回落到「全部」保证可见
watch(() => props.selectedFindingId, (id) => {
  if (id == null) return
  const visible = displayFindings.value.some((finding) => finding.id === id)
  if (!visible) findingFilter.value = 'ALL'
})
// 换案卷时复位筛选
watch(() => props.runId, () => {
  findingFilter.value = 'ALL'
})

/** 定位证据后把焦点交进码面(页面在抽屉关闭后调用) */
function focusEvidence() {
  evidenceEl.value?.focusDiff()
}
defineExpose({ focusEvidence })
</script>

<style scoped>
.ink-paper-workspace { display: grid; gap: var(--ink-sp-16); align-content: start; }

.ink-panel {
  padding: var(--ink-sp-24);
  background: var(--ink-wash-panel);
  border: 1px solid var(--line-soft);
  border-radius: var(--ink-radius-paper);
  box-shadow: var(--ink-shadow-panel);
}

/* ---- 案卷头 ---- */
.workspace-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--ink-sp-24);
}
.head-copy { min-width: 0; }
.workspace-head h1 {
  margin: 6px 0;
  font-family: var(--ink-font-display);
  font-size: clamp(24px, 2.2vw, var(--ink-fs-28));
  letter-spacing: 0.03em;
}
.head-sub {
  max-width: 60ch;
  margin: 0;
  color: var(--ink-muted);
  font-size: var(--ink-fs-13);
  line-height: 1.7;
}
.run-status-line {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--ink-sp-12);
}
.status-note { color: var(--ink-muted); font-size: var(--ink-fs-12); }
.head-sha {
  padding: 2px 7px;
  color: var(--ink-default);
  background: var(--surface-muted);
  border: 1px solid var(--line-soft);
  font-family: var(--ink-font-code);
  font-size: var(--ink-fs-12);
}
.head-actions { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: var(--ink-sp-8); }

/* ---- 页面级状态条 ---- */
.state-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ink-sp-16);
  padding: var(--ink-sp-12) var(--ink-sp-16);
  border: 1px solid var(--line-soft);
  border-left-width: 3px;
  background: var(--ink-wash-panel);
}
.state-banner div { display: grid; gap: 3px; min-width: 0; }
.state-banner strong { color: var(--ink-strong); font-size: var(--ink-fs-13); }
.state-banner span { color: var(--ink-default); font-size: var(--ink-fs-12); line-height: 1.6; }
.state-banner.is-error { border-left-color: var(--cinnabar); background: var(--cinnabar-soft); }
.state-banner.is-error strong { color: var(--cinnabar-hover); }
.state-banner.is-permission { border-left-color: var(--amber-ink); }
.state-banner.is-permission strong { color: var(--amber-ink); }
.state-banner.is-offline { border-left-color: var(--amber-ink); }
.state-banner.is-offline strong { color: var(--amber-ink); }

/* ---- 空态 ---- */
.empty-panel h2 {
  margin: 0 0 var(--ink-sp-8);
  font-family: var(--ink-font-display);
  font-size: var(--ink-fs-20);
}
.empty-panel p {
  max-width: 62ch;
  margin: 0 0 var(--ink-sp-16);
  color: var(--ink-muted);
  font-size: var(--ink-fs-13);
  line-height: 1.8;
}
.empty-actions { display: flex; flex-wrap: wrap; gap: var(--ink-sp-8); }

/* ---- 装载骨架(静止,不转圈) ---- */
.skeleton-panel { display: grid; gap: var(--ink-sp-12); }
.skeleton-note { margin: 0; color: var(--ink-muted); font-size: var(--ink-fs-13); }
.skeleton-bar {
  height: 14px;
  background: var(--surface-muted);
  border: 1px solid var(--line-soft);
}
.skeleton-bar.w40 { width: 40%; }
.skeleton-bar.w60 { width: 60%; }
.skeleton-bar.w80 { width: 80%; }

/* ---- 风险摘要 ---- */
.risk-strip {
  display: grid;
  grid-template-columns: 1.3fr repeat(4, minmax(0, 0.82fr)) 1.4fr;
  padding: 0;
  overflow: hidden;
}
.risk-strip article {
  display: flex;
  align-items: center;
  gap: var(--ink-sp-12);
  min-width: 0;
  min-height: 88px;
  padding: var(--ink-sp-12) var(--ink-sp-16);
  border-right: 1px solid var(--line-soft);
}
.risk-strip article:last-child { border-right: 0; }
.risk-strip strong { color: var(--ink-strong); font-size: 22px; }
.risk-strip small,
.risk-strip article > span { color: var(--ink-muted); font-size: var(--ink-fs-12); }
.risk-lead,
.run-health { display: grid !important; align-content: center; gap: 3px; }
.run-health strong { font-size: var(--ink-fs-16); }

/* ---- 分节标题 ---- */
.section-heading {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--ink-sp-16);
  margin-bottom: var(--ink-sp-16);
}
.section-heading h2 {
  margin: 5px 0 0;
  font-family: var(--ink-font-display);
  font-size: var(--ink-fs-16);
  letter-spacing: 0.02em;
}
.section-note { color: var(--ink-muted); font-size: var(--ink-fs-12); }
.section-empty { margin: 0; color: var(--ink-muted); font-size: var(--ink-fs-13); }

.filter-chips { display: flex; flex-wrap: wrap; gap: 4px; }
.filter-chips button {
  min-height: 44px;
  padding: 0 var(--ink-sp-12);
  color: var(--ink-muted);
  background: transparent;
  border: 1px solid var(--line-soft);
  border-radius: var(--ink-radius-control);
  font-size: var(--ink-fs-12);
  transition: color var(--ink-t-hover) var(--ink-ease-out), background var(--ink-t-hover) var(--ink-ease-out);
}
.filter-chips button:hover:not(.active) { color: var(--mineral-cyan); border-color: var(--mineral-cyan); }
.filter-chips button.active {
  color: var(--ink-on-accent);
  background: var(--ink-default);
  border-color: var(--ink-default);
}

/* ---- 响应式 ---- */
@media (max-width: 1279px) {
  .risk-strip { grid-template-columns: 1.2fr repeat(4, minmax(0, 0.8fr)); }
  .run-health { grid-column: 1 / -1; min-height: 56px; border-top: 1px solid var(--line-soft); border-right: 0; }
}
@media (max-width: 880px) {
  .workspace-head { display: grid; }
  .head-actions { width: 100%; justify-content: flex-start; }
  .risk-strip { grid-template-columns: repeat(4, 1fr); }
  .risk-lead { grid-column: span 2; }
  .finding-heading { align-items: flex-start; flex-direction: column; gap: var(--ink-sp-8); }
}
@media (max-width: 560px) {
  .ink-panel { padding: var(--ink-sp-16) var(--ink-sp-12); }
  .section-heading { display: grid; align-items: start; }
  .state-banner { align-items: stretch; flex-direction: column; }
  .head-actions { display: grid; grid-template-columns: 1fr 1fr; }
  .risk-strip { grid-template-columns: 1fr 1fr; }
  .risk-strip article { min-height: 70px; padding: var(--ink-sp-8) var(--ink-sp-12); }
  .risk-strip article:nth-child(even) { border-right: 0; }
  .risk-lead,
  .run-health { grid-column: 1 / -1; }
  .filter-chips { width: 100%; overflow-x: auto; }
}
</style>
