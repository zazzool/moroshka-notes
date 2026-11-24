#!/usr/bin/env python3
"""Static handoff checks; this does not compile Android resources or emulate a launcher."""
from pathlib import Path
from PIL import Image
import hashlib
import json
import math
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent
RES = ROOT / "res"
ANDROID = "{http://schemas.android.com/apk/res/android}"
FOREGROUND_SHA256 = "58e250065fc96944364c50e4385b6cd0bbb3a8f16e450a5df14a97e3f06bcd80"


def radius_bound(path_data, center):
    """Conservative bounds for this mark's M/c/C/half-circle a/Z paths.

    Cubics lie inside their control-point convex hull. The supported arcs are
    semicircles: their full circle is a conservative bound. Reject other syntax.
    """
    tokens = re.findall(r"[A-Za-z]|[-+]?(?:\d*\.\d+|\d+)(?:[eE][-+]?\d+)?", path_data)
    pos, command, current, start, radius = 0, None, (0., 0.), (0., 0.), 0.
    lengths = {"M": 2, "C": 6, "A": 7}
    def point_bound(point):
        return math.hypot(point[0]-center[0], point[1]-center[1])
    while pos < len(tokens):
        if tokens[pos].isalpha():
            command = tokens[pos]
            pos += 1
            if command.upper() == "Z":
                current = start
                radius = max(radius, point_bound(current))
                continue
        upper = command.upper()
        assert upper in lengths, f"Unsupported vector path command: {command}"
        count = lengths[upper]
        numbers = list(map(float, tokens[pos:pos+count]))
        assert len(numbers) == count
        pos += count
        offset = current if command.islower() else (0., 0.)
        if upper == "M":
            current = (numbers[0]+offset[0], numbers[1]+offset[1])
            start = current
            radius = max(radius, point_bound(current))
        elif upper == "C":
            points = [(numbers[i]+offset[0], numbers[i+1]+offset[1]) for i in (0, 2, 4)]
            radius = max(radius, point_bound(current), *(point_bound(p) for p in points))
            current = points[-1]
        else:
            rx, ry, rotation, large, sweep, dx, dy = numbers
            end = (dx+offset[0], dy+offset[1])
            assert rx == ry and rotation == 0 and end[1] == current[1]
            assert abs(end[0]-current[0]) == 2*rx
            circle_center = ((current[0]+end[0])/2, current[1])
            radius = max(radius, point_bound(circle_center)+rx)
            current = end
    return radius


