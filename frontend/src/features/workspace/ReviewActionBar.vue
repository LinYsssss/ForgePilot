<template>
  <div class="ink-action-bar">
    <div class="gate-grid" role="list" aria-label="候选 Patch 校验门">
      <span
        v-for="gate in model.gates"
        :key="gate.key"
        role="listitem"
        :class="{ pass: gate.pass, fail: gate.fail }"
      >
        {{ gate.key }} <b>{{ gate.label }}</b>
      </span>
    </div>

    <p v-if="model.reason" class="gate-reason">{{ model.reason }}</p>
    <p v-else class="gate-pass-note">四道校验已通过，可以落印。</p>

    <div v-if="inlineError" class="action-error" role="alert">
      <strong>{{ inlineError.title }}</strong>
      <span>{{ inlineError.message }}</span>
    </div>

    <label class="comment-field">
      <span>审批意见</span>
      <textarea
        v-model="comment"
        rows="3"
        placeholder="记录批准或拒绝依据…"
        :disabled="submitBusy"
      ></textarea>
    </label>

    <div class="approval-actions">
      <button
        type="button"
        class="ink-outline-button is-danger"
        :disabled="submitBusy || !patch"
        @click="decide('REJECTED')"
      >退回修改</button>
      <button
        ref="approveEl"
        type="button"
        class="ink-primary-button"
        :disabled="!model.approvable || submitBusy || blocked"
        :aria-busy="String(!!submitBusy)"
        @click="dialogOpen = true"
      >{{ submitBusy ? '正在提交…' : '批准并落印' }}</button>
    </div>

    <InkDialog
      v-if="dialogOpen"
      glyph="准"
      title="确认批准候选 Patch？"
      :body="dialogBody"
      confirm-label="确认落印"
      cancel-label="返回复核"
      :busy="submitBusy"
      @cancel="closeDialog"
      @confirm="confirmApprove"
    />
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import InkDialog from '../shell/InkDialog.vue'
import { submitPatchApproval } from '../../api/patchApproval.js'
import { shortCommit } from '../../utils/format.js'
import { approvalModel, describeActionError } from './workspaceModel.js'

// 人工落款(合同 §5 ReviewActionBar):次动作墨线、主动作朱砂实底;
// 批准是危险/最终动作 → InkDialog 确认后才提交(原型冻结流程),
// 拒绝沿用旧 PatchApprovalPanel 的直接提交语义。
// 数据语义零改动:提交走既有 api/patchApproval.submitPatchApproval,
// 可批准判定经 approvalModel 复用 canApprovePatch;双击/重复提交由
// submitBusy 防护。结果通过 decided/error 事件交还页面(旧事件面)。
const props = defineProps({
  projectId: { type: Number, default: null },
  agentRunId: { type: Number, default: null },
  patch: { type: Object, default: null },
  currentHeadSha: { type: String, default: '' },
  blocked: { type: Boolean, default: false }, // 页面级 error/offline 时禁止落印(原型语义)
})
const emit = defineEmits(['decided', 'error'])

const comment = ref('')
const submitBusy = ref(false)
const dialogOpen = ref(false)
const inlineError = ref(null)
const approveEl = ref(null)

const model = computed(() => approvalModel(props.patch))
const dialogBody = computed(() =>
  `批准后将以当前 Head SHA（${shortCommit(props.currentHeadSha)}）与审批意见落印记录，该决定会写入审计。`)

function closeDialog() {
  dialogOpen.value = false
  approveEl.value?.focus()
}

async function confirmApprove() {
  await decide('APPROVED')
  closeDialog()
}

async function decide(decision) {
  if (submitBusy.value || !props.patch) return
  submitBusy.value = true
  inlineError.value = null
  try {
    const value = await submitPatchApproval({
      projectId: props.projectId,
      agentRunId: props.agentRunId,
      patchId: props.patch.id,
      currentHeadSha: props.currentHeadSha,
      decision,
      comment: comment.value,
    })
    emit('decided', { decision, value })
  } catch (error) {
    inlineError.value = describeActionError(error)
    emit('error', error)
  } finally {
    submitBusy.value = false
  }
}
</script>

<style scoped>
.ink-action-bar { display: grid; gap: var(--ink-sp-12); }

/* ---- 四道校验门(状态以文本 + 边线双编码) ---- */
.gate-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: var(--ink-sp-8);
}
.gate-grid span {
  display: flex;
  justify-content: space-between;
  gap: var(--ink-sp-8);
  padding: 9px 11px;
  color: var(--ink-muted);
  background: var(--surface-muted);
  border-left: 2px solid var(--line-strong);
  font-size: var(--ink-fs-12);
}
.gate-grid span.pass { border-left-color: var(--success-ink); }
.gate-grid span.pass b { color: var(--success-ink); }
.gate-grid span.fail { border-left-color: var(--cinnabar); }
.gate-grid span.fail b { color: var(--cinnabar); }
.gate-grid b { font-weight: 700; }

.gate-reason {
  margin: 0;
  padding: var(--ink-sp-8) var(--ink-sp-12);
  color: var(--cinnabar-hover);
  background: var(--cinnabar-soft);
  border-left: 3px solid var(--cinnabar);
  font-size: var(--ink-fs-12);
  line-height: 1.6;
}
.gate-pass-note { margin: 0; color: var(--success-ink); font-size: var(--ink-fs-12); }

/* ---- 行内错误(合同 §6 error/permission/offline:摘要 + 保留重试路径) ---- */
.action-error {
  display: grid;
  gap: 3px;
  padding: var(--ink-sp-8) var(--ink-sp-12);
  border: 1px solid var(--cinnabar);
  border-left-width: 3px;
  background: var(--cinnabar-soft);
}
.action-error strong { color: var(--cinnabar-hover); font-size: var(--ink-fs-13); }
.action-error span { color: var(--ink-default); font-size: var(--ink-fs-12); line-height: 1.6; }

.comment-field { display: grid; gap: 7px; }
.comment-field > span { color: var(--ink-strong); font-size: var(--ink-fs-12); font-weight: 700; }
.comment-field textarea {
  width: 100%;
  resize: vertical;
  min-height: 72px;
  padding: var(--ink-sp-8) var(--ink-sp-12);
  color: var(--ink-strong);
  background: var(--surface-paper);
  border: 1px solid var(--line-strong);
  border-radius: var(--ink-radius-control);
  line-height: 1.5;
}
.comment-field textarea::placeholder { color: var(--ink-muted); }
.comment-field textarea:disabled { opacity: 0.62; filter: saturate(0.4); }

.approval-actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--ink-sp-8);
}

/* ≤560:操作区吸底安全区(冻结合同:不遮挡代码与最后一行内容,
   Toast 已在 ink-base 中避开该安全区) */
@media (max-width: 560px) {
  .gate-grid { grid-template-columns: 1fr 1fr; }
  .approval-actions {
    position: sticky;
    bottom: max(8px, env(safe-area-inset-bottom, 0px));
    z-index: 5;
    display: grid;
    grid-template-columns: 1fr 1.25fr;
    padding: var(--ink-sp-8);
    background: var(--ink-wash-overlay);
    border: 1px solid var(--line-soft);
    box-shadow: var(--ink-shadow-layer);
  }
}
</style>
