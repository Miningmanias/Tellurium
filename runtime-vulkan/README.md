# Bounded Vulkan smoke runtime

This module owns native execution for the synthetic v0.1 expression language. It is not installed in the diagnostics mod and does not accelerate Minecraft yet. Runtime dependencies are LWJGL 3.3.3 core/Vulkan/shaderc and Windows/Linux x64 natives. No CUDA backend or CPU substitute is present.

`VulkanSmokeRunner.run` compiles the diagnostic GLSL ABI from `compiler-vulkan`, selects a GPU compute queue, creates two small coherent mapped buffers, dispatches 64-wide workgroups with a sample-count tail guard, waits on a fence and compares finite output by raw FP64 bits. Reports identify device, driver, GLSL and SPIR-V hashes. Discrete GPUs are preferred; CPU and unknown device types are rejected.

`VulkanWorldgenExecutor` is the separate persistent worldgen boundary. It owns
one instance/device/queue, validates the emitted `GPU_IEEE_BITS` SPIR-V,
dispatches bounded slices (maximum 4,096 elements), waits on a fence, and
returns device-produced state IDs from an explicit request allowlist. The
compiler can additionally emit a raw two-word-per-element state/fluid-mark
record, embed immutable captured aquifer statuses while evaluating nearest-three
pressure on the device, and bind a captured six-material ore rule with legacy or xoroshiro positional RNG; the
smoke task exercises all three material paths. Run the opt-in
`:runtime-vulkan:nativeWorldgenSmoke` task for the real-device proof. Registry-
backed Minecraft material parity and live generation integration remain
unqualified and unwired.

Native execution is opt-in through `:oracle-and-replay:gpuSmoke`. Ordinary JUnit tests cover capability selection, count validation, allocation sizes and operation budgets without calling Vulkan or shaderc.

The synthetic native replay launchers set `-Dorg.lwjgl.system.stackSize=2048` (KiB) before LWJGL initializes. The NeoForge Minecraft candidate launcher defaults to `16384` (KiB) because shaderc also parses the captured 5,237-node graph. A direct Java caller must set the value before LWJGL's thread-local stack exists.

Each dispatch is limited to 4,096 samples, 2,000,000 expanded synthetic operations and a 10-second default fence timeout. An unsuccessful fence leaves native resources quarantined because idle ownership is unproven; the dedicated CLI stops submitting and exits nonzero. This is a smoke-process lifetime policy, not a reusable production device-loss recovery system.

The capability probe checks Vulkan 1.2, shaderFloat64 and advertised FP64 preservation/rounding support. Advertised support does not enable SPIR-V float-control execution modes. The smoke tests finite fixtures including negative zero and subnormal addition; a pass establishes only their raw-bit comparisons on the named device. Explicit mode emission, Vulkan validation-layer campaigns, cross-vendor qualification, production memory arenas, persistent caches, timelines and async scheduling remain future work.

`Fp64Profile.STRICT` is the default and requires advertised subnormal preservation. `NORMAL_RANGE_DIAGNOSTIC` is an explicit, limited alternative used by `gpuSmokeNormal` for seven fixed normal-range fixtures. It still requires a GPU, FP64, signed-zero preservation and round-to-nearest support, rejects subnormal expected outputs before native use and compares every result by raw bits. It does not prove that arbitrary expression intermediates remain normal. Its successful results cannot be accepted as strict results. Production routing requires a proven numeric domain or an exact alternative for unsupported arithmetic.
