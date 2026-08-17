import { strict as assert } from 'node:assert'
import test from 'node:test'
import { readFile } from 'node:fs/promises'
import { createSseParser } from '../src/api/sse.js'

test('assistant SSE parser handles chunk boundaries, CRLF, multiple events and a final tail packet', () => {
  const events = []
  const parser = createSseParser(event => events.push(event))
  parser.push('event: context\r\ndata: {"sources":[]}\r')
  parser.push('\n\r\nevent: delta\ndata: {"text":"你"}\n\nevent: delta\n')
  parser.push('data: {"text":"好"}\n\nevent: done\ndata: {"totalTokens":0}')
  parser.finish()
  assert.deepEqual(events.map(item => item.event), ['context', 'delta', 'delta', 'done'])
  assert.equal(JSON.parse(events[2].data).text, '好')
})


test('assistant output is plain text and verified sources only come from context sources', async () => {
  const component = await readFile(new URL('../src/features/requirements/RequirementsPaper.vue', import.meta.url), 'utf8')
  assert.doesNotMatch(component, /v-html/)
  assert.match(component, /v-for="source in assistantSources"/)
  assert.match(component, /white-space:\s*pre-wrap/)
})


test('assistant history keeps only recent bounded completed messages', async () => {
  const { buildAssistantHistory } = await import('../src/composables/useRequirementAssistant.js')
  const history = buildAssistantHistory([
    { role: 'USER', content: 'old' },
    { role: 'ASSISTANT', content: 'x'.repeat(9000) },
    { role: 'ASSISTANT', content: 'ignored', error: 'failed' },
    { role: 'USER', content: 'newest' },
  ], { maxMessages: 3, maxItemChars: 8, maxTotalChars: 12 })
  assert.deepEqual(history, [
    { role: 'ASSISTANT', content: 'xxxxxx' },
    { role: 'USER', content: 'newest' },
  ])
})

test('assistant stale streams cannot clear or mutate a newer request', async () => {
  globalThis.document = { cookie: '' }
  const encoder = new TextEncoder()
  const held = []
  let streamCall = 0
  globalThis.fetch = async (url, options = {}) => {
    if (String(url).endsWith('/assistant/config')) {
      return new Response(JSON.stringify({ code: 0, data: { enabled: true } }), {
        status: 200, headers: { 'Content-Type': 'application/json' },
      })
    }
    streamCall++
    if (streamCall === 2) {
      const payload = [
        'event: context\ndata: {"sources":[{"id":"REQ-1"}],"warnings":[],"truncatedSections":[]}\n\n',
        'event: delta\ndata: {"text":"second"}\n\n',
        'event: done\ndata: {"totalTokens":0}\n\n',
      ].join('')
      return new Response(new ReadableStream({
        start(controller) { controller.enqueue(encoder.encode(payload)); controller.close() },
      }), { status: 200, headers: { 'Content-Type': 'text/event-stream;charset=UTF-8' } })
    }
    return new Response(new ReadableStream({
      start(controller) { held.push({ controller, signal: options.signal }) },
    }), { status: 200, headers: { 'Content-Type': 'text/event-stream;charset=UTF-8' } })
  }

  const { useRequirementAssistant } = await import('../src/composables/useRequirementAssistant.js')
  const assistant = useRequirementAssistant()
  assistant.reset()
  await assistant.loadConfig()

  const first = assistant.ask(1, 1, 'first')
  while (held.length < 1) await Promise.resolve()
  await assistant.ask(1, 1, 'second')
  assert.equal(assistant.messages.value.at(-1).content, 'second')

  const third = assistant.ask(1, 1, 'third')
  while (held.length < 2) await Promise.resolve()
  held[0].controller.error(new DOMException('aborted', 'AbortError'))
  await first
  assert.equal(assistant.streaming.value, true, 'stale first request must not clear the active third request')
  assert.equal(assistant.messages.value.at(-1).pending, true)

  assistant.reset()
  held[1].controller.error(new DOMException('aborted', 'AbortError'))
  await third
  assert.equal(assistant.streaming.value, false)
  assert.deepEqual(assistant.messages.value, [])
})


test('apiStream reuses CSRF, credentials and centralized 401 handling', async () => {
  globalThis.document = { cookie: 'XSRF-TOKEN=rotated-token' }
  const calls = []
  let unauthorized = 0
  globalThis.fetch = async (url, options = {}) => {
    calls.push({ url: String(url), options })
    if (String(url).endsWith('/auth/csrf')) {
      return new Response(JSON.stringify({
        code: 0,
        data: { enabled: true, cookieName: 'XSRF-TOKEN', headerName: 'X-XSRF-TOKEN' },
      }), { status: 200, headers: { 'Content-Type': 'application/json' } })
    }
    return new Response(null, { status: 401 })
  }
  const client = await import('../src/api/client.js')
  client.setUnauthorizedHandler(() => { unauthorized++ })
  await client.initCsrf()
  await assert.rejects(
    client.apiStream('/projects/1/requirements/2/assistant/stream', { method: 'POST', body: '{}' }),
    error => error.status === 401,
  )
  assert.equal(calls[1].options.credentials, 'include')
  assert.equal(calls[1].options.headers['X-XSRF-TOKEN'], 'rotated-token')
  assert.equal(unauthorized, 1)
})
