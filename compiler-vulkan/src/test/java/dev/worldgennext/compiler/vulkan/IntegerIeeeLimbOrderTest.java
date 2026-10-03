// SPDX-License-Identifier: MIT
package dev.worldgennext.compiler.vulkan;

import dev.worldgennext.compiler.vulkan.worldgen.IntegerIeeeEmitter;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Source ABI regression, not a substitute for real-device arithmetic conformance. */
class IntegerIeeeLimbOrderTest {
    @Test
    void extendedUnsignedProductMapsGlslMsbFirstOutputsToLowHighCarriers() {
        String source = IntegerIeeeEmitter.source();
        assertTrue(source.contains("umulExtended(left, right, result.y, result.x);"));
        assertFalse(source.contains("umulExtended(left, right, result.x, result.y);"));
        // The FP32 consumer reads high bits from y and low/rounding bits from x.
        assertTrue(source.contains("(product.y & 0x00008000u)"));
        assertTrue(source.contains("(product.x >> shift) | (product.y << (32u - shift))"));
    }
}
