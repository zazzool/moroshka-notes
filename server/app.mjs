import { createServer } from 'node:http'
import { timingSafeEqual, createHash, randomBytes, randomInt } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { ApiError, openVault, titleOf, maxBytes } from './vault.mjs'
import { intelligence } from './intelligence.mjs'
import { mcpResponse } from './mcp.mjs'

const assets = new Map([
  ['/', ['index.html', 'text/html; charset=utf-8']], ['/app.js', ['app.js', 'text/javascript']],
  ['/backup.mjs', ['backup.mjs', 'text/javascript']],
  ['/sync-pull.mjs', ['sync-pull.mjs', 'text/javascript']],
  ['/knowledge.mjs', ['knowledge.mjs', 'text/javascript']],
  ['/style.css', ['style.css', 'text/css']], ['/icon.png', ['icon.png', 'image/png']], ['/sw.js', ['sw.js', 'text/javascript']],
  ['/assets/cloudberry.webp', ['assets/cloudberry.webp', 'image/webp']],
  ['/assets/hero-day.webp', ['assets/hero-day.webp', 'image/webp']],
  ['/assets/hero-night.webp', ['assets/hero-night.webp', 'image/webp']],
  ['/assets/ideas.webp', ['assets/ideas.webp', 'image/webp']],
  ['/assets/knowledge.webp', ['assets/knowledge.webp', 'image/webp']],
  ['/assets/search.webp', ['assets/search.webp', 'image/webp']],
  ['/assets/balance.webp', ['assets/balance.webp', 'image/webp']],
  ['/assets/manrope.ttf', ['assets/manrope.ttf', 'font/ttf']],
  ['/assets/manrope-OFL.txt', ['assets/manrope-OFL.txt', 'text/plain; charset=utf-8']],
  ['/assets/note.svg', ['assets/note.svg', 'image/svg+xml; charset=utf-8']],
  ['/assets/checkbox.svg', ['assets/checkbox.svg', 'image/svg+xml; charset=utf-8']],
  ['/assets/search.svg', ['assets/search.svg', 'image/svg+xml; charset=utf-8']],
  ['/assets/link.svg', ['assets/link.svg', 'image/svg+xml; charset=utf-8']],
  ['/assets/sparkles.svg', ['assets/sparkles.svg', 'image/svg+xml; charset=utf-8']],
  ['/assets/folder.svg', ['assets/folder.svg', 'image/svg+xml; charset=utf-8']]
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
  const { token, actionsToken, mcpToken, dir, openrouterKey, model, embeddingModel, publicUrl } = options
  if (!token || token.length < 24) throw Error('Задайте MOROSHKA_TOKEN длиной от 24 символов')
  if (actionsToken && (actionsToken.length < 24 || token === actionsToken)) throw Error('Нужен отдельный ключ для ChatGPT Actions длиной от 24 символов')
  if (mcpToken && (mcpToken.length < 24 || mcpToken === token || mcpToken === actionsToken)) throw Error('Нужен отдельный MCP_TOKEN длиной от 24 символов')
  const vault = openVault(dir)
  const ai = intelligence({vault,dir,key:openrouterKey,model,embeddingModel,request})
  // Pairing requests are short-lived and live only in this process.
  const pairing = new Map()
  function cleanPairing() {
    const now = Date.now()
    for (const [code, item] of pairing) if (item.expires <= now) pairing.delete(code)
  }
  function sameOrigin(req) {
    const origin = req.headers.origin
    if (!origin) return true
    try {
      const actual = new URL(origin)
      const expected = publicUrl ? new URL(publicUrl) : null
      return expected ? actual.origin === expected.origin : actual.host === req.headers.host
    } catch { return false }
  }
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
      if (pathname.startsWith('/pair/')) {
        if (req.method !== 'POST' || !sameOrigin(req) ||
            !/^application\/json(?:\s*;|$)/i.test(req.headers['content-type'] || '')) {
          throw new ApiError(403, 'Запрос отклонён')
        }
        cleanPairing()
        if (pathname === '/pair/start') {
          if (pairing.size >= 20) throw new ApiError(429, 'Слишком много запросов на подключение')
          let code
          do { code = String(randomInt(10000000, 100000000)) } while (pairing.has(code))
          const secret = randomBytes(32).toString('hex')
          pairing.set(code, { secret: hash(secret), expires: Date.now() + 120000, approved: false })
          return json(res, 200, { code, secret, expiresIn: 120 })
        }
        if (pathname === '/pair/approve') {
          requireKey(req, token)
          const { code } = await payload(req)
          if (typeof code !== 'string' || !/^\d{8}$/.test(code)) throw new ApiError(400, 'Введите восемь цифр')
          const item = pairing.get(code)
          if (!item || item.approved) throw new ApiError(404, 'Код не найден или уже использован')
          item.approved = true
          return json(res, 200, { approved: true })
        }
        if (pathname === '/pair/claim' || pathname === '/pair/cancel') {
          const { code, secret } = await payload(req)
          if (typeof code !== 'string' || typeof secret !== 'string' || !/^[0-9a-f]{64}$/.test(secret))
            throw new ApiError(400, 'Неверный запрос')
          const item = pairing.get(code)
          if (!item || !timingSafeEqual(item.secret, hash(secret))) throw new ApiError(404, 'Код подключения истёк')
          if (pathname === '/pair/cancel') { pairing.delete(code); return json(res, 200, { cancelled: true }) }
          if (!item.approved) return json(res, 200, { pending: true })
          pairing.delete(code)
          return json(res, 200, { token })
        }
        throw new ApiError(404, 'Не найдено')
      }
      if (pathname === '/mcp') {
        if (!mcpToken) throw new ApiError(404,'MCP не настроен')
        if (!sameOrigin(req)) throw new ApiError(403,'Origin отклонён')
        requireKey(req,mcpToken)
        if (req.method !== 'POST') {res.setHeader('Allow','POST');res.writeHead(405);return res.end()}
        if (!/^application\/json(?:\s*;|$)/i.test(req.headers['content-type']||''))throw new ApiError(415,'Ожидается JSON')
        const message=mcpResponse(await payload(req),req.headers,vault)
        if(message.body===null){res.writeHead(message.status);return res.end()}
        return json(res,message.status,message.body)
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
        if (get && pathname === '/api/notes') return json(res, 200, { notes: vault.list(), deleted: vault.deleted() })
        if (get && pathname === '/api/sync/manifest') return json(res, 200, vault.manifest())
        if (req.method === 'POST' && pathname === '/api/sync/read') {
          const {notes} = await payload(req)
          return json(res, 200, {notes:vault.readBatch(notes)})
        }
        if (get && pathname === '/api/trash') return json(res, 200, { notes: vault.trashList() })
        if (get && pathname.startsWith('/api/trash/')) {
          return json(res, 200, vault.trashRead(pathname.slice('/api/trash/'.length)))
        }
        if (get && pathname.startsWith('/api/history/')) {
          const segments = pathname.slice('/api/history/'.length).split('/')
          if (segments.length === 1) return json(res, 200, { revisions: vault.history(segments[0]) })
          if (segments.length === 2) return json(res, 200, vault.historyRead(segments[0], segments[1]))
          throw new ApiError(400, 'Некорректный путь редакции')
        }
        if (req.method === 'PUT' && pathname.startsWith('/api/notes/')) {
          const input = await payload(req)
          return json(res, 200, vault.put(pathname.slice('/api/notes/'.length), input))
        }
        if (req.method === 'DELETE' && pathname.startsWith('/api/notes/')) {
          const { base } = await payload(req)
          return json(res, 200, vault.remove(pathname.slice('/api/notes/'.length), base))
        }
        if (get && pathname === '/api/ai/status') return json(res,200,ai.status())
        if (req.method === 'POST' && pathname === '/api/ai/analyze') return json(res,200,await ai.analyze(await payload(req)))
        if (req.method === 'POST' && pathname === '/api/ai/index') return json(res,200,await ai.index(await payload(req)))
        if (req.method === 'POST' && pathname === '/api/ai/search') return json(res,200,await ai.search(await payload(req)))
        if (req.method === 'POST' && pathname === '/api/ai/related') return json(res,200,ai.related(await payload(req)))
        if (req.method === 'DELETE' && pathname === '/api/ai/index') return json(res,200,ai.clear())
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
  application({ token: e.MOROSHKA_TOKEN, actionsToken: e.ACTIONS_TOKEN, mcpToken:e.MCP_TOKEN, embeddingModel:e.OPENROUTER_EMBEDDING_MODEL, publicUrl: e.PUBLIC_URL,
    dir: e.VAULT_DIR || 'vault', openrouterKey: e.OPENROUTER_API_KEY, model: e.OPENROUTER_MODEL
  }).listen(Number(e.PORT || 8090), e.HOST || '127.0.0.1', () => console.log('Moroshka Notes запущен'))
}
