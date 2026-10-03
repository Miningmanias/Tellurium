// SPDX-License-Identifier: MIT
package dev.worldgennext.semantic;

@FunctionalInterface
public interface DensityEvaluator { double evaluate(DensityExpression expression, SamplePoint point); }
