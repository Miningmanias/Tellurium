#!/usr/bin/env python3
"""Counts, in a Distant Horizons database, how many full-detail sections came from which generation step.

Distant Horizons stores one byte per data column saying how that column was produced (its
EDhApiWorldGenerationStep: 0 empty, 5 surface, 8 features, 9 light, and others in between).  A section of
rough surface data and a section built from finished chunks are therefore told apart without decoding the
terrain.  A full-detail section is 64x64 columns: 4x4 chunks.

The bytes are Zstandard-compressed.  A section whose 4,096 columns all have the same step compresses to one
fixed 19-byte frame with that byte in it, and those are read directly; with Python 3.14 or the zstandard
package the other sections are decompressed too, otherwise they are counted as "mixed".

Usage: scripts/dh-database-steps.py <world>/data/DistantHorizons.sqlite
"""
import collections
import os
import shutil
import sqlite3
import sys
import tempfile

try:
    from compression import zstd

    def unpack(blob):
        return zstd.decompress(blob)
except ImportError:
    try:
        import zstandard

        def unpack(blob):
            return zstandard.ZstdDecompressor().decompress(blob, max_output_size=1 << 20)
    except ImportError:
        unpack = None

# magic, frame header saying 4,096 bytes of content, one compressed block whose literals are a run of one byte
UNIFORM_PREFIX = bytes.fromhex("28B52FFD60000F4D000010")
NAMES = {0: "empty", 5: "surface", 6: "carvers", 7: "liquid carvers", 8: "features", 9: "light", 255: "down-sampled"}

path = sys.argv[1].replace("\\", "/")
# A database left by a process that was stopped has a write-ahead log that must be replayed, which a read-only
# connection cannot do; so the three files are copied and the copy is opened normally.
scratch = tempfile.mkdtemp(prefix="dh-steps-")
for suffix in ("", "-wal", "-shm"):
    if os.path.exists(path + suffix):
        shutil.copy(path + suffix, os.path.join(scratch, "copy.sqlite" + suffix))
db = sqlite3.connect(os.path.join(scratch, "copy.sqlite"))
uniform = collections.Counter()
columns = collections.Counter()
sections = mixed = other = 0
for blob, mode in db.execute("select ColumnGenerationStep, CompressionMode from FullData where DetailLevel = 0"):
    sections += 1
    # CompressionMode 4 is Zstandard in the version this was written against; anything else is reported, not guessed.
    if blob is None or mode != 4:
        other += 1
    elif len(blob) == 19 and blob.startswith(UNIFORM_PREFIX) and blob[11] == blob[12]:
        uniform[blob[11]] += 1
    elif unpack is not None:
        columns.update(unpack(blob))
        mixed += 1
    else:
        mixed += 1
print(f"{path}: {sections} full-detail sections ({sections * 16} chunks of area)")
for step, count in sorted(uniform.items()):
    print(f"  all {NAMES.get(step, 'step ' + str(step))}: {count} sections = {count * 16} chunks")
print(f"  mixed steps: {mixed} sections" + ("" if unpack is not None or not mixed else " (not decompressed)"))
for step, count in sorted(columns.items()):
    print(f"    {NAMES.get(step, 'step ' + str(step))}: {count} columns = {count / 256:.0f} chunks")
if other:
    print(f"  not read (no data or another compression): {other} sections")
db.close()
shutil.rmtree(scratch, ignore_errors=True)
