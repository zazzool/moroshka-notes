// Metadata proposals are never an automatic rewrite. Preserve the source except
// the explicitly accepted heading, append (or prefix) only validated tags.
const tagPattern=/^[\p{L}\p{N}_][\p{L}\p{N}_-]{0,39}$/u
export const categoryLabels={note:'Заметка',task:'Задача',idea:'Идея',project:'Проект',reference:'Материал'}
export function applyProposal(text,{title=null,tags=[],category=null}={}){
  if(title!==null&&(typeof title!=='string'||!title.trim()||title.length>120||/[\u0000-\u001f]/u.test(title)))throw Error('Некорректный заголовок')
  if(!Array.isArray(tags)||tags.some(t=>typeof t!=='string'||!tagPattern.test(t))||
     (category!==null&&!Object.hasOwn(categoryLabels,category)))throw Error('Некорректные метаданные')
  let fence=null, found=false
  const present=new Set()
  const lines=text.split('\n').map(line=>{
    const trimmed=line.trimStart(), marker=trimmed.match(/^(\x60{3,}|~{3,})/)?.[1]
    if(marker){
      if(!fence)fence=marker
      else if(marker[0]===fence[0]&&marker.length>=fence.length)fence=null
      return line
    }
    if(fence)return line
    if(/^#[ \t]+[^\r\n]+/.test(line)){
      if(!found){found=true;return title===null?line:line.replace(/^#[ \t]+[^\r\n]+/,()=> '# '+title.trim())}
      return line
    }
    for(const m of line.matchAll(/(?<!\S)#([\p{L}\p{N}_][\p{L}\p{N}_-]{0,39})(?![\p{L}\p{N}_-])/gu))
      present.add(m[1].toLocaleLowerCase())
    return line
  })
  let result=lines.join('\n')
  if(title!==null&&!found)result='# '+title.trim()+'\n\n'+result
  const added=[]
  for(const t of [...tags,...(category===null?[]:['type-'+category])]){
    const key=t.toLocaleLowerCase()
    if(!present.has(key)){present.add(key);added.push('#'+t)}
  }
  if(added.length){
    const line=added.join(' ')+'\n'
    result=fence?line+'\n'+result:result+(result.endsWith('\n\n')?'':result.endsWith('\n')?'\n':'\n\n')+line
  }
  if(new TextEncoder().encode(result).length>512*1024)throw Error('Предел одной заметки — 512 КиБ')
  return result
}
export function appendLink(text,target,allTitles,existing){
  if(typeof target!=='string'||!target.trim()||/[\[\]\r\n\u0000-\u001f]/u.test(target))throw Error('Название нельзя использовать в вики-ссылке')
  const norm=s=>s.trim().toLocaleLowerCase()
  if(allTitles.filter(t=>norm(t)===norm(target)).length!==1)throw Error('Название неоднозначно; сначала переименуйте заметку')
  if(existing.some(t=>norm(t)===norm(target)))return text
  // Prefixing the link keeps it outside a possibly unclosed code fence.
  return '[['+target+']]\n\n'+text
}
