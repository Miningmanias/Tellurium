#!/usr/bin/env python3
"""Validate v0.2 baseline JSON input; never execute or qualify hardware.

CLI: python -B scripts/capture-baseline.py manifest.json [--partial]
Schema (all object keys are exact; no unknown claims/extensions accepted):
  {schemaVersion: 2, identity: IDENTITY, workloads: [WORKLOAD], rows: [ROW]}
IDENTITY: {sourceSha256, jarSha256, corpusSha256, configSha256}; each is a
64-hex SHA256 string. Every row repeats exactly this frozen identity. These
are declared hashes, not file rehashing or independent evidence verification.
WORKLOAD: {workloadId: nonblank string, warmupCoordinates: [[chunkX,chunkZ]],
measuredCoordinates: [[chunkX,chunkZ]]}. Coordinates are unique integer pairs,
measured is nonempty, warmup and measured are disjoint. The declaration fixes
equivalent coordinates across every endpoint/backend/repetition.
ROW required keys:
  workloadId, identity, endpoint (NOISE/FULL/SAVED), backend
  (CPU_OWNED/GPU_IEEE_BITS/CPU_ORIGINAL_PLANNED), mode
  (CPU_ONLY/GPU_REQUIRED/ORIGINAL_SAME_STACK respectively), temperature
  (COLD/WARM), repetition (positive integer), runId, launchId, freshWorldId
  (globally unique nonblank strings), warmupCoordinates, measuredCoordinates,
  compared (positive comparison units), comparedCoreChunks (measured count),
  mismatches (zero), wallNanos, p50Nanos, p95Nanos (positive integer nanos,
  p50 <= p95 <= wall), timingScope (ENDPOINT_WALL), stageNanos,
  transferredBytes (nonnegative integer; positive for GPU), memoryHighWaterBytes (positive int),
  failureCounts {failed, timedOut, cancelled, fallback, recovery, deviceLost}
  (all integer zero). COLD warmup is []; WARM uses the declared nonempty
  warmup. Each row represents an independent process and fresh world.
stageNanos: {generation: positive integer, kernel: nonnegative integer optional}.
SAVED additionally requires positive save and reopenValidation stage nanos;
their sum with generation <= wallNanos. Other endpoints forbid these keys.
Stage timers can overlap (kernel is diagnostic); do not subtract them from wall.
Optional chunksPerSecond must be finite positive and equal
comparedCoreChunks * 1e9 / wallNanos (relative tolerance 1e-9).
GPU rows additionally require gpuReceiptCount, commitCount (both measured
count), gpuEvidence: one object per measured coordinate with exact keys
{coordinate, receiptSha256, commitSha256, comparisonSha256,
backend: GPU_IEEE_BITS, deviceProducedBlocks: positive int, mismatches: 0}.
These coordinate-bound links are globally unique SHA256 identities; warmup
receipts cannot fill measured coverage. CPU rows forbid GPU evidence keys.
For SAVED, GPU receipts describe NOISE ownership; downstream stages/save/reopen
remain original CPU work, not GPU kernel throughput.
Full validation requires every declared workload x three endpoints x three
backends x COLD/WARM with at least three independent repetitions in each cell.
--partial permits missing cells/repetitions only, preserving every row contract.
Output status is VALIDATED_BASELINE_INPUT or PARTIAL_VALIDATED_INPUT, always
releaseQualification=false: neither establishes G12 or a physical-device pass.
"""
from __future__ import annotations

import argparse
import json
import math
import re
import sys
from collections import Counter
from pathlib import Path


ENDPOINTS = ("NOISE", "FULL", "SAVED")
MODES = {"CPU_OWNED": "CPU_ONLY", "GPU_IEEE_BITS": "GPU_REQUIRED",
         "CPU_ORIGINAL_PLANNED": "ORIGINAL_SAME_STACK"}
HASH_KEYS = {"sourceSha256", "jarSha256", "corpusSha256", "configSha256"}
FAILURES = {"failed", "timedOut", "cancelled", "fallback", "recovery", "deviceLost"}
GPU_KEYS = {"gpuReceiptCount", "commitCount", "gpuEvidence"}
ROW_KEYS = set("workloadId identity endpoint backend mode temperature repetition runId "
               "launchId freshWorldId warmupCoordinates measuredCoordinates compared "
               "comparedCoreChunks mismatches wallNanos p50Nanos p95Nanos timingScope "
               "stageNanos transferredBytes memoryHighWaterBytes failureCounts".split())


