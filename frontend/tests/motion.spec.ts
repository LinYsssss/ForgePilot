import { readFile } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

// Resolved from this module, not the process working directory. Vite rewrites
// `new URL("<literal>", import.meta.url)` into an asset URL, so the path is
// derived from `fileURLToPath` instead.
const baseCssPath = join(dirname(fileURLToPath(import.meta.url)), "../src/styles/base.css");
const tokensCssPath = join(dirname(fileURLToPath(import.meta.url)), "../src/styles/tokens.css");
const appPath = join(dirname(fileURLToPath(import.meta.url)), "../src/App.vue");
const particleFieldPath = join(
  dirname(fileURLToPath(import.meta.url)),
  "../src/components/motion/CyberParticleField.vue",
);

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

  it("mounts the complete ambient motion stack in the official application", async () => {
    const [app, particles, css] = await Promise.all([
      readFile(appPath, "utf8"),
      readFile(particleFieldPath, "utf8"),
      readFile(baseCssPath, "utf8"),
    ]);
    expect(app).toContain("<CyberParticleField />");
    expect(app).toContain("motion-orb-cyan");
    expect(app).toContain("motion-scanlines");
    expect(particles).toContain("requestAnimationFrame");
    expect(particles).toContain("visibilitychange");
    expect(particles).toContain("prefers-reduced-motion: reduce");
    expect(css).toContain("@keyframes fp-radar-sweep");
    expect(css).toContain("@keyframes fp-laser-scan");
    expect(css).toContain("@keyframes fp-shimmer-wave");
    expect(css).toContain("@keyframes fp-border-flow");
  });
});
