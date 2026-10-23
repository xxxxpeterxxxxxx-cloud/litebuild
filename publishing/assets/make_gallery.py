#!/usr/bin/env python3
"""Compose LiteBuild project icon + professional gallery cards.

Icon: crop watermark, square, 512x512.
Cards: 1600x900, dark gradient, accent bar, EN headline + sub,
rounded screenshot inset with shadow, wordmark bottom-left.
"""
import math
import os
from PIL import Image, ImageDraw, ImageFilter, ImageFont, ImageEnhance, ImageOps

ASSETS = os.path.dirname(os.path.abspath(__file__))
GALLERY = os.path.join(ASSETS, "gallery")
OUT = os.path.join(ASSETS, "out")
os.makedirs(OUT, exist_ok=True)

FONT_PATH = "/usr/share/fonts/google-noto-vf/NotoSans[wght].ttf"

def font(weight, size):
    f = ImageFont.truetype(FONT_PATH, size)
    try:
        f.set_variation_by_axes([weight])
    except Exception:
        pass
    return f

F_HEAD = font(700, 64)
F_SUB = font(400, 30)
F_MARK = font(600, 26)
F_TAG = font(700, 22)

ACCENT = (56, 224, 215)      # teal
ACCENT2 = (88, 166, 255)     # blue
BG_TOP = (10, 14, 28)
BG_BOT = (18, 26, 46)
WHITE = (245, 248, 252)
GRAY = (164, 178, 200)


def rounded(img, radius):
    m = Image.new("L", img.size, 0)
    d = ImageDraw.Draw(m)
    d.rounded_rectangle([0, 0, img.size[0], img.size[1]], radius=radius, fill=255)
    out = img.convert("RGBA")
    out.putalpha(m)
    return out


def vgrad(size, top, bot):
    w, h = size
    grad = Image.new("RGB", (1, h))
    for y in range(h):
        t = y / max(1, h - 1)
        grad.putpixel((0, y), tuple(int(a + (b - a) * t) for a, b in zip(top, bot)))
    return grad.resize((w, h))


