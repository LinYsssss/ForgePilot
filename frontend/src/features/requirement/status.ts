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
