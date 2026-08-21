import type { RouteRecordRaw } from "vue-router";

import FoundationPlaceholderPage from "../views/FoundationPlaceholderPage.vue";

export const TOP_LEVEL_NAVIGATION = [
  { label: "项目", to: "/projects" },
  { label: "研发需求", to: "/requirements" },
  { label: "代码审查", to: "/reviews" },
] as const;

export const HOME_ROUTE_PATH = TOP_LEVEL_NAVIGATION[0].to;

export const PRODUCT_ROUTE_PATHS = [
  "/projects",
  "/projects/:id/members",
  "/projects/:id/settings",
  "/requirements",
  "/requirements/:id",
  "/reviews",
  "/reviews/:id",
] as const;

export const routes: RouteRecordRaw[] = [
  { path: "/", redirect: "/projects" },
  {
    path: "/projects",
    name: "projects",
    component: FoundationPlaceholderPage,
    meta: { title: "项目" },
  },
  {
    path: "/projects/:id/members",
    name: "project-members",
    component: FoundationPlaceholderPage,
    meta: { title: "项目成员" },
  },
  {
    path: "/projects/:id/settings",
    name: "project-settings",
    component: FoundationPlaceholderPage,
    meta: { title: "项目设置" },
  },
  {
    path: "/requirements",
    name: "requirements",
    component: FoundationPlaceholderPage,
    meta: { title: "研发需求" },
  },
  {
    path: "/requirements/:id",
    name: "requirement-detail",
    component: FoundationPlaceholderPage,
    meta: { title: "需求详情" },
  },
  {
    path: "/reviews",
    name: "reviews",
    component: FoundationPlaceholderPage,
    meta: { title: "代码审查" },
  },
  {
    path: "/reviews/:id",
    name: "review-detail",
    component: FoundationPlaceholderPage,
    meta: { title: "审查详情" },
  },
  { path: "/:pathMatch(.*)*", redirect: "/projects" },
];
