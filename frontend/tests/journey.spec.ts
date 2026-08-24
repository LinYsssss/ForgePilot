import { flushPromises, mount, type VueWrapper } from "@vue/test-utils";
import { createMemoryHistory } from "vue-router";

import App from "../src/App.vue";
import { createAppRouter } from "../src/app/router";
import { bootstrapSession, clearSession } from "../src/features/auth/session";

/**
 * One three-role journey through the real `App`, the real router and a stubbed
 * `fetch`, asserting both the rendered DOM and the requests that produced it.
 *
 * WHAT THIS IS NOT: it is not a browser acceptance run. jsdom loads no CSS under
 * this vitest config, `getBoundingClientRect()` is always zero and there is no
 * `matchMedia`, so responsive layout, contrast, focus visibility, tab order and
 * visual drift are all untestable here and none of them is claimed below. What
 * this does cover is the route guards, the request contract, the per-role action
 * surface, and the DOM-structure rules of PRD.md 5 that forbid merging a
 * finding's status, its lineage, the model's confidence and the Review's
 * Decision into one label. `frontend/MANUAL-ACCEPTANCE.md` carries the part a
 * person still has to do by eye.
 */

interface RecordedCall {
  path: string;
  method: string;
  body: string | null;
}

interface MemberRow {
  userId: number;
  username: string;
  displayName: string;
  roles: ("LEADER" | "DEVELOPER" | "REVIEWER")[];
}

interface FindingRow {
  id: number;
  findingType: "REQUIREMENT" | "CODE_QUALITY";
  path: string | null;
  line: number | null;
  evidence: string | null;
  status: string;
  continuity: "NEW" | "PERSISTING" | "SUPPRESSED";
  requirementId: number | null;
  requirementRevisionId: number | null;
  acId: number | null;
  acKey: string | null;
  assigneeId: number | null;
  carriedFromFindingId: number | null;
  findingKey: string;
  evidenceHash: string | null;
  basisHash: string | null;
}

interface ReviewRow {
  id: number;
  pullRequestId: number;
  headSha: string;
  reviewInputFingerprint: string;
  requirementId: number | null;
  requirementRevisionId: number | null;
  status: "PENDING" | "RUNNING" | "COMPLETED" | "FAILED";
  decision: "PENDING" | "APPROVE" | "REQUEST_CHANGES";
  decisionBy: number | null;
  decisionAt: string | null;
  decisionComment: string | null;
  createdAt: string;
  findings: FindingRow[];
}

interface EventRow {
  id: number;
  actorId: number;
  action: string;
  fromStatus: string;
  toStatus: string;
  comment: string | null;
  createdAt: string;
}

const ACCOUNTS = [
  { id: 1, username: "lead", displayName: "负责人", role: "LEADER" as const },
  { id: 2, username: "dev", displayName: "开发者", role: "DEVELOPER" as const },
  { id: 3, username: "rev", displayName: "评审者", role: "REVIEWER" as const },
];

const HEAD_ONE = "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678";
const HEAD_TWO = "9988776655443322110099887766554433221100";

/** The whole server, small enough to read and stateful enough to be a journey. */
class FakeServer {
  session: { id: number; username: string; displayName: string } | null = null;
  projectId: number | null = null;
  projectName = "";
  members: MemberRow[] = [];
  requirementId: number | null = null;
  requirementTitle = "";
  requirementStatus = "DRAFT";
  requirementAssigneeId: number | null = null;
  revisionId = 30;
  criteria: { id: number; acKey: string; sortOrder: number; text: string }[] = [];
  pullRequestHead = HEAD_ONE;
  pullRequestFingerprint = "fingerprint-one";
  reviews: ReviewRow[] = [];
  events: EventRow[] = [];
  nextEventId = 1;

  /** Happens outside ForgePilot: a push moves the head and the engine files a new Review. */
  pushNewHeadAndReview(): void {
    this.pullRequestHead = HEAD_TWO;
    this.pullRequestFingerprint = "fingerprint-two";
    this.reviews.push({
      id: 502,
      pullRequestId: 7,
      headSha: HEAD_TWO,
      reviewInputFingerprint: "fingerprint-two",
      requirementId: this.requirementId,
      requirementRevisionId: this.revisionId,
      status: "COMPLETED",
      decision: "PENDING",
      decisionBy: null,
      decisionAt: null,
      decisionComment: null,
      createdAt: "2026-08-21T09:00:00Z",
      findings: [],
    });
  }

