<template>
  <LoginGate v-if="!authenticated" @authenticated="onAuthenticated" />

  <InkShell
    v-else
    ref="shellEl"
    :nav-items="navItems"
    active-key="atelier"
    context-label="当前项目"
    :context-title="activeProject ? activeProject.name : '未选择项目'"
    :rail-badge="railBadge"
    rail-title="审查批注"
    :user="{ name: me.nickname || me.username, role: me.role }"
    @navigate="onNavigate"
    @logout="logout()"
  >
    <template #case>
      <RunCaseList
        :runs="filteredAgentRuns"
        :counts="agentRunCounts"
        :total="agentRuns.length"
        :filter="agentRunFilter"
        :selected-id="agentRunId"
        :loading="!!busy.agentRuns"
        @select="onSelectRun"
        @filter="onRunFilter"
      />
    </template>
    <template #index-foot>
      <span>书院守门规则</span>
      <strong>仅持久化证据可用于阻断</strong>
    </template>

    <PaperWorkspace
      ref="paperEl"
      :project-id="activeProject ? activeProject.projectId : null"
      :run-id="agentRunId"
      :head-sha="agentHeadSha"
      :run-detail="agentRunDetail"
      :timeline="agentTimeline"
      :findings="agentFindings"
      :patch="agentPatch"
      :polling="agentPolling"
      :loading-workspace="!!busy.agent"
      :loading-runs="!!busy.agentRuns"
      :control-busy="!!busy.agentControl"
      :has-project="!!activeProject"
      :runs-count="agentRuns.length"
      :load-state="loadState"
      :selected-finding-id="selectedFindingId"
      @refresh="loadCase"
      @retry="loadCase"
      @select-finding="onSelectFinding"
      @locate="onLocateAnchor"
      @cancel-run="askCancelAgentRun"
      @retry-run="askRetryAgentRun"
      @decided="onDecided"
      @approval-error="onPatchError"
      @go="onNavigate"
    />

    <!-- 取消/重试等确认(useConfirm 单例的墨境呈现;语义与旧 AppShell 一致:
         失败保留模态可重试,错误经 run() 落 toast) -->
    <InkDialog
      v-if="confirmModal"
      glyph="印"
      :title="confirmModal.title"
      :body="confirmModal.body"
      :confirm-label="confirmModal.confirmLabel || '确认'"
      cancel-label="返回"
      :busy="!!busy.confirm"
      @cancel="dismiss"
      @confirm="run(confirmAction)"
    />

    <!-- 一次性落印反馈(合同 §7 Seal confirmation;静态/降级即时呈现) -->
    <div v-if="sealVisible" class="ink-result-layer" aria-live="assertive">
      <div class="ink-result-seal" :class="{ 'is-static': motionMode !== 'normal' }" aria-hidden="true">准</div>
      <strong>已批准</strong>
      <span>审批决定已写入案卷</span>
    </div>

    <template #rail>
      <AnnotationRail :notes="railNotesList" :facts="caseFacts" @locate="onLocateNote" />
    </template>
  </InkShell>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import LoginGate from '../features/auth/LoginGate.vue'
import InkShell from '../features/shell/InkShell.vue'
import InkDialog from '../features/shell/InkDialog.vue'
import AnnotationRail from '../features/workspace/AnnotationRail.vue'
import PaperWorkspace from '../features/workspace/PaperWorkspace.vue'
import RunCaseList from '../features/workspace/RunCaseList.vue'
import { nav } from '../nav.js'
import { fmtTime, shortCommit } from '../utils/format.js'
import { useAgentWorkspace } from '../composables/useAgentWorkspace.js'
import { useBusy } from '../composables/useBusy.js'
import { useConfirm } from '../composables/useConfirm.js'
import { useSession } from '../composables/useSession.js'
import { useToast } from '../composables/useToast.js'
import { useWorkspace } from '../composables/useWorkspace.js'
import { useInkNavigation } from '../features/shell/useInkNavigation.js'
import { useMotionPolicy } from '../shared/motion/useMotionPolicy.js'
import {
  classifyLoadError,
  completedStepCount,
  defaultSelectedId,
  findingCounts,
  railNotes,
} from '../features/workspace/workspaceModel.js'

// 墨境书院 · Agent 审查纵向切片(implement.md 步骤 6):
// 页面只做路由编排与单例接线 —— 门禁沿用登录态分流;Agent 数据
// (Run 列表 / Timeline / Finding / Patch / SSE+轮询 / 取消重试 / 证据
// 定位)全部来自 useAgentWorkspace 既有函数,不建第二套数据通道。
// 呈现由 features/workspace 的展示组件承担,纯派生逻辑在 workspaceModel。
const { authenticated, me, activeProject } = useSession()
const { busy, run } = useBusy()
const { toastMsg } = useToast()
const { confirmModal, dismiss, confirmAction } = useConfirm()
const { motionMode } = useMotionPolicy()
const { loadMe, refreshAll, logout } = useWorkspace()
const {
  agentRuns, agentRunId, agentRunFilter, agentHeadSha,
  agentTimeline, agentFindings, agentPatch, agentRunDetail, agentPolling,
  filteredAgentRuns, agentRunCounts,
  loadAgentRuns, loadAgentWorkspace, selectAgentRun,
  onPatchDecided, onPatchError, askCancelAgentRun, askRetryAgentRun,
  focusEvidenceAnchor,
} = useAgentWorkspace()

const shellEl = ref(null)
const paperEl = ref(null)

