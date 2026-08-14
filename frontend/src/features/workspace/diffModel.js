// EvidenceDiff 的行模型(纯逻辑,可测)。
// unified 行号推导复用 utils/labels.diffLines(冻结锚点:citation 按
// file+line 定位,useAgentWorkspace.focusEvidenceAnchor 消费
// data-diff-file/data-diff-line);split 视图由 unified 行配对派生,
// 不改变任何行内容——两种视图是同一份 patch 的等价投影。
import { diffLines } from '../../utils/labels.js'

/**
 * unified 视图行:diffLines 输出 + 行所属文件(跟踪 "+++ b/<path>",
 * 与旧 PatchDiffViewer 完全同构,保证 citation 定位在墨境同样命中)。
 * @param {string} patchContent
 * @returns {Array<{text: string, cls: string, oldNo: string, newNo: string, file: string}>}
 */
export function unifiedRows(patchContent) {
  let file = ''
  return diffLines(patchContent || '').map((line) => {
    if (line.text.startsWith('+++ b/')) file = line.text.slice(6).trim()
    else if (line.text.startsWith('+++ ')) file = line.text.slice(4).trim()
    return { ...line, file: line.cls === 'meta' || line.cls === 'hunk' ? '' : file }
  })
}

/**
 * split(并排)视图:把 unified 行流配对成左(旧)/右(新)两列。
 * 规则:
 *   - meta/hunk 行整行跨列;
 *   - 连续的删除段与紧随的新增段逐行对齐,较长一侧余下行单侧呈现;
 *   - 上下文行两侧同现。
 * 右列(新码)携带 file/newNo,供 data-diff-file/data-diff-line 锚点。
 * @param {ReturnType<typeof unifiedRows>} rows
 * @returns {Array<{type: 'meta'|'hunk'|'pair', key: number, text?: string,
 *   left?: {no: string, text: string, cls: string}|null,
 *   right?: {no: string, text: string, cls: string, file?: string}|null}>}
 */
export function splitRows(rows) {
  const out = []
  const list = Array.isArray(rows) ? rows : []
  let i = 0
  while (i < list.length) {
    const row = list[i]
    if (row.cls === 'meta' || row.cls === 'hunk') {
      out.push({ type: row.cls, key: out.length, text: row.text })
      i += 1
      continue
    }
    if (row.cls === 'del') {
      const dels = []
      while (i < list.length && list[i].cls === 'del') dels.push(list[i++])
      const adds = []
      while (i < list.length && list[i].cls === 'add') adds.push(list[i++])
      const span = Math.max(dels.length, adds.length)
      for (let k = 0; k < span; k += 1) {
        out.push({
          type: 'pair',
          key: out.length,
          left: dels[k] ? { no: dels[k].oldNo, text: dels[k].text, cls: 'del' } : null,
          right: adds[k] ? { no: adds[k].newNo, text: adds[k].text, cls: 'add', file: adds[k].file } : null,
        })
      }
      continue
    }
    if (row.cls === 'add') {
      out.push({
        type: 'pair',
        key: out.length,
        left: null,
        right: { no: row.newNo, text: row.text, cls: 'add', file: row.file },
      })
      i += 1
      continue
    }
    out.push({
      type: 'pair',
      key: out.length,
      left: { no: row.oldNo, text: row.text, cls: '' },
      right: { no: row.newNo, text: row.text, cls: '', file: row.file },
    })
    i += 1
  }
  return out
}
