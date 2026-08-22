import {
  listProjectReviews,
  requestReview,
} from "../src/features/review/api";

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("review API boundary", () => {
  it("loads the project-wide index and requests a selected PR review", async () => {
    const calls: Array<{ path: string; method: string; body: string | null }> = [];
    vi.stubGlobal(
      "fetch",
      vi.fn((input: string | URL | Request, init?: RequestInit) => {
        const path = String(input);
        const method = init?.method ?? "GET";
        calls.push({
          path,
          method,
          body: typeof init?.body === "string" ? init.body : null,
        });
        const body = method === "POST" ? { reviewId: 8, status: "PENDING", executionAttempt: 1 } : [];
        return Promise.resolve(
          new Response(JSON.stringify(body), {
            status: 200,
            headers: { "Content-Type": "application/json" },
          }),
        );
      }),
    );

    await listProjectReviews(3);
    await requestReview(3, 7);

    expect(calls).toEqual([
      { path: "/api/projects/3/reviews", method: "GET", body: null },
      { path: "/api/projects/3/pull-requests/7/reviews", method: "POST", body: "{}" },
    ]);
  });
});
