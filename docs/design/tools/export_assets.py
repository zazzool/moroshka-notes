#!/usr/bin/env python3
"""Export portable delivery formats from preserved image masters.

Requires Pillow. Does not regenerate artwork or modify the PNG masters.
Run from anywhere: python3 docs/design/tools/export_assets.py
"""
from pathlib import Path
import json
from PIL import Image, ImageOps

DESIGN = Path(__file__).resolve().parents[1]
MASTERS = DESIGN / 'assets' / 'masters'
RASTER = DESIGN / 'assets' / 'raster'
FOCUS = {
    'cloudberry-landscape': (0.43, 0.58),
    'mountain-dawn': (0.50, 0.55),
    'lake-sunset': (0.50, 0.50),
    'starry-night': (0.58, 0.48),
    'cloudberry-macro': (0.50, 0.58),
}

def cover_export(image, size, focal=(0.5, 0.5)):
    """Technical cover crop; never distort the image to force an aspect ratio."""
    width, height = image.size
    aspect = size[0] / size[1]
    if width / height > aspect:
        crop_width, crop_height = height * aspect, float(height)
    else:
        crop_width, crop_height = float(width), width / aspect
    left = (width - crop_width) * focal[0]
    top = (height - crop_height) * focal[1]
    box = (left, top, left + crop_width, top + crop_height)
    return image.resize(size, Image.Resampling.LANCZOS, box=box), [round(v, 4) for v in box]

def export():
    outputs = []
    derivations = []
    for source in sorted(MASTERS.rglob('*.png')):
        rel = source.relative_to(MASTERS)
        out = RASTER / rel.parent
        out.mkdir(parents=True, exist_ok=True)
        with Image.open(source) as raw:
            im = ImageOps.exif_transpose(raw)
            # Technical delivery sizes only; source compositions are generated separately.
            if rel.parts[0] == 'backgrounds':
                size = (900, 1600) if source.stem.endswith('-portrait') else (1600, 900)
                topic = source.stem.rsplit('-', 1)[0]
                im, box = cover_export(im.convert('RGB'), size, FOCUS.get(topic, (0.5, 0.5)))
                dst = out / (source.stem + '.webp')
                im.save(dst, 'WEBP', quality=88, method=6)
                outputs.append(str(dst.relative_to(DESIGN)))
                fallback = out / (source.stem + '.jpg')
                im.save(fallback, 'JPEG', quality=90, optimize=True, progressive=True)
                outputs.append(str(fallback.relative_to(DESIGN)))
                derivations.append({'master': str(source.relative_to(DESIGN)), 'exports': [str(dst.relative_to(DESIGN)), str(fallback.relative_to(DESIGN))], 'size': list(size), 'source_crop_box': box, 'operation': 'cover-crop-and-resample; no-stretch'})
            elif rel.parts[0] == 'illustrations':
                im, box = cover_export(im.convert('RGB'), (1280, 720))
                dst = out / (source.stem + '.webp')
                im.save(dst, 'WEBP', quality=90, method=6)
                outputs.append(str(dst.relative_to(DESIGN)))
                derivations.append({'master': str(source.relative_to(DESIGN)), 'exports': [str(dst.relative_to(DESIGN))], 'size': [1280, 720], 'source_crop_box': box, 'operation': 'cover-crop-and-resample; no-stretch'})
            elif rel.parts[0] == 'brand':
                # Retain generated alpha. PNG is the import-safe brand format.
                im = im.convert('RGBA')
                im.thumbnail((1024, 1024), Image.Resampling.LANCZOS)
                dst = out / source.name
                im.save(dst, 'PNG', optimize=True)
                outputs.append(str(dst.relative_to(DESIGN)))
                webp = out / (source.stem + '.webp')
                im.save(webp, 'WEBP', lossless=True, method=6)
                outputs.append(str(webp.relative_to(DESIGN)))
                if source.stem == 'app-icon-3d':
                    for side in (48, 72, 96, 144, 192, 512):
                        sized = out / f'app-icon-{side}.png'
                        im.resize((side, side), Image.Resampling.LANCZOS).save(sized, 'PNG', optimize=True)
                        outputs.append(str(sized.relative_to(DESIGN)))
    (RASTER / 'export-map.json').write_text(json.dumps({'derivations': derivations, 'note': 'PNG masters remain intact; crop boxes are recorded in original pixel coordinates.'}, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({'exports': outputs, 'count': len(outputs)}, ensure_ascii=False, indent=2))

if __name__ == '__main__':
    export()
