#!/usr/bin/env python3
"""Copy the Android model logos into the iOS asset catalog.

Bitmap logos (drawable-nodpi/logo_*.webp) become PNG image sets; vector
drawables (logo_openai.xml, penrose_triangle.xml) become SVG image sets that
keep their vector representation. Re-run when a logo is added on Android.
"""

import json
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

IOS_DIR = Path(__file__).resolve().parent.parent
RES = IOS_DIR.parent / "android/app/src/main/res"
OUT = IOS_DIR / "LMPlayground/Assets.xcassets/Logos"
ANDROID = "{http://schemas.android.com/apk/res/android}"
VECTORS = ["logo_openai", "penrose_triangle"]


def image_set(name: str, filename: str, vector: bool) -> Path:
    folder = OUT / f"{name}.imageset"
    folder.mkdir(parents=True, exist_ok=True)
    contents = {
        "images": [{"filename": filename, "idiom": "universal"}],
        "info": {"author": "xcode", "version": 1},
    }
    if vector:
        contents["properties"] = {"preserves-vector-representation": True}
    (folder / "Contents.json").write_text(json.dumps(contents, indent=2) + "\n")
    return folder


def color(value: str) -> str:
    # #AARRGGBB -> #RRGGBB with opacity handled by the caller; #RRGGBB as is.
    return "#" + value[-6:]


def vector_to_svg(xml_path: Path) -> str:
    root = ET.parse(xml_path).getroot()
    vw = root.get(f"{ANDROID}viewportWidth")
    vh = root.get(f"{ANDROID}viewportHeight")

    def render(node) -> str:
        out = []
        for child in node:
            tag = child.tag
            if tag == "path":
                fill = child.get(f"{ANDROID}fillColor", "#000000")
                rule = "evenodd" if child.get(f"{ANDROID}fillType") == "evenOdd" else "nonzero"
                out.append(f'<path d="{child.get(f"{ANDROID}pathData")}" fill="{color(fill)}" fill-rule="{rule}"/>')
            elif tag == "group":
                sx = child.get(f"{ANDROID}scaleX", "1")
                sy = child.get(f"{ANDROID}scaleY", "1")
                tx = child.get(f"{ANDROID}translateX", "0")
                ty = child.get(f"{ANDROID}translateY", "0")
                out.append(f'<g transform="translate({tx} {ty}) scale({sx} {sy})">{render(child)}</g>')
        return "".join(out)

    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {vw} {vh}" '
            f'width="{vw}" height="{vh}">{render(root)}</svg>\n')


def main() -> int:
    if OUT.exists():
        shutil.rmtree(OUT)
    OUT.mkdir(parents=True)
    (OUT / "Contents.json").write_text(json.dumps({"info": {"author": "xcode", "version": 1}}, indent=2) + "\n")

    count = 0
    for webp in sorted((RES / "drawable-nodpi").glob("logo_*.webp")):
        folder = image_set(webp.stem, f"{webp.stem}.png", vector=False)
        subprocess.run(["sips", "-s", "format", "png", str(webp), "--out", str(folder / f"{webp.stem}.png")],
                       check=True, capture_output=True)
        count += 1
    for name in VECTORS:
        folder = image_set(name, f"{name}.svg", vector=True)
        (folder / f"{name}.svg").write_text(vector_to_svg(RES / "drawable" / f"{name}.xml"))
        count += 1
    print(f"exported {count} logos to {OUT.relative_to(IOS_DIR.parent)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
