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
