import {
  DPR_MAX,
  PARTICLE_MAX,
  PARTICLE_MIN,
  clampDpr,
  createParticles,
  particleAlpha,
  particleCount,
  stepParticles,
} from "../src/components/motion/cyberParticles";

describe("cyber particle engine", () => {
  it("bounds device pixel ratio and responsive particle work", () => {
    expect(clampDpr(Number.NaN)).toBe(1);
    expect(clampDpr(8)).toBe(DPR_MAX);
    expect(particleCount(320)).toBe(PARTICLE_MIN);
    expect(particleCount(10_000)).toBe(PARTICLE_MAX);
  });

  it("creates deterministic particles across every neon color", () => {
    const particles = createParticles({ count: 4, width: 100, height: 50, random: () => 0.5 });
    expect(particles.map((particle) => particle.color)).toEqual([
      "cyan",
      "purple",
      "emerald",
      "amber",
    ]);
    expect(particles[0]).toMatchObject({ x: 50, y: 25, velocityX: 0, velocityY: -0.12 });
  });

  it("moves, pulses, repels, and wraps particles without replacing the array", () => {
    const particles = createParticles({ count: 1, width: 100, height: 50, random: () => 0.5 });
    const particle = particles[0];
    expect(particle).toBeDefined();
    if (particle === undefined) return;
    const initialX = particle.x;
    const initialPhase = particle.pulsePhase;
    const result = stepParticles(particles, {
      width: 100,
      height: 50,
      pointerX: 55,
      pointerY: 25,
      random: () => 0.25,
    });
    expect(result).toBe(particles);
    expect(particle.x).toBeLessThan(initialX);
    expect(particle.pulsePhase).toBeGreaterThan(initialPhase);
    expect(particleAlpha(particle)).toBeGreaterThanOrEqual(0.05);

    particle.y = -11;
    stepParticles(particles, { width: 100, height: 50, random: () => 0.25 });
    expect(particle.y).toBe(60);
    expect(particle.x).toBe(25);
  });
});
