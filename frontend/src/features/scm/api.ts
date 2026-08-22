import { requestJson } from "../../lib/http";

/** `GITLAB` is reserved in the schema but no code can serve it, so it is not here. */
export type ScmProvider = "GITHUB";

export const SCM_PROVIDERS: readonly ScmProvider[] = ["GITHUB"];

/**
 * What a LEADER may see of the connection. Neither the token nor the webhook
 * secret is in this shape: they are write-only and the server never echoes them,
 * so there is no field here that could accidentally render one.
 */
export interface ScmRepository {
  id: number;
  projectId: number;
  provider: ScmProvider;
  instanceIdentity: string;
  externalId: string;
  apiBase: string;
  createdAt: string;
  updatedAt: string;
}

export interface PullRequest {
  id: number;
  projectId: number;
  repositoryId: number;
  externalNumber: number;
  baseSha: string;
  headSha: string;
  reviewInputFingerprint: string;
  requirementId: number | null;
  authorExternalUserId: string | null;
  authorUsername: string | null;
  authorUserId: number | null;
  sourceUpdatedAt: string | null;
  updatedAt: string;
}

export interface ScmRepositoryRegistration {
  provider: ScmProvider;
  externalId: string;
  apiBase: string;
  token: string;
  webhookSecret: string;
}

/** Every field is optional; an omitted one leaves that part of the connection alone. */
export interface ScmRepositoryPatch {
  provider?: ScmProvider;
  externalId?: string;
  apiBase?: string;
  token?: string;
  webhookSecret?: string;
}

function projectPath(projectId: number): string {
  return `/api/projects/${projectId}`;
}

export function registerScmRepository(
  projectId: number,
  registration: ScmRepositoryRegistration,
): Promise<ScmRepository> {
  return requestJson<ScmRepository>(`${projectPath(projectId)}/scm/repositories`, {
    method: "POST",
    body: JSON.stringify(registration),
  });
}

export function updateScmRepository(
  projectId: number,
  repositoryId: number,
  patch: ScmRepositoryPatch,
): Promise<ScmRepository> {
  return requestJson<ScmRepository>(
    `${projectPath(projectId)}/scm/repositories/${repositoryId}`,
    { method: "PATCH", body: JSON.stringify(patch) },
  );
}

export function getPullRequest(
  projectId: number,
  pullRequestId: number,
): Promise<PullRequest> {
  return requestJson<PullRequest>(
    `${projectPath(projectId)}/pull-requests/${pullRequestId}`,
  );
}

/**
 * Sets or clears the pull request's requirement (PRD P1). `null` is a legal value
 * and means "this pull request implements no requirement"; it is a correction the
 * audit records like any other, not a missing field.
 */
export function setPullRequestRequirement(
  projectId: number,
  pullRequestId: number,
  requirementId: number | null,
  reason: string,
): Promise<PullRequest> {
  return requestJson<PullRequest>(
    `${projectPath(projectId)}/pull-requests/${pullRequestId}/requirement`,
    {
      method: "PUT",
      body: JSON.stringify(
        reason === "" ? { requirementId } : { requirementId, reason },
      ),
    },
  );
}
