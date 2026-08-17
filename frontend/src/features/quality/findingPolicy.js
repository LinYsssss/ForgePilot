import { FINDING_ACTION_LABELS } from '../../api/finding.js'

const REVIEW_ACTIONS = new Set(['CONFIRMED', 'REJECTED', 'VERIFIED', 'CLOSED'])
const FIX_ACTIONS = new Set(['IN_PROGRESS', 'FIXED'])
const ACTIONS_BY_LIFECYCLE = Object.freeze({
  OPEN: ['CONFIRMED', 'REJECTED'],
  CONFIRMED: ['IN_PROGRESS', 'REJECTED'],
  IN_PROGRESS: ['FIXED'],
  FIXED: ['IN_PROGRESS', 'VERIFIED'],
  VERIFIED: ['CLOSED'],
  CLOSED: [],
  REJECTED: [],
})

function sameId(left, right) {
  return left !== null && left !== undefined
    && right !== null && right !== undefined
    && String(left) === String(right)
}

function canReview(role) {
  return role === 'LEADER' || role === 'REVIEWER'
}

function canFix(finding, role, currentUserId) {
  return role === 'LEADER' || sameId(finding?.assigneeId, currentUserId)
}

/**
 * Frontend usability policy only; backend authorization remains authoritative.
 * Returned actions already reflect the lifecycle graph and current role.
 */
export function availableFindingActions(finding, role, currentUserId) {
  const lifecycle = String(finding?.lifecycle || 'OPEN').toUpperCase()
  return (ACTIONS_BY_LIFECYCLE[lifecycle] || []).filter(action => {
    if (REVIEW_ACTIONS.has(action)) return canReview(role)
    if (FIX_ACTIONS.has(action)) return canFix(finding, role, currentUserId)
    return false
  })
}

export function canAssignFinding(role) {
  return role === 'LEADER'
}

export function findingActionLabel(action) {
  return FINDING_ACTION_LABELS[action] || action
}

export function canSendFindingAction(finding, action, fixCommitSha = '') {
  if (action !== 'FIXED') return true
  return String(fixCommitSha || '').trim().length <= 80
}

export function findingSeverityTone(severity) {
  switch (String(severity || '').toUpperCase()) {
    case 'CRITICAL': return 'critical'
    case 'HIGH': return 'high'
    case 'MEDIUM': return 'medium'
    case 'LOW': return 'low'
    default: return 'info'
  }
}

export function suggestionTone(suggestion) {
  switch (String(suggestion || '').toUpperCase()) {
    case 'RESOLVED_SUGGESTED': return 'success'
    case 'STILL_PRESENT': return 'critical'
    case 'UNKNOWN': return 'info'
    default: return 'neutral'
  }
}
