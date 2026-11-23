import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { randomUUID } from 'node:crypto'
import { application } from '../server/app.mjs'
import { digest } from '../server/vault.mjs'

async function fixture(t) {
  const dir = mkdtempSync(join(tmpdir(), 'moroshka-'))
  const token = randomUUID(), actionsToken = randomUUID()
  const outgoing = []
  const server = application({ token, actionsToken, dir, openrouterKey:'fixture', model:'test/model', publicUrl:'https://notes.example.org' }, async (url, req) => {
    outgoing.push({url,body:JSON.parse(req.body)})
    return Response.json({choices:[{message:{content:'Пересказ'}}]})
  })
  await new Promise(r => server.listen(0, '127.0.0.1', r))
  t.after(async () => { await new Promise(r => server.close(r)); rmSync(dir,{recursive:true,force:true}) })
  const origin = 'http://127.0.0.1:' + server.address().port
  const api = (path, method='GET', data, key=token) => fetch(origin + path, {
    method,headers:{Authorization:'Bearer '+key,'Content-Type':'application/json'},body:data && JSON.stringify(data)
  })
  return {api,dir,actionsToken,outgoing}
}
test('Markdown round trip, conditional writes, concurrent update and retry',async t => {
  const {api,dir} = await fixture(t); const id = randomUUID(); const path='/api/notes/'+id
  const text='# Морошка\n\n[[Лес]]\n- [ ] Осмотреть поляну\n'
  const create=await api(path,'PUT',{text,base:null})
  assert.equal(create.status,200)
  const {revision}=await create.json(); assert.equal(revision,digest(text))
  assert.equal(readFileSync(join(dir,id+'.md'),'utf8'),text)
  assert.equal((await api(path,'PUT',{text,base:null})).status,200)
  const results=await Promise.all(['A','B'].map(change=>api(path,'PUT',{text:change,base:revision})))
  assert.deepEqual(results.map(r=>r.status).sort(),[200,409])
  const {notes}=await (await api('/api/notes')).json()
  assert.equal(notes.length,1); assert.equal(notes[0].text,readFileSync(join(dir,id+'.md'),'utf8'))
})
test('scoped keys, read-only Actions, OpenAPI and selected-note AI request',async t => {
  const {api,actionsToken,outgoing}=await fixture(t); const id=randomUUID()
  assert.equal((await api('/api/notes','GET',undefined,actionsToken)).status,401)
  assert.equal((await api('/api/notes','GET',undefined,'')).status,401)
  assert.equal((await api('/actions/search?q=test')).status,401)
  await api('/api/notes/'+id,'PUT',{text:'# Test\nHello',base:null})
  await api('/api/notes/'+randomUUID(),'PUT',{text:'Other private text',base:null})
  const results=await (await api('/actions/search?q=test','GET',undefined,actionsToken)).json()
  assert.deepEqual(results.notes,[{id,title:'Test'}])
  const read=await api('/actions/notes/'+id,'GET',undefined,actionsToken)
  assert.equal((await read.json()).text,'# Test\nHello')
  assert.equal((await api('/actions/notes/'+id,'PUT',{text:'edited'},actionsToken)).status,404)
  assert.equal((await (await api('/openapi.json')).json()).servers[0].url,'https://notes.example.org')
  const summary=await (await api('/api/summary','POST',{id,revision:digest('# Test\nHello')})).json()
  assert.equal(summary.summary,'Пересказ')
  assert.equal(outgoing.length,1)
  assert.equal(outgoing[0].body.messages[1].content,'# Test\nHello')
})
test('invalid writes, oversized note, missing configuration and anonymous shell',async t => {
  const {api}=await fixture(t)
  assert.equal((await api('/api/notes/nope','PUT',{base:null,text:'x'})).status,400)
  const path='/api/notes/'+randomUUID()
  for (const body of [{base:null,text:5},{text:'x'},{base:null,text:'a'.repeat(512*1024+1)}]) {
    assert.equal((await api(path,'PUT',body)).status,400)
  }
  assert.equal((await api('/healthz')).status,200)
  for (const path of ['/','/sw.js','/icon.png','/app.js','/style.css']) assert.equal((await api(path)).status,200)
  assert.throws(()=>application({token:'short'}))
})
