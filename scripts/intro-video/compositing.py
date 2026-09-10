# -*- coding: utf-8 -*-
"""PIL 合成基元。除 crop_sprite 外一律回傳 RGB，避免模式在管線中飄移。"""
import os

from PIL import Image, ImageDraw, ImageFilter, ImageFont

import storyboard as sb


def load_font(size, path=None):
    path = path or sb.FONT_PATH
    # 缺字型一律 fail fast：靜默 fallback 會產出豆腐字
    if not os.path.exists(path):
        raise FileNotFoundError(
            "找不到字幕字型 {}。本機無 PingFang，且不可用 Hiragino 代替"
            "（日文字形，部分繁中字寫法不同）".format(path))
    return ImageFont.truetype(path, size)


def crossfade(a, b, t):
    if a.size != b.size:
        raise ValueError("交叉淡入的兩張圖尺寸必須相同：{} vs {}".format(a.size, b.size))
    return Image.blend(a.convert("RGB"), b.convert("RGB"),
                       min(max(float(t), 0.0), 1.0))


def crop_sprite(base, bbox):
    """從乾淨畫面裁出真實卡片，不重繪字體——這是保真度的來源。"""
    return base.convert("RGBA").crop(tuple(int(v) for v in bbox))


def paste_sprite(canvas, sprite, center_xy, rotate_deg=2.0, shadow=True):
    out = canvas.convert("RGBA")
    layer = sprite.rotate(rotate_deg, resample=Image.BICUBIC, expand=True)
    x = int(center_xy[0] - layer.width / 2.0)
    y = int(center_xy[1] - layer.height / 2.0)
    if shadow:
        shade = Image.new("RGBA", layer.size, (0, 0, 0, 0))
        shade.putalpha(layer.split()[3].point(lambda a: int(a * 0.28)))
        shade = shade.filter(ImageFilter.GaussianBlur(9))
        out.alpha_composite(shade, (x, y + 6))
    out.alpha_composite(layer, (x, y))
    return out.convert("RGB")


def draw_caption(img, text, alpha=1.0):
    base = img.convert("RGB")
    if not text or alpha <= 0.0:
        return base
    base = base.convert("RGBA")
    band = Image.new("RGBA", (base.width, sb.CAPTION_HEIGHT),
                     sb.INK + (255,))
    draw = ImageDraw.Draw(band)
    font = load_font(30)
    width = draw.textlength(text, font=font)
    draw.text(((base.width - width) / 2.0, (sb.CAPTION_HEIGHT - 30) / 2.0 - 3),
              text, font=font, fill=sb.PAPER + (255,))
    if alpha < 1.0:
        band.putalpha(band.split()[3].point(
            lambda a: int(a * min(max(alpha, 0.0), 1.0))))
    base.alpha_composite(band, (0, base.height - sb.CAPTION_HEIGHT))
    return base.convert("RGB")


def draw_boxes(img, boxes, width=3):
    out = img.convert("RGB")
    if not boxes:
        return out
    draw = ImageDraw.Draw(out)
    for box in boxes:
        draw.rectangle(tuple(box), outline=sb.DANGER, width=width)
    return out


def draw_cursor(img, xy):
    out = img.convert("RGBA")
    layer = Image.new("RGBA", out.size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer)
    x, y = int(xy[0]), int(xy[1])
    arrow = [(x, y), (x, y + 22), (x + 6, y + 16), (x + 10, y + 25),
             (x + 14, y + 23), (x + 10, y + 14), (x + 17, y + 14)]
    draw.polygon(arrow, fill=sb.PAPER + (255,), outline=sb.INK + (255,))
    out.alpha_composite(layer)
    return out.convert("RGB")


def ken_burns(img, scale):
    base = img.convert("RGB")
    if abs(scale - 1.0) < 1e-9:
        return base
    width, height = base.size
    big = base.resize((int(width * scale), int(height * scale)), Image.LANCZOS)
    left = (big.width - width) // 2
    top = (big.height - height) // 2
    return big.crop((left, top, left + width, top + height))
