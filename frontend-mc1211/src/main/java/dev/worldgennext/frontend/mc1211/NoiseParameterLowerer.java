// SPDX-License-Identifier: MIT
package dev.worldgennext.frontend.mc1211;

import dev.worldgennext.semantic.snapshot.NoiseParameters;
import java.util.List;

public final class NoiseParameterLowerer { public NoiseParameters lower(String key, int firstOctave, List<Double> amplitudes, long salt) { return new NoiseParameters(key, firstOctave, amplitudes, salt); } }
