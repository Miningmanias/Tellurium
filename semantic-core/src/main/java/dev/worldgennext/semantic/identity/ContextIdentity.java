// SPDX-License-Identifier: MIT
package dev.worldgennext.semantic.identity;

import dev.worldgennext.semantic.program.NumericProfile;
import dev.worldgennext.semantic.program.WorldgenProgram;
import dev.worldgennext.semantic.snapshot.WorldgenSnapshot;
import java.util.Objects;

/** Reusable-result identity. Device generation is deliberately separate from world identity. */
public record ContextIdentity(String snapshotHash, String registryHash, String programHash,
                              NumericProfile numericProfile, String abiVersion, String compilerVersion,
                              long worldEpoch, DynamicInputIdentity dynamicInputs, long deviceGeneration) {
    public ContextIdentity {
        requireText(snapshotHash, "snapshotHash"); requireText(registryHash, "registryHash");
        requireText(programHash, "programHash"); requireText(abiVersion, "abiVersion");
        requireText(compilerVersion, "compilerVersion"); Objects.requireNonNull(numericProfile, "numericProfile");
        Objects.requireNonNull(dynamicInputs, "dynamicInputs");
        if (worldEpoch < 0 || deviceGeneration < 0) throw new IllegalArgumentException("Negative identity generation");
    }
    public ContextIdentity withoutDeviceGeneration() {
        return new ContextIdentity(snapshotHash, registryHash, programHash, numericProfile, abiVersion,
                compilerVersion, worldEpoch, dynamicInputs, 0);
    }
    public String worldKey() {
        return ProgramFingerprint.combine(snapshotHash, registryHash, programHash, numericProfile.name(), abiVersion,
                compilerVersion, Long.toString(worldEpoch), dynamicInputs.toString());
    }
    public static ContextIdentity of(WorldgenSnapshot snapshot, WorldgenProgram program, NumericProfile profile,
                                     String abiVersion, String compilerVersion, long worldEpoch, long deviceGeneration) {
        Objects.requireNonNull(snapshot, "snapshot"); Objects.requireNonNull(program, "program");
        return new ContextIdentity(snapshot.fingerprint(), snapshot.registry().fingerprint(), program.fingerprint(), profile,
                abiVersion, compilerVersion, worldEpoch, snapshot.dynamicInputs(), deviceGeneration);
    }
    private static void requireText(String value, String name) {
        if (Objects.requireNonNull(value, name).isBlank()) throw new IllegalArgumentException(name + " is blank");
    }
}
