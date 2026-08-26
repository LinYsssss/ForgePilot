import { computed, ref, type ComputedRef } from "vue";

import { HttpError, requestJson } from "../../lib/http";

/** 每个认证端点都返回的 `{id, username}`（API.md）。 */
export interface AccountView {
  id: number;
  username: string;
  displayName: string;
}

const account = ref<AccountView | null>(null);
const readonlyAccount = computed(() => account.value);

export function useSession(): { account: ComputedRef<AccountView | null> } {
  return { account: readonlyAccount };
}

export function hasSession(): boolean {
  return account.value !== null;
}

export function clearSession(): void {
  account.value = null;
}

/**
 * 冷启动探测。`GET /api/auth/me` 同时会下发 `XSRF-TOKEN` cookie，
 * 后续写操作的请求层需要它（API.md）。
 */
export async function bootstrapSession(): Promise<void> {
  try {
    account.value = await requestJson<AccountView>("/api/auth/me");
  } catch (error: unknown) {
    if (error instanceof HttpError && error.status === 401) {
      account.value = null;
      return;
    }
    throw error;
  }
}

export async function signIn(username: string, password: string): Promise<void> {
  account.value = await requestJson<AccountView>("/api/auth/login", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ username, password }).toString(),
  });
}

export async function register(
  username: string,
  displayName: string,
  password: string,
): Promise<void> {
  await requestJson<AccountView>("/api/auth/register", {
    method: "POST",
    body: JSON.stringify({ username, displayName, password }),
  });
}

export async function changeDisplayName(displayName: string): Promise<void> {
  account.value = await requestJson<AccountView>("/api/auth/profile", {
    method: "PATCH",
    body: JSON.stringify({ displayName }),
  });
}

/**
 * 修改当前登录账号的口令。后端会递增该账号的 session version，
 * 同时把新版本号写进本次 HttpSession，因此本标签页保持登录，
 * 而其余所有会话随之失效。
 */
export async function changePassword(
  currentPassword: string,
  newPassword: string,
): Promise<void> {
  await requestJson<void>("/api/auth/password", {
    method: "POST",
    body: JSON.stringify({ currentPassword, newPassword }),
  });
}

export async function signOut(): Promise<void> {
  await requestJson<void>("/api/auth/logout", { method: "POST" });
  account.value = null;
}
