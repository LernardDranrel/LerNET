"""Generate the same LerNET mark for Compose resources and the Windows launcher.

Uses only the Python standard library so the build does not depend on an image editor.
"""

from pathlib import Path
import math
import struct
import zlib


ROOT = Path(__file__).resolve().parents[2]
RESOURCES = ROOT / "desktop-app" / "src" / "main" / "resources"


def over(base, color, alpha):
    return tuple(base[i] * (1 - alpha) + color[i] * alpha for i in range(3))


def sample(x, y, status):
    # Rounded black tile, quiet blue-grey edge, white L, and a small state dot.
    qx, qy = abs(x - 128) - 76, abs(y - 128) - 76
    distance = math.hypot(max(qx, 0), max(qy, 0)) + min(max(qx, qy), 0) - 40
    alpha = max(0.0, min(1.0, (7 - distance) / 7)) * 0.26
    rgb = (60.0, 80.0, 120.0)
    if distance <= 0:
        alpha = 1.0
        rgb = (15.0, 20.0, 28.0) if distance < -2.2 else (58.0, 71.0, 99.0)
    if distance < -3 and (91 <= x <= 113 and 68 <= y <= 173 or 91 <= x <= 167 and 152 <= y <= 173):
        rgb = (250.0, 252.0, 255.0)
    if status and (x - 207) ** 2 + (y - 205) ** 2 <= 13 ** 2:
        rgb = (105.0, 219.0, 164.0) if status == "running" else (255.0, 133.0, 133.0)
        alpha = 1.0
    return rgb, alpha


def chunk(kind, data):
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))


def png(size, status=None):
    scale = 256 / size
    pixels = bytearray()
    for iy in range(size):
        pixels.append(0)
        for ix in range(size):
            premul = [0.0, 0.0, 0.0]
            coverage = 0.0
            for sy in range(4):
                for sx in range(4):
                    color, alpha = sample((ix + (sx + .5) / 4) * scale, (iy + (sy + .5) / 4) * scale, status)
                    coverage += alpha
                    for channel in range(3):
                        premul[channel] += color[channel] * alpha
            coverage /= 16
            rgba = [int(round(value / (coverage * 16))) if coverage else 0 for value in premul]
            rgba.append(int(round(coverage * 255)))
            pixels.extend(max(0, min(255, value)) for value in rgba)
    header = b"\x89PNG\r\n\x1a\n"
    ihdr = struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)
    return header + chunk(b"IHDR", ihdr) + chunk(b"IDAT", zlib.compress(bytes(pixels), 9)) + chunk(b"IEND", b"")


def main():
    RESOURCES.mkdir(parents=True, exist_ok=True)
    for suffix in (None, "running", "failed"):
        filename = "lernet-icon" + ("-" + suffix if suffix else "") + ".png"
        (RESOURCES / filename).write_bytes(png(256, suffix))

    sizes = (16, 24, 32, 48, 64, 128, 256)
    images = [png(size) for size in sizes]
    directory = b"\x00\x00\x01\x00" + struct.pack("<H", len(sizes))
    offset = 6 + 16 * len(sizes)
    for size, payload in zip(sizes, images):
        directory += struct.pack("<BBBBHHII", size if size < 256 else 0,
                                 size if size < 256 else 0, 0, 0, 1, 32, len(payload), offset)
        offset += len(payload)
    (RESOURCES / "lernet.ico").write_bytes(directory + b"".join(images))


if __name__ == "__main__":
    main()
