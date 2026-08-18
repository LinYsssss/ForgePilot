export function formatDuration(ms) {
  if (ms === null || ms === undefined) return '无样本'
  if (ms < 1000) return `${ms} ms`
  if (ms < 60000) return `${(ms / 1000).toFixed(1)} s`
  if (ms < 3600000) return `${(ms / 60000).toFixed(1)} min`
  return `${(ms / 3600000).toFixed(1)} h`
}
export function formatRate(rate) { return rate === null || rate === undefined ? '无样本' : `${(rate * 100).toFixed(1)}%` }
export function metricEntries(map) { return Object.entries(map || {}).sort((a, b) => b[1] - a[1]) }
