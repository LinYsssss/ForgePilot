import { reactive } from 'vue'
import { api } from '../api/client.js'
import { useToast } from './useToast.js'

// 问题反馈(单例):一人一票的 upsert 语义 + 展开态与草稿。
const { toastMsg } = useToast()

const openFeedback = reactive({})
const feedbackMap = reactive({})
const fbDraft = reactive({})

function ensureDraft(id) { if (!fbDraft[id]) fbDraft[id] = { comment: '' }; return fbDraft[id] }

function myVote(issueId) {
  const list = feedbackMap[issueId] || []
  return list.find(fb => fb.mine) || null
}

function voteClass(issueId, type) {
  const mine = myVote(issueId)
  return mine && mine.feedbackType === type ? 'on-' + type : ''
}

function feedbackCount(issueId) {
  const n = (feedbackMap[issueId] || []).length
  return n ? ` (${n})` : ''
}

async function toggleFeedback(id) {
  openFeedback[id] = !openFeedback[id]
  if (openFeedback[id]) await loadFeedback(id)
}

async function loadFeedback(id) {
  feedbackMap[id] = await api(`/review-issues/${id}/feedback`)
  const mine = myVote(id)
  ensureDraft(id).comment = mine?.comment || ''
}

async function vote(issueId, type) {
  const draft = ensureDraft(issueId)
  await api(`/review-issues/${issueId}/feedback`, { method: 'POST', body: JSON.stringify({ feedbackType: type, comment: draft.comment || '' }) })
  openFeedback[issueId] = true
  await loadFeedback(issueId)
  toastMsg('反馈已保存', 'success')
}

async function submitFeedbackForm(issueId) {
  const mine = myVote(issueId)
  if (!mine) return toastMsg('请先选择一个投票', 'error')
  const d = ensureDraft(issueId)
  await api(`/review-issues/${issueId}/feedback`, { method: 'POST', body: JSON.stringify({ feedbackType: mine.feedbackType, comment: d.comment }) })
  await loadFeedback(issueId)
  toastMsg('反馈说明已更新', 'success')
}

async function removeMyFeedback(issueId) {
  await api(`/review-issues/${issueId}/feedback`, { method: 'DELETE' })
  ensureDraft(issueId).comment = ''
  await loadFeedback(issueId)
  toastMsg('已撤回你的反馈', 'success')
}

export function useFeedback() {
  return {
    openFeedback, feedbackMap, fbDraft,
    ensureDraft, myVote, voteClass, feedbackCount,
    toggleFeedback, loadFeedback, vote, submitFeedbackForm, removeMyFeedback,
  }
}
