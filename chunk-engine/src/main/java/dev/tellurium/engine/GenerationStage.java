// SPDX-License-Identifier: MIT
package dev.tellurium.engine;

/** Only MATERIAL_FIXTURE is executable in v0.1; Minecraft stages remain explicit unsupported contracts. */
public enum GenerationStage { MATERIAL_FIXTURE, BIOMES, NOISE, SURFACE, CARVERS, FEATURES, LIGHT, FULL, SAVED }
