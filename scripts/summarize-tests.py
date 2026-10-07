#!/usr/bin/env python3
"""Summarize real JUnit XML. Does not infer unexecuted GPU/game gates.

Results are looked for where the build puts them: <module>/build/test-results/test for the library modules
and mod/targets/<target>/build/test-results/test for the builds of the mod.  A module that has tests and no
results is an omission, not a pass: every library module with a src/test folder, and every target named with
--require (by default the reference target, neoforge-1211, whose tests the root build runs), has to appear.
"""
import argparse
import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

REFERENCE_TARGETS = ("neoforge-1211",)


def result_folders(root):
    """module name -> folder holding its JUnit XML (which may not exist)."""
    folders = {}
    for module in sorted(p for p in root.iterdir() if p.is_dir()):
        if (module / "src" / "test").is_dir() or (module / "build" / "test-results" / "test").is_dir():
            folders[module.name] = module / "build" / "test-results" / "test"
    targets = root / "mod" / "targets"
    if targets.is_dir():
        for target in sorted(p for p in targets.iterdir() if p.is_dir()):
            if (target / "build" / "test-results" / "test").is_dir():
                folders[target.name] = target / "build" / "test-results" / "test"
    return folders


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--output", type=Path)
    parser.add_argument("--require", action="append", default=None,
                        help="a mod target whose results must be present (repeatable; default neoforge-1211)")
    args = parser.parse_args()
    folders = result_folders(args.root)
    required = set(REFERENCE_TARGETS if args.require is None else args.require)
    required |= {p.name for p in args.root.iterdir() if p.is_dir() and (p / "src" / "test").is_dir()}
    for target in required:
        folders.setdefault(target, args.root / "mod" / "targets" / target / "build" / "test-results" / "test")
    modules = {}
    totals = dict(tests=0, failures=0, errors=0, skipped=0)
    for module, folder in sorted(folders.items()):
        for result in sorted(folder.glob("TEST-*.xml")):
            suite = ET.parse(result).getroot()
            count = modules.setdefault(module, dict(tests=0, failures=0, errors=0, skipped=0))
            for key in totals:
                value = int(suite.attrib.get(key, 0))
                if value < 0:
                    raise ValueError(f"Negative JUnit count in {result}")
                totals[key] += value
                count[key] += value
    absent = sorted(module for module in required if modules.get(module, {}).get("tests", 0) == 0)
    passed = totals["tests"] > totals["skipped"] and totals["failures"] == 0 and totals["errors"] == 0 and not absent
    report = dict(schemaVersion=2, scope="CPU_JUNIT", status="PASSED" if passed else "FAILED",
                  counts=totals, modules=modules, modulesWithoutResults=absent,
                  note="Existing XML only. Run a clean validation first for a fresh milestone report; native/game gates are separate.")
    content = json.dumps(report, indent=2) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(content, encoding="utf-8")
    print(content, end="")
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main())
