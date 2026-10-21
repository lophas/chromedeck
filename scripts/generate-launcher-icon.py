#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
# Writes dc_ic_launcher_*.xml and preview.html to the current directory; copy the
# XML files to lorie-app/src/main/res/drawable. The icon is an original design.
# Generates the Desktop Chrome Android launcher icon (108x108 adaptive canvas)
# as Android vector drawables and an SVG/HTML preview.
def rr(x, y, w, h, r):
    return (f"M{x+r},{y} H{x+w-r} A{r},{r} 0 0 1 {x+w},{y+r} V{y+h-r} A{r},{r} 0 0 1 {x+w-r},{y+h} "
            f"H{x+r} A{r},{r} 0 0 1 {x},{y+h-r} V{y+r} A{r},{r} 0 0 1 {x+r},{y} Z")
def circ(cx, cy, r):
    return f"M{cx-r},{cy} A{r},{r} 0 1 0 {cx+r},{cy} A{r},{r} 0 1 0 {cx-r},{cy} Z"

import math
def arc(cx, cy, r, a0, a1):
    p = lambda a: (cx + r*math.cos(math.radians(a)), cy + r*math.sin(math.radians(a)))
    (x0, y0), (x1, y1) = p(a0), p(a1)
    return f"M{x0:.2f},{y0:.2f} A{r},{r} 0 0 1 {x1:.2f},{y1:.2f}"

FRAME, SCREEN = "#3C4350", "#FFFFFF"
RED, YELLOW, GREEN, BLUE = "#E8473B", "#F5B90A", "#2FA65A", "#3F7FEE"
CX, CY, R = 54, 49.5, 11.4
# Three separate tri-colour arcs with gaps (strokes) around a blue core.
ARCS = [(RED, -145, -85), (GREEN, -25, 35), (YELLOW, 95, 155)]
fg = [
    (FRAME, rr(25, 29, 58, 40, 5.5)),
    (SCREEN, rr(28.5, 32.5, 51, 33, 2.5)),
    (FRAME, rr(49.5, 69, 9, 4.5, 0.5)),
    (FRAME, rr(39, 73, 30, 4.5, 2.25)),
    (BLUE, circ(CX, CY, 5.2)),
]
strokes = [(c, arc(CX, CY, R, a0, a1)) for c, a0, a1 in ARCS]
STROKE_W = 3.6
mono = [rr(25, 29, 58, 40, 5.5) + " " + rr(28.5, 32.5, 51, 33, 2.5),
        rr(49.5, 69, 9, 4.5, 0.5), rr(39, 73, 30, 4.5, 2.25), circ(CX, CY, 5.2)]

def vector(paths, even_odd=False, stroke_paths=None):
    out = ['<?xml version="1.0" encoding="utf-8"?>',
           '<!-- SPDX-License-Identifier: GPL-3.0-only -->',
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
           '    android:width="108dp" android:height="108dp"',
           '    android:viewportWidth="108" android:viewportHeight="108">']
    for color, d in paths:
        rule = ' android:fillType="evenOdd"' if even_odd else ''
        out.append(f'    <path android:fillColor="{color}"{rule}\n        android:pathData="{d}" />')
    for color, d in (stroke_paths or []):
        out.append(f'    <path android:strokeColor="{color}" android:strokeWidth="{STROKE_W}"\n'
                   f'        android:strokeLineCap="round" android:pathData="{d}" />')
    out.append('</vector>\n')
    return "\n".join(out)

open("dc_ic_launcher_foreground.xml", "w").write(vector(fg, stroke_paths=strokes))
open("dc_ic_launcher_monochrome.xml", "w").write(vector([("#FFFFFF", d) for d in mono], True, [("#FFFFFF", d) for _, d in strokes]))
open("dc_ic_launcher_background.xml", "w").write("""<?xml version="1.0" encoding="utf-8"?>
<!-- SPDX-License-Identifier: GPL-3.0-only -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <path android:pathData="M0,0 H108 V108 H0 Z">
        <aapt:attr name="android:fillColor">
            <gradient android:type="linear" android:startX="0" android:startY="0"
                android:endX="108" android:endY="108">
                <item android:offset="0" android:color="#FFFFFFFF" />
                <item android:offset="1" android:color="#FFE3EAF5" />
            </gradient>
        </aapt:attr>
    </path>
</vector>
""")

def svg(mask, size, mono_mode=False):
    clip = {"circle": '<circle cx="54" cy="54" r="54"/>',
            "squircle": '<rect x="0" y="0" width="108" height="108" rx="30"/>',
            "rounded": '<rect x="0" y="0" width="108" height="108" rx="20"/>'}[mask]
    if mono_mode:
        body = '<rect width="108" height="108" fill="#DCE6F5"/>' + "".join(
            f'<path fill="#24406E" fill-rule="evenodd" d="{d}"/>' for d in mono) + "".join(
            f'<path fill="none" stroke="#24406E" stroke-width="{STROKE_W}" stroke-linecap="round" d="{d}"/>' for _, d in strokes)
    else:
        body = ('<defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1">'
                '<stop offset="0" stop-color="#FFFFFF"/><stop offset="1" stop-color="#E3EAF5"/></linearGradient></defs>'
                '<rect width="108" height="108" fill="url(#g)"/>' +
                "".join(f'<path fill="{c}" d="{d}"/>' for c, d in fg) +
                "".join(f'<path fill="none" stroke="{c}" stroke-width="{STROKE_W}" stroke-linecap="round" d="{d}"/>' for c, d in strokes))
    # Launchers show the central 72dp of the 108dp canvas
    return (f'<svg width="{size}" height="{size}" viewBox="18 18 72 72" xmlns="http://www.w3.org/2000/svg">'
            f'<defs><clipPath id="m{mask}{size}{mono_mode}"><g transform="translate(18 18) scale(0.6667)">{clip}</g></clipPath></defs>'
            f'<g clip-path="url(#m{mask}{size}{mono_mode})">{body}</g></svg>')

cells = []
for label, mask in (("Kör", "circle"), ("Squircle", "squircle"), ("Lekerekített", "rounded")):
    cells.append(f'<figure>{svg(mask, 144)}<figcaption>{label}</figcaption></figure>')
cells.append(f'<figure>{svg("circle", 144, True)}<figcaption>Themed (egyszínű)</figcaption></figure>')
small = "".join(svg("circle", s) for s in (96, 64, 48, 32))
html = f"""<!doctype html><html lang="hu"><head><meta charset="utf-8"><title>Desktop Chrome ikon</title>
<style>body{{font:15px system-ui;margin:24px;background:#f4f6fa;color:#1d2433}}
.row{{display:flex;flex-wrap:wrap;gap:28px;align-items:flex-end;padding:20px;border-radius:16px;margin-bottom:18px}}
.light{{background:#fff}} .dark{{background:#1b1d22;color:#e8eaf0}} figure{{margin:0;text-align:center}}
figcaption{{margin-top:8px;font-size:13px}} h2{{font-size:16px;margin:8px 0}}</style></head><body>
<h2>Launcher-maszkok</h2><div class="row light">{''.join(cells)}</div>
<h2>Sötét háttéren, különböző méretekben</h2><div class="row dark">{small}</div>
<h2>Világos háttéren, különböző méretekben</h2><div class="row light">{small}</div></body></html>"""
open("preview.html", "w").write(html)
print("ok")
