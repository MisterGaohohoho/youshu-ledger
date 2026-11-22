"""Regenerate the committed tree icon assets (requires Pillow only for authoring)."""
from pathlib import Path
import hashlib

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / '.github/branding/youshu-tree-source.png'
SOURCE_SHA256 = 'fce849508ffe3af73ef08b122d6db3c82d6bb7131e5ab7141d866a584f6126bc'
DRAWABLES = ROOT / 'app/src/main/res/drawable-nodpi'


def extend_edges(image, size=432, artwork_size=264):
    """Fit the supplied square into 66/108 dp and bleed its edges for launcher motion."""
    art = image.resize((artwork_size, artwork_size), Image.Resampling.LANCZOS)
    margin = (size - artwork_size) // 2
    canvas = Image.new('RGB', (size, size))
    canvas.paste(art, (margin, margin))
    for box, target, dimensions in (
        ((0, 0, artwork_size, 1), (margin, 0), (artwork_size, margin)),
        ((0, artwork_size - 1, artwork_size, artwork_size), (margin, margin + artwork_size), (artwork_size, margin)),
        ((0, 0, 1, artwork_size), (0, margin), (margin, artwork_size)),
        ((artwork_size - 1, 0, artwork_size, artwork_size), (margin + artwork_size, margin), (margin, artwork_size)),
    ):
        canvas.paste(art.crop(box).resize(dimensions, Image.Resampling.NEAREST), target)
    for corner, target in (
        ((0, 0), (0, 0)),
        ((artwork_size - 1, 0), (margin + artwork_size, 0)),
        ((0, artwork_size - 1), (0, margin + artwork_size)),
        ((artwork_size - 1, artwork_size - 1), (margin + artwork_size, margin + artwork_size)),
    ):
        canvas.paste(Image.new('RGB', (margin, margin), art.getpixel(corner)), target)
    return canvas.convert('RGBA')


def tree_silhouette(image):
    """Extract the connected tree, omitting clouds, loose leaves and the ground."""
    width, height = image.size
    mask = Image.new('L', image.size)
    pixels = image.load()
    selected = mask.load()
    for y in range(height):
        for x in range(width):
            r, g, b = pixels[x, y]
            foreground = max(r, g, b) - min(r, g, b) > 45 or min(r, g, b) < 140
            if y >= height * 0.79:
                # Keep the brown trunk and its dark outline, not the low grass.
                foreground = foreground and width * 0.30 <= x <= width * 0.72 and (r > g * 1.10 or max(r, g, b) < 100)
            if foreground and y < height * 0.955:
                selected[x, y] = 255
    trunk_seed = (width // 2, round(height * 0.82))
    if mask.getpixel(trunk_seed) != 255:
        raise ValueError('The expected tree trunk is missing from the supplied artwork')
    ImageDraw.floodfill(mask, trunk_seed, 128)
    connected = mask.point(lambda value: 255 if value == 128 else 0)
    # Fill enclosed logo details: Android tints these assets with a single color.
    ImageDraw.floodfill(connected, (0, 0), 128)
    return connected.point(lambda value: 0 if value == 128 else 255)


def white_icon(mask, size, content_size, crop=False):
    if crop:
        mask = mask.crop(mask.getbbox())
    mask = mask.copy()
    mask.thumbnail((content_size, content_size), Image.Resampling.LANCZOS)
    alpha = Image.new('L', (size, size))
    alpha.paste(mask, ((size - mask.width) // 2, (size - mask.height) // 2))
    icon = Image.new('RGBA', (size, size), (255, 255, 255, 0))
    icon.putalpha(alpha)
    return icon


def main():
    if hashlib.sha256(SOURCE.read_bytes()).hexdigest() != SOURCE_SHA256:
        raise ValueError('Unexpected source artwork; review provenance before regenerating')
    source = Image.open(SOURCE).convert('RGB')
    if source.size != (1254, 1254):
        raise ValueError('Expected the original 1254 by 1254 image')
    foreground = extend_edges(source)
    foreground.save(DRAWABLES / 'youshu_icon.png', optimize=True)
    # Android's normal adaptive viewport occupies the middle 72 of 108 dp.
    foreground.crop((72, 72, 360, 360)).resize((512, 512), Image.Resampling.LANCZOS).save(
        ROOT / '.github/branding/youshu-icon.png', optimize=True)
    silhouette = tree_silhouette(source)
    white_icon(silhouette, 432, 264).save(DRAWABLES / 'youshu_monochrome.png', optimize=True)
    white_icon(silhouette, 192, 144, crop=True).save(DRAWABLES / 'youshu_notification_foreground.png', optimize=True)
    white_icon(silhouette, 96, 72, crop=True).save(DRAWABLES / 'youshu_notification_small.png', optimize=True)
    print('Generated the color launcher, themed launcher and notification tree assets.')


if __name__ == '__main__':
    main()
