#!/usr/bin/env python3
"""Fail-closed placeholder-free launcher for the separately packaged corpus runner.

The script only orchestrates an already built command. It never generates expected
Minecraft output from the candidate process and refuses overlapping world directories.
"""
from __future__ import annotations

import argparse
import os
import subprocess
from pathlib import Path


def resolved(path: str) -> Path:
    return Path(path).expanduser().resolve()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--original-world", required=True)
    parser.add_argument("--candidate-world", required=True)
    parser.add_argument("--", dest="command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    original = resolved(args.original_world)
    candidate = resolved(args.candidate_world)
    if original == candidate or original in candidate.parents or candidate in original.parents:
        parser.error("original and candidate world directories overlap")
    if not args.command:
        parser.error("provide the separately configured runner after --")
    env = os.environ.copy()
    env["WORLDGENNEXT_ORIGINAL_WORLD"] = str(original)
    env["WORLDGENNEXT_CANDIDATE_WORLD"] = str(candidate)
    return subprocess.call(args.command, env=env)


if __name__ == "__main__":
    raise SystemExit(main())
