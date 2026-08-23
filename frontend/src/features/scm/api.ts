import { requestJson } from "../../lib/http";

export type ScmProvider = "GITHUB" | "GITLAB";

export const SCM_PROVIDERS: readonly ScmProvider[] = ["GITHUB", "GITLAB"];

export const SCM_PROVIDER_DEFAULTS: Readonly<Record<ScmProvider, string>> = {
  GITHUB: "https://api.github.com",
  GITLAB: "https://gitlab.com/api/v4",
};

/**
 * LEADER 能看到的连接信息。token 与 webhook 密钥都不在这个结构里：
 * 它们是只写的，服务端从不回显，因此这里不存在任何可能意外把它们渲染出来的字段。
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

/** 所有字段都是可选的；省略某个字段表示该部分连接配置保持不变。 */
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
 * 设置或清除 PR 的关联需求（PRD P1）。`null` 是合法取值，
 * 表示「这个 PR 不实现任何需求」；它与其他纠正一样会被审计记录，
 * 而不是一个漏填的字段。
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
