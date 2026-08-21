import {
  createRouter,
  createWebHistory,
  type Router,
  type RouterHistory,
} from "vue-router";

import { routes } from "./routes";

export function createAppRouter(history: RouterHistory = createWebHistory()): Router {
  return createRouter({
    history,
    routes,
    scrollBehavior: () => ({ top: 0 }),
  });
}
