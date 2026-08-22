import type { ProjectRole } from "../project/api";
import type {
  AcVerdict,
  FindingAction,
  FindingContinuity,
  FindingStatus,
  FindingType,
  PullRequestActivity,
  ReviewActivity,
  ReviewDecision,
  ReviewStatus,
} from "./api";

export const REVIEW_ACTIVITIES: readonly ReviewActivity[] = [
  "NO_PR",
  "REVIEW_REQUIRED",
  "FAILED",
  "CHANGES_REQUESTED",
  "REVIEWING",
  "PENDING",
  "APPROVED",
  "MIXED",
];

export const REVIEW_ACTIVITY_LABELS: Record<ReviewActivity, string> = {
  NO_PR: "无关联 PR",
  REVIEW_REQUIRED: "待审查",
  FAILED: "审查失败",
  CHANGES_REQUESTED: "已退回",
  REVIEWING: "审查中",
  PENDING: "排队中",
  APPROVED: "已通过",
  MIXED: "多个状态",
};

export const REVIEW_ACTIVITY_TONES: Record<ReviewActivity, string> = {
  NO_PR: "neutral",
  REVIEW_REQUIRED: "warning",
  FAILED: "danger",
  CHANGES_REQUESTED: "danger",
  REVIEWING: "info",
  PENDING: "neutral",
  APPROVED: "success",
  MIXED: "warning",
};

/**
 * Four marks are shown next to a finding and PRD.md:131 / :135 forbid merging any
 * of them: the execution status of the Review, the human status of the Finding,
 * the Finding's cross-round lineage, and the Review's one-shot Decision. They are
 * four label maps here, and four containers in the template, so that "merge them
 * into one risk badge" is not something a single edit can do by accident.
 */
export const REVIEW_STATUS_LABELS: Record<ReviewStatus, string> = {
  PENDING: "排队中",
  RUNNING: "审查中",
  COMPLETED: "已完成",
  FAILED: "执行失败",
};

export const REVIEW_STATUS_TONES: Record<ReviewStatus, string> = {
  PENDING: "neutral",
  RUNNING: "info",
  COMPLETED: "success",
  FAILED: "danger",
};

export const REVIEW_DECISION_LABELS: Record<ReviewDecision, string> = {
  PENDING: "尚无终局决定",
  APPROVE: "已通过",
  REQUEST_CHANGES: "已退回",
};

export const REVIEW_DECISION_TONES: Record<ReviewDecision, string> = {
  PENDING: "neutral",
  APPROVE: "success",
  REQUEST_CHANGES: "danger",
};

export const FINDING_STATUS_LABELS: Record<FindingStatus, string> = {
  OPEN: "待确认",
  CONFIRMED: "已确认",
  IN_PROGRESS: "处理中",
  FIXED: "已修复",
  VERIFIED: "已验证",
  CLOSED: "已关闭",
  REJECTED: "已驳回",
};

export const FINDING_STATUS_TONES: Record<FindingStatus, string> = {
  OPEN: "warning",
  CONFIRMED: "info",
  IN_PROGRESS: "info",
  FIXED: "info",
  VERIFIED: "success",
  CLOSED: "neutral",
  REJECTED: "danger",
};

export const FINDING_CONTINUITY_LABELS: Record<FindingContinuity, string> = {
  NEW: "本轮新增",
  PERSISTING: "沿用未修复",
  SUPPRESSED: "继承抑制",
};

export const FINDING_CONTINUITY_TONES: Record<FindingContinuity, string> = {
  NEW: "info",
  PERSISTING: "warning",
  SUPPRESSED: "neutral",
};

export const FINDING_TYPE_LABELS: Record<FindingType, string> = {
  REQUIREMENT: "需求违规",
  CODE_QUALITY: "代码质量",
};

export const AC_VERDICT_LABELS: Record<AcVerdict, string> = {
  COVERED: "已覆盖",
  NOT_FOUND: "未找到",
  AT_RISK: "有风险",
};

