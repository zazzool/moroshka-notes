import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createHash } from 'node:crypto'

const root = new URL('../', import.meta.url)
const bytes = path => readFileSync(new URL(path, root))
const hash = path => createHash('sha256').update(bytes(path)).digest('hex')

test('Android runtime artwork, font and launcher match the approved handoff', () => {
  const manifest = JSON.parse(bytes('docs/design/ANDROID_RUNTIME_ASSETS.json'))
  for (const { source, path } of manifest.copies) assert.equal(hash(path), hash(source), path)
  assert.match(bytes('android/app/src/main/AndroidManifest.xml').toString(), /android:icon="@mipmap\/moroshka_launcher"/)
})
