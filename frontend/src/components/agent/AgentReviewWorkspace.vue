<template><div class="agent-workspace"><AgentTimeline :steps="timeline"/><AgentFindings :findings="findings"/><PatchDiffViewer :patch-content="patch?.patchContent" :validation-log="patch?.validationLog" :download-url="patch?.downloadUrl"/><PatchApprovalPanel :project-id="projectId" :agent-run-id="agentRunId" :patch="patch" :current-head-sha="currentHeadSha" @decided="$emit('decided', $event)" @error="$emit('error', $event)"/></div></template>
<script setup>
import AgentTimeline from './AgentTimeline.vue'; import AgentFindings from './AgentFindings.vue'; import PatchDiffViewer from './PatchDiffViewer.vue'; import PatchApprovalPanel from './PatchApprovalPanel.vue'
defineProps({ projectId: Number, agentRunId: Number, currentHeadSha: String, timeline: { type: Array, default: () => [] }, findings: { type: Array, default: () => [] }, patch: Object }); defineEmits(['decided', 'error'])
</script>
