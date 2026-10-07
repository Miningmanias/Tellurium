// SPDX-License-Identifier: MIT
package dev.tellurium.semantic;

@FunctionalInterface
public interface DensityEvaluator { double evaluate(DensityExpression expression, SamplePoint point); }
