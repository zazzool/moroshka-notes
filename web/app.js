const $ = id => document.getElementById(id)
const prefix = 'moroshka.note.'
const notes = new Map()
let selected, view = 'notes', dirtyEditor = false, busy = false
let token = sessionStorage.getItem('moroshka.key') || ''
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
function render() {
  const list = $('items'); list.replaceChildren()
  const q = norm($('search').value)
  let count = 0
  for (const n of [...notes.values()].sort((a,b) => title(a).localeCompare(title(b),'ru'))) {
    if (!norm(n.text).includes(q)) continue
    if (view === 'notes') {
      const b = action('', () => select(n.id)); b.className = 'note'
      if (n.id === selected) b.setAttribute('aria-current','true')
      b.append(el('strong',title(n)), el('small',n.text.split('\n').filter(l => l.trim() && !l.startsWith('# ')).join(' ') || 'Markdown'))
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
async function sync() {
  if (!token) { $('settings').showModal(); return }
  if (busy) return
  persistEditor(); busy = true; document.querySelector('.shell').inert = true; $('status').textContent = 'Синхронизация…'
  let conflicts = 0
  try {
    const {notes:remote} = await api('/api/notes')
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
function start() {
  for (const k of Object.keys(localStorage).filter(k => k.startsWith(prefix))) {
    const n = JSON.parse(localStorage.getItem(k)); notes.set(n.id,n)
  }
  $('new').onclick = () => { try { newNote() } catch(e) { report(e) } }
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
  $('connect').onclick = () => { $('key').value = token; $('settings').showModal() }
  $('settings').addEventListener('close', () => {
    if ($('settings').returnValue !== 'save') return
    token = $('key').value.trim(); sessionStorage.setItem('moroshka.key',token)
    if (token) sync(); else $('status').textContent = 'На этом устройстве'
  })
  $('sync').onclick = () => sync().catch(report)
  $('export').onclick = () => {
    const a = document.createElement('a'); const url = URL.createObjectURL(new Blob([$('body').value],{type:'text/markdown'}))
    a.href = url; a.download = title(notes.get(selected)).replace(/[^\p{L}\p{N} _-]/gu,'_').slice(0,100)+'.md'; a.click(); setTimeout(()=>URL.revokeObjectURL(url),1000)
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
