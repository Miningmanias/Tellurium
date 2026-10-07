// SPDX-License-Identifier: MIT
package dev.tellurium.semantic;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.IdentityHashMap;

/** Stable structural identity, preserving operand order, boundary geometry and signed zero. */
public final class ExpressionIdentity {
    private ExpressionIdentity() {}

    public static String hash(DensityExpression expression) {
        ExpressionValidation.validate(expression);
        return HexFormat.of().formatHex(hashNode(expression, new IdentityHashMap<>()));
    }

    private static byte[] hashNode(DensityExpression expression, IdentityHashMap<DensityExpression, byte[]> cache) {
        byte[] prior = cache.get(expression);
        if (prior != null) return prior;
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("Required SHA-256 unavailable", e); }
        digest.update("tellurium-synthetic-v1\0".getBytes(StandardCharsets.UTF_8));
        switch (expression) {
            case DensityExpression.Constant n -> { digest.update((byte) 0); bits(digest, Double.doubleToRawLongBits(n.value())); }
            case DensityExpression.Coordinate n -> { digest.update((byte) 1); digest.update((byte) n.axis().ordinal()); }
            case DensityExpression.Add n -> { digest.update((byte) 2); digest.update(hashNode(n.left(), cache)); digest.update(hashNode(n.right(), cache)); }
            case DensityExpression.Multiply n -> { digest.update((byte) 3); digest.update(hashNode(n.left(), cache)); digest.update(hashNode(n.right(), cache)); }
            case DensityExpression.RangeChoice n -> {
                digest.update((byte) 4); digest.update(hashNode(n.input(), cache));
                bits(digest, Double.doubleToRawLongBits(n.minInclusive())); bits(digest, Double.doubleToRawLongBits(n.maxExclusive()));
                digest.update(hashNode(n.whenIn(), cache)); digest.update(hashNode(n.whenOut(), cache));
            }
            case DensityExpression.Interpolated n -> {
                digest.update((byte) 5); bits(digest, n.geometry().width()); bits(digest, n.geometry().height());
                digest.update(hashNode(n.child(), cache));
            }
        }
        byte[] result = digest.digest(); cache.put(expression, result); return result;
    }

    private static void bits(MessageDigest digest, long value) { digest.update(ByteBuffer.allocate(8).putLong(value).array()); }
}
