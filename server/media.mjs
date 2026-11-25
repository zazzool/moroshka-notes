import {mkdirSync,readFileSync,writeFileSync,renameSync,rmSync,existsSync} from 'node:fs'
import {join} from 'node:path'
import {createHash,randomUUID} from 'node:crypto'
import {ApiError} from './vault.mjs'
import {imageId,imageRefs,maxImageBytes,validateJpeg} from '../web/media-format.mjs'
const hash=bytes=>createHash('sha256').update(bytes).digest('hex')
export function openMedia(dir){
  const folder=join(dir,'media-v1')
  function file(id){
    if(typeof id!=='string'||!imageId.test(id))throw new ApiError(400,'Некорректный идентификатор изображения')
    return join(folder,id+'.jpg')
  }
  function read(id){
    const path=file(id)
    try{
      const bytes=readFileSync(path)
      if(bytes.length>maxImageBytes||hash(bytes)!==id)throw new ApiError(500,'Повреждено изображение')
      return bytes
    }catch(e){if(e.code==='ENOENT')throw new ApiError(404,'Изображение не найдено');throw e}
  }
  function exists(id){return existsSync(file(id))}
  function missing(ids){
    if(!Array.isArray(ids)||ids.length>128||new Set(ids).size!==ids.length)
      throw new ApiError(400,'Ожидается до 128 разных идентификаторов')
    return ids.filter(id=>!exists(id))
  }
  function put(id,data){
    const path=file(id)
    if(typeof data!=='string'||data.length>Math.ceil(maxImageBytes/3)*4||!data.length||
      !/^[A-Za-z0-9+/]+={0,2}$/.test(data))throw new ApiError(400,'Ожидается JPEG в Base64')
    const bytes=Buffer.from(data,'base64')
    if(bytes.toString('base64')!==data||hash(bytes)!==id)throw new ApiError(400,'Контрольная сумма изображения не совпадает')
    try{validateJpeg(bytes)}catch(e){throw new ApiError(400,e.message)}
    if(exists(id)){read(id);return {id}}
    mkdirSync(folder,{recursive:true,mode:0o700})
    const tmp=path+'.'+randomUUID()+'.tmp'
    try{writeFileSync(tmp,bytes,{mode:0o600,flag:'wx',flush:true});renameSync(tmp,path)}
    finally{rmSync(tmp,{force:true})}
    return {id}
  }
  function checkRefs(text){
    if(typeof text!=='string')return
    const refs=imageRefs(text)
    if(refs.length>128)throw new ApiError(400,'Не более 128 изображений в заметке')
    if(refs.some(id=>!exists(id)))throw new ApiError(409,'Сначала загрузите изображения заметки')
  }
  return {read,put,missing,checkRefs}
}
