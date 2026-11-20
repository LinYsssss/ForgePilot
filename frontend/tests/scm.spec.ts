import { flushPromises, mount } from "@vue/test-utils";
import { createMemoryHistory } from "vue-router";

import App from "../src/App.vue";
import { createAppRouter } from "../src/app/router";
import { bootstrapSession, clearSession } from "../src/features/auth/session";
import {
  SCM_PROVIDER_DEFAULTS,
  SCM_PROVIDERS,
} from "../src/features/scm/api";

function response(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
}

afterEach(() => {
  vi.unstubAllGlobals();
  clearSession();
});

describe("SCM provider settings", () => {
  it("declares both approved providers and their authoritative public defaults", () => {
    expect(SCM_PROVIDERS).toEqual(["GITHUB", "GITLAB"]);
    expect(SCM_PROVIDER_DEFAULTS).toEqual({
      GITHUB: "https://api.github.com",
      GITLAB: "https://gitlab.com/api/v4",
    });
  });

  it("loads the repository page and keeps the registration provider defaults", async () => {
    clearSession();
    vi.stubGlobal(
      "fetch",
      vi.fn((input: string | URL | Request) => {
        const path = String(input);
        if (path === "/api/auth/me") {
          return Promise.resolve(response({ id: 1, username: "lead", displayName: "负责人" }));
        }
        if (path === "/api/projects") {
          return Promise.resolve(
            response([{
              id: 1,
              name: "ForgePilot",
              status: "ACTIVE",
              createdAt: "2026-08-22T00:00:00Z",
              myRoles: ["LEADER"],
            }]),
          );
        }
        if (path === "/api/projects/1/scm/repositories") {
          return Promise.resolve(response([]));
        }
        throw new Error(`Unexpected request: ${path}`);
      }),
    );

    await bootstrapSession();
    const router = createAppRouter(createMemoryHistory());
    await router.push("/repositories?project=1");
    const wrapper = mount(App, { global: { plugins: [router] } });
    await flushPromises();

    expect(wrapper.findAll(".nav-link")).toHaveLength(6);
    const provider = wrapper.get("#repository-provider");
    expect(provider.findAll("option").map((option) => option.text())).toEqual([
      "GITHUB",
      "GITLAB",
    ]);
    expect(wrapper.get<HTMLInputElement>("#repository-api-base").element.value).toBe(
      SCM_PROVIDER_DEFAULTS.GITHUB,
    );

    await provider.setValue("GITLAB");

    expect(wrapper.get<HTMLInputElement>("#repository-api-base").element.value).toBe(
      SCM_PROVIDER_DEFAULTS.GITLAB,
    );
    expect(wrapper.text()).toContain("只写，不回显");
  });

  it("asks for the LEADER-only notification settings only when the viewer is the LEADER", async () => {
    // 非 LEADER 读这份配置只会得到 403；页面本来就不给他们显示这一块，因此不该发这个请求。
    for (const [role, expected] of [["REVIEWER", 0], ["LEADER", 1]] as const) {
      clearSession();
      const requests: string[] = [];
      vi.stubGlobal(
        "fetch",
        vi.fn((input: string | URL | Request) => {
          const path = String(input);
          requests.push(path);
          const body = path === "/api/auth/me"
            ? { id: 1, username: "member", displayName: "成员" }
            : path === "/api/projects"
              ? [{ id: 1, name: "ForgePilot", status: "ACTIVE", createdAt: "2026-08-22T00:00:00Z", myRoles: [role] }]
              : path === "/api/projects/1/notifications/dingtalk"
                ? { configured: false, enabled: false, signed: false, keyword: null, updatedAt: null }
                : [];
          return Promise.resolve(response(body));
        }),
      );

      await bootstrapSession();
      const router = createAppRouter(createMemoryHistory());
      await router.push("/repositories?project=1");
      const wrapper = mount(App, { global: { plugins: [router] } });
      await flushPromises();
      await flushPromises();

      expect(requests.filter((path) => path === "/api/projects/1/notifications/dingtalk")).toHaveLength(expected);
      expect(wrapper.find(".notification-editor").exists()).toBe(expected === 1);
      wrapper.unmount();
      vi.unstubAllGlobals();
    }
  });

  it("restores an existing safe repository and updates with its loaded id", async () => {
    const requests: string[] = [];
    vi.stubGlobal(
      "fetch",
      vi.fn((input: string | URL | Request, init?: RequestInit) => {
        const path = String(input);
        requests.push(`${init?.method ?? "GET"} ${path}`);
        if (path === "/api/auth/me") {
          return Promise.resolve(response({ id: 1, username: "lead", displayName: "负责人" }));
        }
        if (path === "/api/projects") {
          return Promise.resolve(response([{
            id: 1,
            name: "ForgePilot",
            status: "ACTIVE",
            createdAt: "2026-08-22T00:00:00Z",
            myRoles: ["LEADER"],
          }]));
        }
        if (path === "/api/projects/1/scm/repositories") {
          return Promise.resolve(response([{
            id: 42,
            projectId: 1,
            provider: "GITLAB",
            instanceIdentity: "gitlab.example.com",
            externalId: "team/forgepilot",
            apiBase: "https://gitlab.example.com/api/v4",
            createdAt: "2026-08-22T00:00:00Z",
            updatedAt: "2026-08-23T00:00:00Z",
          }]));
        }
        if (path === "/api/projects/1/scm/repositories/42" && init?.method === "PATCH") {
          return Promise.resolve(response({
            id: 42,
            projectId: 1,
            provider: "GITLAB",
            instanceIdentity: "gitlab.example.com",
            externalId: "team/forgepilot",
            apiBase: "https://gitlab.example.com/api/v4",
            createdAt: "2026-08-22T00:00:00Z",
            updatedAt: "2026-08-23T00:00:00Z",
          }));
        }
        throw new Error(`Unexpected request: ${path}`);
      }),
    );

    await bootstrapSession();
    const router = createAppRouter(createMemoryHistory());
    await router.push("/repositories?project=1");
    const wrapper = mount(App, { global: { plugins: [router] } });
    await flushPromises();

    expect(wrapper.get<HTMLInputElement>("#repository-external-id").element.value)
      .toBe("team/forgepilot");
    expect(wrapper.get<HTMLInputElement>("#repository-api-base").element.value)
      .toBe("https://gitlab.example.com/api/v4");
    expect(wrapper.find("#repository-provider").exists()).toBe(false);
    expect(wrapper.text()).not.toContain("内部记录 ID");

    await wrapper.get(".repository-form").trigger("submit");
    await flushPromises();
    expect(requests).toContain("PATCH /api/projects/1/scm/repositories/42");
  });
});
