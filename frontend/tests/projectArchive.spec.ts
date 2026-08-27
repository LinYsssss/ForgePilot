import { flushPromises, mount } from "@vue/test-utils";
import { createMemoryHistory } from "vue-router";

import App from "../src/App.vue";
import { createAppRouter } from "../src/app/router";
import { bootstrapSession, clearSession } from "../src/features/auth/session";

/**
 * 归档项目的那道二次确认闸。
 *
 * <p>锁的不变量只有一条：**必须把项目名一字不差地重新输入一遍，确认按钮才可用**。
 * 这是整个功能里唯一承重的东西——归档本身只是一次状态写入，而这道闸是它与
 * 误操作之间仅有的隔离。放宽任何一条（trim、忽略大小写、前缀匹配）都会让
 * 它形同虚设，而那种退化不会有任何测试以外的迹象。
 */

const LEADER_PROJECT = {
  id: 7,
  name: "ForgePilot",
  status: "ACTIVE",
  createdAt: "2026-08-24T00:00:00Z",
  myRoles: ["LEADER"],
};

let archiveCalls: string[];

function stubServer(): void {
  clearSession();
  archiveCalls = [];
  vi.stubGlobal(
    "fetch",
    vi.fn((input: string | URL | Request, init?: RequestInit) => {
      const path = String(input);
      if ((init?.method ?? "GET") === "POST" && path.endsWith("/archive")) {
        archiveCalls.push(path);
        return Promise.resolve(new Response(null, { status: 204 }));
      }
      const body = path === "/api/auth/me"
        ? { id: 1, username: "lead", displayName: "负责人" }
        : path === "/api/projects"
          ? [LEADER_PROJECT]
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

async function openProjects() {
  stubServer();
  await bootstrapSession();
  const router = createAppRouter(createMemoryHistory());
  await router.push("/projects");
  const wrapper = mount(App, { global: { plugins: [router] } });
  await flushPromises();
  await flushPromises();
  return wrapper;
}

function confirmButton(wrapper: Awaited<ReturnType<typeof openProjects>>) {
  return wrapper
    .findAll(".archive-confirm button")
    .find((button) => button.text().includes("确认归档"));
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("archiving a project is gated on retyping its name", () => {
  it("keeps the confirm button disabled until the typed name matches exactly", async () => {
    const wrapper = await openProjects();

    const archive = wrapper
      .findAll(".project-card .record-actions button")
      .find((button) => button.text() === "归档项目");
    expect(archive).toBeDefined();
    await archive!.trigger("click");

    // 面板刚展开，输入为空：闸是关的。
    expect(confirmButton(wrapper)!.attributes("disabled")).toBeDefined();

    const input = wrapper.find(".archive-confirm .field input");

    // 前缀不算数。
    await input.setValue("Forge");
    expect(confirmButton(wrapper)!.attributes("disabled")).toBeDefined();

    // 大小写不算数。
    await input.setValue("forgepilot");
    expect(confirmButton(wrapper)!.attributes("disabled")).toBeDefined();

    // 首尾空白不算数——不做 trim 是有意的。
    await input.setValue(" ForgePilot ");
    expect(confirmButton(wrapper)!.attributes("disabled")).toBeDefined();

    // 一字不差才放行。
    await input.setValue("ForgePilot");
    expect(confirmButton(wrapper)!.attributes("disabled")).toBeUndefined();

    expect(archiveCalls).toEqual([]);
  });

  it("moves the project into the archived group once confirmed", async () => {
    const wrapper = await openProjects();

    const archive = wrapper
      .findAll(".project-card .record-actions button")
      .find((button) => button.text() === "归档项目");
    await archive!.trigger("click");
    await wrapper.find(".archive-confirm .field input").setValue("ForgePilot");
    expect(confirmButton(wrapper)!.attributes("disabled")).toBeUndefined();

    // 点 submit 按钮在 jsdom 里不会自动提交表单（真实浏览器会），
    // 所以这里直接触发表单的 submit。
    await wrapper.find(".archive-confirm").trigger("submit");
    await flushPromises();

    expect(archiveCalls).toEqual(["/api/projects/7/archive"]);
    expect(wrapper.findAll(".project-card")).toHaveLength(0);
    expect(wrapper.find(".archived-disclosure").text()).toContain("ForgePilot");
  });
});
