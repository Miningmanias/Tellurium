// SPDX-License-Identifier: MIT
package dev.worldgennext.frontend.mc1211;

public final class MarkerLowerer { public String cacheMode(SourceNodeSnapshot source) { if (source == null) throw new NullPointerException("source"); return source.parameters().getOrDefault("mode", "NONE"); } }
