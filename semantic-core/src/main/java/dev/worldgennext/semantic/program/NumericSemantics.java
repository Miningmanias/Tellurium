// SPDX-License-Identifier: MIT
package dev.worldgennext.semantic.program;

/** Ordered arithmetic surface required by an admitted worldgen program. */
public interface NumericSemantics {
    float add(float left, float right);
    float subtract(float left, float right);
    float multiply(float left, float right);
    float divide(float left, float right);
    double add(double left, double right);
    double subtract(double left, double right);
    double multiply(double left, double right);
    double divide(double left, double right);
    int addInt(int left, int right);
    int multiplyInt(int left, int right);
    long addLong(long left, long right);
    long multiplyLong(long left, long right);
    int floorDiv(int left, int right);
    int floorMod(int left, int right);
    long floorDiv(long left, long right);
    long floorMod(long left, long right);

    default NumericProfile profile() { return NumericProfile.JAVA_REFERENCE; }
}