  seedFirstReview(): void {
    this.reviews.push({
      id: 501,
      pullRequestId: 7,
      headSha: HEAD_ONE,
      reviewInputFingerprint: "fingerprint-one",
      requirementId: this.requirementId,
      requirementRevisionId: this.revisionId,
      status: "COMPLETED",
      decision: "PENDING",
      decisionBy: null,
      decisionAt: null,
      decisionComment: null,
      createdAt: "2026-08-21T06:00:00Z",
      findings: [
        {
          id: 900,
          findingType: "REQUIREMENT",
          path: "src/features/auth/LoginPage.vue",
          line: 42,
          evidence: "口令错误与用户不存在返回了不同的文案。",
          status: "OPEN",
          continuity: "NEW",
          requirementId: this.requirementId,
          requirementRevisionId: this.revisionId,
          acId: 91,
          acKey: "AC-1",
          assigneeId: null,
          carriedFromFindingId: null,
          findingKey: "req-12-AC-1",
          evidenceHash: "evidence-hash-1",
          basisHash: "basis-hash-1",
        },
        {
          id: 901,
          findingType: "CODE_QUALITY",
          path: "src/lib/http.ts",
          line: null,
          evidence: "重试逻辑吞掉了 HTTP 错误。",
          status: "REJECTED",
          continuity: "SUPPRESSED",
          requirementId: null,
          requirementRevisionId: null,
          acId: null,
          acKey: null,
          assigneeId: null,
          carriedFromFindingId: 700,
          findingKey: "quality-http-retry",
          evidenceHash: "evidence-hash-2",
          basisHash: "basis-hash-2",
        },
        // 902 and 903 exist so that "a LEADER sees no claim and no mark-fixed
        // button" is a statement about the role rather than about the state: one
        // finding sits in CONFIRMED and one in IN_PROGRESS the whole time, so a
        // DEVELOPER on the same page does see both buttons.
        {
          id: 902,
          findingType: "CODE_QUALITY",
          path: "src/lib/datetime.ts",
          line: 7,
          evidence: "时区没有固定，跨时区渲染不一致。",
          status: "CONFIRMED",
          continuity: "PERSISTING",
          requirementId: null,
          requirementRevisionId: null,
          acId: null,
          acKey: null,
          assigneeId: null,
          carriedFromFindingId: 701,
          findingKey: "quality-datetime-zone",
          evidenceHash: "evidence-hash-3",
          basisHash: "basis-hash-3",
        },
        {
          id: 903,
          findingType: "CODE_QUALITY",
          path: "src/app/router.ts",
          line: 16,
          evidence: "滚动行为在无窗口环境下没有兜底。",
          status: "IN_PROGRESS",
          continuity: "PERSISTING",
          requirementId: null,
          requirementRevisionId: null,
          acId: null,
          acKey: null,
          assigneeId: 2,
          carriedFromFindingId: 702,
          findingKey: "quality-router-scroll",
          evidenceHash: "evidence-hash-4",
          basisHash: "basis-hash-4",
        },
      ],
    });
  }

  role(): "LEADER" | "DEVELOPER" | "REVIEWER" | null {
    const current = this.session;
    if (current === null) {
      return null;
    }
    return this.members.find((member) => member.userId === current.id)?.roles[0] ?? null;
  }

  findFinding(findingId: number): FindingRow | null {
    for (const review of this.reviews) {
      const match = review.findings.find((finding) => finding.id === findingId);
      if (match !== undefined) {
        return match;
      }
    }
    return null;
  }

  reviewOf(findingId: number): ReviewRow | null {
    return (
      this.reviews.find((review) =>
        review.findings.some((finding) => finding.id === findingId),
      ) ?? null
    );
  }

  isCurrent(review: ReviewRow): boolean {
    return (
      review.headSha === this.pullRequestHead &&
      review.reviewInputFingerprint === this.pullRequestFingerprint &&
      review.requirementRevisionId === this.revisionId
    );
  }
}

const MOVES: Record<string, Record<string, { action: string; roles: string[] }>> = {
  OPEN: {
    CONFIRMED: { action: "CONFIRM", roles: ["LEADER", "REVIEWER"] },
    REJECTED: { action: "REJECT", roles: ["LEADER", "REVIEWER"] },
  },
  CONFIRMED: {
    IN_PROGRESS: { action: "CLAIM", roles: ["DEVELOPER"] },
    REJECTED: { action: "REJECT", roles: ["LEADER", "REVIEWER"] },
  },
  IN_PROGRESS: { FIXED: { action: "MARK_FIXED", roles: ["DEVELOPER"] } },
  FIXED: {
    VERIFIED: { action: "VERIFY", roles: ["LEADER", "REVIEWER"] },
    IN_PROGRESS: { action: "SEND_BACK", roles: ["LEADER", "REVIEWER"] },
  },
  VERIFIED: { CLOSED: { action: "CLOSE", roles: ["LEADER", "REVIEWER"] } },
  REJECTED: { OPEN: { action: "REOPEN", roles: ["LEADER", "REVIEWER"] } },
  CLOSED: {},
};

let server = new FakeServer();
const calls: RecordedCall[] = [];

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function failure(status: number, code: string, message: string): Response {
  return json({ code, message, traceId: "trace" }, status);
}

function reviewSummary(review: ReviewRow): unknown {
  return {
    id: review.id,
    headSha: review.headSha,
    requirementRevisionId: review.requirementRevisionId,
    status: review.status,
    decision: review.decision,
    isCurrent: server.isCurrent(review),
    createdAt: review.createdAt,
  };
}

function reviewDetail(review: ReviewRow): unknown {
  return {
    id: review.id,
    pullRequestId: review.pullRequestId,
    headSha: review.headSha,
    reviewInputFingerprint: review.reviewInputFingerprint,
    requirementId: review.requirementId,
    requirementRevisionId: review.requirementRevisionId,
    status: review.status,
    decision: review.decision,
    decisionBy: review.decisionBy,
    decisionAt: review.decisionAt,
    decisionComment: review.decisionComment,
    isCurrent: server.isCurrent(review),
    contextSnapshot: {
      requirement:
        server.requirementId === null
          ? null
          : {
              id: server.requirementId,
              revisionId: server.revisionId,
              title: server.requirementTitle,
              background: null,
              description: "完成登录态闭环",
            },
      acceptanceCriteria: server.criteria.map((criterion) => ({
        id: criterion.id,
        acKey: criterion.acKey,
        text: criterion.text,
      })),
      pullRequest: {
        provider: "GITHUB",
        instance: "github.com",
        repository: "forgepilot/app",
        number: 42,
        baseSha: "0000000000000000000000000000000000000000",
        headSha: review.headSha,
        inputFingerprint: review.reviewInputFingerprint,
        title: "feat: 登录闭环",
      },
      changedFiles: [
        {
          path: "src/features/auth/LoginPage.vue",
          changeType: "MODIFIED",
          patch: "@@ -40,3 +40,4 @@\n context\n-old message\n+same safe message\n+route to projects",
        },
        {
          path: "src/lib/http.ts",
          changeType: "MODIFIED",
          patch: "@@ -1 +1 @@\n-old\n+new",
        },
      ],
      knowledgeEvidence: [
        { sourceId: 1, documentId: 2, chunkId: 3, excerpt: "认证错误不得泄露账户是否存在。", score: 0.93 },
      ],
      truncation: {
        truncated: true,
        files: [{ path: "src/features/auth/LoginPage.vue", patchTruncated: false }],
        notReviewed: ["src/generated/huge-bundle.ts"],
      },
    },
    coverage: {
      truncated: true,
      files: [{ path: "src/features/auth/LoginPage.vue", patchTruncated: false }],
      notReviewed: ["src/generated/huge-bundle.ts"],
    },
    acVerdicts: [{ acId: 91, acKey: "AC-1", verdict: "AT_RISK" }],
    findings: review.findings,
    engine: "forgepilot-review",
    promptVersion: "v1",
    model: "test-model",
    executionAttempt: 1,
  };
}

