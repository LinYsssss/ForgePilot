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

const account = { id: 1, username: "lead", displayName: "负责人" };

const project = {
  id: 3,
  name: "ForgePilot",
  status: "ACTIVE",
  createdAt: "2026-08-21T02:00:00Z",
  myRoles: ["LEADER"],
};

const members = [
  {
    userId: 1,
    username: "lead",
    displayName: "负责人",
    roles: ["LEADER"],
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
  reviewerId: null,
  reviewerName: null,
  createdAt: "2026-08-21T02:10:00Z",
  updatedAt: "2026-08-21T02:10:00Z",
  reviewActivity: "NO_PR",
  currentRevision: revision,
};

const requirementDocument = {
  id: 44,
  projectId: 3,
  sourceType: "REQUIREMENT_ATTACHMENT",
  sourceRequirementId: 12,
  title: "login.md",
  status: "READY",
  failureReason: null,
  createdAt: "2026-08-21T02:20:00Z",
  updatedAt: "2026-08-21T02:20:00Z",
  chunkCount: 1,
  embeddedChunkCount: 1,
  embeddingDimension: 4096,
  embeddingProvider: "openai-compatible",
  embeddingModel: "Qwen3-Embedding-8B",
  embeddingVersion: "v1",
};

const guidanceAnswer = {
  requirementId: 12,
  revisionId: 30,
  revisionSeq: 1,
  guidanceVersion: "guidance-2",
  summary: "沿用现有约定，先统一错误语义。",
  questions: ["需确认会话有效期。"],
  checklist: ["先统一错误语义；对应 AC-3；验证错误口令与不存在用户的响应一致。"],
  rules: ["保留统一错误返回"],
  risks: ["路由回归"],
  knowledgeSources: [{ documentId: 4, chunkSeq: 1, title: "登录规范", excerpt: "统一错误语义", similarity: 0.91 }],
};

const calls: RecordedCall[] = [];
let attachmentStatuses: Array<"PENDING" | "READY"> = ["READY"];
let attachmentReads = 0;

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function deferredResponse() {
  let resolve: (value: Response) => void = () => { throw new Error("response not initialized"); };
  const promise = new Promise<Response>((complete) => { resolve = complete; });
  return { promise, resolve };
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
  if (path === "/api/projects/3/requirements/12/review-activity") {
    return jsonResponse({
      activity: "NO_PR",
      counts: {
        REVIEW_REQUIRED: 0,
        FAILED: 0,
        CHANGES_REQUESTED: 0,
        REVIEWING: 0,
        PENDING: 0,
        APPROVED: 0,
      },
    });
  }
  if (path === "/api/projects/3/requirements/12/attachments") {
    const status = attachmentStatuses[Math.min(attachmentReads, attachmentStatuses.length - 1)];
    attachmentReads++;
    return jsonResponse([{
      ...requirementDocument,
      status,
      chunkCount: status === "READY" ? 1 : 0,
      embeddedChunkCount: status === "READY" ? 1 : 0,
    }]);
  }
  if (path === "/api/projects/3/requirements/12/attachments/44/content") {
    return jsonResponse({
      documentId: 44,
      fileName: "login.md",
      mediaType: "text/markdown",
      text: "# 登录文档\n\n错误语义必须一致。",
    });
  }
  if (path === "/api/projects/3/requirements/12/quality" && method === "POST") {
    return jsonResponse({
      requirementId: 12,
      revisionId: 30,
      revisionSeq: 1,
      qualityVersion: "v1",
      checkedAt: "2026-08-21T04:00:00Z",
      rules: [],
      ai: null,
    });
  }
  if (path === "/api/projects/3/requirements/12/guidance" && method === "POST") {
    return jsonResponse(guidanceAnswer);
  }
  if (path === "/api/projects/3/requirements/12") {
    return jsonResponse(method === "PATCH" ? { ...detail, updatedAt: "2026-08-21T03:00:00Z" } : detail);
  }
  throw new Error(`unexpected request: ${method} ${path}`);
}

async function mountDetailPage(
  statuses: Array<"PENDING" | "READY"> = ["READY"],
  override?: (path: string, method: string) => Response | Promise<Response> | undefined,
) {
  clearSession();
  calls.length = 0;
  attachmentStatuses = statuses;
  attachmentReads = 0;
  vi.stubGlobal(
    "fetch",
    vi.fn((path: string | URL | Request, init?: RequestInit) => {
      const method = (init?.method ?? "GET").toUpperCase();
      calls.push({
        path: String(path),
        method,
        body: typeof init?.body === "string" ? init.body : null,
      });
      return Promise.resolve(override?.(String(path), method) ?? respond(String(path), method));
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
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe("requirement detail contract", () => {
  it("refreshes a pending attachment without resetting the requirement draft", async () => {
    vi.useFakeTimers();
    const wrapper = await mountDetailPage(["PENDING", "READY"]);
    await wrapper.get("#edit-title").setValue("unsaved title");
    expect(wrapper.get(".attachment-section").text()).toContain("正在处理");

    await vi.advanceTimersByTimeAsync(2_000);
    await flushPromises();

    expect(wrapper.get(".attachment-section").text()).toContain("可用于召回");
    expect(wrapper.get<HTMLInputElement>("#edit-title").element.value).toBe("unsaved title");
    expect(attachmentReads).toBe(2);
    await vi.advanceTimersByTimeAsync(10_000);
    expect(attachmentReads).toBe(2);
  });

  it("shows requirement status without absorbing review activity", async () => {
    const wrapper = await mountDetailPage();

    const status = wrapper.find(".requirement-status");

    expect(status.text()).toBe("草稿");

    // The two values come from different endpoints and stay in different cells.
    expect(status.text()).not.toContain("NO_PR");
    expect(wrapper.find(".review-activity").text()).toBe("无关联 PR");
    expect(wrapper.get(".current-revision").text()).toContain("结构化需求");
    expect(wrapper.get(".attachment-section").text()).toContain("需求文档");

    await wrapper.get(".document-list button").trigger("click");
    await flushPromises();

    expect(wrapper.get(".document-reader").text()).toContain("错误语义必须一致");
    expect(wrapper.get(".document-list a").attributes("href"))
      .toBe("/api/projects/3/requirements/12/attachments/44/download");

    const createObjectUrl = vi.fn((_blob: Blob) => "blob:requirement");
    vi.stubGlobal("URL", {
      createObjectURL: createObjectUrl,
      revokeObjectURL: vi.fn(),
    });
    vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => undefined);
    const exportButton = wrapper.findAll("button")
      .find((button) => button.text() === "导出 Markdown");
    expect(exportButton).toBeDefined();
    await exportButton?.trigger("click");

    const exported = createObjectUrl.mock.calls[0]?.[0];
    expect(exported).toBeInstanceOf(Blob);
    expect(await exported?.text()).toContain("# 登录闭环");
    expect(await exported?.text()).toContain("**AC-1**：登录成功后进入项目列表");
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

  it("keeps quality and one-shot guidance on the requirement they belong to", async () => {
    const wrapper = await mountDetailPage();

    await wrapper.get(".quality-section button").trigger("click");
    await wrapper.get(".guidance-section button").trigger("click");
    await flushPromises();

    expect(calls.some((call) => call.path.endsWith("/quality") && call.method === "POST")).toBe(true);
    expect(calls.some((call) => call.path.endsWith("/guidance") && call.method === "POST")).toBe(true);
    expect(wrapper.get(".quality-report").text()).toContain("v1");
    expect(wrapper.get(".guidance-result").text()).toContain("统一错误语义");
    expect(wrapper.get(".guidance-result").text()).toContain("沿用现有约定");
    expect(wrapper.get(".guidance-result").text()).toContain("需确认会话有效期");
    expect(wrapper.get(".guidance-result").text()).toContain("guidance-2");
    expect(wrapper.get(".guidance-steps li").text()).toContain("对应 AC-3");
    expect(wrapper.get<HTMLDetailsElement>(".guidance-sources").element.open).toBe(false);
    expect(wrapper.get(".guidance-sources").text()).toContain("不是建议正确率");
  });
  it("does not request unsaved content or let an old draft answer finish a newer request", async () => {
    const first = deferredResponse();
    const second = deferredResponse();
    let requested = 0;
    const wrapper = await mountDetailPage(["READY"], (path, method) => {
      if (path.endsWith("/guidance")) return requested++ === 0 ? first.promise : second.promise;
      if (path === "/api/projects/3/requirements/12" && method === "PATCH") {
        // DRAFT 保存不换 revisionId，这条回归不能仅靠版本号不同而通过。
        return jsonResponse({ ...detail, currentRevision: { ...revision, title: "新的已保存标题" } });
      }
      return undefined;
    });
    const generate = () => wrapper.get<HTMLButtonElement>(".guidance-section .section-action-head button");
    await wrapper.get("#edit-title").setValue("未保存的标题");
    expect(generate().element.disabled).toBe(true);
    expect(wrapper.get(".guidance-unsaved").text()).toContain("保存草稿");
    await generate().trigger("click");
    expect(requested).toBe(0);

    await wrapper.get("#edit-title").setValue(revision.title);
    await generate().trigger("click");
    expect(generate().text()).toContain("正在生成");
    await wrapper.get("#edit-title").setValue("新的已保存标题");
    await wrapper.get("form.requirement-form").trigger("submit");
    await flushPromises();
    await generate().trigger("click");
    expect(requested).toBe(2);

    first.resolve(jsonResponse(guidanceAnswer));
    await flushPromises();
    expect(wrapper.find(".guidance-result").exists()).toBe(false);
    expect(generate().element.disabled).toBe(true);
    expect(generate().text()).toContain("正在生成");
    second.resolve(jsonResponse({ ...guidanceAnswer, summary: "针对新保存内容的建议。" }));
    await flushPromises();
    expect(wrapper.get(".guidance-result").text()).toContain("针对新保存内容的建议");
  });

  it("keeps valid advice on regeneration failure and shares one text for copy and download", async () => {
    let fail = false;
    const wrapper = await mountDetailPage(["READY"], (path) => path.endsWith("/guidance") && fail
      ? jsonResponse({ message: "服务暂不可用" }, 502) : undefined);
    await wrapper.get(".guidance-section .section-action-head button").trigger("click");
    await flushPromises();

    const writeText = vi.fn().mockResolvedValue(undefined);
    vi.stubGlobal("navigator", { clipboard: { writeText } });
    const createObjectUrl = vi.fn((_blob: Blob) => "blob:guidance");
    const revokeObjectUrl = vi.fn();
    vi.stubGlobal("URL", { createObjectURL: createObjectUrl, revokeObjectURL: revokeObjectUrl });
    vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => undefined);
    await wrapper.get("[data-guidance-copy]").trigger("click");
    await flushPromises();
    expect(wrapper.get(".guidance-result").text()).toContain("建议已复制");
    await wrapper.get("[data-guidance-download]").trigger("click");
    const text = await createObjectUrl.mock.calls[0]?.[0].text();
    expect(text).toBe(writeText.mock.calls[0]?.[0]);
    expect(text).toContain("REQ-12");
    expect(text).toContain("guidance-2");
    expect(text).toContain("需确认会话有效期");
    expect(text).toContain("登录规范");
    expect(revokeObjectUrl).toHaveBeenCalledWith("blob:guidance");

    writeText.mockRejectedValueOnce(new Error("clipboard denied"));
    await wrapper.get("[data-guidance-copy]").trigger("click");
    await flushPromises();
    expect(wrapper.get(".guidance-result").text()).toContain("复制失败，可下载 Markdown");
    fail = true;
    await wrapper.get(".guidance-section .section-action-head button").trigger("click");
    await flushPromises();
    expect(wrapper.get(".guidance-result").text()).toContain(guidanceAnswer.summary);
    expect(wrapper.get(".guidance-section").text()).toContain("以下仍为上一份有效建议");
  });

  it("renders legacy advice without inventing an empty questions assessment", async () => {
    const wrapper = await mountDetailPage(["READY"], (path) => path.endsWith("/guidance")
      ? jsonResponse({ ...guidanceAnswer, summary: undefined, questions: undefined, guidanceVersion: undefined })
      : undefined);
    await wrapper.get(".guidance-section .section-action-head button").trigger("click");
    await flushPromises();
    expect(wrapper.get(".guidance-result").text()).toContain("统一错误语义");
    expect(wrapper.get(".guidance-result").text()).toContain("未记录（旧版服务）");
    expect(wrapper.get(".guidance-result").text()).not.toContain("本次未提出待确认事项");
  });
});

// Phase is derived; a draft/terminal requirement cannot be relabelled by a PR.
describe("requirementPhase", () => {
  it("shows review and rework without changing lifecycle or hiding missing activity", async () => {
    const { requirementPhase } = await import("../src/features/requirement/status");
    expect(requirementPhase("IN_DEVELOPMENT", "REVIEWING")).toBe("审查中");
    expect(requirementPhase("IN_DEVELOPMENT", "CHANGES_REQUESTED")).toBe("开发中 · 退回修改");
    expect(requirementPhase("IN_DEVELOPMENT", "MIXED")).toBe("多 PR 处理中");
    expect(requirementPhase("DRAFT", "REVIEWING")).toBe("草稿");
    expect(requirementPhase("DONE", "REVIEWING")).toBe("已完成");
    expect(requirementPhase("IN_DEVELOPMENT", null)).toContain("未获取");
  });
});
