import test from 'node:test'
import assert from 'node:assert/strict'
import { createHash, randomUUID } from 'node:crypto'
import { pullSnapshot } from '../web/sync-pull.mjs'

const note = (text,id=randomUUID()) => ({id,text,revision:createHash('sha256').update(text).digest('hex')})
const meta = ({id,revision}) => ({id,revision})
const inventory = (notes,deleted=[]) => ({version:1,notes:notes.map(meta),deleted:deleted.map(meta)})
const apiWith = (notes,deleted=[],calls=[]) => async (path,method='GET',body) => {
  calls.push({path,method,body})
  if(path==='/api/sync/manifest') return inventory(notes,deleted)
  assert.equal(path,'/api/sync/read')
  assert.equal(method,'POST')
  assert.ok(body.notes.length<=8)
  return {notes:body.notes.map(({id,revision})=>notes.find(n=>n.id===id&&n.revision===revision))}
}

test('incremental pull skips known text, batches new revisions and retains deletion metadata',async()=>{
  const notes=Array.from({length:20},(_,i)=>note('# Заметка '+i))
  const deleted=[note('# Удалена')]
  const known=new Map(notes.slice(0,3).map(n=>[n.id,n.revision]))
  // An older base revision (including an edited local note) still needs the remote body.
  known.set(notes[3].id,'0'.repeat(64))
  const calls=[]
  const result=await pullSnapshot(apiWith(notes,deleted,calls),known)
  assert.deepEqual(calls.map(c=>c.body?.notes.length||0),[0,8,8,1])
  assert.deepEqual(result.notes.slice(0,3),notes.slice(0,3).map(meta))
  assert.deepEqual(result.notes.slice(3),notes.slice(3).map(n=>({id:n.id,revision:n.revision,text:n.text})))
  assert.deepEqual(result.deleted,deleted.map(meta))
  calls.length=0
  const unchanged=await pullSnapshot(apiWith(notes,deleted,calls),new Map(notes.map(n=>[n.id,n.revision])))
  assert.equal(calls.length,1)
  assert.ok(unchanged.notes.every(n=>!Object.hasOwn(n,'text')))
})

test('fallback only when the manifest endpoint is absent',async()=>{
  const legacy={notes:[note('# Legacy')],deleted:[]}
  const calls=[]
  assert.equal(await pullSnapshot(async path=>{
    calls.push(path)
    if(path==='/api/sync/manifest') throw Object.assign(Error('missing'),{status:404})
    return legacy
  },new Map()),legacy)
  assert.deepEqual(calls,['/api/sync/manifest','/api/notes'])
  for(const status of [401,409,500]){
    const attempts=[]
    await assert.rejects(pullSnapshot(async path=>{
      attempts.push(path)
      throw Object.assign(Error('failed'),{status})
    },new Map()),e=>e.status===status)
    assert.deepEqual(attempts,['/api/sync/manifest'])
  }
})

test('batch mismatch, duplicate metadata, malformed hash and cancellation abort the pull',async()=>{
  const n=note('# Valid')
  for(const reply of [
    {notes:[]},
    {notes:[{...n,text:'corrupt'}]},
    {notes:[{...n,revision:'0'.repeat(64)}]},
    {notes:[note('# Unrequested')]}
  ]){
    await assert.rejects(pullSnapshot(async path=>path==='/api/sync/manifest'?inventory([n]):reply,new Map()),/Некорректный/)
  }
  for(const bad of [
    {version:2,notes:[],deleted:[]},
    inventory([n,n]),
    inventory([n],[n]),
    {version:1,notes:[{...meta(n),revision:'bad'}],deleted:[]}
  ]){
    await assert.rejects(pullSnapshot(async()=>bad,new Map()),/Некорректный/)
  }
  const calls=[]
  const error=Object.assign(Error('stale'),{status:409})
  await assert.rejects(pullSnapshot(async path=>{
    calls.push(path)
    if(path==='/api/sync/manifest') return inventory([n])
    throw error
  },new Map()),e=>e===error)
  assert.deepEqual(calls,['/api/sync/manifest','/api/sync/read'])
  let checks=0
  await assert.rejects(pullSnapshot(apiWith([n]),new Map(),()=>{
    if(++checks===3) throw Error('cancel')
  }),/cancel/)
})
