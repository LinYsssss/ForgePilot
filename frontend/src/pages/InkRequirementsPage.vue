<template>
  <InkPageFrame active-key="requirements" context-label="当前项目" rail-title="需求备注">
    <RequirementsPaper
      :items="requirements"
      :total="requirementsTotal"
      :detail="requirementDetail"
      :form="requirementForm"
      :members="members"
      :filter="requirementFilter"
      :loading="!!busy.requirements"
      :saving="!!busy.requirementSave"
      :editing="editing"
      :can-manage="canManage"
      :current-user-id="me.userId"
      :check-reports="checkReports"
      :checking="!!busy.requirementCheck"
      :can-trigger-check="canTriggerCheck"
      :links="requirementLinks"
      :assistant-enabled="assistant.enabled.value"
      :assistant-messages="assistant.messages.value"
      :assistant-sources="assistant.sources.value"
      :assistant-warnings="assistant.warnings.value"
      :assistant-truncated="assistant.truncatedSections.value"
      :assistant-streaming="assistant.streaming.value"
      @open="item => run(() => openRequirementWithAssistant(item), 'requirementOpen')"
      @new="startCreate"
      @edit="startEdit"
      @cancel-edit="cancelEdit"
      @save="run(() => saveRequirement().then(() => { editing = false }), 'requirementSave')"
      @add-ac="requirementForm.acceptanceCriteria.push({ text: '' })"
      @remove-ac="index => requirementForm.acceptanceCriteria.splice(index, 1)"
      @assign="(detail, userId) => run(() => assignRequirement(detail, userId), 'requirementAssign')"
      @transition="(detail, status) => run(() => transitionRequirement(detail, status), 'requirementTransition')"
      @filter="value => { requirementFilter = value; run(loadRequirements, 'requirements') }"
      @check="run(runCheck, 'requirementCheck')"
      @add-link="(type, ref) => run(() => addLink(type, ref), 'requirementLink')"
      @remove-link="link => run(() => removeLink(link), 'requirementLink')"
      @assistant-ask="question => assistant.ask(activeProject.projectId, requirementDetail.requirementId, question)"
      @assistant-stop="assistant.stop"
      @assistant-retry="assistant.retry(activeProject.projectId, requirementDetail.requirementId)"
    />

    <template #rail>
      <p class="rail-note">
        需求走 草稿 → 就绪 → 开发中 → 待审查 → 已完成;进入开发后内容锁定,修改须先回退状态。
      </p>
      <p class="rail-note">
        每条需求配验收标准(AC)——后续的需求体检与 PR 一致性审查都以 AC 为锚点。
      </p>
    </template>
  </InkPageFrame>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import InkPageFrame from '../features/shell/InkPageFrame.vue'
import RequirementsPaper from '../features/requirements/RequirementsPaper.vue'
import { useBusy } from '../composables/useBusy.js'
import { useSession } from '../composables/useSession.js'
import { useMembers } from '../composables/useMembers.js'
import { useRequirements } from '../composables/useRequirements.js'
import { useRequirementAssistant } from '../composables/useRequirementAssistant.js'
import { nav } from '../nav.js'

// 研发任务页(P1b,墨境原生新页)。列表/详情/表单均出自 useRequirements 单例;
// 指派下拉复用成员名册(useMembers);editing 是页面局部的视图态,不进全局 store。
const { busy, run } = useBusy()
const { activeProject, me } = useSession()
const { members, loadMembers } = useMembers()
const {
  requirements, requirementsTotal, requirementFilter, requirementDetail, requirementForm,
  checkReports, requirementLinks,
  loadRequirements, openRequirement, resetRequirementForm, editRequirement,
  saveRequirement, assignRequirement, transitionRequirement, runCheck, addLink, removeLink,
} = useRequirements()

const editing = ref(false)
const assistant = useRequirementAssistant()
const canManage = computed(() => !!activeProject.value && activeProject.value.myRole === 'LEADER')
// 体检触发对 LEADER/DEVELOPER 开放(P1a 矩阵);REVIEWER 只读报告。
const canTriggerCheck = computed(() => !!activeProject.value
  && ['LEADER', 'DEVELOPER'].includes(activeProject.value.myRole))

async function openRequirementWithAssistant(item) {
  assistant.reset(`${activeProject.value?.projectId || ''}:${item.requirementId}`)
  await openRequirement(item)
}

function startCreate() {
  resetRequirementForm()
  editing.value = true
}

function startEdit(detail) {
  editRequirement(detail)
  editing.value = true
}

function cancelEdit() {
  resetRequirementForm()
  editing.value = false
}

watch(() => [activeProject.value?.projectId, nav.query().requirementId], async () => {
  assistant.reset()
  editing.value = false
  requirementDetail.value = null
  checkReports.value = []
  requirementLinks.value = []
  await run(loadRequirements, 'requirements')
  run(loadMembers, 'members')
  const requestedId = Number(nav.query().requirementId)
  if (requestedId) {
    const summary = requirements.value.find(item => Number(item.requirementId) === requestedId) || { requirementId: requestedId }
    await run(() => openRequirementWithAssistant(summary), 'requirementOpen')
  }
}, { immediate: true })

onMounted(assistant.loadConfig)
onBeforeUnmount(assistant.reset)
</script>

<style scoped>
.rail-note { margin: 0 0 var(--ink-sp-12); color: var(--ink-muted); line-height: var(--ink-lh-body); }
.rail-note:last-child { margin-bottom: 0; }
</style>
