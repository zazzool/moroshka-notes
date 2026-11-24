#!/usr/bin/env python3
"""Independent, read-only audit of Moroshka design handoff. Not part of app tests."""
from pathlib import Path
from html.parser import HTMLParser
from urllib.parse import urlsplit, unquote
from xml.etree import ElementTree as ET
from PIL import Image, ImageChops, ImageStat
import argparse, hashlib, json, re, sys

p = argparse.ArgumentParser()
p.add_argument('--root', default=str(Path(__file__).resolve().parents[1]))
p.add_argument('--output', default=None, help='Optional JSON report path outside the package')
args = p.parse_args()
root = Path(args.root).resolve()
failures, warnings, checks = [], [], []

def check(ok, name, detail=None):
    checks.append({'check': name, 'ok': bool(ok), **({'detail': detail} if detail is not None else {})})
    if not ok:
        failures.append({'check': name, **({'detail': detail} if detail is not None else {})})

def read_json(path):
    try:
        return json.loads(path.read_text())
    except Exception as e:
        check(False, 'json-readable:'+str(path.relative_to(root)), str(e))
        return None

topics = ['cloudberry-landscape', 'mountain-dawn', 'lake-sunset', 'starry-night', 'cloudberry-macro']
backgrounds = [f'{x}-{o}' for x in topics for o in ['landscape', 'portrait']]
illustrations = ['brain-glow', 'knowledge-book', 'neural-network', 'balance-stones', 'idea-tree']
brands = ['cloudberry-3d', 'cloudberry-sprig', 'app-icon-3d']

for group, names in [('backgrounds', backgrounds), ('illustrations', illustrations), ('brand', brands)]:
    for name in names:
        path = root/'assets/masters'/group/(name+'.png')
        check(path.is_file(), 'master-present:'+group+'/'+name)

image_details = []
for path in sorted(root.rglob('*')):
    if path.suffix.lower() not in ['.png', '.webp', '.jpg', '.jpeg']:
        continue
    rel = str(path.relative_to(root))
    try:
        with Image.open(path) as im:
            im.load()
            d = {'path': rel, 'dimensions': list(im.size), 'mode': im.mode, 'bytes': path.stat().st_size}
            if 'A' in im.getbands():
                d['alpha_extrema'] = list(im.getchannel('A').getextrema())
            image_details.append(d)
            check(im.width>0 and im.height>0, 'image-decodes:'+rel)
            if rel.startswith('assets/raster/backgrounds/'):
                want = (900,1600) if path.stem.endswith('-portrait') else (1600,900)
                check(im.size == want, 'background-export-size:'+rel, {'actual':im.size, 'expected':want})
            if rel.startswith('assets/raster/illustrations/'):
                check(im.size == (1280,720), 'illustration-export-size:'+rel)
            if rel.startswith('assets/raster/brand/app-icon-') and path.stem.split('-')[-1].isdigit():
                side = int(path.stem.split('-')[-1])
                check(im.size == (side,side), 'launcher-export-size:'+rel)
            if path.stem in ['cloudberry-3d','cloudberry-sprig']:
                alpha = im.getchannel('A') if 'A' in im.getbands() else None
                check(alpha is not None and alpha.getextrema()==(0,255), 'true-alpha:'+rel)
    except Exception as e:
        check(False, 'image-decodes:'+rel, str(e))

for name in backgrounds:
    for ext in ['.webp','.jpg']:
        check((root/'assets/raster/backgrounds'/(name+ext)).is_file(), 'background-delivery-present:'+name+ext)
for name in illustrations:
    check((root/'assets/raster/illustrations'/(name+'.webp')).is_file(), 'illustration-delivery-present:'+name)
for name in brands:
    for ext in ['.png','.webp']:
        check((root/'assets/raster/brand'/(name+ext)).is_file(), 'brand-delivery-present:'+name+ext)

sources = read_json(root/'mockups/sources.json')
if sources:
    check(len(sources.get('images',[])) == 5, 'five-original-images')
    for item in sources.get('images',[]):
        f = root/item['file']
        check(f.is_file(), 'original-present:'+item['file'])
        if f.is_file():
            check(hashlib.sha256(f.read_bytes()).hexdigest()==item['sha256'], 'original-byte-identical:'+item['file'])

crop_data = read_json(root/'assets/raster/export-map.json')
if crop_data:
    rows=crop_data.get('derivations',[])
    check(len(rows)==15, 'crop-manifest-count', len(rows))
    for row in rows:
        source = root/row['master']
        if not source.is_file():
            check(False,'crop-master-exists',row['master']);continue
        with Image.open(source) as raw:
            box = row['source_crop_box']; w,h=row['size']
            check(0<=box[0]<box[2]<=raw.width+0.001 and 0<=box[1]<box[3]<=raw.height+0.001,'crop-in-bounds:'+source.name)
            check(abs((box[2]-box[0])/(box[3]-box[1])-w/h)<1e-5,'crop-proportion-no-stretch:'+source.name)
            expected=raw.convert('RGB').resize((w,h),Image.Resampling.LANCZOS,box=tuple(box))
            for output in row['exports']:
                dest=root/output
                if not dest.is_file():
                    check(False,'crop-export-exists',output);continue
                with Image.open(dest) as actual:
                    if actual.size != expected.size:
                        check(False,'crop-output-dimensions:'+output);continue
                    delta=ImageStat.Stat(ImageChops.difference(actual.convert('RGB'),expected)).mean
                    check(max(delta)<10,'crop-pixels-correspond:'+output, {'mean_rgb_error':[round(x,3) for x in delta]})

