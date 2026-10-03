# WorldgenNext 0.2.0 checkpoint

A separate Java21/NeoForge1.21.1 development foundation for exact, efficient chunk generation. No CUDA and no dependency on GPUWorldGen.

**This is a correctness-first v0.2 checkpoint, not a qualified Minecraft terrain release yet.** It provides typed capture/program contracts, deterministic CPU worldgen primitives, registry-aware dense results, resource/commit/lifecycle models, a field-level oracle comparator, lazy Vulkan service boundaries, configuration modes and an installable NeoForge jar. Live Minecraft generation remains disabled until the independent oracle, real CPU/GPU replay, commit, FULL and SAVED/reopened gates qualify.

Start with [Plan.md](Plan.md), [architecture](docs/ARCHITECTURE.md), [file map](docs/FILE_MAP.md), [planned implementation files](docs/IMPLEMENTATION_MAP.md) and [current validation status](docs/STATUS.md). The [long-term proposal](docs/design/PROPOSAL.md) contains conditional performance targets; none are v0.1 benchmark claims.

The [detailed v0.2 plan](docs/V0.2-PLAN.md) covers the complete functional product, including the real Minecraft oracle, exact GPU route, live generation, FULL and saved-world verification. Focused portions are implemented; release qualification remains fail-closed. Optimization starts in v0.3.

## Throughput path (2026-10-02)

The mod now accelerates generation by default:

- NOISE runs on the GPU for routers whose generated kernel structure is on the
  shipped qualified list (vanilla Overworld/Nether/End, Terralith, Tectonic and
  their combination at the pinned versions). Anything else, and any chunk the
  kernels flag, uses the original generator.
- Structure starts/references, surface, carvers and features run on the
  worldgen worker pool instead of one thread.

Measured on the reference host: about 2,700–2,930 NOISE chunks/s on vanilla
Overworld (vanilla: about 720) and about 800 on the combined pack (vanilla:
about 490); FULL about 896 against about 119. GPU output is digest-identical to
vanilla through CARVERS in the tested contexts. See
[throughput evidence](docs/evidence/throughput-fused-gpu.md) for method and
limits; it has not yet been exercised in an installed server or client.

```powershell
# Timed benchmark (fresh world, 8,281 chunks); add -ModsDir for a terrain pack
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\bench-cps.ps1 -Status NOISE
# Digest matrix against serial vanilla for every context
bash scripts/verify-fast-matrix.sh 45
```

## Build and test

Use JDK21, including for the Gradle launcher. The wrapper downloads pinned Gradle8.14.2 with checksum verification; initial dependency downloads require internet access.

```powershell
# Windows: discover an installed JDK21, then build and test
.\scripts\check.ps1

# Or select it explicitly
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'
.\gradlew.bat test build verifyArchitecture testReport --no-daemon
python scripts/summarize-tests.py
```

```sh
# Linux, with JAVA_HOME pointing to JDK21
bash ./gradlew test build verifyArchitecture testReport --no-daemon
python3 scripts/summarize-tests.py
```

The default suite does not require a GPU or launch Minecraft. Aggregate HTML: `build/reports/tests/all/index.html`. Every module retains its own JUnit XML and HTML reports. Python is needed only for the convenience summary script.

## Run the v0.1 diagnostics

With JDK21 selected, run these from the repository root:

```powershell
# Deterministic CPU replay with a counted JSON report
.\gradlew.bat :oracle-and-replay:replay --no-daemon

# Explicit native GPU check; fails if a qualifying GPU is unavailable
.\gradlew.bat :oracle-and-replay:gpuSmoke --no-daemon

# Separate, limited diagnostic for GPUs without FP64 subnormal preservation
.\gradlew.bat :oracle-and-replay:gpuSmokeNormal --no-daemon

# Isolated NeoForge startup and mapped-capture diagnostics
.\gradlew.bat :neoforge-1211:gameSmoke --no-daemon

# Original-only Minecraft NOISE corpus and clean-process determinism preflight
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\capture-original-corpus.ps1
.\gradlew.bat :oracle-and-replay:run --args='compare-corpus build/oracle-captures/original build/oracle-captures/original-repeat --failure-dir=build/oracle-failures' --no-daemon

# The default is the 10-case preflight. A release-sized core square is 5x5;
# use a distinct context ID and frozen stack fingerprint for each real stack.
# Captures are namespaced below CaptureRoot so equal coordinates do not collide.
# powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\capture-original-corpus.ps1 `
#   -ContextId vanilla-overworld -CoreSquareSideChunks 5

