import { createServer } from 'node:http'
import { timingSafeEqual, createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { ApiError, openVault, titleOf, maxBytes } from './vault.mjs'

const assets = new Map([
  ['/', ['index.html', 'text/html; charset=utf-8']], ['/app.js', ['app.js', 'text/javascript']],
  ['/style.css', ['style.css', 'text/css']], ['/icon.png', ['icon.png', 'image/png']], ['/sw.js', ['sw.js', 'text/javascript']]
])
const hash = value => createHash('sha256').update(value).digest()
function json(res, status, body) {
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' })
  res.end(JSON.stringify(body))
}
async function payload(req) {
  const parts = []
  let bytes = 0
  for await (const part of req) {
    bytes += part.length
    if (bytes > maxBytes * 6 + 1024) throw new ApiError(413, 'Слишком большой запрос')
    parts.push(part)
  }
  try {
    const body = JSON.parse(Buffer.concat(parts).toString('utf8'))
    if (body && typeof body === 'object' && !Array.isArray(body)) return body
  } catch {}
  throw new ApiError(400, 'Ожидается JSON')
}
export function application(options, request = fetch) {
  const { token, actionsToken, dir, openrouterKey, model, publicUrl } = options
  if (!token || token.length < 24) throw Error('Задайте MOROSHKA_TOKEN длиной от 24 символов')
  if (actionsToken && (actionsToken.length < 24 || token === actionsToken)) throw Error('Нужен отдельный ключ для ChatGPT Actions длиной от 24 символов')
  const vault = openVault(dir)
  function requireKey(req, key) {
    if (!key || !timingSafeEqual(hash(req.headers.authorization || ''), hash('Bearer ' + key))) {
      throw new ApiError(401, 'Проверьте ключ подключения')
    }
  }
  return createServer({ requestTimeout: 30000, headersTimeout: 15000 }, async (req, res) => {
    res.setHeader('Cache-Control', 'no-store')
    res.setHeader('X-Content-Type-Options', 'nosniff')
    res.setHeader('Referrer-Policy', 'no-referrer')
    res.setHeader('Content-Security-Policy', "default-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")
    try {
      const { pathname, searchParams } = new URL(req.url, 'http://localhost')
      const get = req.method === 'GET'
      if (get && assets.has(pathname)) {
        const [name, type] = assets.get(pathname)
        const file = readFileSync(new URL('../web/' + name, import.meta.url))
        res.writeHead(200, { 'Content-Type': type }); return res.end(file)
      }
      if (get && pathname === '/healthz') return json(res, 200, { ok: true })
      if (get && pathname === '/openapi.json' && actionsToken && publicUrl) {
        const schema = JSON.parse(readFileSync(new URL('./actions.json', import.meta.url)))
        schema.servers = [{ url: publicUrl }]
        return json(res, 200, schema)
      }
      if (pathname.startsWith('/actions/')) {
        requireKey(req, actionsToken)
        if (get && pathname === '/actions/search') {
          const q = (searchParams.get('q') || '').trim().toLocaleLowerCase()
          if (!q) throw new ApiError(400, 'Нужен поисковый запрос')
          const notes = vault.list().filter(n => n.text.toLocaleLowerCase().includes(q))
          return json(res, 200, { notes: notes.slice(0, 20).map(n => ({ id: n.id, title: titleOf(n.text) })) })
        }
        if (get && pathname.startsWith('/actions/notes/')) {
          const note = vault.read(pathname.slice('/actions/notes/'.length))
          if (note) return json(res, 200, note)
        }
      } else if (pathname.startsWith('/api/')) {
        requireKey(req, token)
        if (get && pathname === '/api/notes') return json(res, 200, { notes: vault.list() })
        if (req.method === 'PUT' && pathname.startsWith('/api/notes/')) {
          const input = await payload(req)
          return json(res, 200, vault.put(pathname.slice('/api/notes/'.length), input))
        }
        if (req.method === 'POST' && pathname === '/api/summary') {
          if (!openrouterKey || !model) throw new ApiError(503, 'Владелец сервера ещё не подключил OpenRouter')
          const { id, revision } = await payload(req)
          const note = vault.read(id)
          if (!note) throw new ApiError(404, 'Заметка не найдена')
          if (note.revision !== revision) throw new ApiError(409, 'Сначала синхронизируйте актуальную версию заметки')
          const reply = await request('https://openrouter.ai/api/v1/chat/completions', {
            method: 'POST', headers: { Authorization: 'Bearer ' + openrouterKey, 'Content-Type': 'application/json' },
            signal: AbortSignal.timeout(45000),
            body: JSON.stringify({ model, max_tokens: 500, messages: [
              { role: 'system', content: 'Кратко перескажи предоставленную заметку на языке автора.' },
              { role: 'user', content: note.text }
            ] })
          })
          if (!reply.ok) throw new ApiError(502, 'OpenRouter отклонил запрос')
          const summary = (await reply.json()).choices?.[0]?.message?.content
          if (typeof summary !== 'string') throw new ApiError(502, 'OpenRouter вернул пустой ответ')
          return json(res, 200, { summary })
        }
      }
      throw new ApiError(404, 'Не найдено')
    } catch (err) {
      json(res, err.status || 500, { error: err.status ? err.message : 'Не удалось выполнить запрос' })
    }
  })
}
if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const e = process.env
  application({ token: e.MOROSHKA_TOKEN, actionsToken: e.ACTIONS_TOKEN, publicUrl: e.PUBLIC_URL,
    dir: e.VAULT_DIR || 'vault', openrouterKey: e.OPENROUTER_API_KEY, model: e.OPENROUTER_MODEL
  }).listen(Number(e.PORT || 8090), e.HOST || '127.0.0.1', () => console.log('Moroshka Notes запущен'))
}
