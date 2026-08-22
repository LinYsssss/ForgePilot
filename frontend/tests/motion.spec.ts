import { readFile } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

// Resolved from this module, not the process working directory. Vite rewrites
// `new URL("<literal>", import.meta.url)` into an asset URL, so the path is
// derived from `fileURLToPath` instead.
const baseCssPath = join(dirname(fileURLToPath(import.meta.url)), "../src/styles/base.css");
const tokensCssPath = join(dirname(fileURLToPath(import.meta.url)), "../src/styles/tokens.css");

describe("accessibility and motion foundation", () => {
  it("contains a reduced-motion policy that removes non-essential movement", async () => {
    const css = await readFile(baseCssPath, "utf8");
    expect(css).toContain("@media (prefers-reduced-motion: reduce)");
    expect(css).toContain("transition-duration: 0.01ms !important");
    expect(css).toContain("animation-iteration-count: 1 !important");
  });

  it("keeps the reference-informed console on one explicit dark token contract", async () => {
    const tokens = await readFile(tokensCssPath, "utf8");
    expect(tokens).toContain("color-scheme: dark");
    expect(tokens).toContain("--fp-gradient-canvas:");
    expect(tokens).toContain("--fp-color-accent:");
    expect(tokens).not.toContain("[data-theme");
  });
});
