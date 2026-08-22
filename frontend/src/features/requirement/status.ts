/** Requirement lifecycle from api-contract.md §3. */
export type RequirementStatus =
  | "DRAFT"
  | "READY"
  | "IN_DEVELOPMENT"
  | "DONE"
  | "CANCELED";

/**
 * Read-only derived review activity of a requirement, aggregated over its pull
 * requests. Eight values at this level; a single pull request has only six —
 * `NO_PR` and `MIXED` exist only once there is something to aggregate.
 *
 * Served by its own endpoint rather than on the requirement payload: it is
 * derived from pull requests and reviews, and `requirement` may not reach into
 * those.
 */
export type ReviewActivity =
  | "NO_PR"
  | "REVIEW_REQUIRED"
  | "FAILED"
  | "CHANGES_REQUESTED"
  | "REVIEWING"
  | "PENDING"
  | "APPROVED"
  | "MIXED";

export const REQUIREMENT_STATUS_LABELS: Record<RequirementStatus, string> = {
  DRAFT: "草稿",
  READY: "就绪",
  IN_DEVELOPMENT: "开发中",
  DONE: "已完成",
  CANCELED: "已取消",
};

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

/** Badge modifier per status, so state is never carried by color alone. */
export const REQUIREMENT_STATUS_TONES: Record<RequirementStatus, string> = {
  DRAFT: "neutral",
  READY: "info",
  IN_DEVELOPMENT: "warning",
  DONE: "success",
  CANCELED: "danger",
};

/**
 * Targets reachable through `POST /status`. `IN_DEVELOPMENT` is missing on
 * purpose: its only entry point is the first assignee (api-contract.md §3).
 */
export const STATUS_TRANSITIONS: Record<RequirementStatus, readonly RequirementStatus[]> = {
  DRAFT: ["READY", "CANCELED"],
  READY: ["CANCELED"],
  IN_DEVELOPMENT: ["DONE", "CANCELED"],
  DONE: [],
  CANCELED: [],
};

/** A terminal requirement accepts no further edit, revision, or transition. */
export function isTerminal(status: RequirementStatus): boolean {
  return status === "DONE" || status === "CANCELED";
}
