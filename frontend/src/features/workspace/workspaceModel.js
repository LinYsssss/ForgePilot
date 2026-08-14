// 墨境工作台展示模型(纯逻辑,可在 node --test 下直测;渲染见同目录 SFC)。
// 数据语义全部来自既有后端合同与 useAgentWorkspace 单例:本模块只做
// 「后端形状 → 墨境呈现」的派生,不发请求、不持状态、不 import vue。
// 审批可用性判定直接复用旧树的 patchApprovalPolicy(canApprovePatch),
// 不造第二套策略(D-002:业务语义冻结)。
import { canApprovePatch } from '../../components/agent/patchApprovalPolicy.js'
import { fmtTime, shortCommit } from '../../utils/format.js'

/* ============================================================
   Agent 阶段(stepType = 后端 AgentRunStatus 管线态)
   ============================================================ */

/** 阶段 → 笔触步骤的印记字与短标签(合同 §5 BrushProgress:图标+文本+时间) */
export const AGENT_STAGE_META = Object.freeze({
  RECEIVED: { label: '接卷', glyph: '接' },
  PREPARING_REPOSITORY: { label: '备库', glyph: '备' },
  ANALYZING_CHANGE: { label: '析变', glyph: '析' },
  PLANNING: { label: '规划', glyph: '谋' },
  EXECUTING_TOOLS: { label: '工具', glyph: '工' },
  RETRIEVING_CONTEXT: { label: '检索', glyph: '检' },
  VERIFYING_FINDINGS: { label: '验证', glyph: '核' },
  GENERATING_PATCH: { label: '拟补', glyph: '拟' },
  VALIDATING_PATCH: { label: '验补', glyph: '验' },
  WAITING_APPROVAL: { label: '待批', glyph: '批' },
  PUBLISHING_RESULT: { label: '发布', glyph: '发' },
})

/** 视为「已了结、笔触可越过」的步骤状态(AgentStepStatus) */
const SETTLED_STEPS = new Set(['SUCCEEDED', 'SKIPPED'])

const STEP_STATE_TEXT = {
  RUNNING: '进行中',
  PENDING: '待执行',
  FAILED: '失败',
  INTERRUPTED: '已中断',
  CANCELED: '已取消',
}

/** @param {string} stepType */
export function stageLabel(stepType) {
  return AGENT_STAGE_META[stepType]?.label || stepType || '未知阶段'
}

function stepMetaText(step) {
  if (SETTLED_STEPS.has(step.status)) {
    if (step.status === 'SKIPPED') return '已跳过'
    const time = fmtTime(step.finishedAt)
    return time === '-' ? '已完成' : time
  }
  return STEP_STATE_TEXT[step.status] || step.status || '待执行'
}

/**
 * 真实 Timeline → BrushProgress 模型。
 * current = 第一个未了结步骤的下标(失败/中断/取消的步骤即当前停驻点,
 * meta 以文本说明);全部了结时 current = 步骤总数(描线画满)。
 * @param {Array<{id?: number, sequenceNo?: number, stepType?: string, status?: string, finishedAt?: string}>} timeline
 * @returns {{ steps: Array<{key: string, label: string, glyph: string, meta: string}>, current: number, failed: boolean }}
 */
export function brushModel(timeline) {
  const list = Array.isArray(timeline) ? timeline : []
  const steps = list.map((step, index) => {
    const meta = AGENT_STAGE_META[step.stepType] || {}
    return {
      key: String(step.id ?? step.sequenceNo ?? index),
      label: meta.label || step.stepType || `步骤 ${index + 1}`,
      glyph: meta.glyph || '步',
      meta: stepMetaText(step),
    }
  })
  let current = list.findIndex((step) => !SETTLED_STEPS.has(step.status))
  if (current === -1) current = list.length
  return { steps, current, failed: list.some((step) => step.status === 'FAILED') }
}

/**
 * 完成步骤计数:沿用旧 AgentReviewWorkspace 摘要的判定集,口径不变。
 * @param {Array<{status?: string}>} timeline
 */
export function completedStepCount(timeline) {
  const done = new Set(['SUCCEEDED', 'COMPLETED', 'SUCCESS'])
  return (Array.isArray(timeline) ? timeline : []).filter((step) => done.has(step.status)).length
}

