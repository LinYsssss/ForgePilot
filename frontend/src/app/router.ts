import {
  createRouter,
  createWebHistory,
  type Router,
  type RouterHistory,
} from "vue-router";

import { clearSession, hasSession } from "../features/auth/session";
import { setUnauthorizedHandler } from "../lib/http";
import { LOGIN_ROUTE_PATH, routes } from "./routes";

export function createAppRouter(history: RouterHistory = createWebHistory()): Router {
  const router = createRouter({
    history,
    routes,
    scrollBehavior: () => ({ top: 0 }),
  });

  router.beforeEach((to) =>
    to.meta.requiresSession === true && !hasSession() ? LOGIN_ROUTE_PATH : true,
  );

  // The only place a lost session sends the user back to the login screen.
  setUnauthorizedHandler(() => {
    clearSession();
    if (router.currentRoute.value.path !== LOGIN_ROUTE_PATH) {
      void router.replace(LOGIN_ROUTE_PATH);
    }
  });

  return router;
}
