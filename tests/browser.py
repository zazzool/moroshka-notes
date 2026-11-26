"""End-to-end check with Python Playwright and Chromium. No production dependencies."""
import os
import json
import secrets
import subprocess
import tempfile
from pathlib import Path

from playwright.sync_api import sync_playwright, expect

ROOT = Path(__file__).resolve().parents[1]
KEY = secrets.token_hex(32)


def connect(page):
    if not page.get_by_role("button", name="Подключение", exact=True).is_visible():
        page.locator("#library-tools > summary").click()
    page.get_by_role("button", name="Подключение", exact=True).click()
    page.get_by_text("Ввести ключ вручную").click()
    page.get_by_label("Ключ синхронизации").fill(KEY)
    page.get_by_role("button", name="Подключить", exact=True).click()
    expect(page.locator("#status")).to_have_text("Синхронизировано")


def backup_check(origin, browser):
    first = browser.new_context(accept_downloads=True)
    page = first.new_page()
    page.goto(origin)
    page.get_by_role("button", name="Создать заметку").click()
    original = "# Архив\n\n[[Лес]]\n- [ ] Задача"
    page.get_by_label("Markdown").fill(original)
    page.get_by_role("button", name="Закрыть редактор").click()
    page.get_by_text("Резервные копии", exact=True).click()
    with page.expect_download() as info:
        page.get_by_role("button", name="Сохранить архив").click()
    content = info.value.path().read_bytes()
    payload = json.loads(content)
    assert payload["format"] == "moroshka-notes-backup"
    assert payload["notes"][0]["text"] == original
    assert set(payload["notes"][0]) == {"id", "text", "sha256"}
    # Divergent revision: never overwrite. Repeated import is idempotent.
    page.locator("#items button").filter(has_text="Архив").click()
    page.get_by_label("Markdown").fill("# Архив\n\nНовая правка")
    page.get_by_role("button", name="Закрыть редактор").click()
    page.evaluate("navigator.serviceWorker.ready")
    first.set_offline(True)
    page.reload()
    page.get_by_text("Резервные копии", exact=True).click()
    def restore(bytes_):
        page.locator("#backup-file").set_input_files({"name": "backup.json", "mimeType": "application/json", "buffer": bytes_})
    restore(content)
    expect(page.locator("#msgTitle")).to_have_text("Архив восстановлен")
    expect(page.locator("#msgText")).to_contain_text("Добавлено: 1. Уже есть: 0. Конфликтных копий: 1.")
    page.get_by_role("button", name="Закрыть", exact=True).click()
    expect(page.locator("#items .note")).to_have_count(2)
    restore(content)
    expect(page.locator("#msgText")).to_contain_text("Добавлено: 0. Уже есть: 1.")
    page.get_by_role("button", name="Закрыть", exact=True).click()
    expect(page.locator("#items .note")).to_have_count(2)
    corrupted = json.loads(content)
    corrupted["notes"][0]["text"] = "Повреждено"
    restore(json.dumps(corrupted).encode("utf-8"))
    expect(page.locator("#msgText")).to_contain_text("Контрольная сумма")
    page.get_by_role("button", name="Закрыть", exact=True).click()
    expect(page.locator("#items .note")).to_have_count(2)
    assert page.locator("#items").inner_text().find("Новая правка") >= 0
    first.close()


