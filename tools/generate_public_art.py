"""Regenerate the original ANON icons and offline covers using Pillow 10.1+."""

import json
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parents[1]
ART = ROOT / "app/src/main/res/drawable-nodpi"
ASSETS = ROOT / "app/src/main/assets"
FLUTTER_ART = ROOT / "integration/kazumi/runtime/assets/images"
PINK = "#bf3d50"
CREAM = "#fff8f1"
INK = "#292536"
SAGE = "#426359"
SCALE = 3


def canvas(width, height, background):
    image = Image.new("RGBA", (width * SCALE, height * SCALE), background)
    return image, ImageDraw.Draw(image)


def box(*points):
    return tuple(round(value * SCALE) for value in points)


def point_list(*points):
    return [box(*point) for point in points]


def disc(draw, x, y, radius, ink=INK):
    draw.ellipse(box(x - radius, y - radius, x + radius, y + radius), fill=ink)
    for fraction in (0.69, 0.84):
        rim = radius * fraction
        bounds = box(x - rim, y - rim, x + rim, y + rim)
        draw.arc(bounds, 25, 145, fill="#a99aaf", width=SCALE)
        draw.arc(bounds, 205, 325, fill="#a99aaf", width=SCALE)
    label = radius * 0.29
    draw.ellipse(box(x - label, y - label, x + label, y + label), fill=PINK)
    hole = radius * 0.06
    draw.ellipse(box(x - hole, y - hole, x + hole, y + hole), fill=CREAM)


def sparkle(draw, x, y, radius, color):
    draw.polygon(point_list(
        (x, y - radius), (x + .28 * radius, y - .28 * radius),
        (x + radius, y), (x + .28 * radius, y + .28 * radius),
        (x, y + radius), (x - .28 * radius, y + .28 * radius),
        (x - radius, y), (x - .28 * radius, y - .28 * radius),
    ), fill=color)


def pick(draw, x, y, radius, color):
    outline = [(-.92, -.30), (-.82, -.59), (-.48, -.77), (0, -.83), (.48, -.77),
               (.82, -.59), (.92, -.30), (.80, .07), (.54, .49), (.22, .90),
               (0, 1), (-.22, .90), (-.54, .49), (-.80, .07)]
    draw.polygon(point_list(*[(x + px * radius, y + py * radius) for px, py in outline]), fill=color)


def save(image, path, width, height):
    result = image.resize((width, height), Image.Resampling.LANCZOS)
    if path.suffix == ".webp":
        result.convert("RGB").save(path, lossless=True)
    elif path.suffix == ".jpeg":
        result.convert("RGB").save(path, quality=94, optimize=True)
    else:
        result.save(path, optimize=True)


def make_emblem():
    if not (ART / "anon_avatar.jpg").is_file():
        image, draw = canvas(432, 432, "#f9dce9")
        draw.rounded_rectangle(box(73, 73, 359, 359), radius=68 * SCALE, fill=CREAM)
        disc(draw, 202, 214, 94)
        pick(draw, 273, 260, 53, PINK)
        sparkle(draw, 273, 255, 16, CREAM)
        sparkle(draw, 289, 139, 18, PINK)
        save(image, ART / "anon_avatar.png", 432, 432)

    image, draw = canvas(400, 400, None)
    draw.rounded_rectangle(box(36, 36, 364, 364), radius=32 * SCALE, fill=CREAM)
    draw.rounded_rectangle(box(47, 47, 353, 353), radius=26 * SCALE,
                           outline="#ead5d7", width=SCALE)
    disc(draw, 187, 192, 112)
    pick(draw, 273, 258, 56, PINK)
    sparkle(draw, 273, 252, 20, CREAM)
    sparkle(draw, 288, 102, 21, PINK)
    sparkle(draw, 108, 300, 10, SAGE)
    save(image, ART / "anon_mascot.png", 400, 400)


