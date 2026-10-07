// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.threading;

/** Implemented on ChunkMap by mixin: lets the idle server thread work through queued chunk unloads. */
public interface IdleUnloads {
    boolean tellurium$runIdleUnload();
}
