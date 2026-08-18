export function workbenchTarget(item) {
  const type = item?.targetType || item?.type
  if (type === 'REQUIREMENT') return { name: 'requirements', query: { requirementId: String(item.objectId || item.requirementId) } }
  if (type === 'FINDING') return { name: 'quality', query: { findingId: String(item.objectId || item.findingId) } }
  if (type === 'PULL_REQUEST') return { name: 'repository', query: { section: 'pull-requests', pullRequestId: String(item.objectId || item.pullRequestId) } }
  if (type === 'AGENT_RUN') return { name: 'agent', query: { section: 'agent', runId: String(item.objectId || item.agentRunId) } }
  if (type === 'REVIEW_REPORT') return { name: 'agent', query: { section: 'reviews', reportId: String(item.objectId || item.reportId) } }
  return null
}
