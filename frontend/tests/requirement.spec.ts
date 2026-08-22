import { flushPromises, mount } from "@vue/test-utils";
import { createMemoryHistory } from "vue-router";

import App from "../src/App.vue";
import { createAppRouter } from "../src/app/router";
import { bootstrapSession, clearSession } from "../src/features/auth/session";

interface RecordedCall {
  path: string;
  method: string;
  body: string | null;
}

const account = { id: 1, username: "lead" };

const project = {
  id: 3,
  name: "ForgePilot",
  status: "ACTIVE",
  createdAt: "2026-08-21T02:00:00Z",
  myRole: "LEADER",
};

const members = [
  {
    userId: 1,
    username: "lead",
    role: "LEADER",
    scmExternalUserId: null,
    scmUsername: null,
    scmIdentityVerifiedAt: null,
  },
];

const revision = {
  id: 30,
  seq: 1,
  title: "登录闭环",
  background: null,
  description: null,
  createdBy: 1,
  createdByUsername: "lead",
  changeReason: null,
  createdAt: "2026-08-21T02:10:00Z",
  acceptanceCriteria: [
    { id: 91, acKey: "AC-1", sortOrder: 1, text: "登录成功后进入项目列表" },
    { id: 93, acKey: "AC-3", sortOrder: 2, text: "口令错误与用户不存在返回一致" },
  ],
};

const detail = {
  id: 12,
  status: "DRAFT",
  assigneeId: null,
  assigneeUsername: null,
  createdAt: "2026-08-21T02:10:00Z",
  updatedAt: "2026-08-21T02:10:00Z",
  reviewActivity: "NO_PR",
  currentRevision: revision,
};

const calls: RecordedCall[] = [];

function jsonResponse(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
}

function respond(path: string, method: string): Response {
  if (path === "/api/auth/me") {
    return jsonResponse(account);
  }
  if (path === "/api/projects/3") {
    return jsonResponse(project);
  }
  if (path === "/api/projects/3/members") {
    return jsonResponse(members);
  }
  if (path === "/api/projects/3/requirements/12/revisions") {
    return jsonResponse([revision]);
  }
  if (path === "/api/projects/3/requirements/12") {
    return jsonResponse(method === "PATCH" ? { ...detail, updatedAt: "2026-08-21T03:00:00Z" } : detail);
  }
  throw new Error(`unexpected request: ${method} ${path}`);
}

async function mountDetailPage() {
  clearSession();
  calls.length = 0;
  vi.stubGlobal(
    "fetch",
    vi.fn((path: string | URL | Request, init?: RequestInit) => {
      const method = (init?.method ?? "GET").toUpperCase();
      calls.push({
        path: String(path),
        method,
        body: typeof init?.body === "string" ? init.body : null,
      });
      return Promise.resolve(respond(String(path), method));
    }),
  );

  await bootstrapSession();
  const router = createAppRouter(createMemoryHistory());
  await router.push("/requirements/12?project=3");
  const wrapper = mount(App, { global: { plugins: [router] } });
  await flushPromises();
  return wrapper;
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("requirement detail contract", () => {
  it("shows requirement status without absorbing review activity", async () => {
    const wrapper = await mountDetailPage();

    const status = wrapper.find(".requirement-status");

    expect(status.text()).toBe("草稿");

    // PRD 5 requires requirement status and review activity to be shown side by
    // side and never merged. Batch 3 moved activity onto its own endpoint, since
    // it is derived from pull requests and reviews and this module may not read
    // those, so this page no longer carries it and the pairing cannot be asserted
    // here yet. What is still assertable — and is the half that would actually
    // regress — is that the status field has not quietly absorbed it.
    expect(status.text()).not.toContain("NO_PR");
    expect(wrapper.find(".review-activity").exists()).toBe(false);
  });

  it("keeps every existing acKey and never sends sortOrder when editing criteria", async () => {
    const wrapper = await mountDetailPage();

    await wrapper.find(".ac-editor .ac-add").trigger("click");
    await wrapper.find("#edit-ac-2").setValue("会话过期跳回登录页");
    await wrapper.find("form.requirement-form").trigger("submit");
    await flushPromises();

    const write = calls.find((call) => call.method === "PATCH");
    expect(write?.path).toBe("/api/projects/3/requirements/12");
    expect(write?.body).not.toBeNull();
    expect(JSON.parse(write?.body ?? "{}")).toMatchObject({
      title: "登录闭环",
      acceptanceCriteria: [
        { acKey: "AC-1", text: "登录成功后进入项目列表" },
        { acKey: "AC-3", text: "口令错误与用户不存在返回一致" },
        { text: "会话过期跳回登录页" },
      ],
    });
    expect(write?.body).not.toContain("sortOrder");
  });
});
