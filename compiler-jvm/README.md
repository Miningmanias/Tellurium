# Prebound CPU compiler

`new CpuCompiler().compile(expression)` validates once and binds immutable nodes into reusable `CompiledDensity` callbacks. Sampling performs no AST type dispatch. Shared AST identities share compiled callbacks; each sample remains independent and thread-safe. This is **prebinding**, not generated JVM bytecode or a claim of measured acceleration.

Range branches are lazy. Explicit interpolation boundaries use independently implemented coordinate/corner logic and ordered Y/X/Z arithmetic. Nonfinite intermediates and signed-int corner overflow fail with `ArithmeticException`; accepted subnormal and signed-zero behavior follows Java21 semantics.

The deterministic suite compares 3200 seeded samples bit-for-bit against the independent interpreter, plus separate analytic and golden tests that can detect bugs shared by two implementations. It also verifies parallel calls to one compiled program. Run from the repository root on JDK21:

```powershell
.\gradlew.bat :compiler-jvm:test --no-daemon
```

Future work is actual bytecode generation and batched exact noise/router/material evaluation, qualified against an independent unaccelerated Minecraft/same-modpack oracle. Neither that frontend nor native SIMD exists in v0.1.