# Reference-only FULL endpoint stability smoke (two fresh processes)
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\capture-original-full.ps1 `
  -OutputRoot .\build\oracle-full-check `
  -RunRoot .\build\oracle-runs\full-check `
  -Seed 0 -Dimension minecraft:overworld -ChunkX 0 -ChunkZ 0

# Reference-only SAVED/reopened smoke (two fresh processes)
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\capture-original-saved.ps1 `
  -OutputRoot .\build\oracle-saved-check `
  -RunRoot .\build\oracle-runs\saved-check `
  -Seed 0 -Dimension minecraft:overworld -ChunkX 0 -ChunkZ 0

# Rebuild the portable source archive
.\gradlew.bat sourceBundle --no-daemon
```

Replay reports are in `oracle-and-replay/build/replay/`. The dedicated game run is under `neoforge-1211/build/run/gametest/`. GPU checks are bounded smoke workloads, not terrain benchmarks. Run them one at a time.

On the initial RTX5070Ti host, the normal-range diagnostic passed 917 comparisons. The strict GPU check failed its capability gate because the driver does not advertise FP64 subnormal preservation. The two results have different corpus IDs and neither establishes full floating-point or Minecraft qualification. See [validation status](docs/STATUS.md).

The checkpoint mod is `neoforge-1211/build/libs/worldgennext-neoforge-1.21.1-0.2.0.jar`. In a Minecraft1.21.1/NeoForge installation it exposes `/worldgennext status` and `/worldgennext selftest` to operators. The latter compares synthetic CPU expressions and fixture material states; it does not accelerate or validate world generation. Native Vulkan is exercised through the separate replay application, while the persistent service is packaged with lazy initialization. Fabric packaging is deferred.

## Modules

| Module | 0.2 checkpoint responsibility |
| --- | --- |
| semantic-core | Typed programs, immutable snapshots/identities, material/execution contracts and v0.1 language |
| compiler-jvm | Scalar typed interpreter, deterministic RNG/noise/aquifer/ore primitives and dense CPU baseline |
| compiler-vulkan | Synthetic emitter plus integer-carrier helper/source and dense worldgen shader contracts |
| material-codec | Registry-aware complete dense results, metadata, little-endian ABI and fixture encodings |
| spatial-data | Full spatial identities, typed sample leases, cached/uncached stores and bounded accounting |
| chunk-engine | Unique work/subscriptions, atomic resource admission, backend receipts and authoritative commit seam |
| frontend-mc1211 | Pure captured-node/router/snapshot lowering with explicit unsupported diagnostics |
| runtime-vulkan | Persistent service lifecycle, disjoint leases, visibility, pipeline and quarantine models |
| oracle-and-replay | Isolated corpus API, field-by-field comparator, failure bundles and endpoint baseline contracts |
| oracle-1211 | Independent original-only NeoForge/Minecraft capture process; no WorldgenNext candidate dependency |
| neoforge-1211 | Config/lazy-native/snapshot/commit lifecycle composition and loader bootstrap checks |
| fabric-1211 | Deferred adapter frame; no loadable Fabric release |

See the module READMEs for runnable diagnostics and detailed API limits. [docs/CONTRACTS.md](docs/CONTRACTS.md) fixes the shared boundaries for contributors.

## Validation and future work

CPU tests, native GPU replay, game startup, Minecraft parity and performance are separate gates. A successful synthetic replay is not vanilla parity, and synthetic samples are not chunks/sec. A strict GPU check cannot substitute CPU execution for missing hardware.

The original-only Minecraft oracle slice is now runnable and has a 10-case vanilla Overworld NOISE determinism preflight plus separate reference-only FULL and SAVED/reopened boundary smokes; the opt-in CPU candidate also passes those ten independent NOISE cases. The required six-context corpus, GPU lowering/execution, and the remaining same-stack, live, FULL and saved-world candidate gates are still open. See [roadmap](docs/ROADMAP.md), [oracle evidence](docs/evidence/v0.2-original-oracle.md) and [test layers](docs/TESTING.md). The new scheduler, sparse GPU terrain production, lighting and saving arrive only after their correctness gates exist.

## Licensing

New code is MIT. Build/runtime dependencies retain their own terms. Standard Gradle wrapper files are Apache2.0; their origin and hashes are recorded in [attribution](docs/ATTRIBUTION.md). No C2ME/OpenCL-module implementation or decompiled Minecraft source is bundled.
