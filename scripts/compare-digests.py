#!/usr/bin/env python3
"""Compare two benchmark chunk digests field by field; exit 1 on any difference.

A digest has one row per chunk: "x z" followed either by the single word MISSING or by exactly the fields
blocks, heightmaps, post, biomes and structures, each once, as name=value.  Anything else (a truncated row,
an unknown or repeated field, a stray token, a chunk listed twice) is a malformed digest and fails the
comparison whatever the two files contain; two files that are wrong in the same way do not compare equal.

  --ignore a,b   differences in these fields are reported but do not fail the comparison
  --only a,b     only these fields are compared
  --expect-chunks N  both files must list exactly N chunks (the measured square)
A chunk missing from either file, a MISSING row and a malformed file always fail.
"""
import argparse
import sys
from collections import Counter

FIELDS = ("blocks", "heightmaps", "post", "biomes", "structures")


class Malformed(Exception):
    pass


def load(path):
    rows = {}
    try:
        handle = open(path, encoding="utf-8")
    except OSError as failure:
        raise Malformed(f"{path}: {failure}") from failure
    with handle:
        for number, line in enumerate(handle, 1):
            parts = line.split()
            if not parts:
                continue
            where = f"{path}:{number}"
            if len(parts) < 3:
                raise Malformed(f"{where}: a row needs two coordinates and its fields")
            try:
                key = (int(parts[0]), int(parts[1]))
            except ValueError:
                raise Malformed(f"{where}: chunk coordinates are not integers") from None
            if key in rows:
                raise Malformed(f"{where}: chunk {key} is listed twice")
            if parts[2:] == ["MISSING"]:
                rows[key] = None
                continue
            fields = {}
            for token in parts[2:]:
                name, separator, value = token.partition("=")
                if not separator or name not in FIELDS:
                    raise Malformed(f"{where}: unexpected token {token!r}")
                if name in fields:
                    raise Malformed(f"{where}: field {name} is given twice")
                if not value:
                    raise Malformed(f"{where}: field {name} has no value")
                fields[name] = value
            absent = [name for name in FIELDS if name not in fields]
            if absent:
                raise Malformed(f"{where}: missing field(s) {', '.join(absent)}")
            rows[key] = fields
    return rows


def names(text):
    chosen = [name for name in text.split(",") if name]
    for name in chosen:
        if name not in FIELDS:
            raise SystemExit(f"unknown field {name!r}; the fields are {', '.join(FIELDS)}")
    return chosen


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("expected")
    parser.add_argument("actual")
    parser.add_argument("--ignore", default="")
    parser.add_argument("--only", default="")
    parser.add_argument("--expect-chunks", type=int, help="the number of chunks both files must list")
    args = parser.parse_args(argv)
    ignored = set(names(args.ignore))
    compared_fields = names(args.only) or list(FIELDS)
    try:
        expected, actual = load(args.expected), load(args.actual)
        if not expected:
            raise Malformed(f"{args.expected}: no chunks")
    except Malformed as failure:
        print(f"malformed: {failure}")
        print("FAIL")
        return 1
    if args.expect_chunks is not None and len(expected) != args.expect_chunks:
        # Two files cut short in the same way agree with each other and cover less than was measured.
        print(f"expected digest lists {len(expected)} chunks, the run measured {args.expect_chunks}")
        print("FAIL")
        return 1
    missing = sorted(set(expected) - set(actual))
    extra = sorted(set(actual) - set(expected))
    differences = Counter()
    examples = {}
    compared = 0
    for key in sorted(set(expected) & set(actual)):
        e, a = expected[key], actual[key]
        if e is None or a is None:
            differences["missing_chunk"] += 1
            examples.setdefault("missing_chunk", key)
            continue
        compared += 1
        for field in compared_fields:
            if e[field] != a[field]:
                differences[field] += 1
                examples.setdefault(field, key)
    print(f"expected={len(expected)} actual={len(actual)} compared={compared} "
          f"missing={len(missing)} extra={len(extra)}")
    for field, count in differences.most_common():
        note = " (ignored)" if field in ignored else ""
        print(f"  {field}: {count} chunks differ (first {examples[field]}){note}")
    failing = [field for field in differences if field not in ignored]
    ok = not missing and not extra and not failing and compared == len(expected)
    print("PASS" if ok else "FAIL")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
