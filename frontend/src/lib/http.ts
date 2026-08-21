/** Error body shape shared by every ForgePilot endpoint (api-contract.md §0). */
export interface ApiError {
  code: string;
  message: string;
  traceId: string;
}

export class HttpError extends Error {
  constructor(
    public readonly status: number,
    public readonly body: unknown,
  ) {
    super(`HTTP request failed with status ${status}`);
    this.name = "HttpError";
  }
}

export type RequestJsonOptions = Omit<RequestInit, "credentials">;

const STATUSES_WITHOUT_BODY = new Set([204, 205, 304]);
const WRITE_METHODS = new Set(["POST", "PUT", "PATCH", "DELETE"]);
const CSRF_COOKIE = "XSRF-TOKEN";
const CSRF_HEADER = "X-XSRF-TOKEN";

let unauthorizedHandler: (() => void) | null = null;

/**
 * Registers the single reaction to an expired session. `requestJson` calls it
 * for every 401 so that no view has to redirect on its own.
 */
export function setUnauthorizedHandler(handler: () => void): void {
  unauthorizedHandler = handler;
}

function readCookie(name: string): string | null {
  for (const entry of document.cookie.split(";")) {
    const separator = entry.indexOf("=");
    if (separator > 0 && entry.slice(0, separator).trim() === name) {
      return decodeURIComponent(entry.slice(separator + 1));
    }
  }
  return null;
}

function parseJsonOrText(text: string): unknown {
  if (text === "") {
    return undefined;
  }
  try {
    return JSON.parse(text) as unknown;
  } catch {
    return text;
  }
}

function isApiError(body: unknown): body is ApiError {
  return (
    typeof body === "object" &&
    body !== null &&
    "message" in body &&
    typeof body.message === "string"
  );
}

/** Message to show a user for a failed request, preferring the server's own text. */
export function apiErrorMessage(error: unknown): string {
  if (error instanceof HttpError) {
    return isApiError(error.body) ? error.body.message : error.message;
  }
  return error instanceof Error ? error.message : String(error);
}

export async function requestJson<T>(
  path: string,
  options: RequestJsonOptions = {},
): Promise<T> {
  const headers = new Headers(options.headers);
  headers.set("Accept", "application/json");
  if (options.body != null && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }
  if (WRITE_METHODS.has((options.method ?? "GET").toUpperCase())) {
    const token = readCookie(CSRF_COOKIE);
    if (token !== null) {
      headers.set(CSRF_HEADER, token);
    }
  }

  const response = await fetch(path, {
    ...options,
    credentials: "same-origin",
    headers,
  });

  const text = STATUSES_WITHOUT_BODY.has(response.status) ? "" : await response.text();
  if (!response.ok) {
    if (response.status === 401) {
      unauthorizedHandler?.();
    }
    throw new HttpError(response.status, parseJsonOrText(text));
  }

  return (text === "" ? undefined : JSON.parse(text)) as T;
}
