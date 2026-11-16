"""Generate the app's launcher and floating-overlay icons.

Style: dark chrome-steel lettering (blue-black with a bright horizon band), navy accents,
white faces, and a small walking-cat silhouette drawn here from simple shapes.
Lettering uses Cinzel Decorative (SIL Open Font License), downloaded on demand.

    python android/art/make_icons.py            # writes the res/ files
    python android/art/make_icons.py --preview out.png   # also writes a preview sheet
    python android/art/make_icons.py --launcher words    # "ANOTHER MACRO" launcher instead of "AM"

Needs Pillow and numpy only.
"""
from __future__ import annotations

import argparse
import math
import urllib.request
from pathlib import Path

import numpy as np
from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app/src/main/res"
CACHE = Path(__file__).resolve().parent / ".fonts"
FONT_URL = "https://raw.githubusercontent.com/google/fonts/main/ofl/cinzeldecorative/CinzelDecorative-{}.ttf"

NAVY = (24, 38, 86)          # subtitle / ring navy
INK = (6, 9, 16)             # outline
CAT_INK = (14, 18, 32)       # the cat is near-black like the letters
WHITE = (255, 255, 255)
SS = 4                       # supersampling factor


def font(weight: str, size: int) -> ImageFont.FreeTypeFont:
    CACHE.mkdir(exist_ok=True)
    path = CACHE / f"CinzelDecorative-{weight}.ttf"
    if not path.exists():
        urllib.request.urlretrieve(FONT_URL.format(weight), path)
    return ImageFont.truetype(str(path), size)


# ---------------------------------------------------------------- materials

def chrome(mask: Image.Image, top: int | None = None, bottom: int | None = None) -> Image.Image:
    """Fill `mask` (L) with a dark chrome-steel gradient. top/bottom bound the gradient (y px)."""
    w, h = mask.size
    box = mask.getbbox() or (0, 0, w, h)
    y0 = box[1] if top is None else top
    y1 = box[3] if bottom is None else bottom
    t = np.clip((np.arange(h, dtype=np.float32) - y0) / max(1, y1 - y0), 0, 1)
    # (position, rgb) stops: dark top, steel rise, bright horizon, hard drop, blue floor glow.
    stops = [
        (0.00, (10, 13, 22)),
        (0.18, (34, 44, 64)),
        (0.40, (120, 140, 170)),
        (0.50, (232, 240, 250)),
        (0.53, (40, 50, 72)),
        (0.62, (8, 11, 20)),
        (0.85, (44, 66, 104)),
        (1.00, (120, 150, 196)),
    ]
    xs = [s[0] for s in stops]
    col = np.stack([np.interp(t, xs, [s[1][c] for s in stops]) for c in range(3)], axis=1)
    img = np.repeat(col[:, None, :], w, axis=1)
    # Faint diagonal streaks, like brushed metal reflections.
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    streak = 0.5 + 0.5 * np.sin((xx * 0.9 + yy * 1.6) / max(6, h / 40))
    img *= (0.88 + 0.12 * streak)[..., None]
    img = np.clip(img, 0, 255).astype(np.uint8)
    out = Image.fromarray(img, "RGB").convert("RGBA")
    out.putalpha(mask)
    return out


def noise(w: int, h: int, seed: int, octaves=((6, 1.0), (14, 0.5), (32, 0.25))) -> np.ndarray:
    """Smooth value noise in 0..1 (deterministic for a seed)."""
    rng = np.random.default_rng(seed)
    acc = np.zeros((h, w), np.float32)
    for cells, amp in octaves:
        g = (rng.random((cells, cells)) * 255).astype(np.uint8)
        acc += amp * np.asarray(Image.fromarray(g, "L").resize((w, h), Image.BICUBIC), np.float32) / 255
    acc -= acc.min()
    return acc / max(1e-6, acc.max())


