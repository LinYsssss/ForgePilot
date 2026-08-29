import { requestJson } from "../../lib/http";

/** 一次 Review 的执行状态（API.md）。与 `decision` 正交。 */
export type ReviewStatus = "PENDING" | "RUNNING" | "COMPLETED" | "FAILED";

/** 一次性的人工裁定。`PENDING` 表示「尚未作出裁定」，而不是第三种裁定。 */
export type ReviewDecision = "PENDING" | "APPROVE" | "REQUEST_CHANGES";

/** 一条 Finding 的人工处理生命周期（API.md）。 */
export type FindingStatus =
  | "OPEN"
  | "CONFIRMED"
  | "IN_PROGRESS"
  | "FIXED"
  | "VERIFIED"
  | "CLOSED"
  | "REJECTED";

/**
 * 一条 Finding 的跨轮次血缘。PRD §5 禁止把它与 `status` 合并成一个字段
 * 或一个标签：一个说的是人做了什么判断，另一个说的是这条问题从哪来。
 */
export type FindingContinuity = "NEW" | "PERSISTING" | "SUPPRESSED";

export type FindingType = "CODE_QUALITY" | "REQUIREMENT";

/** `ReviewPrompts` 两个 schema 里的封闭词表；它同时是 `finding_key` 的输入之一。 */
export type FindingCategory =
  | "CORRECTNESS"
  | "SECURITY"
  | "ERROR_HANDLING"
  | "CONCURRENCY"
  | "PERFORMANCE"
  | "API_CONTRACT"
  | "TEST_COVERAGE"
  | "MAINTAINABILITY"
  | "REQUIREMENT_GAP";

/**
 * 模型自报的把握，分档而非数字：它没有经过校准，一个小数会让人以为它校准过。
 * 它与 Finding 人工状态、Review Decision 是三个互不替代的维度，不合并展示。
 */
export type FindingConfidence = "HIGH" | "MEDIUM" | "LOW";

export type AcVerdict = "COVERED" | "NOT_FOUND" | "AT_RISK";

export type FindingAction =
  | "CONFIRM"
  | "REJECT"
  | "CLAIM"
  | "MARK_FIXED"
  | "VERIFY"
  | "SEND_BACK"
  | "CLOSE"
  | "REOPEN";

/** 需求层面的聚合。`NO_PR` 与 `MIXED` 在单个 PR 层面并不存在。 */
export type ReviewActivity =
  | "NO_PR"
  | "REVIEW_REQUIRED"
  | "FAILED"
  | "CHANGES_REQUESTED"
  | "REVIEWING"
  | "PENDING"
  | "APPROVED"
  | "MIXED";

/** 单个 PR 只有六个取值；`NO_PR` 与 `MIXED` 只存在于需求层面。 */
export type PullRequestActivity =
  | "REVIEW_REQUIRED"
  | "FAILED"
  | "CHANGES_REQUESTED"
  | "REVIEWING"
  | "PENDING"
  | "APPROVED";

export interface Finding {
  id: number;
  findingType: FindingType;
  path: string | null;
  /** 当 patch 无法把该 finding 定位到一个可核实的行上时为 null。 */
  line: number | null;
  evidence: string | null;
  /** V9 起落库。之前这个语义标签算完 `finding_key` 就被丢掉了。 */
  category: FindingCategory | null;
  /** 模型自己写的问题说明；V9 之前的 Finding 没有，故可空。 */
  explanation: string | null;
  /** 模型给的修复建议。只是建议：AI 不产出补丁、不自动改码。 */
  suggestion: string | null;
  confidence: FindingConfidence | null;
  status: FindingStatus;
  continuity: FindingContinuity;
  requirementId: number | null;
  requirementRevisionId: number | null;
  acId: number | null;
  acKey: string | null;
  assigneeId: number | null;
  carriedFromFindingId: number | null;
  findingKey: string;
  evidenceHash: string | null;
  basisHash: string | null;
}

export interface AcVerdictRow {
  acId: number;
  acKey: string;
  verdict: AcVerdict;
}

export interface FileCoverage {
  path: string;
  patchTruncated: boolean;
}

/**
 * 覆盖清单。「`notReviewed` 是空数组」与「整份清单缺席」是两个不同的答案，
 * 契约禁止把它们混为一谈，因此整个对象是可空的，而它内部的数组永远不为空缺。
 */
export interface Coverage {
  truncated: boolean;
  files: FileCoverage[];
  notReviewed: string[];
}

export interface ReviewDetail {
  id: number;
  pullRequestId: number;
  headSha: string;
  reviewInputFingerprint: string;
  requirementId: number | null;
  requirementRevisionId: number | null;
  status: ReviewStatus;
  decision: ReviewDecision;
  decisionBy: number | null;
  decisionAt: string | null;
  decisionComment: string | null;
  /** 每次读取时把身份与 PR 比对后现算得出。 */
  isCurrent: boolean;
  contextSnapshot: unknown;
  coverage: Coverage | null;
  acVerdicts: AcVerdictRow[] | null;
  findings: Finding[];
  engine: string | null;
  promptVersion: string | null;
  model: string | null;
  executionAttempt: number;
}

export interface ReviewSummary {
  id: number;
  headSha: string;
  requirementRevisionId: number | null;
  status: ReviewStatus;
  decision: ReviewDecision;
  isCurrent: boolean;
  createdAt: string;
}

/** 项目级审查索引返回的一行（最新在前）。 */
export interface ProjectReviewRow {
  id: number;
  pullRequestId: number;
  pullRequestNumber: number;
  headSha: string;
  requirementId: number | null;
  status: ReviewStatus;
  decision: ReviewDecision;
  isCurrent: boolean;
  createdAt: string;
}

