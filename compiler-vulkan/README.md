# Synthetic Vulkan shader compiler

`new GlslCompiler().emit(expression)` returns one complete GLSL450 compute shader. Each node has a callable function; branch-exclusive calls occur inside `if/else` control regions. The emitter preserves interpolation boundaries and emits explicit `precise` arithmetic. Constants use their exact two-word IEEE bit representation, including negative zero and subnormals.

ABI v1: local size64; binding0 readonly std430 `ivec4 points[]` (16 bytes/sample); binding1 writeonly std430 `double values[]` (8 bytes/sample); push constant `uint sampleCount`. The invocation checks sampleCount before reading its point. Runtime must bind sufficiently sized buffers and dispatch enough groups; the fourth point component is unused.

**Failure contract:** a nonfinite intermediate or out-of-signed-int lattice corner writes canonical quiet NaN `0x7ff8000000000000`. The consumer must reject every nonfinite output; valid results are always finite. A valid `sampleCount` and correctly sized resources remain host obligations. No error flag is hidden in a successful value.

FP64 support alone is insufficient qualification. Runtime must query/enable appropriate IEEE rounding and signed-zero/NaN/denormal preservation, inspect generated SPIR-V constraints, and compare actual GPU results. `precise` restricts contraction; it does not independently establish every required hardware numerical property. Core tests inspect ABI/control-flow/source contracts; shaderc compilation and physical execution are separate runtime gates and must not be reported as covered by string assertions.

No Minecraft noise primitives, aquifer/ore decisions, sparse material output or production generation hooks are implemented. Later compilation must own these exact semantics before importing graphs or reporting terrain parity.
