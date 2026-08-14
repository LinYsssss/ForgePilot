// 单一指针观察器(模块级单例;合同 §7 实现规则)。
// 只做三件事:pointermove(passive)→ rAF 合并 → 把归一化坐标写进根节点
// CSS 变量 --ink-pointer-x/y。不写任何 Vue 响应式状态;只有
// InkAmbientScene 的 CSS 消费这两个变量。抑制(静态/减动效/隐藏/失焦)
// 时立即归零。粒子画布经 getPointerState() 读取同一份坐标,不另设监听。
import { watch } from 'vue'
import { createPointerField, normalizePointer } from './pointerField.js'
import { useMotionPolicy } from './useMotionPolicy.js'

const { ambientAllowed } = useMotionPolicy()

let field = null
let bindCount = 0
let teardown = null

function writeVars(x, y) {
  document.documentElement.style.setProperty('--ink-pointer-x', x.toFixed(3))
  document.documentElement.style.setProperty('--ink-pointer-y', y.toFixed(3))
}

/** ambient 消费口(粒子漂移用);无监听时返回原点。 */
export function getPointerState() {
  return field ? field.current() : { x: 0, y: 0 }
}

/** 壳层/登录门禁挂载时绑定一次;返回解绑函数。重入安全,始终只有一个监听。 */
export function bindPointerField() {
  bindCount += 1
  if (bindCount === 1 && typeof window !== 'undefined') {
    field = createPointerField({ write: writeVars })

    const onPointerMove = (event) => {
      if (!ambientAllowed.value) return
      const { x, y } = normalizePointer(event.clientX, event.clientY, window.innerWidth, window.innerHeight)
      field.move(x, y)
    }
    // 抑制生效(含页面隐藏/窗口失焦,由 motion policy 汇聚)→ 归零
    const stopWatch = watch(ambientAllowed, (allowed) => {
      if (!allowed) field.reset()
    })

    window.addEventListener('pointermove', onPointerMove, { passive: true })

    teardown = () => {
      window.removeEventListener('pointermove', onPointerMove)
      stopWatch()
      field.reset()
      field.dispose()
      field = null
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
