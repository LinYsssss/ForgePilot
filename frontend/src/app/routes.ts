import type { LocationQueryValue, RouteLocationRaw, RouteRecordRaw } from "vue-router";

import LoginPage from "../features/auth/LoginPage.vue";
import ProjectMembersPage from "../features/project/ProjectMembersPage.vue";
import ProjectsPage from "../features/project/ProjectsPage.vue";
import RequirementDetailPage from "../features/requirement/RequirementDetailPage.vue";
import RequirementsPage from "../features/requirement/RequirementsPage.vue";
import FoundationPlaceholderPage from "../views/FoundationPlaceholderPage.vue";

declare module "vue-router" {
  interface RouteMeta {
    title: string;
    /** Product routes need a signed-in account; the login route does not. */
    requiresSession?: boolean;
  }
}

export const TOP_LEVEL_NAVIGATION = [
  { label: "项目", to: "/projects" },
  { label: "研发需求", to: "/requirements" },
  { label: "代码审查", to: "/reviews" },
] as const;

export const HOME_ROUTE_PATH = TOP_LEVEL_NAVIGATION[0].to;

export const LOGIN_ROUTE_PATH = "/login";

/** Requirement screens are project scoped through this query key. */
export const PROJECT_QUERY_KEY = "project";

export const PRODUCT_ROUTE_PATHS = [
  "/projects",
  "/projects/:id/members",
  "/projects/:id/settings",
  "/requirements",
  "/requirements/:id",
  "/reviews",
  "/reviews/:id",
] as const;

export function projectMembersRoute(projectId: number): RouteLocationRaw {
  return { name: "project-members", params: { id: String(projectId) } };
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

/** Reads a positive integer id from a route param or query value. */
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
    component: FoundationPlaceholderPage,
    meta: { title: "项目设置", requiresSession: true },
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
    component: FoundationPlaceholderPage,
    meta: { title: "代码审查", requiresSession: true },
  },
  {
    path: "/reviews/:id",
    name: "review-detail",
    component: FoundationPlaceholderPage,
    meta: { title: "审查详情", requiresSession: true },
  },
  { path: "/:pathMatch(.*)*", redirect: HOME_ROUTE_PATH },
];
