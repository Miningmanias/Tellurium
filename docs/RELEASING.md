# Releasing

## A release of the mod

1. Set the version in `build.gradle` and `TelluriumMod.VERSION`, and add the release to `CHANGELOG.md`.
2. `gradlew test build verifyArchitecture`: the jar of every build lands in `build/release/`.
3. `bash scripts/release-check.sh` (NeoForge 1.21.1), then each released jar on a real server:
   `bash scripts/test-installed-commands.sh`, `bash scripts/test-installed-fabric.sh 100`, and the same two
   scripts with `FABRIC_MC=1.21.11` / `NEOFORGE_MC=1.21.11`. They run in the default mode, which is what
   shows that the tested-generator lists still match the kernels.
4. Copy the jars to be published, their checksums and the notes for the listing into one folder.
   For `0.2.0-alpha.1` that was the 1.21.1 and 1.21.11 jars of both loaders.

The rest of this page is about the earlier, stricter qualification route.

## The v0.2 qualification route

Release `0.2.0` requires all G0–G12 gates from [V0.2-PLAN.md](V0.2-PLAN.md) with raw manifests on one frozen source/jar/dependency snapshot. `qualifyV02` deliberately fails while `test-manifest/v0.2-acceptance.json` is `NOT_RUN`.

The installed-jar smoke is run explicitly with `testInstalledMod` and a fresh
disposable NeoForge server template. It preserves per-mode logs and a JSON
report under the supplied run root; a clean boot is packaging evidence only,
not NOISE/FULL/SAVED parity evidence.

The old v0.1 evidence remains immutable. Native smoke, synthetic replay, CPU fallback, checksums and a successful loader bootstrap cannot substitute for the same-stack NOISE, FULL and fresh-process SAVED/reopened comparisons.

## Throughput path release check

The G0–G12 gates above govern the staged `GPU_IEEE_BITS` route and are not
changed or closed by anything below. The fused GPU path, the CPU-side changes
and the save/load changes that players actually run are checked separately:

```sh
bash scripts/release-check.sh                 # about 25 minutes
FULL_MATRIX=1 bash scripts/release-check.sh   # all 15 exactness contexts
CLIENT=1 bash scripts/release-check.sh        # adds the singleplayer digest (opens a game window)
```

It runs the build and unit tests, SURFACE and CARVERS digests against serial
vanilla, FULL save/reopen, kill-and-resume with region-file validation, and
every command typed into the installed server running the release jar. It
refuses to start while another program is using the GPU. It prints one line
per check and exits non-zero if any fails; logs are in `build/release-check/`.
Not covered: power loss, Linux, other GPUs, a non-development client, LAN
play, and world generators outside the tested list (see `gpu.mode = "check"`).
