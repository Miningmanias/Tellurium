# Synthetic semantic core

The v0.1 immutable expression language has finite constants, integer coordinates, ordered addition/multiplication, lazy lower-inclusive/upper-exclusive range choice, and explicit interpolation boundaries. `ReferenceInterpreter` implements the pinned `DensityEvaluator` interface independently of the prebound CPU compiler. `ExpressionIdentity.hash` includes operand order, raw constant bits (including negative zero), geometry and a semantic version prefix.

An interpolated child is evaluated at eight lattice corners, then interpolated in **Y, X, Z** order using `low + fraction * (high - low)`. Surrounding operations stay outside the boundary. Nested boundaries retain their own geometry. Negative positions use floor cells; every required corner must fit the signed-int `SamplePoint` domain. Out-of-domain corners throw `ArithmeticException` rather than wrap.

The supported numerical subset preserves signed zero and subnormal values on Java21. Nonfinite literals/bounds are rejected at construction, and any nonfinite arithmetic intermediate rejects the sample, even if a later operation could mask it. Underflow to a finite signed zero is permitted. Programs are bounded to depth128, 4096 distinct nodes and a conservative 65,536 expanded sample-operation budget. These are synthetic-engine limits, not declarations about Minecraft's full numerical behavior.

```java
var x = new DensityExpression.Coordinate(DensityExpression.Axis.X);
var expression = new DensityExpression.Interpolated(x, new CellGeometry(4, 8));
double value = new ReferenceInterpreter().evaluate(expression, new SamplePoint(-1, 3, 2));
```

Tests include analytic linear/quadratic oracles, an IEEE-754 golden that distinguishes interpolation orders, branch failures, negative/cell-edge/extreme positions, signed zero, subnormals and bounded shared DAGs. Complete noise, aquifers, ore materials, biomes, surfaces, RNG effects and Minecraft registry semantics belong to later frontends; no Minecraft parity is claimed here.
