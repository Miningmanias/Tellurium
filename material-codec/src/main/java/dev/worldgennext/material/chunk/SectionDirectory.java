// SPDX-License-Identifier: MIT
package dev.worldgennext.material.chunk;

public record SectionDirectory(int sectionY, int offset, int length, int nonAirCount) {
    public SectionDirectory {
        if (offset < 0 || length < 0 || nonAirCount < 0 || nonAirCount > 4096) throw new IllegalArgumentException("Invalid section directory entry");
    }
}
