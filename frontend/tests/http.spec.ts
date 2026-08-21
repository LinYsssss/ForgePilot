import { HttpError, requestJson, setUnauthorizedHandler } from "../src/lib/http";

interface RecordedCall {
  path: string;
  init: RequestInit;
}

function recordingFetch(response: () => Response): RecordedCall[] {
  const calls: RecordedCall[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn((path: string | URL | Request, init?: RequestInit) => {
      calls.push({ path: String(path), init: init ?? {} });
      return Promise.resolve(response());
    }),
  );
  return calls;
}

afterEach(() => {
  vi.unstubAllGlobals();
  setUnauthorizedHandler(() => undefined);
  document.cookie = "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT";
});

describe("same-origin request boundary", () => {
  it("uses same-origin credentials and JSON headers without adding retries", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ status: "UP" }), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      }),
    );
    vi.stubGlobal("fetch", fetchMock);

    await expect(requestJson<{ status: string }>("/api/health")).resolves.toEqual({ status: "UP" });
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock).toHaveBeenCalledWith(
      "/api/health",
      expect.objectContaining({ credentials: "same-origin" }),
    );
  });

  it("preserves the HTTP status and parsed body on failure", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ message: "unavailable" }), {
          status: 503,
          headers: { "Content-Type": "application/json" },
        }),
      ),
    );

    const error = await requestJson("/api/health").catch((reason: unknown) => reason);
    expect(error).toBeInstanceOf(HttpError);
    expect(error).toMatchObject({ status: 503, body: { message: "unavailable" } });
  });

  it("keeps the status and raw text when a gateway returns a non-JSON error body", async () => {
    const html = "<html><head><title>502 Bad Gateway</title></head><body>502</body></html>";
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(html, {
          status: 502,
          headers: { "Content-Type": "text/html" },
        }),
      ),
    );

    const error = await requestJson("/api/health").catch((reason: unknown) => reason);
    expect(error).toBeInstanceOf(HttpError);
    expect(error).toMatchObject({ status: 502, body: html });
  });

  it("resolves to undefined for a 204 response without a body", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(null, { status: 204 })));

    await expect(requestJson("/api/health")).resolves.toBeUndefined();
  });

  it("sends the XSRF-TOKEN cookie as the CSRF header on writes and not on reads", async () => {
    document.cookie = "XSRF-TOKEN=csrf-token-42";
    const calls = recordingFetch(() => new Response(null, { status: 204 }));

    await requestJson("/api/projects", { method: "POST", body: JSON.stringify({ name: "a" }) });
    await requestJson("/api/projects");

    expect(new Headers(calls[0].init.headers).get("X-XSRF-TOKEN")).toBe("csrf-token-42");
    expect(new Headers(calls[1].init.headers).has("X-XSRF-TOKEN")).toBe(false);
  });

  it("notifies the single unauthorized handler for a 401 and still throws", async () => {
    const onUnauthorized = vi.fn();
    setUnauthorizedHandler(onUnauthorized);
    recordingFetch(
      () =>
        new Response(JSON.stringify({ code: "UNAUTHORIZED", message: "未登录", traceId: "t" }), {
          status: 401,
          headers: { "Content-Type": "application/json" },
        }),
    );

    const error = await requestJson("/api/projects").catch((reason: unknown) => reason);

    expect(onUnauthorized).toHaveBeenCalledTimes(1);
    expect(error).toBeInstanceOf(HttpError);
    expect(error).toMatchObject({ status: 401 });
  });
});