function revisionView(): unknown {
  return {
    id: server.revisionId,
    seq: 1,
    title: server.requirementTitle,
    background: null,
    description: null,
    createdBy: 1,
    createdByUsername: "lead",
    changeReason: null,
    createdAt: "2026-08-21T02:10:00Z",
    acceptanceCriteria: server.criteria,
  };
}

function requirementDetail(): unknown {
  return {
    id: server.requirementId,
    status: server.requirementStatus,
    assigneeId: server.requirementAssigneeId,
    assigneeUsername:
      server.members.find((member) => member.userId === server.requirementAssigneeId)
        ?.username ?? null,
    createdAt: "2026-08-21T02:10:00Z",
    updatedAt: "2026-08-21T02:10:00Z",
    currentRevision: revisionView(),
  };
}

function handleAuth(path: string, method: string, body: string | null): Response | null {
  if (path === "/api/auth/me") {
    return server.session === null
      ? failure(401, "UNAUTHORIZED", "未登录")
      : json(server.session);
  }
  if (path === "/api/auth/login" && method === "POST") {
    const username = new URLSearchParams(body ?? "").get("username") ?? "";
    const account = ACCOUNTS.find((candidate) => candidate.username === username);
    if (account === undefined) {
      return failure(401, "UNAUTHORIZED", "用户名或口令不正确");
    }
    server.session = { id: account.id, username: account.username, displayName: account.displayName };
    return json(server.session);
  }
  if (path === "/api/auth/logout" && method === "POST") {
    server.session = null;
    return json({});
  }
  if (path === "/api/auth/password" && method === "POST") {
    return json({});
  }
  return null;
}

function handleProject(path: string, method: string, body: string | null): Response | null {
  if (path === "/api/projects" && method === "GET") {
    return json(server.projectId === null ? [] : [projectView()]);
  }
  if (path === "/api/projects" && method === "POST") {
    const payload = JSON.parse(body ?? "{}") as { name: string };
    server.projectId = 3;
    server.projectName = payload.name;
    const account = server.session;
    if (account !== null) {
      server.members = [
        {
          userId: account.id,
          username: account.username,
          displayName: account.displayName,
          roles: ["LEADER"],
        },
      ];
    }
    return json(projectView(), 201);
  }
  if (path === "/api/projects/3" && method === "GET") {
    return json(projectView());
  }
  if (path === "/api/projects/3/scm/repositories" && method === "GET") {
    return json([]);
  }
  if (path === "/api/projects/3/members" && method === "GET") {
    return json(server.members);
  }
  if (path.startsWith("/api/projects/3/members/candidates?") && method === "GET") {
    const query = new URL(path, "http://forgepilot.test").searchParams.get("q") ?? "";
    return json(ACCOUNTS.filter((account) =>
      account.username.includes(query) || account.displayName.includes(query),
    ).map((account) => ({
      userId: account.id,
      username: account.username,
      displayName: account.displayName,
      enabled: true,
      alreadyMember: server.members.some((member) => member.userId === account.id),
    })));
  }
  if (path === "/api/projects/3/members/batch" && method === "POST") {
    const payload = JSON.parse(body ?? "{}") as {
      members: Array<{
        userId: number;
        roles: ("LEADER" | "DEVELOPER" | "REVIEWER")[];
      }>;
    };
    const added: MemberRow[] = [];
    for (const input of payload.members) {
      const account = ACCOUNTS.find((candidate) => candidate.id === input.userId);
      if (account === undefined) return failure(404, "NOT_FOUND", "用户不存在");
      added.push({
        userId: account.id,
        username: account.username,
        displayName: account.displayName,
        roles: input.roles,
      });
    }
    server.members = [...server.members, ...added];
    return json(added, 201);
  }
  if (path === "/api/projects/3/scm/bindings" && method === "GET") {
    return json([]);
  }
  if (path === "/api/projects/3/scm/binding-options" && method === "GET") {
    return json([]);
  }
  return null;
}

function projectView(): unknown {
  return {
    id: server.projectId,
    name: server.projectName,
    status: "ACTIVE",
    createdAt: "2026-08-21T02:00:00Z",
    myRoles: [server.role() ?? "DEVELOPER"],
  };
}

