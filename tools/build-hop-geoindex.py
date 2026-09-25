"""Build a compact reverse IP -> country index from the bundled sing-geoip SRS files.

The SRS reader intentionally accepts only the one IP-CIDR rule written by
SagerNet/sing-geoip. A format change fails the build instead of silently
producing incorrect country labels.
"""

from __future__ import annotations

import heapq
import struct
import zlib
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "core-engine/src/main/assets/rule-set"
OUTPUT = ROOT / "core-engine/src/main/assets/hop-geoip.idx"


def uvarint(data: bytes, position: int) -> tuple[int, int]:
    value = 0
    for shift in range(0, 70, 7):
        byte = data[position]
        position += 1
        value |= (byte & 0x7F) << shift
        if byte < 0x80:
            return value, position
    raise ValueError("Invalid uvarint")


def read_ranges(path: Path) -> list[tuple[int, int, int]]:
    raw = path.read_bytes()
    if raw[:4] != b"SRS\x01":
        raise ValueError(f"Unsupported SRS header: {path.name}")
    data = zlib.decompress(raw[4:])
    rules, position = uvarint(data, 0)
    if rules != 1 or data[position : position + 3] != b"\x00\x06\x01":
        raise ValueError(f"Expected one IP-CIDR rule: {path.name}")
    position += 3
    count = int.from_bytes(data[position : position + 8], "big")
    position += 8
    result = []
    for _ in range(count):
        addresses = []
        for _ in range(2):
            size, position = uvarint(data, position)
            if size not in (4, 16):
                raise ValueError(f"Unsupported address length in {path.name}")
            addresses.append(data[position : position + size])
            position += size
        start, end = addresses
        if len(start) != len(end) or start > end:
            raise ValueError(f"Invalid IP range in {path.name}")
        result.append((len(start), int.from_bytes(start, "big"), int.from_bytes(end, "big")))
    if data[position:] != b"\xff\x00":
        raise ValueError(f"Unexpected SRS rule items in {path.name}")
    return result


def flatten(ranges: list[tuple[int, int, str]], bits: int) -> list[tuple[int, int, str]]:
    events: dict[int, list[tuple[int, int]]] = {}
    members: list[tuple[int, int, str]] = []
    for start, end, code in ranges:
        index = len(members)
        members.append((start, end, code))
        events.setdefault(start, []).append((1, index))
        events.setdefault(end + 1, []).append((-1, index))

    active: set[int] = set()
    heap: list[tuple[int, str, int]] = []
    positions = sorted(events)
    result: list[tuple[int, int, str]] = []
    for event_index, start in enumerate(positions[:-1]):
        for action, index in events[start]:
            if action == 1:
                active.add(index)
                first, last, code = members[index]
                heapq.heappush(heap, (last - first, code, index))
            else:
                active.remove(index)
        while heap and heap[0][2] not in active:
            heapq.heappop(heap)
        if not heap:
            continue
        end = min(positions[event_index + 1] - 1, (1 << bits) - 1)
        code = heap[0][1]
        if result and result[-1][2] == code and result[-1][1] + 1 == start:
            previous = result[-1]
            result[-1] = (previous[0], end, code)
        else:
            result.append((start, end, code))
    return result


def main() -> None:
    grouped: dict[int, list[tuple[int, int, str]]] = {4: [], 16: []}
    files = sorted(SOURCE.glob("geoip-??.srs"))
    if len(files) < 200:
        raise ValueError("Bundled country rule sets are incomplete")
    for path in files:
        code = path.stem[-2:].upper()
        for size, start, end in read_ranges(path):
            grouped[size].append((start, end, code))

    ipv4 = flatten(grouped[4], 32)
    ipv6 = flatten(grouped[16], 128)
    with OUTPUT.open("wb") as output:
        output.write(b"HGI1")
        output.write(struct.pack(">II", len(ipv4), len(ipv6)))
        for size, rows in ((4, ipv4), (16, ipv6)):
            for start, end, code in rows:
                output.write(start.to_bytes(size, "big"))
                output.write(end.to_bytes(size, "big"))
                output.write(code.encode("ascii"))
    print(f"{len(files)} sets -> {len(ipv4)} IPv4, {len(ipv6)} IPv6 ranges -> {OUTPUT.stat().st_size} bytes")


if __name__ == "__main__":
    main()
