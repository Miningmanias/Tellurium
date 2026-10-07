// SPDX-License-Identifier: MIT
package dev.tellurium.semantic.program;

/** Evaluation scope is part of a program node's identity; scopes are not interchangeable caches. */
public enum EvaluationDomain {
    WORLD,
    COLUMN,
    LATTICE,
    BLOCK,
    AQUIFER_CELL,
    MARKER
}