svg_count=0
for f in sorted(root.rglob('*.svg')):
    rel=str(f.relative_to(root));svg_count+=1
    try:
        doc=ET.parse(f); el=doc.getroot()
        check(el.tag.endswith('svg'),'svg-root:'+rel)
        symbols=[x for x in el.iter() if x.tag.endswith('symbol')]
        check('viewBox' in el.attrib or (bool(symbols) and all('viewBox' in x.attrib for x in symbols)),'svg-viewbox:'+rel)
        check(not any(x.tag.endswith('script') for x in el.iter()),'svg-no-script:'+rel)
        if f.parent.name=='icons':
            check(el.attrib.get('viewBox')=='0 0 24 24','icon-grid-24:'+f.name)
    except Exception as e: check(False,'svg-parses:'+rel,str(e))

coverage=read_json(root/'assets/icons-coverage.json')
if coverage:
    slots=[s for row in coverage.get('rows',[]) for s in row]
    check(len(slots)==27,'board-icons-27-slots',len(slots))
    for slot in slots:
        check((root/'assets'/slot['file']).is_file(),'board-icon-exists:'+slot['name'])
    check(len(list((root/'assets/icons').glob('*.svg')))>=27,'icon-count-at-least-27')

font=root/'assets/fonts/Manrope-Variable.ttf'
check(font.is_file(),'font-bundled')
if font.is_file():
    try:
        from fontTools.ttLib import TTFont
        ff=TTFont(font); cmap=ff.getBestCmap()
        missing=[chr(x) for x in [0x401,0x451,*range(0x410,0x450)] if x not in cmap]
        check(not missing,'font-russian-cyrillic',missing)
        check('fvar' in ff,'font-variable')
    except Exception as e: check(False,'font-parse',str(e))
license_path=root/'assets/fonts/OFL.txt'
check(license_path.is_file(),'font-license-present')
if license_path.is_file():
    t=license_path.read_text()
    check('SIL OPEN FONT LICENSE' in t.upper() and '1.1' in t,'font-license-OFL-1.1')

class Links(HTMLParser):
    def __init__(self):super().__init__();self.links=[]
    def handle_starttag(self,tag,attrs):
        for k,v in attrs:
            if k in ['src','href','poster'] and v:self.links.append(v)

broken=[];checked=0
for f in sorted(root.rglob('*')):
    if f.suffix.lower() not in ['.md','.html','.css','.svg']:continue
    t=f.read_text(); links=[]
    if f.suffix=='.md':
        links+=re.findall(r'!?\[[^\]]*\]\(([^)]+)\)',t)
    elif f.suffix=='.html':
        parser=Links();parser.feed(t);links+=parser.links
    elif f.suffix=='.svg':
        try:
            for e in ET.fromstring(t).iter():
                for k,v in e.attrib.items():
                    if k.split('}')[-1]=='href':links.append(v)
        except ET.ParseError:pass
    links+=re.findall(r'url\([\s\"\']*([^\)\"\']+)',t)
    for link in links:
        link=link.strip().split(' "',1)[0]
        if not link or link.startswith(('#','data:','javascript:')) or '${' in link:continue
        parts=urlsplit(link)
        if parts.scheme or parts.netloc:continue
        target=(f.parent/unquote(parts.path)).resolve()
        checked+=1
        if not target.exists():broken.append({'source':str(f.relative_to(root)),'link':link})
check(not broken,'local-static-links',broken)

for rel in ['README.md','HANDOFF.md','SOURCES.md','ASSET_COVERAGE.md','manifest.json','index.html','tokens.json','assets/effects/components.css']:
    check((root/rel).is_file(),'handoff-present:'+rel)
component_sheets=[x for x in (root/'assets/components').glob('*.svg') if 'sprite' not in x.name]
check(bool(component_sheets),'editable-component-sheet')
manifest=read_json(root/'manifest.json')
if manifest:
    expected=set()
    for item in manifest.get('files',[]):
        name=item['path'];expected.add(name);dest=root/name
        check(dest.is_file(),'manifest-file-present:'+name)
        if dest.is_file():
            data=dest.read_bytes()
            check(len(data)==item['bytes'] and hashlib.sha256(data).hexdigest()==item['sha256'],'manifest-integrity:'+name)
    actual={str(x.relative_to(root)) for x in root.rglob('*') if x.is_file() and x.name not in ('manifest.json','CHECKSUMS.sha256') and '__pycache__' not in x.parts}
    check(expected==actual,'manifest-exact-file-set',{'missing':sorted(expected-actual),'unlisted':sorted(actual-expected)})
    check(manifest.get('summary',{}).get('original_images')==5,'manifest-original-count')
    check(manifest.get('summary',{}).get('artwork_masters')==18,'manifest-master-count')
checksums=root/'CHECKSUMS.sha256'
check(checksums.is_file(),'checksum-file-present')
if checksums.is_file():
    for line in checksums.read_text().splitlines():
        digest,name=line.split('  ',1);dest=root/name
        check(dest.is_file() and hashlib.sha256(dest.read_bytes()).hexdigest()==digest,'checksum:'+name)

out={'root':str(root),'passed':not failures,'check_count':len(checks),'failure_count':len(failures),'failures':failures,'warnings':warnings,'svg_count':svg_count,'image_count':len(image_details),'static_links_checked':checked,'images':image_details,'checks':checks}
if args.output:
    Path(args.output).write_text(json.dumps(out,ensure_ascii=False,indent=2)+'\n')
print(json.dumps({k:out[k] for k in ['passed','check_count','failure_count','svg_count','image_count','static_links_checked','failures']},ensure_ascii=False,indent=2))
sys.exit(0 if out['passed'] else 1)