export interface ReviewRequested {
  reviewId: number;
  status: ReviewStatus;
  executionAttempt: number;
}

export interface DecisionResult {
  decision: ReviewDecision;
  decisionBy: number | null;
  decisionAt: string | null;
}

export interface FindingEvent {
  id: number;
  actorId: number;
  action: FindingAction;
  fromStatus: FindingStatus;
  toStatus: FindingStatus;
  comment: string | null;
  createdAt: string;
}

/**
 * 一条需求的聚合活动状态，外加各状态的计数。计数永远存在，
 * 且永远携带全部六个单 PR 取值（包括为零的项），
 * 因此这里不存在「键不存在」的分支处理。
 */
export interface ActivityView {
  activity: ReviewActivity;
  counts: Record<PullRequestActivity, number>;
}

function projectPath(projectId: number): string {
  return `/api/projects/${projectId}`;
}

export function getReview(projectId: number, reviewId: number): Promise<ReviewDetail> {
  return requestJson<ReviewDetail>(`${projectPath(projectId)}/reviews/${reviewId}`);
}

/** The primary 代码审查 index; the backend returns newest first. */
export function listProjectReviews(projectId: number): Promise<ProjectReviewRow[]> {
  return requestJson<ProjectReviewRow[]>(`${projectPath(projectId)}/reviews`);
}

export function listPullRequestReviews(
  projectId: number,
  pullRequestId: number,
): Promise<ReviewSummary[]> {
  return requestJson<ReviewSummary[]>(
    `${projectPath(projectId)}/pull-requests/${pullRequestId}/reviews`,
  );
}

/** 触发、失败后重试、新修订后的重新审查，都是同一次调用。 */
export function requestReview(
  projectId: number,
  pullRequestId: number,
): Promise<ReviewRequested> {
  return requestJson<ReviewRequested>(
    `${projectPath(projectId)}/pull-requests/${pullRequestId}/reviews`,
    { method: "POST", body: JSON.stringify({}) },
  );
}

export function decideReview(
  projectId: number,
  reviewId: number,
  decision: "APPROVE" | "REQUEST_CHANGES",
  comment: string,
): Promise<DecisionResult> {
  return requestJson<DecisionResult>(
    `${projectPath(projectId)}/reviews/${reviewId}/decision`,
    {
      method: "POST",
      body: JSON.stringify(comment === "" ? { decision } : { decision, comment }),
    },
  );
}

export function moveFinding(
  projectId: number,
  findingId: number,
  status: FindingStatus,
  comment: string,
): Promise<{ status: FindingStatus }> {
  return requestJson<{ status: FindingStatus }>(
    `${projectPath(projectId)}/findings/${findingId}/status`,
    {
      method: "POST",
      body: JSON.stringify(comment === "" ? { status } : { status, comment }),
    },
  );
}

export function listFindingEvents(
  projectId: number,
  findingId: number,
): Promise<FindingEvent[]> {
  return requestJson<FindingEvent[]>(
    `${projectPath(projectId)}/findings/${findingId}/events`,
  );
}

/** 以需求 id 为键，使列表界面一次调用就能读到整列数据。 */
export function listReviewActivity(
  projectId: number,
): Promise<Record<string, ActivityView>> {
  return requestJson<Record<string, ActivityView>>(
    `${projectPath(projectId)}/review-activity`,
  );
}

export function getRequirementReviewActivity(
  projectId: number,
  requirementId: number,
): Promise<ActivityView> {
  return requestJson<ActivityView>(
    `${projectPath(projectId)}/requirements/${requirementId}/review-activity`,
  );
}

/**
 * 一条验收条件当前的状态。
 *
 * `verdict` 为 `null` 表示当前修订**还没有被审查过**，与 `NOT_FOUND`
 * （审查过了，但 diff 里没有东西实现它）不是一回事，界面上必须分开说。
 */
export interface AcCoverage {
  acKey: string;
  text: string;
  verdict: AcVerdict | null;
  openFindings: number;
}

export interface RequirementCoverage {
  requirementId: number;
  requirementRevisionId: number | null;
  lastReviewId: number | null;
  criteria: AcCoverage[];
}

/** 二项比例的 95% Wilson 区间；`null` 表示样本量为 0，没有区间可言。 */
export interface Interval {
  low: number;
  high: number;
}

/**
 * 校准表的一个置信度分箱。
 *
 * `confirmedRate` 与 `interval` 在 `adjudicated` 为 0 时都是 `null`：一个没有样本的
 * 分箱既没有比例也没有区间，显示 0% 会被读成「模型在这一档上从来没对过」。
 */
export interface CalibrationBin {
  confidence: FindingConfidence;
  adjudicated: number;
  confirmed: number;
  confirmedRate: number | null;
  interval: Interval | null;
}

/**
 * `awaitingAdjudication` 与 `withoutConfidence` 让一张空表能说清自己为什么空：
 * 前者等人裁决，后者是更早的 prompt 版本产出的、本就没有置信度的 finding。
 */
export interface ReviewCalibration {
  bins: CalibrationBin[];
  awaitingAdjudication: number;
  withoutConfidence: number;
}

export function getRequirementCoverage(
  projectId: number,
  requirementId: number,
): Promise<RequirementCoverage> {
  return requestJson<RequirementCoverage>(
    `${projectPath(projectId)}/requirements/${requirementId}/coverage`,
  );
}

export function getReviewCalibration(projectId: number): Promise<ReviewCalibration> {
  return requestJson<ReviewCalibration>(`${projectPath(projectId)}/review-calibration`);
}