def history_check(origin, browser):
    ctx = browser.new_context()
    page = ctx.new_page()
    page.goto(origin)
    connect(page)
    page.locator("#new").click()
    original = "# История теста\n\nИсходная редакция"
    changed = "# История теста\n\nНовая редакция"
    page.get_by_label("Markdown").fill(original)
    page.locator("#sync").click()
    expect(page.locator("#status")).to_have_text("Синхронизировано")
    page.get_by_label("Markdown").fill(changed)
    page.locator("#delete-note").click()
    expect(page.locator("#msgTitle")).to_have_text("Сначала синхронизируйте заметку")
    page.get_by_role("button", name="Закрыть", exact=True).click()
    page.locator("#sync").click()
    expect(page.locator("#status")).to_have_text("Синхронизировано")
    page.locator("#history").click()
    expect(page.locator("#archive-title")).to_have_text("История сервера")
    expect(page.locator("#archive-items button")).to_have_count(1)
    page.locator("#archive-items button").first.click()
    expect(page.locator("#archive-preview")).to_have_text(original)
    page.locator("#archive-copy").click()
    expect(page.get_by_label("Markdown")).to_have_value(original)
    page.locator("#sync").click()
    expect(page.locator("#status")).to_have_text("Синхронизировано")
    page.get_by_role("button", name="Закрыть редактор").click()
    page.locator("#items .note").filter(has_text="Новая редакция").click()
    page.once("dialog", lambda dialog: dialog.accept())
    page.locator("#delete-note").click()
    expect(page.locator("#status")).to_have_text("Заметка перемещена в корзину")
    expect(page.locator("#items .note").filter(has_text="Новая редакция")).to_have_count(0)
    page.locator("#trash").click()
    page.locator("#archive-items button").filter(has_text="История теста").click()
    expect(page.locator("#archive-preview")).to_have_text(changed)
    page.locator("#archive-history").click()
    expect(page.locator("#archive-title")).to_have_text("История сервера")
    page.locator("#archive-items button").first.click()
    expect(page.locator("#archive-preview")).to_have_text(original)
    page.locator("#server-archive").get_by_role("button", name="Закрыть", exact=True).click()
    page.locator("#trash").click()
    page.locator("#archive-items button").filter(has_text="История теста").click()
    expect(page.locator("#archive-preview")).to_have_text(changed)
    page.locator("#archive-copy").click()
    expect(page.get_by_label("Markdown")).to_have_value(changed)
    page.locator("#sync").click()
    expect(page.locator("#status")).to_have_text("Синхронизировано")
    page.get_by_role("button", name="Закрыть редактор").click()
    expect(page.locator("#items .note").filter(has_text="Исходная редакция")).to_have_count(1)
    expect(page.locator("#items .note").filter(has_text="Новая редакция")).to_have_count(1)
    ctx.close()


def auto_sync_check(origin, browser):
    ctx = browser.new_context(service_workers="block")
    page = ctx.new_page()
    page.goto(origin)
    connect(page)
    page.locator("#new").click()
    original = "# Автообмен тест\n\nПервая редакция"
    newer = "# Автообмен тест\n\nПравка во время передачи"
    page.get_by_label("Markdown").fill(original)
    # Delay the first upload while typing in the same open editor.
    sent = []
    def delay_upload(route):
        req = route.request
        data = req.post_data_json if req.method == "PUT" else None
        if req.method == "PUT" and not sent and data and data.get("text") == original:
            sent.append(req.url)
            page.get_by_label("Markdown").fill(newer)
            assert page.get_by_label("Markdown").is_editable()
        route.continue_()
    page.route("**/api/notes/*", delay_upload)
    page.get_by_label("Автосинхронизация", exact=True).check()
    expect(page.locator("#saved")).to_have_text("Синхронизировано", timeout=20000)
    assert sent
    expect(page.get_by_label("Markdown")).to_have_value(newer)
    expect(page.locator("#status")).to_have_text("Синхронизировано", timeout=20000)
    # An offline edit persists locally, and connectivity resumes the exchange.
    ctx.set_offline(True)
    offline = "# Автообмен тест\n\nОфлайн правка"
    page.get_by_label("Markdown").fill(offline)
    expect(page.get_by_label("Markdown")).to_have_value(offline)
    expect(page.locator("#saved")).to_have_text("Сохранено на устройстве")
    ctx.set_offline(False)
    expect(page.locator("#saved")).to_have_text("Синхронизировано", timeout=20000)
    response = ctx.request.get(origin+"/api/notes", headers={"Authorization":"Bearer "+KEY})
    assert any(n["text"] == offline for n in response.json()["notes"])
    # Concurrent remote edit must not overwrite an open editor.
    remote = next(n for n in response.json()["notes"] if n["text"] == offline)
    server_text = "# Автообмен тест\n\nСерверная редакция"
    reply = ctx.request.put(origin+"/api/notes/"+remote["id"], headers={"Authorization":"Bearer "+KEY},
                            data={"base":remote["revision"],"text":server_text})
    assert reply.status == 200
    local_text = "# Автообмен тест\n\nНовая локальная редакция"
    page.get_by_label("Markdown").fill(local_text)
    expect(page.locator("#status")).to_contain_text("закройте редактор", timeout=20000)
    expect(page.get_by_label("Markdown")).to_have_value(local_text)
    page.get_by_role("button", name="Закрыть редактор").click()
    expect(page.locator("#items .note").filter(has_text="Серверная редакция")).to_have_count(1, timeout=20000)
    expect(page.locator("#items .note").filter(has_text="Новая локальная редакция")).to_have_count(1)
    page.get_by_label("Автосинхронизация", exact=True).uncheck()
    page.reload()
    expect(page.get_by_label("Автосинхронизация", exact=True)).not_to_be_checked()
    ctx.close()


