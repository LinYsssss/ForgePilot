<template>
  <InkPageFrame
    active-key="quality"
    context-label="当前项目"
    rail-title="闭环说明"
    :rail-badge="activeFindingCount ? String(activeFindingCount) : ''"
  >
    <QualityPaper
      :items="findings"
      :selected="selectedFinding"
      :selected-id="selectedFindingId"
      :members="members"
      :filter="lifecycleFilter"
      :page="pageInfo.page"
      :total="pageInfo.totalElements"
      :total-pages="pageInfo.totalPages"
      :role="activeProject?.myRole || ''"
      :current-user-id="me.userId"
      :loading="!!busy.qualityFindings"
      :mutating="!!busy.qualityMutation"
      @refresh="run(loadQualityFindings, 'qualityFindings')"
      @filter="value => run(() => setLifecycleFilter(value), 'qualityFindings')"
      @page="value => run(() => goToQualityPage(value), 'qualityFindings')"
      @select="selectQualityFinding"
      @assign="(finding, userId) => run(() => assignQualityFinding(finding, userId), 'qualityMutation')"
      @action="(finding, action, sha) => run(() => transitionQualityFinding(finding, action, sha), 'qualityMutation')"
    />

    <template #rail>
      <p class="rail-note">自动复审只给出「仍存在 / 建议已解决」提示，不会替代人工关闭。</p>
      <p class="rail-note">负责人可指派；审查人员负责确认、驳回、验证与关闭；开发人员仅处理指派给自己的修复。</p>
      <p class="rail-note">门禁状态由服务端综合阻断裁决、覆盖率与未闭环高风险问题计算。</p>
    </template>
  </InkPageFrame>
</template>

<script setup>
import { computed, watch } from 'vue'
import InkPageFrame from '../features/shell/InkPageFrame.vue'
import QualityPaper from '../features/quality/QualityPaper.vue'
import { useBusy } from '../composables/useBusy.js'
import { useMembers } from '../composables/useMembers.js'
import { useQualityFindings } from '../composables/useQualityFindings.js'
import { useSession } from '../composables/useSession.js'
import { nav } from '../nav.js'

const { busy, run } = useBusy()
const { activeProject, me } = useSession()
const { members, loadMembers } = useMembers()
const {
  findings,
  lifecycleFilter,
  selectedFindingId,
  selectedFinding,
  pageInfo,
  resetQualityFindings,
  loadQualityFindings,
  setLifecycleFilter,
  goToQualityPage,
  selectQualityFinding,
  transitionQualityFinding,
  assignQualityFinding,
} = useQualityFindings()

const activeFindingCount = computed(() => findings.value.filter(
  finding => !['CLOSED', 'REJECTED'].includes(finding.lifecycle),
).length)

watch(() => [activeProject.value?.projectId, nav.query().findingId], async () => {
  resetQualityFindings()
  const requestedId = Number(nav.query().findingId) || null
  await run(() => loadQualityFindings({ preferredId: requestedId }), 'qualityFindings')
  run(loadMembers, 'members')
}, { immediate: true })
</script>

<style scoped>
.rail-note { margin: 0 0 var(--ink-sp-12); color: var(--ink-muted); line-height: var(--ink-lh-body); }
.rail-note:last-child { margin-bottom: 0; }
</style>