/* ---------- 导航(与页框共用同一份条目与执行器,见 useInkNavigation) ---------- */
const { navItems, onNavigate } = useInkNavigation('atelier')

// 登录成功:与 afterLogin 同源动作(loadMe + refreshAll),但停留在墨境;
// refreshAll 会装载项目并选中默认项目,下方 watch 随之装载案卷。
async function onAuthenticated() {
  await run(async () => {
    await loadMe()
    await refreshAll()
    toastMsg('登录成功', 'success')
  }, 'refresh')
}

/* ---------- 案卷装载(错误按 ApiError 结构化字段分类,401 归全局漏斗) ---------- */
const loadState = ref(null)

async function loadCase() {
  if (!authenticated.value || !activeProject.value) return
  loadState.value = null
  try {
    await loadAgentRuns()
    if (agentRunId.value) await loadAgentWorkspace()
  } catch (error) {
    loadState.value = classifyLoadError(error)
  }
}

watch([authenticated, activeProject], ([auth, project]) => {
  if (auth && project) loadCase()
}, { immediate: true })

async function onSelectRun(id) {
  if (agentRunId.value === id) {
    shellEl.value?.closeDrawer(false)
    return
  }
  agentRunId.value = id
  loadState.value = null
  shellEl.value?.closeDrawer(false)
  try {
    await selectAgentRun()
  } catch (error) {
    loadState.value = classifyLoadError(error)
  }
}

function onRunFilter(key) {
  agentRunFilter.value = key
}

/* ---------- Finding 选择与证据定位 ---------- */
const selectedFindingId = ref(null)

watch(agentFindings, (list) => {
  selectedFindingId.value = defaultSelectedId(list, selectedFindingId.value)
}, { immediate: true })

function onSelectFinding(id) {
  selectedFindingId.value = id
}

// 证据锚点:写入路由 query(evidence=path:line,与旧外链语义同构),
// 定位本体复用 useAgentWorkspace.focusEvidenceAnchor;同锚点重复点击
// 时 query 不变、watch 不触发,故补一次直调。
async function onLocateAnchor(anchor) {
  if (!anchor) return
  nav.push({ name: 'inkAtelier', query: { evidence: anchor } })
  await nextTick()
  focusEvidenceAnchor()
}

async function onLocateNote(note) {
  if (note.findingId) selectedFindingId.value = note.findingId
  shellEl.value?.closeDrawer(false)
  await nextTick()
  if (note.anchor) await onLocateAnchor(note.anchor)
  paperEl.value?.focusEvidence()
}

/* ---------- 审批结果:toast+重载复用 onPatchDecided,批准追加一次落印 ---------- */
const sealVisible = ref(false)
let sealTimer = null

function onDecided({ decision }) {
  onPatchDecided().catch(() => {})
  if (decision === 'APPROVED') {
    clearTimeout(sealTimer)
    sealVisible.value = true
    sealTimer = setTimeout(() => {
      sealVisible.value = false
    }, motionMode.value === 'normal' ? 1200 : 500)
  }
}

onBeforeUnmount(() => {
  clearTimeout(sealTimer)
})

/* ---------- 朱批栏派生数据 ---------- */
const railNotesList = computed(() =>
  railNotes({ findings: agentFindings.value, steps: agentTimeline.value, run: agentRunDetail.value }))

const railBadge = computed(() => {
  const critical = findingCounts(agentFindings.value).bySeverity.CRITICAL
  return critical ? String(critical) : ''
})

const caseFacts = computed(() => {
  const run = agentRunDetail.value
  if (!run) return []
  const facts = [
    { dt: 'Run', dd: `#${run.id}` },
    { dt: 'Head', dd: shortCommit(run.headSha) },
    { dt: '步骤', dd: `${completedStepCount(agentTimeline.value)}/${agentTimeline.value.length}` },
    { dt: '建立', dd: fmtTime(run.createdAt) },
  ]
  if (run.pullRequestId) facts.splice(2, 0, { dt: 'PR', dd: `#${run.pullRequestId}` })
  return facts
})
</script>

<style scoped>
/* 一次性落印反馈层(已批准原型 result-layer;背景走 --ink-wash-overlay,
   不支持 backdrop-filter 时 token 自动回退近实色) */
.ink-result-layer {
  position: fixed;
  inset: 0;
  z-index: 70;
  display: grid;
  place-content: center;
  justify-items: center;
  color: var(--cinnabar);
  background: var(--ink-wash-overlay);
  backdrop-filter: blur(6px);
}
.ink-result-seal {
  display: grid;
  place-items: center;
  width: 108px;
  height: 108px;
  border: 5px double currentColor;
  font-family: var(--ink-font-display);
  font-size: 50px;
  font-weight: 700;
  transform: rotate(-7deg);
  animation: ink-seal-land var(--ink-t-reveal) var(--ink-ease-out) both;
}
.ink-result-seal.is-static { animation: none; }
@keyframes ink-seal-land {
  from { opacity: 0; transform: scale(1.5) rotate(-12deg); filter: blur(5px); }
  70% { opacity: 1; transform: scale(0.94) rotate(-7deg); filter: blur(0); }
}
.ink-result-layer strong {
  margin-top: var(--ink-sp-16);
  color: var(--ink-strong);
  font-family: var(--ink-font-display);
  font-size: 24px;
}
.ink-result-layer span {
  margin-top: 6px;
  color: var(--ink-muted);
  font-size: var(--ink-fs-12);
}
</style>