/* ============================================================
   Agent Run 状态呈现(标签/语气/印记字;筛选口径仍在 useAgentWorkspace)
   ============================================================ */

const RUN_STATUS_LABELS = {
  RECEIVED: '已接卷',
  PREPARING_REPOSITORY: '准备仓库',
  ANALYZING_CHANGE: '分析变更',
  PLANNING: '规划中',
  EXECUTING_TOOLS: '执行工具',
  RETRIEVING_CONTEXT: '检索上下文',
  VERIFYING_FINDINGS: '验证 Finding',
  GENERATING_PATCH: '生成 Patch',
  VALIDATING_PATCH: '验证 Patch',
  WAITING_APPROVAL: '等待审批',
  WAITING_EXTERNAL: '等待外部',
  PUBLISHING_RESULT: '发布结果',
  RETRY_WAIT: '等待重试',
  COMPLETED: '已完成',
  SUCCEEDED: '已完成',
  FAILED: '失败',
  TIMED_OUT: '超时',
  DEAD: '已死信',
  CANCELED: '已取消',
}

const RUN_TONE_GROUPS = [
  // 朱砂不参与 Run 状态编码(合同 §5:朱砂只属 Critical/主动作/最终确定)
  [['WAITING_APPROVAL', 'WAITING_EXTERNAL', 'RETRY_WAIT'], { tone: 'info', glyph: '待' }],
  [['COMPLETED', 'SUCCEEDED'], { tone: 'success', glyph: '成' }],
  [['FAILED', 'TIMED_OUT', 'DEAD'], { tone: 'high', glyph: '败' }],
  [['CANCELED'], { tone: 'neutral', glyph: '止' }],
]

/** @param {string} status */
export function runStatusLabel(status) {
  return RUN_STATUS_LABELS[status] || status || '未知'
}

/**
 * @param {string} status
 * @returns {{ tone: string, glyph: string }} 运行中的管线态统一落 running/行
 */
export function runPresentation(status) {
  for (const [statuses, view] of RUN_TONE_GROUPS) {
    if (statuses.includes(status)) return { ...view }
  }
  return { tone: 'running', glyph: '行' }
}

/* ============================================================
   Finding 呈现与筛选
   ============================================================ */

const SEVERITY_RANK = { CRITICAL: 0, HIGH: 1, MEDIUM: 2, LOW: 3, INFO: 4 }
const SEVERITY_TONES = { CRITICAL: 'critical', HIGH: 'high', MEDIUM: 'medium', LOW: 'low', INFO: 'info' }
const SEVERITY_TEXT = { CRITICAL: 'Critical', HIGH: 'High', MEDIUM: 'Medium', LOW: 'Low', INFO: 'Info' }

/** 与旧 AgentFindings.isRejected 同义:验证器否决态 */
export function isRejected(finding) {
  return finding?.status === 'rejected'
}

/** SealBadge 语气映射;朱砂(critical)独占 CRITICAL(合同 §5) */
export function severityTone(severity) {
  return SEVERITY_TONES[severity] || 'neutral'
}

/** @param {string} severity */
export function severityText(severity) {
  return SEVERITY_TEXT[severity] || severity || '未知'
}

/** 置信度展示(与旧 AgentFindings.pct 同口径) */
export function confidencePct(value) {
  return value == null ? '-' : Math.round(value * 100) + '%'
}

/**
 * 摘要计数:已否决 Finding 不计入活跃与严重度(与旧 activeFindings 口径一致)。
 * @param {Array<object>} findings
 */
export function findingCounts(findings) {
  const list = Array.isArray(findings) ? findings : []
  const bySeverity = { CRITICAL: 0, HIGH: 0, MEDIUM: 0, LOW: 0, INFO: 0 }
  let active = 0
  let rejected = 0
  let blocking = 0
  for (const finding of list) {
    if (isRejected(finding)) {
      rejected += 1
      continue
    }
    active += 1
    if (finding.blocking) blocking += 1
    if (bySeverity[finding.severity] != null) bySeverity[finding.severity] += 1
  }
  return { bySeverity, active, rejected, blocking, total: list.length }
}

