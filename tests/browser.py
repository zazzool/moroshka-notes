"""End-to-end check with Python Playwright and Chromium. No production dependencies."""
import os
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


def check(origin):
    with sync_playwright() as pw:
        browser = pw.chromium.launch(executable_path=os.getenv("CHROMIUM"), args=["--no-sandbox"])
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

