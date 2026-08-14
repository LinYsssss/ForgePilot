// 指针场(纯逻辑,无 DOM):归一化坐标 + rAF 合并写入。
// 合同 §7 实现规则:壳层仅注册一个 pointer observer;每帧至多写一次
// --ink-pointer-x/y;不触发 Vue 响应式;抑制时归零。
// DOM 绑定见 usePointerField.js;本模块可被 node --test 直接驱动。

/**
 * 把视口坐标归一化到 [-1, 1](中心为 0)。宽高非法时返回 0,避免 NaN 进入 CSS。
 * @param {number} clientX @param {number} clientY
 * @param {number} width @param {number} height
 */
export function normalizePointer(clientX, clientY, width, height) {
  const x = width > 0 ? (clientX / width - 0.5) * 2 : 0
  const y = height > 0 ? (clientY / height - 0.5) * 2 : 0
  return { x: clamp(x), y: clamp(y) }
}

function clamp(value) {
  if (!Number.isFinite(value)) return 0
  return Math.min(1, Math.max(-1, value))
}

/**
 * @param {object} options
 * @param {(x: number, y: number) => void} options.write 每帧最多调用一次的写出口
 * @param {(cb: () => void) => number} [options.schedule] 默认 requestAnimationFrame
 * @param {(id: number) => void} [options.cancel]
 */
export function createPointerField({ write, schedule, cancel }) {
  const scheduleFrame = schedule || ((cb) => requestAnimationFrame(cb))
  const cancelFrame = cancel || ((id) => cancelAnimationFrame(id))
  let frame = 0
  let x = 0
  let y = 0

  function flush() {
    frame = 0
    write(x, y)
  }

  /** 高频调用安全:同一帧内的多次 move 只产生一次 write */
  function move(nextX, nextY) {
    x = clamp(nextX)
    y = clamp(nextY)
    if (!frame) frame = scheduleFrame(flush)
  }

  /** 抑制/离场时归零:取消挂起帧并立即写 (0,0) */
  function reset() {
    if (frame) {
      cancelFrame(frame)
      frame = 0
    }
    x = 0
    y = 0
    write(0, 0)
  }

  function dispose() {
    if (frame) {
      cancelFrame(frame)
      frame = 0
    }
  }

  function current() {
    return { x, y }
  }

  return { move, reset, dispose, current }
}
