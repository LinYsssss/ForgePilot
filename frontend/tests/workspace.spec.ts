import { flushPromises, mount } from "@vue/test-utils";
import { createMemoryHistory } from "vue-router";

import App from "../src/App.vue";
import { createAppRouter } from "../src/app/router";
import { bootstrapSession, clearSession } from "../src/features/auth/session";

/**
 * 工作台默认项目规则（T-001）。锁的不变量只有一条：**默认规则只在 URL 缺少
 * project query 时触发**。写错的两个方向都有真实后果——补 query 后仍然触发就是
 * 无限重定向，带 query 进入也触发就是覆盖用户已经做出的选择（PRD AC2）。
 */

const PROJECTS = [
  { id: 7, name: "先返回的项目", status: "ACTIVE", createdAt: "2026-08-24T00:00:00Z", myRoles: ["LEADER"] },
  { id: 9, name: "后返回的项目", status: "ACTIVE", createdAt: "2026-08-24T00:00:00Z", myRoles: ["LEADER"] },
];

function stubServer(): void {
  clearSession();
  vi.stubGlobal(
    "fetch",
    vi.fn((input: string | URL | Request) => {
      const path = String(input);
      const body = path === "/api/auth/me"
        ? { id: 1, username: "lead", displayName: "负责人" }
        : path === "/api/projects"
          ? PROJECTS
          : [];
      return Promise.resolve(
        new Response(JSON.stringify(body), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        }),
      );
    }),
  );
}

async function enterWorkspace(path: string) {
  stubServer();
  await bootstrapSession();
  const router = createAppRouter(createMemoryHistory());
  await router.push(path);
  const wrapper = mount(App, { global: { plugins: [router] } });
  await flushPromises();
  await flushPromises();
  return { router, wrapper };
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("workspace default project context", () => {
  it("fills the missing project query with the first listed project", async () => {
    const { router } = await enterWorkspace("/workspace");

    expect(router.currentRoute.value.path).toBe("/workspace");
    expect(router.currentRoute.value.query.project).toBe("7");
  });

  it("leaves an explicit project choice alone", async () => {
    const { router } = await enterWorkspace("/workspace?project=9");

    expect(router.currentRoute.value.query.project).toBe("9");
  });
});
