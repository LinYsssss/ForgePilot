import type { LocationQueryValue, RouteLocationRaw, RouteRecordRaw } from "vue-router";

import LoginPage from "../features/auth/LoginPage.vue";
import ProjectMembersPage from "../features/project/ProjectMembersPage.vue";
import ProjectSettingsPage from "../features/project/ProjectSettingsPage.vue";
import ProjectsPage from "../features/project/ProjectsPage.vue";
import RequirementDetailPage from "../features/requirement/RequirementDetailPage.vue";
import RequirementsPage from "../features/requirement/RequirementsPage.vue";
import ReviewDetailPage from "../features/review/ReviewDetailPage.vue";
import ReviewsPage from "../features/review/ReviewsPage.vue";

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

/** Requirement and review screens are project scoped through this query key. */
export const PROJECT_QUERY_KEY = "project";

/**
 * The review list narrows to one pull request through this key. It is a filter on
 * an approved path, not an eighth route: there is no pull request page and the
 * seven paths below are the whole product surface.
 */
export const PULL_REQUEST_QUERY_KEY = "pullRequest";

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

export function projectSettingsRoute(projectId: number): RouteLocationRaw {
  return { name: "project-settings", params: { id: String(projectId) } };
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
    component: ProjectSettingsPage,
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
