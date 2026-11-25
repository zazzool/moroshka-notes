// Portable backup v1/v2. No connection keys, revisions or browser-specific metadata.
import {imageRefs} from './media-format.mjs'
import {fromBase64,toBase64,verifyImage} from './media-store.mjs'
export const maxArchiveBytes = 32 * 1024 * 1024
const maxNoteBytes = 512 * 1024
const maxNotes = 10000
const format = 'moroshka-notes-backup'
const idPattern = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/
const encoder = new TextEncoder()
function requireValue(condition, message) { if (!condition) throw Error(message) }
async function digest(text) {
  const bytes = await crypto.subtle.digest('SHA-256', encoder.encode(text))
  return Array.from(new Uint8Array(bytes), b => b.toString(16).padStart(2,'0')).join('')
}
function validate(note, ids) {
  requireValue(note && typeof note.id === 'string' && idPattern.test(note.id), 'Некорректный UUID заметки в архиве')
  requireValue(!ids.has(note.id), 'В архиве повторяется UUID заметки')
  requireValue(typeof note.text === 'string' && encoder.encode(note.text).length <= maxNoteBytes, 'Некорректный текст или размер заметки в архиве')
  ids.add(note.id)
}
export async function encodeArchive(notes,readImage=null) {
  const input = [...notes]
  requireValue(input.length <= maxNotes, 'В архиве не более 10 000 заметок')
  const ids = new Set(), output = []
  for (const n of input) {
    validate(n, ids)
    output.push({id:n.id, text:n.text, sha256:await digest(n.text)})
  }
  const refs=[...new Set(output.flatMap(n=>imageRefs(n.text)))]
  const images=[]
  let imageLength=0
  for(const id of refs){
    const bytes=readImage&&await readImage(id)
    requireValue(bytes,'В архиве отсутствует изображение '+id.slice(0,12))
    await verifyImage(id,bytes)
    const data=toBase64(bytes)
    imageLength+=data.length
    requireValue(imageLength<=maxArchiveBytes,'Архив больше 32 МиБ')
    images.push({id,data})
  }
  const bytes = encoder.encode(JSON.stringify({format, version:images.length?2:1, notes:output,...(images.length?{images}:{})}))
  requireValue(bytes.length <= maxArchiveBytes, 'Архив больше 32 МиБ')
  return bytes
}
export async function decodeArchiveBundle(bytes) {
  requireValue(bytes.byteLength <= maxArchiveBytes, 'Архив больше 32 МиБ')
  let data
  try { data = JSON.parse(new TextDecoder('utf-8', {fatal:true}).decode(bytes)) }
  catch { throw Error('Повреждённый JSON или кодировка архива') }
  requireValue(data?.format === format && [1,2].includes(data.version) && Array.isArray(data.notes), 'Неподдерживаемый формат архива Морошки')
  requireValue(data.notes.length <= maxNotes, 'В архиве не более 10 000 заметок')
  const ids = new Set(), verified = []
  for (const n of data.notes) {
    validate(n, ids)
    requireValue(n.sha256 === await digest(n.text), 'Контрольная сумма заметки не совпадает: ' + n.id)
    verified.push({id:n.id, text:n.text, sha256:n.sha256})
  }
  const refs=new Set(verified.flatMap(n=>imageRefs(n.text)))
  const images=new Map()
  requireValue(data.version===1||Array.isArray(data.images),'В архиве нет изображений')
  for(const item of data.version===2?data.images:[]){
    requireValue(item&&refs.has(item.id)&&!images.has(item.id),'Лишнее или повторное изображение в архиве')
    images.set(item.id,await verifyImage(item.id,fromBase64(item.data)))
  }
  requireValue(images.size===refs.size,'В архиве отсутствует изображение')
  return {notes:verified,images}
}
export async function decodeArchive(bytes){return (await decodeArchiveBundle(bytes)).notes}
async function conflictId(note, attempt) {
  const h = await digest('moroshka-backup-v1\n' + note.id + '\n' + note.sha256 + '\n' + attempt)
  return h.slice(0,8)+'-'+h.slice(8,12)+'-8'+h.slice(13,16)+'-8'+h.slice(17,20)+'-'+h.slice(20,32)
}
// Plan before writing. Same archive can be retried; never overwrite existing content.
export async function planRestore(verified, current, blocked = new Set()) {
  const known = new Map(current)
  const reserved = new Set(verified.map(n => n.id))
  const additions = []
  let skipped = 0, conflicts = 0
  for (const n of verified) {
    let id = n.id
    if (!blocked.has(id) && known.has(id) && known.get(id).text === n.text) { skipped++; continue }
    const conflict = known.has(id) || blocked.has(id)
    if (conflict) {
      let found = false
      for (let attempt = 0; attempt < 100; attempt++) {
        const alternative = await conflictId(n, attempt)
        if (reserved.has(alternative) || blocked.has(alternative)) continue
        if (known.has(alternative) && known.get(alternative).text !== n.text) continue
        id = alternative; found = true; break
      }
      requireValue(found, 'Не удалось выделить идентификатор конфликтной копии')
      if (known.has(id)) { skipped++; continue }
      conflicts++
    }
    const note = {id, text:n.text}
    known.set(id, note); additions.push(note)
  }
  return {additions, skipped, conflicts}
}
