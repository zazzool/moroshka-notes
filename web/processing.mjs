import {scan,tagsOf,titleOf} from './markdown.mjs'
import {stripImages} from './media-format.mjs'

export const operations={metadata:'Заголовки, теги и тип',summary:'Краткое содержание',
  search:'Расширенный поиск',links:'Предложения связей',ocr:'Распознавание фото'}
export const defaults=Object.fromEntries(Object.keys(operations).map(op=>[op,'local']))
export const modes={local:'На устройстве',server:'Сервер · OpenRouter',off:'Выключено'}
export function normalizeModes(value){
  return Object.fromEntries(Object.keys(operations).map(op=>[op,Object.hasOwn(modes,value?.[op])?value[op]:defaults[op]]))
}
const stop=new Set('это как что для или при так уже ещё еще его она они если есть был быть все всё этот этой который которые можно нужно надо более также the and for with this that are was from have not but you your can will into'.split(' '))
function clean(text){
  return scan(stripImages(text)).prose.map(({line})=>line.trim())
    .filter(line=>line&&!/^#{1,6}(?:\s|$)/.test(line)&&!/^(?:#[\p{L}\p{N}_-]+\s*)+$/u.test(line))
    .join('\n').slice(0,32000)
}
function terms(text){
  return (text.toLowerCase().replaceAll('ё','е').match(/[\p{L}\p{N}]{3,40}/gu)||[])
    .filter(t=>!stop.has(t)&&/\p{L}/u.test(t)).slice(0,5000)
}
function counts(text){
  const out=new Map()
  for(const t of terms(text))out.set(t,(out.get(t)||0)+1)
  return out
}
function similarity(a,b){
  let dot=0,an=0,bn=0
  for(const [t,n] of a){dot+=n*(b.get(t)||0);an+=n*n}
  for(const n of b.values())bn+=n*n
  return an&&bn?dot/Math.sqrt(an*bn):0
}
export function localMetadata(text){
  const body=clean(text)
  const title=(body.split('\n').find(Boolean)||'').replace(/^[-*]\s+(?:\[[ xX]\]\s+)?/,'').trim()
    .slice(0,100).replace(/[\u0000-\u001f]/gu,'').trim()||titleOf(text).slice(0,100)||'Без названия'
  const existing=new Set(tagsOf(text).map(t=>t.toLowerCase()))
  const tags=[...counts(body)].filter(([t])=>t.length>=4&&!existing.has(t))
    .sort((a,b)=>b[1]-a[1]||a[0].localeCompare(b[0])).slice(0,5).map(([t])=>t)
  const lower=body.toLowerCase()
  const category=scan(text).prose.some(({line})=>/^\s*[-*]\s+\[[ xX]\]\s+/.test(line))?'task':
    /(^|\s)(проект|project)(\s|:|$)/u.test(lower)?'project':
    /(^|\s)(идея|idea)(\s|:|$)/u.test(lower)?'idea':
    /https?:\/\/|doi:/u.test(lower)?'reference':'note'
  return {title,category,tags}
}
export function localSummary(text){
  const body=clean(text)||stripImages(text).slice(0,32000)
  if(!body.trim())return 'В заметке нет текста.'
  const freq=counts(body)
  return body.split(/(?<=[.!?…])\s+|\n+/u).map(s=>s.trim()).filter(Boolean).slice(0,200)
    .map((s,i)=>{const ts=[...new Set(terms(s))];return {s,i,score:ts.length?ts.reduce((sum,t)=>sum+(freq.get(t)||0),0)/Math.sqrt(ts.length):0}})
    .sort((a,b)=>b.score-a.score||a.i-b.i).slice(0,3).sort((a,b)=>a.i-b.i).map(v=>v.s.slice(0,600)).join('\n\n')
}
export function localRank(query,notes,exclude=null){
  const q=counts(query.slice(0,32000))
  return [...notes].filter(n=>n.id!==exclude).map(n=>({...n,similarity:similarity(q,counts(clean(n.text)+' '+titleOf(n.text)+' '+tagsOf(n.text).join(' ')))}))
    .filter(n=>n.similarity>0).sort((a,b)=>b.similarity-a.similarity||a.id.localeCompare(b.id)).slice(0,12)
}
