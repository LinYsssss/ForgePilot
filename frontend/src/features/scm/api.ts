import { requestJson } from "../../lib/http";

export type ScmProvider = "GITHUB" | "GITLAB";
export type ScmIdentityUsage = "WORK" | "PERSONAL" | "CLIENT" | "OTHER";
export type ScmIdentityStatus = "VERIFIED" | "LEGACY_UNCONFIRMED" | "REVOKED";
export type ScmBindingStatus =
  | "PENDING_APPROVAL"
  | "ACTIVE"
  | "REJECTED"
  | "REVOKED"
  | "SUPERSEDED"
  | "LEGACY_UNCONFIRMED";

export const SCM_PROVIDERS: readonly ScmProvider[] = ["GITHUB", "GITLAB"];

export const SCM_PROVIDER_DEFAULTS: Readonly<Record<ScmProvider, string>> = {
  GITHUB: "https://api.github.com",
  GITLAB: "https://gitlab.com/api/v4",
};

/** Provider 网页端创建个人 Token 的路径，自建实例同路径。 */
export const SCM_TOKEN_PAGE_PATHS: Readonly<Record<ScmProvider, string>> = {
  GITHUB: "/settings/tokens",
  GITLAB: "/-/user_settings/personal_access_tokens",
};

/** 一次性个人 Token 只需覆盖 `GET user` 身份验证。 */
export const SCM_IDENTITY_TOKEN_SCOPES: Readonly<Record<ScmProvider, string>> = {
  GITHUB: "read:user",
  GITLAB: "read_user",
};

/** 仓库接入 Token 需要读 PR/MR 与 diff、并校验成员仓库权限。 */
export const SCM_REPOSITORY_TOKEN_SCOPES: Readonly<Record<ScmProvider, string>> = {
  GITHUB: "repo（公开仓库用 public_repo）",
  GITLAB: "read_api",
};

/**
 * 由调用方此刻填写的 `apiBase` 推出该 Provider 的 Token 创建页。
 *
 * 这是给浏览器点的链接，不构成任何服务端出站调用，因此后端
 * `OutboundUrlPolicy` 的白名单未被放宽。`apiBase` 不是合法 http(s) URL 时返回
 * `null`，调用方改为给出路径文本——解析不出来的地址不渲染成链接。
 */
export function providerTokenPage(provider: ScmProvider, apiBase: string): string | null {
  let base: URL;
  try {
    base = new URL(apiBase);
  } catch {
    return null;
  }
  if (base.protocol !== "https:" && base.protocol !== "http:") {
    return null;
  }
  // api.github.com 的网页端是 github.com；路径里的 /api/v3、/api/v4 一并丢弃。
  const host = base.host.startsWith("api.") ? base.host.slice("api.".length) : base.host;
  return `${base.protocol}//${host}${SCM_TOKEN_PAGE_PATHS[provider]}`;
}

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
  identityApprovalRequired: boolean;
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
  canEditRequirementAssociation: boolean;
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
  identityApprovalRequired?: boolean;
}

export interface ScmIdentity {
  id: number;
  provider: ScmProvider | null;
  instanceIdentity: string | null;
  externalUserId: string;
  externalUsername: string;
  label: string;
  usageType: ScmIdentityUsage;
  verificationStatus: ScmIdentityStatus;
  verifiedAt: string | null;
  lastSyncedAt: string | null;
}

export interface ScmBinding {
  id: number;
  userId: number;
  identityId: number;
  label: string;
  usageType: ScmIdentityUsage;
  provider: ScmProvider;
  instanceIdentity: string;
  externalUserId: string;
  externalUsername: string;
  status: ScmBindingStatus;
  accessLevel: "READ" | "WRITE" | "ADMIN" | null;
  accessCheckedAt: string | null;
  activatedAt: string | null;
  endedAt: string | null;
}

function projectPath(projectId: number): string {
  return `/api/projects/${projectId}`;
}

export function listScmRepositories(projectId: number): Promise<ScmRepository[]> {
  return requestJson<ScmRepository[]>(`${projectPath(projectId)}/scm/repositories`);
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

export function listScmIdentities(): Promise<ScmIdentity[]> {
  return requestJson<ScmIdentity[]>("/api/scm/identities");
}

export function verifyScmIdentity(input: {
  provider: ScmProvider;
  apiBase: string;
  oneTimeToken: string;
  label: string;
  usageType: ScmIdentityUsage;
}): Promise<ScmIdentity> {
  return requestJson<ScmIdentity>("/api/scm/identities/verify", {
    method: "POST",
    body: JSON.stringify(input),
  });
}

export function updateScmIdentity(
  identityId: number,
  label: string,
  usageType: ScmIdentityUsage,
): Promise<ScmIdentity> {
  return requestJson<ScmIdentity>(`/api/scm/identities/${identityId}`, {
    method: "PATCH",
    body: JSON.stringify({ label, usageType }),
  });
}

export function revokeScmIdentity(identityId: number): Promise<void> {
  return requestJson<void>(`/api/scm/identities/${identityId}`, { method: "DELETE" });
}

export function listBindingOptions(projectId: number): Promise<ScmIdentity[]> {
  return requestJson<ScmIdentity[]>(`${projectPath(projectId)}/scm/binding-options`);
}

export function listScmBindings(projectId: number): Promise<ScmBinding[]> {
  return requestJson<ScmBinding[]>(`${projectPath(projectId)}/scm/bindings`);
}

export function bindScmIdentity(
  projectId: number,
  identityId: number,
  oneTimeToken: string,
): Promise<ScmBinding> {
  return requestJson<ScmBinding>(`${projectPath(projectId)}/scm/bindings`, {
    method: "POST",
    body: JSON.stringify({ identityId, oneTimeToken }),
  });
}

export function decideScmBinding(
  projectId: number,
  bindingId: number,
  decision: "approve" | "reject",
): Promise<void> {
  return requestJson<void>(
    `${projectPath(projectId)}/scm/bindings/${bindingId}/${decision}`,
    { method: "POST" },
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
