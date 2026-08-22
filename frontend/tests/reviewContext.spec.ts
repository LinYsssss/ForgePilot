import { parseReviewContext } from "../src/features/review/context";

const snapshot = {
  requirement: {
    id: 12,
    revisionId: 30,
    title: "登录闭环",
    background: null,
    description: "让登录状态可验证",
  },
  acceptanceCriteria: [
    { id: 91, acKey: "AC-1", text: "登录成功后进入项目列表" },
  ],
  pullRequest: {
    provider: "GITHUB",
    instance: "github.com",
    repository: "forgepilot/app",
    number: 42,
    baseSha: "base",
    headSha: "head",
    inputFingerprint: "fingerprint",
    title: "feat: login",
  },
  changedFiles: [
    { path: "src/login.ts", changeType: "MODIFIED", patch: "@@ -1 +1 @@\n-old\n+new" },
  ],
  knowledgeEvidence: [
    { sourceId: 1, documentId: 2, chunkId: 3, excerpt: "安全规范", score: 0.91 },
  ],
  truncation: {
    truncated: true,
    files: [{ path: "src/login.ts", patchTruncated: true }],
    notReviewed: ["src/generated.ts"],
  },
};

describe("review context narrowing", () => {
  it("accepts a complete immutable evidence snapshot", () => {
    expect(parseReviewContext(snapshot)).toEqual(snapshot);
  });

  it("keeps a deliberately absent requirement distinct from malformed data", () => {
    expect(parseReviewContext({ ...snapshot, requirement: null })?.requirement).toBeNull();
    expect(parseReviewContext({ ...snapshot, requirement: { id: "12" } })).toBeNull();
  });

  it("rejects malformed nested evidence instead of partially rendering it", () => {
    expect(
      parseReviewContext({
        ...snapshot,
        knowledgeEvidence: [{ ...snapshot.knowledgeEvidence[0], score: "high" }],
      }),
    ).toBeNull();
    expect(
      parseReviewContext({
        ...snapshot,
        truncation: { ...snapshot.truncation, notReviewed: [12] },
      }),
    ).toBeNull();
  });
});
