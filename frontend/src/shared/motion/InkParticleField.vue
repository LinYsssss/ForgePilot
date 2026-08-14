<template>
  <canvas ref="canvasEl" class="ink-particle-field" :class="{ 'is-login': variant === 'login' }" aria-hidden="true"></canvas>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { clampDpr, createParticles, particleColor, particleCount, stepParticles } from './inkParticles.js'
import { getPointerState } from './usePointerField.js'
import { useMotionPolicy } from './useMotionPolicy.js'

// 原生 Canvas 墨粒(合同 §5 InkParticleField):环境层,aria-hidden、
// pointer-events:none;30–64 粒、DPR ≤ 1.5;static/reduced/coarse/
// hidden/unfocused 任一生效即停止 RAF,只留一帧静态墨点。
// 不读取业务数据;指针牵引读的是单例指针场的坐标,不另设监听。
defineProps({
  variant: { type: String, default: 'workspace' }, // workspace | login
})

const canvasEl = ref(null)
const { ambientAllowed } = useMotionPolicy()

let context = null
let particles = []
let width = 0
let height = 0
let frame = 0

function resize() {
  const canvas = canvasEl.value
  if (!canvas || !context) return
  const ratio = clampDpr(window.devicePixelRatio)
  width = window.innerWidth
  height = window.innerHeight
  canvas.width = Math.round(width * ratio)
  canvas.height = Math.round(height * ratio)
  canvas.style.width = `${width}px`
  canvas.style.height = `${height}px`
  context.setTransform(ratio, 0, 0, ratio, 0, 0)
  particles = createParticles({ count: particleCount(width), width, height })
  draw(false)
}

function draw(advance) {
  if (!context) return
  if (advance) {
    const pointer = getPointerState()
    stepParticles(particles, { width, height, pointerX: pointer.x, pointerY: pointer.y })
  }
  context.clearRect(0, 0, width, height)
  context.globalCompositeOperation = 'multiply'
  for (const p of particles) {
    context.beginPath()
    context.fillStyle = particleColor(p)
    context.arc(p.x, p.y, p.r, 0, Math.PI * 2)
    context.fill()
  }
}

function loop() {
  frame = 0
  if (!ambientAllowed.value) return draw(false)
  draw(true)
  frame = requestAnimationFrame(loop)
}

function syncLoop() {
  if (frame) cancelAnimationFrame(frame)
  frame = 0
  if (ambientAllowed.value) frame = requestAnimationFrame(loop)
  else draw(false)
}

watch(ambientAllowed, syncLoop)

onMounted(() => {
  context = canvasEl.value?.getContext('2d', { alpha: true }) || null
  window.addEventListener('resize', resize, { passive: true })
  resize()
  syncLoop()
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', resize)
  if (frame) cancelAnimationFrame(frame)
  frame = 0
  context = null
})
</script>

<style scoped>
.ink-particle-field {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  opacity: 0.34;
  mix-blend-mode: multiply;
  pointer-events: none;
}
.ink-particle-field.is-login { opacity: 0.42; }
</style>
