import { createHash, randomUUID } from 'node:crypto'
import { existsSync, mkdirSync, readdirSync, readFileSync, renameSync, rmSync, statSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'

export const validId = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/
export const maxBytes = 512 * 1024
const validRevision = /^[0-9a-f]{64}$/
export class ApiError extends Error {
  constructor(status, message) { super(message); this.status = status }
}
export const digest = text => createHash('sha256').update(text).digest('hex')
export function titleOf(text) {
  return text.match(/^#\s+(.+)$/m)?.[1].trim() || 'Без названия'
}

export function openVault(dir) {
  mkdirSync(dir, { recursive: true, mode: 0o700 })
  function read(id) {
    if (!validId.test(id)) throw new ApiError(400, 'Некорректный идентификатор')
    try {
      const text = readFileSync(join(dir, id + '.md'), 'utf8')
      return { id, text, revision: digest(text) }
    } catch (err) {
      if (err.code === 'ENOENT') return null
      throw err
    }
  }
  function list() {
    return readdirSync(dir).filter(n => n.endsWith('.md') && validId.test(n.slice(0, -3)))
      .sort().map(n => read(n.slice(0, -3))).filter(Boolean)
  }
  // A complete revision inventory, without materializing all note texts at once.
  function manifest() {
    const notes = readdirSync(dir).filter(n => n.endsWith('.md') && validId.test(n.slice(0,-3)))
      .sort().map(n => {
        const note = read(n.slice(0,-3))
        return note && {id:note.id, revision:note.revision}
      }).filter(Boolean)
    return {version:1, notes, deleted:deleted()}
  }
  // Pin every requested body to the observed revision. Reject the entire batch
  // if any note has changed since the manifest; clients can start over safely.
  function readBatch(requested) {
    if (!Array.isArray(requested) || !requested.length || requested.length > 8)
      throw new ApiError(400, 'Запросите от 1 до 8 редакций')
    const ids = new Set()
    for (const item of requested) {
      if (!item || typeof item.id !== 'string' || !validId.test(item.id) ||
          typeof item.revision !== 'string' || !validRevision.test(item.revision) || ids.has(item.id))
        throw new ApiError(400, 'Некорректный идентификатор, ревизия или повтор заметки')
      ids.add(item.id)
    }
    return requested.map(({id,revision}) => {
      const note = read(id)
      if (!note || note.revision !== revision || existsSync(join(dir,id+'.deleted')))
        throw new ApiError(409, 'Библиотека изменилась во время обмена. Повторите синхронизацию.')
      if (Buffer.byteLength(note.text) > maxBytes)
        throw new ApiError(413, 'Заметка превышает допустимый размер 512 КиБ')
      return note
    })
  }
  // Atomic immutable snapshots precede replacement of an existing live note.
  function atomicWrite(path, text) {
    const temporary = path + '.' + randomUUID() + '.tmp'
    try {
      writeFileSync(temporary, text, { mode: 0o600, flag: 'wx', flush: true })
      renameSync(temporary, path)
    } finally { rmSync(temporary, { force: true }) }
  }
  function historyDir(id) {
    if (typeof id !== 'string' || !validId.test(id)) throw new ApiError(400, 'Некорректный идентификатор')
    return join(dir, 'history', id)
  }
  function historyFile(id, revision) {
    if (typeof revision !== 'string' || !validRevision.test(revision)) throw new ApiError(400, 'Некорректная ревизия')
    return join(historyDir(id), revision + '.md')
  }
  function archive(note) {
    const path = historyFile(note.id, note.revision)
    if (existsSync(path)) {
      if (digest(readFileSync(path, 'utf8')) !== note.revision)
        throw new ApiError(500, 'Повреждена сохранённая редакция. Запись остановлена.')
      return
    }
    mkdirSync(historyDir(note.id), { recursive: true, mode: 0o700 })
    atomicWrite(path, note.text)
  }
  function history(id) {
    const folder = historyDir(id)
    if (!existsSync(folder)) return []
    return readdirSync(folder).filter(n => n.endsWith('.md') && validRevision.test(n.slice(0,-3)))
      .map(n => ({ revision:n.slice(0,-3), archivedAt:statSync(join(folder,n)).mtime.toISOString() }))
      .sort((a,b) => b.archivedAt.localeCompare(a.archivedAt) || a.revision.localeCompare(b.revision))
  }
  function historyRead(id, revision) {
    const path = historyFile(id, revision)
    if (!existsSync(path)) throw new ApiError(404, 'Редакция не найдена')
    const text = readFileSync(path, 'utf8')
    if (digest(text) !== revision) throw new ApiError(500, 'Контрольная сумма редакции не совпадает')
    return {id, revision, text}
  }
  function trashRead(id) {
    if (typeof id !== 'string' || !validId.test(id)) throw new ApiError(400, 'Некорректный идентификатор')
    const path = join(dir, id + '.deleted')
    if (!existsSync(path)) throw new ApiError(404, 'Удалённая заметка не найдена')
    const text = readFileSync(path, 'utf8')
    return {id, text, revision:digest(text), modifiedAt:statSync(path).mtime.toISOString()}
  }
  function trashList() {
    return readdirSync(dir).filter(n => n.endsWith('.deleted') && validId.test(n.slice(0,-8)))
      .map(n => {
        const note = trashRead(n.slice(0,-8))
        return {id:note.id, title:titleOf(note.text), revision:note.revision, modifiedAt:note.modifiedAt}
      }).sort((a,b) => b.modifiedAt.localeCompare(a.modifiedAt) || a.id.localeCompare(b.id))
  }
  // Synchronous compare + rename serializes writers inside this single server process.
  function put(id, { text, base }) {
    if (typeof id !== 'string' || !validId.test(id)) throw new ApiError(400, 'Некорректный идентификатор')
    if (typeof text !== 'string' || Buffer.byteLength(text) > maxBytes ||
        !(base === null || (typeof base === 'string' && /^[0-9a-f]{64}$/.test(base)))) {
      throw new ApiError(400, 'Проверьте текст, размер заметки и базовую ревизию')
    }
    if (existsSync(join(dir, id + '.deleted'))) throw new ApiError(409, 'Заметка удалена на другом устройстве')
    const current = read(id)
    const revision = digest(text)
    if (current?.revision === revision) return current
    if ((current?.revision ?? null) !== base) throw new ApiError(409, 'Заметка изменилась на другом устройстве')
    if (current) archive(current)
    atomicWrite(join(dir, id + '.md'), text)
    return { id, text, revision }
  }
  function deleted() {
    return readdirSync(dir).filter(n => n.endsWith('.deleted') && validId.test(n.slice(0, -8)))
      .map(n => ({ id: n.slice(0, -8), revision: digest(readFileSync(join(dir, n), 'utf8')) }))
  }
  function remove(id, base) {
    if (!validId.test(id) || typeof base !== 'string' || !/^[0-9a-f]{64}$/.test(base)) throw new ApiError(400, 'Некорректная ревизия')
    const tombstone = join(dir, id + '.deleted')
    if (existsSync(tombstone)) return { deleted: true }
    const current = read(id)
    if (!current || current.revision !== base) throw new ApiError(409, 'Заметка изменилась на другом устройстве')
    renameSync(join(dir, id + '.md'), tombstone)
    return { deleted: true }
  }
  return { read, list, put, deleted, remove, history, historyRead, trashList, trashRead, manifest, readBatch }
}
