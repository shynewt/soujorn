"""Generates the Sojourn icon drawables.

    python3 -I tools/build_icon.py app/src/main/res [preview.svg]

The mark: an arch, the place you stay, framing a sun over the sea. A trail of dots, one per
day, climbs around it and ends in the apricot dot of today. VectorDrawable has no dashed
strokes, so the trail is laid out here as separate circles.
"""

import math
import sys

CX, CY, R = 54, 50, 23
TRAIL_START = (31, 84)
TRAIL_END_DEG = 36
TODAY_DEG = 30
SPACING = 5.3
DOT = 1.1

ARCH = "M37,78 V50 A17,17 0 0 1 71,50 V78 Q71,80 69,80 H39 Q37,80 37,78 Z"
WAVE_BACK = "M30,66 C39,60 46,60 54,64.5 S69,70 78,62 V84 H30 Z"
WAVE_FRONT = "M30,73 C38,69 45,68.5 53,72 S68,76.5 78,71 V84 H30 Z"


def point(deg):
    a = math.radians(deg)
    return CX + R * math.cos(a), CY - R * math.sin(a)


def trail():
    """Evenly spaced dots up the left side, over the top, ending before today's dot."""
    dots = []
    x0, y0 = TRAIL_START
    straight = y0 - CY
    arc = math.radians(180 - TRAIL_END_DEG) * R
    total = straight + arc
    d = 0.0
    while d <= total + 1e-6:
        if d <= straight:
            dots.append((x0, y0 - d))
        else:
            angle = 180 - math.degrees((d - straight) / R)
            dots.append(point(angle))
        d += SPACING
    return dots


def circle(x, y, r):
    return f"M{x - r:.2f},{y:.2f} a{r},{r} 0 1,0 {2 * r},0 a{r},{r} 0 1,0 {-2 * r},0 Z"


def gradient(x1, y1, x2, y2, stops):
    items = "".join(f'<item android:offset="{o}" android:color="{c}"/>' for o, c in stops)
    return (
        '<aapt:attr name="android:fillColor">'
        f'<gradient android:type="linear" android:startX="{x1}" android:startY="{y1}" '
        f'android:endX="{x2}" android:endY="{y2}">{items}</gradient></aapt:attr>'
    )


HEADER = (
    '<?xml version="1.0" encoding="utf-8"?>\n'
    '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
    '    xmlns:aapt="http://schemas.android.com/aapt"\n'
    '    android:width="{size}dp" android:height="{size}dp"\n'
    '    android:viewportWidth="{view}" android:viewportHeight="{view}">\n'
)


def foreground():
    dots = "".join(circle(x, y, DOT) for x, y in trail())
    tx, ty = point(TODAY_DEG)
    return (
        HEADER.format(size=108, view=108)
        + f'  <path android:fillColor="#FFFFE7CF" android:fillAlpha="0.55" android:pathData="{dots}"/>\n'
        + f'  <path android:fillColor="#FFFF8A4C" android:fillAlpha="0.25" android:pathData="{circle(tx, ty, 5.6)}"/>\n'
        + f'  <path android:fillColor="#FFFF8A4C" android:pathData="{circle(tx, ty, 3.4)}"/>\n'
        + f'  <path android:pathData="{ARCH}">{gradient(54, 33, 54, 80, [(0, "#FFFFF7EC"), (1, "#FFFFD9B8")])}</path>\n'
        + f'  <group>\n    <clip-path android:pathData="{ARCH}"/>\n'
        + f'    <path android:pathData="{circle(54, 62, 11.5)}">{gradient(54, 50.5, 54, 73.5, [(0, "#FFFFC77D"), (1, "#FFFF6A3D")])}</path>\n'
        + f'    <path android:fillColor="#FF2BA597" android:pathData="{WAVE_BACK}"/>\n'
        + f'    <path android:fillColor="#FF147A72" android:pathData="{WAVE_FRONT}"/>\n'
        + "  </group>\n</vector>\n"
    )


def background():
    return (
        HEADER.format(size=108, view=108)
        + f'  <path android:pathData="M0,0h108v108h-108z">{gradient(0, 0, 108, 108, [(0, "#FF14837A"), (1, "#FF062B2D")])}</path>\n'
        + "</vector>\n"
    )


# The silhouette: the arch with the sun and one wave cut out, plus the trail.
SUN_HALF = "M42.5,63 A11.5,11.5 0 0 1 65.5,63 Z"
WAVE_BAND = "M37,65 C43,61.5 48,61.5 54,64.5 S66,69.5 71,64.5 L71,68.5 C66,73.5 60,72 54,68.5 S43,65.5 37,69 Z"


def silhouette(size, view, transform=""):
    dots = "".join(circle(x, y, DOT * 1.2) for x, y in trail())
    tx, ty = point(TODAY_DEG)
    group_open = f'  <group {transform}>\n' if transform else "  <group>\n"
    return (
        HEADER.format(size=size, view=view)
        + group_open
        + f'    <path android:fillColor="#FFFFFFFF" android:fillType="evenOdd" android:pathData="{ARCH} {SUN_HALF} {WAVE_BAND}"/>\n'
        + f'    <path android:fillColor="#FFFFFFFF" android:pathData="{dots} {circle(tx, ty, 3.6)}"/>\n'
        + "  </group>\n</vector>\n"
    )


def preview_svg():
    dots = "".join(f'<circle cx="{x:.2f}" cy="{y:.2f}" r="{DOT}"/>' for x, y in trail())
    tx, ty = point(TODAY_DEG)
    return f"""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="432" height="432">
<defs>
<linearGradient id="bg" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#14837A"/><stop offset="1" stop-color="#062B2D"/></linearGradient>
<linearGradient id="sun" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#FFC77D"/><stop offset="1" stop-color="#FF6A3D"/></linearGradient>
<linearGradient id="sky" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#FFF7EC"/><stop offset="1" stop-color="#FFD9B8"/></linearGradient>
<clipPath id="arch"><path d="{ARCH}"/></clipPath><clipPath id="sq"><rect width="108" height="108" rx="30"/></clipPath>
</defs>
<g clip-path="url(#sq)"><rect width="108" height="108" fill="url(#bg)"/>
<g fill="#FFE7CF" fill-opacity="0.55">{dots}</g>
<circle cx="{tx:.2f}" cy="{ty:.2f}" r="5.6" fill="#FF8A4C" fill-opacity="0.25"/><circle cx="{tx:.2f}" cy="{ty:.2f}" r="3.4" fill="#FF8A4C"/>
<path d="{ARCH}" fill="url(#sky)"/>
<g clip-path="url(#arch)"><circle cx="54" cy="62" r="11.5" fill="url(#sun)"/><path d="{WAVE_BACK}" fill="#2BA597"/><path d="{WAVE_FRONT}" fill="#147A72"/></g>
</g></svg>
"""


def main():
    res = sys.argv[1]
    files = {
        "drawable/ic_launcher_foreground.xml": foreground(),
        "drawable/ic_launcher_background.xml": background(),
        "drawable/ic_launcher_monochrome.xml": silhouette(108, 108),
        # The trail spans x 30..78 and y 26..85; scale it into the 24dp status bar grid.
        "drawable/ic_stat_sojourn.xml": silhouette(
            24, 24, 'android:scaleX="0.37" android:scaleY="0.37" android:translateX="-7.98" android:translateY="-8.4"'
        ),
    }
    for path, text in files.items():
        with open(f"{res}/{path}", "w") as out:
            out.write(text)
    if len(sys.argv) > 2:
        with open(sys.argv[2], "w") as out:
            out.write(preview_svg())


main()
