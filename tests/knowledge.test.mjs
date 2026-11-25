import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync,rmSync,readFileSync,writeFileSync,existsSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { randomUUID } from 'node:crypto'
import { application } from '../server/app.mjs'
import { digest } from '../server/vault.mjs'
import { applyProposal,appendLink } from '../web/knowledge.mjs'
import { titleOf, tagsOf, linksOf, scan, renameHeading } from '../web/markdown.mjs'

async function fixture(t){
  const dir=mkdtempSync(join(tmpdir(),'moroshka-knowledge-'))
  const token=randomUUID(),actionsToken=randomUUID(),mcpToken=randomUUID(),calls=[]
  let override=null
  const server=application({dir,token,actionsToken,mcpToken,model:'test/chat',embeddingModel:'test/vector',
    openrouterKey:'fixture',publicUrl:'https://notes.example.org'},async (url,req)=>{
    const body=JSON.parse(req.body);calls.push({url,body})
    if(override)return override(url,body)
    if(url.endsWith('/embeddings'))return Response.json({data:body.input.map((s,index)=>({
      index,embedding:/сон|спать|бессонниц/i.test(s)?[1,0]:[0,1]}))})
    return Response.json({choices:[{message:{content:JSON.stringify({title:'Предложение',category:'idea',tags:['сон','Сон','исследование']})}}]})
  })
  await new Promise(r=>server.listen(0,'127.0.0.1',r))
  t.after(async()=>{await new Promise(r=>server.close(r));rmSync(dir,{recursive:true,force:true})})
  const origin='http://127.0.0.1:'+server.address().port
  const api=(path,method='GET',body,key=token,headers={})=>fetch(origin+path,{method,
    headers:{Authorization:'Bearer '+key,'Content-Type':'application/json',...(path==='/mcp'?{Host:'notes.example.org'}:{}),...headers},
    body:body===undefined?undefined:JSON.stringify(body)})
  async function add(text,id=randomUUID(),base=null){
    const res=await api('/api/notes/'+id,'PUT',{text,base});assert.equal(res.status,200);return res.json()
  }
  return {dir,token,actionsToken,mcpToken,calls,api,add,setOverride:f=>{override=f}}
}

test('AI proposals are explicit, validated, revision pinned and never mutate Markdown',async t=>{
  const {api,add,calls,setOverride,actionsToken}=await fixture(t)
  const n=await add('# Original\n\nСон\n\n- [ ] задача')
  const input={id:n.id,revision:n.revision}
  assert.equal((await api('/api/ai/analyze','POST',input)).status,400)
  assert.equal((await api('/api/ai/analyze','POST',{...input,consent:true},actionsToken)).status,401)
  assert.equal(calls.length,0)
  const result=await (await api('/api/ai/analyze','POST',{...input,consent:true})).json()
  assert.deepEqual(result,{...input,title:'Предложение',category:'idea',tags:['сон','исследование'],truncated:false})
  assert.equal(calls[0].body.messages[1].content,n.text)
  assert.deepEqual(calls[0].body.provider,{require_parameters:true,data_collection:'deny',zdr:true})
  assert.equal((await (await api('/api/notes')).json()).notes[0].text,n.text)
  setOverride(()=>Response.json({choices:[{message:{content:'{"title":"bad","category":"bad","tags":[]}'}}]}))
  assert.equal((await api('/api/ai/analyze','POST',{...input,consent:true})).status,502)
  await add('# New',n.id,n.revision)
  const before=calls.length
  assert.equal((await api('/api/ai/analyze','POST',{...input,consent:true})).status,409)
  assert.equal(calls.length,before)
})

