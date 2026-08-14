// 笔触进度模型(纯逻辑,可测;渲染见 BrushProgress.vue)。
// 合同 §5 BrushProgress:当前步骤必须同时有图标(印记字)、文本与
// 数值/时间;动画只在阶段变化时播放一次。

/**
 * @param {Array<{key: string, label: string, glyph?: string, meta?: string}>} steps
 * @param {number} current 当前进行中步骤的下标;传 steps.length 表示全部完成
 * @returns {{
 *   steps: Array<{key: string, label: string, glyph: string, meta: string, status: 'done'|'current'|'pending'}>,
 *   doneCount: number, total: number, fraction: number
 * }}
 */
export function progressModel(steps, current) {
  const list = Array.isArray(steps) ? steps : []
  const total = list.length
  const cursor = Math.max(0, Math.min(Number.isInteger(current) ? current : 0, total))
  const resolved = list.map((step, index) => ({
    key: step.key ?? String(index),
    label: step.label ?? '',
    glyph: step.glyph ?? String(index + 1),
    meta: step.meta ?? '',
    status: index < cursor ? 'done' : index === cursor ? 'current' : 'pending',
  }))
  // 描线推进比例:到达当前步骤圆心;全部完成时画满
  const fraction = total <= 1 ? (cursor > 0 ? 1 : 0) : Math.min(1, cursor / (total - 1))
  return { steps: resolved, doneCount: cursor, total, fraction }
}
