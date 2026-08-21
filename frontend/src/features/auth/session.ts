import { computed, ref, type ComputedRef } from "vue";

import { HttpError, requestJson } from "../../lib/http";

/** `{id, username}` returned by every auth endpoint (api-contract.md §1). */
export interface AccountView {
  id: number;
  username: string;
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
 * Cold start probe. `GET /api/auth/me` also issues the `XSRF-TOKEN` cookie the
 * request layer needs for later writes (api-contract.md §0).
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

export async function register(username: string, password: string): Promise<void> {
  await requestJson<AccountView>("/api/auth/register", {
    method: "POST",
    body: JSON.stringify({ username, password }),
  });
}

export async function signOut(): Promise<void> {
  await requestJson<void>("/api/auth/logout", { method: "POST" });
  account.value = null;
}