function handleRequirement(
  path: string,
  method: string,
  body: string | null,
): Response | null {
  if (path === "/api/projects/3/requirements" && method === "GET") {
    return json(
      server.requirementId === null
        ? []
        : [
            {
              id: server.requirementId,
              title: server.requirementTitle,
              status: server.requirementStatus,
              assigneeId: server.requirementAssigneeId,
              assigneeUsername: null,
              currentRevisionSeq: 1,
              updatedAt: "2026-08-21T02:10:00Z",
            },
          ],
    );
  }
  if (path === "/api/projects/3/requirements" && method === "POST") {
    const payload = JSON.parse(body ?? "{}") as {
      title: string;
      acceptanceCriteria: { text: string }[];
    };
    server.requirementId = 12;
    server.requirementTitle = payload.title;
    server.criteria = payload.acceptanceCriteria.map((criterion, index) => ({
      id: 91 + index,
      acKey: `AC-${index + 1}`,
      sortOrder: index + 1,
      text: criterion.text,
    }));
    server.seedFirstReview();
    return json(requirementDetail(), 201);
  }
  if (path === "/api/projects/3/requirements/12" && method === "GET") {
    return json(requirementDetail());
  }
  if (path === "/api/projects/3/requirements/12/revisions" && method === "GET") {
    return json([revisionView()]);
  }
  if (path === "/api/projects/3/requirements/12/review-activity" && method === "GET") {
    return json({
      activity: server.reviews.length === 0 ? "NO_PR" : "REVIEWING",
      counts: {
        REVIEW_REQUIRED: 0,
        FAILED: 0,
        CHANGES_REQUESTED: 0,
        REVIEWING: server.reviews.length,
        PENDING: 0,
        APPROVED: 0,
      },
    });
  }
  if (path === "/api/projects/3/requirements/12/attachments" && method === "GET") {
    return json([]);
  }
  if (path === "/api/projects/3/requirements/12/quality" && method === "POST") {
    return json({ requirementId: 12, revisionId: server.revisionId, revisionSeq: 1, qualityVersion: "v1", checkedAt: "2026-08-21T04:00:00Z", rules: [], ai: null });
  }
  if (path === "/api/projects/3/requirements/12/guidance" && method === "POST") {
    return json({ requirementId: 12, revisionId: server.revisionId, revisionSeq: 1, checklist: ["先统一错误语义"], rules: [], risks: ["补路由测试"], knowledgeSources: [] });
  }
  if (path === "/api/projects/3/requirements/12/status" && method === "POST") {
    const payload = JSON.parse(body ?? "{}") as { status: string };
    server.requirementStatus = payload.status;
    return json(requirementDetail());
  }
  if (path === "/api/projects/3/requirements/12/assignee" && method === "POST") {
    const payload = JSON.parse(body ?? "{}") as { userId: number };
    const first = server.requirementAssigneeId === null;
    server.requirementAssigneeId = payload.userId;
    if (first) {
      server.requirementStatus = "IN_DEVELOPMENT";
    }
    return json(requirementDetail());
  }
  return null;
}

function handleReview(path: string, method: string, body: string | null): Response | null {
  if (path === "/api/projects/3/review-activity" && method === "GET") {
    return json({
      "12": {
        activity: "REVIEWING",
        counts: {
          REVIEW_REQUIRED: 0,
          FAILED: 0,
          CHANGES_REQUESTED: 0,
          REVIEWING: 1,
          PENDING: 0,
          APPROVED: 0,
        },
      },
    });
  }
  if (path === "/api/projects/3/reviews" && method === "GET") {
    return json(
      [...server.reviews].reverse().map((review) => ({
        id: review.id,
        pullRequestId: review.pullRequestId,
        pullRequestNumber: 42,
        headSha: review.headSha,
        requirementId: review.requirementId,
        status: review.status,
        decision: review.decision,
        isCurrent: server.isCurrent(review),
        createdAt: review.createdAt,
      })),
    );
  }
  if (path === "/api/projects/3/pull-requests/7" && method === "GET") {
    return json({
      id: 7,
      projectId: 3,
      repositoryId: 5,
      externalNumber: 42,
      baseSha: "0000000000000000000000000000000000000000",
      headSha: server.pullRequestHead,
      reviewInputFingerprint: server.pullRequestFingerprint,
      requirementId: server.requirementId,
      authorExternalUserId: "gh-2",
      authorUsername: "dev",
      authorUserId: 2,
      canEditRequirementAssociation: true,
      sourceUpdatedAt: "2026-08-21T05:00:00Z",
      updatedAt: "2026-08-21T05:00:00Z",
    });
  }
  if (path === "/api/projects/3/pull-requests/7/reviews" && method === "GET") {
    return json(server.reviews.map(reviewSummary));
  }
  const detailMatch = /^\/api\/projects\/3\/reviews\/(\d+)$/.exec(path);
  if (detailMatch !== null && method === "GET") {
    const review = server.reviews.find(
      (candidate) => candidate.id === Number(detailMatch[1]),
    );
    return review === undefined
      ? failure(404, "NOT_FOUND", "审查不存在")
      : json(reviewDetail(review));
  }
  const decisionMatch = /^\/api\/projects\/3\/reviews\/(\d+)\/decision$/.exec(path);
  if (decisionMatch !== null && method === "POST") {
    return decide(Number(decisionMatch[1]), body);
  }
  return null;
}

function decide(reviewId: number, body: string | null): Response {
  const role = server.role();
  if (role !== "LEADER" && role !== "REVIEWER") {
    return failure(403, "FORBIDDEN", "只有负责人与评审可以做终局决定");
  }
  const review = server.reviews.find((candidate) => candidate.id === reviewId);
  if (review === undefined) {
    return failure(404, "NOT_FOUND", "审查不存在");
  }
  const payload = JSON.parse(body ?? "{}") as { decision: string; comment?: string };
  if (review.status !== "COMPLETED" || review.decision !== "PENDING") {
    return failure(409, "CONFLICT", "这条 Review 不能再被决定");
  }
  if (!server.isCurrent(review)) {
    return failure(409, "CONFLICT", "这条 Review 审的不是当前输入");
  }
  const blocked = server.reviews.some(
    (candidate) =>
      candidate.headSha === server.pullRequestHead &&
      candidate.decision === "REQUEST_CHANGES",
  );
  if (blocked) {
    return failure(409, "CONFLICT", "该 head 上已经存在退回");
  }
  review.decision = payload.decision === "APPROVE" ? "APPROVE" : "REQUEST_CHANGES";
  review.decisionBy = server.session?.id ?? null;
  review.decisionAt = "2026-08-21T08:00:00Z";
  review.decisionComment = payload.comment ?? null;
  return json({
    decision: review.decision,
    decisionBy: review.decisionBy,
    decisionAt: review.decisionAt,
  });
}

