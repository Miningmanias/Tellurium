// SPDX-License-Identifier: MIT
package dev.worldgennext.semantic.program;

/** Values that may cross the typed worldgen program boundary. */
public enum ValueType {
    FP32,
    FP64,
    INT32,
    INT64,
    BOOLEAN,
    BLOCK_STATE,
    FLUID_STATE,
    VOID
}