def require(condition, message):
    if not condition:
        raise ValueError(message)


def keys(value, required, optional=frozenset()):
    require(type(value) is dict, "expected an object")
    require(required <= value.keys() and value.keys() <= required | optional,
            f"object keys: missing {sorted(required - value.keys())}, "
            f"unknown {sorted(value.keys() - required - optional)}")


def integer(value, minimum=0):
    require(type(value) is int and value >= minimum,
            f"expected integer >= {minimum}, got {value!r}")


def label(value):
    require(type(value) is str and bool(value.strip()), "expected nonblank string")
    return value


def sha(value):
    require(type(value) is str and re.fullmatch(r"[0-9a-fA-F]{64}", value) is not None,
            "expected SHA256 hex identity")
    return value.lower()


def identity(value):
    keys(value, HASH_KEYS)
    return {key: sha(value[key]) for key in HASH_KEYS}


def coordinates(value, nonempty=False):
    require(type(value) is list and (value or not nonempty), "expected coordinates list")
    result = []
    for pair in value:
        require(type(pair) is list and len(pair) == 2 and
                all(type(n) is int for n in pair), "expected integer chunk coordinate pair")
        result.append(tuple(pair))
    require(len(set(result)) == len(result), "duplicate coordinates")
    return tuple(result)


def unique(value, seen, description):
    require(value not in seen, f"duplicate {description}: {value}")
    seen.add(value)


