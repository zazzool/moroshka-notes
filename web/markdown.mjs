// Conservative top-level Markdown scanner, shared by browser and server.
const fencePattern=/^ {0,3}(\x60{3,}|~{3,})([^\r\n]*)\r?$/
const headingPattern=/^( {0,3})#[ \t]+([^\r\n]+)/
const anyHeading=/^ {0,3}#{1,6}(?:[ \t]|\r?$)/
const tagPattern=/(?<!\S)#([\p{L}\p{N}_][\p{L}\p{N}_-]{0,39})(?![\p{L}\p{N}_-])/gu
const linkPattern=/(?<!\\)\[\[([^\]\r\n]+)\]\]/g
export const tagName=/^[\p{L}\p{N}_][\p{L}\p{N}_-]{0,39}$/u

export function scan(text){
  let fence=null
  const lines=text.split('\n'), prose=[]
  for(let index=0;index<lines.length;index++){
    const line=lines[index], marker=line.match(fencePattern)
    if(fence){
      if(marker&&marker[1][0]===fence[0]&&marker[1].length>=fence.length&&/^[ \t]*$/.test(marker[2]))fence=null
      continue
    }
    if(marker&&!(marker[1][0]==='\x60'&&marker[2].includes('\x60'))){fence=marker[1];continue}
    if(/^(?: {4}|\t)/.test(line))continue
    prose.push({index,line})
  }
  return {lines,prose,openFence:fence!==null}
}
// Hide same-line code spans while retaining UTF-16 offsets for surgical edits.
export function maskInlineCode(line){
  const runs=[...line.matchAll(/\x60+/g)], ranges=[]
  for(let i=0;i<runs.length;i++){
    let j=i+1
    while(j<runs.length&&runs[j][0].length!==runs[i][0].length)j++
    if(j===runs.length)continue
    ranges.push([runs[i].index,runs[j].index+runs[j][0].length])
    i=j
  }
  let result=line
  for(const [a,b] of ranges.reverse())result=result.slice(0,a)+' '.repeat(b-a)+result.slice(b)
  return result
}
export function titleOf(text){
  for(const {line} of scan(text).prose){
    const m=line.match(headingPattern)
    if(m&&m[2].trim())return m[2].trim()
  }
  return 'Без названия'
}
export function tagsOf(text){
  const tags=new Map()
  for(const {line} of scan(text).prose){
    if(anyHeading.test(line))continue
    for(const m of maskInlineCode(line).matchAll(tagPattern)){
      const key=m[1].toLocaleLowerCase()
      if(!tags.has(key))tags.set(key,m[1])
    }
  }
  return [...tags.values()]
}
export function linksOf(text){
  const links=[]
  for(const {line} of scan(text).prose){
    for(const m of maskInlineCode(line).matchAll(linkPattern)){
      // Don't interpret a code span nested in a candidate as a changed target.
      const raw=line.slice(m.index,m.index+m[0].length)
      if(raw!==m[0])continue
      const target=m[1].trim()
      if(target)links.push(target)
    }
  }
  return links
}
export function renameHeading(text,value){
  const title=value.trim()
  if(!title||/[\u0000-\u001f]/u.test(title))throw Error('Введите заголовок в одну строку.')
  const {lines,prose}=scan(text)
  for(const {index,line} of prose){
    if(!headingPattern.test(line))continue
    lines[index]=line.replace(headingPattern,(_m,indent)=>indent+'# '+title)
    return lines.join('\n')
  }
  return '# '+title+'\n\n'+text
}
export function removeTags(text,remove){
  const {lines,prose}=scan(text)
  for(const {index,line} of prose){
    if(anyHeading.test(line))continue
    const matches=[...maskInlineCode(line).matchAll(tagPattern)].filter(m=>remove(m[1]))
    for(const m of matches.reverse())lines[index]=lines[index].slice(0,m.index)+lines[index].slice(m.index+m[0].length)
  }
  return lines.join('\n')
}
export function addTags(text,values){
  const known=new Set(tagsOf(text).map(t=>t.toLocaleLowerCase())), added=[]
  for(const name of values){
    if(!tagName.test(name))throw Error('Некорректный тег')
    const key=name.toLocaleLowerCase()
    if(!known.has(key)){known.add(key);added.push('#'+name)}
  }
  if(!added.length)return text
  const line=added.join(' ')+'\n'
  if(scan(text).openFence)return line+'\n'+text
  return text+(!text||text.endsWith('\n\n')?'':text.endsWith('\n')?'\n':'\n\n')+line
}