test('semantic index requires consent, reuses exact revisions and hides stale/deleted vectors',async t=>{
  const {api,add,calls,dir}=await fixture(t)
  const a=await add('# Сон\nБессонница'),b=await add('# Спать\nсон'),c=await add('# Сад\nРастения')
  const requested=[a,b,c].map(({id,revision})=>({id,revision}))
  assert.equal((await api('/api/ai/index','POST',{notes:requested})).status,400)
  assert.equal(calls.length,0)
  const status=await (await api('/api/ai/index','POST',{notes:requested,consent:true})).json()
  assert.equal(status.indexed,3)
  assert.equal(calls.length,1)
  assert.deepEqual(calls[0].body.provider,{data_collection:'deny',zdr:true})
  assert.equal((await api('/api/ai/index','POST',{notes:requested,consent:true})).status,200)
  assert.equal(calls.length,1)
  const result=await (await api('/api/ai/search','POST',{query:'сон',consent:true})).json()
  assert.deepEqual(new Set(result.notes.map(n=>n.id)),new Set([a.id,b.id]))
  assert.equal(calls.length,2)
  const related=await (await api('/api/ai/related','POST',{id:a.id,revision:a.revision})).json()
  assert.deepEqual(related.notes.map(n=>n.id),[b.id])
  assert.equal(calls.length,2)
  await add('# Садовая работа',b.id,b.revision)
  assert.equal((await api('/api/notes/'+c.id,'DELETE',{base:c.revision})).status,200)
  assert.equal(existsSync(join(dir,'semantic-v1',b.id+'.json')),false)
  assert.equal(existsSync(join(dir,'semantic-v1',c.id+'.json')),false)
  assert.equal((await (await api('/api/ai/status')).json()).indexed,1)
  assert.deepEqual((await (await api('/api/ai/related','POST',{id:a.id,revision:a.revision})).json()).notes,[])
  assert.equal((await api('/api/ai/index','POST',{notes:[requested[1]],consent:true})).status,409)
  assert.equal((await (await api('/api/ai/index','DELETE')).json()).indexed,0)
  assert.equal((await api('/api/ai/search','POST',{query:'сон',consent:true})).status,409)
  assert.equal((await (await api('/api/notes')).json()).notes.length,2)
})

test('MCP modern and legacy read-only tools have an independent bearer scope',async t=>{
  const {api,add,token,actionsToken,mcpToken}=await fixture(t)
  const note=await add('# Сон\nТекст')
  const send=(method,params={},headers={},key=mcpToken,id=1)=>api('/mcp','POST',{jsonrpc:'2.0',id,method,params},key,headers)
  for(const key of [token,actionsToken,'wrong'])assert.equal((await send('tools/list',{}, {},key)).status,401)
  assert.equal((await api('/api/notes','GET',undefined,mcpToken)).status,401)
  assert.equal((await send('tools/list',{}, {Origin:'https://evil.invalid'})).status,403)
  assert.equal((await send('tools/list',{}, {Host:'evil.invalid'})).status,403)
  assert.equal((await send('tools/list',{}, {Origin:'https://notes.example.org/evil'})).status,403)
  const init=await (await send('initialize',{protocolVersion:'2025-11-25'})).json()
  assert.equal(init.result.protocolVersion,'2025-11-25')
  const list=await (await send('tools/list')).json()
  assert.deepEqual(list.result.tools.map(t=>t.name),['search_notes','read_note','list_notes'])
  const result=await (await send('tools/call',{name:'read_note',arguments:{id:note.id}})).json()
  assert.equal(JSON.parse(result.result.content[0].text).text,note.text)
  const found=await (await send('tools/call',{name:'search_notes',arguments:{query:'сон'}})).json()
  assert.equal(JSON.parse(found.result.content[0].text).notes[0].id,note.id)
  assert.equal((await send('tools/call',{name:'delete_note',arguments:{id:note.id}})).status,200)
  assert.equal((await (await send('tools/call',{name:'delete_note',arguments:{id:note.id}})).json()).error.code,-32602)
  const version='2026-07-28',meta={'io.modelcontextprotocol/protocolVersion':version,'io.modelcontextprotocol/clientInfo':{name:'test',version:'1'},'io.modelcontextprotocol/clientCapabilities':{}}
  const modernHeaders={'MCP-Protocol-Version':version,'Mcp-Method':'server/discover'}
  const discover=await (await send('server/discover',{_meta:meta},modernHeaders)).json()
  assert.ok(discover.result.supportedVersions.includes(version))
  assert.equal((await send('tools/list',{_meta:meta},modernHeaders)).status,400)
  const modern=await (await send('tools/call',{_meta:meta,name:'read_note',arguments:{id:note.id}},
    {'MCP-Protocol-Version':version,'Mcp-Method':'tools/call','Mcp-Name':'read_note'})).json()
  assert.equal(modern.result.resultType,'complete')
  assert.equal(JSON.parse(modern.result.content[0].text).id,note.id)
  const modernList=await (await send('tools/list',{_meta:meta},{'MCP-Protocol-Version':version,'Mcp-Method':'tools/list'})).json()
  assert.equal(modernList.result.resultType,'complete')
  assert.equal(modernList.result.tools.length,3)
  assert.equal(list.result.resultType,undefined)
  assert.equal((await api('/mcp','GET',undefined,mcpToken)).status,405)
  assert.equal((await api('/mcp','POST',{jsonrpc:'2.0',method:'notifications/initialized'},mcpToken)).status,202)
})

