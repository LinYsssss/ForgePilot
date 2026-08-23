import { requestJson } from "../../lib/http";
import type { RequirementStatus } from "./status";

export interface AcceptanceCriterion {
  id: number;
  acKey: string;
  sortOrder: number;
  text: string;
}

export interface Revision {
  id: number;
  seq: number;
  title: string;
  background: string | null;
  description: string | null;
  createdBy: number;
  createdByUsername: string;
  changeReason: string | null;
  createdAt: string;
  acceptanceCriteria: AcceptanceCriterion[];
}

export interface RequirementSummary {
  id: number;
  title: string;
  status: RequirementStatus;
  assigneeId: number | null;
  assigneeUsername: string | null;
  currentRevisionSeq: number;
  updatedAt: string;
}

export interface RequirementDetail {
  id: number;
  status: RequirementStatus;
  assigneeId: number | null;
  assigneeUsername: string | null;
  createdAt: string;
  updatedAt: string;
  currentRevision: Revision;
}

/**
 * 正在编辑中的一条验收条件。`acKey` 是服务端为已存在的行回传的稳定身份；
 * 新增的行没有它，由服务端分配。`sortOrder` 由数组顺序推导，绝不上送。
 */
export interface AcceptanceCriterionDraft {
  acKey?: string;
  text: string;
}

export interface RevisionContent {
  title: string;
  background: string | null;
  description: string | null;
  acceptanceCriteria: AcceptanceCriterionDraft[];
}

/**
 * 命中的一条确定性规则。只有针对单条验收条件的规则才会设置 `acKey`，
 * 因此它是可空的，而不是缺席的。
 */
export interface QualityRuleFinding {
  rule: "MISSING_DESCRIPTION" | "DUPLICATE_CRITERION" | "PROMPT_BUDGET_EXCEEDED";
  acKey: string | null;
  message: string;
}

export interface QualityAiIssue {
  acKey: string | null;
  message: string;
}

/**
 * 对某一次修订做一次需求质量检查的结果。刻意没有评分、也没有总评：
 * 一个数字恰恰会被当成闸门来读，而这个结果只是建议，不改动任何状态。
 */
export interface QualityReport {
  requirementId: number;
  revisionId: number;
  revisionSeq: number;
  qualityVersion: string;
  checkedAt: string;
  rules: QualityRuleFinding[];
  ai: { summary: string | null; issues: QualityAiIssue[] } | null;
}

/** provider 针对某个不可变需求修订给出的一次回答；不做任何持久化。 */
export interface ImplementationGuidance {
  requirementId: number;
  revisionId: number;
  revisionSeq: number;
  guidance: string;
}

function requirementsPath(projectId: number): string {
  return `/api/projects/${projectId}/requirements`;
}

function body(content: RevisionContent, changeReason?: string): string {
  const payload = {
    title: content.title,
    background: content.background,
    description: content.description,
    acceptanceCriteria: content.acceptanceCriteria.map((criterion) =>
      criterion.acKey === undefined
        ? { text: criterion.text }
        : { acKey: criterion.acKey, text: criterion.text },
    ),
    ...(changeReason === undefined ? {} : { changeReason }),
  };
  return JSON.stringify(payload);
}

export function listRequirements(projectId: number): Promise<RequirementSummary[]> {
  return requestJson<RequirementSummary[]>(requirementsPath(projectId));
}

export function getRequirement(
  projectId: number,
  requirementId: number,
): Promise<RequirementDetail> {
  return requestJson<RequirementDetail>(`${requirementsPath(projectId)}/${requirementId}`);
}

export function listRevisions(
  projectId: number,
  requirementId: number,
): Promise<Revision[]> {
  return requestJson<Revision[]>(
    `${requirementsPath(projectId)}/${requirementId}/revisions`,
  );
}

export function createRequirement(
  projectId: number,
  content: RevisionContent,
): Promise<RequirementDetail> {
  return requestJson<RequirementDetail>(requirementsPath(projectId), {
    method: "POST",
    body: body(content),
  });
}

/** 对 DRAFT 状态需求的原地编辑。 */
export function editDraft(
  projectId: number,
  requirementId: number,
  content: RevisionContent,
): Promise<RequirementDetail> {
  return requestJson<RequirementDetail>(
    `${requirementsPath(projectId)}/${requirementId}`,
    { method: "PATCH", body: body(content) },
  );
}

/** 需求离开 DRAFT 之后，发布一个新的不可变修订。 */
export function publishRevision(
  projectId: number,
  requirementId: number,
  content: RevisionContent,
  changeReason: string,
): Promise<RequirementDetail> {
  return requestJson<RequirementDetail>(
    `${requirementsPath(projectId)}/${requirementId}/revisions`,
    { method: "POST", body: body(content, changeReason) },
  );
}

export function changeStatus(
  projectId: number,
  requirementId: number,
  status: RequirementStatus,
): Promise<RequirementDetail> {
  return requestJson<RequirementDetail>(
    `${requirementsPath(projectId)}/${requirementId}/status`,
    { method: "POST", body: JSON.stringify({ status }) },
  );
}

export function assign(
  projectId: number,
  requirementId: number,
  userId: number,
): Promise<RequirementDetail> {
  return requestJson<RequirementDetail>(
    `${requirementsPath(projectId)}/${requirementId}/assignee`,
    { method: "POST", body: JSON.stringify({ userId }) },
  );
}

/**
 * 针对需求的当前修订运行质量检查。用 POST，因为它会花掉一次 provider 调用
 * 并把结果写到那个修订上；结果归属于该修订，而一次 DRAFT 编辑会把它清空。
 */
export function checkQuality(
  projectId: number,
  requirementId: number,
): Promise<QualityReport> {
  return requestJson<QualityReport>(
    `${requirementsPath(projectId)}/${requirementId}/quality`,
    { method: "POST", body: JSON.stringify({}) },
  );
}

/**
 * 为当前修订生成一次实现建议。这里**刻意**没有会话 id、没有输入框、
 * 没有历史记录，也没有流式输出通道。
 */
export function generateGuidance(
  projectId: number,
  requirementId: number,
): Promise<ImplementationGuidance> {
  return requestJson<ImplementationGuidance>(
    `${requirementsPath(projectId)}/${requirementId}/guidance`,
    { method: "POST", body: JSON.stringify({}) },
  );
}

/** 把一个已存修订转成可编辑的行，并保留每一行稳定的 `acKey`。 */
export function toDraft(revision: Revision): RevisionContent {
  return {
    title: revision.title,
    background: revision.background,
    description: revision.description,
    acceptanceCriteria: revision.acceptanceCriteria.map((criterion) => ({
      acKey: criterion.acKey,
      text: criterion.text,
    })),
  };
}
