# Chunk engine model

This Minecraft-free module models admission, immutable fixture-result validation and an atomic commit decision. It does not install a chunk scheduler, submit GPU commands, mutate a Minecraft chunk or perform vanilla generation. Only `MATERIAL_FIXTURE` is executable; every Minecraft `GenerationStage` explicitly fails as unsupported.

`EpochTaskEngine` reserves task bytes before admission. A result is copied and its metadata/checksum verified before commit. Cancellation, producer failure, epoch advance and duplicate completion cannot overwrite a committed result. Validation occurs outside the engine lock. Cancelling or retiring an active validator keeps its reservation until validation returns. Completion callbacks run outside the engine lock, and consumer futures cannot complete the engine's private future.

Completed task keys remain as bounded tombstones until an epoch advance. Reaching the record limit rejects admission, even when byte capacity is available. This deliberately small model has no durable completed-key store or eviction strategy suitable for an unbounded production world. Its byte reservation accounts for the required logical snapshot/task work, not peak JVM allocations, temporary codec copies or consumer-owned committed values.

`WorldgenCoordinator` separately retains a bounded terminal outcome index for
same-generation duplicate suppression. The index is retired on world/device
invalidation and is cleared during close; active backend reservations remain
owned by the coordinator until their completion callback, even when a stale
outcome is no longer publishable.

`SpatialWorkService` composes the static route policy with the typed spatial
store. It selects CPU or GPU before producer execution, consumes through the
bounded `TiledSampleConsumer`, preserves explicit `SampleWindow` residency and
fails closed when a selected route has no producer. Closing the service
cancels pending spatial requests before closing the supplied store; completed
windows remain caller-owned. It is a sample service, not a second Minecraft
holder/ticket scheduler; chunk publication remains owned by
`WorldgenCoordinator`.

`ExecutionRoute` is caller-reported provenance. Its GPU counters cannot prove physical GPU execution. CPU planned work and CPU recovery have separate counters. `RoutingPolicy` chooses a supported exact route from caller-supplied CPU time and GPU queue/execution/materialization estimates; sums saturate rather than overflow and strict GPU mode fails if unsupported. It contains no learned estimates or production dispatch policy yet.

Run `./gradlew :chunk-engine:test` on JDK 21. Tests cover validation failures, ownership, racing completions, cancellation and epoch advance during a blocked validator, admission bounds, futures, route accounting, typed spatial service selection and unsupported stages. Real chunk transactions, oracle-qualified stages, adaptive measurements, scheduling dependencies and native lifetime integration remain future milestones.
