import { fmtDate } from './format.js'

// 状态/枚举 → 中文标签与样式类的纯函数映射。从 App.vue 抽出,供各视图共用。
export function statusLabel(s) { return { PENDING: '等待中', RUNNING: '运行中', SUCCESS: '成功', FAILED: '失败', DEAD: '已死信', CANCELED: '已停止' }[s] || s }
export function prStateLabel(s) { return { PENDING: '待审查', PASSED: '已通过', CHANGES_REQUESTED: '已打回', WAIVED: '已豁免' }[s] || s }
export function actionLabel(s) { return { APPROVE: '通过', REQUEST_CHANGES: '打回', WAIVE: '豁免', COMMENT: '评论' }[s] || s }
export function actionStateClass(s) { return { APPROVE: 'SUCCESS', REQUEST_CHANGES: 'FAILED', WAIVE: 'PENDING', COMMENT: 'CONSUMED' }[s] || s }
export function mqStatusClass(s) { return s === 'CONSUMED' || s === 'PUBLISHED' ? 'SUCCESS' : (s === 'DEAD' ? 'DEAD' : 'FAILED') }
export function fbLabel(t) { return { TRUE_POSITIVE: '真实问题', FALSE_POSITIVE: '误报', NEED_DISCUSSION: '需讨论' }[t] || t }
export function fbBadge(t) { return { TRUE_POSITIVE: 'risk-LOW', FALSE_POSITIVE: 'risk-HIGH', NEED_DISCUSSION: 'risk-MEDIUM' }[t] || 'risk-NONE' }
export function confClass(c) { return c >= 0.75 ? 'c-high' : c >= 0.5 ? 'c-mid' : 'c-low' }
export function confText(c) { return c >= 0.75 ? '高置信' : c >= 0.5 ? '中等' : '较低' }

export function relativeDay(dateStr) {
  const today = fmtDate(new Date().toISOString())
  const y = new Date(); y.setDate(y.getDate() - 1)
  const yesterday = fmtDate(y.toISOString())
  if (dateStr === today) return '今天'
  if (dateStr === yesterday) return '昨天'
  return ''
}

export function diffLines(diff) {
  if (!diff) return []
  // 解析 @@ -old,+new @@ 头,为每行标注新旧行号(上下文行两侧都有,增/删行只有一侧)。
  let oldNo = null
  let newNo = null
  return diff.split(/\r?\n/).map(text => {
    let cls = ''
    let o = ''
    let n = ''
    const hunk = /^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@/.exec(text)
    if (hunk) {
      cls = 'hunk'
      oldNo = parseInt(hunk[1], 10)
      newNo = parseInt(hunk[2], 10)
    } else if (text.startsWith('+++') || text.startsWith('---') || text.startsWith('diff ') || text.startsWith('index ')) {
      cls = 'meta'
    } else if (text.startsWith('+')) {
      cls = 'add'
      if (newNo != null) n = newNo++
    } else if (text.startsWith('-')) {
      cls = 'del'
      if (oldNo != null) o = oldNo++
    } else if (oldNo != null && newNo != null) {
      o = oldNo++
      n = newNo++
    }
    return { text: text || ' ', cls, oldNo: String(o), newNo: String(n) }
  })
}
