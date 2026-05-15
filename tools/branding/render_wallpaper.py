#!/usr/bin/env python3
"""
Render the Mythara splash mark as a phone wallpaper PNG.

Background: vertical purple-black gradient — near-pure black with a
violet undertone at the top (#06040C) deepening into Mythara purple
toward the bottom (#2A1740). Reads "deep space" rather than flat
grey, while staying dark enough that lockscreen text + the petal
colors pop.

Foreground: the same 10-petal geometric rose used by splash_icon.xml —
5 large purple petals (#6B50FF) at 0/72/144/216/288 deg, 5 smaller
lavender petals (#9B86FF) interleaved at 36/108/180/252/324 deg, and
a cyan hexagon (#68FFD6) at the center.

Wordmark: "MYTHARA" (JetBrains Mono Bold, lavender, letter-spaced)
sits just below the rose, with a smaller "1.0" (JetBrains Mono
Regular, cyan) underneath as the version stamp, and the seven-word
backronym "Mind-Yoked Tonal-Haptic Adaptive Resonant Assistant"
beneath that in a muted lavender — one word per MYTHARA letter,
mapping the brand to what the system actually ships:
  M ind         the personalized LearningVault
  Y oked        tied to YOUR state, not generic
  T onal        Music Mode + Resonance binaural / isochronic
  H aptic       watch buzz on insights, reminders, sessions
  A daptive     learns continuously from your data
  R esonant     Resonance Mode closed-loop biometric regulation
  A ssistant    the agent that runs commands & talks back

Default output resolution: 1280 x 2856 (Pixel 10 Pro physical).
Override via --width / --height for other devices, --out for a
different output path. Fonts are pulled from the :app module so the
wordmark matches the watch-face / chat-UI typography.

Usage:
  python3 tools/branding/render_wallpaper.py
  python3 tools/branding/render_wallpaper.py --out /tmp/wp.png \\
      --width 1080 --height 2424   # Pixel 9 Pro Fold

After rendering, push to a paired device and apply via the
WallpaperApplyReceiver shipped in :app:

  adb -s <serial> push /tmp/mythara_wallpaper.png \\
      /sdcard/Pictures/mythara_wallpaper.png
  adb -s <serial> shell am broadcast \\
      -a com.mythara.action.APPLY_WALLPAPER \\
      -n com.mythara.debug/com.mythara.services.WallpaperApplyReceiver \\
      --es path /sdcard/Pictures/mythara_wallpaper.png \\
      --es target both

Requires: Pillow >= 10.
"""
import argparse
import math
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


# ─── Palette (matches splash_icon.xml + the watch face) ──────────────
BG_TOP = (0x06, 0x04, 0x0C)       # near-pure black with a violet bias
BG_BOT = (0x2A, 0x17, 0x40)       # deeper Mythara purple
PURPLE = (0x6B, 0x50, 0xFF)       # large petals
LAVENDER = (0x9B, 0x86, 0xFF)     # small petals + wordmark
CYAN = (0x68, 0xFF, 0xD6)         # hexagon nucleus + version stamp
SUBTITLE_COLOR = (0x76, 0x68, 0xB8)  # muted lavender for the tagline


# ─── Repo-anchored asset paths ───────────────────────────────────────
REPO_ROOT = Path(__file__).resolve().parents[2]
FONT_BOLD = REPO_ROOT / "app/src/main/res/font/jetbrains_mono_bold.ttf"
FONT_REG = REPO_ROOT / "app/src/main/res/font/jetbrains_mono_regular.ttf"


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    p.add_argument("--width", type=int, default=1280, help="output width in px (default 1280, Pixel 10 Pro)")
    p.add_argument("--height", type=int, default=2856, help="output height in px (default 2856, Pixel 10 Pro)")
    p.add_argument(
        "--out",
        type=Path,
        default=Path("/tmp/mythara_wallpaper.png"),
        help="output PNG path (default /tmp/mythara_wallpaper.png)",
    )
    return p.parse_args()


def rotate(px: float, py: float, deg: float) -> tuple[float, float]:
    """Rotate (px,py) around the origin by `deg` degrees."""
    r = math.radians(deg)
    c, s = math.cos(r), math.sin(r)
    return (px * c - py * s, px * s + py * c)


def render_petal(
    draw: ImageDraw.ImageDraw,
    cx: float,
    cy: float,
    scale: float,
    deg: float,
    color: tuple[int, int, int],
    p_local: tuple[tuple[float, float], ...],
) -> None:
    """
    Render one petal. Source paths are diamond polygons in 108-space
    centered on (54,54). p_local is a tuple of (dx,dy) offsets from
    that center *before* rotation, in source units.
    """
    pts = []
    for px, py in p_local:
        rx, ry = rotate(px, py, deg)
        pts.append((cx + rx * scale, cy + ry * scale))
    draw.polygon(pts, fill=color)


def render_gradient(img: Image.Image) -> None:
    """Vertical linear gradient from BG_TOP to BG_BOT, one row at a
    time. Pillow has no native gradient primitive but full-canvas
    line draws are cheap at phone resolutions."""
    w, h = img.size
    draw = ImageDraw.Draw(img)
    for y in range(h):
        t = y / (h - 1)
        r = round(BG_TOP[0] + (BG_BOT[0] - BG_TOP[0]) * t)
        g = round(BG_TOP[1] + (BG_BOT[1] - BG_TOP[1]) * t)
        b = round(BG_TOP[2] + (BG_BOT[2] - BG_TOP[2]) * t)
        draw.line([(0, y), (w, y)], fill=(r, g, b))


