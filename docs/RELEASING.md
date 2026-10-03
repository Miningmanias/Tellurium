# Releasing

Release `0.2.0` requires all G0–G12 gates from [V0.2-PLAN.md](V0.2-PLAN.md) with raw manifests on one frozen source/jar/dependency snapshot. `qualifyV02` deliberately fails while `test-manifest/v0.2-acceptance.json` is `NOT_RUN`.

The installed-jar smoke is run explicitly with `testInstalledMod` and a fresh
disposable NeoForge server template. It preserves per-mode logs and a JSON
report under the supplied run root; a clean boot is packaging evidence only,
not NOISE/FULL/SAVED parity evidence.

The old v0.1 evidence remains immutable. Native smoke, synthetic replay, CPU fallback, checksums and a successful loader bootstrap cannot substitute for the same-stack NOISE, FULL and fresh-process SAVED/reopened comparisons.
