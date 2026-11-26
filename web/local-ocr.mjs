// Only fixed, same-origin, build-time verified assets are executable.
export const ocrAssets=['tesseract.min.js','worker.min.js',
  'tesseract-core-lstm.wasm.js','tesseract-core-lstm.wasm',
  'tesseract-core-simd-lstm.wasm.js','tesseract-core-simd-lstm.wasm',
  'eng.traineddata','rus.traineddata'].map(name=>'/ocr-v1/'+name)
const cacheName='moroshka-ocr-v1'
export async function ocrReady(){
  if(!('caches' in globalThis))return false
  const cache=await caches.open(cacheName)
  for(const path of ocrAssets)if(!await cache.match(path))return false
  return true
}
export async function installOcr(){
  if(!('caches' in globalThis)||!('Worker' in globalThis)||!('WebAssembly' in globalThis))
    throw Error('Браузер не поддерживает локальный OCR')
  const cache=await caches.open(cacheName)
  // No library contents or keys are included in these static GET requests.
  for(const path of ocrAssets){
    if(await cache.match(path))continue
    const response=await fetch(path,{credentials:'same-origin',signal:AbortSignal.timeout(60000)})
    if(!response.ok)throw Error('Локальный OCR не установлен на сервере. Обновите сервер.')
    await cache.put(path,response)
  }
}
let loading
function library(){
  if(globalThis.Tesseract)return Promise.resolve(globalThis.Tesseract)
  if(!loading)loading=new Promise((resolve,reject)=>{
    const script=document.createElement('script')
    script.src='/ocr-v1/tesseract.min.js'
    script.onload=()=>globalThis.Tesseract?.createWorker?resolve(globalThis.Tesseract):reject(Error('Не удалось загрузить OCR'))
    script.onerror=()=>reject(Error('Не удалось загрузить OCR'))
    document.head.append(script)
  }).catch(e=>{loading=null;throw e})
  return loading
}
export async function recognizeLocal(bytes){
  if(!await ocrReady())throw Error('Сначала загрузите локальный OCR в настройках')
  const tess=await library()
  let worker, timer, expired=false
  const create=tess.createWorker(['rus','eng'],1,{
    workerPath:'/ocr-v1/worker.min.js',corePath:'/ocr-v1',langPath:'/ocr-v1',
    workerBlobURL:false,gzip:false,cacheMethod:'none',legacyCore:false,legacyLang:false
  }).then(value=>{if(expired){value.terminate();throw Error('Время OCR истекло')}worker=value;return value})
  const timeout=new Promise((_,reject)=>{timer=setTimeout(()=>{expired=true;reject(Error('Локальное распознавание превысило лимит времени'))},120000)})
  try{
    await Promise.race([create,timeout])
    const reply=await Promise.race([worker.recognize(new Blob([bytes],{type:'image/jpeg'})),timeout])
    return String(reply.data.text||'').trim().slice(0,64000)
  }finally{clearTimeout(timer);if(worker)await worker.terminate()}
}
