import { flushPromises, mount, RouterLinkStub, type VueWrapper } from "@vue/test-utils";
import { createMemoryHistory, createRouter } from "vue-router";

import ReviewDetailPage from "../src/features/review/ReviewDetailPage.vue";
import type { ReviewDetail } from "../src/features/review/api";
import type { PullRequest } from "../src/features/scm/api";

type Operation = "association" | "decision" | "finding" | "events";
const OPERATIONS: Operation[] = ["association", "decision", "finding", "events"];
const wrappers: VueWrapper[] = [];

function review(projectId: number, id: number): ReviewDetail {
  return {
    id, pullRequestId: id + 10, headSha: `head-${projectId}-${id}`,
    reviewInputFingerprint: `input-${projectId}-${id}`,
    requirementId: null, requirementRevisionId: null,
    status: "COMPLETED", decision: "PENDING", decisionBy: null, decisionAt: null,
    decisionComment: null, decisionBlockReason: null, isCurrent: true,
    contextSnapshot: null, coverage: null, acVerdicts: [],
    engine: null, promptVersion: null, model: null, executionAttempt: 1,
    findings: [{
      id: id + 1000, findingType: "CODE_QUALITY", path: "src/a.ts", line: 1,
      evidence: "return value;", category: "CORRECTNESS", explanation: null,
      suggestion: null, confidence: null, status: "OPEN", continuity: "NEW",
      requirementId: null, requirementRevisionId: null, acId: null, acKey: null,
      assigneeId: null, carriedFromFindingId: null, findingKey: `finding-${id}`,
      evidenceHash: null, basisHash: null,
    }],
  };
}

function pullRequest(projectId: number, id: number): PullRequest {
  return {
    id, projectId, repositoryId: 1, externalNumber: id,
    title: `PR ${projectId}/${id}`, baseSha: "base", headSha: `head-${projectId}-${id - 10}`,
    reviewInputFingerprint: `input-${projectId}-${id - 10}`, requirementId: null,
    authorExternalUserId: "author", authorUsername: "author", authorUserId: null,
    canEditRequirementAssociation: true, sourceUpdatedAt: null,
    updatedAt: "2026-09-12T00:00:00Z",
  };
}

function response(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status, headers: { "Content-Type": "application/json" },
  });
}

function deferredResponse() {
  let resolve!: (value: Response) => void;
  const promise = new Promise<Response>((done) => { resolve = done; });
  return { promise, resolve };
}

function operationRequest(operation: Operation, reviewId = 101, projectId = 3) {
  const base = `/api/projects/${projectId}`;
  switch (operation) {
    case "association": return { method: "PUT", path: `${base}/pull-requests/${reviewId + 10}/requirement` };
    case "decision": return { method: "POST", path: `${base}/reviews/${reviewId}/decision` };
    case "finding": return { method: "POST", path: `${base}/findings/${reviewId + 1000}/status` };
    case "events": return { method: "GET", path: `${base}/findings/${reviewId + 1000}/events` };
  }
}

async function openReview() {
  const calls: { path: string; method: string }[] = [];
  let held: { path: string; method: string; deferred: ReturnType<typeof deferredResponse> } | null = null;

  function bodyFor(path: string): unknown {
    const match = /^\/api\/projects\/(\d+)(.*)$/.exec(path);
    if (!match) throw new Error(`Unexpected request: ${path}`);
    const projectId = Number(match[1]);
    const suffix = match[2];
    if (suffix === "") return {
      id: projectId, name: `Project ${projectId}`, status: "ACTIVE", myRoles: ["LEADER"],
    };
    const detail = /^\/reviews\/(\d+)$/.exec(suffix);
    if (detail) return review(projectId, Number(detail[1]));
    const pull = /^\/pull-requests\/(\d+)(?:\/requirement)?$/.exec(suffix);
    if (pull) return pullRequest(projectId, Number(pull[1]));
    const history = /^\/pull-requests\/(\d+)\/reviews$/.exec(suffix);
    if (history) return [{
      ...review(projectId, Number(history[1]) - 10), createdAt: "2026-09-12T00:00:00Z",
    }];
    if (suffix.endsWith("/decision")) return { decision: "APPROVE", decisionBy: 1, decisionAt: null };
    if (suffix.endsWith("/status")) return { status: "CONFIRMED" };
    if (["/members", "/requirements", "/knowledge/documents"].includes(suffix)
      || suffix.endsWith("/events")) return [];
    throw new Error(`Unexpected request: ${path}`);
  }

  vi.stubGlobal("fetch", vi.fn((input: string | URL | Request, init?: RequestInit) => {
    const path = String(input);
    const method = init?.method ?? "GET";
    calls.push({ path, method });
    if (held?.path === path && held.method === method) {
      const pending = held.deferred.promise;
      held = null;
      return pending;
    }
    return Promise.resolve(response(bodyFor(path)));
  }));

  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: "/reviews/:id", component: ReviewDetailPage }],
  });
  await router.push("/reviews/101?project=3");
  const wrapper = mount(ReviewDetailPage, {
    global: { plugins: [router], stubs: { RouterLink: RouterLinkStub } },
  });
  wrappers.push(wrapper);
  await flushPromises();
  expect(wrapper.get("#review-progress-title").text()).toContain("101");

  return {
    wrapper, router, calls,
    hold(request: { path: string; method: string }) {
      const deferred = deferredResponse();
      held = { ...request, deferred };
      return {
        complete: () => deferred.resolve(response(bodyFor(request.path))),
        fail: () => deferred.resolve(response({ message: "old page failed" }, 409)),
      };
    },
  };
}

