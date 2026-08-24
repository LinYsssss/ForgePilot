import { flushPromises, mount } from "@vue/test-utils";
import { createMemoryHistory } from "vue-router";

import App from "../src/App.vue";
import { createAppRouter } from "../src/app/router";
import {
  PRODUCT_ROUTE_PATHS,
  TOP_LEVEL_NAVIGATION,
} from "../src/app/routes";
import { bootstrapSession, clearSession } from "../src/features/auth/session";

function jsonResponse(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
}

async function mountSignedInShell() {
  clearSession();
  vi.stubGlobal(
    "fetch",
    vi.fn((path: string | URL | Request) =>
      Promise.resolve(
        String(path) === "/api/auth/me"
          ? jsonResponse({ id: 1, username: "lead", displayName: "负责人" })
          : jsonResponse([]),
      ),
    ),
  );

  await bootstrapSession();
  const router = createAppRouter(createMemoryHistory());
  await router.push("/");
  const wrapper = mount(App, { global: { plugins: [router] } });
  await flushPromises();
  return { router, wrapper };
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("route and shell contract", () => {
  it("exposes the approved six-entry product surface and compatibility route", () => {
    expect(PRODUCT_ROUTE_PATHS).toEqual([
      "/workspace",
      "/account",
      "/projects",
      "/projects/:id/members",
      "/projects/:id/settings",
      "/requirements",
      "/requirements/:id",
      "/knowledge",
      "/repositories",
      "/reviews",
      "/reviews/:id",
    ]);
    expect(TOP_LEVEL_NAVIGATION.map((item) => item.to)).toEqual([
      "/workspace",
      "/projects",
      "/requirements",
      "/knowledge",
      "/repositories",
      "/reviews",
    ]);
  });

  it("redirects root to the workbench inside the semantic shell", async () => {
    const { router, wrapper } = await mountSignedInShell();

    expect(router.currentRoute.value.path).toBe("/workspace");
    expect(wrapper.find("header").exists()).toBe(true);
    expect(wrapper.find('header nav[aria-label="主导航"]').exists()).toBe(true);
    expect(wrapper.find("header .brand-lockup").attributes("src")).toBe(
      "/brand/logo-lockup.png",
    );
    expect(wrapper.find(".app-sidebar").exists()).toBe(false);
    expect(wrapper.findAll(".nav-link")).toHaveLength(6);
    expect(wrapper.find("main h1").text()).toBe("工作台");
  });

  it("uses only the app mark on the signed-out login surface", async () => {
    clearSession();
    const router = createAppRouter(createMemoryHistory());
    await router.push("/login");
    const wrapper = mount(App, { global: { plugins: [router] } });
    await flushPromises();

    expect(wrapper.find("header").exists()).toBe(false);
    expect(wrapper.findAll(".login-page img")).toHaveLength(1);
    expect(wrapper.find(".login-logo-app").attributes("src")).toBe("/brand/logo-app.png");
    expect(wrapper.find(".brand-lockup").exists()).toBe(false);
  });

  it("redirects former project settings links to repository integration", async () => {
    const { router } = await mountSignedInShell();
    await router.push("/projects/8/settings");
    await flushPromises();
    expect(router.currentRoute.value.path).toBe("/repositories");
    expect(router.currentRoute.value.query.project).toBe("8");
  });

  it("exposes a skip link that targets the main landmark", async () => {
    const { wrapper } = await mountSignedInShell();

    const skipLink = wrapper.find("a.skip-link");
    expect(skipLink.exists()).toBe(true);
    expect(skipLink.attributes("href")).toBe("#app-main");
    expect(wrapper.find("main#app-main").exists()).toBe(true);
  });
});