def main():
    files = sorted(p for p in RES.rglob("*") if p.is_file())
    identifiers = {(p.parent.name.split("-")[0], p.stem) for p in files}
    xml_paths = [p for p in files if p.suffix == ".xml"]
    references = []
    for file in xml_paths:
        tree = ET.parse(file)
        for element in tree.iter():
            for value in element.attrib.values():
                if value.startswith("@"): 
                    match = re.fullmatch(r"@(drawable|mipmap)/([a-z0-9_]+)", value)
                    assert match, f"Unexpected resource reference: {value}"
                    assert (match[1], match[2]) in identifiers, f"Missing reference: {value}"
                    references.append(value)
    art = RES / "drawable-nodpi/moroshka_launcher_foreground_art.png"
    sha = hashlib.sha256(art.read_bytes()).hexdigest()
    assert sha == FOREGROUND_SHA256, "Foreground bytes differ from the approved original"
    im = Image.open(art)
    im.verify()
    im = Image.open(art)
    assert im.mode == "RGBA" and im.width == im.height
    alpha = im.getchannel("A")
    w, h = im.size
    box_dp = 74.0  # Slightly larger than the ideal 73.98dp fractional-inset box.
    max_radius, max_all_alpha = 0.0, 0.0
    min_x, min_y, max_x, max_y = w, h, -1, -1
    faint_outside = 0
    for index, value in enumerate(alpha.tobytes()):
        if value == 0:
            continue
        x, y = index % w, index // w
        radius = math.hypot(((x+.5)/w-.5)*box_dp, ((y+.5)/h-.5)*box_dp)
        max_all_alpha = max(max_all_alpha, radius)
        if value >= 2:
            max_radius = max(max_radius, radius)
            min_x, min_y = min(min_x, x), min(min_y, y)
            max_x, max_y = max(max_x, x), max(max_y, y)
        elif radius > 33:
            faint_outside += 1
    pixel_half_diagonal = math.hypot(box_dp/w, box_dp/h)/2
    assert max_radius + pixel_half_diagonal < 33
    inset = ET.parse(RES / "drawable-v26/moroshka_launcher_foreground.xml").getroot()
    assert all(inset.get(ANDROID+key) == "15.75%" for key in ("insetLeft", "insetRight", "insetTop", "insetBottom"))

    mono_file = RES / "drawable/moroshka_launcher_monochrome.xml"
    mono = ET.parse(mono_file).getroot()
    group = mono.find("group")
    sx, sy = float(group.get(ANDROID+"scaleX")), float(group.get(ANDROID+"scaleY"))
    tx, ty = float(group.get(ANDROID+"translateX")), float(group.get(ANDROID+"translateY"))
    assert sx == sy == .68
    source_center = ((54-tx)/sx, (54-ty)/sy)
    vector_radius = 0.0
    for path in group.findall("path"):
        assert path.get(ANDROID+"strokeLineCap") == "round"
        assert path.get(ANDROID+"strokeLineJoin") == "round"
        stroke = float(path.get(ANDROID+"strokeWidth"))
        vector_radius = max(vector_radius, (radius_bound(path.get(ANDROID+"pathData"), source_center) + stroke/2)*sx)
    assert vector_radius < 33

    legacy = {}
    for density, pixels in (("mdpi",48), ("hdpi",72), ("xhdpi",96), ("xxhdpi",144), ("xxxhdpi",192)):
        file = RES / f"mipmap-{density}/moroshka_launcher.png"
        size = Image.open(file).size
        assert size == (pixels, pixels)
        legacy[density] = list(size)
    result = {
        "status": "passed-static-handoff-checks",
        "android_build_run": False,
        "device_or_launcher_run": False,
        "xml_files_parsed": len(xml_paths),
        "local_resource_references_resolved": len(references),
        "foreground_sha256": sha,
        "foreground_original_unchanged": True,
        "geometry": {
            "design_canvas_dp": [108,108], "safe_circle_diameter_dp": 66,
            "inset_each_edge_percent": 15.75,
            "ideal_art_square_dp": 108*(1-2*.1575),
            "conservative_checked_square_dp": box_dp,
            "visible_alpha_threshold_inclusive": 2,
            "max_visible_pixel_center_radius_dp": max_radius,
            "max_visible_pixel_extent_radius_dp": max_radius+pixel_half_diagonal,
            "visible_bounds_px": [min_x,min_y,max_x+1,max_y+1],
            "visible_width_height_dp": [(max_x-min_x+1)/w*box_dp,(max_y-min_y+1)/h*box_dp],
            "max_any_nonzero_alpha_radius_dp": max_all_alpha,
            "alpha_1_pixels_outside_circle": faint_outside,
            "all_nonzero_alpha_inside_circle": max_all_alpha+pixel_half_diagonal <= 33,
            "monochrome_strict_conservative_radius_dp": vector_radius,
            "monochrome_method": "cubic control-point hull and full arc-circle bounds plus half round stroke width"
        },
        "legacy_export_sizes": legacy,
        "limitations": ["Alpha-1 noise outside the visible mark was preserved, not retouched.", "Android rounding, inflation, masking and launcher rendering still require Android Studio/device validation."]
    }
    (ROOT / "validation.json").write_text(json.dumps(result,ensure_ascii=False,indent=2)+"\n", encoding="utf-8")
    print(json.dumps(result,ensure_ascii=False,indent=2))


if __name__ == "__main__":
    main()
