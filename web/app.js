import { encodeArchive, decodeArchiveBundle, planRestore, maxArchiveBytes } from '/backup.mjs'
import { pullSnapshot } from '/sync-pull.mjs'
import { applyProposal, appendLink, categoryLabels } from '/knowledge.mjs'
import { titleOf, linksOf, tagsOf, scan } from '/markdown.mjs'
import { imageRefs, appendImage, stripImages, composeImages, feedContent } from '/media-format.mjs'
import { getImage, saveImage, importPhoto, pullImages, pushImages } from '/media-store.mjs'
import { operations, defaults, modes, normalizeModes, localMetadata, localSummary, localRank } from '/processing.mjs'
import { installOcr, ocrReady, recognizeLocal } from '/local-ocr.mjs'

const $ = id => document.getElementById(id)
const prefix = 'moroshka.note.'
const captureKey = 'moroshka.captureDraft'
const autoKey = 'moroshka.autoSync'
let autoEnabled = localStorage.getItem(autoKey) === 'true'
let autoTimer = null, autoFailures = 0, autoAuthBlocked = false
let autoNotBefore = 0, autoGeneration = 0
let exchangeActive = false
const AUTO_DELAY = 3000, AUTO_POLL = 120000
const notes = new Map()
let selected, view = 'notes', dirtyEditor = false, busy = false
let token = localStorage.getItem('moroshka.key') || sessionStorage.getItem('moroshka.key') || ''
let pairingGeneration = 0
let activePairing = null
let archiveGeneration = 0
let archiveText = null
let archiveSourceId = null
let photoTarget=null, photoBusy=false, photoGeneration=0
let feedLimit=80
const thumbnailUrls=new Map()
const title = n => titleOf(n.text)
const links = linksOf
const norm = text => text.trim().toLocaleLowerCase()
function put(note) {
  if (new Blob([note.text]).size > 512 * 1024) throw Error('Предел одной заметки — 512 КиБ')
  const previous=notes.get(note.id)
  if(note.dirty&&(!previous||previous.text!==note.text))note={...note,modifiedAt:Date.now(),timeSource:'local'}
  else if(!note.modifiedAt&&previous?.modifiedAt)note={...note,modifiedAt:previous.modifiedAt,timeSource:previous.timeSource}
  else if(!note.modifiedAt&&!note.dirty&&!previous)note={...note,modifiedAt:Date.now(),timeSource:'received'}
  localStorage.setItem(prefix + note.id, JSON.stringify(note))
  notes.set(note.id, note)
  if (note.dirty && !exchangeActive) requestAuto(AUTO_DELAY)
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
  put({ ...notes.get(selected), text: composeImages($('body').value,imageRefs(notes.get(selected).text)), dirty: true })
  dirtyEditor = false; $('saved').textContent = 'Сохранено на устройстве'
}
function select(id) {
  persistEditor(); selected = id; $('reader').hidden = !id
  if (id) { $('body').value = stripImages(notes.get(id).text); $('saved').textContent = notes.get(id).dirty ? 'На устройстве' : 'Синхронизировано' }
  render()
  if (!id && autoEnabled && !exchangeActive) requestAuto(AUTO_DELAY)
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
  $('capture').hidden = false
  $('recognize-photo').hidden = !selected || !imageRefs(notes.get(selected).text).length
  if (selected) $('export').textContent = imageRefs(notes.get(selected).text).length ? 'Экспорт с фото' : 'Скачать .md'
  const list = $('items'); list.replaceChildren()
  const q = norm($('search').value)
  let count = 0
  const ordered=[...notes.values()].sort((a,b)=>(b.modifiedAt||0)-(a.modifiedAt||0)||a.id.localeCompare(b.id))
  let previousDay=''
  for (const n of ordered) {
    if (!norm(n.text).includes(q)) continue
    if (view === 'notes') {
      if(count>=feedLimit)break
      const day=n.modifiedAt?new Date(n.modifiedAt).toLocaleDateString('ru-RU',{day:'numeric',month:'long',year:'numeric'}):'Без даты'
      if(day!==previousDay){list.append(el('div',day,'feed-day'));previousDay=day}
      const b = action('', () => select(n.id)); b.className = 'note'
      if (n.id === selected) b.setAttribute('aria-current','true')
      const context=feedContent(n.text)
      const body=el('span','','note-body')
      body.append(el('span',context.body,'note-excerpt'))
      if(context.title!=='Без названия'&&context.title!==context.body&&!context.body.startsWith(context.title+' '))
        body.append(el('span',context.title,'note-heading'))
      const time=n.modifiedAt?new Date(n.modifiedAt).toLocaleTimeString('ru-RU',{hour:'2-digit',minute:'2-digit'}):'Дата неизвестна'
      const info=[(n.timeSource==='received'?'Получено ':'')+time,
        context.images?'Фото: '+context.images:'',context.links?'Связей: '+context.links:'',
        ...context.tags.slice(0,3).map(t=>t.startsWith('type-')&&categoryLabels[t.slice(5)]?categoryLabels[t.slice(5)]:'#'+t)]
      body.append(el('span',info.filter(Boolean).join(' · '),'note-context'))
      b.append(body)
      const id=imageRefs(n.text)[0]
      if(id)b.append(photoThumbnail(id,'note-photo'))
      list.append(b); count++
    } else {
      scan(n.text).prose.forEach(({line,index:i}) => {
        const m = line.match(/^\s*[-*] \[([ xX])\] (.+)/); if (!m) return
        const row = el('div','','task'); const check = el('input',''); check.type = 'checkbox'; check.checked = m[1] !== ' '
        check.setAttribute('aria-label', m[2])
        check.onchange = () => {
          try {
            const lines = n.text.split('\n'); lines[i] = lines[i].replace(/\[[ xX]\]/,check.checked ? '[x]' : '[ ]')
            put({...n,text:lines.join('\n'),dirty:true}); if (selected === n.id) $('body').value = stripImages(notes.get(n.id).text)
            render()
          } catch(e) { report(e); render() }
        }
        const b = action('', () => select(n.id)); b.append(el('span',m[2],'taskText'),el('small',title(n))); row.append(check,b); list.append(row); count++
      })
    }
  }
  $('count').textContent = view==='notes'?ordered.filter(n=>norm(n.text).includes(q)).length:count
  if(view==='notes'&&ordered.filter(n=>norm(n.text).includes(q)).length>feedLimit)list.append(action('Показать ещё',()=>{feedLimit+=80;render()}))
  if (!count) {
    const box = el('div','','empty')
    box.append(el('h2',q ? 'Совпадений нет' : view === 'tasks' ? 'Задач пока нет' : 'Первая заметка'),
      el('p',q ? 'Попробуйте другое слово или тег.' : view === 'tasks' ? 'Строки вида «- [ ] Прочитать статью» появятся здесь.' : 'Запишите мысль. Добавьте [[ссылку]], чтобы связать её с другой заметкой.'))

    list.append(box)
  }
  related()
  renderPhotos()
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
  if (busy) return
  $('key').value = token
  $('settings').showModal()
  startPairing()
}
function requestAuto(delay = AUTO_POLL) {
  if (!autoEnabled || !token || autoAuthBlocked) return
  clearTimeout(autoTimer)
  autoTimer = setTimeout(runAuto, Math.max(delay, autoNotBefore - Date.now()))
}
async function runAuto() {
  autoTimer = null
  if (!autoEnabled || !token || autoAuthBlocked) return
  if (document.visibilityState !== 'visible') { requestAuto(AUTO_POLL); return }
  if (busy || photoBusy || document.querySelector('dialog[open]')) { requestAuto(5000); return }
  if (!navigator.onLine) {
    $('status').textContent = 'Офлайн · локальные заметки сохранены'
    requestAuto(AUTO_POLL)
    return
  }
  await sync(true)
}
function setAuto(value) {
  localStorage.setItem(autoKey, String(value))
  autoEnabled = value
  autoFailures = 0; autoAuthBlocked = false; autoNotBefore = 0; autoGeneration++
  clearTimeout(autoTimer); autoTimer = null
  if (value) requestAuto(AUTO_DELAY)
  else if (!busy) $('status').textContent = 'Автообмен выключен'
}
function applySynced(note) {
  put(note)
  if (selected === note.id) {
    // Preserve focus and selection if text has not changed.
    if ($('body').value !== stripImages(note.text)) $('body').value = stripImages(note.text)
    $('saved').textContent = note.dirty ? 'На устройстве' : 'Синхронизировано'
  }
}
async function sync(automatic = false) {
  if (!token) { if (!automatic) openSettings(); return }
  if (busy || photoBusy) return
  if (automatic && !autoEnabled) return
  try { persistEditor() } catch(e) {
    if (automatic) {
      $('status').textContent = 'Автообмен отложен: не сохранён текст'
      requestAuto(AUTO_POLL)
    } else report(e)
    return
  }
  clearTimeout(autoTimer); autoTimer = null
  busy = true; exchangeActive = true
  if (!automatic) document.querySelector('.shell').inert = true
  $('status').textContent = automatic ? 'Автосинхронизация…' : 'Синхронизация…'
  let conflicts = 0, deferred = false, ok = false
  const generation = autoGeneration
  const checkpoint = () => {
    if (automatic && (!autoEnabled || generation !== autoGeneration)) throw Object.assign(Error('Автообмен выключен'),{cancelled:true})
    persistEditor()
  }
  try {
    const knownBases = new Map([...notes.values()].map(n=>[n.id,n.base]))
    const {notes:remote, deleted = []} = await pullSnapshot(api,knownBases,checkpoint)
    checkpoint()
    $('note-photos').dataset.key=''
    for(const r of remote){
      if(typeof r.text==='string')await pullImages(r.text,api,checkpoint)
      else{
        const local=notes.get(r.id)
        if(local&&!local.dirty)await pullImages(local.text,api,checkpoint)
      }
    }
    checkpoint()
    const deletedIds = new Set(deleted.map(n=>n.id))
    for (const item of deleted) {
      const local = notes.get(item.id)
      if (!local) continue
      // Do not replace or close an open editor in the automatic mode.
      if (automatic && selected === item.id) { deferred = true; continue }
      if (local.dirty) {
        create(local.text)
        conflicts++
      }
      localStorage.removeItem(prefix + item.id)
      notes.delete(item.id)
      if (selected === item.id) { selected = null; $('reader').hidden = true }
    }
    const byId = new Map(remote.map(n => [n.id,n]))
    const ids = new Set([...notes.keys(), ...byId.keys()])
    for (const id of ids) {
      if (deletedIds.has(id)) continue
      checkpoint()
      const n = notes.get(id)
      const r = byId.get(id)
      if (r && !n) {
        if (typeof r.text !== 'string') throw Error('Локальная библиотека изменилась. Повторите обмен.')
        applySynced({id:r.id,text:r.text,base:r.revision,dirty:false,modifiedAt:r.modifiedAt,timeSource:r.modifiedAt?'server':'received'}); continue
      }
      if (!n) continue
      if (r && typeof r.text !== 'string') {
        if (n.base !== r.revision) throw Error('Локальная редакция изменилась. Повторите обмен.')
        if (!n.dirty) {
          if(r.modifiedAt&&n.modifiedAt!==r.modifiedAt)applySynced({...n,modifiedAt:r.modifiedAt,timeSource:'server'})
          continue
        }
      }
      if (r && r.text === n.text) {
        if (n.dirty || n.base !== r.revision || (r.modifiedAt&&n.modifiedAt!==r.modifiedAt)) applySynced({id,text:n.text,base:r.revision,dirty:false,modifiedAt:r.modifiedAt,timeSource:r.modifiedAt?'server':n.timeSource})
        continue
      }
      if (r && (!n.dirty || r.revision !== n.base)) {
        if (automatic && selected === id) { deferred = true; continue }
        if (n.dirty) {
          const copy = '# Конфликт · ' + title(n) + '\n\n' + n.text
          create(new Blob([copy]).size > 512*1024 ? n.text : copy)
          conflicts++
        }
        applySynced({id,text:r.text,base:r.revision,dirty:false,modifiedAt:r.modifiedAt,timeSource:r.modifiedAt?'server':'received'})
        continue
      }
      if (!n.dirty && r) continue
      // Absence without a tombstone is not a deletion (server rollback/migration).
      // A conditional create still refuses a concurrent write or tombstone.
      const sentText = n.text, sentBase = r ? n.base : null
      await pushImages(sentText,api,checkpoint)
      const result = await api('/api/notes/'+id,'PUT',{text:sentText,base:sentBase})
      checkpoint()
      const latest = notes.get(id)
      if (latest) {
        applySynced({...latest,base:result.revision,dirty:latest.text !== sentText})
      }
    }
    ok = true; autoFailures = 0; autoAuthBlocked = false; autoNotBefore = 0
    const pending = [...notes.values()].some(n=>n.dirty)
    $('status').textContent = conflicts ? 'Конфликтов: ' + conflicts :
      deferred ? 'Есть изменения на сервере · закройте редактор для получения' :
      pending ? 'Локальные изменения ожидают отправки' : 'Синхронизировано'
    if (conflicts && !automatic) alertText('Сохранены обе версии','Ваш текст в заметке «Конфликт». Проверьте его и нажмите синхронизацию ещё раз.')
  } catch(e) {
    if (automatic) {
      autoAuthBlocked = e.status === 401
      if (!e.cancelled) {
        autoFailures = Math.min(autoFailures+1,5)
        autoNotBefore = Date.now() + Math.min(15000 * 2 ** autoFailures, 300000)
      }
      $('status').textContent = e.cancelled ? (autoEnabled ? 'Автообмен ожидает запуска' : 'Автообмен выключен') :
        autoAuthBlocked ? 'Проверьте ключ сервера' : 'Автообмен отложен · локальные изменения сохранены'
    } else {
      $('status').textContent = 'Синхронизация прервана'
      if (e.status === 401) autoAuthBlocked = true
      report(e)
    }
  } finally {
    busy = false; exchangeActive = false
    document.querySelector('.shell').inert = false
    // Never reload the open textarea after an automatic exchange.
    if (automatic) render()
    else select(selected)
    if (autoEnabled && token && !autoAuthBlocked) {
      const pending = [...notes.values()].some(n=>n.dirty)
      requestAuto(ok ? deferred ? 30000 : pending ? AUTO_DELAY : AUTO_POLL :
        Math.min(15000 * 2 ** autoFailures, 300000))
    }
  }
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
function downloadArchive(bytes,name='Moroshka-backup-' + new Date().toISOString().slice(0,10) + '.json') {
  const a = document.createElement('a')
  const url = URL.createObjectURL(new Blob([bytes], {type:'application/json'}))
  a.href = url
  a.download = name
  a.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
const processingKey='moroshka.processing.v1'
let routes=normalizeModes(null)
try{routes=normalizeModes(JSON.parse(localStorage.getItem(processingKey)||'null'))}catch{}
function processingMode(op){
  const mode=routes[op]
  if(mode==='off'){alertText('Операция выключена','Измените режим в разделе «Обработка заметок».');return null}
  return mode
}
let knowledgeGeneration=0
function knowledgeDialog(titleText,serverRequired=true,reuse=false){
  if(busy)return null
  if(serverRequired&&!token){alertText('Подключите сервер','Настройте сервер для этой операции.');return null}
  try{persistEditor()}catch(e){report(e);return null}
  const gen=++knowledgeGeneration, dialog=$('knowledge-dialog')
  $('knowledge-title').textContent=titleText
  $('knowledge-status').textContent='Загрузка…'
  $('knowledge-body').replaceChildren()
  if(!reuse)dialog.showModal()
  return {active:()=>dialog.open&&knowledgeGeneration===gen,status:text=>$('knowledge-status').textContent=text,
    body:$('knowledge-body'),close:()=>dialog.close()}
}
function knowledgeButton(text,callback){
  const b=el('button',text)
  b.type='button'
  b.onclick=async()=>{
    b.disabled=true
    try{await callback()}catch(e){if($('knowledge-dialog').open)$('knowledge-status').textContent=e.message}
    finally{b.disabled=false}
  }
  return b
}
function checkedOption(body,text){
  const label=el('label','','knowledge-choice'), box=el('input','')
  box.type='checkbox'
  label.append(box,el('span',text))
  body.append(label)
  return box
}
function currentPinned(source,local=false){
  persistEditor()
  const n=notes.get(source.id)
  if(selected!==source.id||!n||n.text!==source.text||(!local&&(n.base!==source.base||n.dirty)))
    throw Error('Заметка изменилась. Повторите анализ после синхронизации.')
  return n
}
function pinnedSelection(local=false){
  try{persistEditor()}catch(e){report(e);return null}
  const n=notes.get(selected)
  if(!n||(!local&&(!token||n.dirty||!n.base))){
    alertText('Сначала синхронизируйте заметку','Предложения привязаны к сохранённой на сервере редакции.')
    return null
  }
  return {...n}
}
async function analyzeNote(){
  if(busy)return
  const mode=processingMode('metadata');if(!mode)return
  const local=mode==='local',source=pinnedSelection(local); if(!source)return
  if(!local&&!confirm('Передать OpenRouter до 12 000 символов этой заметки для создания предложений? Запрос может быть платным.'))return
  const d=knowledgeDialog(local?'Предложения · локальные правила':'Предложения ИИ',!local); if(!d)return
  try{
    const result=local?localMetadata(source.text):await api('/api/ai/analyze','POST',{id:source.id,revision:source.base,consent:true})
    if(!d.active())return
    if(!local&&(result.id!==source.id||result.revision!==source.base))throw Error('Получена другая редакция')
    currentPinned(source,local)
    // Validate all candidates before showing any action that can persist them.
    applyProposal(source.text,{title:result.title,tags:result.tags,category:result.category})
    d.status(result.truncated?'Проанализированы первые 12 000 символов. Выберите изменения.':'Выберите изменения. Исходный текст не переписывается.')
    const heading=checkedOption(d.body,'Заголовок: '+result.title)
    const category=checkedOption(d.body,'Тип: '+categoryLabels[result.category])
    const tagOptions=result.tags.map(t=>({tag:t,box:checkedOption(d.body,'#'+t)}))
    function accept(all){
      currentPinned(source,local)
      const text=applyProposal(source.text,{title:all||heading.checked?result.title:null,
        category:all||category.checked?result.category:null,tags:tagOptions.filter(t=>all||t.box.checked).map(t=>t.tag)})
      if(text!==source.text){
        put({...notes.get(source.id),text,dirty:true})
        $('body').value=stripImages(text); $('saved').textContent='Сохранено на устройстве'; render()
      }
      d.close()
    }
    d.body.append(knowledgeButton('Принять выбранное',()=>accept(false)),knowledgeButton('Принять все',()=>accept(true)))
  }catch(e){if(d.active())d.status(e.message)}
}
async function semanticDialog(){
  const mode=processingMode('search');if(!mode)return
  if(mode==='local'){
    const d=knowledgeDialog('Расширенный поиск · локально',false);if(!d)return
    d.status('Ранжирование по совпадению слов. Не используются эмбеддинги или OpenRouter.')
    const input=el('input','');input.setAttribute('aria-label','Локальный запрос');input.placeholder='Слова или фраза';input.maxLength=1000
    const results=el('div','','knowledge-results')
    d.body.append(input,knowledgeButton('Найти',()=>{
      const query=input.value.trim();if(!query)throw Error('Введите запрос')
      results.replaceChildren()
      const found=localRank(query,notes.values())
      if(!found.length)results.append(el('p','Совпадений по словам нет.'))
      for(const row of found)results.append(knowledgeButton(title(row),()=>{
        const n=notes.get(row.id);if(!n||n.text!==row.text)throw Error('Заметка изменилась. Повторите поиск.')
        d.close();select(n.id)
      }))
    }),results)
    input.focus()
    return
  }
  const d=knowledgeDialog('Смысловой поиск');if(!d)return
  async function refresh(){
    const status=await api('/api/ai/status')
    if(!d.active())return
    d.status(status.enabled?'Актуальный индекс: '+status.indexed+' из '+status.total:
      'На сервере не настроена модель эмбеддингов.')
    d.body.replaceChildren()
    if(!status.enabled){
      d.body.append(knowledgeButton('Удалить векторный индекс',async()=>{
        if(!confirm('Удалить векторный индекс сервера? Сами заметки останутся.'))return
        await api('/api/ai/index','DELETE')
        if(d.active())d.status('Индекс удалён. Модель эмбеддингов не настроена.')
      }))
      return
    }
    if(status.pending.length){
      d.body.append(knowledgeButton('Индексировать следующие '+status.pending.length+' заметок',async()=>{
        if(!confirm('Передать OpenRouter первые 12 000 символов каждой из '+status.pending.length+' заметок для эмбеддингов? Возможна оплата.'))return
        await api('/api/ai/index','POST',{notes:status.pending,consent:true})
        if(d.active())await refresh()
      }))
    }
    const input=el('input','');input.placeholder='Поиск по смыслу';input.setAttribute('aria-label','Смысловой запрос');input.maxLength=1000
    const results=el('div','','knowledge-results')
    d.body.append(input,knowledgeButton('Найти',async()=>{
      const q=input.value.trim();if(!q)throw Error('Введите запрос')
      if(!confirm('Передать поисковый запрос OpenRouter для вычисления эмбеддинга?'))return
      const response=await api('/api/ai/search','POST',{query:q,consent:true})
      if(!d.active())return
      results.replaceChildren()
      if(!response.notes.length)results.append(el('p','Совпадений среди актуальных индексированных заметок нет.'))
      for(const item of response.notes)results.append(knowledgeButton(item.title,()=>{
        const n=notes.get(item.id)
        if(!n||n.dirty||n.base!==item.revision)throw Error('Сначала синхронизируйте актуальную заметку.')
        d.close();select(n.id)
      }))
    }),results,knowledgeButton('Удалить векторный индекс',async()=>{
      if(!confirm('Удалить векторный индекс сервера? Сами заметки останутся.'))return
      await api('/api/ai/index','DELETE')
      if(d.active())await refresh()
    }))
  }
  try{await refresh()}catch(e){if(d.active())d.status(e.message)}
}
async function suggestLinks(){
  if(busy)return
  const mode=processingMode('links');if(!mode)return
  const local=mode==='local',source=pinnedSelection(local);if(!source)return
  const d=knowledgeDialog(local?'Предложения связей · по словам':'Смысловые связи',!local);if(!d)return
  try{
    const result=local?{notes:localRank(stripImages(source.text),notes.values(),source.id).map(n=>({...n,title:title(n)}))}:await api('/api/ai/related','POST',{id:source.id,revision:source.base})
    if(!d.active())return
    d.status(local?'Совпадения по словам; связь добавляется только по вашему выбору.':result.notes.length?'Похожие индексированные заметки. Это предложения, а не установленные связи.':'Похожих индексированных заметок пока нет.')
    for(const item of result.notes){
      d.body.append(knowledgeButton('Связать: '+item.title,()=>{
        currentPinned(source,local)
        const target=notes.get(item.id)
        if(!target||title(target)!==item.title||(local?target.text!==item.text:target.dirty||target.base!==item.revision))throw Error('Целевая заметка изменилась. Повторите запрос.')
        const text=appendLink(source.text,item.title,[...notes.values()].map(title),links(source.text))
        if(text!==source.text){put({...source,text,dirty:true});$('body').value=stripImages(text);$('saved').textContent='Сохранено на устройстве';render()}
        d.close()
      }))
    }
  }catch(e){if(d.active())d.status(e.message)}
}

function photoThumbnail(id,cls='photo-thumb'){
  const image=el('img','',cls);image.alt='Фотография';image.loading='lazy'
  if(thumbnailUrls.has(id)){image.src=thumbnailUrls.get(id);return image}
  getImage(id).then(bytes=>{
    if(!bytes||!image.isConnected)return
    if(!thumbnailUrls.has(id)){
      if(thumbnailUrls.size>=80){
        const oldest=thumbnailUrls.keys().next().value
        URL.revokeObjectURL(thumbnailUrls.get(oldest));thumbnailUrls.delete(oldest)
      }
      thumbnailUrls.set(id,URL.createObjectURL(new Blob([bytes],{type:'image/jpeg'})))
    }
    image.src=thumbnailUrls.get(id)
  }).catch(()=>{})
  return image
}
function renderPhotos(){
  const strip=$('note-photos')
  const ids=selected?imageRefs(notes.get(selected).text):[]
  const key=(selected||'')+ids.join(',')
  if(strip.dataset.key===key)return
  strip.dataset.key=key;strip.replaceChildren();strip.hidden=!ids.length
  for(const id of ids){
    const button=action('',()=>showPhoto(id))
    button.className='photo-tile';button.setAttribute('aria-label','Открыть фотографию')
    button.append(photoThumbnail(id))
    strip.append(button)
  }
}
function choosePhoto(target){
  if(photoBusy||busy){alertText('Обмен выполняется','Повторите действие после завершения текущего обмена.');return}
  try{persistEditor()}catch(e){report(e);return}
  photoTarget=target
  $('photo-source').showModal()
}
async function addPhoto(file){
  if(!file||photoBusy)return
  const target=photoTarget, draftAtStart=$('capture-text').value, selectedAtStart=selected
  photoTarget=null;photoBusy=true
  const status=$('capture-status')
  status.textContent='Сохранение фото…'
  try{
    const id=await importPhoto(file)
    persistEditor()
    let n
    if(target){
      const old=notes.get(target)
      if(!old)throw Error('Исходная заметка больше не доступна')
      n={...old,text:appendImage(old.text,id),dirty:true};put(n)
    }else{
      const draft=draftAtStart
      const heading=draft.split('\n').find(l=>l.trim())?.trim().replace(/^# /,'').slice(0,80)||'Фотозаметка'
      n=create(appendImage('# '+heading+'\n\n'+draft,id))
      if($('capture-text').value===draftAtStart){
        localStorage.removeItem(captureKey);$('capture-text').value=''
      }
    }
    status.textContent='Фотография сохранена'
    if(selected===selectedAtStart)select(n.id)
    else render()
  }catch(e){report(e);status.textContent='Фотография не добавлена'}
  finally{photoBusy=false;if(autoEnabled)requestAuto(AUTO_DELAY)}
}
async function showPhoto(id){
  if(!selected)return
  try{persistEditor()}catch(e){report(e);return}
  const source={...notes.get(selected)}, gen=++photoGeneration
  const dialog=$('photo-view')
  $('photo-image').removeAttribute('src')
  $('ocr-result').hidden=true;$('ocr-text').value=''
  $('photo-status').textContent='Загрузка…'
  $('ocr-start').disabled=true
  dialog.showModal()
  const active=()=>dialog.open&&photoGeneration===gen
  let bytes
  try{
    bytes=await getImage(id)
    if(!bytes&&token)await pullImages(appendImage('',id),api)
    bytes=bytes||await getImage(id)
    if(!active())return
    if(!bytes)throw Error('Изображение не загружено')
    const url=URL.createObjectURL(new Blob([bytes],{type:'image/jpeg'}))
    $('photo-image').src=url
    $('photo-image').dataset.url=url
    $('photo-status').textContent='Фотография сохранена'
    $('ocr-start').disabled=false
  }catch(e){if(active())$('photo-status').textContent=e.message}
  $('photo-remove').onclick=()=>{
    if(!active()||!confirm('Убрать фотографию из заметки?'))return
    try{
      persistEditor()
      const current=notes.get(source.id)
      if(!current||selected!==source.id||current.text!==source.text)throw Error('Заметка изменилась. Откройте фотографию снова.')
      const text=composeImages(stripImages(current.text),imageRefs(current.text).filter(v=>v!==id))
      put({...current,text,dirty:true});$('body').value=stripImages(text);dialog.close();render()
    }catch(e){$('photo-status').textContent=e.message}
  }
  $('ocr-start').onclick=async()=>{
    if(!active())return
    const mode=routes.ocr
    if(mode==='off'){$('photo-status').textContent='OCR выключен в настройках обработки.';return}
    if(mode==='server'&&!token){$('photo-status').textContent='Сначала подключите сервер.';return}
    if(mode==='server'&&!confirm('Передать эту фотографию вашему серверу и модели OpenRouter для распознавания? Запрос может быть платным.'))return
    $('ocr-start').disabled=true;$('photo-status').textContent='Распознаём текст…'
    try{
      let reply
      if(mode==='local'){
        if(!await ocrReady()){
          if(!confirm('Загрузить проверенные файлы OCR и языковые пакеты с вашего сервера? Фото не отправляется.'))return
          $('photo-status').textContent='Установка локального OCR…'
          await installOcr()
        }
        if(!active())return
        $('photo-status').textContent='Распознавание на устройстве…'
        reply={id,text:await recognizeLocal(bytes)}
      }else{
        await pushImages(appendImage('',id),api)
        reply=await api('/api/ai/ocr','POST',{id,consent:true})
      }
      if(!active())return
      if(reply.id!==id||typeof reply.text!=='string')throw Error('Некорректный ответ распознавания')
      $('ocr-text').value=reply.text
      $('ocr-result').hidden=false
      $('photo-status').textContent=reply.possiblyTruncated?'Текст может быть неполным. Проверьте его.':(mode==='local'?'Локальное OCR · ':'OpenRouter · ')+'Проверьте и при необходимости исправьте текст.'
    }catch(e){if(active())$('photo-status').textContent=e.message}
    finally{if(active())$('ocr-start').disabled=false}
  }
  $('ocr-accept').onclick=()=>{
    if(!active())return
    try{
      persistEditor()
      const current=notes.get(source.id)
      if(!current||selected!==source.id||current.text!==source.text)throw Error('Заметка изменилась. Повторите распознавание.')
      const text=composeImages(stripImages(current.text)+'\n\n'+$('ocr-text').value,imageRefs(current.text))
      put({...current,text,dirty:true});$('body').value=stripImages(text);dialog.close();render()
    }catch(e){$('photo-status').textContent=e.message}
  }
}

function fieldLabel(body,name,input){
  const label=el('label',name,'processing-label')
  label.append(input);body.append(label);return input
}
function processingSettings(){
  const d=knowledgeDialog('Обработка заметок',false);if(!d)return
  d.status('Режимы относятся к этому устройству. Серверный режим не отправляет данные без отдельного действия.')
  const controls=new Map()
  for(const [op,name] of Object.entries(operations)){
    const select=el('select','')
    for(const [id,label] of Object.entries(modes)){
      const option=el('option',label);option.value=id;select.append(option)
    }
    select.value=routes[op];select.setAttribute('aria-label',name)
    fieldLabel(d.body,name,select);controls.set(op,select)
  }
  d.body.append(el('p','Локально: простые правила, извлечение фраз и совпадения слов. OCR — Tesseract, русский и английский. Не генеративный ИИ.'))
  d.body.append(knowledgeButton('По умолчанию',()=>{for(const [op,select] of controls)select.value=defaults[op]}))
  d.body.append(knowledgeButton('Сохранить режимы',()=>{
    const value=normalizeModes(Object.fromEntries([...controls].map(([op,select])=>[op,select.value])))
    localStorage.setItem(processingKey,JSON.stringify(value));routes=value
    d.status('Режимы сохранены на этом устройстве.')
  }))
  d.body.append(knowledgeButton('Настроить OpenRouter на сервере',()=>{if(!token){d.close();alertText('Подключите сервер','Сначала подключите свой сервер.');return}return openRouterSettings(true)}))
  d.body.append(knowledgeButton('Установить локальный OCR',async()=>{
    if(await ocrReady()){d.status('Русский и английский OCR уже установлен.');return}
    if(!confirm('Загрузить OCR-движок и языковые пакеты с вашего сервера? Фото не отправляются.'))return
    d.status('Загрузка пакетов…');await installOcr()
    if(d.active())d.status('Локальное распознавание готово.')
  }))
}
async function openRouterSettings(reuse=false){
  const d=knowledgeDialog('OpenRouter · сервер',true,reuse);if(!d)return
  try{
    const config=await api('/api/ai/config');if(!d.active())return
    d.status(config.configured?'API-ключ настроен на сервере.':'OpenRouter не подключён.')
    d.body.append(el('p','Настройки действуют на всех устройствах. Ключ хранится только на сервере и не показывается повторно.'))
    const link=el('a','Получить API-ключ OpenRouter');link.href='https://openrouter.ai/keys';link.target='_blank';link.rel='noopener noreferrer';d.body.append(link)
    const key=el('input','');key.type='password';key.autocomplete='off';key.placeholder='Пусто — сохранить прежний';key.setAttribute('aria-label','Новый API-ключ')
    fieldLabel(d.body,'Новый API-ключ',key)
    const fields={}
    for(const [name,label] of Object.entries({model:'Текст и классификация',embeddingModel:'Эмбеддинги',visionModel:'Фото и OCR'})){
      const input=el('input','');input.value=config[name];input.placeholder=config.recommended[name];input.setAttribute('aria-label',label)
      fieldLabel(d.body,label,input);fields[name]=input
    }
    d.body.append(el('p','Пустое поле модели отключает соответствующую серверную функцию. Новый ключ проверяется без передачи заметок.'))
    d.body.append(knowledgeButton('Рекомендуемые модели',()=>{for(const [name,input] of Object.entries(fields))input.value=config.recommended[name]}))
    let writing=false
    async function save(action){
      if(writing)return
      writing=true
      const body={revision:config.revision,keyAction:action,...Object.fromEntries(Object.entries(fields).map(([name,input])=>[name,input.value.trim()]))}
      if(action==='replace')body.apiKey=key.value.trim()
      key.value=''
      try{
        await api('/api/ai/config','PUT',body)
        if(d.active()){d.close();alertText('OpenRouter настроен','Настройки сохранены на сервере без перезапуска.')}
      }finally{writing=false;delete body.apiKey}
    }
    d.body.append(knowledgeButton('Сохранить на сервере',()=>save(key.value.trim()?'replace':'keep')))
    d.body.append(knowledgeButton('Проверить сохранённый ключ',async()=>{
      await api('/api/ai/config/test','POST',{})
      if(d.active())d.status('OpenRouter подтвердил ключ.')
    }))
    if(config.configured)d.body.append(knowledgeButton('Отключить ключ на сервере',()=>{
      if(confirm('Отключить OpenRouter для всех устройств? Локальные функции останутся.'))return save('remove')
    }))
  }catch(e){if(d.active())d.status(e.message)}
}

function start() {
  for (const k of Object.keys(localStorage).filter(k => k.startsWith(prefix))) {
    const n = JSON.parse(localStorage.getItem(k)); notes.set(n.id,n)
  }
  $('new').onclick = () => { try { newNote() } catch(e) { report(e) } }
  $('capture-photo').onclick=()=>choosePhoto(null)
  $('add-photo').onclick=()=>choosePhoto(selected)
  $('recognize-photo').onclick=()=>{if(selected){const id=imageRefs(notes.get(selected).text)[0];if(id)showPhoto(id)}}
  $('pick-photo').onclick=()=>{$('photo-source').close();$('photo-file').click()}
  $('take-photo').onclick=()=>{$('photo-source').close();$('camera-file').click()}
  for(const key of ['photo-file','camera-file'])$(key).onchange=()=>{
    const file=$(key).files[0];$(key).value='';addPhoto(file)
  }
  $('photo-view').addEventListener('close',()=>{
    photoGeneration++
    const image=$('photo-image')
    if(image.dataset.url)URL.revokeObjectURL(image.dataset.url)
    delete image.dataset.url;image.removeAttribute('src');$('ocr-text').value=''
  })
  const mobile=matchMedia('(max-width:680px)')
  const libraryTools=$('library-tools')
  const compact=()=>{libraryTools.open=!mobile.matches;if(mobile.matches)$('editor-tools').open=false}
  compact();mobile.addEventListener('change',compact)
  const closeTools=()=>{if(mobile.matches)libraryTools.open=false}
  for(const id of ['connect','processing-settings','ai-search','trash','import','backup-import','backup-export']){
    $(id).addEventListener('click',closeTools)
  }
  document.addEventListener('click',event=>{if(!libraryTools.contains(event.target))closeTools()})
  document.addEventListener('keydown',event=>{if(event.key==='Escape')closeTools()})
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
    if(photoBusy){$('capture-status').textContent='Сохраняется фотография';return}
    const original = capture.value
    if (!original.trim()) { capture.focus(); return }
    try {
      persistEditor()
      const heading = original.split('\n').find(line => line.trim()).trim().replace(/^# /,'').slice(0,80).trim() || 'Новая заметка'
      const n = create('# ' + heading + '\n\n' + original)
      localStorage.removeItem(captureKey)
      capture.value = ''; unsavedCapture = false
      $('capture-status').textContent = 'Заметка сохранена'
      view='notes';$('search').value=''
      document.querySelector('nav [aria-current]')?.removeAttribute('aria-current')
      document.querySelector('nav [data-view="notes"]').setAttribute('aria-current','page')
      $('heading').textContent='Заметки'
      select(null)
      $('feed').scrollTop=0
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
    autoAuthBlocked = false; autoFailures = 0; autoNotBefore = 0
    if (token) sync(); else { clearTimeout(autoTimer); autoTimer = null; $('status').textContent = 'На этом устройстве' }
  })
  $('sync').onclick = () => sync().catch(report)
  $('auto-sync').checked = autoEnabled
  $('auto-sync').onchange = () => {
    if ($('auto-sync').checked && !token) {
      $('auto-sync').checked = false
      alertText('Подключите сервер','Автообмен можно включить после подключения.')
      return
    }
    try { setAuto($('auto-sync').checked) }
    catch(e) { $('auto-sync').checked = autoEnabled; report(e) }
  }
  window.addEventListener('online', () => {
    autoNotBefore = 0; autoFailures = 0
    if (autoEnabled) requestAuto(AUTO_DELAY)
  })
  window.addEventListener('offline', () => {
    if (autoEnabled && !busy) $('status').textContent = 'Офлайн · локальные заметки сохранены'
  })
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') requestAuto(AUTO_DELAY)
  })
  requestAuto(AUTO_DELAY)
  $('export').onclick = async () => {
    try{
      persistEditor()
      const n=notes.get(selected)
      if(!n)return
      const name=title(n).replace(/[^\p{L}\p{N} _-]/gu,'_').slice(0,100)||'Moroshka-note'
      if(imageRefs(n.text).length){
        await archiveOperation(async()=>downloadArchive(await encodeArchive([n],getImage),name+'.moroshka.json'))
        return
      }
      const a=document.createElement('a'),url=URL.createObjectURL(new Blob([n.text],{type:'text/markdown'}))
      a.href=url;a.download=name+'.md';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000)
    }catch(e){report(e)}
  }
  $('processing-settings').onclick=processingSettings
  $('ai-analyze').onclick = analyzeNote
  $('ai-search').onclick = semanticDialog
  $('ai-links').onclick = suggestLinks
  $('knowledge-dialog').addEventListener('close',()=>{knowledgeGeneration++;$('knowledge-body').replaceChildren()})
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
  $('archive-copy').onclick = async () => {
    if (archiveText === null) return
    try {
      await pullImages(archiveText,api)
      const note = create(archiveText)
      $('server-archive').close()
      select(note.id)
    } catch(e) { $('archive-status').textContent = e.message }
  }
  $('backup-export').onclick = () => {
    if ($('capture-text').value.trim()) return alertText('Сохраните черновик','В архив входят только сохранённые заметки.')
    return archiveOperation(async () => downloadArchive(await encodeArchive(notes.values(),getImage)))
  }
  $('backup-import').onclick = () => $('backup-file').click()
  $('backup-file').onchange = async () => {
    const file = $('backup-file').files[0]
    $('backup-file').value = ''
    if (!file) return
    await archiveOperation(async () => {
      if (file.size > maxArchiveBytes) throw Error('Архив больше 32 МиБ')
      const bundle = await decodeArchiveBundle(new Uint8Array(await file.arrayBuffer()))
      const plan = await planRestore(bundle.notes, notes)
      for(const [id,bytes] of bundle.images)await saveImage(bytes,id)
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
    const mode=processingMode('summary');if(!mode)return
    const n=pinnedSelection(mode==='local');if(!n)return
    if(mode==='local')return alertText('Краткое содержание · локальные фразы',localSummary(n.text))
    if (!confirm('Передать OpenRouter первые 12 000 символов заметки для краткого пересказа? Запрос может быть платным.')) return
    try { const r = await api('/api/summary','POST',{id:n.id,revision:n.base,consent:true}); alertText(r.truncated?'Резюме первых 12 000 символов':'Резюме',r.summary) } catch(e) {report(e)}
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
