export const INK_NAV = [
  { key: 'dashboard', glyph: '台', label: '工作台', routeName: 'dashboard', needsProject: false },
  { key: 'projects', glyph: '项', label: '项目', routeName: 'projects', needsProject: false },
  { key: 'requirements', glyph: '需', label: '研发任务', routeName: 'requirements', needsProject: true },
  { key: 'repository', glyph: '仓', label: '代码仓库', routeName: 'repository', needsProject: true },
  { key: 'agent', glyph: '审', label: '智能审查', routeName: 'agent', needsProject: true },
  { key: 'quality', glyph: '质', label: '质量中心', routeName: 'quality', needsProject: true },
  { key: 'knowledge', glyph: '知', label: '知识库', routeName: 'knowledge', needsProject: true },
  { key: 'metrics', glyph: '度', label: '研发度量', routeName: 'metrics', needsProject: true },
]
export const INK_NATIVE_KEYS = INK_NAV.map(item => item.key)
export function navItemsFor(hasProject) { return INK_NAV.map(({key,glyph,label,needsProject})=>({key,glyph,label,disabled:needsProject&&!hasProject})) }
export function resolveNavigation(key,currentKey){if(key===currentKey)return{action:'none',key};const item=INK_NAV.find(entry=>entry.key===key);return item?{action:'ink',key,routeName:item.routeName}:{action:'none',key}}
