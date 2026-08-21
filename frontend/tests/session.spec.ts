import { flushPromises } from "@vue/test-utils";
import { createMemoryHistory } from "vue-router";

import { createAppRouter } from "../src/app/router";
import { bootstrapSession, clearSession, hasSession } from "../src/features/auth/session";
import { requestJson } from "../src/lib/http";

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

beforeEach(() => {
  clearSession();
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("session boundary", () => {
  it("sends an unauthenticated visitor from a guarded route to the login page", async () => {
    const router = createAppRouter(createMemoryHistory());

    await router.push("/requirements");

    expect(router.currentRoute.value.path).toBe("/login");
  });

  it("keeps a guarded route reachable once the cold-start probe found a session", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(jsonResponse({ id: 1, username: "lead" })),
    );
    await bootstrapSession();
    const router = createAppRouter(createMemoryHistory());

    await router.push("/requirements");

    expect(router.currentRoute.value.path).toBe("/requirements");
  });

  it("returns to the login page when any request reports an expired session", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(jsonResponse({ id: 1, username: "lead" })),
    );
    await bootstrapSession();
    const router = createAppRouter(createMemoryHistory());
    await router.push("/projects");
    expect(router.currentRoute.value.path).toBe("/projects");

    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          jsonResponse({ code: "UNAUTHORIZED", message: "未登录", traceId: "t" }, 401),
        ),
    );
    await requestJson("/api/projects").catch(() => undefined);
    await flushPromises();

    expect(hasSession()).toBe(false);
    expect(router.currentRoute.value.path).toBe("/login");
  });
});