def marble(mask: Image.Image, seed: int = 7) -> Image.Image:
    """Near-black polished steel with blue sheen patches and pale cracked highlights
    (the logo-letter look)."""
    w, h = mask.size
    sheen = noise(w, h, seed, ((5, 1.0), (11, 0.5), (23, 0.25)))
    # Pale highlights: noise stretched vertically so it reads as streaky reflections.
    rng = np.random.default_rng(seed + 1)
    acc = np.zeros((h, w), np.float32)
    for cx, cy, amp in ((40, 9, 1.0), (90, 20, 0.5), (180, 40, 0.25)):
        g = (rng.random((cy, cx)) * 255).astype(np.uint8)
        acc += amp * np.asarray(Image.fromarray(g, "L").resize((w, h), Image.BICUBIC), np.float32) / 255
    acc = (acc - acc.min()) / max(1e-6, acc.max() - acc.min())
    yy = np.linspace(0, 1, h, dtype=np.float32)[:, None]
    blue = np.clip((sheen - 0.45) / 0.35, 0, 1)
    pale = np.clip((acc - 0.70) / 0.14, 0, 1) ** 1.4 * np.clip((sheen - 0.25) / 0.4, 0, 1)
    base = np.array([8, 10, 16], np.float32)
    deep = np.array([52, 80, 128], np.float32)
    light = np.array([206, 220, 240], np.float32)
    img = base + (deep - base) * blue[..., None]
    img = img + (light - img) * pale[..., None]
    img *= (0.80 + 0.30 * np.exp(-((yy - 0.45) / 0.25) ** 2))[..., None]   # brighter band mid-height
    out = Image.fromarray(np.clip(img, 0, 255).astype(np.uint8), "RGB").convert("RGBA")
    out.putalpha(mask)
    return out


def mist(n: int, seed: int = 3) -> Image.Image:
    """Warm off-white with faint grey cloud wisps (the game's title-screen backdrop)."""
    c = noise(n, n, seed, ((3, 1.0), (7, 0.5), (16, 0.25)))
    wisps = np.clip((c - 0.45) / 0.4, 0, 1) ** 1.6
    hi = np.array([248, 246, 245], np.float32)
    lo = np.array([214, 214, 220], np.float32)
    img = hi + (lo - hi) * wisps[..., None] * 0.55
    return Image.fromarray(img.astype(np.uint8), "RGB").convert("RGBA")