def validate(data, partial=False):
    keys(data, {"schemaVersion", "identity", "workloads", "rows"})
    integer(data["schemaVersion"], 2)
    require(data["schemaVersion"] == 2, "unsupported schemaVersion")
    frozen = identity(data["identity"])
    require(type(data["workloads"]) is list and data["workloads"], "no declared workloads")
    workloads = {}
    for workload in data["workloads"]:
        keys(workload, {"workloadId", "warmupCoordinates", "measuredCoordinates"})
        name = label(workload["workloadId"])
        require(name not in workloads, "duplicate workloadId")
        warmup = coordinates(workload["warmupCoordinates"])
        measured = coordinates(workload["measuredCoordinates"], True)
        require(not set(warmup) & set(measured), "warmup overlaps measured coordinates")
        workloads[name] = warmup, measured
    require(type(data["rows"]) is list and data["rows"], "no measurement rows")
    counts = Counter()
    repeats = set()
    ids = {key: set() for key in ("runId", "launchId", "freshWorldId")}
    evidence_ids = set()
    for index, row in enumerate(data["rows"]):
        try:
            keys(row, ROW_KEYS, GPU_KEYS | {"chunksPerSecond"})
            require(identity(row["identity"]) == frozen, "row differs from frozen identity")
            name = label(row["workloadId"])
            require(name in workloads, "undeclared workloadId")
            endpoint, backend, temperature = row["endpoint"], row["backend"], row["temperature"]
            require(type(endpoint) is str and endpoint in ENDPOINTS, "unknown endpoint")
            require(type(backend) is str and backend in MODES, "unknown backend")
            require(row["mode"] == MODES[backend], "backend/mode mismatch")
            require(type(temperature) is str and temperature in ("COLD", "WARM"),
                    "unknown temperature")
            integer(row["repetition"], 1)
            cell = name, endpoint, backend, temperature
            unique((*cell, row["repetition"]), repeats, "repetition")
            for key, seen in ids.items():
                unique(label(row[key]), seen, key)
            warmup, measured = workloads[name]
            require(coordinates(row["measuredCoordinates"], True) == measured,
                    "measured coordinates differ from declaration")
            require(coordinates(row["warmupCoordinates"]) ==
                    (warmup if temperature == "WARM" else ()), "warmup differs from declaration")
            require(temperature != "WARM" or warmup, "WARM requires explicit warmup")
            for key in ("compared", "comparedCoreChunks", "wallNanos", "p50Nanos",
                        "p95Nanos", "memoryHighWaterBytes"):
                integer(row[key], 1)
            require(row["comparedCoreChunks"] == len(measured) and
                    row["compared"] >= len(measured), "incomplete measured chunk comparison")
            integer(row["mismatches"])
            require(row["mismatches"] == 0, "mismatched evidence")
            integer(row["transferredBytes"])
            require(row["timingScope"] == "ENDPOINT_WALL", "throughput requires endpoint wall time")
            require(row["p50Nanos"] <= row["p95Nanos"] <= row["wallNanos"],
                    "invalid latency ordering")
            stages = row["stageNanos"]
            required_stages = {"generation"} | ({"save", "reopenValidation"} if endpoint == "SAVED" else set())
            keys(stages, required_stages, {"kernel"})
            for stage, nanos in stages.items():
                integer(nanos, 0 if stage == "kernel" else 1)
                require(nanos <= row["wallNanos"], "stage exceeds endpoint wall time")
            require(sum(stages[key] for key in required_stages) <= row["wallNanos"],
                    "generation/save/reopen exceed wall time")
            keys(row["failureCounts"], FAILURES)
            for failures in row["failureCounts"].values():
                integer(failures)
                require(failures == 0, "failed/fallback/recovered evidence")
            if "chunksPerSecond" in row:
                rate = row["chunksPerSecond"]
                require(type(rate) in (int, float), "rate must be a number, not bool")
                try:
                    rate = float(rate)
                except OverflowError as exc:
                    raise ValueError("rate must be finite positive") from exc
                require(math.isfinite(rate) and rate > 0, "rate must be finite positive")
                require(math.isclose(rate, len(measured) / row["wallNanos"] * 1e9,
                                     rel_tol=1e-9, abs_tol=0), "rate is not endpoint wall throughput")
            if backend == "GPU_IEEE_BITS":
                integer(row["transferredBytes"], 1)
                require(GPU_KEYS <= row.keys(), "missing GPU receipt/commit links")
                for key in ("gpuReceiptCount", "commitCount"):
                    integer(row[key], 1)
                    require(row[key] == len(measured), "GPU counts differ from compared core chunks")
                evidence = row["gpuEvidence"]
                require(type(evidence) is list and len(evidence) == len(measured),
                        "incomplete GPU links")
                covered = set()
                for link in evidence:
                    keys(link, {"coordinate", "receiptSha256", "commitSha256", "comparisonSha256",
                                "backend", "deviceProducedBlocks", "mismatches"})
                    coord = coordinates([link["coordinate"]], True)[0]
                    unique(coord, covered, "GPU coordinate")
                    require(link["backend"] == backend, "GPU evidence backend mismatch")
                    integer(link["deviceProducedBlocks"], 1)
                    integer(link["mismatches"])
                    require(link["mismatches"] == 0, "GPU receipt mismatch")
                    for key in ("receiptSha256", "commitSha256", "comparisonSha256"):
                        unique(sha(link[key]), evidence_ids, "GPU evidence identity")
                require(covered == set(measured), "GPU links do not cover measured coordinates")
            else:
                require(not GPU_KEYS & row.keys(), "CPU row claims GPU evidence")
            counts[cell] += 1
        except ValueError as exc:
            raise ValueError(f"row {index}: {exc}") from exc
    missing = [f"{name}/{endpoint}/{backend}/{temperature}"
               for name in workloads for endpoint in ENDPOINTS for backend in MODES
               for temperature in ("COLD", "WARM")
               if counts[name, endpoint, backend, temperature] < 3]
    require(partial or not missing, "missing matrix repetitions: " + ", ".join(missing))
    return {"status": "PARTIAL_VALIDATED_INPUT" if partial else "VALIDATED_BASELINE_INPUT",
            "releaseQualification": False, "rows": len(data["rows"]),
            "incompleteCells": missing}


def reject_constant(value):
    raise ValueError(f"nonfinite JSON number: {value}")


def strict_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, f"duplicate JSON key: {key}")
        result[key] = value
    return result


def main(argv=None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("manifest", type=Path)
    parser.add_argument("--partial", action="store_true", help="validate incomplete draft measurements")
    args = parser.parse_args(argv)
    try:
        data = json.loads(args.manifest.read_text(encoding="utf-8"),
                          parse_constant=reject_constant, object_pairs_hook=strict_object)
        result = validate(data, args.partial)
    except (OSError, UnicodeError, ValueError) as exc:
        print(f"baseline input rejected: {exc}", file=sys.stderr)
        return 1
    print(json.dumps(result, sort_keys=True, allow_nan=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
