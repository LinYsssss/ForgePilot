<template>
  <div class="agent-workspace" aria-live="polite">
    <section class="agent-summary" aria-label="Agent Run 摘要">
      <div><span>Agent Run</span><strong>#{{ agentRunId }}</strong></div>
      <div><span>Head SHA</span><code>{{ shortSha }}</code></div>
      <div><span>完成步骤</span><strong>{{ completedSteps }}/{{ timeline.length }}</strong></div>
      <div><span>Findings</span><strong>{{ activeFindings }}</strong></div>
      <div><span>Patch</span><strong>{{ patchState }}</strong></div>
    </section>
    <AgentTimeline :steps="timeline" />
    <AgentFindings :findings="findings" />
    <PatchDiffViewer :patch-content="patch?.patchContent" :validation-log="patch?.validationLog" :download-url="patch?.downloadUrl" />
    <PatchApprovalPanel :project-id="projectId" :agent-run-id="agentRunId" :patch="patch" :current-head-sha="currentHeadSha" @decided="$emit('decided', $event)" @error="$emit('error', $event)" />
  </div>
</template>
<script setup>
import { computed } from 'vue'
import AgentTimeline from './AgentTimeline.vue'
import AgentFindings from './AgentFindings.vue'
import PatchDiffViewer from './PatchDiffViewer.vue'
import PatchApprovalPanel from './PatchApprovalPanel.vue'
const props = defineProps({ projectId: Number, agentRunId: Number, currentHeadSha: String, timeline: { type: Array, default: () => [] }, findings: { type: Array, default: () => [] }, patch: Object })
defineEmits(['decided', 'error'])
const completedSteps = computed(() => props.timeline.filter(step => ['SUCCEEDED', 'COMPLETED', 'SUCCESS'].includes(step.status)).length)
// 被验证器否决的 Finding 不计入摘要,否则会高估这次 run 的问题数
const activeFindings = computed(() => props.findings.filter(f => f.status !== 'rejected').length)
const shortSha = computed(() => props.currentHeadSha ? props.currentHeadSha.slice(0, 12) : '-')
const patchState = computed(() => props.patch?.status || 'NONE')
</script>
<style scoped>
/* Precision Workbench 工作台骨架:摘要磁贴 tokens 手铸白卡(参考概览页
   DashboardStats),四个子面板各自 el-card。scoped 同名重定义清单:
   .agent-summary(整段,压过 styles.css 冻结版的暗色磁贴) */
.agent-workspace {
  display: grid;
  gap: var(--sp-5);
  align-content: start;
  font-family: var(--rs-font-body);
  color: var(--rs-text);
}

/* ---- Run 摘要五磁贴 ---- */
.agent-summary {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: var(--sp-3);
  margin-bottom: 0; /* 间距交给工作台 gap;冻结版 margin-bottom 归零 */
}
.agent-summary > div {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 14px 16px;
  background: var(--rs-surface);
  border: 1px solid var(--rs-border);
  border-radius: var(--rs-radius-base);
  box-shadow: var(--rs-shadow-sm);
  transition: border-color var(--rs-t-base) var(--rs-ease-out), box-shadow var(--rs-t-base) var(--rs-ease-out), transform var(--rs-t-base) var(--rs-ease-out);
}
/* 触屏无 hover;先钉平(冻结版 hover 位移会泄漏),指针设备再微升 */
.agent-summary > div:hover { border-color: var(--rs-border); transform: none; }
@media (hover: hover) {
  .agent-summary > div:hover { border-color: var(--rs-border-strong); box-shadow: var(--rs-shadow-md); transform: translateY(-2px); }
}
.agent-summary span {
  color: var(--rs-text-dim);
  font-size: var(--rs-fs-xs);
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.07em;
}
.agent-summary strong, .agent-summary code {
  font-size: var(--rs-fs-lg);
  font-weight: 700;
  color: var(--rs-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.agent-summary code {
  font-family: var(--rs-font-mono);
  font-feature-settings: "liga" 0;
  font-size: var(--rs-fs-md);
}

@media (max-width: 900px) {
  .agent-summary { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
</style>
