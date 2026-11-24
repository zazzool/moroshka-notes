import test from 'node:test'
import assert from 'node:assert/strict'
import { encodeArchive, decodeArchive, planRestore, maxArchiveBytes } from '../web/backup.mjs'

const id = '11111111-1111-1111-1111-111111111111'
const second = '22222222-2222-2222-2222-222222222222'
const original = {id, text:'# Архив\n\n[[Лес]]\n- [ ] Задача'}
const encoder = new TextEncoder()

test('portable backup roundtrip includes only note data and integrity hashes', async () => {
  const input = [{...original, base:'secret revision', token:'must-not-leak'}]
  const bytes = await encodeArchive(input)
  const archive = JSON.parse(new TextDecoder().decode(bytes))
  assert.equal(archive.format, 'moroshka-notes-backup')
  assert.equal(archive.version, 1)
  assert.deepEqual(Object.keys(archive.notes[0]).sort(), ['id','sha256','text'])
  assert.equal(archive.notes[0].text, original.text)
  const restored = await decodeArchive(bytes)
  assert.deepEqual(restored.map(({id,text})=>({id,text})), [original])
  assert.equal((await planRestore(restored,new Map())).additions[0].id, id)
})

test('restore preserves conflicts, handles deleted IDs, and is idempotent', async () => {
  const verified = await decodeArchive(await encodeArchive([original]))
  const current = new Map([[id,{id,text:'newer local edit'}]])
  const plan = await planRestore(verified,current)
  assert.equal(plan.conflicts,1)
  assert.equal(plan.additions.length,1)
  assert.notEqual(plan.additions[0].id,id)
  assert.equal(plan.additions[0].text, original.text)
  assert.equal(current.get(id).text,'newer local edit')
  const same = await planRestore(verified,new Map([...current,...plan.additions.map(n=>[n.id,n])]))
  assert.deepEqual(same,{additions:[],skipped:1,conflicts:0})
  const deleted = await planRestore(verified,new Map(),new Set([id]))
  assert.equal(deleted.additions[0].id,plan.additions[0].id)
  assert.equal((await planRestore(verified,new Map([[id,original]]))).skipped,1)
})

test('rejects invalid, oversized or corrupt data before any restore', async () => {
  const bytes = await encodeArchive([original,{id:second,text:'second'}])
  const corrupt = JSON.parse(new TextDecoder().decode(bytes))
  corrupt.notes[1].text = 'modified'
  await assert.rejects(decodeArchive(encoder.encode(JSON.stringify(corrupt))), /Контрольная сумма/)
  corrupt.notes[1] = corrupt.notes[0]
  await assert.rejects(decodeArchive(encoder.encode(JSON.stringify(corrupt))), /повторяется/)
  await assert.rejects(decodeArchive(new Uint8Array(maxArchiveBytes+1)), /32 МиБ/)
  await assert.rejects(decodeArchive(new Uint8Array([0xff,0x00])), /кодировка/)
  await assert.rejects(decodeArchive(encoder.encode('{}')), /формат/)
  await assert.rejects(encodeArchive([{id:'../secret',text:'x'}]), /UUID/)
  await assert.rejects(encodeArchive([{id,text:'я'.repeat(262145)}]), /размер/)
  await assert.rejects(encodeArchive([original,original]), /повторяется/)
})