/** 排序:未否决在前,严重度降序,再按 id 稳定 */
export function sortFindings(findings) {
  return [...(Array.isArray(findings) ? findings : [])].sort((a, b) => {
    const rejectedDelta = Number(isRejected(a)) - Number(isRejected(b))
    if (rejectedDelta) return rejectedDelta
    const rankDelta = (SEVERITY_RANK[a.severity] ?? 9) - (SEVERITY_RANK[b.severity] ?? 9)
    if (rankDelta) return rankDelta
    return (a.id ?? 0) - (b.id ?? 0)
  })
}

/**
 * 筛选签:全部 + 出现过的严重度 + 已否决(仅在存在时出现)。
 * @returns {Array<{key: string, label: string, count: number}>}
 */
export function findingFilterChips(findings) {
  const counts = findingCounts(findings)
  const chips = [{ key: 'ALL', label: '全部', count: counts.total }]
  for (const severity of Object.keys(SEVERITY_RANK)) {
    if (counts.bySeverity[severity]) {
      chips.push({ key: severity, label: severityText(severity), count: counts.bySeverity[severity] })
    }
  }
  if (counts.rejected) chips.push({ key: 'REJECTED', label: '已否决', count: counts.rejected })
  return chips
}

/**
 * @param {Array<object>} findings
 * @param {string} filter ALL | REJECTED | 严重度枚举
 */
export function filterFindings(findings, filter) {
  const sorted = sortFindings(findings)
  if (!filter || filter === 'ALL') return sorted
  if (filter === 'REJECTED') return sorted.filter(isRejected)
  return sorted.filter((finding) => !isRejected(finding) && finding.severity === filter)
}

/**
 * 维持选中项:仍存在则保留,否则落到排序后的第一项。
 * @returns {number|null}
 */
export function defaultSelectedId(findings, currentId) {
  const list = Array.isArray(findings) ? findings : []
  if (currentId != null && list.some((finding) => finding.id === currentId)) return currentId
  return sortFindings(list)[0]?.id ?? null
}

/** @param {object} finding */
export function findingLocation(finding) {
  if (!finding?.filePath) return '未标注文件'
  return finding.lineStart ? `${finding.filePath}:${finding.lineStart}` : finding.filePath
}

/** 证据 → 定位锚点 query(path:line;与旧 AgentFindings.fileLink 同构) */
export function evidenceAnchor(evidence) {
  const path = evidence?.filePath || evidence?.path || ''
  if (!path) return ''
  return `${path}:${evidence.lineStart || evidence.line || 1}`
}

/** Finding → 定位锚点:优先第一条带路径的证据,退回 finding 自身位置 */
export function findingAnchor(finding) {
  for (const evidence of finding?.evidence || []) {
    const anchor = evidenceAnchor(evidence)
    if (anchor) return anchor
  }
  if (finding?.filePath) return `${finding.filePath}:${finding.lineStart || 1}`
  return ''
}

/** @param {object} finding */
export function findingStatusText(finding) {
  if (isRejected(finding)) return '已否决'
  return finding?.blocking ? '可阻断' : '待复核'
}

/* ============================================================
   朱批栏批注(来源+时间+状态+锚点;合同 §5 AnnotationRail)
   ============================================================ */

/**
 * 由真实工作台数据派生批注时间线:活跃 Finding(朱批)→ 最近完成的
 * 两个步骤(过程记录)→ 案卷建立。不虚构任何评分或事件。
 * @param {{ findings?: Array<object>, steps?: Array<object>, run?: object|null }} input
 * @returns {Array<{kind: string, id: string, findingId?: number, tone: string, time: string, title: string, body: string, status?: string, anchor?: string}>}
 */
