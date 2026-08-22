<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from "vue";

import {
  clampDpr,
  createParticles,
  particleAlpha,
  particleCount,
  stepParticles,
  type CyberParticle,
  type ParticleColor,
} from "./cyberParticles";

const REDUCED_MOTION_QUERY = "(prefers-reduced-motion: reduce)";
const COLOR_PROPERTIES: Record<ParticleColor, string> = {
  cyan: "--fp-color-particle-cyan",
  purple: "--fp-color-particle-purple",
  emerald: "--fp-color-particle-emerald",
  amber: "--fp-color-particle-amber",
};

const canvasRef = ref<HTMLCanvasElement | null>(null);
let context: CanvasRenderingContext2D | null = null;
let particles: CyberParticle[] = [];
let width = 0;
let height = 0;
let animationFrame: number | null = null;
let pointerX: number | null = null;
let pointerY: number | null = null;
let reducedMotion: MediaQueryList | null = null;
let linkColor = "";
let particleColors: Record<ParticleColor, string> = {
  cyan: "",
  purple: "",
  emerald: "",
  amber: "",
};

function readColors(): void {
  const styles = getComputedStyle(document.documentElement);
  linkColor = styles.getPropertyValue("--fp-color-particle-link").trim();
  particleColors = Object.fromEntries(
    Object.entries(COLOR_PROPERTIES).map(([name, property]) => [
      name,
      styles.getPropertyValue(property).trim(),
    ]),
  ) as Record<ParticleColor, string>;
}

function handlePointerMove(event: PointerEvent): void {
  if (event.pointerType === "touch") return;
  pointerX = event.clientX;
  pointerY = event.clientY;
}

function clearPointer(): void {
  pointerX = null;
  pointerY = null;
}

function resize(): void {
  const canvas = canvasRef.value;
  if (canvas === null || context === null) return;
  const ratio = clampDpr(window.devicePixelRatio);
  width = window.innerWidth;
  height = window.innerHeight;
  canvas.width = Math.round(width * ratio);
  canvas.height = Math.round(height * ratio);
  canvas.style.width = `${width}px`;
  canvas.style.height = `${height}px`;
  context.setTransform(ratio, 0, 0, ratio, 0, 0);
  particles = createParticles({ count: particleCount(width), width, height });
}

function render(): void {
  if (context === null) return;
  stepParticles(particles, { width, height, pointerX, pointerY });
  context.clearRect(0, 0, width, height);

  for (let left = 0; left < particles.length; left += 1) {
    const first = particles[left];
    if (first === undefined) continue;
    for (let right = left + 1; right < particles.length; right += 1) {
      const second = particles[right];
      if (second === undefined) continue;
      const distance = Math.hypot(first.x - second.x, first.y - second.y);
      if (distance >= 110) continue;
      context.beginPath();
      context.globalAlpha = 0.12 * (1 - distance / 110);
      context.strokeStyle = linkColor;
      context.lineWidth = 0.6;
      context.moveTo(first.x, first.y);
      context.lineTo(second.x, second.y);
      context.stroke();
    }
  }

  for (const particle of particles) {
    context.beginPath();
    context.globalAlpha = 0.2;
    context.fillStyle = particleColors[particle.color];
    context.arc(particle.x, particle.y, particle.radius * 2.4, 0, Math.PI * 2);
    context.fill();

    context.beginPath();
    context.globalAlpha = particleAlpha(particle);
    context.arc(particle.x, particle.y, particle.radius, 0, Math.PI * 2);
    context.fill();
  }

  context.globalAlpha = 1;
  animationFrame = requestAnimationFrame(render);
}

function stop(): void {
  if (animationFrame !== null) {
    cancelAnimationFrame(animationFrame);
    animationFrame = null;
  }
  context?.clearRect(0, 0, width, height);
}

function start(): void {
  if (animationFrame !== null || reducedMotion?.matches === true || document.hidden) return;
  animationFrame = requestAnimationFrame(render);
}

function syncMotion(): void {
  if (reducedMotion?.matches === true || document.hidden || !document.hasFocus()) {
    stop();
  } else {
    start();
  }
}

onMounted(() => {
  const canvas = canvasRef.value;
  if (canvas === null) return;
  context = canvas.getContext("2d", { alpha: true });
  if (context === null) return;
  reducedMotion = window.matchMedia(REDUCED_MOTION_QUERY);
  readColors();
  resize();
  window.addEventListener("resize", resize, { passive: true });
  window.addEventListener("pointermove", handlePointerMove, { passive: true });
  window.addEventListener("pointerleave", clearPointer, { passive: true });
  window.addEventListener("focus", syncMotion, { passive: true });
  window.addEventListener("blur", syncMotion, { passive: true });
  document.addEventListener("visibilitychange", syncMotion, { passive: true });
  reducedMotion.addEventListener("change", syncMotion);
  syncMotion();
});

onBeforeUnmount(() => {
  stop();
  window.removeEventListener("resize", resize);
  window.removeEventListener("pointermove", handlePointerMove);
  window.removeEventListener("pointerleave", clearPointer);
  window.removeEventListener("focus", syncMotion);
  window.removeEventListener("blur", syncMotion);
  document.removeEventListener("visibilitychange", syncMotion);
  reducedMotion?.removeEventListener("change", syncMotion);
  context = null;
  reducedMotion = null;
});
</script>

<template>
  <canvas ref="canvasRef" class="cyber-particle-field" aria-hidden="true"></canvas>
</template>
