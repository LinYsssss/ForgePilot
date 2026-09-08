import type { ReviewActivity } from "../review/api";

/** 取自 API.md 的需求生命周期。 */
export type RequirementStatus =
  | "DRAFT"
  | "READY"
  | "IN_DEVELOPMENT"
  | "DONE"
  | "CANCELED";

export const REQUIREMENT_STATUSES: readonly RequirementStatus[] = [
  "DRAFT",
  "READY",
  "IN_DEVELOPMENT",
  "DONE",
  "CANCELED",
];

export const REQUIREMENT_STATUS_LABELS: Record<RequirementStatus, string> = {
  DRAFT: "草稿",
  READY: "就绪",
  IN_DEVELOPMENT: "开发中",
  DONE: "已完成",
  CANCELED: "已取消",
};

/** 每个状态对应的徽标修饰符，使状态绝不仅靠颜色来传达。 */
export const REQUIREMENT_STATUS_TONES: Record<RequirementStatus, string> = {
  DRAFT: "neutral",
  READY: "info",
  IN_DEVELOPMENT: "warning",
  DONE: "success",
  CANCELED: "danger",
};

/**
 * 可以通过 `POST /status` 抵达的目标状态。`IN_DEVELOPMENT` 刻意缺席：
 * 进入它的唯一入口是首次指派（API.md）。
 */
export const STATUS_TRANSITIONS: Record<RequirementStatus, readonly RequirementStatus[]> = {
  DRAFT: ["READY", "CANCELED"],
  READY: ["CANCELED"],
  IN_DEVELOPMENT: ["DONE", "CANCELED"],
  DONE: [],
  CANCELED: [],
};

/** 处于终态的需求不再接受任何编辑、修订或状态流转。 */
export function isTerminal(status: RequirementStatus): boolean {
  return status === "DONE" || status === "CANCELED";
}

/** Presentation only: lifecycle and review activity remain independently authoritative. */
export function requirementPhase(status: RequirementStatus, activity?: ReviewActivity | null): string {
  if (status !== "IN_DEVELOPMENT") return REQUIREMENT_STATUS_LABELS[status];
  switch (activity) {
    case "PENDING": case "REVIEWING": return "审查中";
    case "CHANGES_REQUESTED": return "开发中 · 退回修改";
    case "APPROVED": return "审查通过 · 待负责人确认完成";
    case "FAILED": return "审查失败";
    case "REVIEW_REQUIRED": return "待重新审查";
    case "MIXED": return "多 PR 处理中";
    case "NO_PR": return "开发中";
    default: return "开发中 · 审查进度未获取";
  }
}

export function requirementHandler(
  status: RequirementStatus,
  activity: ReviewActivity | null | undefined,
  assigneeName: string | null,
  reviewerName: string | null,
): string {
  if (isTerminal(status)) return "—";
  if (status !== "IN_DEVELOPMENT") return "项目负责人";
  switch (activity) {
    case "PENDING": case "REVIEWING": return reviewerName ?? "项目负责人";
    case "APPROVED": case "FAILED": return "项目负责人";
    case "MIXED": return "开发与审查负责人，详见各 PR";
    default: return assigneeName ?? "项目负责人";
  }
}
