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

export interface Project {
  id: number;
  name: string;
  status: ProjectStatus;
  createdAt: string;
  myRole: ProjectRole;
}

export interface Member {
  userId: number;
  username: string;
  role: ProjectRole;
  scmExternalUserId: string | null;
  scmUsername: string | null;
  scmIdentityVerifiedAt: string | null;
}

export interface MemberPatch {
  role?: ProjectRole;
  scmExternalUserId?: string;
  scmUsername?: string;
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

export function listMembers(projectId: number): Promise<Member[]> {
  return requestJson<Member[]>(`/api/projects/${projectId}/members`);
}

export function addMember(
  projectId: number,
  username: string,
  role: ProjectRole,
): Promise<Member> {
  return requestJson<Member>(`/api/projects/${projectId}/members`, {
    method: "POST",
    body: JSON.stringify({ username, role }),
  });
}

export function updateMember(
  projectId: number,
  userId: number,
  patch: MemberPatch,
): Promise<Member> {
  return requestJson<Member>(`/api/projects/${projectId}/members/${userId}`, {
    method: "PATCH",
    body: JSON.stringify(patch),
  });
}
