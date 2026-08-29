import { flushPromises, mount } from "@vue/test-utils";
import { createMemoryHistory } from "vue-router";

import App from "../src/App.vue";
import { createAppRouter } from "../src/app/router";
import { bootstrapSession, clearSession } from "../src/features/auth/session";

/**
 * 校准卡上唯一一条会静默说谎的不变量：**没有样本时不得显示成一个数字**。
 *
 * 把 `null` 折成 `0%` 会把「还没有人裁决过这一档」读成「模型在这一档上从来没对过」，
 * 而这两句话导向完全相反的处置——前者去干活，后者去换模型。区间同理：`[0,0]`
 * 是「测得很确定」，不是「什么都没测」。
 */

const PROJECTS = [
  { id: 7, name: "项目", status: "ACTIVE", createdAt: "2026-08-24T00:00:00Z", myRoles: ["LEADER"] },
];

/** 一个空箱、一个有样本的箱：两种渲染必须同时正确，否则只是碰巧对了一半。 */
const CALIBRATION = {
  bins: [
    { confidence: "HIGH", adjudicated: 4, confirmed: 3, confirmedRate: 0.75, interval: { low: 0.301, high: 0.954 } },
    { confidence: "MEDIUM", adjudicated: 0, confirmed: 0, confirmedRate: null, interval: null },
    { confidence: "LOW", adjudicated: 0, confirmed: 0, confirmedRate: null, interval: null },
  ],
  awaitingAdjudication: 2,
  withoutConfidence: 8,
};

function stubServer(): void {
  clearSession();
  vi.stubGlobal(
    "fetch",
    vi.fn((input: string | URL | Request) => {
      const path = String(input);
      const body = path === "/api/auth/me"
        ? { id: 1, username: "lead", displayName: "负责人" }
        : path === "/api/projects"
          ? PROJECTS
          : path.endsWith("/review-calibration")
            ? CALIBRATION
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

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("review calibration card", () => {
  it("shows a sample-starved bin as such instead of as a zero percent", async () => {
    stubServer();
    await bootstrapSession();
    const router = createAppRouter(createMemoryHistory());
    await router.push("/workspace?project=7");
    const wrapper = mount(App, { global: { plugins: [router] } });
    await flushPromises();
    await flushPromises();

    const rows = wrapper.findAll(".calibration-table tbody tr");
    expect(rows).toHaveLength(3);

    const populated = rows[0]?.text() ?? "";
    expect(populated).toContain("75%");
    expect(populated).toContain("30%");
    expect(populated).toContain("95%");

    const empty = rows[1]?.text() ?? "";
    expect(empty).toContain("样本不足");
    expect(empty).not.toContain("0%");

    // 空表的两种成因必须分别说出来，否则读者无从判断该去干活还是该去重跑审查。
    const card = wrapper.find(".calibration-panel").text();
    expect(card).toContain("2");
    expect(card).toContain("8");
  });
});
