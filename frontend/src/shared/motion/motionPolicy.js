// 动效偏好状态机(纯逻辑,无 DOM;Vue 绑定见 useMotionPolicy.js)。
// 合同 §7:normal / 用户手动静态(static)/ prefers-reduced-motion /
// coarse pointer / 页面隐藏 / 窗口失焦 —— 任一抑制项生效时,
// 环境动效(视差、漂移、粒子 RAF)必须停止;信息层不受影响。

/** @typedef {'staticMode'|'reduced'|'coarse'|'hidden'|'unfocused'} MotionFlag */

export const MOTION_FLAGS = Object.freeze(['staticMode', 'reduced', 'coarse', 'hidden', 'unfocused'])

/**
 * @param {Partial<Record<MotionFlag, boolean>>} [initial]
 */
export function createMotionPolicy(initial = {}) {
  const state = {
    staticMode: false,
    reduced: false,
    coarse: false,
    hidden: false,
    unfocused: false,
  }
  for (const flag of MOTION_FLAGS) {
    if (flag in initial) state[flag] = !!initial[flag]
  }
  const listeners = new Set()

  function snapshot() {
    return { ...state }
  }

  /** 环境动效是否允许运行(粒子 RAF、指针视差、循环动画) */
  function ambientAllowed() {
    return !state.staticMode && !state.reduced && !state.coarse && !state.hidden && !state.unfocused
  }

  /**
   * 派生模式标签:
   * - static:用户手动关闭(优先级最高,显式意图)
   * - reduced:系统减动效或触屏 coarse pointer(静态构图)
   * - paused:页面隐藏/窗口失焦(内容不变,循环暂停)
   * - normal:全部动效可用
   */
  function mode() {
    if (state.staticMode) return 'static'
    if (state.reduced || state.coarse) return 'reduced'
    if (state.hidden || state.unfocused) return 'paused'
    return 'normal'
  }

  /**
   * @param {MotionFlag} flag
   * @param {boolean} value
   * @returns {ReturnType<typeof snapshot>}
   */
  function set(flag, value) {
    if (!MOTION_FLAGS.includes(flag)) return snapshot()
    const next = !!value
    if (state[flag] === next) return snapshot()
    state[flag] = next
    for (const listener of listeners) listener(snapshot())
    return snapshot()
  }

  function toggleStatic() {
    return set('staticMode', !state.staticMode).staticMode
  }

  /** @param {(s: ReturnType<typeof snapshot>) => void} listener */
  function subscribe(listener) {
    listeners.add(listener)
    return () => listeners.delete(listener)
  }

  return { set, toggleStatic, snapshot, ambientAllowed, mode, subscribe }
}
