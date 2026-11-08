"""Generate the launcher icon foreground (vector) and a 1024 px PNG.

Design: the Traditional Chinese character 語 in white, with light-blue
sound-wave bars at both sides, on dark navy. The glyph comes from Noto Sans TC Bold (SIL OFL 1.1).

Usage: python make_icon.py NotoSansTC-Bold.otf ic_launcher_foreground.xml icon-1024.png
"""
import sys

from fontTools.pens.boundsPen import BoundsPen
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont
from PIL import Image, ImageDraw, ImageFont

FONT, OUT_XML, OUT_PNG = sys.argv[1:4]
NAVY, BLUE, WHITE = "#101418", "#7DD3FC", "#FFFFFF"

# All sizes are in the 108-unit adaptive-icon canvas (safe zone = circle r33 around 54,54).
GLYPH_SIZE = 38.0                                # longest side of 語
# Sound-wave bars only at the sides, never behind 語 (they made its 口 boxes look filled at 48 px).
BARS = ((-29.5, 9.0), (-23.5, 17.0), (23.5, 17.0), (29.5, 9.0))   # (x offset from centre, height)
BAR_W = 3.4

# ---- glyph outline, centred on 54,54
font = TTFont(FONT)
gs = font.getGlyphSet()
glyph = gs[font.getBestCmap()[ord("語")]]
bp = BoundsPen(gs)
glyph.draw(bp)
xmin, ymin, xmax, ymax = bp.bounds
s = GLYPH_SIZE / max(xmax - xmin, ymax - ymin)
ox = 54 - (xmin + xmax) / 2 * s
oy = 54 + (ymin + ymax) / 2 * s
pen = SVGPathPen(gs, ntos=lambda v: ("%.2f" % v).rstrip("0").rstrip("."))
glyph.draw(TransformPen(pen, (s, 0, 0, -s, ox, oy)))
glyph_path = pen.getCommands()

bars = [(54 + dx, h) for dx, h in BARS]


def bar_segment(x, h):
    """A round-capped stroke whose total visible length is h."""
    half = max(h - BAR_W, 0.01) / 2
    return f"M{x:.2f},{54 - half:.2f} V{54 + half:.2f}"


xml = f'''<?xml version="1.0" encoding="utf-8"?>
<!-- 圖示：「語」兩側聲波（繁體，Noto Sans TC Bold 字形，SIL OFL 1.1）。由 tools/make_icon.py 產生 -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path android:strokeColor="{BLUE}" android:strokeWidth="{BAR_W}" android:strokeLineCap="round" android:pathData="{' '.join(bar_segment(x, h) for x, h in bars)}" />
    <path android:fillColor="{WHITE}" android:pathData="{glyph_path}" />
</vector>
'''
with open(OUT_XML, "w", encoding="utf-8") as fh:
    fh.write(xml)

# ---- PNG with the same geometry (for the store listing)
N = 2048
k = N / 108
im = Image.new("RGB", (N, N), NAVY)
d = ImageDraw.Draw(im)
for x, h in bars:
    d.rounded_rectangle([(x - BAR_W / 2) * k, (54 - h / 2) * k, (x + BAR_W / 2) * k, (54 + h / 2) * k],
                        radius=BAR_W / 2 * k, fill=BLUE)
m = Image.new("L", (2000, 2000), 0)
ImageDraw.Draw(m).text((150, 0), "語", font=ImageFont.truetype(FONT, 1600), fill=255)
m = m.crop(m.getbbox())
gw, gh = (xmax - xmin) * s * k, (ymax - ymin) * s * k
m = m.resize((round(gw), round(gh)), Image.LANCZOS)
pos = (round(54 * k - m.width / 2), round(54 * k - m.height / 2))
im.paste(Image.new("RGB", m.size, WHITE), pos, m)
im.resize((1024, 1024), Image.LANCZOS).save(OUT_PNG)
