// 动效偏好(模块级单例 composable,遵循 state-management.md 模式)。
// 状态机本体在 motionPolicy.js(纯逻辑,可测);这里只做 Vue 绑定:
// matchMedia(reduced/coarse)、visibilitychange、window blur/focus 监听,
// 并把派生结果暴露为共享 ref。bind 带重入保护:LoginGate 与 InkShell
// 互斥挂载,即便交替也只存在一份监听(合同 §7:观察器唯一)。
import { ref } from 'vue'
import { createMotionPolicy } from './motionPolicy.js'

const policy = createMotionPolicy()

const staticMode = ref(false)     // 用户手动「静态墨境」
const ambientAllowed = ref(true)  // 环境动效(粒子/视差/漂移)是否可运行
const motionMode = ref('normal')  // normal | static | reduced | paused

policy.subscribe(sync)

function sync() {
  const snap = policy.snapshot()
  staticMode.value = snap.staticMode
  ambientAllowed.value = policy.ambientAllowed()
  motionMode.value = policy.mode()
}

function toggleStaticMode() {
  policy.toggleStatic()
}

let bindCount = 0
let teardown = null

/** 注册系统监听(壳层/登录门禁挂载时调用一次);返回解绑函数。 */
function bindMotionPolicy() {
  bindCount += 1
  if (bindCount === 1 && typeof window !== 'undefined') {
    const reduced = window.matchMedia('(prefers-reduced-motion: reduce)')
    const coarse = window.matchMedia('(pointer: coarse)')
    const onReduced = () => policy.set('reduced', reduced.matches)
    const onCoarse = () => policy.set('coarse', coarse.matches)
    const onVisibility = () => policy.set('hidden', document.hidden)
    const onBlur = () => policy.set('unfocused', true)
    const onFocus = () => policy.set('unfocused', false)

    policy.set('reduced', reduced.matches)
    policy.set('coarse', coarse.matches)
    policy.set('hidden', document.hidden)
    policy.set('unfocused', !document.hasFocus())
    sync()

    reduced.addEventListener?.('change', onReduced)
    coarse.addEventListener?.('change', onCoarse)
    document.addEventListener('visibilitychange', onVisibility)
    window.addEventListener('blur', onBlur)
    window.addEventListener('focus', onFocus)

    teardown = () => {
      reduced.removeEventListener?.('change', onReduced)
      coarse.removeEventListener?.('change', onCoarse)
      document.removeEventListener('visibilitychange', onVisibility)
      window.removeEventListener('blur', onBlur)
      window.removeEventListener('focus', onFocus)
    }
  }
  let released = false
  return () => {
    if (released) return
    released = true
    bindCount -= 1
    if (bindCount === 0 && teardown) {
      teardown()
      teardown = null
    }
  }
}

export function useMotionPolicy() {
  return { staticMode, ambientAllowed, motionMode, toggleStaticMode, bindMotionPolicy }
}
