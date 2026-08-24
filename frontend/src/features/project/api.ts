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
