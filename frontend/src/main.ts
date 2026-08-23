import { createApp } from "vue";

import App from "./App.vue";
import { createAppRouter } from "./app/router";
import { bootstrapSession } from "./features/auth/session";
import "./styles/tokens.css";
import "./styles/base.css";

// 冷启动探测同时会下发 CSRF cookie，因此它必须先于路由判断
// 「首个路由是否可达」而运行。
void bootstrapSession().then(() => {
  createApp(App).use(createAppRouter()).mount("#app");
});
