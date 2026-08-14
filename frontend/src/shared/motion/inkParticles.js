// 墨粒模型(纯逻辑,无 Canvas/DOM;渲染绑定见 InkParticleField.vue)。
// 合同 §5 InkParticleField 冻结预算:30–64 粒、DPR ≤ 1.5、半径 0.7–3.2px、
// 松烟墨为主 + 至多约 1/13–1/15 低透明朱砂;慢漂,不响应业务状态。
// 颜色取自已批准原型:松烟墨 #2F443C(47 68 60),朱砂 = 冻结 --cinnabar
// #9E382B(158 56 43)。

export const PARTICLE_MIN = 30
export const PARTICLE_MAX = 64
export const DPR_MAX = 1.5
export const RADIUS_MIN = 0.7
export const RADIUS_MAX = 3.2
export const CINNABAR_INTERVAL = 15 // 每 15 粒 1 粒朱砂(≤ 合同上限 1/13)

const SOOT_RGB = '47 68 60'
const CINNABAR_RGB = '158 56 43'

/** DPR 钳制:预算上限 1.5 @param {number} ratio */
export function clampDpr(ratio) {
  const value = Number.isFinite(ratio) && ratio > 0 ? ratio : 1
  return Math.min(value, DPR_MAX)
}

/** 按视口宽度取粒子数,钳制在冻结区间 [30, 64] @param {number} width */
export function particleCount(width) {
  const base = Number.isFinite(width) && width > 0 ? Math.round(width / 26) : PARTICLE_MIN
  return Math.max(PARTICLE_MIN, Math.min(PARTICLE_MAX, base))
}

/**
 * @param {object} options
 * @param {number} options.count
 * @param {number} options.width
 * @param {number} options.height
 * @param {() => number} [options.random] 可注入以便测试
 */
export function createParticles({ count, width, height, random = Math.random }) {
  return Array.from({ length: count }, (_, index) => ({
    x: random() * width,
    y: random() * height,
    r: RADIUS_MIN + random() * (RADIUS_MAX - RADIUS_MIN),
    vx: -0.03 + random() * 0.06,
    vy: -0.045 - random() * 0.07,
    alpha: 0.04 + random() * 0.095,
    warm: index % CINNABAR_INTERVAL === 0 && index > 0, // 首粒不取朱砂,保持“至多”语义
  }))
}

/**
 * 推进一帧:缓慢上漂 + 指针极低幅牵引 + 边界回绕。
 * @param {ReturnType<typeof createParticles>} particles
 * @param {{width: number, height: number, pointerX?: number, pointerY?: number}} field
 */
export function stepParticles(particles, { width, height, pointerX = 0, pointerY = 0 }) {
  for (const p of particles) {
    p.x += p.vx + pointerX * 0.015
    p.y += p.vy + pointerY * 0.01
    if (p.y < -8) {
      p.y = height + 8
      p.x = Math.random() * width
    }
    if (p.x < -8) p.x = width + 8
    if (p.x > width + 8) p.x = -8
  }
  return particles
}

/** 单粒填充色(朱砂降透明,保持“低透明点缀”) @param {{warm: boolean, alpha: number}} p */
export function particleColor(p) {
  return p.warm
    ? `rgb(${CINNABAR_RGB} / ${(p.alpha * 0.65).toFixed(3)})`
    : `rgb(${SOOT_RGB} / ${p.alpha.toFixed(3)})`
}
