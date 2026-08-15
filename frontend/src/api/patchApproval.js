import { api } from './client.js'

/**
 * 提交补丁审批裁定。currentHeadSha 必须由调用方带上:后端据此判断补丁是否已被新提交顶掉
 * (stale),从而拒绝一次基于过期快照的批准——审批的对象是「这个 head 上的这个补丁」,
 * 而不是补丁本身。
 */
export function submitPatchApproval({ projectId, agentRunId, patchId, currentHeadSha, decision, comment }) {
  return api(`/projects/${projectId}/agent-runs/${agentRunId}/patches/${patchId}/approval`, {
    method: 'POST',
    body: JSON.stringify({ currentHeadSha, decision, comment: comment || '' }),
  })
}
