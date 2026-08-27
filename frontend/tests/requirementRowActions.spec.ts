import { flushPromises, mount } from "@vue/test-utils";
import { createMemoryHistory } from "vue-router";

import App from "../src/App.vue";
import { createAppRouter } from "../src/app/router";
import { bootstrapSession, clearSession } from "../src/features/auth/session";

/**
 * 需求列表的行内动作。
 *
 * <p>这些入口只是把详情页早就有的能力提到列表上，因此锁的不变量是
 * **门槛没有被放宽**：编辑入口不得出现在终态需求上，作废不得出现在
 * 已经是终态的需求上，删除只能出现在已作废的需求上。
 *
 * <p>写反的方向有真实后果：给 DONE 一个「发布新版本」入口，用户点进去
 * 会撞上后端的 409；给非作废需求一个「删除」按钮，点下去同样是 409。
 * 两种都是把服务端约束当成了 UI 提示。
 */

const account = { id: 1, username: "lead", displayName: "负责人" };

const project = {
  id: 3,
  name: "ForgePilot",
  status: "ACTIVE",
  createdAt: "2026-08-21T02:00:00Z",
  myRoles: ["LEADER"],
};

function summary(id: number, status: string) {
  return {
    id,
    title: `需求 ${id}`,
    status,
    assigneeId: null,
    assigneeUsername: null,
    currentRevisionSeq: 1,
    updatedAt: "2026-08-21T02:10:00Z",
  };
}

const REQUIREMENTS = [
  summary(11, "DRAFT"),
  summary(12, "READY"),
  summary(13, "IN_DEVELOPMENT"),
  summary(14, "DONE"),
  summary(15, "CANCELED"),
];

function stubServer(): void {
  clearSession();
  vi.stubGlobal(
    "fetch",
    vi.fn((input: string | URL | Request) => {
      const path = String(input);
      const body = path === "/api/auth/me"
        ? account
        : path === "/api/projects"
          ? [project]
          : path.startsWith("/api/projects/3/requirements")
            ? REQUIREMENTS
            : path.includes("review-activity")
              ? {}
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

async function openRequirements() {
  stubServer();
  await bootstrapSession();
  const router = createAppRouter(createMemoryHistory());
  await router.push("/requirements?project=3");
  const wrapper = mount(App, { global: { plugins: [router] } });
  await flushPromises();
  await flushPromises();
  return wrapper;
}

/** 拿到某条需求那张卡上的全部行内动作文案。 */
function actionsFor(
  wrapper: Awaited<ReturnType<typeof openRequirements>>,
  id: number,
): string[] {
  const card = wrapper
    .findAll(".requirement-card")
    .find((it) => it.text().includes(`需求 ${id}`));
  if (card === undefined) {
    throw new Error(`没有找到需求 ${id} 的卡片`);
  }
  return card
    .findAll(".requirement-actions .button")
    .map((it) => it.text());
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("requirement row actions do not widen the server-side gates", () => {
  it("offers draft editing only on DRAFT and revision publishing on the live states", async () => {
    const wrapper = await openRequirements();

    expect(actionsFor(wrapper, 11)).toContain("编辑草稿");
    expect(actionsFor(wrapper, 12)).toContain("发布新版本");
    expect(actionsFor(wrapper, 13)).toContain("发布新版本");

    // 终态没有任何编辑入口——后端会以 409 拒绝，UI 不该先把人领过去。
    expect(actionsFor(wrapper, 14)).not.toContain("发布新版本");
    expect(actionsFor(wrapper, 15)).not.toContain("发布新版本");
    expect(actionsFor(wrapper, 14)).not.toContain("编辑草稿");
    expect(actionsFor(wrapper, 15)).not.toContain("编辑草稿");
  });

  it("offers cancel only where a CANCELED transition exists", async () => {
    const wrapper = await openRequirements();

    expect(actionsFor(wrapper, 11)).toContain("作废");
    expect(actionsFor(wrapper, 12)).toContain("作废");
    expect(actionsFor(wrapper, 13)).toContain("作废");
    expect(actionsFor(wrapper, 14)).not.toContain("作废");
    expect(actionsFor(wrapper, 15)).not.toContain("作废");
  });

  it("offers delete only on an already-canceled requirement", async () => {
    const wrapper = await openRequirements();

    expect(actionsFor(wrapper, 15)).toContain("删除");
    for (const id of [11, 12, 13, 14]) {
      expect(actionsFor(wrapper, id)).not.toContain("删除");
    }
  });
});
