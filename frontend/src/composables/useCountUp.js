import { onScopeDispose, ref, watch } from 'vue'

// 数字滚动:目标值变化时用 rAF 缓动到位。整数展示;
// prefers-reduced-motion 下直达终态。纯展示工具,不做任何业务。
const reduced = typeof matchMedia === 'function' && matchMedia('(prefers-reduced-motion: reduce)').matches

export function useCountUp(source, duration = 600) {
  const display = ref(0) // 从 0 起步,首帧即有进场滚动
  let raf = null

  watch(source, target => {
    if (raf) cancelAnimationFrame(raf)
    const to = Number(target) || 0
    if (reduced) { display.value = to; return }
    const from = Number(display.value) || 0
    if (from === to) return
    const start = performance.now()
    const tick = now => {
      const t = Math.min((now - start) / duration, 1)
      const eased = 1 - Math.pow(1 - t, 3) // ease-out cubic
      display.value = Math.round(from + (to - from) * eased)
      if (t < 1) raf = requestAnimationFrame(tick)
      else raf = null
    }
    raf = requestAnimationFrame(tick)
  }, { immediate: true })

  onScopeDispose(() => { if (raf) cancelAnimationFrame(raf) })
  return display
}
