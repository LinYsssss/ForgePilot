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
 * 一条 finding 旁边会展示四个标记，而 PRD.md:131 / :135 禁止合并其中任何两个：
 * Review 的执行状态、Finding 的人工状态、Finding 的跨轮次血缘，
 * 以及 Review 那次一次性的 Decision。它们在这里是四张标签表、
 * 在模板里是四个容器，好让「把它们合成一个风险徽标」
 * 不可能被一次顺手的改动意外做成。
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

/** 那张稠密六键计数表的遍历顺序，使列的顺序保持稳定。 */
export const PULL_REQUEST_ACTIVITIES: readonly PullRequestActivity[] = [
  "REVIEW_REQUIRED",
  "FAILED",
  "CHANGES_REQUESTED",
  "REVIEWING",
  "PENDING",
  "APPROVED",
];

/** PRD §3 矩阵中的一格：这一步叫什么，以及谁可以执行它。 */
export interface FindingMove {
  target: FindingStatus;
  action: FindingAction;
  allowed: readonly ProjectRole[];
  /** `REJECTED → OPEN` 只用于重开被继承的抑制项（PRD §5）。 */
  onlySuppressed?: true;
}

const BY_REVIEWERS: readonly ProjectRole[] = ["LEADER", "REVIEWER"];
const BY_DEVELOPER: readonly ProjectRole[] = ["DEVELOPER"];

/**
 * PRD §3 的逐格抄录，与 `FindingLifecycleService.MOVES` 一一对应。
 *
 * <p>那两格看上去像笔误、但其实不是：LEADER 既不能认领 finding，
 * 也不能把它标记为已修复。把这两个按钮摆给 LEADER，只会换来服务端的 403；
 * 而展示一个规格明确保留的操作，正是 UI 教会用户「一个并不存在的权限」的方式。
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
 * 当前查看者在这条 finding 上**实际**能执行的操作。一次并非继承而来的驳回，
 * 对任何角色都是不可逆的终态，因此它给出的是「没有任何可执行操作」，
 * 而不是一个必定失败的按钮。未知角色同样什么都不给：
 * 在这里收紧是安全方向，放宽不是。
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

/** 一个简短但仍可辨识的 commit id；完整值保留在 `title` 属性里。 */
export function shortSha(sha: string): string {
  return sha.length > 12 ? sha.slice(0, 12) : sha;
}
