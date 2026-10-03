// SPDX-License-Identifier: MIT
package dev.worldgennext.frontend.mc1211;

public final class SplineLowerer { public boolean preservesExtrapolation(SourceNodeSnapshot source) { return source != null && source.parameters().getOrDefault("extrapolation", "linear").equals("linear"); } }
