# Independent Minecraft original oracle

This module launches a clean NeoForge 21.1.176 / Minecraft 1.21.1 server with only the `worldgennext_oracle` mod. It intentionally has no dependency on the WorldgenNext product, compiler, Vulkan runtime or replay application. The capture is therefore an original same-stack control, not a candidate result.

Build the isolated module with JDK21:

```powershell
.\gradlew.bat :oracle-1211:build --no-daemon
```

The repeatable five-seed/two-center NOISE corpus is captured by:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\capture-original-corpus.ps1
```

Use `-Dimension minecraft:the_nether` or `-Dimension minecraft:the_end` with a
separate `-CaptureRoot`/`-RunRoot` to capture those loaded vanilla dimensions.

The script uses fresh run directories by default. Pass `-CaptureRoot` and `-RunRoot` to select artifact locations. A capture run requires quoted `-Dworldgennext.oracle.*` properties when invoking Gradle directly; `oracleSmoke` rejects a missing, stale or malformed output file.

Compare two generated directories with the pure replay application:

```powershell
.\gradlew.bat :oracle-and-replay:run --args='compare-corpus expected-dir actual-dir --failure-dir=build/oracle-failures' --no-daemon
```

This is an original-only P01 slice. It does not implement the candidate frontend, CPU parity, GPU execution, live generation interception, FULL or SAVED/reopened qualification.
