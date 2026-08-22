export const PARTICLE_MIN = 36;
export const PARTICLE_MAX = 90;
export const DPR_MAX = 2;
export const RADIUS_MIN = 1;
export const RADIUS_MAX = 3.8;

export type ParticleColor = "cyan" | "purple" | "emerald" | "amber";

export interface CyberParticle {
  x: number;
  y: number;
  radius: number;
  velocityX: number;
  velocityY: number;
  alpha: number;
  pulseSpeed: number;
  pulsePhase: number;
  color: ParticleColor;
}

interface ParticleArea {
  width: number;
  height: number;
}

interface CreateParticleOptions extends ParticleArea {
  count: number;
  random?: () => number;
}

interface StepParticleOptions extends ParticleArea {
  pointerX?: number | null;
  pointerY?: number | null;
  random?: () => number;
}

const PARTICLE_COLORS: readonly ParticleColor[] = ["cyan", "purple", "emerald", "amber"];

export function clampDpr(ratio: number): number {
  const value = Number.isFinite(ratio) && ratio > 0 ? ratio : 1;
  return Math.min(value, DPR_MAX);
}

export function particleCount(width: number): number {
  const base = Number.isFinite(width) && width > 0 ? Math.round(width / 22) : PARTICLE_MIN;
  return Math.max(PARTICLE_MIN, Math.min(PARTICLE_MAX, base));
}

export function createParticles(options: CreateParticleOptions): CyberParticle[] {
  const random = options.random ?? Math.random;
  return Array.from({ length: options.count }, (_, index) => ({
    x: random() * options.width,
    y: random() * options.height,
    radius: RADIUS_MIN + random() * (RADIUS_MAX - RADIUS_MIN),
    velocityX: -0.05 + random() * 0.1,
    velocityY: -0.06 - random() * 0.12,
    alpha: 0.15 + random() * 0.45,
    pulseSpeed: 0.02 + random() * 0.04,
    pulsePhase: random() * Math.PI * 2,
    color: PARTICLE_COLORS[index % PARTICLE_COLORS.length] ?? "cyan",
  }));
}

export function stepParticles(
  particles: CyberParticle[],
  options: StepParticleOptions,
): CyberParticle[] {
  const random = options.random ?? Math.random;
  for (const particle of particles) {
    particle.x += particle.velocityX;
    particle.y += particle.velocityY;
    particle.pulsePhase += particle.pulseSpeed;

    if (
      options.pointerX !== null &&
      options.pointerX !== undefined &&
      options.pointerY !== null &&
      options.pointerY !== undefined
    ) {
      const deltaX = options.pointerX - particle.x;
      const deltaY = options.pointerY - particle.y;
      const distance = Math.hypot(deltaX, deltaY);
      if (distance < 180 && distance > 0) {
        const force = (180 - distance) / 180;
        particle.x -= (deltaX / distance) * force * 0.8;
        particle.y -= (deltaY / distance) * force * 0.8;
      }
    }

    if (particle.y < -10) {
      particle.y = options.height + 10;
      particle.x = random() * options.width;
    }
    if (particle.x < -10) particle.x = options.width + 10;
    if (particle.x > options.width + 10) particle.x = -10;
  }
  return particles;
}

export function particleAlpha(particle: CyberParticle): number {
  return Math.max(0.05, particle.alpha * (0.7 + 0.3 * Math.sin(particle.pulsePhase)));
}
