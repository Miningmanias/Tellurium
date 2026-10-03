// SPDX-License-Identifier: MIT
package dev.worldgennext.oracle.schema;

/** Marker/version contract for the classpath-isolated original capture artifact. */
public final class CorpusApiVersion {
    public static final int SCHEMA_VERSION = 1;
    public static final String ARTIFACT_ID = "worldgennext-corpus-api";
    private CorpusApiVersion() {}
    public static String identity() { return ARTIFACT_ID + "-v" + SCHEMA_VERSION; }
}
