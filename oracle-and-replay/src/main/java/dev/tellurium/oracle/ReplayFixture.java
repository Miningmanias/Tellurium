// SPDX-License-Identifier: MIT
package dev.tellurium.oracle;

import dev.tellurium.semantic.DensityExpression;
import dev.tellurium.semantic.ExpressionIdentity;
import dev.tellurium.semantic.SamplePoint;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** An immutable synthetic graph and exact integer input set; not a Minecraft fixture. */
public record ReplayFixture(String name, DensityExpression expression, List<SamplePoint> points) {
    public ReplayFixture {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Fixture name required");
        Objects.requireNonNull(expression, "expression");
        points = List.copyOf(points);
        if (points.isEmpty() || points.size() > 4096) throw new IllegalArgumentException("Fixture requires 1..4096 points");
    }
    public String expressionHash() { return ExpressionIdentity.hash(expression); }
    public String pointHash() {
        ByteBuffer bytes = ByteBuffer.allocate(points.size() * 12);
        for (var point : points) bytes.putInt(point.x()).putInt(point.y()).putInt(point.z());
        return sha256(bytes.array());
    }
    static String valuesHash(double[] values) {
        ByteBuffer bytes = ByteBuffer.allocate(values.length * 8);
        for (double value : values) bytes.putLong(Double.doubleToRawLongBits(value));
        return sha256(bytes.array());
    }
    static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }
}