def incremental_check(origin, browser):
    import uuid
    ctx = browser.new_context(service_workers="block")
    headers = {"Authorization": "Bearer " + KEY}
    id1, id2 = str(uuid.uuid4()), str(uuid.uuid4())
    old = "# Инкрементальный тест\n\n" + "я" * 8000
    untouched = "# Неизменная заметка\n\n" + "б" * 8000
    for note_id, text in ((id1, old), (id2, untouched)):
        res = ctx.request.put(origin + "/api/notes/" + note_id, headers=headers,
                              data={"text": text, "base": None})
        assert res.status == 200
    page = ctx.new_page()
    page.goto(origin)
    connect(page)
    calls = []
    def observe(req):
        path = req.url.split(origin, 1)[-1]
        if path.startswith("/api/sync/") or path.startswith("/api/notes"):
            calls.append((req.method, path, req.post_data_json if req.method == "POST" else None))
    page.on("request", observe)
    def sync():
        page.locator("#sync").click()
        expect(page.locator("#status")).to_have_text("Синхронизировано", timeout=20000)
    sync()
    assert calls == [("GET", "/api/sync/manifest", None)], calls
    current = ctx.request.get(origin + "/api/sync/manifest", headers=headers).json()
    revision = next(n["revision"] for n in current["notes"] if n["id"] == id1)
    updated = "# Инкрементальный тест\n\nИзменение сервера"
    reply = ctx.request.put(origin + "/api/notes/" + id1, headers=headers,
                            data={"text": updated, "base": revision})
    assert reply.status == 200
    calls.clear()
    sync()
    assert calls == [("GET", "/api/sync/manifest", None),
                     ("POST", "/api/sync/read", {"notes": [{"id": id1, "revision": reply.json()["revision"]}]})], calls
    page.locator("#items .note").filter(has_text="Изменение сервера").click()
    expect(page.get_by_label("Markdown")).to_have_value(updated)
    page.get_by_label("Markdown").fill("# Инкрементальный тест\n\nЛокальная редакция")
    page.get_by_role("button", name="Закрыть редактор").click()
    calls.clear()
    sync()
    assert calls == [("GET", "/api/sync/manifest", None), ("PUT", "/api/notes/" + id1, None)], calls
    # A 404 specifically on the capability endpoint is the legacy compatibility path.
    page.route("**/api/sync/manifest", lambda route: route.fulfill(
        status=404, content_type="application/json", body='{"error":"Not found"}'))
    calls.clear()
    sync()
    assert calls == [("GET", "/api/sync/manifest", None), ("GET", "/api/notes", None)], calls
    ctx.close()


def set_processing(page, **values):
    if not page.locator("#library-tools").evaluate("(el)=>el.open"):
        page.locator("#library-tools > summary").click()
    page.locator("#processing-settings").click()
    labels={"metadata":"Заголовки, теги и тип","summary":"Краткое содержание",
            "search":"Расширенный поиск","links":"Предложения связей","ocr":"Распознавание фото"}
    for operation, value in values.items():
        page.get_by_label(labels[operation],exact=True).select_option(value)
    page.get_by_role("button",name="Сохранить режимы").click()
    expect(page.locator("#knowledge-status")).to_have_text("Режимы сохранены на этом устройстве.")
    page.locator("#knowledge-dialog").get_by_role("button",name="Закрыть",exact=True).click()


