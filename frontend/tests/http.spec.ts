import { HttpError, requestJson } from "../src/lib/http";

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
});
