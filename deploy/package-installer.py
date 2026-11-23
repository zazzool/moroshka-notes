"""Bundle the committed server, installer and revision into a single Python zipapp."""
import subprocess
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED

root = Path(__file__).resolve().parents[1]
revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root)
sources = subprocess.check_output(["git", "archive", "HEAD", "Dockerfile", ".dockerignore",
                                   "server", "web", "deploy/Caddyfile", "deploy/compose.yaml",
                                   "deploy/install.sh"], cwd=root)
entry = subprocess.check_output(["git", "show", "HEAD:deploy/installer.py"], cwd=root)
dest = root / "release" / "Moroshka-Server.pyz"
dest.parent.mkdir(exist_ok=True)
with ZipFile(dest, "w", compression=ZIP_DEFLATED) as bundle:
    bundle.writestr("__main__.py", entry)
    bundle.writestr("revision", revision)
    bundle.writestr("sources.tar", sources)
print(dest)

