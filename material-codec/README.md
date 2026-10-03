# Material codec

This Java 21 module encodes a complete 4,096-state fixture section as immutable uniform, sorted-palette or dense data. IDs are nonnegative fixture integers with air ID 0. They are not Minecraft registry IDs. Selection runs after all states are available and does not infer materials from density signs.

`SectionCodec.encode/decode` validate ownership and metadata. `toBytes/fromBytes` use a bounded version-1 little-endian format: magic, version, encoding type, payload length and non-air count as five 32-bit words, followed by a 64-bit logical checksum. Payloads contain one state, 4,096 states, or a palette size, sorted states and packed indices. Parsing rejects invalid lengths, unknown versions/types, negative IDs, invalid or unused palette indices, nonzero padding and inconsistent metadata. FNV-1a detects accidental corruption; it is neither cryptographic authentication nor a vanilla parity oracle.

`SectionMetadata` reports fixture state counts and highest non-air local Y per column using `index = y*256 + z*16 + x`. Minecraft fluids, ores, light, tick lists, post-processing and chunk mutation are deferred.

Run `./gradlew :material-codec:test` on JDK 21. The suite includes seeded round trips over packing widths, independent checksum data, aliasing, complete malformed-input cases and metadata coordinate assertions. The implementation favors an inspectable reference format; its multiple temporary copies are not a production zero-copy fast path.
