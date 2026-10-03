// SPDX-License-Identifier: MIT
package dev.worldgennext.engine;

/** Caller-reported provenance; this MC-free model never dispatches a physical GPU itself. */
public enum ExecutionRoute { GPU, CPU_PLANNED, CPU_RECOVERY, CPU_ORIGINAL }
