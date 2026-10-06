// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.threading;

/** Implemented on ChunkMap by mixin: lets the idle server thread work through queued chunk unloads. */
public interface IdleUnloads {
    boolean worldgenNext$runIdleUnload();
}
