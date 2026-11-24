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
  return {api,dir,actionsToken,outgoing,origin}
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

test('delete preserves server copy and rejects stale writes', async t => {
  const {api,dir} = await fixture(t), id = randomUUID(), path = '/api/notes/' + id
  const text = '# Archived\noriginal'
  await api(path,'PUT',{text,base:null})
  assert.equal((await api(path,'DELETE',{base:digest('stale')})).status,409)
  assert.equal((await api(path,'DELETE',{base:digest(text)})).status,200)
  assert.equal((await api(path,'DELETE',{base:digest(text)})).status,200)
  assert.equal(readFileSync(join(dir,id+'.deleted'),'utf8'),text)
  assert.equal((await api(path,'PUT',{text:'stale edit',base:digest(text)})).status,409)
  const data = await (await api('/api/notes')).json()
  assert.deepEqual(data.notes,[])
  assert.deepEqual(data.deleted,[{id,revision:digest(text)}])
})

test('pairing requires owner approval and a secret held by the requesting browser', async t => {
  const { api } = await fixture(t)
  const start = await api('/pair/start','POST',{})
  assert.equal(start.status,200)
  const {code,secret} = await start.json()
  assert.match(code,/^\d{8}$/)
  const claim = {code,secret}
  assert.deepEqual(await (await api('/pair/claim','POST',claim,'')).json(),{pending:true})
  assert.equal((await api('/pair/approve','POST',{code},'wrong')).status,401)
  assert.equal((await api('/pair/approve','POST',{code})).status,200)
  assert.equal((await api('/pair/claim','POST',{code,secret:'0'.repeat(64)},'')).status,404)
  const result = await (await api('/pair/claim','POST',claim,'')).json()
  assert.ok(result.token)
  assert.equal((await api('/pair/claim','POST',claim,'')).status,404)
  assert.equal((await api('/pair/approve','POST',{code})).status,404)
})

test('pairing rejects cross-origin requests and supports cancellation', async t => {
  const {api,origin} = await fixture(t)
  const response = await fetch(origin+'/pair/start', {
    method:'POST', headers:{Origin:'https://other.example','Content-Type':'application/json'}, body:'{}'
  })
  assert.equal(response.status,403)
  const {code,secret} = await (await api('/pair/start','POST',{})).json()
  assert.equal((await api('/pair/cancel','POST',{code,secret:'0'.repeat(64)},'')).status,404)
  assert.equal((await api('/pair/cancel','POST',{code,secret},'')).status,200)
  assert.equal((await api('/pair/approve','POST',{code})).status,404)
})

test('approved visual assets are served without authentication using the correct MIME type', async t => {
  const { origin } = await fixture(t)
  for (const [path, mime] of [["/assets/cloudberry.webp","image/webp"],["/assets/hero-day.webp","image/webp"],["/assets/hero-night.webp","image/webp"],["/assets/ideas.webp","image/webp"],["/assets/knowledge.webp","image/webp"],["/assets/search.webp","image/webp"],["/assets/balance.webp","image/webp"],["/assets/manrope.ttf","font/ttf"],["/assets/manrope-OFL.txt","text/plain; charset=utf-8"],["/assets/note.svg","image/svg+xml; charset=utf-8"],["/assets/checkbox.svg","image/svg+xml; charset=utf-8"],["/assets/search.svg","image/svg+xml; charset=utf-8"],["/assets/link.svg","image/svg+xml; charset=utf-8"],["/assets/sparkles.svg","image/svg+xml; charset=utf-8"],["/assets/folder.svg","image/svg+xml; charset=utf-8"]]) {
    const res = await fetch(origin + path)
    assert.equal(res.status, 200, path)
    assert.equal(res.headers.get('content-type'), mime, path)
    assert.ok((await res.arrayBuffer()).byteLength > 0, path)
  }
  assert.equal((await fetch(origin + '/assets/not-a-file.webp')).status, 404)
})


test('history archives unique previous revisions and never archives rejected writes', async t => {
  const {api,dir,actionsToken} = await fixture(t)
  const id=randomUUID(), path='/api/notes/'+id, history='/api/history/'+id
  const first='# История\n\nПервая редакция', second='# История\n\nВторая редакция'
  const a=digest(first), b=digest(second)
  assert.equal((await api(path,'PUT',{text:first,base:null})).status,200)
  assert.deepEqual((await (await api(history)).json()).revisions,[])
  assert.equal((await api(path,'PUT',{text:second,base:a})).status,200)
  assert.equal((await api(path,'PUT',{text:'rejected',base:a})).status,409)
  assert.equal((await api(path,'PUT',{text:second,base:a})).status,200)
  let list=(await (await api(history)).json()).revisions
  assert.deepEqual(list.map(x=>x.revision),[a])
  assert.ok(Number.isFinite(Date.parse(list[0].archivedAt)))
  assert.deepEqual(await (await api(history+'/'+a)).json(),{id,revision:a,text:first})
  assert.equal(readFileSync(join(dir,'history',id,a+'.md'),'utf8'),first)
  assert.equal((await api(history+'/'+b)).status,404)
  assert.equal((await api(history,'GET',undefined,'wrong')).status,401)
  assert.equal((await api(history,'GET',undefined,actionsToken)).status,401)
  assert.equal((await api('/api/history/not-a-uuid')).status,400)
  assert.equal((await api(history+'/not-a-hash')).status,400)
  assert.equal((await api(path,'PUT',{text:first,base:b})).status,200)
  assert.equal((await api(path,'PUT',{text:second,base:a})).status,200)
  list=(await (await api(history)).json()).revisions
  assert.deepEqual(new Set(list.map(x=>x.revision)),new Set([a,b]))
  assert.equal(list.length,2)
  assert.equal((await api(path,'DELETE',{base:b})).status,200)
  const trash=(await (await api('/api/trash')).json()).notes
  assert.equal(trash.length,1)
  assert.deepEqual({id:trash[0].id,title:trash[0].title,revision:trash[0].revision},{id,title:'История',revision:b})
  const deleted=await (await api('/api/trash/'+id)).json()
  assert.equal(deleted.text,second)
  assert.equal((await api('/api/trash','GET',undefined,actionsToken)).status,401)
  assert.equal((await api('/api/trash/invalid')).status,400)
  assert.equal((await api('/api/trash/'+randomUUID())).status,404)
  assert.equal((await api(path,'PUT',{text:first,base:null})).status,409)
  const restored=randomUUID()
  assert.equal((await api('/api/notes/'+restored,'PUT',{text:deleted.text,base:null})).status,200)
  const live=await (await api('/api/notes')).json()
  assert.deepEqual(live.notes,[{id:restored,text:second,revision:b}])
  assert.deepEqual(live.deleted,[{id,revision:b}])
  assert.equal((await api(history+'/'+a)).status,200)
})
