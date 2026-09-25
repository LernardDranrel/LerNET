"""Rebuild the desktop window/tray icons from the LerNET mark."""

from pathlib import Path

from PIL import Image, ImageDraw


RESOURCE_DIR = Path(__file__).resolve().parents[1] / "desktop-app" / "src" / "main" / "resources"

for filename, status_color in (
    ("lernet-icon.png", None),
    ("lernet-icon-running.png", "#76D7A0"),
    ("lernet-icon-failed.png", "#FF8989"),
):
    image = Image.new("RGBA", (64, 64), "#000000")
    draw = ImageDraw.Draw(image)
    # The L is 13% smaller than the previous 22 x 36 pixel mark.
    draw.polygon([(24, 17), (30, 17), (30, 41), (42, 41), (42, 47), (24, 47)], fill="#FFFFFF")
    if status_color:
        draw.ellipse((45, 45, 53, 53), fill=status_color)
    image.save(RESOURCE_DIR / filename)
