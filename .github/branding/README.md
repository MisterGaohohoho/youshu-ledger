# Application artwork

The application uses the pixel-art spending tree supplied by the project maintainer
on 2026-11-22 for this release. The original image is preserved as
`youshu-tree-source.png` (1254 by 1254 pixels).

Original SHA-256: `fce849508ffe3af73ef08b122d6db3c82d6bb7131e5ab7141d866a584f6126bc`.

The color artwork is resized without redrawing the tree or its embedded symbols.
It occupies 66 dp of the 108 dp adaptive foreground canvas. Repeated edge pixels
extend the background for launcher masks and motion, preserving the whole tree
in the normal circular and rounded-square viewports.

- `youshu-icon.png`: 512-pixel square preview of the normal launcher viewport.
- `app/src/main/res/drawable-nodpi/youshu_icon.png`: 432-pixel adaptive foreground.
- `youshu_icon_background`: warm neutral launcher background, `#F8F5EB`.
- `youshu_monochrome.png`: alpha-only tree silhouette for system-themed icons.
- `youshu_notification_foreground.png` and `youshu_notification_small.png`:
  silhouettes of the same tree for legible notification identity at small sizes.

Regenerate these committed PNGs with `python scripts/generate-app-icons.py` and
Pillow installed. Pillow is an authoring dependency only; normal Android builds
use the committed images and do not download or generate artwork.

Platform symbols depicted in the supplied artwork remain the trademarks of their
respective owners. Their depiction does not imply endorsement, account access or
an official relationship. The project license does not grant trademark rights.
The previous geometric receipt icon remains available in Git history.
