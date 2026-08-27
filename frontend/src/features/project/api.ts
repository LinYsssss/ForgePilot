import { requestJson } from "../../lib/http";

export type ProjectRole = "LEADER" | "DEVELOPER" | "REVIEWER";
export type ProjectStatus = "ACTIVE" | "ARCHIVED";

export const PROJECT_ROLES: readonly ProjectRole[] = ["LEADER", "DEVELOPER", "REVIEWER"];

export const PROJECT_ROLE_LABELS: Record<ProjectRole, string> = {
  LEADER: "负责人",
  DEVELOPER: "开发",
  REVIEWER: "评审",
};

export const PROJECT_STATUS_LABELS: Record<ProjectStatus, string> = {
  ACTIVE: "进行中",
  ARCHIVED: "已归档",
};

export function hasProjectRole(
  project: Pick<Project, "myRoles"> | null | undefined,
  role: ProjectRole,
): boolean {
  return project?.myRoles.includes(role) === true;
}

export interface Project {
  id: number;
  name: string;
  status: ProjectStatus;
  createdAt: string;
  myRoles: ProjectRole[];
}

export interface Member {
  userId: number;
  username: string;
  displayName: string;
  roles: ProjectRole[];
}

export interface MemberCandidate {
  userId: number;
  username: string;
  displayName: string;
  enabled: boolean;
  alreadyMember: boolean;
}

export function listProjects(): Promise<Project[]> {
  return requestJson<Project[]>("/api/projects");
}

export function getProject(projectId: number): Promise<Project> {
  return requestJson<Project>(`/api/projects/${projectId}`);
}

export function createProject(name: string): Promise<Project> {
  return requestJson<Project>("/api/projects", {
    method: "POST",
    body: JSON.stringify({ name }),
  });
}

/**
 * 归档一个项目：它从工作区列表收起，数据与审计一行不动。
 *
 * 这里不是删除，文案也不该说成删除。硬删在数据库层就走不通——
 * `project_deletion_record` 指向 `project(id)` 的外键没有 `ON DELETE`，
 * 那张记录删除行为的台账自己会拒绝项目被删掉。
 */
export function archiveProject(projectId: number): Promise<void> {
  return requestJson<void>(`/api/projects/${projectId}/archive`, { method: "POST" });
}

/** 取消归档：项目回到工作区列表。归档不销毁任何东西，所以它必须可逆。 */
export function unarchiveProject(projectId: number): Promise<void> {
  return requestJson<void>(`/api/projects/${projectId}/unarchive`, { method: "POST" });
}

export function listMembers(projectId: number): Promise<Member[]> {
  return requestJson<Member[]>(`/api/projects/${projectId}/members`);
}

export function searchMemberCandidates(
  projectId: number,
  query: string,
  page = 0,
): Promise<MemberCandidate[]> {
  return requestJson<MemberCandidate[]>(
    `/api/projects/${projectId}/members/candidates?q=${encodeURIComponent(query)}&page=${page}&size=20`,
  );
}

export function addMembers(
  projectId: number,
  members: Array<{ userId: number; roles: ProjectRole[] }>,
): Promise<Member[]> {
  return requestJson<Member[]>(`/api/projects/${projectId}/members/batch`, {
    method: "POST",
    body: JSON.stringify({ members }),
  });
}

export function updateMemberRoles(
  projectId: number,
  userId: number,
  roles: ProjectRole[],
): Promise<Member> {
  return requestJson<Member>(`/api/projects/${projectId}/members/${userId}/roles`, {
    method: "PATCH",
    body: JSON.stringify({ roles }),
  });
}

export function transferLeader(projectId: number, targetUserId: number): Promise<void> {
  return requestJson<void>(`/api/projects/${projectId}/members/leader-transfer`, {
    method: "POST",
    body: JSON.stringify({ targetUserId, confirmed: true }),
  });
}

/**
 * 移除项目成员。后端在同一事务里撤销它的需求指派、Finding 认领与项目 SCM 绑定，
 * 而不可变的 PR 作者快照与全部审计保持不动。
 */
export function removeMember(projectId: number, userId: number): Promise<void> {
  return requestJson<void>(`/api/projects/${projectId}/members/${userId}`, { method: "DELETE" });
}
