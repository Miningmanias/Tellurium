#!/usr/bin/env python3
"""Checks that a benchmark report describes a finished run of what was asked for; exit 1 otherwise.

Usage: check-bench-report.py <report.json> [--endpoint SURFACE] [--radius 45] [--seed 0] [--same-area-as other.json]
The report must say PASS, every requested chunk of the measured phase must have completed and none failed,
and endpoint, radius and seed must be the ones given.  --same-area-as also requires the two reports to
cover the same chunks (centre, radius, seed and endpoint), which is what makes their digests comparable.
Prints one line: "ok ..." or the reason.
"""
import argparse
import json
import sys


def load(path):
    try:
        with open(path, encoding="utf-8") as handle:
            report = json.load(handle)
    except (OSError, ValueError) as failure:
        raise SystemExit(f"unreadable report {path}: {failure}")
    if not isinstance(report, dict) or report.get("kind") != "tellurium_chunk_throughput":
        raise SystemExit(f"{path} is not a chunk throughput report")
    return report


def main(argv):
    parser = argparse.ArgumentParser()
    parser.add_argument("report")
    parser.add_argument("--endpoint")
    parser.add_argument("--radius", type=int)
    parser.add_argument("--seed")
    parser.add_argument("--same-area-as")
    args = parser.parse_args(argv)
    report = load(args.report)
    reasons = []
    if report.get("status") != "PASS":
        reasons.append(f"status {report.get('status')}")
    measured = report.get("measured")
    counts = [measured.get(name) if isinstance(measured, dict) else None for name in ("requested", "completed", "failed")]
    # The three counts have to be there as whole numbers: an omitted "failed" is not "none failed".
    if any(type(count) is not int for count in counts):
        reasons.append("the measured phase lacks whole-number requested/completed/failed counts")
        measured = {}
    else:
        requested, completed, failed = counts
        if requested <= 0:
            reasons.append(f"requested {requested} chunks")
        if completed != requested:
            reasons.append(f"completed {completed} of {requested}")
        if failed != 0:
            reasons.append(f"{failed} chunks failed")
        radius = report.get("radiusChunks")
        # The measured square is the one the radius describes.
        if type(radius) is not int or radius < 0:
            reasons.append(f"radius {radius!r} is not a whole number of chunks")
        elif requested != (2 * radius + 1) ** 2:
            reasons.append(f"requested {requested} chunks, a radius of {radius} is {(2 * radius + 1) ** 2}")
    if args.endpoint and report.get("endpoint") != f"MINECRAFT:{args.endpoint.upper()}":
        reasons.append(f"endpoint {report.get('endpoint')}, not {args.endpoint.upper()}")
    if args.radius is not None and report.get("radiusChunks") != args.radius:
        reasons.append(f"radius {report.get('radiusChunks')}, not {args.radius}")
    if args.seed is not None and str(report.get("seed")) != str(args.seed):
        reasons.append(f"seed {report.get('seed')}, not {args.seed}")
    if args.same_area_as:
        other = load(args.same_area_as)
        for field in ("endpoint", "radiusChunks", "center", "seed"):
            if report.get(field) != other.get(field):
                reasons.append(f"{field} differs from the other run ({report.get(field)} / {other.get(field)})")
    if reasons:
        print("; ".join(reasons))
        return 1
    print(f"ok {report.get('endpoint')} radius {report.get('radiusChunks')} seed {report.get('seed')} "
          f"centre {report.get('center')} {measured.get('completed')} chunks")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
