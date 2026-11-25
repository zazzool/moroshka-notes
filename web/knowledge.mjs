// Metadata proposals are never an automatic rewrite. Preserve the source except
// the explicitly accepted heading, append (or prefix) only validated tags.
import { addTags, removeTags, renameHeading, tagName } from './markdown.mjs'
const tagPattern=tagName
export const categoryLabels={note:'Заметка',task:'Задача',idea:'Идея',project:'Проект',reference:'Материал'}
export function applyProposal(text,{title=null,tags=[],category=null}={}){
  if(title!==null&&(typeof title!=='string'||!title.trim()||title.length>120||/[\u0000-\u001f]/u.test(title)))throw Error('Некорректный заголовок')
  if(!Array.isArray(tags)||tags.length>8||tags.some(t=>typeof t!=='string'||!tagPattern.test(t))||
     (category!==null&&!Object.hasOwn(categoryLabels,category)))throw Error('Некорректные метаданные')
  let result=title===null?text:renameHeading(text,title)
  if(category!==null){
    const desired='type-'+category
    const managed=new Set(Object.keys(categoryLabels).map(k=>'type-'+k))
    result=removeTags(result,t=>managed.has(t.toLocaleLowerCase())&&t.toLocaleLowerCase()!==desired)
  }
  const acceptedTags=category===null?tags:tags.filter(t=>!Object.hasOwn(categoryLabels,t.toLocaleLowerCase().replace(/^type-/,''))||!t.toLocaleLowerCase().startsWith('type-'))
  result=addTags(result,[...acceptedTags,...(category===null?[]:['type-'+category])])
  if(new TextEncoder().encode(result).length>512*1024)throw Error('Предел одной заметки — 512 КиБ')
  return result
}
export function appendLink(text,target,allTitles,existing){
  if(typeof target!=='string'||!target.trim()||/[\[\]\r\n\u0000-\u001f]/u.test(target))throw Error('Название нельзя использовать в вики-ссылке')
  const norm=s=>s.trim().toLocaleLowerCase()
  if(allTitles.filter(t=>norm(t)===norm(target)).length!==1)throw Error('Название неоднозначно; сначала переименуйте заметку')
  if(existing.some(t=>norm(t)===norm(target)))return text
  // Prefixing the link keeps it outside a possibly unclosed code fence.
  const updated='[['+target+']]\n\n'+text
  if(new TextEncoder().encode(updated).length>512*1024)throw Error('Предел одной заметки — 512 КиБ')
  return updated
}
