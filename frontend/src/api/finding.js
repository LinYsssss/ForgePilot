import { api } from './client.js'
import { pageMeta, unwrapPage } from './page.js'

/** Project Finding lifecycle values. */
export const FINDING_LIFECYCLES = Object.freeze([
  'OPEN',
  'CONFIRMED',
  'IN_PROGRESS',
  'FIXED',
  'VERIFIED',
  'CLOSED',
  'REJECTED',
])

export const FINDING_LIFECYCLE_LABELS = Object.freeze({
  OPEN: '待确认',
  CONFIRMED: '已确认',
  IN_PROGRESS: '修复中',
  FIXED: '待验证',
  VERIFIED: '已验证',
  CLOSED: '已关闭',
  REJECTED: '已驳回',
})

export const FINDING_ACTION_LABELS = Object.freeze({
  CONFIRMED: '确认问题',
  REJECTED: '驳回问题',
  IN_PROGRESS: '开始修复',
  FIXED: '标记已修复',
  VERIFIED: '验证修复',
  CLOSED: '关闭问题',
})

export const FINDING_SUGGESTION_LABELS = Object.freeze({
  STILL_PRESENT: '复审：仍存在',
  RESOLVED_SUGGESTED: '复审：建议已解决',
  UNKNOWN: '复审：需人工确认',
})

/**
 * @typedef {Object} Finding
 * @property {number|string} id
 * @property {string} lifecycle
 * @property {number|string|null} assigneeId
 * @property {string|null} assigneeName
 * @property {string} fixCommitSha
 * @property {string|null} resolutionSuggestion
 */

function firstDefined(...values) {
  return values.find(value => value !== undefined && value !== null)
}

function normalizeText(value) {
  return value === undefined || value === null ? '' : String(value)
}

function normalizeLifecycle(value) {
  const normalized = normalizeText(value).trim().toUpperCase()
  return FINDING_LIFECYCLES.includes(normalized) ? normalized : 'OPEN'
}

function normalizeId(value) {
  if (value === undefined || value === null || value === '') return null
  const number = Number(value)
  return Number.isNaN(number) ? value : number
}

/** Normalize additive backend field spellings without changing the raw payload. */
export function normalizeFinding(raw = {}) {
  const assignee = firstDefined(raw.assignee, raw.assigneeUser, raw.assigneeMember) || {}
  const id = firstDefined(raw.id, raw.findingId, raw.finding_id)
  const assigneeId = normalizeId(firstDefined(
    raw.assigneeId,
    raw.assignee_id,
    assignee.userId,
    assignee.user_id,
    assignee.id,
  ))
  const assigneeName = firstDefined(
    raw.assigneeName,
    raw.assignee_name,
    assignee.nickname,
    assignee.username,
    assignee.name,
  )
  const evidence = Array.isArray(raw.evidence) ? raw.evidence : []

  return {
    ...raw,
    id,
    findingId: firstDefined(raw.findingId, id),
    severity: normalizeText(raw.severity).trim().toUpperCase() || 'INFO',
    lifecycle: normalizeLifecycle(firstDefined(raw.lifecycle, raw.lifecycleStatus, raw.lifecycle_status)),
    assigneeId,
    assigneeName: assigneeName === undefined || assigneeName === null ? null : String(assigneeName),
    fixCommitSha: normalizeText(firstDefined(raw.fixCommitSha, raw.fix_commit_sha)),
    verifiedBy: normalizeId(firstDefined(raw.verifiedBy, raw.verified_by)),
    verifiedAt: firstDefined(raw.verifiedAt, raw.verified_at) || null,
    resolutionSuggestion: normalizeText(firstDefined(raw.resolutionSuggestion, raw.resolution_suggestion)).trim().toUpperCase() || null,
    evidence,
  }
}

function projectFindingsPath(projectId) {
  return '/projects/' + encodeURIComponent(projectId) + '/findings'
}

/**
 * @returns {Promise<{items: Finding[], page: number, size: number, totalElements: number, totalPages: number}>}
 */
export async function listProjectFindings(projectId, { lifecycle = '', page = 0, size = 20 } = {}) {
  const params = new URLSearchParams()
  if (lifecycle) params.set('lifecycle', lifecycle)
  params.set('page', String(page))
  params.set('size', String(size))
  const data = await api(projectFindingsPath(projectId) + '?' + params.toString())
  return {
    items: unwrapPage(data).map(normalizeFinding),
    ...pageMeta(data, { page, size }),
  }
}

export async function transitionProjectFinding(projectId, findingId, action, fixCommitSha = '') {
  const body = { action }
  if (fixCommitSha && String(fixCommitSha).trim()) body.fixCommitSha = String(fixCommitSha).trim()
  const data = await api(projectFindingsPath(projectId) + '/' + encodeURIComponent(findingId) + '/lifecycle', {
    method: 'POST',
    body: JSON.stringify(body),
  })
  return data ? normalizeFinding(data) : null
}

export async function assignProjectFinding(projectId, findingId, userId) {
  const data = await api(projectFindingsPath(projectId) + '/' + encodeURIComponent(findingId) + '/assign', {
    method: 'POST',
    body: JSON.stringify({ userId }),
  })
  return data ? normalizeFinding(data) : null
}
