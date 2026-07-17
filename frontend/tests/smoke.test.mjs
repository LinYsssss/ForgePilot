import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { test } from 'node:test'
import config from '../vite.config.js'
import { canApprovePatch } from '../src/components/agent/patchApprovalPolicy.js'

test('frontend declares the supported Node runtime', async () => {
  const packageJson = JSON.parse(
    await readFile(new URL('../package.json', import.meta.url), 'utf8')
  )

  assert.equal(packageJson.engines?.node, '>=20 <23')
})

test('vite build output is deterministic', () => {
  assert.equal(config.build?.outDir, 'dist')
  assert.equal(config.build?.emptyOutDir, true)
})

test('index declares the Vue entrypoint', async () => {
  const html = await readFile(new URL('../index.html', import.meta.url), 'utf8')

  assert.match(html, /src="\/src\/main\.js"/)
})

test('invalid or stale patches disable human approval', () => {
  assert.equal(canApprovePatch({ applyStatus: 'FAILED', targetDisappeared: true }), false)
  assert.equal(canApprovePatch({ applyStatus: 'SUCCEEDED', targetDisappeared: false }), false)
  assert.equal(canApprovePatch({ applyStatus: 'SUCCEEDED', targetDisappeared: true, stale: true }), false)
  assert.equal(canApprovePatch({ applyStatus: 'SUCCEEDED', targetDisappeared: true, stale: false }), true)
})