def emboss(mask: Image.Image, fill: Image.Image, outline: int, shadow: int, inline: int = 0) -> Image.Image:
    """Outline + soft shadow under a filled shape. All sizes in (supersampled) px."""
    w, h = mask.size
    layer = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    if shadow:
        sh = mask.filter(ImageFilter.MaxFilter(outline * 2 + 1)).filter(ImageFilter.GaussianBlur(shadow))
        sh = ImageChops.offset(sh, 0, shadow // 2)
        layer.alpha_composite(Image.merge("RGBA", (*[Image.new("L", (w, h), 20)] * 3, sh.point(lambda v: v * 0.45))))
    if outline:
        ring = mask.filter(ImageFilter.MaxFilter(outline * 2 + 1))
        layer.alpha_composite(Image.merge("RGBA", (*[Image.new("L", (w, h), c) for c in INK], ring)))
    layer.alpha_composite(fill)
    if inline:
        cut = engrave(mask, inline)
        layer.alpha_composite(Image.merge("RGBA", (*[Image.new("L", (w, h), c) for c in (226, 234, 246)], cut.point(lambda v: v * 0.8))))
    # Thin inner highlight along the top edge of the shape.
    hi = ImageChops.subtract(mask, ImageChops.offset(mask, 0, max(1, outline // 2)))
    layer.alpha_composite(Image.merge("RGBA", (*[Image.new("L", (w, h), 255)] * 3, hi.point(lambda v: v * 0.55))))
    return layer


def text_mask(size, text, fnt, center, tracking=0, squeeze=0.9, weight=0.0) -> Image.Image:
    """Render `text` centred at `center`, narrowed by `squeeze` (taller, like the logo's capitals)
    and thickened by `weight` x font size (Cinzel Decorative Regular is a little light for an icon)."""
    w, h = size
    wide = Image.new("L", (int(w / squeeze), h), 0)
    d = ImageDraw.Draw(wide)
    widths = [d.textlength(ch, font=fnt) for ch in text]
    total = sum(widths) + tracking * (len(text) - 1)
    x = wide.size[0] / 2 - total / 2
    for ch, cw in zip(text, widths):
        d.text((x, center[1]), ch, font=fnt, fill=255, anchor="lm")
        x += cw + tracking
    if weight:
        r = max(1, int(fnt.size * weight))
        wide = wide.filter(ImageFilter.MaxFilter(r * 2 + 1)).filter(ImageFilter.GaussianBlur(r * 0.4))
    m = Image.new("L", size, 0)
    m.paste(wide.resize((int(wide.size[0] * squeeze), h), Image.LANCZOS), (int(center[0] - w / 2), 0))
    return m


def engrave(mask: Image.Image, depth: int) -> Image.Image:
    """A thin inline cut running inside the strokes (the logo's letters carry one)."""
    inner = mask.filter(ImageFilter.MinFilter(depth * 2 + 1))
    core = inner.filter(ImageFilter.MinFilter(max(3, depth // 2 * 2 + 1)))
    return ImageChops.subtract(inner, core)


# ---------------------------------------------------------------- the cat

def cat_mask(size, x, y, s) -> Image.Image:
    """Walking-cat silhouette facing right; (x, y) = left end of its ground line, s = length."""
    m = Image.new("L", size, 0)
    d = ImageDraw.Draw(m)

    def P(px, py):
        return x + px * s, y - py * s

    def ell(cx, cy, rx, ry):
        (x0, y0), (x1, y1) = P(cx - rx, cy + ry), P(cx + rx, cy - ry)
        d.ellipse([x0, y0, x1, y1], fill=255)

    def stroke(pts, w0, w1):
        for i, (px, py) in enumerate(pts):
            r = (w0 + (w1 - w0) * i / max(1, len(pts) - 1)) * s / 2
            cx, cy = P(px, py)
            d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=255)

    def line(a, b, w0, w1, steps=24):
        stroke([(a[0] + (b[0] - a[0]) * t / steps, a[1] + (b[1] - a[1]) * t / steps) for t in range(steps + 1)], w0, w1)

    ell(0.44, 0.27, 0.31, 0.105)            # body
    ell(0.66, 0.285, 0.12, 0.11)             # chest / shoulders
    ell(0.22, 0.28, 0.12, 0.115)            # haunch
    d.polygon([P(0.66, 0.40), P(0.80, 0.46), P(0.84, 0.36), P(0.74, 0.21)], fill=255)   # neck
    ell(0.85, 0.44, 0.085, 0.08)            # head
    ell(0.925, 0.415, 0.04, 0.03)           # muzzle
    d.polygon([P(0.775, 0.47), P(0.79, 0.585), P(0.835, 0.505)], fill=255)   # ears
    d.polygon([P(0.855, 0.51), P(0.905, 0.585), P(0.915, 0.475)], fill=255)
    line((0.70, 0.23), (0.77, 0.015), 0.065, 0.05)   # front legs, mid-stride
    line((0.64, 0.21), (0.58, 0.015), 0.06, 0.05)
    line((0.25, 0.23), (0.31, 0.015), 0.075, 0.05)   # hind legs
    line((0.17, 0.23), (0.08, 0.015), 0.075, 0.05)
    tail = []
    for i in range(41):                     # raised, curling tail
        t = i / 40
        bx = (1 - t) ** 3 * 0.13 + 3 * (1 - t) ** 2 * t * 0.0 + 3 * (1 - t) * t * t * -0.02 + t ** 3 * 0.07
        by = (1 - t) ** 3 * 0.33 + 3 * (1 - t) ** 2 * t * 0.40 + 3 * (1 - t) * t * t * 0.66 + t ** 3 * 0.70
        tail.append((bx, by))
    stroke(tail, 0.06, 0.035)
    return m.filter(ImageFilter.GaussianBlur(0.8))


# ---------------------------------------------------------------- frames

def disc(size: int, inset: float) -> Image.Image:
    m = Image.new("L", (size, size), 0)
    ImageDraw.Draw(m).ellipse([inset, inset, size - inset, size - inset], fill=255)
    return m


def round_badge(px: int, glyph_mask_fn) -> Image.Image:
    """Misty off-white face, chrome bezel, navy inner ring; glyph_mask_fn(size) -> L mask of the glyph."""
    n = px * SS
    out = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    outer = disc(n, n * 0.015)
    bezel = ImageChops.subtract(outer, disc(n, n * 0.075))
    face = disc(n, n * 0.075)
    # Soft drop shadow so the white face reads on light game backgrounds.
    out.alpha_composite(Image.merge("RGBA", (*[Image.new("L", (n, n), 0)] * 3, outer.filter(ImageFilter.GaussianBlur(n * 0.01)).point(lambda v: v * 0.5))))
    m = mist(n)
    m.putalpha(face)
    out.alpha_composite(m)
    # Very light blue-grey vignette on the face.
    yy, xx = np.mgrid[0:n, 0:n].astype(np.float32)
    rr = np.hypot(xx - n / 2, yy - n / 2) / (n / 2)
    vig = (np.clip((rr - 0.55) / 0.4, 0, 1) ** 2 * 70).astype(np.uint8)
    vig = Image.fromarray(np.minimum(vig, np.array(face)), "L")
    out.alpha_composite(Image.merge("RGBA", (Image.new("L", (n, n), 150), Image.new("L", (n, n), 148), Image.new("L", (n, n), 156), vig)))
    out.alpha_composite(chrome(bezel, 0, n))
    ring = ImageChops.subtract(disc(n, n * 0.085), disc(n, n * 0.105))
    out.alpha_composite(Image.merge("RGBA", (*[Image.new("L", (n, n), c) for c in NAVY], ring)))
    g = glyph_mask_fn(n)
    out.alpha_composite(emboss(g, marble(g), outline=int(n * 0.008), shadow=int(n * 0.01)))
    return out.resize((px, px), Image.LANCZOS)


# ---------------------------------------------------------------- glyphs

def poly(n, pts):
    m = Image.new("L", (n, n), 0)
    ImageDraw.Draw(m).polygon([(x * n, y * n) for x, y in pts], fill=255)
    return m


def g_play(n):
    return poly(n, [(0.38, 0.28), (0.38, 0.72), (0.74, 0.50)])


def g_pause(n):
    m = Image.new("L", (n, n), 0)
    d = ImageDraw.Draw(m)
    for x0 in (0.33, 0.55):
        d.rounded_rectangle([x0 * n, 0.29 * n, (x0 + 0.12) * n, 0.71 * n], radius=n * 0.02, fill=255)
    return m


def g_stop(n):
    m = Image.new("L", (n, n), 0)
    ImageDraw.Draw(m).rounded_rectangle([0.31 * n, 0.31 * n, 0.69 * n, 0.69 * n], radius=n * 0.03, fill=255)
    return m


def g_home(n):
    roof = poly(n, [(0.50, 0.24), (0.77, 0.50), (0.69, 0.50), (0.69, 0.74), (0.56, 0.74), (0.56, 0.60),
                    (0.44, 0.60), (0.44, 0.74), (0.31, 0.74), (0.31, 0.50), (0.23, 0.50)])
    return roof


def g_restart(n):
    m = Image.new("L", (n, n), 0)
    d = ImageDraw.Draw(m)
    r_out, r_in = 0.24 * n, 0.15 * n
    c = n / 2
    d.pieslice([c - r_out, c - r_out, c + r_out, c + r_out], start=-40, end=250, fill=255)
    d.ellipse([c - r_in, c - r_in, c + r_in, c + r_in], fill=0)
    # Arrow head at the gap, pointing clockwise.
    a = math.radians(250)                     # the arc's leading (clockwise) end
    mid = (r_out + r_in) / 2
    px, py = c + mid * math.cos(a), c + mid * math.sin(a)
    tx, ty = -math.sin(a), math.cos(a)          # clockwise tangent
    nx, ny = math.cos(a), math.sin(a)
    h = 0.10 * n
    d.polygon([(px + nx * h, py + ny * h), (px - nx * h, py - ny * h), (px + tx * h * 1.5, py + ty * h * 1.5)], fill=255)
    return m


def g_am(n):
    f = font("Regular", int(n * 0.46))
    return text_mask((n, n), "AM", f, (n / 2, n * 0.50), tracking=-n * 0.015, weight=0.010)


# ---------------------------------------------------------------- launcher

def launcher_foreground(px: int, style: str = "words") -> Image.Image:
    """Adaptive-icon foreground (108dp square; content kept inside the 66dp safe circle)."""
    n = px * SS
    out = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    if style == "monogram":
        m = text_mask((n, n), "AM", font("Regular", int(n * 0.345)), (n / 2, n * 0.45), tracking=-n * 0.012, weight=0.008)
        out.alpha_composite(emboss(m, marble(m), outline=int(n * 0.004), shadow=int(n * 0.005)))
        ly = n * 0.70
        nav = Image.new("L", (n, n), 0)
        ImageDraw.Draw(nav).polygon([(n * 0.30, ly), (n * 0.55, ly - n * 0.006), (n * 0.70, ly),
                                     (n * 0.55, ly + n * 0.006)], fill=255)
        out.alpha_composite(Image.merge("RGBA", (*[Image.new("L", (n, n), c) for c in NAVY], nav)))
        cat = cat_mask((n, n), n * 0.52, ly - n * 0.003, n * 0.16)
        out.alpha_composite(Image.merge("RGBA", (*[Image.new("L", (n, n), c) for c in CAT_INK], cat)))
        return out.resize((px, px), Image.LANCZOS)
    m1 = text_mask((n, n), "ANOTHER", font("Regular", int(n * 0.11)), (n / 2, n * 0.405), tracking=0, weight=0.008)
    m2 = text_mask((n, n), "MACRO", font("Regular", int(n * 0.15)), (n / 2, n * 0.528), tracking=0, weight=0.008)
    fill = marble(ImageChops.lighter(m1, m2))
    out.alpha_composite(emboss(ImageChops.lighter(m1, m2), fill, outline=int(n * 0.003), shadow=int(n * 0.004)))
    # A near-black cat walking along a tapered navy line under the words.
    ly = n * 0.678
    nav = Image.new("L", (n, n), 0)
    ImageDraw.Draw(nav).polygon([(n * 0.27, ly), (n * 0.55, ly - n * 0.0055), (n * 0.73, ly),
                                 (n * 0.55, ly + n * 0.0055)], fill=255)
    out.alpha_composite(Image.merge("RGBA", (*[Image.new("L", (n, n), c) for c in NAVY], nav)))
    cat = cat_mask((n, n), n * 0.49, ly - n * 0.003, n * 0.155)
    out.alpha_composite(Image.merge("RGBA", (*[Image.new("L", (n, n), c) for c in CAT_INK], cat)))
    return out.resize((px, px), Image.LANCZOS)


def launcher_background(px: int) -> Image.Image:
    """Adaptive-icon background layer: misty off-white."""
    return mist(px * 2).resize((px, px), Image.LANCZOS)


def legacy_launcher(px: int, style: str = "words") -> Image.Image:
    """Full square preview (background + foreground layers), as a launcher would show it."""
    bg = launcher_background(px)
    bg.alpha_composite(launcher_foreground(px, style))
    return bg


# ---------------------------------------------------------------- main

DENSITIES = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}
OVERLAY = {"bubble": g_am, "play": g_play, "pause": g_pause, "stop": g_stop, "home": g_home, "restart": g_restart}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--preview", type=Path)
    ap.add_argument("--no-write", action="store_true")
    ap.add_argument("--launcher", choices=["words", "monogram"], default="monogram")
    args = ap.parse_args()

    fg = {d: launcher_foreground(px, args.launcher) for d, px in DENSITIES.items()}
    ov = {k: round_badge(176, fn) for k, fn in OVERLAY.items()}
    if not args.no_write:
        for d, im in fg.items():
            im.save(RES / f"mipmap-{d}/ic_launcher_foreground.webp", lossless=True)
            launcher_background(DENSITIES[d]).convert("RGB").save(RES / f"mipmap-{d}/ic_launcher_background.webp", quality=92)
        for k, im in ov.items():
            im.save(RES / f"drawable-nodpi/overlay_{k}.webp", lossless=True)
    if args.preview:
        preview(fg["xxxhdpi"], ov).save(args.preview)


def preview(fg, ov):
    """Launcher as the phone masks it (72dp circle / squircle of the 108dp layer) + overlay icons."""
    W, H = 1400, 640
    sheet = Image.new("RGBA", (W, H), (226, 229, 236, 255))
    n = fg.size[0]
    full = launcher_background(fg.size[0])
    full.alpha_composite(fg)
    full = full.crop((n // 6, n // 6, n - n // 6, n - n // 6))
    k = full.size[0]
    circ = Image.new("RGBA", full.size, (0, 0, 0, 0))
    circ.paste(full, (0, 0), disc(k, 0))
    sq = Image.new("L", full.size, 0)
    ImageDraw.Draw(sq).rounded_rectangle([0, 0, k, k], radius=k * 0.3, fill=255)
    rsq = Image.new("RGBA", full.size, (0, 0, 0, 0))
    rsq.paste(full, (0, 0), sq)
    sheet.alpha_composite(circ.resize((300, 300), Image.LANCZOS), (40, 40))
    sheet.alpha_composite(rsq.resize((300, 300), Image.LANCZOS), (380, 40))
    sheet.alpha_composite(circ.resize((120, 120), Image.LANCZOS), (720, 130))
    sheet.alpha_composite(rsq.resize((120, 120), Image.LANCZOS), (860, 130))
    x = 40
    for im in ov.values():
        sheet.alpha_composite(im, (x, 400))
        x += 200
    sheet.alpha_composite(Image.new("RGBA", (W - 1240, 240), (20, 26, 40, 255)), (1240, 370))
    sheet.alpha_composite(ov["bubble"].resize((120, 120), Image.LANCZOS), (1260, 430))
    return sheet


if __name__ == "__main__":
    main()
