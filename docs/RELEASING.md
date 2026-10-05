# Releasing

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