function handleFinding(path: string, method: string, body: string | null): Response | null {
  const statusMatch = /^\/api\/projects\/3\/findings\/(\d+)\/status$/.exec(path);
  if (statusMatch !== null && method === "POST") {
    return moveFinding(Number(statusMatch[1]), body);
  }
  const eventsMatch = /^\/api\/projects\/3\/findings\/(\d+)\/events$/.exec(path);
  if (eventsMatch !== null && method === "GET") {
    return json(server.events);
  }
  return null;
}

function moveFinding(findingId: number, body: string | null): Response {
  const finding = server.findFinding(findingId);
  if (finding === null) {
    return failure(404, "NOT_FOUND", "Finding 不存在");
  }
  const payload = JSON.parse(body ?? "{}") as { status: string; comment?: string };
  const move = MOVES[finding.status][payload.status];
  if (move === undefined) {
    return failure(409, "CONFLICT", "非法的状态流转");
  }
  const role = server.role();
  if (role === null || !move.roles.includes(role)) {
    return failure(403, "FORBIDDEN", "当前角色不能执行这一步");
  }
  if (move.action === "REOPEN" && finding.continuity !== "SUPPRESSED") {
    return failure(409, "CONFLICT", "只有被抑制的继承驳回项可以重开");
  }
  const from = finding.status;
  finding.status = payload.status;
  if (move.action === "CLAIM") {
    finding.assigneeId = server.session?.id ?? null;
  }
  server.events.push({
    id: server.nextEventId,
    actorId: server.session?.id ?? 0,
    action: move.action,
    fromStatus: from,
    toStatus: payload.status,
    comment: payload.comment ?? null,
    createdAt: "2026-08-21T07:00:00Z",
  });
  server.nextEventId += 1;
  return json({ status: finding.status });
}

function handle(path: string, method: string, body: string | null): Response {
  const response =
    handleAuth(path, method, body) ??
    handleProject(path, method, body) ??
    handleRequirement(path, method, body) ??
    handleReview(path, method, body) ??
    handleFinding(path, method, body);
  if (response === null) {
    throw new Error(`unexpected request: ${method} ${path}`);
  }
  return response;
}

function lastCall(method: string, pathSuffix: string): RecordedCall | undefined {
  return [...calls]
    .reverse()
    .find((call) => call.method === method && call.path.endsWith(pathSuffix));
}

async function signInAs(wrapper: VueWrapper, username: string): Promise<void> {
  await wrapper.find("#login-username").setValue(username);
  await wrapper.find("#login-password").setValue("correct horse battery");
  await wrapper.find("form.login-form").trigger("submit");
  await flushPromises();
}

