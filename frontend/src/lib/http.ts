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

export async function requestJson<T>(
  path: string,
  options: RequestJsonOptions = {},
): Promise<T> {
  const headers = new Headers(options.headers);
  headers.set("Accept", "application/json");
  if (options.body != null && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }

  const response = await fetch(path, {
    ...options,
    credentials: "same-origin",
    headers,
  });

  const text = STATUSES_WITHOUT_BODY.has(response.status) ? "" : await response.text();
  if (!response.ok) {
    throw new HttpError(response.status, parseJsonOrText(text));
  }

  return (text === "" ? undefined : JSON.parse(text)) as T;
}
