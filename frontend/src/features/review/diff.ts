export type DiffLineKind = "meta" | "hunk" | "context" | "addition" | "deletion";

export interface DiffLine {
  key: string;
  kind: DiffLineKind;
  oldLine: number | null;
  newLine: number | null;
  text: string;
}

const HUNK = /^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@/;

/** Converts a provider unified patch into stable display rows and real hunk lines. */
export function parseUnifiedDiff(patch: string): DiffLine[] {
  let oldLine: number | null = null;
  let newLine: number | null = null;

  return patch.split("\n").map((text, index) => {
    const hunk = HUNK.exec(text);
    if (hunk !== null) {
      oldLine = Number(hunk[1]);
      newLine = Number(hunk[2]);
      return { key: `${index}:hunk`, kind: "hunk", oldLine: null, newLine: null, text };
    }
    if (oldLine === null || newLine === null) {
      return { key: `${index}:meta`, kind: "meta", oldLine: null, newLine: null, text };
    }
    if (text.startsWith("+") && !text.startsWith("+++")) {
      const row = {
        key: `${index}:addition:${newLine}`,
        kind: "addition" as const,
        oldLine: null,
        newLine,
        text,
      };
      newLine += 1;
      return row;
    }
    if (text.startsWith("-") && !text.startsWith("---")) {
      const row = {
        key: `${index}:deletion:${oldLine}`,
        kind: "deletion" as const,
        oldLine,
        newLine: null,
        text,
      };
      oldLine += 1;
      return row;
    }
    if (text.startsWith("\\")) {
      return { key: `${index}:meta`, kind: "meta", oldLine: null, newLine: null, text };
    }
    const row = {
      key: `${index}:context:${oldLine}:${newLine}`,
      kind: "context" as const,
      oldLine,
      newLine,
      text,
    };
    oldLine += 1;
    newLine += 1;
    return row;
  });
}

/** A Finding line normally names the new file; deletions have only an old line. */
export function isFindingLine(row: DiffLine, line: number | null): boolean {
  return line !== null && (row.newLine === line || (row.newLine === null && row.oldLine === line));
}
