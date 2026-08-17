import { ref } from 'vue'
import { api, apiStream, ApiError } from '../api/client.js'
import { readSseStream } from '../api/sse.js'

const MAX_HISTORY_MESSAGES = 12
const MAX_HISTORY_ITEM_CHARS = 8000
const MAX_HISTORY_TOTAL_CHARS = 24000

const enabled = ref(false)
const currentKey = ref('')
const messages = ref([])
const sources = ref([])
const warnings = ref([])
const truncatedSections = ref([])
const streaming = ref(false)
const lastQuestion = ref('')
let controller = null
let activeAnswer = null
let activeRequestId = 0

async function loadConfig() {
  try {
    const config = await api('/assistant/config')
    enabled.value = !!config?.enabled
  } catch {
    enabled.value = false
  }
}

function keyOf(projectId, requirementId) { return `${projectId || ''}:${requirementId || ''}` }

function ensureKey(projectId, requirementId) {
  const next = keyOf(projectId, requirementId)
  if (currentKey.value !== next) reset(next)
}

export function buildAssistantHistory(items, limits = {}) {
  const maxMessages = limits.maxMessages ?? MAX_HISTORY_MESSAGES
  const maxItemChars = limits.maxItemChars ?? MAX_HISTORY_ITEM_CHARS
  const maxTotalChars = limits.maxTotalChars ?? MAX_HISTORY_TOTAL_CHARS
  const result = []
  let total = 0
  const candidates = Array.isArray(items) ? items : []
  for (let index = candidates.length - 1; index >= 0 && result.length < maxMessages; index--) {
    const item = candidates[index]
    if (!item || item.pending || item.error || item.cancelled || !['USER', 'ASSISTANT'].includes(item.role)) continue
    const content = String(item.content || '').trim().slice(0, maxItemChars)
    if (!content) continue
    const remaining = maxTotalChars - total
    if (remaining <= 0) break
    const bounded = content.slice(0, remaining)
    result.unshift({ role: item.role, content: bounded })
    total += bounded.length
  }
  return result
}

function removeMessage(target) {
  messages.value = messages.value.filter(item => item !== target)
}

function cancelActive() {
  activeRequestId++
  if (controller) controller.abort()
  controller = null
  if (activeAnswer) {
    activeAnswer.pending = false
    activeAnswer.cancelled = true
    if (!activeAnswer.content) removeMessage(activeAnswer)
  }
  activeAnswer = null
  streaming.value = false
}

async function ask(projectId, requirementId, question) {
  const text = String(question || '').trim()
  if (!enabled.value || !projectId || !requirementId || !text) return
  ensureKey(projectId, requirementId)
  if (controller || streaming.value) cancelActive()
  lastQuestion.value = text
  const history = buildAssistantHistory(messages.value)
  messages.value.push({ role: 'USER', content: text })
  const answer = { role: 'ASSISTANT', content: '', pending: true, error: '', cancelled: false }
  messages.value.push(answer)
  sources.value = []
  warnings.value = []
  truncatedSections.value = []
  streaming.value = true
  const requestId = ++activeRequestId
  const requestController = new AbortController()
  controller = requestController
  activeAnswer = answer
  let doneReceived = false
  try {
    const body = await apiStream(`/projects/${projectId}/requirements/${requirementId}/assistant/stream`, {
      method: 'POST', signal: requestController.signal, body: JSON.stringify({ message: text, history }),
    })
    await readSseStream(body, ({ event, data }) => {
      if (requestId !== activeRequestId) return
      const payload = JSON.parse(data)
      if (event === 'context') {
        sources.value = Array.isArray(payload.sources) ? payload.sources : []
        warnings.value = Array.isArray(payload.warnings) ? payload.warnings : []
        truncatedSections.value = Array.isArray(payload.truncatedSections) ? payload.truncatedSections : []
      } else if (event === 'delta') {
        answer.content += payload.text || ''
      } else if (event === 'done') {
        doneReceived = true
      } else if (event === 'error') {
        throw new ApiError(payload.message || '研发助手生成失败', { code: payload.errorCode || null })
      }
    })
    if (requestId === activeRequestId && !doneReceived) {
      throw new ApiError('研发助手流式响应意外结束')
    }
    if (requestId === activeRequestId) answer.pending = false
  } catch (error) {
    if (requestId !== activeRequestId) return
    answer.pending = false
    if (error?.name === 'AbortError') {
      answer.cancelled = true
      if (!answer.content) removeMessage(answer)
    } else {
      answer.error = error?.message || '研发助手生成失败'
    }
  } finally {
    if (requestId === activeRequestId) {
      controller = null
      activeAnswer = null
      streaming.value = false
    }
  }
}

function stop() {
  cancelActive()
}

function retry(projectId, requirementId) {
  const question = lastQuestion.value
  if (!question || streaming.value) return Promise.resolve()
  while (messages.value.length && messages.value.at(-1).role === 'ASSISTANT' && messages.value.at(-1).error) {
    messages.value.pop()
  }
  if (messages.value.at(-1)?.role === 'USER' && messages.value.at(-1)?.content === question) messages.value.pop()
  return ask(projectId, requirementId, question)
}

function reset(nextKey = '') {
  cancelActive()
  currentKey.value = nextKey
  messages.value = []
  sources.value = []
  warnings.value = []
  truncatedSections.value = []
  lastQuestion.value = ''
}

export function useRequirementAssistant() {
  return { enabled, currentKey, messages, sources, warnings, truncatedSections, streaming,
    loadConfig, ask, stop, retry, reset, ensureKey }
}
