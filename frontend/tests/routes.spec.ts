import { mount } from "@vue/test-utils";
import { createMemoryHistory } from "vue-router";

import App from "../src/App.vue";
import { createAppRouter } from "../src/app/router";
import {
  PRODUCT_ROUTE_PATHS,
  TOP_LEVEL_NAVIGATION,
} from "../src/app/routes";

describe("foundation route contract", () => {
  it("keeps exactly the seven approved product paths and three top-level entries", () => {
    expect(PRODUCT_ROUTE_PATHS).toEqual([
      "/projects",
      "/projects/:id/members",
      "/projects/:id/settings",
      "/requirements",
      "/requirements/:id",
      "/reviews",
      "/reviews/:id",
    ]);
    expect(TOP_LEVEL_NAVIGATION.map((item) => item.to)).toEqual([
      "/projects",
      "/requirements",
      "/reviews",
    ]);
  });

  it("redirects root and renders a semantic, business-free shell", async () => {
    const router = createAppRouter(createMemoryHistory());
    await router.push("/");
    await router.isReady();

    const wrapper = mount(App, { global: { plugins: [router] } });

    expect(router.currentRoute.value.path).toBe("/projects");
    expect(wrapper.find("header").exists()).toBe(true);
    expect(wrapper.find('nav[aria-label="主导航"]').exists()).toBe(true);
    expect(wrapper.findAll(".nav-link")).toHaveLength(3);
    expect(wrapper.find("main h1").text()).toBe("项目");
    expect(wrapper.text()).toContain("当前仅为工程底座");
    expect(wrapper.findAll("form")).toHaveLength(0);
    expect(wrapper.findAll("button")).toHaveLength(0);
  });

  it("exposes a skip link that targets the main landmark", async () => {
    const router = createAppRouter(createMemoryHistory());
    await router.push("/");
    await router.isReady();

    const wrapper = mount(App, { global: { plugins: [router] } });

    const skipLink = wrapper.find("a.skip-link");
    expect(skipLink.exists()).toBe(true);
    expect(skipLink.attributes("href")).toBe("#app-main");
    expect(wrapper.find("main#app-main").exists()).toBe(true);
  });
});
