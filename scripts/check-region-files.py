#!/usr/bin/env python3
"""Validates every chunk referenced by the headers of the region files in a folder.

Usage: check-region-files.py [--min-chunks N] [--min-files N] [--optional FOLDER]... <region folder>...
For each .mca: every non-zero header entry must point inside the file (all of the chunk's bytes present), sectors of different chunks must not
overlap, the chunk's length prefix must fit its sectors, and the payload must decompress (zlib/gzip/none;
LZ4 payloads are only bounds-checked) to something that starts like a compound tag.  A chunk kept in an
external .mcc file is read and checked the same way.

A folder that does not exist is a problem unless it is given with --optional (the game creates poi and
entities folders only when it has something to put there).  A region file of zero bytes is what the game
leaves when it opened a region and wrote nothing; it is counted as empty, holds no chunks and is not by
itself a problem, and a file with part of a header is.  "0 problems" therefore does not mean anything was
found: a caller that expects chunks says how many with --min-chunks (and --min-files), counted over all
folders together.  Exit status 1 on any problem or unmet minimum.
"""
import argparse
import gzip
import struct
import sys
import zlib
from pathlib import Path


def decode(kind, payload):
    """Raises unless the payload of this compression type holds something that starts like a compound tag."""
    if not payload:
        raise ValueError("payload is empty")
    if kind == 2:
        raw = zlib.decompress(payload)
    elif kind == 1:
        raw = gzip.decompress(payload)
    elif kind == 3:
        raw = payload
    elif kind == 4:
        raw = b"\x0a"  # LZ4: bounds only
    else:
        raise ValueError(f"unknown compression type {kind}")
    if not raw or raw[0] != 0x0A:
        raise ValueError("payload is not a compound tag")


def check(path):
    """Returns (chunks, problems, empty)."""
    data = path.read_bytes()
    problems = []
    if not data:
        return 0, [], True
    if len(data) < 8192:
        return 0, ["shorter than its two header sectors"], False
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
        try:
            if kind & 0x80:
                region = path.name.split(".")
                external = path.with_name(f"c.{(index % 32) + 32 * int(region[1])}.{(index // 32) + 32 * int(region[2])}.mcc")
                if not external.is_file():
                    raise ValueError(f"external file {external.name} missing")
                try:
                    decode(kind & 0x7F, external.read_bytes())
                except Exception as failure:  # noqa: BLE001
                    raise ValueError(f"external file {external.name}: {failure}") from failure
            else:
                decode(kind, data[offset + 5:offset + 4 + length])
        except Exception as failure:  # noqa: BLE001 - any decode failure is the finding
            problems.append(f"{where}: {failure}")
            continue
        chunks += 1
    return chunks, problems, False


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("folders", nargs="*")
    parser.add_argument("--optional", action="append", default=[], help="a folder that may be absent")
    parser.add_argument("--min-chunks", type=int, default=0)
    parser.add_argument("--min-files", type=int, default=0)
    args = parser.parse_args(argv)
    if not args.folders and not args.optional:
        parser.error("no region folder given")
    total = files = bad = empty = 0
    for folder, required in [(f, True) for f in args.folders] + [(f, False) for f in args.optional]:
        if not Path(folder).is_dir():
            if required:
                bad += 1
                print(f"{folder}: no such folder")
            continue
        for path in sorted(Path(folder).glob("*.mca")):
            chunks, problems, is_empty = check(path)
            files += 1
            empty += is_empty
            total += chunks
            for problem in problems:
                bad += 1
                print(f"{path}: {problem}")
    if total < args.min_chunks:
        bad += 1
        print(f"only {total} readable chunks where at least {args.min_chunks} are expected")
    if files - empty < args.min_files:
        bad += 1
        print(f"only {files - empty} region files with content where at least {args.min_files} are expected")
    print(f"{files} region files{f' ({empty} empty)' if empty else ''}, {total} readable chunks, {bad} problems")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
