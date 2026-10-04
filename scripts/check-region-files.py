#!/usr/bin/env python3
"""Validates every chunk referenced by the headers of the region files in a folder.

Usage: check-region-files.py <region folder>...
For each .mca: every non-zero header entry must point inside the file (all of the chunk's bytes present), sectors of different chunks must not
overlap, the chunk's length prefix must fit its sectors, and the payload must decompress (zlib/gzip/none;
LZ4 payloads are only bounds-checked) to something that starts like a compound tag.  Exit status 1 on any
problem.
"""
import gzip
import struct
import sys
import zlib
from pathlib import Path


def check(path):
    data = path.read_bytes()
    problems = []
    if len(data) < 8192:
        return 0, ["shorter than its two header sectors"] if data else []
    used = {}
    chunks = 0
    for index in range(1024):
        entry = struct.unpack_from(">I", data, index * 4)[0]
        if entry == 0:
            continue
        sector, count = entry >> 8, entry & 0xFF
        where = f"chunk {index % 32},{index // 32}"
        # The last chunk of a file is padded to a full sector only when the file is closed, so after a kill
        # the file may end inside a chunk's last sector; the chunk's own bytes must still all be there.
        if sector < 2 or count == 0 or sector * 4096 + 5 > len(data):
            problems.append(f"{where}: sectors {sector}+{count} start outside the file ({len(data)} bytes)")
            continue
        for s in range(sector, sector + count):
            if s in used:
                problems.append(f"{where}: sector {s} also used by chunk {used[s] % 32},{used[s] // 32}")
            used[s] = index
        offset = sector * 4096
        length, kind = struct.unpack_from(">IB", data, offset)
        if length == 0 or length + 4 > count * 4096 or offset + 4 + length > len(data):
            problems.append(f"{where}: length {length} does not fit {count} sectors or the file ({len(data)} bytes)")
            continue
        if kind & 0x80:
            external = path.with_name(f"c.{(index % 32) + 32 * int(path.name.split('.')[1])}.{(index // 32) + 32 * int(path.name.split('.')[2])}.mcc")
            if not external.is_file():
                problems.append(f"{where}: external file {external.name} missing")
            chunks += 1
            continue
        payload = data[offset + 5:offset + 4 + length]
        try:
            if kind == 2:
                raw = zlib.decompress(payload)
            elif kind == 1:
                raw = gzip.decompress(payload)
            elif kind == 3:
                raw = payload
            elif kind == 4:
                raw = b"\x0a"
            else:
                raise ValueError(f"unknown compression type {kind}")
            if not raw or raw[0] != 0x0A:
                raise ValueError("payload is not a compound tag")
        except Exception as failure:  # noqa: BLE001 - any decode failure is the finding
            problems.append(f"{where}: {failure}")
            continue
        chunks += 1
    return chunks, problems


def main():
    total = files = bad = 0
    for folder in sys.argv[1:]:
        for path in sorted(Path(folder).glob("*.mca")):
            chunks, problems = check(path)
            files += 1
            total += chunks
            for problem in problems:
                bad += 1
                print(f"{path}: {problem}")
    print(f"{files} region files, {total} readable chunks, {bad} problems")
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
