#!/usr/bin/env python3
"""Make Play Store screenshots (1080 x 1920) from raw phone screenshots.

  python3 frame.py raw/1.png "Know every lead before you answer" out/1.png [#2F55D4]

The raw screenshot is scaled into a rounded phone frame under a caption, on the ITTC blue.
Use real screenshots taken on a phone with demo leads only (never real students).
"""
import sys
from PIL import Image, ImageDraw, ImageFont, ImageFilter

W, H = 1080, 1920


def font(size):
    for p in ("/usr/share/fonts/truetype/noto/NotoSansBengali-Bold.ttf",
              "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"):
        try:
            return ImageFont.truetype(p, size)
        except OSError:
            pass
    return ImageFont.load_default()


def wrap(draw, text, f, width):
    lines, line = [], ""
    for word in text.split():
        t = (line + " " + word).strip()
        if draw.textlength(t, font=f) <= width:
            line = t
        else:
            lines.append(line)
            line = word
    return lines + [line]


def main(src, caption, out, color="#2F55D4"):
    bg = Image.new("RGB", (W, H), color)
    d = ImageDraw.Draw(bg)
    f = font(64)
    y = 110
    for ln in wrap(d, caption, f, W - 160):
        d.text((W / 2, y), ln, font=f, fill="white", anchor="ma")
        y += 84
    shot = Image.open(src).convert("RGB")
    top = y + 60
    ph = H - top - 60
    pw = int(ph * shot.width / shot.height)
    if pw > W - 160:
        pw = W - 160
        ph = int(pw * shot.height / shot.width)
    shot = shot.resize((pw, ph), Image.LANCZOS)
    x = (W - pw) // 2
    r = 48
    shadow = Image.new("L", (W, H), 0)
    ImageDraw.Draw(shadow).rounded_rectangle((x - 14, top - 6, x + pw + 14, top + ph + 26), r + 14, fill=110)
    bg.paste((0, 0, 0), mask=shadow.filter(ImageFilter.GaussianBlur(24)))
    ImageDraw.Draw(bg).rounded_rectangle((x - 14, top - 14, x + pw + 14, top + ph + 14), r + 14, fill="#11151F")
    mask = Image.new("L", (pw, ph), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, pw, ph), r, fill=255)
    bg.paste(shot, (x, top), mask)
    bg.save(out, optimize=True)


if __name__ == "__main__":
    main(*sys.argv[1:])
