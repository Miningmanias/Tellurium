// SPDX-License-Identifier: MIT
package dev.worldgennext.semantic.program;

/** Java 21-compatible primitive semantics, including wrapping integer arithmetic. */
public final class JavaIntegerSemantics implements NumericSemantics {
    public static final JavaIntegerSemantics INSTANCE = new JavaIntegerSemantics();
    public JavaIntegerSemantics() {}
    @Override public float add(float left, float right) { return left + right; }
    @Override public float subtract(float left, float right) { return left - right; }
    @Override public float multiply(float left, float right) { return left * right; }
    @Override public float divide(float left, float right) { return left / right; }
    @Override public double add(double left, double right) { return left + right; }
    @Override public double subtract(double left, double right) { return left - right; }
    @Override public double multiply(double left, double right) { return left * right; }
    @Override public double divide(double left, double right) { return left / right; }
    @Override public int addInt(int left, int right) { return left + right; }
    @Override public int multiplyInt(int left, int right) { return left * right; }
    @Override public long addLong(long left, long right) { return left + right; }
    @Override public long multiplyLong(long left, long right) { return left * right; }
    @Override public int floorDiv(int left, int right) { return Math.floorDiv(left, right); }
    @Override public int floorMod(int left, int right) { return Math.floorMod(left, right); }
    @Override public long floorDiv(long left, long right) { return Math.floorDiv(left, right); }
    @Override public long floorMod(long left, long right) { return Math.floorMod(left, right); }

    public static int floatBits(float value) { return Float.floatToRawIntBits(value); }
    public static long doubleBits(double value) { return Double.doubleToRawLongBits(value); }
    public static float floatFromBits(int bits) { return Float.intBitsToFloat(bits); }
    public static double doubleFromBits(long bits) { return Double.longBitsToDouble(bits); }
    public static float roundToFloat(double value) { return (float) value; }
    public static int floatToInt(float value) { return (int) value; }
    public static long doubleToLong(double value) { return (long) value; }
}
