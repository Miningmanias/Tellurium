// SPDX-License-Identifier: MIT
package dev.worldgennext.spatial.worldgen;

import dev.worldgennext.semantic.identity.ContextIdentity;
import java.util.Objects;

/** Full spatial/cache identity. Epoch, context and dynamic dependencies cannot be omitted. */
public record SampleKey(String nodeIdentity, SampleDomain domain, SampleExtent extent,
                        int halo, String contextIdentity, long worldEpoch) {
    public SampleKey {
        if (nodeIdentity == null || nodeIdentity.isBlank()) throw new IllegalArgumentException("Node identity required");
        Objects.requireNonNull(domain, "domain"); Objects.requireNonNull(extent, "extent");
        if (halo < 0) throw new IllegalArgumentException("Negative halo");
        if (contextIdentity == null || contextIdentity.isBlank()) throw new IllegalArgumentException("Context identity required");
        if (worldEpoch < 0) throw new IllegalArgumentException("Negative world epoch");
    }
    public SampleKey(String nodeIdentity, SampleDomain domain, SampleExtent extent, int halo, ContextIdentity context) {
        this(nodeIdentity, domain, extent, halo, Objects.requireNonNull(context).worldKey(), context.worldEpoch());
    }
    public SampleExtent requestedExtent() { return halo == 0 ? extent : extent.expand(halo); }
}