def processing_check(origin, browser):
    ctx=browser.new_context()
    page=ctx.new_page()
    page.goto(origin)
    page.evaluate("navigator.serviceWorker.ready")
    page.locator("#new").click()
    original="# Заметка\n\nПроект исследования сна. Режим сна. Сон и память."
    page.get_by_label("Markdown").fill(original)
    calls=[]
    page.on("request",lambda req: calls.append(req.url) if "/api/ai/" in req.url or "/api/summary" in req.url else None)
    ctx.set_offline(True)
    page.locator("#ai-analyze").click()
    expect(page.locator("#knowledge-title")).to_have_text("Предложения · локальные правила")
    expect(page.get_by_text("Тип: Проект")).to_be_visible()
    page.get_by_role("button",name="Принять все").click()
    assert "Проект исследования сна" in page.get_by_label("Markdown").input_value()
    page.locator("#summary").click()
    expect(page.locator("#msgTitle")).to_have_text("Краткое содержание · локальные фразы")
    page.get_by_role("button",name="Закрыть",exact=True).click()
    page.get_by_role("button",name="Закрыть редактор").click()
    page.locator("#ai-search").click()
    expect(page.locator("#knowledge-title")).to_have_text("Расширенный поиск · локально")
    page.get_by_label("Локальный запрос").fill("сон")
    page.locator("#knowledge-dialog").get_by_role("button",name="Найти",exact=True).click()
    expect(page.locator(".knowledge-results button")).to_have_count(1)
    page.locator("#knowledge-dialog").get_by_role("button",name="Закрыть",exact=True).click()
    assert calls==[]
    set_processing(page,metadata="off")
    page.locator("#items .note").first.click()
    page.locator("#ai-analyze").click()
    expect(page.locator("#msgTitle")).to_have_text("Операция выключена")
    page.get_by_role("button",name="Закрыть",exact=True).click()
    ctx.set_offline(False)
    page.get_by_role("button",name="Закрыть редактор").click()
    connect(page)
    page.locator("#processing-settings").click()
    page.get_by_role("button",name="Настроить OpenRouter на сервере").click()
    expect(page.locator("#knowledge-title")).to_have_text("OpenRouter · сервер")
    expect(page.get_by_label("Текст и классификация",exact=True)).to_have_value("openai/gpt-4.1-mini")
    page.get_by_label("Текст и классификация",exact=True).fill("test/changed")
    page.get_by_role("button",name="Сохранить на сервере").click()
    expect(page.locator("#msgTitle")).to_have_text("OpenRouter настроен")
    page.get_by_role("button",name="Закрыть",exact=True).click()
    saved=ctx.request.get(origin+"/api/ai/config",headers={"Authorization":"Bearer "+KEY}).json()
    assert saved["model"]=="test/changed" and not saved["configured"]
    # Local OCR uses the actual WASM engine. First load model files, then run
    # recognition offline in a fresh page to prove that no provider is called.
    page.locator("#processing-settings").click()
    page.once("dialog",lambda dialog:dialog.accept())
    page.get_by_role("button",name="Установить локальный OCR").click()
    expect(page.locator("#knowledge-status")).to_have_text("Локальное распознавание готово.",timeout=120000)
    page.locator("#knowledge-dialog").get_by_role("button",name="Закрыть",exact=True).click()
    page.evaluate("navigator.serviceWorker.ready")
    page.reload()
    page.wait_for_function("navigator.serviceWorker.controller !== null")
    image=page.evaluate("""async()=>{const c=document.createElement('canvas');c.width=1200;c.height=300;
      const ctx=c.getContext('2d');ctx.fillStyle='white';ctx.fillRect(0,0,c.width,c.height);
      ctx.font='64px sans-serif';ctx.fillStyle='black';ctx.fillText('HELLO WORLD',60,110);
      ctx.fillText('ПРИВЕТ МИР',60,220);return c.toDataURL('image/jpeg').split(',')[1]}""")
    import base64
    ctx.set_offline(True)
    page.locator("#capture-photo").click()
    page.locator("#photo-source").get_by_role("button",name="Отмена").click()
    page.locator("#photo-file").set_input_files({"name":"ocr.jpg","mimeType":"image/jpeg","buffer":base64.b64decode(image)})
    expect(page.locator("#note-photos .photo-tile")).to_have_count(1)
    page.locator("#recognize-photo").click()
    expect(page.locator("#photo-status")).to_have_text("Фотография сохранена")
    calls.clear()
    page.locator("#ocr-start").click()
    expect(page.locator("#ocr-result")).to_be_visible(timeout=120000)
    recognized=page.locator("#ocr-text").input_value().upper()
    assert "HELLO" in recognized and "ПРИВЕТ" in recognized, recognized
    assert calls==[]
    page.locator("#ocr-accept").click()
    assert "HELLO" in page.get_by_label("Markdown").input_value().upper()
    ctx.close()


