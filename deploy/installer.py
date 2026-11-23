"""Entry point inside the Moroshka server installer zipapp."""
import base64
import hashlib
import os
import queue
import re
import subprocess
import sys
import tempfile
import threading
import tkinter as tk
from pathlib import Path
from tkinter import filedialog, ttk
from zipfile import ZipFile

DNS = re.compile(r"(?:[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?\.)+[A-Za-z]{2,63}")
TARGET = re.compile(r"[A-Za-z0-9_][A-Za-z0-9_-]*@([A-Za-z0-9][A-Za-z0-9.-]*)")


def host_record(scan, fingerprint):
    for line in scan.splitlines():
        fields = line.split()
        if len(fields) != 3 or fields[1] != "ssh-ed25519":
            continue
        raw = base64.b64decode(fields[2], validate=True)
        sha = base64.b64encode(hashlib.sha256(raw).digest()).decode().rstrip("=")
        if "SHA256:" + sha == fingerprint:
            return line
    raise ValueError("Отпечаток SSH-сервера отличается от указанного.")


def provision(target, domain, fingerprint, identity):
    match = TARGET.fullmatch(target)
    if not match or not DNS.fullmatch(domain):
        raise ValueError("Проверьте адрес SSH и домен.")
    if not re.fullmatch(r"SHA256:[A-Za-z0-9+/]{43}", fingerprint):
        raise ValueError("Нужен SHA256-отпечаток хост-ключа Ed25519 от провайдера VPS.")
    with ZipFile(sys.argv[0]) as bundle:
        revision = bundle.read("revision").decode().strip()
        archive = bundle.read("sources.tar")
    if not re.fullmatch(r"[0-9a-f]{40}", revision):
        raise ValueError("Повреждён номер сборки.")
    scan = subprocess.run(["ssh-keyscan", "-T", "8", "-t", "ed25519", match[1]],
                          capture_output=True, text=True, timeout=15)
    if not scan.stdout:
        raise ValueError("SSH-сервер недоступен на порту 22.")
    with tempfile.TemporaryDirectory() as tmp:
        hosts = Path(tmp) / "known_hosts"
        hosts.write_text(host_record(scan.stdout, fingerprint) + "\n")
        ssh = ["ssh", "-o", "StrictHostKeyChecking=yes", "-o", "BatchMode=yes",
               "-o", "ConnectTimeout=12", "-o", "GlobalKnownHostsFile=" + os.devnull,
               "-o", "UserKnownHostsFile=" + str(hosts)]
        if identity:
            ssh += ["-i", identity, "-o", "IdentitiesOnly=yes"]
        remote = ("set -eu; dir=$(mktemp -d); trap 'rm -rf \"$dir\"' EXIT; "
                  'tar -xf - -C "$dir"; cd "$dir"; '
                  'if [ "$(id -u)" = 0 ]; then runner=""; else runner="sudo -n"; fi; '
                  f'$runner bash deploy/install.sh {domain} {revision}')
        run = subprocess.run([*ssh, target, remote], input=archive, capture_output=True, timeout=1200)
        if run.returncode:
            raise RuntimeError(run.stderr.decode(errors="replace")[-1500:] or "Ошибка установки по SSH.")
        # Return only the pairing key, never the rest of the server configuration.
        pairing = subprocess.run([*ssh, target, "if [ \"$(id -u)\" = 0 ]; then r=''; else r='sudo -n'; fi; "
                                  "$r sed -n 's/^MOROSHKA_TOKEN=//p' /etc/moroshka/keys.env"],
                                 capture_output=True, text=True, timeout=20, check=True)
        if not pairing.stdout.strip():
            raise ValueError("Сервер запущен. Ключ подключения доступен на VPS в /etc/moroshka/keys.env.")
        return pairing.stdout.strip()


def main():
    window = tk.Tk()
    window.title("Moroshka Notes · Свой сервер")
    frame = ttk.Frame(window, padding=22)
    frame.grid(sticky="nsew")
    ttk.Label(frame, text="Свой сервер Moroshka Notes", font=("TkDefaultFont", 17)).grid(columnspan=2, sticky="w")
    values = []
    labels = ["SSH-адрес (user@host)", "Домен заметок", "Отпечаток хоста SSH (SHA256)",
              "Файл SSH-ключа или пусто для ssh-agent"]
    for index, label in enumerate(labels):
        ttk.Label(frame, text=label).grid(row=index*2+1, column=0, sticky="w", pady=(10, 2))
        field = ttk.Entry(frame, width=52)
        field.grid(row=index*2+2, column=0, sticky="ew")
        values.append(field)

    def choose():
        path = filedialog.askopenfilename()
        if path:
            values[-1].delete(0, tk.END)
            values[-1].insert(0, path)

    ttk.Button(frame, text="Выбрать", command=choose).grid(row=8, column=1)
    status = tk.StringVar(value="Для установки нужен отдельный Ubuntu 24.04 VPS и домен, направленный на него.")
    ttk.Label(frame, textvariable=status, wraplength=450).grid(row=10, columnspan=2, sticky="w", pady=10)
    result = queue.Queue()
    key = tk.StringVar()

    def poll():
        try:
            success, message = result.get_nowait()
        except queue.Empty:
            window.after(250, poll)
            return
        install.state(["!disabled"])
        if success:
            status.set("Сервер готов. Введите этот ключ в разделе «Подключение» на своих устройствах.")
            key.set(message)
            copy.state(["!disabled"])
        else:
            status.set(message)

    def begin():
        args = [v.get().strip() for v in values]
        def worker():
            try:
                result.put((True, provision(*args)))
            except (ValueError, OSError, RuntimeError, subprocess.SubprocessError) as err:
                result.put((False, str(err)))
        key.set("")
        copy.state(["disabled"])
        install.state(["disabled"])
        status.set("Проверка SSH и установка. Первый запуск может занять несколько минут.")
        threading.Thread(target=worker, daemon=True).start()
        window.after(250, poll)

    def copy_key():
        window.clipboard_clear()
        window.clipboard_append(key.get())
        status.set("Ключ подключения скопирован.")

    install = ttk.Button(frame, text="Развернуть или обновить", command=begin)
    install.grid(row=9, column=0, pady=(12,0), sticky="w")
    copy = ttk.Button(frame, text="Скопировать ключ подключения", command=copy_key)
    copy.state(["disabled"])
    copy.grid(row=11, column=0, sticky="w")
    window.mainloop()


if __name__ == "__main__":
    main()

