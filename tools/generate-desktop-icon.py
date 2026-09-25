"""Rasterize the existing Android ic_logo.xml rectangles for the Windows tray."""
from pathlib import Path
import struct
import zlib

size = 64
scale = size / 108

def chunk(kind: bytes, data: bytes) -> bytes:
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

for suffix, ink in {
    "": (255, 255, 255, 255),
    "-running": (118, 215, 160, 255),
    "-failed": (255, 137, 137, 255),
}.items():
    pixels = bytearray()
    for y in range(size):
        pixels.append(0)
        for x in range(size):
            ux, uy = (x + 0.5) / scale, (y + 0.5) / scale
            letter = (36 <= ux < 48 and 24 <= uy < 84) or (36 <= ux < 72 and 72 <= uy < 84)
            pixels.extend(ink if letter else (0, 0, 0, 255))
    png = (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(bytes(pixels), 9))
        + chunk(b"IEND", b"")
    )
    target = Path(__file__).resolve().parents[1] / f"desktop-app/src/main/resources/lernet-icon{suffix}.png"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(png)
