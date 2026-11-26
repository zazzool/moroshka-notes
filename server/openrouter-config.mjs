import {readFileSync,writeFileSync,renameSync,rmSync,lstatSync} from 'node:fs'
import {join} from 'node:path'
import {randomUUID} from 'node:crypto'
import {ApiError} from './vault.mjs'

// An editable server-side override. Env variables are migration defaults only;
// an explicit disconnect persists even when the old environment still has a key.
export const recommendedModels=Object.freeze({
  model:'openai/gpt-4.1-mini',embeddingModel:'openai/text-embedding-3-small',visionModel:'openai/gpt-4.1-mini'
})
const fields=Object.keys(recommendedModels)
const modelId=/^[a-zA-Z0-9][a-zA-Z0-9._:/-]{0,199}$/
const requireValue=(ok,status,message)=>{if(!ok)throw new ApiError(status,message)}
function validateModel(value){
  requireValue(typeof value==='string'&&(!value||modelId.test(value)),400,'Некорректный идентификатор модели')
  return value
}
function validateKey(value){
  requireValue(typeof value==='string'&&value.length>=16&&value.length<=512&&/^[A-Za-z0-9._-]+$/.test(value),400,'Некорректный API-ключ OpenRouter')
  return value
}
export function openRouterConfig({dir,key,model,embeddingModel,visionModel,request=fetch}){
  const file=join(dir,'.openrouter-settings-v1.json')
  const seed={revision:'environment',key:key||null,model:model||recommendedModels.model,
    embeddingModel:embeddingModel||recommendedModels.embeddingModel,visionModel:visionModel||recommendedModels.visionModel}
  // Preserve deliberately disabled/undefined models in migrated installations
  // with an existing environment key. New installations receive useful suggestions.
  if(key){seed.model=model||'';seed.embeddingModel=embeddingModel||'';seed.visionModel=visionModel||''}
  let value=seed
  try{
    const stat=lstatSync(file)
    if(!stat.isFile()||stat.size>8192||(stat.mode&0o077)!==0)throw Error('Unsafe OpenRouter config')
    const stored=JSON.parse(readFileSync(file,'utf8'))
    if(stored.version!==1||typeof stored.revision!=='string'||stored.revision.length>100)throw Error('Invalid OpenRouter config')
    if(stored.key!==null)validateKey(stored.key)
    fields.forEach(f=>validateModel(stored[f]))
    value={revision:stored.revision,key:stored.key,...Object.fromEntries(fields.map(f=>[f,stored[f]]))}
  }catch(e){if(e.code!=='ENOENT')throw Error('Повреждены или небезопасны настройки OpenRouter; проверьте сервер')}
  const current=()=>({...value})
  const status=()=>({revision:value.revision,configured:!!value.key,
    source:value.revision==='environment'?'environment':'application',
    ...Object.fromEntries(fields.map(f=>[f,value[f]])),recommended:{...recommendedModels}})
  async function verify(candidate){
    requireValue(candidate,409,'Сначала укажите ключ OpenRouter')
    let reply
    try{
      reply=await request('https://openrouter.ai/api/v1/key',{
        method:'GET',headers:{Authorization:'Bearer '+candidate},signal:AbortSignal.timeout(15000),redirect:'error'})
    }catch{throw new ApiError(502,'Не удалось связаться с OpenRouter')}
    try{
      if(reply.status===401||reply.status===403)throw new ApiError(400,'OpenRouter отклонил API-ключ')
      requireValue(reply.ok,502,'Не удалось проверить ключ OpenRouter')
    }finally{await reply.body?.cancel().catch(()=>{})}
    return {valid:true}
  }
  async function update(input){
    requireValue(input&&input.revision===value.revision,409,'Настройки изменились на другом устройстве. Откройте их снова.')
    requireValue(['keep','replace','remove'].includes(input.keyAction),400,'Выберите действие с ключом')
    const next={...value,revision:randomUUID()}
    for(const field of fields)next[field]=validateModel(input[field])
    if(input.keyAction==='replace'){
      next.key=validateKey(input.apiKey)
      await verify(next.key)
    }else if(input.keyAction==='remove'){next.key=null}
    requireValue(input.revision===value.revision,409,'Настройки изменились на другом устройстве')
    const temp=file+'.'+randomUUID()+'.tmp'
    try{
      writeFileSync(temp,JSON.stringify({version:1,...next}),{mode:0o600,flag:'wx',flush:true})
      renameSync(temp,file)
    }finally{rmSync(temp,{force:true})}
    value=next
    return status()
  }
  return {current,status,update,verify:()=>verify(value.key)}
}
