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
 * One acceptance criterion being edited. `acKey` is the stable identity handed
 * back for a row that already exists; a new row has none and the server assigns
 * one. `sortOrder` is derived from array order and is never sent.
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
 * One deterministic rule hit. `acKey` is set only by rules that are about one
 * criterion, so it is nullable rather than absent.
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
 * The result of one Requirement Quality check on one revision. There is no score
 * and no overall verdict on purpose: a number is exactly what gets read as a gate,
 * and this result is advice that moves no status.
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

/** One provider answer about one immutable Requirement revision; never persisted. */
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

/** In-place edit of a DRAFT requirement. */
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

/** Publishes a new immutable revision once the requirement left DRAFT. */
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
 * Runs the quality check against the requirement's current revision. POST because
 * it spends a provider call and writes onto that revision; the result belongs to
 * the revision and a DRAFT edit clears it again.
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
 * Generates one implementation answer for the current revision. This deliberately
 * has no conversation id, input box, history, or streaming path.
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

/** Turns a stored revision into editable rows, keeping each stable `acKey`. */
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