def make_badge(name):
    image, draw = canvas(162, 162, None)
    accent = SAGE if name in ("look", "peek") else PINK
    tint = "#e5f0eb" if accent == SAGE else "#fae5e7"
    draw.rounded_rectangle(box(16, 16, 146, 146), radius=35 * SCALE, fill=tint)
    if name == "create":
        pick(draw, 75, 83, 39, accent)
        sparkle(draw, 74, 76, 14, CREAM)
        sparkle(draw, 120, 47, 12, accent)
    elif name == "look":
        draw.line(box(93, 97, 115, 119), fill=accent, width=12 * SCALE)
        draw.ellipse(box(40, 41, 100, 101), fill=accent)
        draw.ellipse(box(50, 51, 90, 91), fill=tint)
        sparkle(draw, 68, 70, 13, accent)
    elif name == "love":
        draw.ellipse(box(43, 53, 86, 96), fill=accent)
        draw.ellipse(box(76, 53, 119, 96), fill=accent)
        draw.polygon(point_list((47, 84), (81, 75), (115, 84), (81, 120)), fill=accent)
        sparkle(draw, 102, 61, 12, CREAM)
    else:
        draw.arc(box(37, 60, 125, 117), 193, 347, fill=accent, width=7 * SCALE)
        draw.arc(box(37, 45, 125, 102), 13, 167, fill=accent, width=7 * SCALE)
        draw.ellipse(box(67, 67, 95, 95), fill=accent)
        sparkle(draw, 117, 51, 12, accent)
    save(image, ART / f"mygo_{name}.png", 162, 162)


def make_avatar_placeholder():
    image, draw = canvas(180, 180, "#e5e4eb")
    draw.ellipse(box(20, 20, 160, 160), fill="#d5d1df")
    draw.ellipse(box(72, 48, 108, 84), fill="#817a8d")
    draw.rounded_rectangle(box(49, 90, 131, 134), radius=21 * SCALE, fill="#817a8d")
    FLUTTER_ART.mkdir(parents=True, exist_ok=True)
    save(image, FLUTTER_ART / "noface.jpeg", 180, 180)


TITLES = {
    554779: ("STUDENT", "COUNCIL", "#733b52", "#f5bdb3"),
    622288: ("FX TRADER", "KURUMI", "#375a58", "#cde4c2"),
    513878: ("CYBERPUNK", "EDGERUNNERS 2", "#343655", "#fac8a3"),
    624085: ("NIA", "LISTON", "#4c4269", "#e8cbe9"),
    625799: ("CHEAT", "ARTIFICER", "#53543b", "#e9d6ae"),
    630160: ("DARK", "SUMMONER", "#653f49", "#f2c6cd"),
}


def make_poster(item):
    title, subtitle, base, accent = TITLES[item["id"]]
    image, draw = canvas(320, 456, base)
    draw.rounded_rectangle(box(27, 27, 293, 429), radius=31 * SCALE,
                           outline=accent, width=SCALE)
    draw.ellipse(box(55, 55, 265, 265), outline=accent, width=SCALE)
    draw.ellipse(box(74, 74, 246, 246), outline=accent, width=SCALE)
    draw.polygon(point_list((160, 112), (216, 161), (160, 212), (105, 161)), fill=accent)
    sparkle(draw, 160, 161, 35, base)
    title_font = ImageFont.load_default(size=36 * SCALE)
    subtitle_font = ImageFont.load_default(size=22 * SCALE)
    date_font = ImageFont.load_default(size=16 * SCALE)
    draw.text(box(47, 300), title, font=title_font, fill=CREAM)
    draw.text(box(49, 352), subtitle, font=subtitle_font, fill=accent)
    draw.text(box(50, 397), item["date"][:7], font=date_font, fill=CREAM)
    save(image, ASSETS / "anime-popular-posters" / f'{item["id"]}.webp', 320, 456)


def main():
    make_emblem()
    make_avatar_placeholder()
    for badge in ("create", "look", "love", "peek"):
        make_badge(badge)
    catalogue = json.loads((ASSETS / "anime-popular-2026-11-21.json").read_text(encoding="utf-8"))
    for item in catalogue["data"]:
        make_poster(item)


if __name__ == "__main__":
    main()
