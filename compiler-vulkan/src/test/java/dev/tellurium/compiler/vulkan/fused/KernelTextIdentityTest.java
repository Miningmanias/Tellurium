// SPDX-License-Identifier: MIT
package dev.tellurium.compiler.vulkan.fused;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The tested-generator lists ({@code fused-qualified.properties}) are keyed on a hash of the whole kernel
 * source, comments included.  The parts of that source that are the same for every generator are pinned
 * here, so that a change to them (an edit, a rename across the repository, a checkout that turned the line
 * ends into CRLF) fails a unit test instead of silently sending every listed generator back to the original
 * code.  Whoever changes them on purpose runs the exactness matrix for every build, replaces the lists, and
 * then these values.
 */
class KernelTextIdentityTest {
    @Test
    void theLibraryEveryKernelStartsWithIsByteForByteWhatTheListsWereMadeWith() {
        String library = FusedNoiseCompiler.library();
        assertFalse(library.contains("\r"), "fused_lib.glsl must have LF line ends (see .gitattributes)");
        assertEquals("4fcc01f106b1c48e49c2ab3b00bdfc652bf4882dfae58c9cbf14292bf2df8948", FusedNoiseCompiler.sha256(library));
    }

    @Test
    void theVersionLineOfEveryKernelKeepsTheNameItWasListedUnder() {
        // The mod was called WorldgenNext when the lists were made; this line is part of the hashed text.
        assertEquals("worldgennext-fused-noise-v1", FusedNoiseCompiler.VERSION);
    }
}
