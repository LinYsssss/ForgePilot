<template>
  <el-card class="approval-panel" shadow="never">
    <template #header>
      <div class="rs-card-head"><h3>人工审批</h3></div>
    </template>
    <p class="gate-line">Apply: {{ patch?.applyStatus || 'NOT_RUN' }} · Build: {{ patch?.buildStatus || 'NOT_RUN' }} · Test: {{ patch?.testStatus || 'NOT_RUN' }} · Scan: {{ patch?.scanStatus || 'NOT_RUN' }}</p>
    <p v-if="!approvable" class="warning">Patch 未通过 apply/目标消失校验或已过期，不能批准。</p>
    <el-input v-model="comment" type="textarea" :rows="3" placeholder="审批意见" />
    <div class="actions">
      <el-button type="primary" :disabled="!approvable || busy" @click="decide('APPROVED')">批准</el-button>
      <el-button type="danger" plain :disabled="busy" @click="decide('REJECTED')">拒绝</el-button>
    </div>
  </el-card>
</template>
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
<style scoped>
/* Precision Workbench 人工审批:el-card 外壳 + el-input(textarea) +
   el-button 语义分型(批准=primary / 拒绝=danger plain)。
   canApprovePatch 禁用条件与提示文案逐字保留。
   scoped 同名重定义清单:.warning/.actions */
.rs-card-head { display: flex; align-items: center; gap: var(--sp-3); }
.rs-card-head h3 {
  margin: 0;
  font-family: var(--rs-font-body);
  font-size: var(--rs-fs-md);
  font-weight: 700;
  letter-spacing: -0.01em;
  color: var(--rs-text);
}

/* 四道校验状态行(Apply/Build/Test/Scan) */
.gate-line {
  margin: 0 0 var(--sp-3);
  color: var(--rs-text-soft);
  font-size: var(--rs-fs-base);
  line-height: 1.6;
}

/* 不可批准提示(轻量左条告警;文案逐字保留) */
.warning {
  margin: 0 0 var(--sp-3);
  padding: var(--sp-2) var(--sp-3);
  color: var(--rs-risk-medium-text);
  background: var(--rs-risk-medium-bg);
  border-left: 3px solid var(--rs-risk-medium-solid);
  border-radius: var(--rs-radius-sm);
  font-size: var(--rs-fs-sm);
}

.actions {
  display: flex;
  gap: var(--sp-3);
  margin-top: var(--sp-5);
  flex-wrap: wrap;
}
/* 按钮间距交给 gap;Element 相邻按钮默认 12px margin 归零 */
.actions .el-button + .el-button { margin-left: 0; }
</style>
