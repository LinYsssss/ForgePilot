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
          ? jsonResponse({ id: 1, username: "lead" })
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
  it("keeps exactly the seven approved product paths and three top-level entries", () => {
    expect(PRODUCT_ROUTE_PATHS).toEqual([
      "/projects",
      "/projects/:id/members",
      "/projects/:id/settings",
      "/requirements",
      "/requirements/:id",
      "/reviews",
      "/reviews/:id",
    ]);
    expect(TOP_LEVEL_NAVIGATION.map((item) => item.to)).toEqual([
      "/projects",
      "/requirements",
      "/reviews",
    ]);
  });

  it("redirects root to the project screen inside the semantic shell", async () => {
    const { router, wrapper } = await mountSignedInShell();

    expect(router.currentRoute.value.path).toBe("/projects");
    expect(wrapper.find("header").exists()).toBe(true);
    expect(wrapper.find('nav[aria-label="主导航"]').exists()).toBe(true);
    expect(wrapper.findAll(".nav-link")).toHaveLength(3);
    expect(wrapper.find("main h1").text()).toBe("项目");
  });

  it("exposes a skip link that targets the main landmark", async () => {
    const { wrapper } = await mountSignedInShell();

    const skipLink = wrapper.find("a.skip-link");
    expect(skipLink.exists()).toBe(true);
    expect(skipLink.attributes("href")).toBe("#app-main");
    expect(wrapper.find("main#app-main").exists()).toBe(true);
  });
});
