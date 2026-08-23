import type { LocationQueryValue, RouteLocationRaw, RouteRecordRaw } from "vue-router";

import LoginPage from "../features/auth/LoginPage.vue";
import KnowledgePage from "../features/knowledge/KnowledgePage.vue";
import ProjectMembersPage from "../features/project/ProjectMembersPage.vue";
import ProjectsPage from "../features/project/ProjectsPage.vue";
import RequirementDetailPage from "../features/requirement/RequirementDetailPage.vue";
import RequirementsPage from "../features/requirement/RequirementsPage.vue";
import ReviewDetailPage from "../features/review/ReviewDetailPage.vue";
import ReviewsPage from "../features/review/ReviewsPage.vue";
import RepositoryPage from "../features/scm/RepositoryPage.vue";
import WorkspacePage from "../features/workspace/WorkspacePage.vue";

declare module "vue-router" {
  interface RouteMeta {
    title: string;
    /** 产品路由需要已登录账号；登录路由不需要。 */
    requiresSession?: boolean;
  }
}

export const TOP_LEVEL_NAVIGATION = [
  { label: "工作台", to: "/workspace" },
  { label: "项目", to: "/projects" },
  { label: "研发需求", to: "/requirements" },
  { label: "项目知识", to: "/knowledge" },
  { label: "仓库接入", to: "/repositories" },
  { label: "代码审查", to: "/reviews" },
] as const;

export const HOME_ROUTE_PATH = TOP_LEVEL_NAVIGATION[0].to;

export const LOGIN_ROUTE_PATH = "/login";

/** 需求与审查界面通过这个查询参数限定到某个项目。 */
export const PROJECT_QUERY_KEY = "project";

/**
 * 审查列表通过这个参数收窄到单个 PR。它是既有合法路径上的一个过滤条件，
 * 而不是第八条路由：本产品没有 PR 详情页，下面那七条路径就是全部产品面。
 */
export const PULL_REQUEST_QUERY_KEY = "pullRequest";

export const PRODUCT_ROUTE_PATHS = [
  "/workspace",
  "/projects",
  "/projects/:id/members",
  "/projects/:id/settings",
  "/requirements",
  "/requirements/:id",
  "/knowledge",
  "/repositories",
  "/reviews",
  "/reviews/:id",
] as const;

export function projectMembersRoute(projectId: number): RouteLocationRaw {
  return { name: "project-members", params: { id: String(projectId) } };
}

export function projectSettingsRoute(projectId: number): RouteLocationRaw {
  return { name: "repositories", query: { [PROJECT_QUERY_KEY]: String(projectId) } };
}

export function workspaceRoute(projectId?: number): RouteLocationRaw {
  return projectId === undefined
    ? { name: "workspace" }
    : { name: "workspace", query: { [PROJECT_QUERY_KEY]: String(projectId) } };
}

export function knowledgeRoute(projectId: number): RouteLocationRaw {
  return { name: "knowledge", query: { [PROJECT_QUERY_KEY]: String(projectId) } };
}

export function repositoriesRoute(projectId: number): RouteLocationRaw {
  return { name: "repositories", query: { [PROJECT_QUERY_KEY]: String(projectId) } };
}

export function reviewsRoute(
  projectId: number,
  pullRequestId?: number,
): RouteLocationRaw {
  return {
    name: "reviews",
    query: {
      [PROJECT_QUERY_KEY]: String(projectId),
      ...(pullRequestId === undefined
        ? {}
        : { [PULL_REQUEST_QUERY_KEY]: String(pullRequestId) }),
    },
  };
}

export function reviewDetailRoute(
  projectId: number,
  reviewId: number,
): RouteLocationRaw {
  return {
    name: "review-detail",
    params: { id: String(reviewId) },
    query: { [PROJECT_QUERY_KEY]: String(projectId) },
  };
}

export function requirementsRoute(projectId: number): RouteLocationRaw {
  return { name: "requirements", query: { [PROJECT_QUERY_KEY]: String(projectId) } };
}

export function requirementDetailRoute(
  projectId: number,
  requirementId: number,
): RouteLocationRaw {
  return {
    name: "requirement-detail",
    params: { id: String(requirementId) },
    query: { [PROJECT_QUERY_KEY]: String(projectId) },
  };
}

/** 从路由参数或查询值中读取一个正整数 id。 */
export function parseId(
  value: string | LocationQueryValue | LocationQueryValue[] | undefined,
): number | null {
  if (typeof value !== "string") {
    return null;
  }
  const id = Number(value);
  return Number.isInteger(id) && id > 0 ? id : null;
}

export const routes: RouteRecordRaw[] = [
  { path: "/", redirect: HOME_ROUTE_PATH },
  {
    path: "/workspace",
    name: "workspace",
    component: WorkspacePage,
    meta: { title: "工作台", requiresSession: true },
  },
  {
    path: LOGIN_ROUTE_PATH,
    name: "login",
    component: LoginPage,
    meta: { title: "登录" },
  },
  {
    path: "/projects",
    name: "projects",
    component: ProjectsPage,
    meta: { title: "项目", requiresSession: true },
  },
  {
    path: "/projects/:id/members",
    name: "project-members",
    component: ProjectMembersPage,
    meta: { title: "项目成员", requiresSession: true },
  },
  {
    path: "/projects/:id/settings",
    name: "project-settings",
    redirect: (to) => ({ path: "/repositories", query: { [PROJECT_QUERY_KEY]: String(to.params.id) } }),
    meta: { title: "仓库接入", requiresSession: true },
  },
  {
    path: "/knowledge",
    name: "knowledge",
    component: KnowledgePage,
    meta: { title: "项目知识", requiresSession: true },
  },
  {
    path: "/repositories",
    name: "repositories",
    component: RepositoryPage,
    meta: { title: "仓库接入", requiresSession: true },
  },
  {
    path: "/requirements",
    name: "requirements",
    component: RequirementsPage,
    meta: { title: "研发需求", requiresSession: true },
  },
  {
    path: "/requirements/:id",
    name: "requirement-detail",
    component: RequirementDetailPage,
    meta: { title: "需求详情", requiresSession: true },
  },
  {
    path: "/reviews",
    name: "reviews",
    component: ReviewsPage,
    meta: { title: "代码审查", requiresSession: true },
  },
  {
    path: "/reviews/:id",
    name: "review-detail",
    component: ReviewDetailPage,
    meta: { title: "审查详情", requiresSession: true },
  },
  { path: "/:pathMatch(.*)*", redirect: HOME_ROUTE_PATH },
];
