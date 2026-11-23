import { createHash, randomUUID } from 'node:crypto'
import { mkdirSync, readdirSync, readFileSync, renameSync, rmSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'

export const validId = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/
export const maxBytes = 512 * 1024
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
  // Synchronous compare + rename serializes writers inside this single server process.
  function put(id, { text, base }) {
    if (typeof text !== 'string' || Buffer.byteLength(text) > maxBytes ||
        !(base === null || (typeof base === 'string' && /^[0-9a-f]{64}$/.test(base)))) {
      throw new ApiError(400, 'Проверьте текст, размер заметки и базовую ревизию')
    }
    const current = read(id)
    const revision = digest(text)
    if (current?.revision === revision) return current
    if ((current?.revision ?? null) !== base) throw new ApiError(409, 'Заметка изменилась на другом устройстве')
    const temporary = join(dir, '.' + randomUUID() + '.tmp')
    try {
      writeFileSync(temporary, text, { mode: 0o600, flag: 'wx', flush: true })
      renameSync(temporary, join(dir, id + '.md'))
    } finally { rmSync(temporary, { force: true }) }
    return { id, text, revision }
  }
  return { read, list, put }
}
