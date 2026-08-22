/** Requirement lifecycle from api-contract.md §3. */
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
