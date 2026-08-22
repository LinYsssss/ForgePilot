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

  it("switches the registration contract and webhook guidance to GitLab", async () => {
    clearSession();
    vi.stubGlobal(
      "fetch",
      vi.fn((input: string | URL | Request) => {
        const path = String(input);
        if (path === "/api/auth/me") {
          return Promise.resolve(response({ id: 1, username: "lead" }));
        }
        if (path === "/api/projects/1") {
          return Promise.resolve(
            response({
              id: 1,
              name: "ForgePilot",
              status: "ACTIVE",
              createdAt: "2026-08-22T00:00:00Z",
              myRole: "LEADER",
            }),
          );
        }
        if (path === "/api/projects/1/requirements") {
          return Promise.resolve(response([]));
        }
        throw new Error(`Unexpected request: ${path}`);
      }),
    );

    await bootstrapSession();
    const router = createAppRouter(createMemoryHistory());
    await router.push("/projects/1/settings");
    const wrapper = mount(App, { global: { plugins: [router] } });
    await flushPromises();

    expect(wrapper.findAll(".nav-link")).toHaveLength(3);
    const provider = wrapper.get("#scm-provider");
    expect(provider.findAll("option").map((option) => option.text())).toEqual([
      "GITHUB",
      "GITLAB",
    ]);
    expect(wrapper.get<HTMLInputElement>("#scm-api-base").element.value).toBe(
      SCM_PROVIDER_DEFAULTS.GITHUB,
    );

    await provider.setValue("GITLAB");

    expect(wrapper.get<HTMLInputElement>("#scm-api-base").element.value).toBe(
      SCM_PROVIDER_DEFAULTS.GITLAB,
    );
    expect(wrapper.get(".scm-webhook-path").text()).toContain(
      "/api/scm/gitlab/webhook",
    );
    expect(wrapper.get("label[for='scm-webhook-secret']").element.parentElement?.textContent)
      .toContain("whsec_");
    expect(wrapper.text()).toContain("legacy secret token");
  });
});
