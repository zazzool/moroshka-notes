import test from 'node:test'
import assert from 'node:assert/strict'
import {mkdtempSync,rmSync} from 'node:fs'
import {tmpdir} from 'node:os'
import {join} from 'node:path'
import {createHash,randomUUID} from 'node:crypto'
import {application} from '../server/app.mjs'
import {appendImage,stripImages,composeImages,imageRefs,feedContent,validateJpeg} from '../web/media-format.mjs'
import {encodeArchive,decodeArchiveBundle} from '../web/backup.mjs'

const jpeg='/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAYEBQYFBAYGBQYHBwYIChAKCgkJChQODwwQFxQYGBcUFhYaHSUfGhsjHBYWICwgIyYnKSopGR8tMC0oMCUoKSj/2wBDAQcHBwoIChMKChMoGhYaKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCj/wAARCAAYADADASIAAhEBAxEB/8QAGAABAAMBAAAAAAAAAAAAAAAAAAQFBgf/xAAxEAAAAgcDCwQDAAAAAAAAAAAAAQIDBAUGBxIRFCETFhcYIlZnlaTT4iQmMVEzNUH/xAAUAQEAAAAAAAAAAAAAAAAAAAAA/8QAFBEBAAAAAAAAAAAAAAAAAAAAAP/aAAwDAQACEQMRAD8A7VFM4oEhV+tTmfz9ujyZqcqpua9ZTUiSaO0igZHspEeB/wBFTrByw3m6Bq7YtopmXm+/Wp2ZlRu88hT6t2urLM6ypEkthOsrbLbDwwMjIVOmThxMnkfmAawcsN5ugau2GsHLDeboGrthpk4cTJ5H5hpk4cTJ5H5gGsHLDeboGrtjQQVNKDo3eq12ww+L82qlJtCau6rlViskkUTO1NAi+UkcLbcRLgWMs7b97ciRyXWj90w3bLVVfj2jqspx+qi+xqgAAAAAAAAAAH//2Q=='
const bytes=Buffer.from(jpeg,'base64')
const id=createHash('sha256').update(bytes).digest('hex')
test('photo references stay separate from source and archive includes verified JPEG',async()=>{
  assert.deepEqual(validateJpeg(bytes),{width:48,height:24})
  const source='# Title\n\nActual content #tag'
  const text=appendImage(source,id)
  assert.deepEqual(imageRefs(text),[id])
  assert.equal(stripImages(text),source)
  assert.equal(composeImages(source,[id]),text)
  assert.equal(appendImage(text,id),text)
  assert.equal(feedContent(text).body,'Actual content #tag')
  assert.equal(feedContent(text).images,1)
  assert.deepEqual(imageRefs('~~~\n'+text+'\n~~~'),[])
  const n={id:randomUUID(),text}
  const archive=await encodeArchive([n],async()=>new Uint8Array(bytes))
  const restored=await decodeArchiveBundle(archive)
  assert.equal(restored.notes[0].text,text)
  assert.deepEqual(Buffer.from(restored.images.get(id)),bytes)
  const raw=JSON.parse(new TextDecoder().decode(archive))
  assert.equal(raw.version,2)
  raw.images[0].id='0'.repeat(64)
  await assert.rejects(decodeArchiveBundle(new TextEncoder().encode(JSON.stringify(raw))),/изображение/)
  await assert.rejects(encodeArchive([n]),/отсутствует изображение/)
})
test('owner media API validates hashes, orders uploads before notes, and OCR needs consent',async t=>{
  const dir=mkdtempSync(join(tmpdir(),'moroshka-photos-')),token=randomUUID(),actionsToken=randomUUID(),calls=[]
  const server=application({dir,token,actionsToken,openrouterKey:'fixture',visionModel:'fixture/vision'},async(url,request)=>{
    calls.push(JSON.parse(request.body))
    return Response.json({choices:[{message:{content:'Распознанная строка'},finish_reason:'stop'}]})
  })
  await new Promise(r=>server.listen(0,'127.0.0.1',r))
  t.after(async()=>{await new Promise(r=>server.close(r));rmSync(dir,{recursive:true,force:true})})
  const api=(path,method='GET',data,key=token)=>fetch('http://127.0.0.1:'+server.address().port+path,{
    method,headers:{Authorization:'Bearer '+key,'Content-Type':'application/json'},body:data===undefined?undefined:JSON.stringify(data)})
  const text=appendImage('# Фото\n\nКонтекст',id),note='/api/notes/'+randomUUID(),media='/api/media/'+id
  assert.equal((await api(note,'PUT',{text,base:null})).status,409)
  assert.equal((await api(media,'PUT',{data:jpeg},actionsToken)).status,401)
  assert.equal((await api('/api/media/'+'0'.repeat(64),'PUT',{data:jpeg})).status,400)
  assert.equal((await api('/api/media/missing','POST',{ids:[id,id]})).status,400)
  assert.deepEqual(await (await api('/api/media/missing','POST',{ids:[id]})).json(),{missing:[id]})
  assert.equal((await api(media,'PUT',{data:jpeg})).status,200)
  assert.equal((await api(media,'PUT',{data:jpeg})).status,200)
  assert.deepEqual(await (await api('/api/media/missing','POST',{ids:[id]})).json(),{missing:[]})
  assert.equal((await api(media,'GET',undefined,actionsToken)).status,401)
  assert.deepEqual(await (await api(media)).json(),{id,data:jpeg})
  const saved=await api(note,'PUT',{text,base:null})
  assert.equal(saved.status,200)
  assert.equal((await api('/api/ai/ocr','POST',{id})).status,400)
  assert.equal(calls.length,0)
  assert.equal((await api('/api/ai/ocr','POST',{id,consent:true},actionsToken)).status,401)
  const result=await (await api('/api/ai/ocr','POST',{id,consent:true})).json()
  assert.deepEqual(result,{id,text:'Распознанная строка',possiblyTruncated:false})
  assert.equal(calls.length,1)
  assert.equal(calls[0].messages[1].content[1].image_url.url,'data:image/jpeg;base64,'+jpeg)
  assert.equal(calls[0].provider.zdr,true)
  assert.equal(calls[0].provider.data_collection,'deny')
  const revision=(await saved.json()).revision
  assert.equal((await api(note,'DELETE',{base:revision})).status,200)
  assert.equal((await api(media)).status,200)
})