def knowledge_check(origin, browser):
    ctx = browser.new_context(service_workers="block")
    page = ctx.new_page()
    page.goto(origin)
    connect(page)
    set_processing(page,metadata="server",search="server",links="server")
    page.locator("#new").click()
    text="# Проверка ИИ\n\nИсходный текст #ручной"
    page.get_by_label("Markdown").fill(text)
    page.locator("#sync").click()
    expect(page.locator("#saved")).to_have_text("Синхронизировано")
    page.route("**/api/ai/analyze",lambda route: route.fulfill(
        json={**route.request.post_data_json,"title":"Новый заголовок","category":"idea","tags":["авто"],"truncated":False}))
    page.once("dialog",lambda d:d.accept())
    page.locator("#ai-analyze").click()
    expect(page.get_by_text("Заголовок: Новый заголовок")).to_be_visible()
    page.get_by_text("#авто",exact=True).click()
    page.get_by_role("button",name="Принять выбранное").click()
    expect(page.get_by_label("Markdown")).to_have_value(text+"\n\n#авто\n")
    page.locator("#ai-analyze").click()
    expect(page.locator("#msgTitle")).to_have_text("Сначала синхронизируйте заметку")
    page.get_by_role("button",name="Закрыть",exact=True).click()
    page.locator("#ai-search").click()
    expect(page.locator("#knowledge-status")).to_have_text("На сервере не настроена модель эмбеддингов.")
    page.locator("#knowledge-dialog").get_by_role("button",name="Закрыть",exact=True).click()
    # Exercise the semantic UI with deterministic HTTP fixtures. The server
    # integration tests separately verify actual index and provider handling.
    page.locator("#sync").click()
    expect(page.locator("#saved")).to_have_text("Синхронизировано")
    manifest=ctx.request.get(origin+"/api/sync/manifest",headers={"Authorization":"Bearer "+KEY}).json()
    source=next(n for n in manifest["notes"] if n["revision"]==__import__("hashlib").sha256((text+"\n\n#авто\n").encode()).hexdigest())
    target_text="# Семантическая цель\n\nПохожая заметка"
    target_id=str(__import__("uuid").uuid4())
    target=ctx.request.put(origin+"/api/notes/"+target_id,headers={"Authorization":"Bearer "+KEY},
                           data={"text":target_text,"base":None}).json()
    page.locator("#sync").click()
    expect(page.locator("#status")).to_have_text("Синхронизировано")
    indexed=[]
    def status_reply(route):
        route.fulfill(json={"enabled":True,"indexed":len(indexed),"total":1,
                            "pending":[] if indexed else [{"id":target_id,"revision":target["revision"]}]})
    def index_reply(route):
        assert route.request.post_data_json["consent"] is True
        indexed.append(True)
        route.fulfill(json={"indexed":1})
    row={"id":target_id,"title":"Семантическая цель","revision":target["revision"],"similarity":0.8}
    page.route("**/api/ai/status",status_reply)
    page.route("**/api/ai/index",index_reply)
    page.route("**/api/ai/search",lambda route:route.fulfill(json={"notes":[row]}))
    page.route("**/api/ai/related",lambda route:route.fulfill(json={"notes":[row]}))
    page.locator("#ai-search").click()
    expect(page.locator("#knowledge-status")).to_have_text("Актуальный индекс: 0 из 1")
    page.once("dialog",lambda d:d.accept())
    page.get_by_role("button",name="Индексировать следующие 1 заметок").click()
    expect(page.locator("#knowledge-status")).to_have_text("Актуальный индекс: 1 из 1")
    page.get_by_label("Смысловой запрос").fill("пример")
    page.once("dialog",lambda d:d.accept())
    page.locator("#knowledge-dialog").get_by_role("button",name="Найти",exact=True).click()
    page.locator("#knowledge-dialog").get_by_role("button",name="Семантическая цель",exact=True).click()
    expect(page.get_by_label("Markdown")).to_have_value(target_text)
    page.get_by_role("button",name="Закрыть редактор").click()
    page.locator("#items .note").filter(has_text="Проверка ИИ").click()
    page.locator("#ai-links").click()
    page.locator("#knowledge-dialog").get_by_role("button",name="Связать: Семантическая цель").click()
    expect(page.get_by_label("Markdown")).to_have_value("[[Семантическая цель]]\n\n"+text+"\n\n#авто\n")
    ctx.close()


def recovery_check(origin, browser, directory):
    import uuid
    ctx = browser.new_context(service_workers="block")
    headers = {"Authorization": "Bearer " + KEY}
    note_id = str(uuid.uuid4())
    path = origin + "/api/notes/" + note_id
    text = "# Recovery fixture\n\nLocal copy survives server rollback"
    assert ctx.request.put(path, headers=headers, data={"text": text, "base": None}).status == 200
    page = ctx.new_page()
    page.goto(origin)
    connect(page)
    page.locator("#items .note").filter(has_text="Recovery fixture").click()
    expect(page.get_by_label("Markdown")).to_have_value(text)
    # Simulate restoration of an older server volume without a deletion marker.
    (Path(directory) / (note_id + ".md")).unlink()
    page.locator("#sync").click()
    expect(page.locator("#status")).to_have_text("Синхронизировано")
    notes = ctx.request.get(origin + "/api/notes", headers=headers).json()["notes"]
    restored = next(n for n in notes if n["id"] == note_id)
    assert restored["text"] == text
    # A real tombstone must NOT be revived.
    assert ctx.request.delete(path, headers=headers, data={"base": restored["revision"]}).status == 200
    page.locator("#sync").click()
    expect(page.locator("#status")).to_have_text("Синхронизировано")
    assert page.locator("#items .note").filter(has_text="Recovery fixture").count() == 0
    snap = ctx.request.get(origin + "/api/notes", headers=headers).json()
    assert not any(n["id"] == note_id for n in snap["notes"])
    assert any(n["id"] == note_id for n in snap["deleted"])
    ctx.close()


