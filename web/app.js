import { encodeArchive, decodeArchive, planRestore, maxArchiveBytes } from '/backup.mjs'

const $ = id => document.getElementById(id)
const prefix = 'moroshka.note.'
const captureKey = 'moroshka.captureDraft'
const notes = new Map()
let selected, view = 'notes', dirtyEditor = false, busy = false
let token = localStorage.getItem('moroshka.key') || sessionStorage.getItem('moroshka.key') || ''
let pairingGeneration = 0
let activePairing = null
let archiveGeneration = 0
let archiveText = null
let archiveSourceId = null
const title = n => n.text.match(/^#\s+(.+)$/m)?.[1].trim() || 'Без названия'
const links = text => [...text.matchAll(/\[\[([^\]\n]+)\]\]/g)].map(m => m[1].trim())
const norm = text => text.trim().toLocaleLowerCase()
function put(note) {
  if (new Blob([note.text]).size > 512 * 1024) throw Error('Предел одной заметки — 512 КиБ')
  localStorage.setItem(prefix + note.id, JSON.stringify(note))
  notes.set(note.id, note)
}
function create(text) {
  const n = { id: crypto.randomUUID(), text, base: null, dirty: true }
  put(n)
  return n
}
function alertText(heading, message) {
  $('msgTitle').textContent = heading; $('msgText').textContent = message
  if (!$('message').open) $('message').showModal()
}
function report(e) { alertText('Moroshka Notes', e.message) }
function el(tag, text, cls) {
  const e = document.createElement(tag); e.textContent = text; if (cls) e.className = cls
  return e
}
function action(label, callback) {
  const b = el('button', label); b.onclick = () => { try { callback() } catch (error) { report(error) } }; return b
}
function persistEditor() {
  if (!dirtyEditor || !selected) return
  put({ ...notes.get(selected), text: $('body').value, dirty: true })
  dirtyEditor = false; $('saved').textContent = 'Сохранено на устройстве'
}
function select(id) {
  persistEditor(); selected = id; $('reader').hidden = !id
  if (id) { $('body').value = notes.get(id).text; $('saved').textContent = notes.get(id).dirty ? 'На устройстве' : 'Синхронизировано' }
  render()
}
function related() {
  const group = $('related'); group.replaceChildren()
  if (!selected) return
  const n = notes.get(selected)
  for (const [heading, matches] of [
    ['Ссылки', [...new Set(links(n.text))].map(name => ({name, ids: [...notes.values()].filter(v => norm(title(v)) === norm(name)).map(v => v.id)}))],
    ['Обратные ссылки', [...notes.values()].filter(v => v.id !== selected && links(v.text).some(s => norm(s) === norm(title(n)))).map(v => ({ name:title(v), ids:[v.id] }))]
  ]) {
    if (!matches.length) continue
    const block = el('div','','linkgroup'); block.append(el('h3',heading))
    for (const {name, ids} of matches) {
      if (!ids.length) block.append(action('＋ ' + name, () => select(create('# ' + name + '\n').id)))
      ids.forEach((id, i) => block.append(action(ids.length > 1 ? name + ' · ' + (i+1) : name, () => select(id))))
    }
    group.append(block)
  }
}
function tagsOf(text) {
  const tags = new Set()
  let fence = null
  for (const line of text.split('\n')) {
    const marker = line.trimStart().match(/^(\x60{3,}|~{3,})/)?.[1]
    if (marker) {
      if (!fence) fence = marker
      else if (marker[0] === fence[0] && marker.length >= fence.length) fence = null
      continue
    }
    if (fence || /^\s*#\s/.test(line)) continue
    for (const match of line.matchAll(/(?<!\S)#([\p{L}\p{N}_][\p{L}\p{N}_-]{0,39})(?![\p{L}\p{N}_-])/gu)) tags.add(match[1])
  }
  return [...tags]
}
function render() {
  $('capture').hidden = view !== 'notes'
  const list = $('items'); list.replaceChildren()
  const q = norm($('search').value)
  let count = 0
  for (const n of [...notes.values()].sort((a,b) => title(a).localeCompare(title(b),'ru'))) {
    if (!norm(n.text).includes(q)) continue
    if (view === 'notes') {
      const b = action('', () => select(n.id)); b.className = 'note'
      if (n.id === selected) b.setAttribute('aria-current','true')
      const symbol = el('span','','note-symbol'); symbol.setAttribute('aria-hidden','true')
      const body = el('span','','note-body')
      body.append(el('strong',title(n)), el('small',n.text.split('\n').filter(l => l.trim() && !l.startsWith('# ')).join(' ') || 'Markdown'))
      const tags = tagsOf(n.text)
      if (tags.length) {
        const group = el('span','','note-tags')
        for (const tag of tags.slice(0,5)) group.append(el('span','#'+tag,'chip'))
        if (tags.length > 5) group.append(el('span','+'+(tags.length-5),'chip'))
        body.append(group)
      }
      b.append(symbol,body)
      list.append(b); count++
    } else {
      n.text.split('\n').forEach((line,i) => {
        const m = line.match(/^\s*[-*] \[([ xX])\] (.+)/); if (!m) return
        const row = el('div','','task'); const check = el('input',''); check.type = 'checkbox'; check.checked = m[1] !== ' '
        check.setAttribute('aria-label', m[2])
        check.onchange = () => {
          try {
            const lines = n.text.split('\n'); lines[i] = lines[i].replace(/\[[ xX]\]/,check.checked ? '[x]' : '[ ]')
            put({...n,text:lines.join('\n'),dirty:true}); if (selected === n.id) $('body').value = notes.get(n.id).text
            render()
          } catch(e) { report(e); render() }
        }
        const b = action('', () => select(n.id)); b.append(el('span',m[2],'taskText'),el('small',title(n))); row.append(check,b); list.append(row); count++
      })
    }
  }
  $('count').textContent = count
  if (!count) {
    const box = el('div','','empty')
    const art = document.createElement('img')
    art.src = q ? '/assets/search.webp' : view === 'tasks' ? '/assets/balance.webp' : '/assets/ideas.webp'
    art.alt = ''; art.loading = 'lazy'; art.className = 'empty-art'
    box.append(art)
    box.append(el('h2',q ? 'Совпадений нет' : view === 'tasks' ? 'Задач пока нет' : 'Первая заметка'),
      el('p',q ? 'Попробуйте другое слово или тег.' : view === 'tasks' ? 'Строки вида «- [ ] Прочитать статью» появятся здесь.' : 'Запишите мысль. Добавьте [[ссылку]], чтобы связать её с другой заметкой.'))
    if (!q && view === 'notes') box.append(action('Создать заметку',newNote))
    list.append(box)
  }
  related()
}
function newNote() {
  const n = create('# Новая заметка\n\n'); select(n.id); $('body').focus(); $('body').setSelectionRange(2,15)
}
async function api(path, method='GET', data) {
  const res = await fetch(path,{ method, headers:{Authorization:'Bearer '+token,'Content-Type':'application/json'},body: data ? JSON.stringify(data) : undefined,signal:AbortSignal.timeout(60000) })
  const result = await res.json()
  if (!res.ok) throw Object.assign(Error(result.error || 'Ошибка сервера'),{status:res.status})
  return result
}
async function startPairing() {
  const generation = ++pairingGeneration
  $('pair-code').textContent = 'Получаем код…'
  $('pair-status').textContent = 'Код действует две минуты.'
  $('pair-retry').hidden = true
  try {
    const started = await api('/pair/start', 'POST', {})
    if (generation !== pairingGeneration || !$('settings').open) {
      api('/pair/cancel', 'POST', {code:started.code, secret:started.secret}).catch(() => {})
      return
    }
    activePairing = started
    if (generation !== pairingGeneration || !$('settings').open) return
    $('pair-code').textContent = started.code.slice(0, 4) + ' ' + started.code.slice(4)
    const expires = Date.now() + started.expiresIn * 1000
    while (generation === pairingGeneration && $('settings').open && Date.now() < expires) {
      await new Promise(resolve => setTimeout(resolve, 2000))
      if (generation !== pairingGeneration || !$('settings').open) return
      const result = await api('/pair/claim', 'POST', {code:started.code, secret:started.secret})
      if (generation !== pairingGeneration || !$('settings').open) return
      if (result.token) {
        token = result.token
        localStorage.setItem('moroshka.key', token)
        sessionStorage.removeItem('moroshka.key')
        $('settings').close('paired')
        await sync()
        return
      }
    }
    if (generation !== pairingGeneration || !$('settings').open) return
    $('pair-status').textContent = 'Код истёк.'
  } catch (e) {
    if (generation !== pairingGeneration || !$('settings').open) return
    $('pair-status').textContent = e.message
  }
  $('pair-retry').hidden = false
}
function openSettings() {
  $('key').value = token
  $('settings').showModal()
  startPairing()
}
async function sync() {
  if (!token) { openSettings(); return }
  if (busy) return
  persistEditor(); busy = true; document.querySelector('.shell').inert = true; $('status').textContent = 'Синхронизация…'
  let conflicts = 0
  try {
    const {notes:remote, deleted = []} = await api('/api/notes')
    for (const item of deleted) {
      const local = notes.get(item.id)
      if (!local) continue
      if (local.dirty) {
        create(local.text)
        conflicts++
      }
      localStorage.removeItem(prefix + item.id)
      notes.delete(item.id)
      if (selected === item.id) { selected = null; $('reader').hidden = true }
    }
    const byId = new Map(remote.map(n => [n.id,n]))
    for (const n of [...notes.values()]) {
      if (!n.dirty) continue
      const r = byId.get(n.id)
      if (r && r.text !== n.text && r.revision !== n.base) {
        const copy = '# Конфликт · ' + title(n) + '\n\n' + n.text
        create(new Blob([copy]).size > 512*1024 ? n.text : copy)
        put({id:r.id,text:r.text,base:r.revision,dirty:false}); conflicts++; continue
      }
      const result = await api('/api/notes/'+n.id,'PUT',{text:n.text,base:n.base})
      put({...n,base:result.revision,dirty:false}); byId.delete(n.id)
    }
    for (const r of byId.values()) put({id:r.id,text:r.text,base:r.revision,dirty:false})
    $('status').textContent = conflicts ? 'Конфликтов: ' + conflicts : 'Синхронизировано'
    if (conflicts) alertText('Сохранены обе версии','Ваш текст в заметке «Конфликт». Проверьте его и нажмите синхронизацию ещё раз.')
  } catch(e) { $('status').textContent = 'Синхронизация прервана'; report(e) }
  finally { busy = false; document.querySelector('.shell').inert = false; select(selected) }
}
// History is the server's saved revisions, not a substitute for offline draft storage.
async function openServerArchive(kind, id = null) {
  if (busy) return
  if (!token) return alertText('Подключите сервер','История и корзина сервера доступны после подключения.')
  try { persistEditor() } catch(e) { report(e); return }
  const dialog = $('server-archive')
  const generation = ++archiveGeneration
  archiveText = null; archiveSourceId = null
  $('archive-title').textContent = kind === 'trash' ? 'Корзина сервера' : 'История сервера'
  $('archive-hint').textContent = kind === 'trash'
    ? 'Удалённые заметки остаются в корзине. Восстановление создаёт новый UUID.'
    : 'Предыдущие синхронизированные редакции. Локальные несинхронизированные правки здесь не отображаются.'
  $('archive-status').textContent = 'Загрузка…'
  $('archive-items').replaceChildren()
  $('archive-preview').textContent = ''; $('archive-preview').hidden = true
  $('archive-copy').disabled = true; $('archive-history').hidden = true
  if (!dialog.open) dialog.showModal()
  const active = () => dialog.open && archiveGeneration === generation
  try {
    const result = await api(kind === 'trash' ? '/api/trash' : '/api/history/'+id)
    if (!active()) return
    const rows = kind === 'trash' ? result.notes : result.revisions
    $('archive-status').textContent = rows.length ? 'Выберите запись для просмотра.' : 'Нет сохранённых записей.'
    for (const row of rows) {
      const date = new Date(kind === 'trash' ? row.modifiedAt : row.archivedAt).toLocaleString('ru-RU')
      const name = kind === 'trash' ? row.title+' · '+date : date+' · '+row.revision.slice(0,10)
      const button = el('button', name, 'archive-entry')
      button.type = 'button'
      button.onclick = async () => {
        if (!active()) return
        for (const item of $('archive-items').querySelectorAll('button')) item.disabled = true
        archiveText = null; archiveSourceId = null
        $('archive-copy').disabled = true; $('archive-history').hidden = true
        $('archive-preview').textContent = ''; $('archive-preview').hidden = true
        $('archive-status').textContent = 'Загрузка текста…'
        try {
          const note = await api(kind === 'trash' ? '/api/trash/'+row.id : '/api/history/'+id+'/'+row.revision)
          if (!active()) return
          if (typeof note.text !== 'string') throw Error('Некорректный ответ сервера')
          archiveText = note.text
          archiveSourceId = kind === 'trash' ? row.id : null
          $('archive-preview').textContent = note.text
          $('archive-preview').hidden = false
          $('archive-copy').disabled = false
          $('archive-history').hidden = kind !== 'trash'
          $('archive-status').textContent = 'Исходная заметка не изменится.'
        } catch(e) {
          if (active()) $('archive-status').textContent = e.message
        } finally {
          if (active()) for (const item of $('archive-items').querySelectorAll('button')) item.disabled = false
        }
      }
      $('archive-items').append(button)
    }
  } catch(e) {
    if (active()) $('archive-status').textContent = e.status === 404
      ? 'История недоступна. Обновите сервер Морошки.' : e.message
  }
}
async function deleteSyncedNote() {
  if (busy || !selected) return
  try { persistEditor() } catch(e) { report(e); return }
  const n = notes.get(selected)
  if (!token || !n || n.dirty || !n.base)
    return alertText('Сначала синхронизируйте заметку','В корзину сервера можно переместить только синхронизированную редакцию. Несохранённый текст не удаляется.')
  if (!confirm('Переместить заметку в корзину сервера? Удаление распространится на другие устройства после синхронизации.')) return
  await archiveOperation(async () => {
    await api('/api/notes/'+n.id,'DELETE',{base:n.base})
    localStorage.removeItem(prefix+n.id)
    notes.delete(n.id)
    select(null)
    $('status').textContent = 'Заметка перемещена в корзину'
  })
}
async function archiveOperation(operation) {
  if (busy) return
  try { persistEditor() } catch(e) { report(e); return }
  busy = true
  document.querySelector('.shell').inert = true
  try { await operation() } catch(e) { report(e) }
  finally { busy = false; document.querySelector('.shell').inert = false; render() }
}
function downloadArchive(bytes) {
  const a = document.createElement('a')
  const url = URL.createObjectURL(new Blob([bytes], {type:'application/json'}))
  a.href = url
  a.download = 'Moroshka-backup-' + new Date().toISOString().slice(0,10) + '.json'
  a.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
function start() {
  for (const k of Object.keys(localStorage).filter(k => k.startsWith(prefix))) {
    const n = JSON.parse(localStorage.getItem(k)); notes.set(n.id,n)
  }
  $('new').onclick = () => { try { newNote() } catch(e) { report(e) } }
  const capture = $('capture-text')
  capture.value = localStorage.getItem(captureKey) || ''
  let unsavedCapture = false
  capture.oninput = () => {
    try {
      localStorage.setItem(captureKey, capture.value)
      unsavedCapture = false
      $('capture-status').textContent = 'Черновик на устройстве'
    } catch(e) {
      unsavedCapture = true
      $('capture-status').textContent = 'Не удалось сохранить черновик: ' + e.message
    }
  }
  $('capture').onsubmit = event => {
    event.preventDefault()
    const original = capture.value
    if (!original.trim()) { capture.focus(); return }
    try {
      persistEditor()
      const heading = original.split('\n').find(line => line.trim()).trim().replace(/^# /,'').slice(0,80).trim() || 'Новая заметка'
      const n = create('# ' + heading + '\n\n' + original)
      localStorage.removeItem(captureKey)
      capture.value = ''; unsavedCapture = false
      $('capture-status').textContent = 'Заметка сохранена'
      select(n.id)
    } catch(e) { report(e) }
  }
  window.addEventListener('beforeunload', event => {
    if (unsavedCapture) { event.preventDefault(); event.returnValue = '' }
  })
  document.querySelectorAll('nav button').forEach(b => b.onclick = () => {
    select(null); view = b.dataset.view; document.querySelector('[aria-current="page"]')?.removeAttribute('aria-current')
    b.setAttribute('aria-current','page'); $('heading').textContent = b.textContent; render()
  })
  $('search').oninput = render
  $('close').onclick = () => select(null)
  $('body').oninput = () => {
    dirtyEditor = true
    try { persistEditor(); render() } catch(e) { $('saved').textContent = e.message }
  }
  window.addEventListener('beforeunload', e => { if (dirtyEditor) {e.preventDefault(); e.returnValue = ''} })
  $('connect').onclick = openSettings
  $('pair-retry').onclick = startPairing
  $('settings').addEventListener('close', () => {
    pairingGeneration++
    if (activePairing) {
      const {code, secret} = activePairing
      activePairing = null
      api('/pair/cancel', 'POST', {code, secret}).catch(() => {})
    }
    if ($('settings').returnValue !== 'save') return
    token = $('key').value.trim(); localStorage.setItem('moroshka.key',token); sessionStorage.removeItem('moroshka.key')
    if (token) sync(); else $('status').textContent = 'На этом устройстве'
  })
  $('sync').onclick = () => sync().catch(report)
  $('export').onclick = () => {
    const a = document.createElement('a'); const url = URL.createObjectURL(new Blob([$('body').value],{type:'text/markdown'}))
    a.href = url; a.download = title(notes.get(selected)).replace(/[^\p{L}\p{N} _-]/gu,'_').slice(0,100)+'.md'; a.click(); setTimeout(()=>URL.revokeObjectURL(url),1000)
  }
  $('history').onclick = () => openServerArchive('history',selected)
  $('trash').onclick = () => openServerArchive('trash')
  $('delete-note').onclick = deleteSyncedNote
  $('server-archive').addEventListener('close', () => {
    archiveGeneration++; archiveText = null; archiveSourceId = null
    $('archive-preview').textContent = ''; $('archive-items').replaceChildren()
  })
  $('archive-history').onclick = () => {
    if (archiveSourceId) openServerArchive('history',archiveSourceId)
  }
  $('archive-copy').onclick = () => {
    if (archiveText === null) return
    try {
      const note = create(archiveText)
      $('server-archive').close()
      select(note.id)
    } catch(e) { $('archive-status').textContent = e.message }
  }
  $('backup-export').onclick = () => {
    if ($('capture-text').value.trim()) return alertText('Сохраните черновик','В архив входят только сохранённые заметки.')
    return archiveOperation(async () => downloadArchive(await encodeArchive(notes.values())))
  }
  $('backup-import').onclick = () => $('backup-file').click()
  $('backup-file').onchange = async () => {
    const file = $('backup-file').files[0]
    $('backup-file').value = ''
    if (!file) return
    await archiveOperation(async () => {
      if (file.size > maxArchiveBytes) throw Error('Архив больше 32 МиБ')
      const verified = await decodeArchive(new Uint8Array(await file.arrayBuffer()))
      const plan = await planRestore(verified, notes)
      let added = 0
      for (const n of plan.additions) {
        try { put({...n,base:null,dirty:true}); added++ }
        catch(e) { throw Error('Импорт прерван после ' + added + ' записей. Существующие заметки не заменены. Повторите импорт после освобождения места: ' + e.message) }
      }
      select(null)
      alertText('Архив восстановлен', 'Добавлено: ' + added + '. Уже есть: ' + plan.skipped + '. Конфликтных копий: ' + plan.conflicts + '.')
    })
  }
  $('import').onclick = () => $('file').click()
  $('file').onchange = async () => {
    try { for (const f of $('file').files) { if (f.size > 512*1024) throw Error('Файл больше 512 КиБ'); select(create(await f.text()).id) } }
    catch(e) { report(e) } finally { $('file').value = '' }
  }
  $('summary').onclick = async () => {
    const n = notes.get(selected)
    if (n.dirty || !token) return alertText('Сначала синхронизируйте заметку','OpenRouter получает сохранённую на вашем сервере версию.')
    if (!confirm('Отправить текст этой заметки в OpenRouter для краткого пересказа?')) return
    try { const r = await api('/api/summary','POST',{id:n.id,revision:n.base}); alertText('Резюме',r.summary) } catch(e) {report(e)}
  }
  render()
}
if (navigator.locks) {
  navigator.locks.request('moroshka-editor',{ifAvailable:true}, async lock => {
    if (!lock) { document.querySelector('.shell').inert = true; return alertText('Библиотека уже открыта','Закройте другую вкладку Moroshka Notes и обновите эту страницу.') }
    start(); await new Promise(() => {})
  }).catch(report)
} else { document.querySelector('.shell').inert = true; alertText('Требуется защищённое соединение','Откройте библиотеку по HTTPS или через localhost в современном браузере.') }


if ('serviceWorker' in navigator) navigator.serviceWorker.register('/sw.js').catch(() => {})
