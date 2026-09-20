import { flushPromises, mount, RouterLinkStub } from "@vue/test-utils";
import { createMemoryHistory, createRouter } from "vue-router";

import KnowledgePage from "../src/features/knowledge/KnowledgePage.vue";

function response(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
}

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

it("refreshes pending knowledge until it becomes ready", async () => {
  vi.useFakeTimers();
  let documentReads = 0;
  const document = {
    id: 8,
    projectId: 3,
    sourceType: "PROJECT_KNOWLEDGE",
    sourceRequirementId: null,
    title: "architecture.md",
    failureReason: null,
    createdAt: "2026-09-20T00:00:00Z",
    updatedAt: "2026-09-20T00:00:00Z",
    chunkCount: 0,
    embeddedChunkCount: 0,
    embeddingDimension: null,
    embeddingProvider: null,
    embeddingModel: null,
    embeddingVersion: null,
  };
  const fetchMock = vi.fn((input: string | URL | Request) => {
    const path = String(input);
    if (path === "/api/projects") {
      return Promise.resolve(response([{
        id: 3,
        name: "ForgePilot",
        status: "ACTIVE",
        createdAt: "2026-09-20T00:00:00Z",
        myRoles: ["LEADER"],
      }]));
    }
    if (path === "/api/projects/3/knowledge/documents") {
      documentReads++;
      return Promise.resolve(response([{
        ...document,
        status: documentReads === 1 ? "PENDING" : "READY",
        chunkCount: documentReads === 1 ? 0 : 1,
        embeddedChunkCount: documentReads === 1 ? 0 : 1,
      }]));
    }
    throw new Error(`Unexpected request: ${path}`);
  });
  vi.stubGlobal("fetch", fetchMock);

  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: "/knowledge", component: KnowledgePage }],
  });
  await router.push("/knowledge?project=3");
  const wrapper = mount(KnowledgePage, {
    global: { plugins: [router], stubs: { RouterLink: RouterLinkStub } },
  });
  await flushPromises();
  expect(wrapper.get(".knowledge-index").text()).toContain("正在处理");

  await vi.advanceTimersByTimeAsync(2_000);
  await flushPromises();
  expect(wrapper.get(".knowledge-index").text()).toContain("可用于审查");
  expect(documentReads).toBe(2);

  await vi.advanceTimersByTimeAsync(10_000);
  expect(documentReads).toBe(2);
  wrapper.unmount();
});