async function perform(wrapper: VueWrapper, operation: Operation) {
  switch (operation) {
    case "association": await wrapper.get("form.inline-form").trigger("submit"); break;
    case "decision": await wrapper.get('[data-decision="APPROVE"]').trigger("click"); break;
    case "finding": await wrapper.get('[data-action="CONFIRM"]').trigger("click"); break;
    case "events": await wrapper.get(".finding-events-button").trigger("click"); break;
  }
  await flushPromises();
}

afterEach(() => {
  for (const wrapper of wrappers.splice(0)) wrapper.unmount();
  vi.unstubAllGlobals();
});

describe("review operations belong to the page visit that started them", () => {
  it.each(OPERATIONS)("ignores an old %s response after switching reviews", async (operation) => {
    const { wrapper, router, calls, hold } = await openReview();
    const old = hold(operationRequest(operation));
    await perform(wrapper, operation);
    await router.push("/reviews/202?project=3");
    await flushPromises();
    old.complete();
    await flushPromises();

    expect(wrapper.get("#review-progress-title").text()).toContain("202");
    expect(wrapper.get(".pull-request-number").text()).toContain("PR 3/212");
    await perform(wrapper, "decision");
    expect(calls.at(-1)?.path).not.toContain("/111/");
    expect(calls).toContainEqual(operationRequest("decision", 202));
  });

  it.each(["association", "decision", "finding"] as const)(
    "ignores a delayed detail refresh after %s succeeds",
    async (operation) => {
      const { wrapper, router, hold, calls } = await openReview();
      const refresh = hold({ method: "GET", path: "/api/projects/3/reviews/101" });
      await perform(wrapper, operation);
      await router.push("/reviews/202?project=3");
      await flushPromises();
      const count = calls.length;
      refresh.complete();
      await flushPromises();

      expect(wrapper.get("#review-progress-title").text()).toContain("202");
      expect(wrapper.get(".pull-request-number").text()).toContain("PR 3/212");
      expect(calls).toHaveLength(count);
    },
  );

  it.each(OPERATIONS)("an old %s failure cannot clear a new pending operation", async (operation) => {
    const { wrapper, router, calls, hold } = await openReview();
    const old = hold(operationRequest(operation));
    await perform(wrapper, operation);
    await router.push("/reviews/202?project=3");
    await flushPromises();
    const current = hold(operationRequest(operation, 202));
    await perform(wrapper, operation);
    expect(calls).toContainEqual(operationRequest(operation, 202));
    await wrapper.get("#decision-comment").setValue("new page comment");
    await wrapper.get("#association-reason").setValue("new page association");
    old.fail();
    await flushPromises();

    expect(wrapper.text()).not.toContain("old page failed");
    expect(wrapper.get<HTMLTextAreaElement>("#decision-comment").element.value).toBe("new page comment");
    expect(wrapper.get<HTMLInputElement>("#association-reason").element.value).toBe("new page association");
    const selector = {
      association: "form.inline-form button", decision: '[data-decision="APPROVE"]',
      finding: '[data-action="CONFIRM"]', events: ".finding-events-button",
    }[operation];
    expect(wrapper.get(selector).attributes("disabled")).toBeDefined();
    current.complete();
    await flushPromises();
    expect(wrapper.get(selector).attributes("disabled")).toBeUndefined();
  });

  it("invalidates operations when revisiting the same review", async () => {
    const { wrapper, router, hold } = await openReview();
    const old = hold(operationRequest("decision"));
    await perform(wrapper, "decision");
    await router.push("/reviews/202?project=3");
    await flushPromises();
    await router.push("/reviews/101?project=3");
    await flushPromises();
    await wrapper.get("#decision-comment").setValue("new visit");
    old.complete();
    await flushPromises();
    expect(wrapper.get<HTMLTextAreaElement>("#decision-comment").element.value).toBe("new visit");
  });

  it("includes project identity when the review route parameter stays the same", async () => {
    const { wrapper, router, hold } = await openReview();
    const old = hold(operationRequest("association"));
    await perform(wrapper, "association");
    await router.push("/reviews/101?project=4");
    await flushPromises();
    old.complete();
    await flushPromises();
    expect(wrapper.get(".pull-request-number").text()).toContain("PR 4/111");
  });

  it("does not start follow-up reads after unmount", async () => {
    const { wrapper, hold, calls } = await openReview();
    const old = hold(operationRequest("decision"));
    await perform(wrapper, "decision");
    wrapper.unmount();
    const count = calls.length;
    old.complete();
    await flushPromises();
    expect(calls).toHaveLength(count);
  });
});
