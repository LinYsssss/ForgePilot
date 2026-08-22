import { requestJson } from "../../lib/http";

/** Execution state of a Review (api-contract.md §2.2). Orthogonal to `decision`. */
export type ReviewStatus = "PENDING" | "RUNNING" | "COMPLETED" | "FAILED";

/** The one-shot human verdict. `PENDING` is the absence of one, not a third verdict. */
export type ReviewDecision = "PENDING" | "APPROVE" | "REQUEST_CHANGES";

/** Human handling lifecycle of a Finding (api-contract.md §3.1). */
export type FindingStatus =
  | "OPEN"
  | "CONFIRMED"
  | "IN_PROGRESS"
  | "FIXED"
  | "VERIFIED"
  | "CLOSED"
  | "REJECTED";

/**
 * Cross-round lineage of a Finding. PRD §5 forbids merging this with `status`
 * into one field or one label: one says what a person decided, the other says
 * where the finding came from.
 */
export type FindingContinuity = "NEW" | "PERSISTING" | "SUPPRESSED";

export type FindingType = "CODE_QUALITY" | "REQUIREMENT";

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

/** Requirement-level aggregation. `NO_PR` and `MIXED` do not exist per PR. */
export type ReviewActivity =
  | "NO_PR"
  | "REVIEW_REQUIRED"
  | "FAILED"
  | "CHANGES_REQUESTED"
  | "REVIEWING"
  | "PENDING"
  | "APPROVED"
  | "MIXED";

/** A single pull request has six values; `NO_PR` and `MIXED` exist only per requirement. */
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
  /** Null when the patch could not place the finding on a verifiable line. */
  line: number | null;
  evidence: string | null;
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
 * The coverage manifest. `notReviewed` being an empty array and the manifest
 * being absent are different answers and D002 forbids collapsing them, so the
 * whole object is nullable and the array inside it never is.
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
  /** Derived on every read by comparing the identity against the pull request. */
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

/** One newest-first row returned by the project-wide Review index. */
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
 * A requirement's aggregated activity plus the per-state counts. The counts are
 * always present and always carry all six single-pull-request values, zeros
 * included, so there is no missing-key branch here.
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

/** Trigger, retry after a failure and re-review after a new revision are one call. */
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

/** Keyed by requirement id, so a list screen reads the whole column in one call. */
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