async function signOut(wrapper: VueWrapper): Promise<void> {
  await wrapper.find(".session-area > button").trigger("click");
  await flushPromises();
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("three-role journey through the real App and router", () => {
  // jsdom mounts, routes and re-renders this whole journey in one test; the
  // default 5s budget is spent on that, not on anything waiting.
  it("walks requirement to PR to finding to send-back to fix to approve to DONE", async () => {
    server = new FakeServer();
    calls.length = 0;
    clearSession();

    vi.stubGlobal(
      "fetch",
      vi.fn((input: string | URL | Request, init?: RequestInit) => {
        const path = String(input);
        const method = (init?.method ?? "GET").toUpperCase();
        const body = typeof init?.body === "string" ? init.body : null;
        calls.push({ path, method, body });
        return Promise.resolve(handle(path, method, body));
      }),
    );

    // 1. Cold start with no session: the guard sends a product path to the login screen.
    await bootstrapSession();
    const router = createAppRouter(createMemoryHistory());
    await router.push("/reviews");
    const wrapper = mount(App, { global: { plugins: [router] } });
    await flushPromises();

    expect(router.currentRoute.value.path).toBe("/login");
    expect(wrapper.find("main h1").text()).toBe("登录");

    // 2. LEADER signs in.
    await signInAs(wrapper, "lead");
    expect(lastCall("POST", "/api/auth/login")?.body).toBe(
      "username=lead&password=correct+horse+battery",
    );
    expect(router.currentRoute.value.path).toBe("/workspace");
    expect(wrapper.find("main h1").text()).toBe("工作台");

    // 3. Creates the project.
    await router.push("/projects");
    await flushPromises();
    await wrapper.find("#new-project-name").setValue("ForgePilot");
    await wrapper.find("form.inline-form").trigger("submit");
    await flushPromises();
    expect(lastCall("POST", "/api/projects")?.body).toBe('{"name":"ForgePilot"}');
    expect(wrapper.text()).toContain("ForgePilot");

    // 4. Searches the directory and adds the developer and reviewer with roles.
    await router.push("/projects/3/members");
    await flushPromises();
    for (const [username, role] of [
      ["dev", "DEVELOPER"],
      ["rev", "REVIEWER"],
    ]) {
      await wrapper.find("#candidate-query").setValue(username);
      await wrapper.find("form.inline-form").trigger("submit");
      await flushPromises();
      await wrapper.find(".candidate-choice input").setValue(true);
      if (role === "REVIEWER") {
        const roleInputs = wrapper.findAll(".role-picker input");
        await roleInputs[0].setValue(false);
        await roleInputs[1].setValue(true);
      }
      const addButton = wrapper.findAll("button")
        .find((button) => button.text().startsWith("一次添加"));
      await addButton?.trigger("click");
      await flushPromises();
      expect(lastCall("POST", "/api/projects/3/members/batch")?.body).toBe(
        JSON.stringify({ members: [{ userId: role === "DEVELOPER" ? 2 : 3, roles: [role] }] }),
      );
    }
    expect(wrapper.text()).toContain("dev");
    expect(wrapper.text()).toContain("rev");

    // 5. Writes the requirement with two acceptance criteria.
    await router.push("/requirements?project=3");
    await flushPromises();
    await wrapper.find("#requirement-title").setValue("登录闭环");
    await wrapper.find("#create-ac-0").setValue("登录成功后进入项目列表");
    await wrapper.find(".ac-editor .ac-add").trigger("click");
    await wrapper.find("#create-ac-1").setValue("口令错误与用户不存在返回一致");
    await wrapper.find("form.requirement-form").trigger("submit");
    await flushPromises();

    const created = lastCall("POST", "/api/projects/3/requirements");
    expect(JSON.parse(created?.body ?? "{}")).toMatchObject({
      title: "登录闭环",
      acceptanceCriteria: [
        { text: "登录成功后进入项目列表" },
        { text: "口令错误与用户不存在返回一致" },
      ],
    });
    expect(router.currentRoute.value.path).toBe("/requirements/12");
    expect(wrapper.find(".requirement-status").text()).toBe("草稿");

    // 6. DRAFT to READY, then the first assignment moves it to IN_DEVELOPMENT.
    const readyButton = wrapper
      .findAll("button")
      .find((button) => button.text() === "置为 就绪");
    await readyButton?.trigger("click");
    await flushPromises();
    expect(lastCall("POST", "/api/projects/3/requirements/12/status")?.body).toBe(
      '{"status":"READY"}',
    );
    expect(wrapper.find(".requirement-status").text()).toBe("就绪");

    await wrapper.find("#requirement-assignee").setValue("2");
    await wrapper.find(".requirement-actions form.inline-form").trigger("submit");
    await flushPromises();
    expect(lastCall("POST", "/api/projects/3/requirements/12/assignee")?.body).toBe(
      '{"userId":2}',
    );
    expect(wrapper.find(".requirement-status").text()).toBe("开发中");

    // 7. The review list narrows to the pull request and shows the frozen columns.
    await router.push("/reviews?project=3&pullRequest=7");
    await flushPromises();
    expect(wrapper.find("main h1").text()).toBe("代码审查");
    const row = wrapper.find(".project-review-table tbody tr");
    expect(row.text()).toContain("PR #42");
    expect(row.text()).toContain("a1b2c3d4e5f6");
    expect(row.text()).toContain("已完成");
    expect(row.text()).toContain("尚无终局决定");
    expect(row.text()).toContain("当前有效");
    expect(wrapper.find(".trigger-row .button-primary").exists()).toBe(true);

    await wrapper.find("#review-status-filter").setValue("FAILED");
    expect(wrapper.find(".project-review-table").exists()).toBe(false);
    expect(wrapper.text()).toContain("没有符合当前筛选条件的 Review");
    await wrapper.find("#review-status-filter").setValue("COMPLETED");
    expect(wrapper.find(".project-review-table").exists()).toBe(true);

    // 8. The Review page keeps the four marks apart, and refuses a LEADER the two
    //    developer-only steps of PRD.md 3.
    await router.push("/reviews/501?project=3");
    await flushPromises();
    const firstFinding = wrapper.findAll(".finding")[0];

    expect(firstFinding.find(".finding-status").text()).toBe("待确认");
    expect(firstFinding.find(".finding-continuity").text()).toBe("本轮新增");
    expect(firstFinding.find(".finding-ai-confidence").text()).toBe("未记录");
    expect(firstFinding.find(".review-decision-mark").text()).toBe("尚无终局决定");

    await firstFinding.findAll("button").find((button) => button.text() === "在证据中定位")?.trigger("click");
    expect(wrapper.find(".snapshot-criterion-selected").text()).toContain("AC-1");
    expect(wrapper.find(".diff-file-active").text()).toContain("LoginPage.vue");
    expect(wrapper.find(".diff-line-selected").text()).toContain("route to projects");

    // Four containers, not one: no element carries two of the four marks, and no
    // mark's text has absorbed another's label.
    const markElements = new Set([
      firstFinding.find(".finding-status").element,
      firstFinding.find(".finding-continuity").element,
      firstFinding.find(".finding-ai-confidence").element,
      firstFinding.find(".review-decision-mark").element,
    ]);
    expect(markElements.size).toBe(4);
    for (const pair of [
      ".finding-status.finding-continuity",
      ".finding-status.finding-ai-confidence",
      ".finding-status.review-decision-mark",
      ".finding-continuity.finding-ai-confidence",
      ".finding-continuity.review-decision-mark",
      ".finding-ai-confidence.review-decision-mark",
    ]) {
      expect(wrapper.findAll(pair)).toHaveLength(0);
    }
    expect(firstFinding.find(".finding-status").text()).not.toContain("本轮新增");
    expect(firstFinding.find(".finding-continuity").text()).not.toContain("待确认");
    expect(firstFinding.find(".review-decision-mark").text()).not.toContain("待确认");

    // D002: the unreviewed file is named on the page, not silently dropped.
    expect(wrapper.find(".coverage-not-reviewed").text()).toContain(
      "src/generated/huge-bundle.ts",
    );

    // PRD.md 3: a LEADER may neither claim a finding nor mark one fixed. Finding
    // 902 is CONFIRMED and 903 is IN_PROGRESS right now, so both buttons would
    // exist for a DEVELOPER — step 10 asserts exactly that, which is what makes
    // these two zeros a statement about the role.
    expect(wrapper.findAll('[data-action="CLAIM"]')).toHaveLength(0);
    expect(wrapper.findAll('[data-action="MARK_FIXED"]')).toHaveLength(0);
    expect(wrapper.findAll('[data-action="CONFIRM"]')).toHaveLength(1);
    // Only the separately grouped suppressed rejection offers REOPEN.
    expect(wrapper.findAll('[data-action="REOPEN"]')).toHaveLength(1);
    expect(wrapper.find(".suppressed-findings .finding-continuity").text()).toBe(
      "继承抑制",
    );

    // 9. REVIEWER confirms the finding.
    await signOut(wrapper);
    expect(router.currentRoute.value.path).toBe("/login");
    await signInAs(wrapper, "rev");
    await router.push("/reviews/501?project=3");
    await flushPromises();

    await wrapper.find("#finding-comment-900").setValue("证据与 AC-1 一致");
    await wrapper.find('[data-action="CONFIRM"]').trigger("click");
    await flushPromises();
    expect(lastCall("POST", "/api/projects/3/findings/900/status")?.body).toBe(
      '{"status":"CONFIRMED","comment":"证据与 AC-1 一致"}',
    );
    expect(wrapper.findAll(".finding")[0].find(".finding-status").text()).toBe("已确认");
    await wrapper.findAll(".finding")[0].find(".finding-events-button").trigger("click");
    await flushPromises();
    expect(wrapper.findAll(".finding")[0].find(".finding-events").text()).toContain("操作人 rev");
    expect(wrapper.findAll(".finding")[0].find(".finding-events").text()).toContain("证据与 AC-1 一致");

    // 10. DEVELOPER claims it and marks it fixed.
    await signOut(wrapper);
    await signInAs(wrapper, "dev");
    await router.push("/reviews/501?project=3");
    await flushPromises();

    // The control group for step 8: on the very same rows, a DEVELOPER does get
    // both buttons, and gets none of the reviewer-side ones.
    expect(wrapper.findAll('[data-action="CLAIM"]').length).toBeGreaterThanOrEqual(2);
    expect(wrapper.findAll('[data-action="MARK_FIXED"]').length).toBeGreaterThanOrEqual(1);
    expect(wrapper.findAll('[data-action="CONFIRM"]')).toHaveLength(0);
    expect(wrapper.findAll('[data-action="REJECT"]')).toHaveLength(0);
    expect(wrapper.findAll('[data-action="REOPEN"]')).toHaveLength(0);

    await wrapper.find('[data-action="CLAIM"]').trigger("click");
    await flushPromises();
    expect(lastCall("POST", "/api/projects/3/findings/900/status")?.body).toBe(
      '{"status":"IN_PROGRESS"}',
    );
    expect(wrapper.findAll(".finding")[0].find(".finding-status").text()).toBe("处理中");
    expect(wrapper.findAll(".finding")[0].find(".finding-assignee").text()).toBe("dev");

    await wrapper.find('[data-action="MARK_FIXED"]').trigger("click");
    await flushPromises();
    expect(lastCall("POST", "/api/projects/3/findings/900/status")?.body).toBe(
      '{"status":"FIXED"}',
    );
    expect(wrapper.findAll(".finding")[0].find(".finding-status").text()).toBe("已修复");

    // A DEVELOPER sees no decision buttons at all.
    expect(wrapper.findAll("[data-decision]")).toHaveLength(0);

    // 11. REVIEWER sends the whole Review back, and the head is locked afterwards.
    await signOut(wrapper);
    await signInAs(wrapper, "rev");
    await router.push("/reviews/501?project=3");
    await flushPromises();

    await wrapper.find("#decision-comment").setValue("AC-1 还没有覆盖");
    await wrapper.find('[data-decision="REQUEST_CHANGES"]').trigger("click");
    await flushPromises();
    expect(lastCall("POST", "/api/projects/3/reviews/501/decision")?.body).toBe(
      '{"decision":"REQUEST_CHANGES","comment":"AC-1 还没有覆盖"}',
    );
    expect(wrapper.find(".review-decision").text()).toBe("已退回");
    expect(wrapper.find(".decision-gate").exists()).toBe(true);
    expect(wrapper.find(".decision-gate").text()).toContain("只有新的 head SHA 能解除");

    // 12. A push produces a new Review; the old one stays and goes stale.
    server.pushNewHeadAndReview();
    await router.push("/reviews/502?project=3");
    await flushPromises();

    expect(wrapper.find(".review-current").text()).toBe("当前有效");
    expect(wrapper.find(".decision-gate").exists()).toBe(false);
    await wrapper.find('[data-decision="APPROVE"]').trigger("click");
    await flushPromises();
    expect(lastCall("POST", "/api/projects/3/reviews/502/decision")?.body).toBe(
      '{"decision":"APPROVE"}',
    );
    expect(wrapper.find(".review-decision").text()).toBe("已通过");

    await router.push("/reviews/501?project=3");
    await flushPromises();
    expect(wrapper.find(".review-current").text()).toBe("已过期");
    expect(wrapper.find(".review-stale").exists()).toBe(true);

    // 13. Approving a Review does not move the requirement. A LEADER does that.
    expect(server.requirementStatus).toBe("IN_DEVELOPMENT");
    await signOut(wrapper);
    await signInAs(wrapper, "lead");
    await router.push("/requirements/12?project=3");
    await flushPromises();

    const doneButton = wrapper
      .findAll("button")
      .find((button) => button.text() === "置为 已完成");
    await doneButton?.trigger("click");
    await flushPromises();
    expect(lastCall("POST", "/api/projects/3/requirements/12/status")?.body).toBe(
      '{"status":"DONE"}',
    );
    expect(wrapper.find(".requirement-status").text()).toBe("已完成");
  }, 60000);

  it("keeps the review screens on semantic landmarks and labelled controls", async () => {
    server = new FakeServer();
    server.projectId = 3;
    server.projectName = "ForgePilot";
    server.members = [
      {
        userId: 1,
        username: "lead",
        displayName: "负责人",
        roles: ["LEADER"],
      },
    ];
    server.requirementId = 12;
    server.requirementTitle = "登录闭环";
    server.session = { id: 1, username: "lead", displayName: "负责人" };
    server.seedFirstReview();
    calls.length = 0;
    clearSession();

    vi.stubGlobal(
      "fetch",
      vi.fn((input: string | URL | Request, init?: RequestInit) => {
        const path = String(input);
        const method = (init?.method ?? "GET").toUpperCase();
        const body = typeof init?.body === "string" ? init.body : null;
        calls.push({ path, method, body });
        return Promise.resolve(handle(path, method, body));
      }),
    );

    await bootstrapSession();
    const router = createAppRouter(createMemoryHistory());
    await router.push("/reviews/501?project=3");
    const wrapper = mount(App, { global: { plugins: [router] } });
    await flushPromises();

    // Landmarks and the skip target survive on a product page, not only on the shell.
    expect(wrapper.find("header").exists()).toBe(true);
    expect(wrapper.find('nav[aria-label="主导航"]').exists()).toBe(true);
    expect(wrapper.find("main#app-main").exists()).toBe(true);
    expect(wrapper.findAll(".nav-link")).toHaveLength(6);

    // Every section names itself through the heading it points at.
    const labelledSections = wrapper.findAll("section[aria-labelledby]");
    expect(labelledSections.length).toBeGreaterThanOrEqual(6);
    for (const section of labelledSections) {
      const id = section.attributes("aria-labelledby");
      expect(id).toBeDefined();
      expect(wrapper.find(`#${id}`).exists()).toBe(true);
    }

    // Every control this page renders is programmatically labelled.
    for (const selector of ["#association-requirement", "#association-reason", "#decision-comment"]) {
      expect(wrapper.find(selector).exists()).toBe(true);
      expect(wrapper.find(`label[for="${selector.slice(1)}"]`).exists()).toBe(true);
    }

    // Status is never carried by color alone: each badge has text of its own.
    for (const selector of [".review-status", ".review-decision", ".review-current"]) {
      expect(wrapper.find(selector).text().length).toBeGreaterThan(0);
    }

    // Native controls, not clickable containers.
    expect(wrapper.findAll('div[role="button"]')).toHaveLength(0);
    const actionButtons = wrapper.findAll("[data-action]");
    expect(actionButtons.length).toBeGreaterThan(0);
    expect(actionButtons.every((node) => node.element.tagName === "BUTTON")).toBe(true);

    // Headings descend without skipping a level: h1 on the shell page, h2 per
    // section, h3 inside a section.
    expect(wrapper.findAll("main h1")).toHaveLength(1);
    expect(wrapper.findAll("main h2").length).toBeGreaterThan(0);
  });

  it("keeps repository credentials write-only and requirement advice on the requirement", async () => {
    server = new FakeServer();
    server.projectId = 3;
    server.projectName = "ForgePilot";
    server.members = [
      {
        userId: 1,
        username: "lead",
        displayName: "负责人",
        roles: ["LEADER"],
      },
    ];
    server.requirementId = 12;
    server.requirementTitle = "登录闭环";
    server.session = { id: 1, username: "lead", displayName: "负责人" };
    calls.length = 0;
    clearSession();

    vi.stubGlobal(
      "fetch",
      vi.fn((input: string | URL | Request, init?: RequestInit) => {
        const path = String(input);
        const method = (init?.method ?? "GET").toUpperCase();
        const body = typeof init?.body === "string" ? init.body : null;
        calls.push({ path, method, body });
        return Promise.resolve(handle(path, method, body));
      }),
    );

    await bootstrapSession();
    const router = createAppRouter(createMemoryHistory());
    await router.push("/repositories?project=3");
    const wrapper = mount(App, { global: { plugins: [router] } });
    await flushPromises();

    expect(wrapper.find("main h1").text()).toBe("仓库接入");
    // The credential inputs exist, are password inputs, and start empty.
    for (const selector of ["#repository-token", "#repository-webhook"]) {
      const input = wrapper.find(selector);
      expect(input.attributes("type")).toBe("password");
      expect((input.element as HTMLInputElement).value).toBe("");
      expect(wrapper.find(`label[for="${selector.slice(1)}"]`).exists()).toBe(true);
    }
    expect(wrapper.text()).toContain("页面和读取接口都不会显示 token 或 Webhook 密钥");

    await wrapper.find(".account-menu summary").trigger("click");
    await wrapper.find("#account-current-password").setValue("old-password");
    await wrapper.find("#account-new-password").setValue("new-password");
    await wrapper.find("#account-password-confirmation").setValue("new-password");
    await wrapper.find("form.account-password-form").trigger("submit");
    await flushPromises();
    expect(lastCall("POST", "/api/auth/password")?.body).toBe(
      '{"currentPassword":"old-password","newPassword":"new-password"}',
    );
    expect(wrapper.find("form.account-password-form").text()).toContain("密码已修改");
  });
});