def add_glow(base, box, color, radius=40, alpha=110):
    glow = Image.new("RGBA", base.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(glow)
    d.rounded_rectangle(box, radius=24, fill=color + (alpha,))
    glow = glow.filter(ImageFilter.GaussianBlur(radius))
    base.alpha_composite(glow)


def card(shot_path, headline, sub, out_name, icon_img=None):
    W, H = 1600, 900
    bg = vgrad((W, H), BG_TOP, BG_BOT).convert("RGBA")
    d = ImageDraw.Draw(bg)

    shot = Image.open(shot_path).convert("RGBA")
    # target screenshot area — leaves clean headline + footer zones
    max_w, max_h = 1100, 570
    ratio = min(max_w / shot.width, max_h / shot.height)
    sw, sh = int(shot.width * ratio), int(shot.height * ratio)
    shot = shot.resize((sw, sh), Image.LANCZOS)
    shot = rounded(shot, 18)

    sx = (W - sw) // 2
    sy = 235

    add_glow(bg, [sx - 8, sy - 8, sx + sw + 8, sy + sh + 8], ACCENT, 55, 60)
    # crisp border
    bd = ImageDraw.Draw(bg)
    bd.rounded_rectangle([sx - 2, sy - 2, sx + sw + 2, sy + sh + 2],
                         radius=20, outline=ACCENT + (140,), width=2)
    bg.alpha_composite(shot, (sx, sy))

    # headline + accent bar
    bd.rounded_rectangle([80, 92, 84, 152], radius=4, fill=ACCENT)
    bd.text((104, 88), headline, font=F_HEAD, fill=WHITE)
    bd.text((106, 164), sub, font=F_SUB, fill=GRAY)

    # wordmark bottom-left
    my = H - 72
    if icon_img is not None:
        ic = icon_img.resize((44, 44), Image.NEAREST)
        ic = rounded(ic, 10)
        bg.alpha_composite(ic, (80, my - 8))
        bd.text((138, my - 4), "LiteBuild", font=F_MARK, fill=WHITE)
    else:
        bd.text((80, my - 4), "LiteBuild", font=F_MARK, fill=WHITE)
    # tag right
    tag = "CLIENT-SIDE • FABRIC 26.2"
    tw = bd.textlength(tag, font=F_TAG)
    bd.text((W - 80 - tw, my), tag, font=F_TAG, fill=(110, 130, 155))

    bg.convert("RGB").save(os.path.join(OUT, out_name), "PNG")
    print("wrote", out_name)


def load_icon():
    """Pixel-art icon produced by make_icon_iso.py (run it first)."""
    return Image.open(os.path.join(OUT, "icon.png")).convert("RGBA")


def make_hero(icon):
    """Wide hero card: icon glow + title + tagline, no screenshot."""
    W, H = 1600, 900
    bg = vgrad((W, H), (8, 12, 26), (14, 30, 50)).convert("RGBA")
    # big soft teal glow center
    glow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    g = ImageDraw.Draw(glow)
    g.ellipse([W // 2 - 420, 60, W // 2 + 420, 900], fill=(56, 224, 215, 46))
    bg.alpha_composite(glow.filter(ImageFilter.GaussianBlur(120)))

    ic = icon.resize((384, 384), Image.NEAREST)
    ic = rounded(ic, 40)
    add_glow(bg, [W // 2 - 200, 120 - 10, W // 2 + 200, 120 + 390], ACCENT, 80, 90)
    bg.alpha_composite(ic, (W // 2 - 192, 128))

    d = ImageDraw.Draw(bg)
    t = "LiteBuild"
    f_title = font(700, 108)
    tw = d.textlength(t, font=f_title)
    d.text(((W - tw) / 2, 540), t, font=f_title, fill=WHITE)
    s = "Builds Litematica blueprints the honest way — real movement, real clicks, real confirmations."
    f_s = font(400, 34)
    sw = d.textlength(s, font=f_s)
    d.text(((W - sw) / 2, 680), s, font=f_s, fill=GRAY)
    s2 = "No world edits • No silent aim • Verified by the server, twice"
    sw2 = d.textlength(s2, font=font(600, 26))
    d.text(((W - sw2) / 2, 745), s2, font=font(600, 26), fill=ACCENT)
    bg.convert("RGB").save(os.path.join(OUT, "hero.png"), "PNG")
    print("wrote hero.png")


if __name__ == "__main__":
    icon = load_icon()

    g = lambda n: os.path.join(GALLERY, n)
    card(g("0013_litebuild-scale-complete.png"),
         "Verifies its own work",
         "Two-pass final audit, incremental checkpoints — done only when the server says so",
         "gallery-verify.png", icon)
    card(g("0024_litebuild-hard-complete.png"),
         "Phases that respect dependencies",
         "Structure › attachments › mechanisms › fluids › activation",
         "gallery-phases.png", icon)
    card(g("0015_litebuild-scenario2-built.png"),
         "Scaffolds over real gaps",
         "Temporary supports only where needed — removed with real mining afterwards",
         "gallery-scaffold.png", icon)
    card(g("0023_litebuild-speed-row18.png"),
         "Smart batching, not crawling",
         "Whole rows from one stance — up to 8 confirmed placements before relocating",
         "gallery-batch.png", icon)
    card(g("0021_litebuild-speed-sprint.png"),
         "Sprints when it makes sense",
         "Real sprint input on long safe legs — never faster than a player could be",
         "gallery-sprint.png", icon)
    card(g("0027_ui-overview.png"),
         "One key, full control",
         "Blueprint selection, gates, verify & fix, cleanup — press G in game",
         "gallery-menu.png", icon)
    card(g("0032_ui-settings-hud.png"),
         "Everything is configurable",
         "Scaffold blocks, timings, head movement, pauses, checkpoints, HUD — drafts validated on save",
         "gallery-settings.png", icon)
    card(g("0017_litebuild-scenario2-cleaned.png"),
         "Cleans up after itself",
         "Own scaffolding removed; foreign or changed blocks are never touched",
         "gallery-cleanup.png", icon)
    make_hero(icon)