def render_rose(draw: ImageDraw.ImageDraw, cx: float, cy: float, scale: float) -> float:
    """Render the 10-petal rose + cyan hexagon centred at (cx,cy).
    Returns the y-coordinate of the rose's bottom-most pixel so the
    caller can lay the wordmark beneath it."""
    # Large purple petals (length 30, width 6) — pathData
    #   M 54,54 L 51,38 L 54,24 L 57,38 Z
    # Local offsets from (54,54): (0,0) (-3,-16) (0,-30) (3,-16)
    big = ((0, 0), (-3, -16), (0, -30), (3, -16))
    for deg in (0, 72, 144, 216, 288):
        render_petal(draw, cx, cy, scale, deg, PURPLE, big)

    # Small lavender petals (length 18, width 4) — pathData
    #   M 54,54 L 52,44 L 54,36 L 56,44 Z
    # Local offsets from (54,54): (0,0) (-2,-10) (0,-18) (2,-10)
    small = ((0, 0), (-2, -10), (0, -18), (2, -10))
    for deg in (36, 108, 180, 252, 324):
        render_petal(draw, cx, cy, scale, deg, LAVENDER, small)

    # Cyan center hexagon — pathData
    #   M 54,49 L 58.33,51.5 L 58.33,56.5 L 54,59 L 49.67,56.5 L 49.67,51.5 Z
    # Local offsets from (54,54):
    hex_pts_local = (
        (0, -5),
        (4.33, -2.5),
        (4.33, 2.5),
        (0, 5),
        (-4.33, 2.5),
        (-4.33, -2.5),
    )
    hex_pts = [(cx + dx * scale, cy + dy * scale) for dx, dy in hex_pts_local]
    draw.polygon(hex_pts, fill=CYAN)

    # Bottom-most petal pixel sits ~30 source-units below the centre
    # (the largest petal length).
    return cy + 30 * scale


def render_wordmark(draw: ImageDraw.ImageDraw, canvas_w: int, rose_bottom_y: float) -> None:
    """Lay out MYTHARA + 1.0 + the seven-word backronym subtitle below
    the rose, all centred horizontally."""
    if not FONT_BOLD.exists() or not FONT_REG.exists():
        sys.exit(f"missing JetBrains Mono fonts under {FONT_BOLD.parent}")

    # ── MYTHARA ──────────────────────────────────────────────────────
    # Bold, large, with explicit per-char letter-spacing for the wide
    # geometric look the watch face uses. Pillow doesn't expose
    # tracking, so we measure each glyph and lay them out by hand.
    mythara_font = ImageFont.truetype(str(FONT_BOLD), 96)
    tracking = 14  # extra px between glyphs
    text = "MYTHARA"
    glyph_widths = [mythara_font.getbbox(ch)[2] - mythara_font.getbbox(ch)[0] for ch in text]
    total_w = sum(glyph_widths) + tracking * (len(text) - 1)
    x = (canvas_w - total_w) // 2
    ascent, _ = mythara_font.getmetrics()
    mythara_y = rose_bottom_y + 70
    for i, ch in enumerate(text):
        # Bias x by -bbox[0] so each glyph's actual ink starts where
        # we placed the cursor, accounting for the glyph's left side
        # bearing.
        bbox = mythara_font.getbbox(ch)
        draw.text((x - bbox[0], mythara_y), ch, font=mythara_font, fill=LAVENDER)
        x += glyph_widths[i] + tracking

    # ── 1.0 ──────────────────────────────────────────────────────────
    version_font = ImageFont.truetype(str(FONT_REG), 40)
    version = "1.0"
    v_bbox = version_font.getbbox(version)
    v_w = v_bbox[2] - v_bbox[0]
    v_x = (canvas_w - v_w) // 2 - v_bbox[0]
    v_y = mythara_y + ascent + 24  # ascent ≈ baseline→top of MYTHARA
    draw.text((v_x, v_y), version, font=version_font, fill=CYAN)

    # ── Backronym tagline ───────────────────────────────────────────
    subtitle_font = ImageFont.truetype(str(FONT_REG), 30)
    subtitle = "Mind-Yoked Tonal-Haptic Adaptive Resonant Assistant"
    s_bbox = subtitle_font.getbbox(subtitle)
    s_w = s_bbox[2] - s_bbox[0]
    s_x = (canvas_w - s_w) // 2 - s_bbox[0]
    v_ascent, _ = version_font.getmetrics()
    s_y = v_y + v_ascent + 32
    draw.text((s_x, s_y), subtitle, font=subtitle_font, fill=SUBTITLE_COLOR)


def main() -> None:
    args = parse_args()
    w, h = args.width, args.height

    # Render at scale 8 against a 108-unit source viewport — gives a
    # ~864 px rose on a 1280 px canvas (~67% of width). Looks like
    # branding, not a wall of color. Scale follows width so non-Pixel-
    # 10-Pro outputs stay proportionate.
    scale = 8 * (w / 1280)
    cx = w / 2
    # Bias the rose slightly above true vertical centre so the mark
    # sits in the top third of a portrait phone — clear of the home
    # dock and (mostly) clear of the lockscreen clock band.
    cy = h / 2 - 200 * (h / 2856)

    img = Image.new("RGB", (w, h), BG_TOP)
    render_gradient(img)

    draw = ImageDraw.Draw(img)
    rose_bottom_y = render_rose(draw, cx, cy, scale)
    render_wordmark(draw, w, rose_bottom_y)

    args.out.parent.mkdir(parents=True, exist_ok=True)
    img.save(args.out, "PNG", optimize=True)
    print(f"wrote {args.out} ({w}x{h})")


if __name__ == "__main__":
    main()