def photo_check(origin, browser):
    import base64
    jpeg=base64.b64decode("/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAYEBQYFBAYGBQYHBwYIChAKCgkJChQODwwQFxQYGBcUFhYaHSUfGhsjHBYWICwgIyYnKSopGR8tMC0oMCUoKSj/2wBDAQcHBwoIChMKChMoGhYaKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCj/wAARCAAYADADASIAAhEBAxEB/8QAGAABAAMBAAAAAAAAAAAAAAAAAAQFBgf/xAAxEAAAAgcDCwQDAAAAAAAAAAAAAQIDBAUGBxIRFCETFhcYIlZnlaTT4iQmMVEzNUH/xAAUAQEAAAAAAAAAAAAAAAAAAAAA/8QAFBEBAAAAAAAAAAAAAAAAAAAAAP/aAAwDAQACEQMRAD8A7VFM4oEhV+tTmfz9ujyZqcqpua9ZTUiSaO0igZHspEeB/wBFTrByw3m6Bq7YtopmXm+/Wp2ZlRu88hT6t2urLM6ypEkthOsrbLbDwwMjIVOmThxMnkfmAawcsN5ugau2GsHLDeboGrthpk4cTJ5H5hpk4cTJ5H5gGsHLDeboGrtjQQVNKDo3eq12ww+L82qlJtCau6rlViskkUTO1NAi+UkcLbcRLgWMs7b97ciRyXWj90w3bLVVfj2jqspx+qi+xqgAAAAAAAAAAH//2Q==")
    ctx=browser.new_context(viewport={"width":390,"height":840},accept_downloads=True)
    page=ctx.new_page()
    page.goto(origin)
    expect(page.locator("#capture")).to_be_visible()
    assert page.locator("#capture").bounding_box()["y"]>420
    assert page.locator("aside").bounding_box()["height"] < 155
    assert not page.locator("#library-tools").evaluate("(d)=>d.open")
    page.locator("#library-tools > summary").click()
    expect(page.locator("#backup-export")).not_to_be_visible()
    expect(page.locator("#connect")).to_be_visible()
    page.locator("#library-tools > summary").click()
    page.get_by_label("О чём вы думаете?").fill("Контекст фотографии")
    page.locator("#capture-photo").click()
    page.locator("#photo-source").get_by_role("button",name="Отмена").click()
    page.locator("#photo-file").set_input_files({"name":"photo.jpg","mimeType":"image/jpeg","buffer":jpeg})
    expect(page.locator("#note-photos .photo-tile")).to_have_count(1)
    expect(page.get_by_label("Markdown")).to_have_value("# Контекст фотографии\n\nКонтекст фотографии")
    expect(page.locator("#recognize-photo")).to_be_visible()
    assert page.locator("#recognize-photo").bounding_box()["y"] + page.locator("#recognize-photo").bounding_box()["height"] <= 840
    page.wait_for_function("document.querySelector('#note-photos img')?.naturalWidth > 0")
    Path("ux-artifacts").mkdir(exist_ok=True)
    page.screenshot(path="ux-artifacts/mobile-photo-editor.png")
    page.get_by_label("Markdown").fill("# Фото\n\nТекст рядом с изображением")
    page.get_by_role("button",name="Закрыть редактор").click()
    page.screenshot(path="ux-artifacts/mobile-photo-feed.png")
    page.evaluate("navigator.serviceWorker.ready")
    ctx.set_offline(True)
    page.reload()
    page.locator("#items .note").filter(has_text="Текст рядом с изображением").click()
    expect(page.locator("#note-photos .photo-tile")).to_have_count(1)
    page.locator("#note-photos button").first.click()
    expect(page.locator("#photo-status")).to_have_text("Фотография сохранена")
    assert page.locator("#photo-image").evaluate("(img)=>img.complete && img.naturalWidth>0")
    page.locator("#photo-view").get_by_role("button",name="Закрыть").click()
    page.get_by_role("button",name="Закрыть редактор").click()
    ctx.set_offline(False)
    connect(page)
    second=browser.new_context()
    other=second.new_page();other.goto(origin);connect(other)
    set_processing(other,ocr="server")
    other.locator("#items .note").filter(has_text="Текст рядом с изображением").click()
    expect(other.locator("#note-photos .photo-tile")).to_have_count(1)
    other.locator("#note-photos button").first.click()
    expect(other.locator("#photo-status")).to_have_text("Фотография сохранена")
    other.route("**/api/ai/ocr",lambda route:route.fulfill(json={"id":route.request.post_data_json["id"],"text":"Распознанная строка"}))
    other.once("dialog",lambda d:d.accept())
    other.locator("#ocr-start").click()
    expect(other.locator("#ocr-text")).to_have_value("Распознанная строка")
    other.locator("#ocr-text").fill("Проверенный текст")
    other.locator("#ocr-accept").click()
    assert "Проверенный текст" in other.get_by_label("Markdown").input_value()
    assert "moroshka-media:" not in other.get_by_label("Markdown").input_value()
    expect(other.locator("#recognize-photo")).to_be_visible()
    with other.expect_download() as one: other.locator("#export").click()
    exported=json.loads(one.value.path().read_bytes())
    assert exported["version"]==2 and len(exported["notes"])==1 and len(exported["images"])==1
    assert "Проверенный текст" in exported["notes"][0]["text"]
    other.get_by_role("button",name="Закрыть редактор").click()
    other.get_by_text("Резервные копии",exact=True).click()
    with other.expect_download() as dl: other.locator("#backup-export").click()
    archive=dl.value.path().read_bytes()
    parsed=json.loads(archive)
    assert parsed["version"]==2 and len(parsed["images"])==1
    third=browser.new_context()
    restored=third.new_page();restored.goto(origin)
    restored.locator("#backup-file").set_input_files({"name":"photos.json","mimeType":"application/json","buffer":archive})
    expect(restored.locator("#msgTitle")).to_have_text("Архив восстановлен")
    restored.get_by_role("button",name="Закрыть",exact=True).click()
    restored.locator("#items .note").filter(has_text="Проверенный текст").click()
    expect(restored.locator("#note-photos .photo-tile")).to_have_count(1)
    third.close();second.close();ctx.close()


