import { isFindingLine, parseUnifiedDiff } from "../src/features/review/diff";

describe("unified diff parser", () => {
  it("maps context, additions and deletions across multiple hunks", () => {
    const rows = parseUnifiedDiff(
      [
        "diff --git a/app.ts b/app.ts",
        "@@ -10,3 +10,4 @@",
        " context",
        "-old",
        "+new",
        "+extra",
        "@@ -30 +31 @@",
        "-before",
        "+after",
      ].join("\n"),
    );

    expect(rows.map(({ kind, oldLine, newLine }) => ({ kind, oldLine, newLine }))).toEqual([
      { kind: "meta", oldLine: null, newLine: null },
      { kind: "hunk", oldLine: null, newLine: null },
      { kind: "context", oldLine: 10, newLine: 10 },
      { kind: "deletion", oldLine: 11, newLine: null },
      { kind: "addition", oldLine: null, newLine: 11 },
      { kind: "addition", oldLine: null, newLine: 12 },
      { kind: "hunk", oldLine: null, newLine: null },
      { kind: "deletion", oldLine: 30, newLine: null },
      { kind: "addition", oldLine: null, newLine: 31 },
    ]);
  });

  it("matches findings to new lines and falls back to old lines for deletions", () => {
    const rows = parseUnifiedDiff("@@ -4,2 +4,2 @@\n-old\n+new\n same");
    expect(isFindingLine(rows[1], 4)).toBe(true);
    expect(isFindingLine(rows[2], 4)).toBe(true);
    expect(isFindingLine(rows[3], 5)).toBe(true);
    expect(isFindingLine(rows[2], null)).toBe(false);
  });
});
