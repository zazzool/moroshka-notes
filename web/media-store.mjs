import {imageId,imageRefs,validateJpeg,maxImageBytes,maxImageSide} from './media-format.mjs'

export async function imageHash(bytes){
  return Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',bytes)),n=>n.toString(16).padStart(2,'0')).join('')
}
export function toBase64(bytes){
  let raw=''
  for(let i=0;i<bytes.length;i+=16384)raw+=String.fromCharCode(...bytes.subarray(i,i+16384))
  return btoa(raw)
}
export function fromBase64(data){
  if(typeof data!=='string'||!data.length||data.length>Math.ceil(maxImageBytes/3)*4||!/^[A-Za-z0-9+/]+={0,2}$/.test(data))throw Error('Некорректные данные изображения')
  const bytes=Uint8Array.from(atob(data),c=>c.charCodeAt(0))
  if(toBase64(bytes)!==data)throw Error('Некорректный Base64')
  validateJpeg(bytes)
  return bytes
}
export async function verifyImage(id,bytes){
  if(typeof id!=='string'||!imageId.test(id)||await imageHash(bytes)!==id)throw Error('Контрольная сумма изображения не совпадает')
  validateJpeg(bytes)
  return bytes
}
let database
function db(){
  if(!database)database=new Promise((resolve,reject)=>{
    const req=indexedDB.open('moroshka-media-v1',1)
    req.onupgradeneeded=()=>req.result.createObjectStore('images')
    req.onsuccess=()=>{req.result.onversionchange=()=>{req.result.close();database=null};resolve(req.result)}
    req.onerror=()=>reject(req.error)
  }).catch(e=>{database=null;throw e})
  return database
}
async function transaction(mode,action){
  const database=await db()
  return new Promise((resolve,reject)=>{
    const tx=database.transaction('images',mode)
    const req=action(tx.objectStore('images'))
    tx.oncomplete=()=>resolve(req.result)
    tx.onabort=()=>reject(tx.error||req.error||Error('Не удалось сохранить изображение'))
    tx.onerror=()=>{}
  })
}
export async function getImage(id){
  if(!imageId.test(id))throw Error('Некорректное изображение')
  const value=await transaction('readonly',s=>s.get(id))
  return value===undefined?null:verifyImage(id,new Uint8Array(value))
}
export async function saveImage(bytes,id=null){
  id=id||await imageHash(bytes)
  await verifyImage(id,bytes)
  await transaction('readwrite',s=>s.put(bytes,id))
  return id
}
export async function importPhoto(file){
  if(!file||file.size>32*1024*1024)throw Error('Выберите изображение размером до 32 МиБ')
  const image=await createImageBitmap(file)
  try{
    const scale=Math.min(1,maxImageSide/Math.max(image.width,image.height))
    const canvas=document.createElement('canvas')
    canvas.width=Math.max(1,Math.round(image.width*scale))
    canvas.height=Math.max(1,Math.round(image.height*scale))
    const ctx=canvas.getContext('2d',{alpha:false})
    ctx.fillStyle='#fff';ctx.fillRect(0,0,canvas.width,canvas.height);ctx.drawImage(image,0,0,canvas.width,canvas.height)
    for(const quality of [.9,.8,.65,.5,.35]){
      const blob=await new Promise(resolve=>canvas.toBlob(resolve,'image/jpeg',quality))
      if(blob&&blob.size<=maxImageBytes)return saveImage(new Uint8Array(await blob.arrayBuffer()))
    }
    throw Error('Не удалось сжать изображение до 2 МиБ')
  }finally{image.close()}
}
export async function fetchImage(id,api,checkpoint=()=>{}){
  checkpoint()
  const existing=await getImage(id)
  if(existing)return existing
  const response=await api('/api/media/'+id)
  checkpoint()
  if(response.id!==id)throw Error('Получено другое изображение')
  const bytes=await verifyImage(id,fromBase64(response.data))
  checkpoint()
  await saveImage(bytes,id)
  return bytes
}
export async function pullImages(text,api,checkpoint=()=>{}){
  for(const id of imageRefs(text))await fetchImage(id,api,checkpoint)
}
export async function pushImages(text,api,checkpoint=()=>{}){
  const ids=imageRefs(text)
  if(!ids.length)return
  if(ids.length>128)throw Error('Не более 128 изображений в заметке')
  checkpoint()
  const result=await api('/api/media/missing','POST',{ids})
  checkpoint()
  if(!Array.isArray(result.missing)||new Set(result.missing).size!==result.missing.length||result.missing.some(id=>!ids.includes(id)))
    throw Error('Некорректный ответ хранилища изображений')
  for(const id of result.missing){
    const bytes=await getImage(id)
    if(!bytes)throw Error('Не найден локальный файл фотографии')
    checkpoint()
    const stored=await api('/api/media/'+id,'PUT',{data:toBase64(bytes)})
    checkpoint()
    if(stored.id!==id)throw Error('Изображение не подтверждено сервером')
  }
}
