# WorldgenNext working instructions

Read Plan.md, docs/CONTRACTS.md and docs/TESTING.md before implementation.
This repository is independent of D:\\Projects\\GPUWorldGen. Treat that checkout as read-only reference.
No CUDA APIs, libraries, runtime dependencies, or CUDA-backed abstraction layers.
Use Java 21 for Gradle and game compilation. On the initial Windows host:
`$env:JAVA_HOME='C:\\Program Files\\Eclipse Adoptium\\jdk-21.0.12.101-hotspot'; .\\gradlew.bat test build --no-daemon`.
Use delegated agents for disjoint modules when multiple independent packages are ready. Pin shared contracts first; each worker owns its module only. Do not overlap edits or run GPU tests concurrently. The coordinator owns root build integration and documentation.
Keep synthetic replay, real GPU execution, Minecraft oracle parity, NOISE throughput, FULL throughput, and saved-world verification distinct. Never report an unimplemented gate as passed or an unsupported feature as successful generation.
Unknown frontend operations fail closed with an explicit reason. No production generation hook until its independent same-stack oracle passes.
Tests should challenge invariants, failure paths and cross-module contracts; do not create disabled placeholders that imply coverage.
All shipping source is new code unless attribution explicitly records otherwise. Do not import C2ME OpenCL implementation.
