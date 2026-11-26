import test from 'node:test'
import assert from 'node:assert/strict'
import {mkdtempSync,rmSync,readFileSync,statSync,existsSync} from 'node:fs'
import {tmpdir} from 'node:os'
import {join} from 'node:path'
import {randomUUID} from 'node:crypto'
import {application} from '../server/app.mjs'
import {normalizeModes,localMetadata,localSummary,localRank} from '../web/processing.mjs'

test('server config is owner-only, checked before replacement, redacted, persisted and live',async t=>{
  const dir=mkdtempSync(join(tmpdir(),'moroshka-config-')),token=randomUUID(),actionsToken=randomUUID(),mcpToken=randomUUID()
  const firstKey='sk-or-v1-'+randomUUID(),nextKey='sk-or-v1-'+randomUUID()
  const calls=[]
  let server,origin
  async function start(){
    server=application({dir,token,actionsToken,mcpToken,openrouterKey:firstKey,model:'legacy/chat'},async(url,req)=>{
      calls.push({url,key:req.headers.Authorization,method:req.method,body:req.body&&JSON.parse(req.body)})
      if(url.endsWith('/key'))return new Response(null,{status:req.headers.Authorization==='Bearer invalid-key-123456789'?401:200})
      if(url.endsWith('/chat/completions'))return Response.json({choices:[{message:{content:'Коротко'}}]})
      throw Error('unexpected upstream')
    })
    await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve))
    origin='http://127.0.0.1:'+server.address().port
  }
  const stop=()=>new Promise(resolve=>server.close(resolve))
  t.after(async()=>{await stop();rmSync(dir,{recursive:true,force:true})})
  const api=(path,method='GET',body,key=token,headers={})=>fetch(origin+path,{method,
    headers:{Authorization:'Bearer '+key,'Content-Type':'application/json',...headers},
    body:body===undefined?undefined:JSON.stringify(body)})
  const path='/api/ai/config', file=join(dir,'.openrouter-settings-v1.json')
  await start()
  for(const key of [actionsToken,mcpToken,'wrong'])assert.equal((await api(path,'GET',undefined,key)).status,401)
  let cfg=await (await api(path)).json()
  assert.equal(cfg.configured,true)
  assert.equal(cfg.model,'legacy/chat')
  assert.equal(cfg.revision,'environment')
  assert.ok(!JSON.stringify(cfg).includes(firstKey))
  const update={revision:cfg.revision,keyAction:'replace',apiKey:nextKey,model:'test/chat',embeddingModel:'test/embed',visionModel:'test/vision'}
  assert.equal((await api(path,'PUT',update,token,{Origin:'https://wrong.example'})).status,403)
  assert.equal((await api(path,'PUT',{...update,apiKey:'invalid-key-123456789'})).status,400)
  assert.equal(existsSync(file),false)
  assert.equal((await api(path,'PUT',{...update,model:'https://evil.example/x?secret'})).status,400)
  const before=calls.length
  const changed=await api(path,'PUT',update)
  assert.equal(changed.status,200)
  cfg=await changed.json()
  assert.ok(cfg.revision!=='environment'&&cfg.configured)
  assert.equal(cfg.model,'test/chat')
  assert.ok(!JSON.stringify(cfg).includes(nextKey))
  assert.equal(calls.length,before+1)
  assert.deepEqual(calls.at(-1),{url:'https://openrouter.ai/api/v1/key',key:'Bearer '+nextKey,method:'GET',body:undefined})
  assert.equal(statSync(file).mode&0o777,0o600)
  assert.equal(JSON.parse(readFileSync(file,'utf8')).key,nextKey)
  assert.equal((await api(path,'PUT',update)).status,409)
  assert.equal((await api(path+'/test','POST',{})).status,200)
  const id=randomUUID(), note=await (await api('/api/notes/'+id,'PUT',{text:'# Текст',base:null})).json()
  assert.equal((await api('/api/summary','POST',{id,revision:note.revision,consent:true})).status,200)
  assert.equal(calls.at(-1).key,'Bearer '+nextKey)
  assert.equal(calls.at(-1).body.model,'test/chat')
  assert.deepEqual(calls.at(-1).body.provider,{data_collection:'deny',zdr:true})
  await stop();await start()
  const restarted=await (await api(path)).json()
  assert.deepEqual(restarted,cfg)
  const remove=await api(path,'PUT',{revision:cfg.revision,keyAction:'remove',model:'test/chat',embeddingModel:'test/embed',visionModel:'test/vision'})
  assert.equal(remove.status,200)
  assert.equal((await remove.json()).configured,false)
  await stop();await start()
  assert.equal((await (await api(path)).json()).configured,false)
  assert.equal((await api(path+'/test','POST',{})).status,409)
  assert.equal((await api('/api/summary','POST',{id,revision:note.revision,consent:true})).status,503)
})

test('local operations have safe defaults, deterministic extractive output, and never alter note input',()=>{
  assert.deepEqual(normalizeModes(null),{metadata:'local',summary:'local',search:'local',links:'local',ocr:'local'})
  assert.deepEqual(normalizeModes({ocr:'server',links:'off',search:'bogus'}),{metadata:'local',summary:'local',search:'local',links:'off',ocr:'server'})
  const source='# Старый заголовок\n\nПроект исследования сна. Стабильный режим сна важен.\n- [ ] Проверить исследование\n#ручной'
  const meta=localMetadata(source)
  assert.equal(meta.category,'task')
  assert.equal(meta.title,'Проект исследования сна. Стабильный режим сна важен.')
  assert.ok(meta.tags.every(t=>/^[\p{L}\p{N}]{3,40}$/u.test(t)))
  assert.equal(meta.tags.includes('ручной'),false)
  const summary=localSummary(source)
  assert.ok(summary.split('\n\n').every(s=>source.includes(s)))
  const a={id:'a',text:'# Сон\n\nСон и режим сна'}
  const b={id:'b',text:'# Дерево\n\nСад, растения'}
  assert.deepEqual(localRank('сон',[b,a]).map(n=>n.id),['a'])
  assert.deepEqual(localRank('сон',[b,a],'a'),[])
})
