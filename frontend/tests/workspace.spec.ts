import { flushPromises, mount } from "@vue/test-utils";
import { createMemoryHistory } from "vue-router";

import App from "../src/App.vue";
import { createAppRouter } from "../src/app/router";
import { bootstrapSession, clearSession } from "../src/features/auth/session";

/**
 * 工作台默认项目规则。锁的不变量只有一条：**默认规则只在 URL 缺少
 * project query 时触发**。写错的两个方向都有真实后果——补 query 后仍然触发就是
 * 无限重定向，带 query 进入也触发就是覆盖用户已经做出的选择（PRD AC2）。
 */

const PROJECTS = [
  { id: 7, name: "先返回的项目", status: "ACTIVE", createdAt: "2026-08-24T00:00:00Z", myRoles: ["LEADER"] },
  { id: 9, name: "后返回的项目", status: "ACTIVE", createdAt: "2026-08-24T00:00:00Z", myRoles: ["LEADER"] },
];

type UsageFixture = "recorded" | "empty" | "error";

function stubServer(usage: UsageFixture): void {
  clearSession();
  vi.stubGlobal(
    "fetch",
    vi.fn((input: string | URL | Request) => {
      const path = String(input);
      if (path.endsWith("/ai-usage")) {
        const row = { useCase: "REVIEW", calls: 1, succeeded: 1, failed: 0, tokens: null,
          p50LatencyMs: 1000, p95LatencyMs: 1000, lastCallAt: "2026-10-29T00:00:00Z" };
        return Promise.resolve(new Response(JSON.stringify(usage === "error"
          ? { code: "provider_unavailable", message: "暂时不可读取", traceId: "" }
          : { rows: usage === "empty" ? [] : [{ ...row, model: "model-a" }, { ...row, model: "model-b" }],
            totalCalls: usage === "empty" ? 0 : 2 }),
        { status: usage === "error" ? 503 : 200, headers: { "Content-Type": "application/json" } }));
      }
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

async function enterWorkspace(path: string, usage: UsageFixture = "empty") {
  stubServer(usage);
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

describe("workspace recorded model usage", () => {
  it("keeps model groups distinct without counting them as different uses", async () => {
    const { wrapper } = await enterWorkspace("/workspace?project=7", "recorded");
    const panel = wrapper.get(".ai-usage-panel");
    expect(panel.get("summary").text()).toContain("累计已记录 2 次尝试 · 1 种用途");
    expect(panel.findAll("tbody tr")).toHaveLength(2);
    expect(panel.text()).toContain("model-a");
    expect(panel.text()).toContain("model-b");
    expect(panel.text()).toContain("未记录");
    wrapper.unmount();
  });

  it.each(["empty", "error"] as const)("distinguishes %s usage without hiding the workspace", async usage => {
    const { wrapper } = await enterWorkspace("/workspace?project=7", usage);
    expect(wrapper.text()).toContain("工作台");
    if (usage === "empty") {
      expect(wrapper.text()).toContain("暂无调用记录");
    } else {
      expect(wrapper.text()).toContain("AI 调用台账读取失败");
      expect(wrapper.text()).not.toContain("暂无调用记录");
      expect(wrapper.find(".ai-usage-panel").exists()).toBe(false);
    }
    wrapper.unmount();
  });
});