export const AC_VERDICT_TONES: Record<AcVerdict, string> = {
  COVERED: "success",
  NOT_FOUND: "danger",
  AT_RISK: "warning",
};

export const FINDING_ACTION_LABELS: Record<FindingAction, string> = {
  CONFIRM: "确认",
  REJECT: "驳回",
  CLAIM: "认领",
  MARK_FIXED: "标记已修复",
  VERIFY: "验证通过",
  SEND_BACK: "打回",
  CLOSE: "关闭",
  REOPEN: "重开",
};

export const PULL_REQUEST_ACTIVITY_LABELS: Record<PullRequestActivity, string> = {
  REVIEW_REQUIRED: "待审查",
  FAILED: "审查失败",
  CHANGES_REQUESTED: "已退回",
  REVIEWING: "审查中",
  PENDING: "排队中",
  APPROVED: "已通过",
};

/** Iteration order for the dense six-key count map, so the column order is stable. */
export const PULL_REQUEST_ACTIVITIES: readonly PullRequestActivity[] = [
  "REVIEW_REQUIRED",
  "FAILED",
  "CHANGES_REQUESTED",
  "REVIEWING",
  "PENDING",
  "APPROVED",
];

/** One cell of PRD §3's matrix: what the step is called and who may take it. */
export interface FindingMove {
  target: FindingStatus;
  action: FindingAction;
  allowed: readonly ProjectRole[];
  /** `REJECTED → OPEN` only reopens an inherited suppression (PRD §5). */
  onlySuppressed?: true;
}

const BY_REVIEWERS: readonly ProjectRole[] = ["LEADER", "REVIEWER"];
const BY_DEVELOPER: readonly ProjectRole[] = ["DEVELOPER"];

/**
 * PRD §3 transcribed cell by cell, mirroring `FindingLifecycleService.MOVES`.
 *
 * <p>The two cells that read like mistakes are not: a LEADER may neither claim a
 * finding nor mark one fixed. Offering those buttons to a LEADER would only
 * produce a 403 from the server, and showing an action the specification withholds
 * is how a UI teaches a permission that does not exist.
 */
export const FINDING_MOVES: Record<FindingStatus, readonly FindingMove[]> = {
  OPEN: [
    { target: "CONFIRMED", action: "CONFIRM", allowed: BY_REVIEWERS },
    { target: "REJECTED", action: "REJECT", allowed: BY_REVIEWERS },
  ],
  CONFIRMED: [
    { target: "IN_PROGRESS", action: "CLAIM", allowed: BY_DEVELOPER },
    { target: "REJECTED", action: "REJECT", allowed: BY_REVIEWERS },
  ],
  IN_PROGRESS: [{ target: "FIXED", action: "MARK_FIXED", allowed: BY_DEVELOPER }],
  FIXED: [
    { target: "VERIFIED", action: "VERIFY", allowed: BY_REVIEWERS },
    { target: "IN_PROGRESS", action: "SEND_BACK", allowed: BY_REVIEWERS },
  ],
  VERIFIED: [{ target: "CLOSED", action: "CLOSE", allowed: BY_REVIEWERS }],
  CLOSED: [],
  REJECTED: [
    { target: "OPEN", action: "REOPEN", allowed: BY_REVIEWERS, onlySuppressed: true },
  ],
};

/**
 * The moves this viewer may actually perform on this finding. A rejection that was
 * never an inherited suppression is an irreversible terminal state for every role,
 * so it yields no move at all rather than a button that always fails. An unknown
 * role yields nothing: narrowing is the safe direction here, widening is not.
 */
export function availableMoves(
  status: FindingStatus,
  continuity: FindingContinuity,
  role: ProjectRole | null,
): readonly FindingMove[] {
  if (role === null) {
    return [];
  }
  return FINDING_MOVES[status].filter(
    (move) =>
      move.allowed.includes(role) &&
      (move.onlySuppressed !== true || continuity === "SUPPRESSED"),
  );
}

/** A short, still-recognisable commit id; the full value stays in the `title`. */
export function shortSha(sha: string): string {
  return sha.length > 12 ? sha.slice(0, 12) : sha;
}
