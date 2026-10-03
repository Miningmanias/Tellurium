#!/usr/bin/env python3
"""Compare two benchmark chunk digests field by field; exit 1 on any difference."""
import sys
from collections import Counter


def load(path):
    rows = {}
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            parts = line.split()
            if not parts:
                continue
            key = (int(parts[0]), int(parts[1]))
            if key in rows:
                raise SystemExit(f"duplicate chunk {key} in {path}")
            rows[key] = dict(p.split("=", 1) for p in parts[2:] if "=" in p) if "MISSING" not in parts else None
    return rows


def main(expected_path, actual_path):
    expected, actual = load(expected_path), load(actual_path)
    if not expected:
        raise SystemExit("expected digest is empty")
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
        for field in sorted(set(e) | set(a)):
            if e.get(field) != a.get(field):
                differences[field] += 1
                examples.setdefault(field, key)
    print(f"expected={len(expected)} actual={len(actual)} compared={compared} "
          f"missing={len(missing)} extra={len(extra)}")
    for field, count in differences.most_common():
        print(f"  {field}: {count} chunks differ (first {examples[field]})")
    ok = not missing and not extra and not differences and compared == len(expected)
    print("PASS" if ok else "FAIL")
    return 0 if ok else 1


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit("usage: compare-digests.py expected.digest.txt actual.digest.txt")
    sys.exit(main(sys.argv[1], sys.argv[2]))
