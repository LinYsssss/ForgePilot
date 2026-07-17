<template>
  <div class="agent-workspace" aria-live="polite">
    <section class="agent-summary" aria-label="Agent Run 摘要">
      <div><span>Agent Run</span><strong>#{{ agentRunId }}</strong></div>
      <div><span>Head SHA</span><code>{{ shortSha }}</code></div>
      <div><span>完成步骤</span><strong>{{ completedSteps }}/{{ timeline.length }}</strong></div>
      <div><span>Findings</span><strong>{{ findings.length }}</strong></div>
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
const shortSha = computed(() => props.currentHeadSha ? props.currentHeadSha.slice(0, 12) : '-')
const patchState = computed(() => props.patch?.status || 'NONE')
</script>
