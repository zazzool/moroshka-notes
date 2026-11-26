// Build-time installation of pinned browser OCR distributions. No npm scripts,
// no remote code at runtime. Verify package bytes before extracting selected files.
import {createHash} from 'node:crypto'
import {mkdtempSync,mkdirSync,readFileSync,writeFileSync,rmSync,copyFileSync} from 'node:fs'
import {join,resolve} from 'node:path'
import {tmpdir} from 'node:os'
import {execFileSync} from 'node:child_process'
const target=resolve(process.argv[2]||'web/ocr-v1')
const packages=[
  {name:'tesseract.js',version:'6.0.1',sha512:'/sPvMvrCtgxnNRCjbTYbr7BRu0yfWDsMZQ2a/T5aN/L1t8wUQN6tTWv6p6FwzpoEBA0jrN2UD2SX4QQFRdoDbA==',
    files:{'package/dist/tesseract.min.js':'tesseract.min.js','package/dist/worker.min.js':'worker.min.js','package/LICENSE.md':'tesseract-js-LICENSE.txt'}},
  {name:'tesseract.js-core',version:'6.0.0',sha512:'1Qncm/9oKM7xgrQXZXNB+NRh19qiXGhxlrR8EwFbK5SaUbPZnS5OMtP/ghtqfd23hsr1ZvZbZjeuAGcMxd/ooA==',
    files:{'package/tesseract-core-lstm.wasm.js':'tesseract-core-lstm.wasm.js','package/tesseract-core-lstm.wasm':'tesseract-core-lstm.wasm',
      'package/tesseract-core-simd-lstm.wasm.js':'tesseract-core-simd-lstm.wasm.js','package/tesseract-core-simd-lstm.wasm':'tesseract-core-simd-lstm.wasm','package/LICENSE':'tesseract-core-LICENSE.txt'}}
]
const models={
  eng:{size:4113088,hash:'7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2'},
  rus:{size:3861738,hash:'e16e5e036cce1d9ec2b00063cf8b54472625b9e14d893a169e2b0dedeb4df225'}
}
const modelBase='https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/65727574dfcd264acbb0c3e07860e4e9e9b22185/'
async function download(url,max){
  let error
  for(let i=0;i<3;i++){
    try{
      const response=await fetch(url,{redirect:'error',signal:AbortSignal.timeout(90000)})
      if(!response.ok)throw Error('HTTP '+response.status)
      let total=0;const chunks=[]
      for await(const chunk of response.body){total+=chunk.byteLength;if(total>max)throw Error('Asset too large');chunks.push(chunk)}
      return Buffer.concat(chunks,total)
    }catch(e){error=e}
  }
  throw error
}
const work=mkdtempSync(join(tmpdir(),'moroshka-ocr-'))
try{
  mkdirSync(target,{recursive:true})
  for(const pkg of packages){
    const url='https://registry.npmjs.org/'+pkg.name+'/-/'+pkg.name+'-'+pkg.version+'.tgz'
    const bytes=await download(url,80*1024*1024)
    if(createHash('sha512').update(bytes).digest('base64')!==pkg.sha512)throw Error('Package integrity mismatch: '+pkg.name)
    const archive=join(work,pkg.name+'.tgz')
    writeFileSync(archive,bytes)
    const listing=new Set(execFileSync('tar',['-tzf',archive],{encoding:'utf8'}).split('\n'))
    for(let [member,dest] of Object.entries(pkg.files)){
      if(!listing.has(member)&&dest.endsWith('LICENSE.txt')){
        member=['package/LICENSE','package/LICENSE.md','package/LICENSE.txt'].find(p=>listing.has(p))
      }
      if(!member||!listing.has(member))throw Error('Missing upstream asset '+dest)
      const content=execFileSync('tar',['-xOzf',archive,member],{maxBuffer:32*1024*1024})
      writeFileSync(join(target,dest),content)
    }
  }
  for(const [name,model] of Object.entries(models)){
    const bytes=await download(modelBase+name+'.traineddata',model.size)
    if(bytes.length!==model.size||createHash('sha256').update(bytes).digest('hex')!==model.hash)throw Error('Model hash mismatch: '+name)
    writeFileSync(join(target,name+'.traineddata'),bytes)
  }
  const license=await download(modelBase+'LICENSE',20000)
  // The pinned upstream Git blob also authenticates the license file.
  if(createHash('sha1').update('blob '+license.length+'\0').update(license).digest('hex')!=='d645695673349e3947e8e5ae42332d0ac3164cd7')
    throw Error('Model license mismatch')
  writeFileSync(join(target,'tessdata-LICENSE.txt'),license)
  console.log('Verified browser OCR assets installed')
}finally{rmSync(work,{recursive:true,force:true})}
