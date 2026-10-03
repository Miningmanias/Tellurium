# NeoForge 1.21.1 WorldgenNext checkpoint

`worldgennext-neoforge-1.21.1-0.2.0.jar` is a loadable correctness-first checkpoint for NeoForge21.1.176, Minecraft1.21.1 and Java21. It packages the typed semantic/compiler/material/spatial/engine/frontend modules and persistent-runtime contract classes. Native initialization is lazy. It still does not enable a mixin generation hook or replace Minecraft's holder scheduler.

Operator commands (permission level2):

- `/worldgennext status` reports version, unsupported Minecraft frontend and unloaded GPU runtime.
- `/worldgennext selftest` runs 65 synthetic CPU density comparisons and a 4,096-state section codec round trip; this is not Minecraft parity.

Build using `:neoforge-1211:build`. The release jar is under `neoforge-1211/build/libs/`. `:neoforge-1211:gameSmoke` launches an opt-in real GameTest server in `neoforge-1211/build/run/gametest/`. Nine required diagnostics check main-mod registration/core availability, explicit rejection of an actual Minecraft generator, default-off version-pinned hook bypass, typed mapped range capture, real ProtoChunk application/rollback, injected mutation-failure restoration, authoritative commit-token behavior, and bound 1.21.1 Overworld/Nether/End NoiseRouter capture with direct `finalDensity` raw-bit parity. They do not test candidate chunk/material equivalence or GPU execution. The test mod and its structure fixture are excluded from the release jar.

The existing GameTest `worldgennext_test:empty` structure is an original generated gzip NBT fixture: DataVersion3955, 5×5×5 dimensions, air palette, empty block/entity lists. It remains a loader/bootstrap smoke fixture, not a terrain-parity corpus.

The opt-in candidate artifact producer accepts `-Dworldgennext.candidate.dimension=minecraft:overworld|minecraft:the_nether|minecraft:the_end`; it remains an isolated capture path and never enables the live generation hook.

`runServer` is also framed for manual development and uses its own `build/run/server` directory; accepting the Minecraft EULA is a separate operator action. No EULA file is generated or accepted by this project.
