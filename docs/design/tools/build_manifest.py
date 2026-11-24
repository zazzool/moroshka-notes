#!/usr/bin/env python3
"""Rebuild the package inventory after an intentional asset change."""
from pathlib import Path
from PIL import Image
import hashlib, json

ROOT = Path(__file__).resolve().parents[1]
EXCLUDE = {'manifest.json', 'CHECKSUMS.sha256'}

def main():
    rows = []
    for path in sorted(ROOT.rglob('*')):
        if not path.is_file() or path.name in EXCLUDE or '__pycache__' in path.parts:
            continue
        rel = path.relative_to(ROOT).as_posix()
        data = path.read_bytes()
        entry = {'path': rel, 'bytes':len(data), 'sha256':hashlib.sha256(data).hexdigest(),
                 'git_blob_sha':hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest()}
        if rel.startswith('mockups/') and path.suffix == '.png':
            entry['provenance'] = 'original-conversation-image'
        elif rel.startswith('assets/masters/'):
            entry['provenance'] = 'recreated-reference-master'
        elif rel.startswith('assets/raster/') and path.suffix in ('.png','.webp','.jpg'):
            entry['provenance'] = 'technical-export-of-preserved-master'
        elif rel.startswith('assets/fonts/'):
            entry['provenance'] = 'google-fonts-manrope-OFL-1.1'
        if path.suffix.lower() in ('.png','.webp','.jpg','.jpeg'):
            with Image.open(path) as im:
                entry.update({'width':im.width, 'height':im.height, 'mode':im.mode})
        rows.append(entry)
    summary = {
        'files_listed':len(rows), 'bytes_listed':sum(r['bytes'] for r in rows),
        'original_images':sum(r.get('provenance')=='original-conversation-image' for r in rows),
        'artwork_masters':sum(r.get('provenance')=='recreated-reference-master' for r in rows),
        'interface_svg_icons':len(list((ROOT/'assets/icons').glob('*.svg'))),
        'interface_png_icons':len(list((ROOT/'assets/icons-png').glob('*.png'))),
        'board_icon_slots':27,
    }
    manifest = {'name':'Moroshka design handoff', 'version':'2.2', 'date':'2026-11-24',
                'scope':'docs/design/', 'self_exclusion':['manifest.json','CHECKSUMS.sha256'],
                'summary':summary, 'files':rows}
    target = ROOT / 'manifest.json'
    target.write_text(json.dumps(manifest,ensure_ascii=False,indent=2) + '\n')
    checksum_rows = [(r['path'],r['sha256']) for r in rows] + [('manifest.json',hashlib.sha256(target.read_bytes()).hexdigest())]
    (ROOT/'CHECKSUMS.sha256').write_text(''.join(f'{sha}  {name}\n' for name,sha in sorted(checksum_rows)))
    print(json.dumps(summary,ensure_ascii=False,indent=2))

if __name__ == '__main__':
    main()
