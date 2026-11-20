import { flushPromises, mount } from "@vue/test-utils";
import { createMemoryHistory } from "vue-router";

import App from "../src/App.vue";
import { createAppRouter } from "../src/app/router";
import { bootstrapSession, clearSession } from "../src/features/auth/session";

/**
 * 从需求文档提取草稿。
 *
 * <p>锁的不变量是**草稿只进表单、保存仍由人提交**：提取本身不得创建需求，
 * 而创建请求发送的必须是表单里的当前内容——包括负责人对草稿的修改。
 */

const account = { id: 1, username: "lead", displayName: "负责人" };
const project = { id: 3, name: "ForgePilot", status: "ACTIVE", createdAt: "2026-08-21T02:00:00Z", myRoles: ["LEADER"] };
const draft = {
  title: "借书接口幂等",
  background: "",
  description: "防止重复提交。",
  acceptanceCriteria: ["重复请求返回首次结果。", "可借册数为 0 时返回 409。"],
};

interface Call { method: string; path: string; body: unknown }

function stubServer(calls: Call[]): void {
  clearSession();
  vi.stubGlobal(
    "fetch",
    vi.fn((input: string | URL | Request, init?: RequestInit) => {
      const path = String(input);
      const method = init?.method ?? "GET";
      if (method !== "GET") {
        calls.push({ method, path, body: JSON.parse(String(init?.body)) });
      }
      const body = path === "/api/auth/me"
        ? account
        : path === "/api/projects"
          ? [project]
          : path === "/api/projects/3/requirements/draft"
            ? draft
            : path === "/api/projects/3/requirements" && method === "POST"
              ? { id: 21, status: "DRAFT", currentRevision: { id: 1, seq: 1, acceptanceCriteria: [] } }
              : path.includes("review-activity")
                ? {}
                : [];
      return Promise.resolve(
        new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } }),
      );
    }),
  );
}

describe("requirement draft from a document", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    clearSession();
  });

  it("fills the form without creating anything, and creation sends the edited form", async () => {
    const calls: Call[] = [];
    stubServer(calls);
    await bootstrapSession();
    const router = createAppRouter(createMemoryHistory());
    await router.push("/requirements?project=3");
    await router.isReady();
    const wrapper = mount(App, { global: { plugins: [router] } });
    await flushPromises();

    await wrapper.get("#requirement-document").setValue("借书接口需要幂等。重复请求返回首次结果。");
    const extract = wrapper.findAll("button").find((button) => button.text() === "提取草稿");
    await extract!.trigger("click");
    await flushPromises();

    expect(calls).toEqual([
      { method: "POST", path: "/api/projects/3/requirements/draft", body: { text: "借书接口需要幂等。重复请求返回首次结果。" } },
    ]);
    expect((wrapper.get("#requirement-title").element as HTMLInputElement).value).toBe("借书接口幂等");
    expect(wrapper.text()).toContain("已提取 2 条验收条件草稿");

    // 创建成功后会跳去详情页；这里只关心发出的请求体，不渲染详情。
    const push = vi.spyOn(router, "push").mockResolvedValue(undefined);
    await wrapper.get("#requirement-title").setValue("借书接口幂等与并发安全");
    await wrapper.get("form.requirement-create").trigger("submit");
    await flushPromises();

    expect(calls[1]).toEqual({
      method: "POST",
      path: "/api/projects/3/requirements",
      body: {
        title: "借书接口幂等与并发安全",
        background: null,
        description: "防止重复提交。",
        acceptanceCriteria: [{ text: "重复请求返回首次结果。" }, { text: "可借册数为 0 时返回 409。" }],
      },
    });
    expect(push).toHaveBeenCalledTimes(1);
    wrapper.unmount();
  });

  it("refuses a document over the limit before sending anything, and says why", async () => {
    // 选择文件不受 textarea maxlength 约束；不在这里拦下，用户只会从后端得到一句看不出原因的校验错误。
    const calls: Call[] = [];
    stubServer(calls);
    await bootstrapSession();
    const router = createAppRouter(createMemoryHistory());
    await router.push("/requirements?project=3");
    await router.isReady();
    const wrapper = mount(App, { global: { plugins: [router] } });
    await flushPromises();

    await wrapper.get("#requirement-document").setValue("读".repeat(60001));
    await wrapper.findAll("button").find((button) => button.text() === "提取草稿")!.trigger("click");
    await flushPromises();

    expect(calls).toEqual([]);
    expect(wrapper.get("form.requirement-create [role=alert]").text()).toContain("超过 60000 字符的上限");
    wrapper.unmount();
  });
});
