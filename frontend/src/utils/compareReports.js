// 对比审查的纯函数核心:把"带知识库"与"不带知识库"两份报告的问题列表
// 对齐成三桶,并标注哪些问题呈现出知识库信号(引用了文档名或历史事故编号)。
// 保持简单可解释:键 = 文件路径 + 归一化标题;不做语义相似度。

function normalizeTitle(title) {
  return String(title || '')
    .toLowerCase()
    .replace(/[\s　]+/g, '')
    .replace(/[\p{P}\p{S}]/gu, '')
}

export function issueKey(issue) {
  return `${issue.filePath || ''}::${normalizeTitle(issue.title)}`
}

/** 历史事故编号(演示语料的 bug-history 约定)或文档文件名被引用即视为知识库信号。 */
export function hasKnowledgeSignal(issue, docNames = []) {
  const text = [issue.title, issue.description, issue.evidence, issue.suggestion, issue.impact]
    .filter(Boolean)
    .join('\n')
  if (/\b(BUG-\d+|INC-\d{4}-\d{2})\b/i.test(text)) return true
  return docNames.some(name => name && text.includes(name))
}

export function compareReports(withReport, withoutReport, docNames = []) {
  const withIssues = withReport?.issues || []
  const withoutIssues = withoutReport?.issues || []
  const withoutByKey = new Map(withoutIssues.map(issue => [issueKey(issue), issue]))
  const matchedKeys = new Set()

  const onlyWith = []
  const both = []
  for (const issue of withIssues) {
    const key = issueKey(issue)
    const counterpart = withoutByKey.get(key)
    if (counterpart) {
      matchedKeys.add(key)
      both.push({ withDocs: issue, withoutDocs: counterpart })
    } else {
      onlyWith.push(issue)
    }
  }
  const onlyWithout = withoutIssues.filter(issue => !matchedKeys.has(issueKey(issue)))

  const signalCount = onlyWith.filter(issue => hasKnowledgeSignal(issue, docNames)).length
  return {
    onlyWith,
    both,
    onlyWithout,
    summary: {
      withTotal: withIssues.length,
      withoutTotal: withoutIssues.length,
      extraFromKnowledge: onlyWith.length,
      knowledgeSignals: signalCount,
    },
  }
}
