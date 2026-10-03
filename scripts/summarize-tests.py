#!/usr/bin/env python3
"""Summarize real JUnit XML. Does not infer unexecuted GPU/game gates."""
import argparse
import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    modules = {}
    totals = dict(tests=0, failures=0, errors=0, skipped=0)
    for result in sorted(args.root.glob("*/build/test-results/test/TEST-*.xml")):
        suite = ET.parse(result).getroot()
        module = result.relative_to(args.root).parts[0]
        count = modules.setdefault(module, dict(tests=0, failures=0, errors=0, skipped=0))
        for key in totals:
            value = int(suite.attrib.get(key, 0))
            if value < 0:
                raise ValueError(f"Negative JUnit count in {result}")
            totals[key] += value
            count[key] += value
    passed = totals["tests"] > totals["skipped"] and totals["failures"] == 0 and totals["errors"] == 0
    report = dict(schemaVersion=1, scope="CPU_JUNIT", status="PASSED" if passed else "FAILED",
                  counts=totals, modules=modules,
                  note="Existing XML only. Run a clean validation first for a fresh milestone report; native/game gates are separate.")
    content = json.dumps(report, indent=2) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(content, encoding="utf-8")
    print(content, end="")
    return 0 if passed else 1

if __name__ == "__main__":
    sys.exit(main())
