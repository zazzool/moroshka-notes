// Revision inventory protocol. Unknown servers retain the legacy full snapshot.
const idPattern = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/
const revisionPattern = /^[0-9a-f]{64}$/
const encoder = new TextEncoder()
const invalid = () => Error('Некорректный ответ сервера при синхронизации')
async function digest(text) {
  const bytes = await crypto.subtle.digest('SHA-256',encoder.encode(text))
  return Array.from(new Uint8Array(bytes),b=>b.toString(16).padStart(2,'0')).join('')
}
export async function pullSnapshot(api, knownBases, checkpoint = ()=>{}) {
  checkpoint()
  let manifest
  try { manifest = await api('/api/sync/manifest') }
  catch(e) {
    if (e.status !== 404) throw e
    checkpoint()
    return api('/api/notes')
  }
  checkpoint()
  if (manifest?.version !== 1 || !Array.isArray(manifest.notes) || !Array.isArray(manifest.deleted))
    throw invalid()
  const ids = new Set()
  for (const item of [...manifest.notes,...manifest.deleted]) {
    if (!item || typeof item.id !== 'string' || !idPattern.test(item.id) ||
        typeof item.revision !== 'string' || !revisionPattern.test(item.revision) || ids.has(item.id))
      throw invalid()
    ids.add(item.id)
  }
  const entries = new Map(manifest.notes.map(({id,revision})=>[id,{id,revision}]))
  const wanted = [...entries.values()].filter(n=>knownBases.get(n.id)!==n.revision)
  for(let i=0;i<wanted.length;i+=8) {
    checkpoint()
    const batch = wanted.slice(i,i+8)
    const expected = new Map(batch.map(n=>[n.id,n.revision]))
    const response = await api('/api/sync/read','POST',{notes:batch})
    checkpoint()
    if (!Array.isArray(response?.notes) || response.notes.length!==batch.length) throw invalid()
    for (const note of response.notes) {
      if (!note || !expected.has(note.id) || expected.get(note.id)!==note.revision ||
          typeof note.text!=='string' || encoder.encode(note.text).length>512*1024 ||
          await digest(note.text)!==note.revision) throw invalid()
      checkpoint()
      expected.delete(note.id)
      entries.set(note.id,{id:note.id,revision:note.revision,text:note.text})
    }
    if (expected.size) throw invalid()
  }
  // Metadata-only entries are usable only while the local base equals the revision.
  return {notes:[...entries.values()],deleted:manifest.deleted}
}