def check(origin, directory):
    with sync_playwright() as pw:
        browser = pw.chromium.launch(executable_path=os.getenv("CHROMIUM"), args=["--no-sandbox"])
        design = browser.new_context(viewport={"width": 360, "height": 780}, color_scheme="dark")
        preview = design.new_page()
        preview.goto(origin)
        expect(preview.get_by_label("О чём вы думаете?")).to_be_visible()
        preview.get_by_label("О чём вы думаете?").fill("Мысль для проверки #дизайн")
        expect(preview.locator("#capture-status")).to_have_text("Черновик на устройстве")
        preview.evaluate("navigator.serviceWorker.ready")
        design.set_offline(True)
        preview.reload()
        expect(preview.get_by_label("О чём вы думаете?")).to_have_value("Мысль для проверки #дизайн")
        preview.get_by_role("button", name="Сохранить", exact=True).click()
        expect(preview.locator("#capture-text")).to_have_value("")
        expect(preview.locator("#items .note")).to_have_count(1)
        assert preview.locator("#capture").bounding_box()["y"] > 390
        assert preview.locator("aside").bounding_box()["height"] < 155
        Path("ux-artifacts").mkdir(exist_ok=True)
        preview.screenshot(path="ux-artifacts/mobile-dark-feed.png")
        preview.locator("#items .note").first.click()
        expect(preview.get_by_label("Markdown")).to_have_value("# Мысль для проверки #дизайн\n\nМысль для проверки #дизайн")
        assert preview.evaluate("document.fonts.load('16px Manrope').then(fs => fs.length > 0 && fs.every(f => f.status === 'loaded'))")
        design.close()
        backup_check(origin, browser)
        first, second = browser.new_context(), browser.new_context(viewport={"width": 390, "height": 840})
        a, b = first.new_page(), second.new_page()
        a.goto(origin)
        a.get_by_role("button", name="Создать заметку").click()
        a.get_by_label("Markdown").fill("# Карта\n\n[[Река]]\n- [ ] Исследовать устье")
        a.get_by_role("button", name="＋ Река").click()
        expect(a.get_by_role("heading", name="Обратные ссылки")).to_be_visible()
        a.locator("#related").get_by_role("button", name="Карта").click()
        a.get_by_role("button", name="Задачи", exact=True).click()
        a.get_by_role("checkbox", name="Исследовать устье").check()
        a.get_by_role("button", name="Заметки", exact=True).click()
        connect(a)
        b.goto(origin)
        connect(b)
        b.locator("#items button").filter(has_text="Карта").click()
        assert "- [x]" in b.get_by_label("Markdown").input_value()
        b.get_by_label("Markdown").fill("# Карта\n\nЛокальная правка")
        a.locator("#items button").filter(has_text="Карта").click()
        a.get_by_label("Markdown").fill("# Карта\n\nДругая правка")
        a.locator("#sync").click()
        expect(a.locator("#status")).to_have_text("Синхронизировано")
        b.locator("#sync").click()
        expect(b.locator("#msgTitle")).to_have_text("Сохранены обе версии")
        b.get_by_role("button", name="Закрыть", exact=True).click()
        expect(b.get_by_label("Markdown")).to_have_value("# Карта\n\nДругая правка")
        b.get_by_role("button", name="Закрыть редактор").click()
        b.locator("#items button").filter(has_text="Конфликт").click()
        assert "Локальная правка" in b.get_by_label("Markdown").input_value()
        b.locator("#sync").click()
        expect(b.locator("#status")).to_have_text("Синхронизировано")
        # A remote deletion must retain a newer local edit as a separate note.
        b.get_by_role("button", name="Закрыть редактор").click()
        b.locator("#items button").filter(has_text="Река").click()
        b.get_by_label("Markdown").fill("# Река\n\nНеотправленная правка")
        response = first.request.get(origin + "/api/notes", headers={"Authorization": "Bearer " + KEY})
        remote = next(n for n in response.json()["notes"] if n["text"] == "# Река\n")
        deleted = first.request.delete(origin + "/api/notes/" + remote["id"],
                                       headers={"Authorization": "Bearer " + KEY}, data={"base": remote["revision"]})
        assert deleted.status == 200
        b.locator("#sync").click()
        expect(b.locator("#msgTitle")).to_have_text("Сохранены обе версии")
        b.get_by_role("button", name="Закрыть", exact=True).click()
        b.locator("#items button").filter(has_text="Неотправленная правка").click()
        assert "Неотправленная правка" in b.get_by_label("Markdown").input_value()
        b.evaluate("navigator.serviceWorker.ready")
        second.set_offline(True)
        b.reload()
        b.locator("#items button").filter(has_text="Конфликт").click()
        expect(b.get_by_label("Markdown")).to_have_value("# Конфликт · Карта\n\n# Карта\n\nЛокальная правка")
        paired = browser.new_context()
        desktop = paired.new_page()
        desktop.goto(origin)
        desktop.get_by_role("button", name="Подключение", exact=True).click()
        expect(desktop.locator("#pair-code")).to_have_text(__import__("re").compile(r"^\d{4} \d{4}$"))
        code = desktop.locator("#pair-code").inner_text().replace(" ", "")
        approved = first.request.post(origin + "/pair/approve",
                                      headers={"Authorization": "Bearer " + KEY}, data={"code": code})
        assert approved.status == 200
        expect(desktop.locator("#status")).to_have_text("Синхронизировано", timeout=10000)
        expect(desktop.locator("#settings")).not_to_be_visible()
        desktop.reload()
        desktop.locator("#sync").click()
        expect(desktop.locator("#status")).to_have_text("Синхронизировано", timeout=10000)
        history_check(origin, browser)
        auto_sync_check(origin, browser)
        incremental_check(origin, browser)
        knowledge_check(origin, browser)
        recovery_check(origin, browser, directory)
        photo_check(origin, browser)
        processing_check(origin, browser)
        browser.close()


if __name__ == "__main__":
    with tempfile.TemporaryDirectory() as directory:
        # The OS chooses a free port and Node publishes it after listen().
        bootstrap = """
          import {application} from './server/app.mjs';
          const app=application({dir:process.env.VAULT_DIR,token:process.env.MOROSHKA_TOKEN});
          app.listen(0,'127.0.0.1',()=>console.log(app.address().port));
        """
        proc = subprocess.Popen(["node", "--input-type=module", "-e", bootstrap], cwd=ROOT,
                                env={**os.environ, "VAULT_DIR": directory, "MOROSHKA_TOKEN": KEY},
                                stdout=subprocess.PIPE, text=True)
        try:
            port = int(proc.stdout.readline())
            check(f"http://127.0.0.1:{port}", directory)
            print("Browser check passed: links, tasks, offline storage, backups, history, automatic and incremental sync.")
        finally:
            proc.terminate()
            proc.wait()

