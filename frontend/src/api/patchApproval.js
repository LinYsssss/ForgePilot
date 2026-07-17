import { api } from './client.js'

export function submitPatchApproval({ projectId, agentRunId, patchId, currentHeadSha, decision, comment }) {
  return api(`/projects/${projectId}/agent-runs/${agentRunId}/patches/${patchId}/approval`, {
    method: 'POST',
    body: JSON.stringify({ currentHeadSha, decision, comment: comment || '' }),
  })
}
