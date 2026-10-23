#!/usr/bin/env python3
"""LiteBuild icon — Litematica-inspired pixel-art voxel stack.

3x3x2 solid stone stack, top layer = cyan wireframe ghost cubes
(blueprint), two of them already filled solid. Crisp NEAREST upscale.
"""
import os
from PIL import Image, ImageDraw

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "out")
os.makedirs(OUT, exist_ok=True)

S = 64
W2, H2, ZH = 8, 4, 9
BASE_Y = 38

STONE_TOP = (152, 155, 160)
STONE_L = (112, 115, 120)
STONE_R = (90, 93, 98)
STONE_EDGE = (58, 61, 66)
NEW_TOP = (196, 200, 206)   # freshly placed — brighter
NEW_L = (140, 144, 150)
NEW_R = (118, 122, 128)
NEW_EDGE = (56, 235, 220)   # cyan edge = just placed

GHOST = (64, 226, 213)
BG = (13, 17, 30)

N = 3                        # footprint N x N
FILLED_TOP = {(0, 2), (2, 0), (1, 1)}  # ghost-layer cells already built solid


def iso(x, y, z):
    return (32 + (x - y) * W2, BASE_Y + (x + y) * H2 - z * ZH)


def cube_solid(d, x, y, z, top, left, right, edge):
    px, py = iso(x, y, z)
    d.polygon([(px, py - ZH), (px + W2, py - ZH + H2), (px, py - ZH + 2 * H2),
               (px - W2, py - ZH + H2)], fill=top, outline=edge)
    d.polygon([(px - W2, py - ZH + H2), (px, py - ZH + 2 * H2),
               (px, py + 2 * H2), (px - W2, py + H2)], fill=left, outline=edge)
    d.polygon([(px + W2, py - ZH + H2), (px, py - ZH + 2 * H2),
               (px, py + 2 * H2), (px + W2, py + H2)], fill=right, outline=edge)


def cube_ghost(d, x, y, z):
    px, py = iso(x, y, z)
    w, h, zh = W2, H2, ZH
    edges = [
        # top diamond
        ((px, py - zh), (px + w, py - zh + h)),
        ((px + w, py - zh + h), (px, py - zh + 2 * h)),
        ((px, py - zh + 2 * h), (px - w, py - zh + h)),
        ((px - w, py - zh + h), (px, py - zh)),
        # bottom diamond
        ((px, py + 0), (px + w, py + h)),
        ((px + w, py + h), (px, py + 2 * h)),
        ((px, py + 2 * h), (px - w, py + h)),
        ((px - w, py + h), (px, py)),
        # verticals
        ((px, py - zh), (px, py)),
        ((px + w, py - zh + h), (px + w, py + h)),
        ((px - w, py - zh + h), (px - w, py + h)),
        ((px, py - zh + 2 * h), (px, py + 2 * h)),
    ]
    for a, b in edges:
        d.line([a, b], fill=GHOST + (255,), width=1)


def draw_icon():
    img = Image.new("RGBA", (S, S), BG + (255,))
    d = ImageDraw.Draw(img)

    # painter's order: back (small x+y) first, layer by layer
    def cells():
        return sorted(((x, y) for x in range(N) for y in range(N)),
                      key=lambda c: (c[0] + c[1], c[1]))

    for z in (0, 1):
        for x, y in cells():
            shade = 1.0 - 0.07 * (x + y)
            cube_solid(d, x, y, z,
                       tuple(int(c * shade) for c in STONE_TOP),
                       tuple(int(c * shade) for c in STONE_L),
                       tuple(int(c * shade) for c in STONE_R), STONE_EDGE)

    # top layer z=2: solid where filled, ghost where blueprint-only
    for x, y in cells():
        if (x, y) in FILLED_TOP:
            cube_solid(d, x, y, 2, NEW_TOP, NEW_L, NEW_R, NEW_EDGE)
        else:
            cube_ghost(d, x, y, 2)

    # two tiny sparkle accents
    for sx, sy in ((10, 16), (52, 12)):
        d.point([(sx, sy), (sx + 1, sy)], fill=GHOST + (200,))

    img.save(os.path.join(OUT, "icon-pixel-64.png"))
    for size, name in ((512, "icon.png"), (512, "icon-512.png"),
                       (256, "icon-256.png"), (128, "icon-128.png")):
        img.resize((size, size), Image.NEAREST).save(
            os.path.join(OUT, name))
    print("icon written")


if __name__ == "__main__":
    draw_icon()