test('accept only explicit metadata; preserve the original source and reject ambiguous links',()=>{
  const original='# Old\n\nBody\n\n#existing\n'
  assert.equal(applyProposal(original,{title:'$&',tags:['existing','new'],category:'idea'}),'# $&\n\nBody\n\n#existing\n\n#new #type-idea\n')
  assert.equal(applyProposal(original,{}),original)
  assert.equal(applyProposal(original,{tags:['existing']}),original)
  const fence='~~~\n# untouched\n'
  assert.equal(applyProposal(fence,{tags:['tag']}),'#tag\n\n'+fence)
  assert.throws(()=>applyProposal(original,{title:'bad\nheader'}))
  assert.throws(()=>applyProposal(original,{tags:['not a tag']}))
  assert.throws(()=>appendLink(original,'same',['Same','same'],[]))
  assert.equal(appendLink(original,'Target',['Target'],[]),'[[Target]]\n\n'+original)
  assert.equal(appendLink(original,'Target',['Target'],['target']),original)
})


test('metadata scanner protects long fences, invalid closing markers, inline code and code examples',()=>{
  const ticks=String.fromCharCode(96)
  const source=[ticks.repeat(4)+'js','# Fake','#type-task [[Fake]]','- [ ] Fake',ticks.repeat(3),
    ticks.repeat(4)+'not a closing fence','# Still fake',ticks.repeat(4),
    '# Real','Body '+ticks+'#fake [[Fake]]'+ticks+' #real [[Target]]','## Section',
    '    # Indented','    #fake [[Fake]]','- [ ] Real task','#type-task'].join('\n')
  assert.equal(titleOf(source),'Real')
  assert.deepEqual(tagsOf(source),['real','type-task'])
  assert.deepEqual(linksOf(source),['Target'])
  assert.deepEqual(scan(source).prose.filter(x=>x.line.startsWith('- [ ]')).map(x=>x.line),['- [ ] Real task'])
  const changed=applyProposal(source,{title:'New $&',category:'idea',tags:['REAL','new']})
  assert.equal(titleOf(changed),'New $&')
  assert.ok(changed.includes('#type-task [[Fake]]'))
  assert.deepEqual(tagsOf(changed),['real','new','type-idea'])
  assert.equal(renameHeading('#\nNot a heading','Title'),'# Title\n\n#\nNot a heading')
  const open=ticks.repeat(4)+'\n# code\n'+ticks.repeat(3)+'\n'
  assert.equal(applyProposal(open,{tags:['tag']}),'#tag\n\n'+open)
})


test('summary requires explicit consent and uses the same privacy routing and bounded content',async t=>{
  const {api,add,calls,setOverride}=await fixture(t)
  const n=await add('# Long\n'+'🧠'.repeat(13000))
  const input={id:n.id,revision:n.revision}
  assert.equal((await api('/api/summary','POST',input)).status,400)
  assert.equal(calls.length,0)
  setOverride(()=>Response.json({choices:[{message:{content:'Short summary'}}]}))
  const summary=await (await api('/api/summary','POST',{...input,consent:true})).json()
  assert.deepEqual(summary,{summary:'Short summary',truncated:true})
  assert.equal(Array.from(calls[0].body.messages[1].content).length,12000)
  assert.deepEqual(calls[0].body.provider,{data_collection:'deny',zdr:true})
  setOverride(()=>new Response('x'.repeat(4*1024*1024+1)))
  assert.equal((await api('/api/summary','POST',{...input,consent:true})).status,502)
  setOverride(()=>Response.json({choices:[{message:{content:''}}]}))
  assert.equal((await api('/api/summary','POST',{...input,consent:true})).status,502)
  const current=(await (await api('/api/notes')).json()).notes[0]
  assert.equal(current.text,n.text)
})
