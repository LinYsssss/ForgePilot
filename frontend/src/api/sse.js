/** Incremental SSE framing parser supporting arbitrary chunks, CRLF, multi-event chunks and tail packets. */
export function createSseParser(onEvent) {
  let buffer = ''
  let pendingCarriageReturn = false
  function dispatch(block) {
    if (!block) return
    let event = 'message'
    const data = []
    for (const line of block.split('\n')) {
      if (line.startsWith('event:')) event = line.slice(6).trim()
      else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''))
    }
    if (data.length) onEvent({ event, data: data.join('\n') })
  }
  function drain() {
    let index
    while ((index = buffer.indexOf('\n\n')) >= 0) {
      dispatch(buffer.slice(0, index))
      buffer = buffer.slice(index + 2)
    }
  }
  function appendNormalized(chunk) {
    for (const current of String(chunk || '')) {
      if (pendingCarriageReturn) {
        buffer += '\n'
        pendingCarriageReturn = false
        if (current === '\n') continue
      }
      if (current === '\r') pendingCarriageReturn = true
      else buffer += current
    }
  }
  return {
    push(chunk) {
      appendNormalized(chunk)
      drain()
    },
    finish() {
      if (pendingCarriageReturn) buffer += '\n'
      pendingCarriageReturn = false
      drain()
      dispatch(buffer)
      buffer = ''
    },
  }
}

export async function readSseStream(stream, onEvent) {
  const reader = stream.getReader()
  const decoder = new TextDecoder()
  const parser = createSseParser(onEvent)
  try {
    while (true) {
      const { value, done } = await reader.read()
      if (done) break
      parser.push(decoder.decode(value, { stream: true }))
    }
    parser.push(decoder.decode())
    parser.finish()
  } finally {
    reader.releaseLock()
  }
}
