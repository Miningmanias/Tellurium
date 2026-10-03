#!/usr/bin/env python3
"""Attributes JFR execution samples of a benchmark run to worldgen stages.

Usage: jfr-stage-profile.py <recording.jfr> [startHH:MM:SS endHH:MM:SS] (local wall-clock window)
Requires the JDK's `jfr` tool on PATH or JAVA_HOME.  A sample is charged to the
first matching marker found anywhere in its stack, in the order below.
"""
import collections
import json
import os
import subprocess
import sys

MARKERS = [
    ("light", ["LightEngine", "starlight", "scalablelux", "ThreadedLevelLightEngine", "SWMRNibbleArray", "StarLight"]),
    ("features", ["applyBiomeDecoration", "generateFeatures", "FeatureRegionScheduler"]),
    ("carvers", ["applyCarvers", "generateCarvers"]),
    ("surface", ["buildSurface", "generateSurface"]),
    ("gpu-apply", ["FastChunkApplier", "FastNoiseEngine.applyOrVerify"]),
    ("gpu-submit", ["FastNoiseEngine.submit", "FastNoiseEngine.complete", "FastNoiseEngine.loop", "FastNoiseEngine.tryGenerate",
                    "FastNoiseEngine.gatherBiomes"]),
    ("noise-cpu", ["fillFromNoise", "doFill"]),
    ("biomes", ["doCreateBiomes", "createBiomes", "fillBiomesFromNoise"]),
    ("structure-starts", ["createStructures", "generateStructureStarts"]),
    ("structure-refs", ["createReferences", "generateStructureReferences"]),
    ("save", ["ChunkSerializer", "AsyncSectionEncoding", "IOWorker", "RegionFile", "ChunkStorage", "NbtIo"]),
    ("full/promote", ["ChunkStatusTasks.full", "LevelChunk.<init>", "postProcessGeneration", "ProtoChunk"]),
    ("spawn", ["generateSpawn", "spawnOriginalMobs"]),
    ("chunk-system", ["ChunkMap", "ChunkHolder", "DistanceManager", "ChunkTaskPriorityQueue", "GenerationChunkHolder",
                      "ChunkGenerationTask", "ProcessorMailbox", "ServerChunkCache"]),
    ("gc/jit", ["GCTaskThread", "CompilerThread"]),
]


def main():
    path = sys.argv[1]
    jfr = os.path.join(os.environ.get("JAVA_HOME", ""), "bin", "jfr") if os.environ.get("JAVA_HOME") else "jfr"
    raw = subprocess.run([jfr, "print", "--json", "--events", "jdk.ExecutionSample", "--stack-depth", "96", path],
                         capture_output=True, text=True, check=True).stdout
    events = json.loads(raw)["recording"]["events"]
    window = sys.argv[2:4] if len(sys.argv) >= 4 else None
    stage = collections.Counter()
    tops = collections.defaultdict(collections.Counter)
    total = 0
    for event in events:
        values = event["values"]
        if window and not (window[0] <= values["startTime"][11:19] <= window[1]):
            continue
        frames = values.get("stackTrace", {}).get("frames", [])
        names = [f["method"]["type"]["name"] + "." + f["method"]["name"] for f in frames]
        text = " ".join(names)
        label = "other"
        for name, keys in MARKERS:
            if any(key in text for key in keys):
                label = name
                break
        total += 1
        stage[label] += 1
        if names:
            tops[label][names[0]] += 1
    print(f"samples={total}")
    for label, count in stage.most_common():
        print(f"{label:18s} {count:7d} {100.0 * count / max(1, total):5.1f}%")
        for frame, n in tops[label].most_common(6):
            print(f"      {n:6d} {frame}")


if __name__ == "__main__":
    main()
