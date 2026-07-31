import { ref } from 'vue'
import { api } from '../api/client.js'
import { unwrapPage } from '../api/page.js'
import { useBusy } from './useBusy.js'
import { useSession } from './useSession.js'
import { useToast } from './useToast.js'

// 知识库(单例):文档列表、上传、检索,以及"参与审查的文档"选择集。
// reviewDocs / prDocs 分属审查页与 PR 页,两处独立持有互不污染;
// 文档列表刷新时统一裁剪掉已被删除的文档 id。
const { activeProject } = useSession()
const { busy } = useBusy()
const { toastMsg } = useToast()

const documents = ref([])
const docType = ref('BUSINESS_FLOW')
const selectedFile = ref(null)
const searchQuery = ref('发货前是否需要校验支付状态')
const searchMatches = ref([])
const searched = ref(false)
const reviewDocs = ref(new Set())
const prDocs = ref(new Set())

function onFileChange(e) { selectedFile.value = e.target.files?.[0] || null }

async function uploadDocument() {
  if (!selectedFile.value) return toastMsg('请选择文档', 'error')
  busy.upload = true
  try {
    const form = new FormData()
    form.append('file', selectedFile.value)
    await api(`/projects/${activeProject.value.projectId}/knowledge/documents?docType=${docType.value}`, { method: 'POST', body: form })
    selectedFile.value = null
    await loadDocuments()
    toastMsg('文档已入库', 'success')
  } finally { busy.upload = false }
}

async function loadDocuments() {
  documents.value = unwrapPage(await api(`/projects/${activeProject.value.projectId}/knowledge/documents?size=100`))
  const ids = new Set(documents.value.map(d => d.documentId))
  reviewDocs.value = new Set([...reviewDocs.value].filter(id => ids.has(id)))
  prDocs.value = new Set([...prDocs.value].filter(id => ids.has(id)))
}

async function reindexKnowledge() {
  const r = await api(`/projects/${activeProject.value.projectId}/knowledge/reindex`, { method: 'POST' })
  await loadDocuments()
  toastMsg(`索引已重建：${r.indexedDocuments}/${r.totalDocuments} 篇` + (r.failedDocuments ? `，${r.failedDocuments} 失败` : ''), 'success')
}

async function deleteDocument(id) {
  await api(`/projects/${activeProject.value.projectId}/knowledge/documents/${id}`, { method: 'DELETE' })
  await loadDocuments()
  toastMsg('文档已删除', 'success')
}

async function searchKnowledge() {
  busy.search = true; searched.value = true
  try {
    const data = await api(`/projects/${activeProject.value.projectId}/knowledge/search`, { method: 'POST', body: JSON.stringify({ query: searchQuery.value, topK: 5 }) })
    searchMatches.value = data.matches || []
  } finally { busy.search = false }
}

function chosenDocsReset() { reviewDocs.value = new Set(); prDocs.value = new Set() }

function reset() {
  documents.value = []
  searchMatches.value = []
  searched.value = false
  chosenDocsReset()
}

export function useKnowledge() {
  return {
    documents, docType, selectedFile, searchQuery, searchMatches, searched, reviewDocs, prDocs,
    onFileChange, uploadDocument, loadDocuments, reindexKnowledge, deleteDocument, searchKnowledge,
    chosenDocsReset, reset,
  }
}