export function railNotes({ findings = [], steps = [], run = null } = {}) {
  const notes = []
  for (const finding of sortFindings(findings)) {
    if (isRejected(finding)) continue
    notes.push({
      kind: 'finding',
      id: `finding-${finding.id}`,
      findingId: finding.id,
      tone: severityTone(finding.severity),
      time: fmtTime(finding.createdAt),
      title: finding.title || '未命名 Finding',
      body: finding.description || '',
      status: findingStatusText(finding),
      anchor: findingAnchor(finding),
    })
  }
  const finished = (Array.isArray(steps) ? steps : [])
    .filter((step) => SETTLED_STEPS.has(step.status) && step.finishedAt)
    .sort((a, b) => (b.sequenceNo ?? 0) - (a.sequenceNo ?? 0))
    .slice(0, 2)
  for (const step of finished) {
    notes.push({
      kind: 'step',
      id: `step-${step.id ?? step.sequenceNo}`,
      tone: 'neutral',
      time: fmtTime(step.finishedAt),
      title: `${stageLabel(step.stepType)}已完成`,
      body: step.outputSummary || '',
    })
  }
  if (run) {
    notes.push({
      kind: 'run',
      id: 'run-created',
      tone: 'neutral',
      time: fmtTime(run.createdAt),
      title: '案卷建立',
      body: `以 head ${shortCommit(run.headSha)} 为审查基线。`,
    })
  }
  return notes
}

/* ============================================================
   审批(可用性判定复用 canApprovePatch,不建第二套策略)
   ============================================================ */

export const APPROVAL_GATES = Object.freeze([
  ['Apply', 'applyStatus'],
  ['Build', 'buildStatus'],
  ['Test', 'testStatus'],
  ['Scan', 'scanStatus'],
])

const GATE_LABELS = { SUCCEEDED: '通过', FAILED: '未通过', NOT_RUN: '未执行', RUNNING: '执行中', SKIPPED: '已跳过' }

/** @param {string} value */
export function gateLabel(value) {
  return GATE_LABELS[value] || value || '未执行'
}

/**
 * @param {object|null} patch
 * @returns {{ approvable: boolean, gates: Array<{key: string, value: string, label: string, pass: boolean, fail: boolean}>, reason: string|null }}
 */
export function approvalModel(patch) {
  const approvable = canApprovePatch(patch)
  const gates = APPROVAL_GATES.map(([key, field]) => {
    const value = patch?.[field] || 'NOT_RUN'
    return { key, value, label: gateLabel(value), pass: value === 'SUCCEEDED', fail: value === 'FAILED' }
  })
  let reason = null
  if (!patch) reason = '暂无候选 Patch：等待 Agent 生成并通过验证后才能审批。'
  else if (!approvable) reason = 'Patch 未通过 apply/目标消失校验或已过期，不能批准。'
  return { approvable, gates, reason }
}

/* ============================================================
   错误分类(按 ApiError 结构化字段分支,不解析提示串)
   ============================================================ */

/**
 * 工作台装载失败 → 页面级状态条。401 返回 null(全局漏斗接管登出)。
 * @param {{ status?: number, message?: string }|null} error
 * @returns {{ kind: 'offline'|'permission'|'error', title: string, message: string }|null}
 */
export function classifyLoadError(error) {
  const status = error?.status
  if (status === 401) return null
  if (status === 0) {
    return { kind: 'offline', title: '当前离线', message: '已加载内容仍可阅读；恢复连接后可重新装载案卷。' }
  }
  if (status === 403) {
    return { kind: 'permission', title: '缺少访问权限', message: '当前账号无权查看该项目的 Agent 数据；请联系管理员开通权限，或切换项目。' }
  }
  return { kind: 'error', title: '装载失败', message: error?.message || '请重试。' }
}

/**
 * 审批动作失败 → 行内错误(动作旁保留重试路径,合同 §6 error/permission/offline)。
 * @param {{ status?: number, message?: string }|null} error
 * @returns {{ kind: 'offline'|'permission'|'error', title: string, message: string }|null}
 */
export function describeActionError(error) {
  const status = error?.status
  if (status === 401) return null
  if (status === 0) {
    return { kind: 'offline', title: '网络不可用', message: '审批未提交；请检查连接后重试，已填写的意见会保留。' }
  }
  if (status === 403) {
    return { kind: 'permission', title: '缺少审批权限', message: '当前账号无权执行 Patch 审批；请联系项目管理员开通权限，或返回复核继续查看证据。' }
  }
  return { kind: 'error', title: '审批失败', message: error?.message || '请稍后重试。' }
}
