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
    ctx = browser.new_context()
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
    expect(page.locator("#status")).to_have_text("Синхронизировано", timeout=20000)
    assert sent
    expect(page.get_by_label("Markdown")).to_have_value(newer)
    expect(page.locator("#saved")).to_have_text("Синхронизировано", timeout=20000)
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


def check(origin):
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
            check(f"http://127.0.0.1:{port}")
            print("Browser check passed: links, tasks, two devices, concurrent edits, offline reload.")
        finally:
            proc.terminate()
            proc.wait()

