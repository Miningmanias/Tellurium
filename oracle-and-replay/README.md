# Synthetic oracle and replay

This executable compares the independent `ReferenceInterpreter` against the prebound `CpuCompiler` or a real Vulkan dispatch. The fixed `worldgennext-synthetic-v1` corpus contains eight graphs and 131 integer points per graph: **1,048 expected comparisons**. It covers negative coordinates, range edges, both 4×8 and 8×4 interpolation geometries, an explicit semantic boundary, an unselected overflowing branch, negative zero and subnormal addition. The 131-point count exercises incomplete final GPU workgroups.

It is not a Minecraft oracle. Noise-router lowering, aquifers, ore materials, biomes, heightmaps, light and saved chunks are absent. A synthetic comparison cannot establish Minecraft parity or chunks per second.

Run from the repository root using JDK21:

```powershell
.\gradlew.bat :oracle-and-replay:replay --no-daemon
.\gradlew.bat :oracle-and-replay:gpuSmoke --no-daemon
.\gradlew.bat :oracle-and-replay:gpuSmokeNormal --no-daemon
.\gradlew.bat :oracle-and-replay:run --args='replay --output=build/replay/custom-cpu.json' --no-daemon
.\gradlew.bat :oracle-and-replay:run --args='gpu-smoke --output=build/replay/custom-gpu.json' --no-daemon
```

The dedicated tasks write `oracle-and-replay/build/replay/cpu-replay.json` and `gpu-smoke.json`. Relative `--output` paths resolve in the application module directory. `:oracle-and-replay:installDist` creates a standalone launcher under `oracle-and-replay/build/install/oracle-and-replay/bin/`.

The separate `gpuSmokeNormal` task (CLI `gpu-smoke-normal`) writes `gpu-normal-range.json` with backend `VULKAN_NORMAL_RANGE_DIAGNOSTIC` and corpus `worldgennext-normal-range-diagnostic-v1`: seven fixtures, 917 samples, excluding the subnormal-addition fixture. It permits devices without advertised FP64 subnormal preservation. It never substitutes for the eight-fixture strict check, and reports full FP64 qualification as `NOT_PASSED`. This diagnostic exists because the initial RTX5070Ti driver lacks that advertised capability; the default strict gate remains intact.

Exit 0 means every expected fixture and sample passed. Exit 1 means an executed report failed comparison, capability or coverage. Exit 2 means invocation, setup or artifact-writing failure. GPU mode requires a qualified physical GPU; missing natives/devices, unsupported features, shader compilation errors, timeout, mismatch or incomplete coverage produce failure without CPU fallback. Native failure stops further submissions and exits the dedicated process.

Reports contain actual expected/compared counts, mismatches, reported/failed fixtures, scope, expression/input/value hashes and native device/shader evidence where available. CPU reports always count zero GPU samples. Incomplete coverage cannot pass. No timing or wall-clock timestamp is included, so identical CPU corpus runs produce byte-identical JSON. Evidence keys are sorted and control characters are escaped.

The tests deliberately inject raw-bit mismatches, nonfinite output, partial evaluation, missing GPU runtime, mismatched native counts and artifact-write errors. Native adapters in these tests are synthetic test doubles and are never counted as real GPU validation. Physical GPU smoke and Minecraft bootstrap are separate opt-in gates.

## Independent Minecraft capture comparison

The separate `oracle-1211` module captures original-only Minecraft snapshots. It is not loaded by the product mod and is not a candidate implementation. Compare individual captures or complete directories after two clean server runs:

```powershell
.\gradlew.bat :oracle-and-replay:run --args='compare-captures expected.snap actual.snap --failure-dir=build/oracle-failures' --no-daemon
.\gradlew.bat :oracle-and-replay:run --args='compare-corpus build/oracle-captures/original build/oracle-captures/original-repeat --failure-dir=build/oracle-failures' --no-daemon
```

The directory command requires the same nonempty case set in both directories, compares all ten logical fields per case, and writes a failure bundle when requested. It proves only the executed original-vs-original corpus; it does not prove candidate parity or a release gate.
