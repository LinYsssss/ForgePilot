<template><section class="panel"><h3>人工审批</h3><p>Apply: {{ patch?.applyStatus || 'NOT_RUN' }} · Build: {{ patch?.buildStatus || 'NOT_RUN' }} · Test: {{ patch?.testStatus || 'NOT_RUN' }} · Scan: {{ patch?.scanStatus || 'NOT_RUN' }}</p><p v-if="!approvable" class="warning">Patch 未通过 apply/目标消失校验或已过期，不能批准。</p><textarea v-model="comment" placeholder="审批意见"></textarea><div class="actions"><button :disabled="!approvable || busy" @click="decide('APPROVED')">批准</button><button class="danger" :disabled="busy" @click="decide('REJECTED')">拒绝</button></div></section></template>
<script setup>
import { computed, ref } from 'vue'
import { submitPatchApproval } from '../../api/patchApproval.js'
import { canApprovePatch } from './patchApprovalPolicy.js'
const props = defineProps({ projectId: Number, agentRunId: Number, patch: Object, currentHeadSha: String })
const emit = defineEmits(['decided', 'error'])
const comment = ref(''); const busy = ref(false)
const approvable = computed(() => canApprovePatch(props.patch))
async function decide(decision) { busy.value = true; try { const value = await submitPatchApproval({ projectId: props.projectId, agentRunId: props.agentRunId, patchId: props.patch.id, currentHeadSha: props.currentHeadSha, decision, comment: comment.value }); emit('decided', value) } catch (error) { emit('error', error) } finally { busy.value = false } }
</script>
