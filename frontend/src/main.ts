import { createApp } from "vue";

import App from "./App.vue";
import { createAppRouter } from "./app/router";
import { bootstrapSession } from "./features/auth/session";
import "./styles/tokens.css";
import "./styles/base.css";

// The cold-start probe also issues the CSRF cookie, so it runs before the
// router decides whether the first route is reachable.
void bootstrapSession().then(() => {
  createApp(App).use(createAppRouter()).mount("#app");
});
