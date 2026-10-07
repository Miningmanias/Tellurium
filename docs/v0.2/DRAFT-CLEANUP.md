# v0.2 draft cleanup log

This file records work that is intentionally shaped for a proof-of-concept
build rather than a release-quality mod. An item stays here until it has been
reworked, verified on the supported matrix, or explicitly accepted as part of
the production design.

## 2026-10-01 — FULL continuation scheduling control

- Preserve the failed first GPU FULL run: 80 block substitutions, the other
  nine fields equal. All 25 backend NOISE halo results independently match;
  these are backend artifacts, not separate post-publication halo snapshots.
  The identical old CPU-coordinator mismatch and passing pre-coordinator
  inline CPU result point toward a scheduling seam. The isolated reference
  now dispatches, computes and commits synchronously in Minecraft's NOISE
  task. CPU/GPU FULL and GPU SAVED/fresh reopen now independently match all
  ten fields each for one vanilla seed-0 core; expand both backends to the
  required six-context/216-core endpoint matrices, then qualify a nonblocking
  authoritative scheduling design. Do not treat this blocking reference as
  a production throughput policy or infer TPS from server process time.
- Synchronous backend execution can block before a request-future timeout
  is installed. The owned-process deadline bounds this verifier; add native
  operation deadlines and actual busy cancellation/close evidence before
  production use. The normal runtime still owns its separate coordinator
  worker and remains unchanged/unqualified.
- Logical replay now supports frozen main class/resource inputs and skips
  recompilation through both initial/reopen processes and independent
  comparisons. Eleven synthetic helper checks pass. Consolidate duplicated
  manifest scanners later; bind actual source/config/mod/installed-jar bytes
  and exercise real process drift/failure, not just mocked fixtures.
- The new pure dispatch test checks caller affinity, a failed backend,
  forbidden commit, terminal completion and released reservation. An initial
  test compile typo (`admittedBytes` vs `reservedBytes`) was corrected; 126
  NeoForge tests and root jar/architecture checks pass on the rebuilt input.
- Original capture can now select canonical signed seed strings, so the
  required FULL/SAVED two-seed 3x3 grids can be captured without generating
  unused extreme-seed endpoints. It uses the bounded owned-process helper
  instead of an unbounded direct Gradle call. Seventeen plan/rejection tests
  pass without game execution. Run real original capture/repeat campaigns,
  complete fresh reopened originals and consolidate root/symlink/preflight
  validation with the newer matrix harness before release.
- The fresh six-context captured GPU matrix is running serially on the same
  main-input fingerprint as the passing endpoint witnesses. Keep receipt
  progress separate from final independent field comparisons; retain any
  failure. Do not rebuild main classes mid-campaign or combine old-build
  receipts into this gate. Native pipeline caches remain tightly bounded
  (8 ordinary/32 reusable); measure compile/dispatch/packing phases before
  enlarging driver retention or claiming a throughput improvement.

## 2026-09-30 — isolated GPU live and campaign runners

- The prior 250-case Terralith replay failed after 237 receipts; preserve that
  failure. Its one-state Long.MAX_VALUE water/stone counterexample is corrected
  by unsigned-magnitude Java floor helpers, with a complete single-chunk
  independent pass. Rerun all six contexts on new frozen inputs rather than
  merging older receipts into a new-build gate. Extend direct physical cell
  interpolation boundary coverage and replace console-only native floor
  smoke output with create-new structured reports. No TPS inference is made.
- The explicit 500,000-request coordinator MODEL campaign passes and reaches
  the retention limit, with all final counts zero. It uses a manual single
  worker and synthetic byte estimates, reflection for leak inspection and
  cached valid fixtures. Add actual native/game busy-close/reload campaigns;
  this component is not the whole coordinator or lifecycle release gate.

- The logical NOISE/FULL/SAVED verifier now selects CPU or exact GPU explicitly.
  Its GPU provider has no production allowlist and cannot pass production
  registration. Run actual core/halo GPU NOISE, downstream and reopen parity
  after isolated replay. Twenty-one new focused contracts now pass within the
  ordinary rebuilt 327-test checkpoint; tests/build do not prove Minecraft
  publication. The first real one-case GPU logical NOISE publication now passes
  all ten independent fields with linked zero-commit backend/actual-commit
  receipts. Full live/downstream coverage remains mandatory and open.
- The original verifier retained coordinator-thread publication because
  its server mailbox waits synchronously; the October 1 reference now runs
  dispatch/compute/publication inline in the actual NOISE task. Replace this with a nonblocking
  authoritative game-thread capture/publication harness before production
  threading qualification. Evidence-future cancellation now forwards to the
  actual request; physical computations are tracked separately from logical
  requests, and shutdown defers exact-once disposal until both retire. Eight
  lifecycle and six observation tests cover cancellation, deferred/failed
  close and observer failures. Verify those semantics in busy native/game
  shutdown/restart, and audit partial evidence-write failures.
- New GPU debug evidence separates validated execution from actual publication
  and refuses overwrite. The CPU provider's older eager committed-count/debug
  writes still need the same phase split. GPU storage-ABI counts include air
  tails; preserve actual device-row and native-submit counters separately.
- Matrix runner, merger and per-context runner duplicate compiled-input hashing
  and process containment. Consolidate after the prototype. Add actual serial
  timeout/exit failure integration for the matrix wrapper; current matrix
  failure/drift tests mock child execution. Preserve failures rather than
  retrying them into a release pass.
- The local matrix manifest points at existing build fixtures, not checked-in
  worlds. Freeze/revalidate capture/mod/config/source/jar identities for the
  final campaign; do not promote PlanOnly or 250 Terralith receipts to a full
  six-context gate. The older frozen build cannot validate new live sources.
- Baseline schema v2 intentionally breaks the weak legacy six-field schema.
  It validates declared identities, coordinate/evidence links and measured
  metrics only; add artifact rehashing and collection of real matched cold/warm
  endpoint timings before any performance claim. Distinguish repetitions from
  cache hits, generation/save time from reopen validation, and kernel time from
  TPS. Evaluate legitimate shared comparison artifacts before relaxing the
  draft validator's global evidence-hash uniqueness rule.

## 2026-09-14

- Added an explicit `tellurium.prototype.cpuLive=true` development mode.
  It installs the existing isolated CPU candidate provider into an ordinary
  running NeoForge server, keeps the original downstream stages, and supports
  a bounded `tellurium.prototype.cpuWorkers` pool. It is default-off,
  rejects `GPU_REQUIRED`, and remains unqualified prototype behavior; the
  production hook still requires an evidence-bearing registration. Expand the
  live CPU matrix, add proper route/context admission, and remove or replace
  this escape hatch before release.
- Added `scripts/run-prototype-cpu-live.ps1`, which starts that mode with
  unique disposable server/result roots, validates the worker bound, and
  records optional per-chunk CPU NOISE result artifacts. It is an operator
  convenience for a playable draft, not a replacement for the isolated
  original/candidate qualification harness. A fresh 8-worker run reached
  `Done (95.755s)` after emitting 121 artifacts (289,542,767 bytes), 121
  backend receipts and 121 committed status sidecars; the disposable process
  was externally stopped after readiness rather than gracefully saved. Add
  server-port selection, structured live receipts, crash recovery, and a real
  release launcher later. The wrapper now writes a
  small `cpu-live-report.json` after shutdown, but that report is only a
  readiness/artifact-count witness and does not add oracle or commit evidence.

## Current draft debt

- The Terralith extreme-seed expansion timed out after 47 complete receipts.
  Three cases were recovered without recompiling and the combined fifty match
  4,915,200 states/500 independent fields, zero differences. The first group
  remains a recorded timeout. Longer group budgets/checkpointed replay and
  generation-start bytecode/resource binding are still needed; the merged
  report does not claim release qualification. The report's block-total
  dictionary measurement was corrected after the independent comparator
  already confirmed the exact total. Add automated negative provenance,
  duplicate, stale-output and interrupted-copy coverage to the new harness
  utilities. They currently require PowerShell 7 and duplicate the compiled
  manifest scanner; centralize that implementation later.
- RawRequest policy derivatives now share owned immutable input storage and
  fresh slices avoid a second clone. Seven focused ownership tests pass.
  Construction/getters stay defensive, but converting the record to a final
  class changes reflection/serialization expectations; review external API
  stability before release. Full-row readback/repacking copies remain.
- Program fingerprints now stream unchanged canonical bytes with per-computation
  identity-keyed header caching. Four independent legacy-format tests pass.
  Keep exact identity compatibility, add deeper/larger graph controls, and
  address recursive spline-header/control-region formatting separately. The
  latest focused checkpoint passes 330 tests plus release packaging, the wide
  63,488-comparison GPU kernel control, four real Terralith chunks and the
  combined-mod negative/positive pair, all with zero differences. No TPS
  improvement is inferred without a matched, qualified live workload.

- The old combined-mod blended fan-in timed out in native pipeline compilation
  after eight minutes. A new compiler-owned 84/8/86-word reduction passes 600
  physical final results and 1,200 intermediate checkpoints without embedding
  dozens of restoring dividers into one module. Main/10 remains the exact
  staged divider; power-of-two scaling preserves IEEE semantics. Complete
  context parity, cross-driver behavior and nonempty structure cases remain
  open. Readback/repacking at both transitions is still a prototype cost;
  console-only native receipts and the diagnostic old-module toggle need cleanup.

- Captured beardifier markers now emit in both carrier types, and standalone
  FP64 structure/combine primitives pass 896 physical comparisons. Full-chunk
  nonempty-structure parity remains open. Standalone/resident candidate stages
  use inline helpers: the broad out-of-line primitive control failed with
  device loss. Preserve that negative control and qualify source/policy on
  supported drivers; do not interpret this A/B as a proven single cause. The
  13,824-word kernel now uses a guarded owned metadata suffix. Wide controls
  pass 63,488 raw-bit comparisons, and the real chunk now reaches final material
  output without device loss; it still differs at 56 granite/stone positions.
  Do not promote that failed chunk into parity. Add
  machine-readable primitive receipts and resident row-preservation coverage.
  Old-world blending remains explicitly unsupported, not silently omitted.
  The first actual nonempty embedded-marker trial also lost the device with
  a graph wrapper marked DontInline. Both stage request builders now align
  reached beardifier helpers with the fully inline primitive policy; retain
  that failure and require the targeted independent Minecraft comparison.
  The fully inline retry and captured-piece standalone wide control also
  lost the device; wrapper flags alone do not explain it. A guarded shared
  kernel-suffix ABI contains that observed wide-fixture failure, without proving
  a general driver cause. Qualify resident row preservation independently.
- Exact isolated ore now stages toggle/ridged/gap graph roots independently and
  repacks their GPU carriers into a ten-word preparation row. Two GPU preparation
  kernels and the shared exact divider supply edge/richness fractions to a
  fourteen-word finish row, removing monolithic restoring divides as well.
  The noise-input-only trial lost the device at its ore fence in 87,204 ms;
  keep it as negative evidence, not proof that all ore inputs were accurate.
  This targets the
  56-state Terralith counterexample; the bounded retry now matches all 98,304
  states and ten independent fields, with zero differences. That one nonempty
  case is not the full 250-case context. A quiet same-jar 50-case seed expansion
  is separate evidence, not implied by the witness.
  The parent rule/RNG remains on GPU, not a CPU ore fallback. Replace controlled
  GLSL assignment inspection with a typed compiler manifest, avoid three separate
  graph traversals/readback transitions, and extend the diagnostic resident row
  before enabling staged ore there. The old monolithic control remains available.
  The ore emitter also normalizes an all-zero positional xoroshiro state using
  Minecraft's constructor fallback. That pure regression is not the cause of
  the observed chunk failure (its captured high state word is nonzero).
  The bounded route still pays for two 29-phase host-mediated divisions and
  eager three-root sampling. Share common ore graph work, qualify shorter
  resident divider batches, make derived shader row-layout headers accurate,
  and add machine-readable per-stage planning/failure timings before claiming
  sustained live TPS improvements.
- FixedBatchBuilder now advances safely near Integer.MAX_VALUE and returns
  immutable null/empty results. Six new pure tests pass; huge real allocations
  are intentionally not made just to exercise the arithmetic boundary.

- The typed frontend now rejects request seed/dimension mismatches, unknown
  router roots, partial programs, and programs whose root values differ from
  the captured snapshot. This closes a prototype admission hole, but the
  version-pinned reader still needs broader mapped-node coverage and the
  resulting exact validation must be exercised against every supported
  Minecraft/terrain-mod fixture before release.
- The captured-noise path now has a shared on-device sampler and a staged
  density source splitter. The sampler's GLSL array-parameter ABI and the
  current no-inline prefixes need validation on every supported driver; the
  implementation should eventually be generated from a typed shader IR rather
  than assembled by source-level parsing.
- Specialized staged shaders still pass intermediate values through host
  readback and repacking between dispatches. A production implementation
  should keep those buffers device-resident and use explicit GPU buffer
  ownership/fences.
- Captured permutation tables now use one explicit packed `[64]` table ABI for
  the shared sampler and its callers; the byte unpack remains on the device.
  The source-level stage compactor still accepts legacy `[256]` custom shader
  text as a compatibility path. Replace both with a compiler-owned resource
  manifest and driver-tested resource layout before release.
- Staged raw dispatches now carry an explicit Vulkan pipeline
  `DISABLE_OPTIMIZATION` policy instead of relying on a process-global toggle;
  it is still an unqualified driver-envelope experiment. Shader `DontInline`
  selection is now request-local as well, avoiding cross-provider system
  property races. Replace both policies with a documented per-driver policy or
  remove them before release. Successful isolated GPU candidate receipts now
  include the executor-lifetime dispatch, element, input/output byte and wall
  time counters; add separate allocation high-water/pipeline-phase timing and
  a per-case telemetry window before release.
- The capture worker is a draft lifecycle: it needs cancellation, backpressure,
  executor reuse, crash recovery, and a clear server-thread shutdown contract.
  The isolated CPU artifact path now keeps capture on the server thread and
  pipelines immutable pure materialization through a bounded two-worker pool;
  this is a proof-run speedup, not a live scheduling policy. Per-case PASS/FAIL
  status sidecars now make incomplete artifacts visible, but the status format
  is still a temporary operator protocol.
- The candidate runner now supports bounded server batches and a configurable
  pure CPU worker pool (the completed Tectonic evidence run used 50 cases per
  server process and two workers). This keeps the draft from retaining an entire
  modded world plus every captured graph indefinitely, but it pays startup cost
  and its new `-Resume`/checkpoint protocol is still a file-backed operator
  tool rather than a coordinator-owned queue. Replace it with a coordinator-
  owned bounded queue and durable manifest before using it as an operator
  service.
- Qualified CPU and GPU provider attempts now submit through the runtime-owned
  coordinator. The shared queue and host reservation are real, while the
  provider still supplies request-local backend and Minecraft commit handlers.
  A schema-1 `/tellurium status-json` snapshot now exposes queue/admission
  dimensions and every independent coordinator counter, but it is still
  operational telemetry rather than a durable qualification receipt. The
  draft does not yet coalesce holder retries or run continuation work on a
  separately proven Minecraft mailbox; those need an integration pass before
  enabling a live route broadly. Runtime composition now dispatches through a dedicated
  daemon-backed coordinator mailbox instead of the Minecraft caller thread;
  make the thread a loader-owned non-daemon resource with explicit executor
  metrics and a proven mailbox handoff before release.
- The coordinator now exposes a completion-mailbox binding. The NeoForge
  runtime binds validation, commit and downstream continuation to the
  authoritative server executor for live and isolated logical runs, while
  backend dispatch remains coordinator-owned. This is a draft thread-safety
  improvement; add mailbox identity/latency telemetry and an explicit
  shutdown handoff contract before release.
- A one-case isolated real-server `NOISE` smoke now exercises that coordinator
  wiring end to end (`build/coordinator-smoke/noise.snap`, 221,640 bytes,
  `PASS`, clean server shutdown). It proves request admission, candidate
  materialization, authoritative commit and the endpoint artifact, but it is
  not a same-stack comparison or production qualification. Keep the smoke
  artifact as a wiring witness and replace the current inline isolated
  continuation with a separately proven mailbox contract before release.
- The combined Terralith/Tectonic/Lithostitched 250-case run and the separate
  Tectonic-only 250-case run are now complete CPU evidence. The
  Tectonic replay used five 50-case server batches, two pure materialization
  workers, and about 90 minutes of wall time. The file-backed batch runner is
  still a prototype; replace its repeated server startup and sequential
  corpus comparison with a durable bounded harness before release.
- Runtime graph capture and pure lowering now reuse identity-keyed readers,
  lowered nodes, and compiled routers across a candidate worker lifetime. The
  caches are intentionally unbounded within that lifetime and rely on process
  teardown for reclamation; add size/epoch limits and hit/miss telemetry before
  long-lived live generation.
- `TiledSampleConsumer` and `SpatialWorkService` now provide the first real
  engine-side demand path for lattice/column/surface/aquifer samples. The
  draft still copies each tile through host-owned `SampleLease` arrays and
  holds every child lease until the aggregate window closes. Closing the
  service now cancels pending windows before closing the store, while already
  completed windows remain explicitly caller-owned. Replace that with
  device/arena-backed spatial buffers, streaming tile release and a
  coordinator-owned residency report before large live regions; the current
  tile-size and in-flight limits are correctness defaults, not tuned policy.
- The source-level reachability parser and function splitter are temporary
  tooling. Replace them with compiler-produced stage manifests and structural
  validation before accepting arbitrary datapacks or large modded graphs. The
  current 75 KB split threshold and 512-stage ceiling are driver-envelope
  probes, not a supported performance policy; lower thresholds caused
  recursive stage-plan explosion in the current source-level planner.
- Qualification receipts now have a deterministic generated writer, hash-check
  their referenced source artifact at load time, and bind result ABI/compiler
  identity at provider admission. Release work should replace this unsigned
  local properties file with a signed/manifested evidence bundle and verify all
  identity fields against the frozen source, jar, dependency and driver
  snapshot.
- Qualification now has a version-2 multi-context bundle shape. It keeps each
  captured `contextKey` exact while aggregating the minimum 1,500 cases, and
  validates every entry's route/ABI/compiler identity and source-artifact hash.
  The new `write-qualification-bundle.ps1` operator path can assemble that
  shape from per-context replay reports. The bundle is an admission seam, not
  evidence by itself: the six-context corpus, signed/manifested provenance and
  full live matrix still need to be generated before enabling a production
  route. Provider registration now also checks that the provider's exact
  allowlist equals the bundle rather than relying only on its route labels.
- Density staged compilation now materializes one stage source and shader
  object at a time and drops intermediate device results after their last
  consumer. This avoids retaining the full source/object frontier for large
  Overworld graphs. The draft density policy now also keeps captured graph,
  noise-wrapper and integer IEEE helper calls as explicit DontInline
  boundaries, while leaving the packed sampler inlineable because its array
  parameter has a driver-specific ABI hazard; `NONE` and an explicit
  comma-separated prefix list are available for driver experiments. The
  tested first Overworld stage now compiles and dispatches under this policy,
  but the full multi-stage path and per-stage native high-water/compile-time
  telemetry remain open. `stopAfterDensityStage` is a diagnostic stop, never
  a successful candidate result.
- The staged aquifer/ore/material requests now carry a separate default
  `wg_,noise_` no-inline policy. The first unrestricted full Overworld attempt
  reached aquifer compilation and grew the server from about 5.5 GiB to 11 GiB
  in seconds before it was stopped. Keep the prefix request-local and expose
  the `NONE` override only for driver experiments; replace the source-level
  function-control workaround with compiler-owned stage metadata and measured
  per-pipeline memory limits before release.
- Runtime execution receipts now retain the exact program plus shader/SPIR-V
  artifact hashes used by the GPU provider, and commit publication compares
  those fields instead of allowing a committer to strip them. CPU and injected
  lifecycle seams use an explicit `NOT_APPLICABLE` marker; the generic Vulkan
  service now has an explicit compiled-artifact handoff overload, while its
  shorter injected-dispatch overload remains a draft-only compatibility seam.
- The configured native byte budget now reaches the provider-owned Vulkan
  executor and rejects a dispatch that cannot fit alongside retained
  quarantine bytes. The current ceiling accounts for active input/output
  buffers, not shader compiler/pipeline allocations or JVM object overhead;
  those measurements need a separate structured high-water implementation.
- The real GPU matrix is incomplete. Overworld captured-noise generation,
  FULL/SAVED replay, live hook integration, stability, installed-jar checks,
  and the required terrain-mod acceptance matrix remain unqualified until
  their independent gates pass.
- The logical NOISE/FULL/SAVED runner now supports bounded multi-case
  candidate endpoint replay; NOISE uses the isolated version-pinned live hook
  and commit path, while SAVED can perform a fresh-process reopen. It remains
  an operator script with startup overhead and no coordinator-owned durable
  checkpoint; replace it with a release harness after the required matrices
  are actually captured.
- The first NOISE live-verifier smoke exposed startup ordering: the requested
  live flag was visible to the mixin before the isolated provider was armed,
  so NeoForge spawn preparation failed closed. Keep requested-versus-armed
  state separate; startup now remains on vanilla until the verifier installs
  its provider, then the requested case takes the live path.
- Baseline rows now have a typed accumulator and deterministic JSON writer,
  but the real capture harness does not yet populate them. Add readiness and
  warmup barriers, source/jar/shader/driver identities, stage timers and
  durable per-run artifacts before treating a baseline as performance or
  release evidence; a serialized draft report must remain clearly distinct
  from a measured campaign.
- Debug properties and stage logs should be replaced by structured diagnostics
  with stable schemas, redacted failure text, and a bounded artifact-retention
  policy.
- The draft hook now records process-local decision and async-future counters,
  and status-json exposes the effective hook/config identity. The counters are
  intentionally not qualification evidence; replace them with a bounded,
  versioned diagnostics sink that joins coordinator, provider, native dispatch,
  commit, FULL and SAVED identities before release. The new
  `write-default-config` command is an explicit convenience writer only and
  does not hot-reload the running runtime; add a documented reload transaction
  or keep restart-only semantics deliberate.
- The direct Overworld density branch now has an exact draft escape path for
  captured normal-noise wrappers: its two Perlin calls are dispatched as
  separate GPU stages and recombined through integer binary64 carriers. This
  preserves device-side arithmetic but currently repacks readback values on
  the host between stages; replace it with device-resident buffers and a
  compiler-owned stage IR before release.
- Direct-branch planning uses a deliberately more aggressive 80-function,
  28 KB/24 KB source-envelope heuristic than the ordinary material planner.
  It was needed to keep the tested NVIDIA branch within a workable compiler
  envelope; thresholds are draft driver policy, not a portable performance
  claim. Add measured per-driver compilation limits and structured stage
  manifests before broadening support.
- The RTX 5070 Ti direct second-density branch completed a full
  98,304-block CPU-vs-GPU diagnostic with zero mismatches, including the
  previously failing depth point. The run intentionally stopped before
  material output and therefore is a branch diagnostic only. An integrated
  ordinary Overworld attempt using the split normal-noise experiment stalled
  during native compilation; after restoring the prior graph-stage default,
  a one-point stage-2 compile/dispatch completed and then stopped
  intentionally. No ordinary material receipt exists yet.
- Rejected GPU experiments are retained as draft evidence: broad direct
  function-control reached about 19 GiB resident during native compilation;
  an arithmetic-inline workaround crashed inside `nvgpucomp64.dll`; and the
  earlier unsplit direct branch mismatched most blocks. Do not present those
  attempts as failures of the exact integer arithmetic contract; they are
  compiler-envelope findings and need bounded crash-safe subprocess isolation
  in a release harness.
- The ordinary density coordinator now retains the previously compiling graph
  stage as its default for captured normal-noise leaves. The two-call Perlin
  fan-out remains an opt-in driver experiment because duplicating its integer
  helper closure stalled shader compilation. A one-point default stage-2
  diagnostic now compiled and dispatched before intentionally stopping; the
  ordinary material path still needs a full run. Replace the host-side
  intermediate readback with device-resident buffers and validate the full
  first branch before relying on this path.
- Density-plan sizing now caches immutable function slices and a table-to-user
  index during one planning pass. This prevents repeated substring/regex
  allocation from consuming tens of gigabytes while exploring the captured
  graph. Replace source-text reachability and heuristic size estimates with a
  compiler-owned graph/IR and measured per-driver limits before release.
- `tellurium.gpuCandidate.recreateDeviceInterval` is an opt-in containment
  switch for drivers that retain native shader compiler state across pipeline
  destruction. The interval-8 probe kept resident memory near 2.6--3 GiB but
  made the 410-stage ordinary run too slow and was stopped without a receipt.
  Keep this out of default runtime policy until device recreation, cached
  resource ownership and end-to-end timing are covered by a release harness.
- The latest ordinary fan-out probes (`...u`, `...v`, `...z` and `...aa`) were
  intentionally stopped or hit the draft planning envelope; they are retained
  as incomplete diagnostics, not pass/fail parity evidence. Remove ad-hoc
  debug switches and consolidate the useful findings into structured,
  bounded artifacts later.
- The normal-noise detector was widened to recognize a single captured
  `wg_noise_normal_*` call inside a simple return expression (for example a
  scaled leaf), while roots with staged density children still use the parent
  ABI. The corrected probe `build/gpu-overworld-normal-noise-probe-20260914c`
  entered the larger ordinary source-compilation traversal but was stopped
  before dispatch/parity output; keep the detector as draft work and add an
  explicit child-fed normal-noise stage form before treating it as complete.

## Fixed-call blended-noise fallback — 2026-09-14

The ordinary captured `wg_noise_blended_*` path now removes the
selector-driven multi-octave shader. It emits one compact fixed-call module
per captured octave (8 main, 16 min-limit and 16 max-limit samples), dispatches
those modules on the device, and combines their two-word FP64 carriers in a
separate device fan-in. The 40-octave fan-out remains device work; the host
only transports intermediate integer carriers between dispatches. This is a
correctness-first draft shape, not the final buffer topology or a throughput
claim.

The first static-group implementation exposed a draft-only carrier write bug:
successful fixed-call samples were computed but their output assignments were
inside the `wg_failed` branch, so a successful dispatch could feed zero or
stale words to the fan-in. The branch is now explicit: failed calls write
qNaN carriers and successful calls write every sample's two-word result. A
source-level regression covers the generated control-flow shape; the real
device oracle below is the end-to-end follow-up.

The target NVIDIA driver produced finite sample carriers for the fixed-call
source when arithmetic was left unrestricted (`NONE`), but returned signed
zero for the first octave at both the earlier interior probe
`(512,-63,512)` and the non-lattice probe `(513,-62,513)` when the
`wg_fp64_` no-inline boundary was applied. The default is therefore now
`blendedNoiseDontInlinePrefix=NONE`. The unrestricted 40-sample run still
stopped during the compiler-heavy sequence after early finite samples; no
ordinary GPU parity or material receipt exists.

The fallback now partitions the captured calls into static groups, defaulting
to two literal octave calls per shader (`blendedNoiseStaticGroupSize=2`, 20
groups total). The earlier bounded device probe with
`debugDensityBlendedNoiseGroupLimit=1` reached the first group dispatch on the
RTX 5070 Ti; its intentional-stop record is
`build/gpu-blended-noise-group-probe-20260914d.log`. This proves the smaller
group ABI reaches the device, not that the full fan-out is numerically correct.
The group-size property and group-limit diagnostic should be removed or
replaced by structured policy once a qualified ordinary route exists. Group
shader compilation is now lazy, so bounded probes do not compile discarded
groups before the first dispatch; this is a compiler-memory safeguard, not a
throughput claim.

Static-group dispatches now fence and clear cached Vulkan pipelines every two
groups by default through `blendedNoisePipelineReclaimInterval`. The optional
`blendedNoiseDeviceRecreateInterval` resets the device generation after a full
group interval when a development driver retains compiler state beyond
pipeline destruction. Both controls are draft containment measures; replace
them with structured runtime resource policy before release and do not treat
their presence as throughput evidence.

After the carrier-write fix, the one-group probe was repeated with the same
single Overworld point. The post-fix stage record is
`neoforge-1211/build/run/gpu-blended-noise-group-probe-fixed-20260914/stages.log`:
it reached `density-blended-group-complete group=main` and then stopped at the
explicit one-group boundary. The sidecar remains `FAIL` by design because the
probe intentionally does not run fan-in or parity. The source regression and
this device reachability result establish the corrected small-group path, but
the ordinary full fan-out remains unqualified.

Two longer experiments are retained as incomplete draft evidence. The default
two-call run dispatched 8 of 20 groups before being stopped during the
compiler-heavy sequence, with no candidate receipt. The explicit
`blendedNoiseStaticGroupSize=8` run stopped while compiling its first 8-call
group, before dispatch. Their run roots are
`neoforge-1211/build/run/gpu-blended-noise-oracle-fixed-20260914` and
`neoforge-1211/build/run/gpu-blended-noise-oracle-fixed8-20260914`; neither is
a parity result. Keep the lazy grouping and explicit bounded diagnostics for
the draft, then replace them with structured driver policy and a qualified
fan-out/fan-in route.

The next draft pass statically specializes the packed captured-noise sampler
once per retained permutation table. Each static group now calls table-specific
sampler functions; no permutation array or table-ID switch crosses the driver
boundary. On seed `0`, Overworld chunk `(32,32)`, probe `(512,-56,512)`, the
RTX 5070 Ti completed all 20 two-call groups and the device fan-in with finite
carriers. The same-stack monolithic comparison still returned `1.0` while the
staged result was `-0.06762143074968009`, so this is a useful compiler/runtime
diagnostic but not parity evidence or a candidate receipt. The retained run
root and stage record are
`neoforge-1211/build/run/gpu-sampler-specialized-oracle-20260914` and
`neoforge-1211/build/gpu-sampler-specialized-oracle-20260914.log`.

The diagnostic now also has a CPU blended-noise oracle after fan-in. The
compiler carries an immutable function-name-to-captured-parameter map, and the
runtime resolves the exact emitted function rather than selecting the first
captured node. The mapping has a focused compiler contract test. Keep this
oracle and the static sampler as draft instrumentation until the staged,
monolithic and CPU results agree across a real corpus.

The corrected one-point follow-up completed all 40 fixed-call samples and the
device fan-in at seed `0`, Overworld chunk `(32,32)`, point `(512,-56,512)`.
Every sample matched the independent CPU octave oracle exactly, and the staged
blended result matched the CPU result exactly at
`-0.2805788967238936`. The compact same-stack diagnostic still returned
`1.0`, so the diagnostic intentionally failed closed and wrote no candidate
receipt. This is the first end-to-end numerical confirmation of the staged
captured blended-noise route, but it is still one point, took 28m33s, and does
not close the ordinary GPU/material gate. The retained run root and stage log
are `neoforge-1211/build/run/gpu-overworld-blended-oracle-fixed2-20260914` and
its `stages.log`.

One ordinary Overworld candidate attempt was then started with the same point,
`batchElements=1`, and no diagnostic early-stop. It reached the captured
child-fed density plan and remained in native compilation at stage index `2`
(`DIRECT_NORMAL_NOISE`) for roughly three minutes before being externally stopped
to avoid another long compiler stall. It produced no candidate artifact or
receipt, so this is incomplete diagnostic work rather than a GPU failure or a
qualification result. The retained run root is
`neoforge-1211/build/run/gpu-overworld-ordinary-draft-20260914`.

The candidate-local packed-sampler and bounded-divider rewrites are now
actually forwarded through the Gradle `runServer` boundary. Earlier probes
that attempted to toggle those properties without that forwarding are invalid
for choosing a production policy and are retained only as historical
diagnostics. `debugDensityBlendedNoiseSampleLimit` is a temporary prefix trace;
remove it and the related ad-hoc source dumps after the compiler/runtime owns
structured stage diagnostics.

Staged density interpolation now reads each captured FP64 interpolation
boundary's X/Y/Z cell sizes and uses that same geometry for host corner packing,
fraction carriers and device corner lookup. The exact draft fraction table is
still limited to cell sizes 2, 4 and 8; a different captured size fails closed
until the integer fraction helper is extended. This removes the old silent
4/8/4 assumption for admitted captures, but it does not resolve the separate
Overworld material-stage numerical mismatch.

The 2026-09-14 consolidated Java 21 `test build` completed successfully with
422 tests, zero failures/errors/skips, architecture and release-jar checks
passing, and a 664-entry source bundle containing no build or Git state. The
release artifact hashes are recorded in `docs/STATUS.md`; the source archive
hash is intentionally kept out of the archive to avoid self-referential
identity. The remaining clean-up is not a build blocker: the ordinary
Overworld GPU fan-out/fan-in, full matrix,
live hook, terrain-mod GPU matrix, installed external server and final G12
qualification still require real evidence.

The first Nether one-branch candidate attempt exposed a missing executor shape,
which is now fixed: one-branch roots use the recursive staged density planner
and shared aquifer/ore/material consumers. The temporary dense fallback was
also tested once on `gpu-nether-dense-20260914`; it reached server readiness
but grew to roughly 11.4 GB in native compilation with no result, so it was
removed from the route. Two follow-up staged attempts were retained:
`gpu-nether-staged-20260914` used the default two-octave groups and stopped
after the compiler-heavy fan-out, while `gpu-nether-staged8-20260914` used the
explicit eight-octave grouping and stopped in its first roughly 58 KB module.
Neither produced a candidate artifact or receipt. This is a real compiler
envelope boundary, not a GPU parity result; see
`docs/evidence/v0.2-gpu-candidate-nether.md`.

## GPU draft continuation — 2026-09-20

The prototype now has a real device-executed `GPU_NATIVE_DRAFT` route for the
captured Nether graph. It rewrites the integer binary64 carrier helpers to
native GLSL `float`/`double` operations and is deliberately not a qualified
`GPU_IEEE_BITS` route: the RTX 5070 Ti reports `preserveDenorm64=false`.
The route nevertheless completed the captured Nether density, aquifer, ore
and material path on the GPU for seed `0`, chunk `(32,32)`, with
`65,536/65,536` blocks and `0` CPU mismatches. The receipt is
`build/gpu-native-nether-20260920i.chunk.gpu-receipt.json` and is labeled
`DRAFT_PARITY_PASS`, not `PASS`.

The native-draft material path currently uses a targeted raw sign-bit
classifier and a registry-wide state-ID allow-list to get the prototype
through the real captured Nether graph. Those are temporary draft shims, not
the final ABI or admission policy. The optional missing-ore-emitter fallback,
broadened source-name matching, and stage-source diagnostics are likewise
developer conveniences that need explicit semantic contracts or removal.

The first exact `GPU_IEEE_BITS` Nether retry was bounded after it stalled in
`vkCreateComputePipelines` during the first two-octave blended-noise stage.
The follow-up one-octave route completed all 40 fan-out groups, exact fan-in,
density and material, then passed `65,536/65,536` blocks with zero CPU
mismatches. A native-draft Overworld attempt traversed roughly 2,711
functions and failed closed at the draft planner's 2,048-stage limit
(`wg_spline_217`), with no artifact. Exact Nether generation is now a working
one-case prototype, but Overworld material generation and the
FULL/SAVED/live/terrain-mod matrices remain open.

Cleanup items from this fast prototype pass:

- Replace `NativeDraftMath` with a qualified integer-carrier implementation,
  or explicitly qualify a separate native profile with denormal and rounding
  capability evidence; never admit the draft profile as `GPU_IEEE_BITS`.
- Remove or formally specify the raw sign-bit classifier, registry-wide state
  allow-list, optional ore fallback and broadened regex/source parsing.
- Replace source-level stage splitting with a compiler-owned graph/IR and
  solve the Overworld 2,048-stage planner ceiling without increasing it
  blindly.
- The exact route now defaults to one octave per shader; replace this slow
  containment policy with a measured compiled-cache/resource strategy after
  multi-context exact coverage is proven.
- Remove ad-hoc stage dumps/debug properties and retain only structured,
  bounded diagnostics and representative receipts. Old `build/` run roots are
  regenerable evidence and should be pruned only after the retained hashes
  and logs are archived.

## Verification policy for this draft

Keep synthetic replay, CPU oracle parity, native device conformance, GPU
candidate generation, FULL throughput, SAVED reopen, live generation, and
terrain-mod compatibility as separate claims. A faster draft checkpoint may
combine compilation checks, but it must not turn an unrun acceptance gate into
success.

## GPU compiler-containment continuation — 2026-09-20

The direct-density executor now bundles independent graph stages that share a
coordinate domain. A bounded Overworld probe on the RTX 5070 Ti executed
bundled stages `0..2` and `5..6`, then stopped intentionally at stage `5`.
This is compiler/driver reachability evidence only; it produced no candidate
artifact or parity receipt.

The follow-up native-draft Overworld attempt reached a 2,204-stage direct
branch after raising the diagnostic planner limit to `4096`, but its full
candidate run stalled in driver pipeline construction at a later graph stage
and was stopped without an artifact. A one-element stage-198 probe was then
used to isolate compiler pressure. It exposed repeated large-capture parsing
and repeated shaderc work, so the draft now caches identity-stable large
capture function/call indexes and keys the runtime shaderc cache by semantic
source/profile/policy rather than an otherwise-unique stage label. These are
containment improvements, not a GPU parity result.

The stage-stop diagnostic now tolerates small interpolation child plans when a
later direct-root stage is requested, and `directStageProbeElements` is a
temporary bounded probe property. Remove both diagnostic conveniences or
replace them with structured stage selection before release. The exact
Overworld `GPU_IEEE_BITS` material route, multi-context GPU qualification,
FULL/SAVED/live/terrain-mod gates remain open.

## GPU draft continuation — 2026-09-21

The fast prototype added a real device-side Beardifier stage and wired it into
the staged Overworld material path. It has not been qualified: the recent
runs were bounded compiler/runtime diagnostics and produced no candidate
artifact or parity receipt.

The remaining normal-noise mismatch is now isolated more narrowly. The native
draft's ordinary `wg_node_1435` path produced an implausible intermediate,
while the exact `wg_node_2226` profile remained numerically sane. The current
containment route therefore keeps the normal-noise coordinate preparation and
the final combine on the native device path, but evaluates the captured
Perlin octave modules with exact compiler output. Octaves are sent in
two-octave GPU modules, with device results copied between modules. This is a
GPU experiment, not CPU fallback and not yet a qualified mixed numeric ABI.

The focused probe accepted the coordinate module (13,234 characters), then
reached the second two-octave module after compiling/dispatching the first;
the two-octave source was about 37.2 KB and resident process memory stayed
below roughly 5 GiB. The full 16-octave normal-noise root and both normal-noise
branches were intentionally stopped before completion because the current
host-side fan-out is still too slow for a useful full-chunk iteration. Earlier
monolithic exact-normal and larger bundled attempts stalled or grew to unsafe
resource levels. None of these runs closed the Overworld GPU gate.

Cleanup items from this continuation:

- Keep intermediate octave and Beardifier values device-resident; the current
  host round trips are only a draft containment mechanism.
- Replace source-string octave extraction and module splitting with
  compiler-owned graph/IR boundaries and explicit numeric-domain contracts.
- Measure group sizes, pipeline cache behavior and driver limits per device;
  the exact one/two-octave and native-draft four-octave defaults are
  conservative probe settings, not performance targets.
- Qualify the exact/native mixed route against the independent same-stack
  oracle across contexts before allowing it near production admission.
- Remove the Beardifier/raw-kernel dump, debug-root properties, temporary
  source-size limits and diagnostic early-stop paths once structured tooling
  exists.
- Re-run the complete Overworld density, aquifer, ore, material, FULL, SAVED,
  terrain-mod and live matrices after the route is stable; do not infer any
  of those gates from the partial probes above.

## Device-resident staged dispatch continuation — 2026-09-21

The Vulkan executor now supports a checked raw-stage chain. It allocates one
input buffer and one intermediate output buffer per stage, records all stages
in one command buffer with shader-write/read barriers, and reads back only the
final buffer. Stage geometry, numeric profile, DontInline policy, pipeline
flags and the aggregate shader/SPIR-V provenance are validated. The captured
Perlin fan-out uses this chain. The multi-step integer FP64 divider can use it
through the explicit `tellurium.gpuCandidate.deviceResidentFp64DivisionChain=true`
experiment, but remains host-mediated by default after the target NVIDIA
driver crashed at queue submission for the long 29-dispatch divider topology.

The opt-in `deviceResidentDensityScratch=true` path now extends the same
mechanism to bounded ordinary GRAPH segments. A fixed row carries coordinates
and stable child slots; each stage copies the row and writes only its own
carrier, so parent stages no longer require Java-side child repacking inside
that segment. Interpolation, blended-noise fan-out, normal-noise special forms,
and the divider intentionally remain outside this first ABI.

The one-point Overworld probe reached both direct normal-noise branches through
the chained route on the RTX 5070 Ti. It returned finite device values and
closed through the existing diagnostic exception, not a Vulkan chain failure.
This is resource/dispatch evidence only: the route still has no full
Overworld candidate artifact, CPU parity receipt or material qualification.

Cleanup items:

- Replace the draft dependency-row ABI with a compiler-owned scratch manifest
  so parent stages can read child slots without Java-side repacking across
  interpolation and specialized-stage boundaries; the current chain is
  limited to bounded linear GRAPH segments.
- Keep all intermediate buffers device-local when the allocator can prove
  shader visibility; the current executor uses host-visible coherent storage
  for the conservative MVP.
- Add native chain fault injection, descriptor/aliasing coverage and a real
  asynchronous completion path before treating the chain as P05 production
  service functionality.
- Add a compiler-owned dependency-row manifest, slot lifetime/alias analysis,
  and row-field preservation tests. The current max-16 segment and
  host-visible coherent buffers are draft bounds, not a release allocator.

## Resident material-row prototype — 2026-09-21

The post-density path now has an opt-in fixed-stride resident row containing
coordinates, density, aquifer carriers, ore carriers, and final state/fluid
fields. Beardifier, aquifer, ore and material stages can copy/update that row
through the raw-chain executor, leaving one final readback instead of a Java
repack between each material stage.

The default remains the previously exercised host-mediated material route. The
opt-in exact Nether probe now completed the resident aquifer/material chain and
produced `build/gpu-resident-material-probe-20260921c.chunk` with
`65,536/65,536` final blocks and zero CPU mismatches. This is one real case,
not resident-material qualification. The earlier run with the long divider
chain still crashed in `nvoglv64.dll` at `vkQueueSubmit` before reaching the
material row, so that divider topology remains disabled by default.

Cleanup items:

- Run a small same-stack resident-material smoke that reaches the aquifer,
  ore, and final row stages without paying the complete 40-octave capture.
- Add a descriptor/row-layout test that validates every stage preserves fields
  it does not own and rejects output rows with invalid state/mark carriers;
  the current Nether receipt is runtime evidence, not this missing contract
  test.
- Promote the resident route only after exact CPU parity and a full saved-world
  witness; until then keep `deviceResidentMaterial` disabled by default.

## Density scratch failure and Overworld material boundary — 2026-09-21

The opt-in `deviceResidentDensityScratch=true` dependency-row experiment was
bounded to sixteen stages and did reach real RTX 5070 Ti dispatches through
Overworld stage 3. It is diagnostic reachability only. When combined with the
resident material row on the exact Nether case, it produced `11,634/65,536`
mismatches; the first observed mismatch was `(526,22,527)`, where CPU lava was
returned as GPU netherrack. The route remains disabled by default and is not a
qualification result.

The resident child wrapper now reuses the validated `wg_stage_density_value`
row lookup instead of duplicating raw SSBO indexing. This is a source-level
ABI correction based on the resident scratch diagnosis; it has passed Java
compile/unit checks but still needs the exact Nether bad-point replay and a
fresh Overworld resident-density matrix before the route can be trusted.

The bad Nether point was isolated with density parity enabled. The host route
returned the CPU carrier `-0.0019387048125086136`, while the scratch route
returned `0.4583333333333333`. The first observed divergence was the ordinary
interpolation child `wg_node_8`: host `0.75`, scratch `0.0` (its two child
values were `0.0` and `0.75`). Submitting one stage per command, using fixed
offset row reads, and enabling the existing pipeline optimization probe did
not correct it. This is evidence of a draft row/dependency ABI or driver
interaction bug, not evidence that the exact GPU arithmetic is wrong.

An exact Overworld run with host-mediated density and the resident material
row was also started with a 4,096-stage planning bound. It reached real GPU
staged execution at stage 79 (`wg_spline_1455`, 11 children) but remained in
driver pipeline construction and was intentionally stopped. It produced no
candidate artifact or receipt. The full Overworld material gate therefore
remains open.

Cleanup before promotion: replace source-assembled row dependencies with a
compiler-owned manifest and slot-lifetime analysis, add field-preservation and
driver-matrix tests, and fail fast on a measured stage/compiler budget. Keep
the scratch and resident-density switches off by default until those checks
and a complete exact Overworld artifact pass.

## Corrected normal-noise wrapper and driver envelope — 2026-09-22

The second transformed-coordinate input for the staged normal-noise wrapper is
now emitted and dispatched separately; a focused exact probe and a targeted
direct-branch point agree with the CPU values. The full Overworld attempt still
does not qualify: exact-inline compilation stalled, the contained two-octave
route crashed in `vkUpdateDescriptorSets`, and the single-octave retry crashed
at the same native NVIDIA descriptor-update boundary. No artifact or receipt
was written.

Cleanup items:

- Replace the temporary source-string normal-noise split with compiler-owned
  graph boundaries that explicitly carry both transformed sample coordinates.
- Keep `normalNoisePerlinGroupSize=2` for both routes. The native-draft route
  experimentally used `4` after a bounded one-element embedded-root smoke
  compiled/dispatched the grouped path and returned the same finite diagnostic
  value as the two-octave route, but the complete replay later stalled in the
  driver/compiler envelope. The source default is therefore back to `2`; group
  four remains an explicit containment experiment and the native draft remains
  unqualified.
- Replace per-leaf `reclaimPipelines()` and interval device recreation with a
  tested descriptor-pool/resource-lifetime policy; these are containment
  measures, not a production Vulkan allocator.
- Add a native fault-injection/validation harness around raw-chain descriptor
  updates and preserve the `hs_err` traces in a device qualification matrix.
- Keep branch-only diagnostics fail-closed behind `debugDensityParity=true` and
  remove temporary debug properties once structured stage tracing exists.
- Re-run exact Overworld density, material, FULL, SAVED, terrain-mod and live
  matrices only after the driver-safe chain is stable; do not promote the
  corrected targeted probes to those gates.

## Post-review native-draft grouping and full replay — 2026-09-22

The native-draft embedded `wg_node_2139` diagnostic was repeated with
`normalNoisePerlinGroupSize=4`. The coordinate, two grouped Perlin and combine
stages compiled and dispatched on the RTX 5070 Ti and returned the finite
diagnostic `0.10744060995659291` at the native-draft probe point. This remains
diagnostic-only and is not CPU parity evidence.

The subsequent full host-staged Overworld replay used the corrected direct
combine expression and finite child-carrier guard, but still remained inside
the native compiler/device-work envelope for roughly ten minutes. It was
cleanly stopped without parity output, a candidate artifact, or a receipt.
Record this as a bounded post-fix compile-envelope result, not as a failed
parity comparison or a passed GPU gate.

The Vulkan executor now keeps a separate bounded cache for reusable staged
normal-noise Perlin/combine pipelines across ordinary graph-stage reclamation,
while one-off graph pipelines retain the eight-entry LRU. Device recreation
and executor close clear both caches. The cache is a draft containment/perf
change and still needs a full Overworld artifact plus cross-driver resource
qualification.

A second full host-staged retry was intended to use the new native-draft
group-4 default and the reusable pipeline cache, but the Gradle harness was
still injecting its legacy `normalNoisePerlinGroupSize=2` default. It again
remained inside native compilation and device work in the bounded window and
was cleanly stopped without parity, artifact, or receipt output. The harness
forwarding was corrected afterward so omitted values now reach the profile-
aware Java defaults; this run therefore confirms only the older group-2
compile envelope, not that group four solves the full graph.

A bounded stage-50 native-draft probe with graph bundling enabled
(`densityStageBundleMaxSourceChars=100000`, group-4 Perlin, recreation every
64 stages) was also stopped after several minutes while compiling the early
captured-noise frontier. It produced no parity or artifact result. Bundling is
therefore retained as an explicit experiment; it is not promoted to the
native-draft default.

## Host-staged normal-noise fallback — 2026-09-22

The default normal-noise path now keeps the Perlin arithmetic on Vulkan while
using ordinary bounded dispatches and host-visible intermediate carriers. The
device-resident raw chain remains opt-in because the target driver crashed in
`vkUpdateDescriptorSets`. The exact-inline host fallback passed the corrected
one-point probe and reached roughly stage 196/410 in the full route without a
native crash, but later Perlin pipeline compilation still stalled. Enabling
Vulkan pipeline optimization did not cross that boundary.

Cleanup items:

- Replace host-visible intermediate copies with a compiler-owned resource plan
  after descriptor lifetime and aliasing are proven on the target driver.
- Add a deterministic normal-noise numeric-policy matrix: exact inline is the
  current passing policy; broad `wg_noise_,wg_fp64_` DontInline is rejected by
  the one-point value check; narrower policies remain unqualified.
- Add a measured per-pipeline compile budget and a diagnostic timeout/report so
  a captured graph fails closed instead of holding a worker and multi-gigabyte
  native compiler state indefinitely.
- Reduce the number of unique captured Perlin pipeline sources through an IR
  specialization/cache design; source-string extraction is only a prototype.
- Keep `normalNoiseDeviceResidentChain=false` and all GPU/live hooks fail-closed
  until a complete exact Overworld material artifact and FULL/SAVED witness
  exist.

## Shared metadata-backed Perlin route and split-carrier parity failure — 2026-09-22

The former normal-noise experiment used one bounded shared shader per
captured request. Each row carried ten sample-input words plus a one-word
header and sixteen fixed metadata slots of 77 words each: active flag,
frequency/value factors, amplitude, offsets and the packed 64-word permutation
table. The route crossed all 410 planned Overworld density stages and reached
the material-input boundary without the earlier native descriptor-update
crash. It is still a draft: the full run failed CPU parity in the split
density carriers before final material output, with no artifact or receipt.

Cleanup items:

- Replace source parsing and the fixed 16-level/77-word metadata ABI with a
  compiler-owned graph and metadata manifest; validate level counts, table
  packing and row bounds before dispatch.
- If the route is revisited, cache one shared shader/metadata layout per
  capture and remove duplicated host-visible metadata uploads only after
  descriptor lifetime and aliasing are proven on the target driver.
- Isolate and fix the underlying split-carrier GPU divergence before debugging
  final material, FULL or SAVED behavior. The current corner-row diagnostic
  already differs before final material input; it is not a Beardifier-only
  issue.
- Replace fixed-coordinate debug helpers and free-form stage logs with bounded
  structured diagnostics, then remove the draft-only probes from the shipping
  path.
- Keep the full route, production hook and all GPU/FULL/SAVED claims
  fail-closed until exact Overworld parity produces an artifact and receipt.

The graph-only no-inline experiment (`densityDontInlinePrefix=wg_node_,wg_spline_`)
was also bounded and stopped at stage 28 around 4.5 GiB during native pipeline
compilation. Keep the broader containment policy as the current draft default
until a compiler-owned numeric-policy matrix can distinguish correctness from
compile-envelope behavior; the host-staged route is the current default.

The follow-up `debugDensityNode=wg_node_45` probe retained 2,290 functions and
reached about 6.9 GiB before native pipeline creation, then was stopped. Keep
node-level monolithic diagnostics out of normal development runs; add a
compiler-owned subgraph extractor or bounded stage oracle before attempting
this isolation again.

## Captured-Perlin compiler-envelope experiment — 2026-09-22

The dynamic metadata/table Perlin shader was replaced for the opt-in route by
a static captured-Perlin dispatch: the compiler-emitted Perlin function and
its immutable tables remain in the shader while coordinates remain GPU input.
On the target driver, the Overworld root still grew native compiler memory into
the multi-gigabyte range; the broad no-inline retry reached `VK_ERROR_DEVICE_LOST`.
No artifact or receipt was produced. The default is host-staged again.

Cleanup items:

- Keep the older unrolled metadata generator only as the explicit
  `normalNoiseSharedGenericShader=false` fallback; remove both source-string
  routes after a compiler-owned Perlin specialization/cache boundary exists.
- Add a bounded native shader compile timeout and memory/driver-loss receipt so
  an opt-in experiment cannot occupy the worker indefinitely.
- Keep `normalNoiseSharedShader=false` and all production/live routes
  fail-closed until a multi-context exact Overworld GPU artifact exists.

## Embedded normal-noise parent GPU containment — 2026-09-22

Captured selector/rarity parents whose return expression embeds a captured
`wg_noise_normal_*` call now use a bounded split prototype instead of the
target driver's faulty monolithic normal-noise module. The prototype emits a
GPU coordinate stage, two GPU Perlin carrier paths, and a GPU parent-combine
stage; the host only mediates the bounded carrier handoff between dispatches.

On the RTX 5070 Ti, a fail-closed one-point probe for `wg_node_2139` compiled
and dispatched all of those stages, returning the finite device carrier
`0.3602672866443762` with zeroed child inputs. A one-element first-branch
replay with real upstream GPU child carriers reached stage 43, executed the
embedded parent, and returned `0.25578899322829773` at `(512,-64,512)` before
the intentional diagnostic stop. These are compiler/dispatch containment
evidence only: neither run performed embedded-parent CPU parity, produced a
candidate artifact or receipt, or exercised material, FULL, SAVED or the live
hook. Removing the stop re-entered the broader recursive graph and its
multi-gigabyte native compile envelope, so that route was stopped without a
result.

Cleanup items:

- Replace source-string recognition with compiler-owned IR boundaries and
  explicit embedded-normal-noise metadata.
- Add CPU parity at the embedded parent and branch root, not just finite
  carrier checks, before counting this route as GPU correctness evidence.
- Add a bounded full-graph compile budget, timeout and structured failure
  receipt; do not let recursive replay consume an unbounded native compiler
  envelope.
- Cache/reuse coordinate, Perlin and combine modules across embedded roots;
  host-mediated carrier copies remain prototype containment, not a production
  resource policy.
- Keep the split route as the draft default only while it is fail-closed and
  unqualified; retain the monolithic route as an explicit experiment.

## Astra review fixes — 2026-09-22

The final read-only GPT-6 Astra review found two prototype correctness risks.
The direct normal-noise combine stage was returning the captured noise wrapper
directly, which could drop an outer captured scale/multiply. It now retains and
replays the original density root expression. The shared carrier lookup now
marks `wg_failed` and returns a NaN carrier when an input row is non-finite, so
an invalid selector/rarity child cannot silently choose a finite fallback.

Post-fix smoke evidence on the RTX 5070 Ti compiled and dispatched the direct
`wg_node_2130` route, returning the finite one-element diagnostic value
`-0.3256693649400241`. The embedded `wg_node_2139` route also compiled and
dispatched with the new carrier guard, returning the finite zero-child probe
`0.10744060995659291` before its intentional diagnostic stop. These probes use
the native-draft smoke profile and are not parity or qualification evidence;
the earlier exact-profile zero-child value remains recorded in the evidence
section above. The full graph, artifact, material, FULL, SAVED and live gates
remain open.

## Native-draft blended-noise group-4 probe — 2026-09-22

The captured 40-octave blended-noise fallback now selects four literal calls
per static shader by default for `GPU_NATIVE_DRAFT`; `GPU_IEEE_BITS` retains
the one-call default. The native-draft probe used
`blendedNoiseStaticGroupSize=4`, `debugDensityBlendedNoiseGroupLimit=1`, a
4,096-element batch and no device recreation. The first configured group ran
through the real RTX 5070 Ti worker and the diagnostic then failed closed at
the intentional `1 of 10 groups` boundary. Its retained sidecar is
`neoforge-1211/build/gpu-overworld-blended-group4-native-draft-20260922.chunk.candidate-status`.

This is only a grouped compiler/dispatch smoke: it did not run the remaining
fan-out or device fan-in, did not compare against the CPU or compact oracle,
and produced no candidate artifact or receipt. The native-draft default is
therefore a bounded prototype containment policy, not a correctness or
throughput claim.

After the Gradle forwarding fix, an omitted-property launch with the master
`candidate.capture` gate disabled was also checked. It only bootstrapped the
server and did not enter the GPU worker; it produced no status or artifact.
The Java command line confirmed that omitted group properties are no longer
injected by Gradle, but this bootstrap adds no GPU evidence.

Cleanup items:

- Add a full grouped fan-out/fan-in CPU and same-stack device oracle for the
  native-draft profile before treating group four as numerically qualified.
- Replace the profile/property switch with a measured device policy once
  compiler budgets and pipeline lifetime are owned by the runtime.
- Keep exact IEEE at one-call groups until a separate exact grouped route
  passes the complete oracle and Overworld material gates.

## GPU capture-gate correction — 2026-09-22

One launch initially used `gpuCandidate.capture=true` with the master
`candidate.capture=false`, so it only bootstrapped NeoForge and was stopped
without entering GPU execution. The capture code requires both flags: the
master switch enables candidate capture and the GPU switch selects the GPU
branch.

The actual corrected full run used both flags, omitted both group-size
properties, and therefore selected native-draft group four for Perlin and
blended noise. It reached real native GPU compilation, oscillated between
roughly 5.4 and 7.3 GiB resident memory, and was cleanly stopped after the
bounded window without a status sidecar, candidate artifact, parity output or
receipt. The retained run root is
`neoforge-1211/build/run/gpu-overworld-full-native-default4-actual-20260922`.

This confirms the profile-aware default is wired through the real GPU worker,
but does not improve the full-graph compiler envelope. Keep the native-draft
group-four defaults as a prototype experiment only; the complete Overworld
GPU/FULL/SAVED/live gates remain open.

The follow-up embedded-root smoke used only
`tellurium.gpuCandidate.capture=true` (no master capture flag and no
explicit Perlin group size). It reached the real GPU coordinate, grouped
Perlin and combine stages and returned `0.10744060995659291` before the
intentional diagnostic stop. Its expected failure sidecar is
`neoforge-1211/build/gpu-overworld-embedded-normal-root-gpu-flag-only-20260922.chunk.candidate-status`.

## Interpolation-child staging containment — 2026-09-22

The native-draft full path now applies the aggressive small-module envelope
only to the top-level density route. Interpolation children default to the
wider `75k` source / `200` reachable-function envelope, with an explicit
`tellurium.gpuCandidate.interpolationAggressiveDensityStaging=true`
override for compiler experiments. The bounded child probe changed the
captured `wg_node_5` plan from the earlier `2,204` stages to `394` stages over
`1,225` corner coordinates; the child completed real GPU stages before the
intentional stop.

The corrected native-draft full replay used group-four Perlin modules and
entered that `394`-stage child, completing the first seven graph stages before
returning to a large normal-noise compiler module. It was stopped without
parity, material output, an artifact or a receipt. This is a real reduction in
graph fragmentation, not a qualification result.

Cleanup items:

- Replace source-size/reachable-function heuristics with compiler-owned graph
  budgets and a measured driver policy.
- Add a bounded full-route watchdog and a structured terminal compiler-envelope
  receipt instead of relying on external stopping.
- Re-run complete density, material, FULL, SAVED and live matrices only after
  the normal-noise boundary has a driver-safe implementation.

## Paired normal-noise module experiment — 2026-09-22

An opt-in `normalNoisePairShader=true` experiment combined the two transformed
Perlin roots of one leaf into a four-carrier GPU module. The one-element smoke
reached the real compiler with a `38,526`-character source and allocated about
`5.8 GiB` in the observed process, but did not reach a dispatch in the bounded
window. The option is therefore retained only as an explicit experiment and
the split host-staged path remains the default.

Cleanup items:

- Remove the temporary pair emitter or replace it with a smaller data-driven
  ABI after an independent carrier/oracle design is available.
- Do not claim that fewer logical modules means lower driver cost; this probe
  demonstrated the opposite on the current RTX driver.

## Native normal-noise inlining experiment — 2026-09-22

The split native-draft normal-noise smoke was repeated with
`normalNoiseDontInlinePrefix=NONE`. It reached dispatch quickly, but the same
`wg_node_2130` diagnostic changed from the guarded value
`-0.3256693649400241` to `-0.11476844329468142`. The faster compiler path is
therefore not a correctness-preserving replacement. Keep the native
explicit `wg_noise_,wg_fp64_` probe until an independent carrier/oracle
explains the difference; do not apply it as a route-wide default.

## Route-wide DontInline default rejection — 2026-09-22

An attempted fix keyed the default `wg_noise_,wg_fp64_` DontInline policy off
the native-draft route flag. The one-element embedded-root smoke then returned
`1.0075162080899902E43` instead of the previous finite `0.6587498722627554`.
The change was reverted. The policy remains shader-profile/explicit-property
controlled until direct and embedded roots have separate, parity-backed
boundaries.

## Native default-group smoke caveat — 2026-09-22

The final embedded smoke omitted an explicit `normalNoisePerlinGroupSize`
override and returned the finite value `0.6587498722627554` after real GPU
dispatch. The selected capture-local root contained one Perlin sample per
side, however, so this is finite-smoke evidence only and does not prove the
native group-of-four default.

Cleanup items:

- Replace fixed `wg_node_*` debug IDs with semantic root selectors or a frozen
  source manifest before using grouped-root probes as regression fixtures.
- Add a deliberately multi-sample captured root to the default-group test and
  record the selected group count in the receipt.

## Full native-draft GPU route reached material parity gate — 2026-09-22

The explicit group-two host-staged route was allowed to run to completion with
`nativeDraft=true`, `normalNoisePerlinGroupSize=2`,
`recreateDeviceBetweenNormalNoiseHalves=true`,
`recreateDeviceInterval=8`, aggressive top-level density staging, and the wide
interpolation-child envelope. The captured `wg_node_5` child completed all
`394/394` stages over `1,225` corner coordinates. The surrounding route then
completed its final root and ran the GPU aquifer, ore, and material stages for
all `98,304` storage blocks. The final stage log reports a finite final-root
sample of `0.1171875` with probe `0.2853890218064652`.

The capture failed closed at the independent CPU comparison, not during GPU
execution: `121/98,304` storage blocks differed. Every reported mismatch was
`minecraft:stone -> minecraft:water[level=0]`; the first was
`(516,45,512)`, followed by the `(512..517,46,512..513)` cluster. The run
took `1h 4m 52s` and left the expected failure sidecar
`neoforge-1211/build/gpu-overworld-native-recreate-g2-20260922.chunk.candidate-status`
plus the full trace
`neoforge-1211/build/gpu-overworld-native-recreate-g2-20260922.stages.log`.
No candidate artifact, receipt, FULL result, SAVED result, or live-generation
qualification was written.

This is the first complete real-GPU Overworld route in the current prototype,
but it is a parity failure. The native-draft default Perlin group size is now
`2` because group four previously stalled in the driver/compiler envelope;
group four remains an explicit experiment. The device-recreation flag remains
opt-in because it is a resource-containment mechanism, not a correctness
proof.

Cleanup items:

- Add a failure artifact that retains density, aquifer, ore, and final-material
  carriers at the first mismatching coordinates so the next diagnosis does
  not require another full-hour replay.
- Run a targeted mismatch-point comparison with density parity enabled to
  separate a final-density divergence from an aquifer/material divergence;
  the current full run did not enable that diagnostic.
- Compare the CPU and GPU nearest-aquifer status/pressure inputs at the water
  cluster, especially global-water versus pressure-rejected/default-block
  handling, before attempting another full route.
- Do not promote this run to G6/GPU, FULL, SAVED, terrain-mod, or live-hook
  evidence until the exact `98,304`-block comparison and receipt succeed.

## First material mismatch traced to native-draft density — 2026-09-22

A CPU-only probe at the first full-route mismatch `(516,45,512)` reported
final density `0.08588803299314993` and direct branch `64.0`. A one-point
native-draft GPU replay reached the material boundary and failed its density
parity check with GPU density `-4.785197563648143E-4`; the raw carriers were
CPU `0x3fb5fcc214d2c6ee` versus GPU `0xbf3f5c3ab43e5ff9`. This proves the
water cluster begins in native-draft density arithmetic, before aquifer
selection, rather than being only an aquifer status-table mismatch.

An exact `GPU_IEEE_BITS` one-point retry at the same coordinate remained in
native compilation for about thirteen minutes without a stage result and was
stopped cleanly. It produced no status sidecar or artifact. The exact route
therefore remains unqualified and has a separate driver/compiler-envelope
blocker from the native-draft numeric divergence.

Cleanup items:

- Compare native and integer-carrier stage outputs at the first divergent
  density node; do not promote hardware arithmetic based on finite output
  alone.
- Add a targeted per-stage CPU/GPU carrier dump so this diagnosis does not
  require compiling the whole route repeatedly.
- Keep `GPU_NATIVE_DRAFT` containment-only and the live hook disabled until
  the exact density carrier reaches CPU parity.

## Native interpolation carrier isolation — 2026-09-22

The next point probe used `(512,-63,512)`. The CPU oracle reported final
density `0.03644339979584914`, interpolated branch `0.1139361094199007`, and
`node5=0.11587696714219438`; its eight `node5` corner carriers were
`[0.1171875, 0.1171875, 0.0911763753592057, 0.10465722094930609,
0.1171875, 0.1171875, 0.09313349376198948, 0.09732175390361061]`.

The native-draft staged `wg_node_4` interpolation returned
`0.11428212498167178`, so the density divergence is reproducible before
aquifer/material selection. Rebuilding only the final interpolation parent
from the exact integer-carrier source did not change the value, which places
the error in the interpolation child graph (including captured spline
carriers), not only in the final lerp. The direct and embedded normal-noise
leaves remain contained to the exact carrier and their focused probes match
the exact route; that containment did not repair the parent-child drift.

A historical `nativeDraftExactInterpolation=true` mode routed the entire
interpolation subtree through the exact integer-carrier shader while keeping
the surrounding route native and GPU-dispatched. A full 98,304-block attempt
reached the real corner-domain roots and the `wg_node_5` spline plan, then was
stopped during exact stage compilation at stage `84/444`; it wrote no artifact,
receipt, or parity result. The property therefore defaults to `false` until a
bounded exact-subtree compiler policy exists.

The implementation was then narrowed to exact interpolation parents and
captured FP32 (`uint`) child stages; surrounding FP64/other graph stages stay
native. A focused `(512,-63,512)` probe with the selective mode was allowed a
bounded nine-minute compiler window and was stopped cleanly without a
diagnostic, artifact, receipt, or parity result. This is still an opt-in
isolation experiment, not a promoted GPU path.

A cheaper native child-boundary attempt then targeted `wg_node_5` directly at
the same `(512,-63,512)` point. It remained in the native driver/compiler
path for roughly four minutes and was stopped cleanly without a value, status,
artifact, receipt, or parity result. This confirms that another full replay is
not an efficient next diagnostic until the shader compile envelope is reduced.

Cleanup items:

- Add a compact per-spline carrier diagnostic so exact containment can be
  limited to the first divergent spline rather than the whole interpolation
  subtree.
- Add a bounded shader-cache/compile policy for the selective FP32 carrier
  island; the current probe still exhausts the development driver's compile
  envelope before reporting a value.
- Replace the fixed debug root IDs with a capture manifest/semantic selector.
- Keep native-draft and exact-subtree runs in separate receipts; neither the
  point probe nor the stopped exact-island replay closes G6/G10/G12.

## Full native group-one replay and fallback-subtree isolation — 2026-09-22

The native-draft route was then run to the real comparison boundary with
`normalNoisePerlinGroupSize=1`, device recreation between normal-noise halves,
`recreateDeviceInterval=8`, and the widened interpolation-child envelope. It
completed `394/394` `wg_node_5` stages over `1,225` corners, then completed the
GPU final root, aquifer, ore, and material dispatches for `98,304` logical
blocks. The independent density comparison failed closed at
`24,055/98,304` values with `1,016` sign mismatches; the first mismatch was
`(512,-63,512)`, CPU `0.03644339979584914` versus GPU
`0.036553977141546776`. The run wrote no candidate artifact or receipt:
`build/gpu-overworld-native-group1-full-20260922.bin.candidate-status` and
`build/gpu-overworld-native-group1-full-20260922.stages.log` are the retained
evidence.

The new opt-in `debugDensityInterpolationComponents` diagnostic prints the
eight child carriers for a selected interpolation point. At
`(512,-63,512)`, GPU `wg_node_5` returned
`[0.1171875, 0.1171875, 0.09394449985337432, 0.10355379072090688,
0.1171875, 0.1171875, 0.09275941305005912, 0.0961675267444693]`, while the
CPU oracle returned
`[0.1171875, 0.1171875, 0.0911763753592057, 0.10465722094930609,
0.1171875, 0.1171875, 0.09313349376198948, 0.09732175390361061]`.
The parent interpolation remains `0.11428212498167178` versus CPU
`0.1139361094199007`.

A CPU probe at `(512,-56,512)` and the stage trace narrow the fallback branch:
`wg_node_2101` agrees (`0.6874952944122257`), but `wg_node_2150` is
`0.047458499560122935` on GPU versus `0.03915412607761706` on CPU. An
opt-in exact `wg_node_215*` island was stopped at the same exact-subtree
compiler boundary before producing a result, so it is not enabled by default.

Cleanup items:

- Add a compiler-owned semantic subgraph extractor so one fallback/spline
  island can be exacted without traversing the full 394-stage closure.
- Preserve the child-corner diagnostic in a structured, hash-pinned artifact
  rather than relying on fixed generated node IDs.
- Keep the one-octave native default as containment-only; no group-size,
  device-recreation, or exact-island setting is a parity fix.
- Do not claim G6/G7/G9/G10/G12 from this complete real-GPU failure.

## Compact exact-node A/B probe — 2026-09-22

The diagnostic path now accepts `debugDensityNodeExact=true` alongside
`debugDensityNode=wg_node_<id>`. It swaps only that one-root diagnostic shader
back to the retained integer-carrier source while leaving the ordinary
native-draft route unchanged, and reports the CPU `wg_node_2150` value when
that known fallback root is selected. Java compilation passed after the
change.

The first `wg_node_2150` exact-source probe was stopped after the target
driver/compiler process reached roughly 5.9 GiB without producing a value.
It produced no artifact or receipt. This confirms that even a monolithic
exact fallback-root diagnostic is too large for the current driver envelope;
the next cleanup is a parent-stage A/B emitter that consumes already-produced
child carriers instead of compiling the complete fallback closure.

The same diagnostic slice now has an opt-in
`nativeDraftExactInterpolationDontInline=true` switch. It removes the broad
density `DontInline` prefix only from exact-island stages, so the driver can
inline the small staged helper closure. This is a compiler-containment
experiment and has not been run to parity yet.

The bounded retry with that switch still compiled the monolithic exact
`wg_node_2150` source past 8 GiB without a value and was stopped. The switch
therefore does not solve the monolithic diagnostic; retain it only for the
future staged-parent emitter.

## Staged exact fallback-root probe — 2026-09-22

The follow-up diagnostic added a staged-root path for
`debugDensityStageRoot=wg_node_2150`. It keeps the one-point stage ABI, walks
the selected root's dependency plan, and applies the exact selector only to
the requested fallback island. The probe used `(512,-56,512)`,
`nativeDraftExactInterpolationRoots=wg_node_2150`, and
`nativeDraftExactInterpolationDontInline=true`.

The real GPU run remained CPU-bound in per-stage shader compaction for about
15 minutes, reached roughly 5.6 GiB resident memory, and never reached the
selected `wg_node_2150` stage. It was stopped cleanly without a GPU value,
candidate artifact, status sidecar, receipt, or parity result. This is still
better containment than the monolithic exact-root attempt, but the current
dependency planner/compiler envelope is too expensive for this isolated
fallback probe.

Cleanup items:

- Add a compiler-owned semantic subgraph extractor that emits only the
  fallback root and its minimal carrier inputs; the current staged planner
  still traverses too much of the generated closure.
- Add persistent shader-cache reuse and a hard per-probe compile budget so
  diagnostics fail with an explicit envelope reason instead of consuming a
  long interactive run.
- Keep `debugDensityStageRoot` and the exact selector diagnostic-only; no
  default-path or parity claim follows from this stopped run.

## Seeded fallback-parent GPU probe — 2026-09-22

The staged-root diagnostic now accepts explicit child carriers through
`debugDensityStageRootChildValues=child=value;...`. At `(512,-56,512)`, the
probe supplied the expected CPU-side carrier pair (`0.03915412607761706` and
`-1000000.0`) for `wg_node_2151` and `wg_node_2212` and evaluated the
`wg_node_2150` parent on the real RTX 5070 Ti with the exact integer-carrier
shader. Vulkan returned
`0.03915412607761706`, matching the CPU `node2150` value exactly.

This is a useful GPU operator isolation result, not a parity result: the
child carriers were explicit diagnostic inputs and were not generated by the
GPU route. It narrows the active failure to the upstream fallback child
subgraph rather than the `wg_node_2150` max/threshold parent itself. The
intentional diagnostic status is retained at
`build/gpu-overworld-stage2150-seeded-20260922.bin.candidate-status`.

Cleanup items:

- Replace manually supplied child values with a compiler-owned semantic
  subgraph/carrier manifest and GPU-produced child rows.
- Preserve the seeded-input marker in every diagnostic receipt so it cannot be
  mistaken for a complete GPU replay.

## Compiler-owned semantic parent probe — 2026-09-22

The compiler now retains the emitted-function to `ProgramNode` map and exposes
`WorldgenProgram.nodeFingerprint(...)`. `debugDensityStageRoot` accepts the
resulting `semantic:<64-hex>` selector, so a probe no longer depends on the
transient `wg_node_<id>` allocation order. CPU semantic diagnostics also print
the fingerprint, operation and relevant bounds. The probe-coordinate index was
corrected so semantic CPU values and seeded children are taken from the same
block.

At `(512,-56,512)`, the stable fallback parent fingerprint was
`e0023b082cc7c7b494ebf6f62a0df74f9cdfcbdb17f7c219c935382ff55cb060`, an
`Ap2 max` node. A fresh real RTX 5070 Ti native-draft probe resolved that
fingerprint to the current run's `wg_node_2150`, seeded its compiler-owned
children (`0.03915412607761706` and `-1000000.0`), and returned:

```
GPU staged root  0.03915412607761706
CPU semantic root 0.03915412607761706
```

This is a repeatable same-semantic parent-operator result, but still a seeded
diagnostic: the child carriers came from the CPU oracle and the probe stops
before complete GPU density/material output. It does not create a candidate
artifact, receipt, or G6/G7/G9/G10/G12 evidence. The older fixed-ID traces are
retained as historical data; their numeric names must not be compared across
separate captures.

Cleanup items:

- Move the semantic selector and direct-child carrier manifest into a
  structured per-capture diagnostic artifact rather than exception text.
- Produce GPU child rows for this semantic subtree before attempting a full
  Overworld replay again.
- Add a bounded compiler budget/cache around staged probes; keep the semantic
  parent probe diagnostic-only until its child closure and density parity pass.

## Unseeded GPU child island — 2026-09-22

The semantic map split the fallback parent's left `min` operand into a large
`min` child and an `add` child. The stable fingerprint
`f18dbcf026711205d20dbfe5b0c1b40df77e4abf6babb4a6ad20f83666cc916a` selected
the `add` child directly. A fresh one-point native-draft run evaluated that
child closure on the RTX 5070 Ti without CPU seeding and returned
`1.0123555653151757`, exactly matching the CPU semantic-root value. The
intentional diagnostic sidecar is under
`neoforge-1211/neoforge-1211/build/run/gpu-overworld-semantic-add-20260922/semantic.bin.candidate-status`.

The sibling `min` operand exceeded the ordinary 2,048-stage plan cap before
GPU dispatch. An opt-in `debugDensityStageRootGpuChildren=true` route was then
added to recurse over direct child rows and release each child plan before
building its parent. Its first large-subtree attempt reached about 6.5 GiB
resident without a value and was stopped; no parity/artifact/receipt result is
inferred. Keep this route diagnostic-only until a bounded resource policy is
added.

Cleanup items:

- Add a hard elapsed/memory/stage budget to the recursive GPU-child route.
- Persist child-row provenance and release telemetry in a structured artifact.
- Continue descending the `30e1…` sibling only under an explicit bounded run;
  do not raise the ordinary planner cap globally.

The first bounded implementation added a 4,096-stage ceiling, disabled the
large-capture compaction cache for this route, and reclaimed Vulkan pipelines
after every completed child. A fresh one-point retry still reached about
7.1 GiB resident before the first child completed, so no reclaim boundary or
GPU value was observed. The route was stopped safely. This identifies the
remaining pressure as compiler/graph construction before stage completion;
keep the route disabled by default and treat subgraph extraction or a separate
process envelope as the next cleanup, not a reason to increase the cap. An
additional opt-in `debugDensityStageRootGpuChildrenNativeNoise=true` switch
now permits native-draft normal-noise leaves for compiler-envelope
exploration; it is intentionally unqualified because it may reintroduce the
known native numeric drift.

The native-leaf retry held about 3.3–3.4 GiB resident for roughly nine
minutes, materially below the exact-leaf attempt, but produced no stage value
or sidecar before it was stopped. It is therefore only a compiler-envelope
containment result; the remaining work is a real subgraph/stage progress
signal and eventual exact parity.

## Semantic carrier-stage extraction — 2026-09-22

The runtime now uses the compiler-owned `Shader.nodeFunctions()` map and
semantic `ProgramNode` children to construct a minimal exact parent shader.
Direct pure operators (`Ap2`, `Binary`, `Unary`, `Select`, `RangeChoice`,
`Marker`, `ShiftedNoise` and `WeirdScaledSampler`) are rebuilt with typed
`carrierN` inputs. Each carrier reads the already-produced two-word FP64 row
from the stage ABI, converting back to FP32 when the original child type is
FP32. Interpolation and structure-dependent parents intentionally fall back to
their existing specialized paths; spline knot trees now rebuild their
coordinate nodes from carriers and omit generated helper-only descendants.
The compiler accepts the
placeholder inputs only as an explicitly typed emission aid; the runtime
replaces their bodies with the row lookup before Shaderc sees the stage.

The first real one-point probe reached the new parent-stage compiler path after
the generated carrier wrapper was corrected. The recursive exact closure then
remained inside driver compilation at approximately 5.3 GiB resident and
6.8 GiB private, so it was stopped before a completed child/value. No artifact,
receipt or parity claim resulted. This proves the parent source is now
separable, but the captured normal-noise leaf closure is still the envelope
limitation.

An additional diagnostic-only semantic leaf path can emit captured `Shift`
nodes as standalone exact shaders through
`debugDensityStageRootGpuChildrenSemanticLeaves=true`. Captured `Noise` nodes
now use the existing two-half exact Perlin containment route because a
one-point standalone semantic `minecraft:cave_layer` probe returned `0.0`
against the CPU semantic value `-0.02575933967591109`. It is not a default
production route and must not be interpreted as successful parity.

The follow-up routes captured Noise leaves back through the existing exact
two-half Perlin containment path. A fresh one-point probe returned
`-0.02575933967591109`, matching the CPU semantic leaf. Embedded normal-noise
parents now use staged coordinate/Perlin/combine modules after their GPU child
rows are available. The same-parent retry advanced into those modules but
still reached about 5.5 GiB resident in the driver compiler before a selected
root value.

Cleanup items:

- Emit a compiler-owned stage manifest with child types, source size,
  reachability, pipeline time and memory/provenance rather than relying on
  exception text and transient files.
- Replace the full captured-noise leaf module with a reusable bounded Perlin
  stage plan that preserves exact carriers without compiling the complete
  normal-noise closure per leaf.
- Add an external process/resource watchdog around driver compilation; the
  Java elapsed guard cannot interrupt a native pipeline compile already inside
  the driver.
- Measure the opt-in device-recreation cadence against real pipeline compile
  retention. The first cadence retry (every four stages) stalled inside one
  native compile before the cadence was reached, so replace it with an external
  watchdog and bounded reusable compiler service before any production GPU
  admission. A second retry with normal-noise half-boundary recreation reduced
  monotonic memory growth but still produced no parent value; retain it only as
  an experiment until the compiler service owns this boundary.
- Consolidate the temporary `gpu-probe-carrier-*` and semantic-leaf manifests
  and run directories into one reproducible harness before packaging; they are
  diagnostic leftovers, not release inputs.
- Keep semantic parent/leaf routes diagnostic-only until a GPU-produced child
  closure and independent density parity result exist.

## Generic Perlin metadata planner and root mismatch — 2026-09-22

The opt-in `normalNoiseSharedShader=true` path now uses one cached generic
Perlin sampler and a row-carried metadata ABI. This materially reduced the
driver envelope: recursive probes progressed to `wg_node_343` under a
180-second budget and `wg_node_1321` under a 1,024-stage ceiling, and the
ordinary planner reached the selected `wg_node_2150` root after 11m31s. The
planner still returned GPU `0.045047703014439194`, versus CPU semantic-root
`0.03915412607761706` and CPU full `0.029168161157509254`, then failed closed
without an artifact or receipt.

The first row layout omitted `yScale`, `yMax` and `smear` from the captured
`noise_sample` ABI. The prototype now widens each level from 77 to 82 words,
preserves those controls, and evaluates sparse active levels without an early
level-count break. A focused rerun then found Minecraft's scalar constructor
form `uvec2(0u)`; the parser now applies GLSL scalar replication. The next
fresh probe reached the 34,475-character generic sampler and entered the
driver compiler at roughly 4.8 GiB resident before being stopped without a
value. Java compilation passed, but the corrected ABI has no GPU parity result.
The selectable unrolled fallback reached a comparable 34,800-character shader
and about 5.1 GiB before the same driver boundary; retain it only as a future
driver/runtime experiment.

Cleanup items:

- Add a focused direct normal-noise CPU/GPU parity probe that exercises nonzero
  `yScale`, `yMax` and `smear`, then rerun the semantic-root probe with the
  corrected 82-word ABI.
- Replace source parsing and the fixed 16-level/82-word row with a
  compiler-owned Perlin metadata manifest and explicit versioned ABI checks.
- Compare per-leaf GPU/CPU values before the full semantic root so the next
  mismatch identifies a specific sampler, child, or parent stage.
- Keep `normalNoiseSharedShader=false` by default until the corrected route
  produces exact multi-context density/material artifacts and receipts.
- Consolidate the `gpu-probe-normal-u/v/w-20260922.tsv` manifests and their
  run directories with the earlier carrier probes, preserving only the
  reproducible failure logs needed for the final evidence package.
- Retain the `gpu-probe-carrier-*` manifests and run directories only as
  temporary evidence; consolidate them into the bounded harness or remove
  them after source/evidence retention is approved.

## Full native-draft material baseline and rejected barrier hybrid — 2026-09-22

- Corrected the GPU host fluid-mark decode so non-air state IDs are required
  before a fluid mark is reported. Corrected the native FP64 negative-half and
  negative-quarter helpers to use the same positivity semantics as the CPU
  staged oracle. The full captured CPU graph/root oracle then passed its exact
  comparison.
- The best restored native-draft full Overworld run used seed `0`, chunk
  `(32,32)`, shared host-staged normal-noise modules, and real GPU density,
  aquifer, ore and material dispatches for `98,304` blocks. It failed the
  independent state gate at `62` blocks: `31` stone-to-air, `29` stone-to-water
  and `2` water-to-stone. The first mismatch was `(516,8,525)`; the focused
  probe identified CPU water aquifer selection versus GPU default-stone
  material selection.
- A one-off exact barrier-leaf carrier experiment completed real GPU work but
  worsened the full result to `101/98,304`. It was reverted instead of being
  left as a misleading improvement. The prototype baseline is still
  fail-closed: no GPU receipt, live provider or release qualification follows
  from this run.

Cleanup debt:

- Keep the native-draft route experimental until the aquifer barrier/pressure
  decision has per-stage carrier parity across seeds and dimensions; replace
  the native arithmetic with the qualified integer IEEE route before release.
- Remove or consolidate temporary `build/gpu-probe-*20260922*` manifests,
  run directories, stage logs and generated shader snapshots after the
  evidence retention decision. They are not release inputs.
- Remove the temporary density/node probe seams and restore diagnostic
  exceptions to structured artifacts rather than exception text.
- Requalify the opt-in device-resident material chain separately across
  batches and populated Beardifier rows; the new smoke only covers one
  CPU-density point.
- Add multi-seed, partial-batch and required-context GPU parity before any
  receipt is admitted. Keep the production/live hook disabled until that
  matrix plus FULL/SAVED verification passes.

## Exact aquifer consumer A/B — 2026-09-22

The new `exactAquiferStage` forwarding seam was exercised on the real RTX
5070 Ti. A focused probe corrected the original target-point carrier, returning
`[1,80,0,0]` and water material `[80,0]`. The full native-draft seed-0
Overworld replay regressed from the best `62/98,304` baseline to
`282/98,304` (`209` stone-to-water and `73` stone-to-air), so the exact
consumer must not be promoted while its input density remains native.

Cleanup debt:

- Keep `exactAquiferStage=false` as the default and document it as diagnostic
  only; do not use it as a production fallback.
- Isolate native density error from aquifer arithmetic with a per-block
  density/pressure decision trace before another full A/B.
- Replace the current property-driven stage selection with a versioned,
  structured stage manifest and preserve the focused/full result pair.
- Consolidate the target4/target5 diagnostic manifests, stage logs, shader
  snapshots and run directories after evidence retention is approved.

## Integer aquifer-pressure draft and target compiler envelope — 2026-09-22

The scalar aquifer probe at `(512,-1,526)` now reaches the pressure consumer
on the RTX 5070 Ti after the FP64 multiply/divide experiments were rejected.
The real GPU returned the expected pressure-base carrier (`7.5`) and the
sign-preserving draft carrier (`+4.0`) for the CPU density
`-0.001301293401027703`. This proves device execution and the intended sign
path, not numeric parity: the approximation deliberately omits barrier noise
and quantizes pressure magnitude to a small signed carrier.

The integer-pressure route is now confined to the explicit
`tellurium.gpuCandidate.exactAquiferStage=true` diagnostic (and the scalar
trace). The default route retains its legacy FP64 pressure function, so the
prototype baseline is not silently replaced by the unqualified approximation.
The legacy ratio table is emitted only for the scalar trace; the normal exact
draft emits the compact integer pressure helper.

Two fresh full exact-aquifer attempts were stopped at the target driver's
native compiler boundary before dispatch: the first held roughly 4.7--5.6 GiB
resident for about eight minutes, and the compact-helper retry held roughly
3.7--4.1 GiB for about six minutes. Neither produced a candidate artifact or
receipt. This is a compile-envelope failure, not a parity result. The prior
completed exact A/B remains the only full result (`282/98,304` mismatches), and
the best default native-draft baseline remains `62/98,304`.

Cleanup debt:

- The initial signed-magnitude approximation described above has been replaced
  in the emitter by an opt-in integer rational comparator: binary64 density is
  cross-compared with bounded pressure rationals using four base-2^16 limbs,
  without FP64 multiply/divide in this consumer. Barrier-noise contribution
  and exact threshold behavior still need qualification.
- Split the exact aquifer consumer into a small independently compiled module
  rather than extracting it from the full captured shader; the current module
  still exceeds the target driver's practical compile envelope.
- Added the `maxShaderSourceChars` fail-closed guard before shaderc/Vulkan
  compilation (default `900000`, `-1` only for bounded experiments). Replace
  the current exception/stage-log evidence with a structured failure sidecar
  before release.
- Add a default-route focused material smoke after any future emitter edit;
  the broad native-draft baseline is expensive and must not be rerun for every
  arithmetic experiment.
- Keep scalar diagnostics opt-in and convert exception-text traces into a
  versioned probe artifact before release.

## Exact integer-pressure follow-up — 2026-09-22

The exact aquifer draft no longer uses the earlier coarse signed carrier. The
`exactAquiferStage=true` consumer now emits integer similarity numerators and
bounded rational pressure comparisons. Its binary64 density comparator handles
the finite/sign/zero cases through integer limbs, and the focused compiler
contract test confirms that this path does not emit the old scaled FP64
pressure helper. This is source-level containment evidence only: the route
still omits the barrier-noise contribution and has not produced a qualified
GPU parity result.

The rational full-shader retry, the native-closure retry, and the latest full
native-draft retry all stopped in the RTX 5070 Ti driver's multi-gigabyte
compiler envelope before a dispatch boundary. They produced no new artifact,
receipt, or parity result. The governing real-GPU evidence remains the prior
default native-draft result (`62/98,304` mismatches) and the completed exact
A/B (`282/98,304`); neither is a qualification pass.

Additional cleanup debt:

- Include barrier noise in the integer pressure decision and validate every
  aquifer threshold against the same-stack CPU oracle.
- Extract the exact aquifer consumer into an independently compiled compact
  module, with a source/compile-budget guard before a full replay.
- Remove `debugAquiferMultiply` and the native-closure escape hatch after the
  compiler investigation, or replace them with structured probe artifacts.
- Keep `exactAquiferStage=false` and `exactAquiferStageNative=false` by
  default; do not admit either route to receipts or the live hook.

## External aquifer barrier carrier — 2026-09-22

The exact aquifer prototype now has an opt-in barrier-carrier seam:
`tellurium.gpuCandidate.exactAquiferBarrierInput=true`. The compiler emits
the aquifer entry as a three-argument function, and the staged runtime sends a
seven-word row (`x,y,z,densityLo,densityHi,barrierLo,barrierHi`). The captured
barrier root is evaluated by its own real GPU stage, so the compact aquifer
module no longer needs the complete barrier graph in its reachable closure.

A fresh RTX 5070 Ti one-point run at `(512,-1,526)` compiled and dispatched the
carrier-enabled module. The saved compact source was about `49 KB`; it contains
the barrier input load and integer-carrier similarity-scaled barrier
contribution, and returned `[0,0,1,0]`, consistent with the CPU probe's
no-fluid/air/default-stone decision. This is real device execution of the new
ABI, not full parity: the run intentionally stopped at the scalar diagnostic
boundary.

Cleanup debt:

- Validate the integer-carrier `2 * barrier * similarity` contribution against
  the CPU oracle at every pressure threshold; the current real-GPU smoke is
  still diagnostic-only.
- Finish the same carrier through the full one-point material route and then
  validate full-chunk threshold parity; the existing 394-stage Overworld
  density containment remains the dominant runtime cost.
- Keep the device-resident material-row extension diagnostic-only until the
  similarity-scaled barrier math and GPU-density carrier pass qualification.
- Emit a structured barrier-stage sidecar (source hash, input/output ABI,
  counts and device identity) instead of relying on exception text and ad-hoc
  stage logs.

## Downstream GPU material probe — 2026-09-22

The new `debugMaterialProbeCpuDensity` seam was exercised at the same point
with the CPU density supplied only as an explicit diagnostic input. The run
then executed the barrier carrier, aquifer, ore and final material stages on
the RTX 5070 Ti without traversing the expensive full Overworld density plan.
The stage log recorded barrier `0.2449561470246542`, aquifer words
`[0,0,1,0]`, ore words `[0,0]`, and final material words `[1,0]` (state ID `1`,
no fluid mark). Aquifer source was `49,321` characters; ore and final-material
sources were `65,272` and `9,264` characters.

This proves the carrier reaches the ordinary downstream GPU material ABI and
compiles all three consumers. It is deliberately not a parity result because
the density was CPU-supplied and the run stops with a diagnostic exception
before artifact publication.

## Resident barrier-row material probe — 2026-09-22

The external barrier ABI now also reaches the opt-in device-resident material
chain. The resident row grows from thirteen to fifteen words: the captured
FP64 density remains at words `3/4`, the barrier carrier occupies `11/12`,
and state/fluid outputs move to `13/14`. A separate resident barrier stage
copies the row and writes the two carrier words before aquifer, ore and final
material stages consume it.

A fresh RTX 5070 Ti one-point run at `(512,-1,526)` used CPU density only as an
explicit diagnostic input and dispatched four real GPU stages. The resident
barrier source was `41,743` characters, the exact aquifer source `50,893`, the ore
source `65,437`, and the material source `9,438`. The stage chain completed
with aquifer `[0,0,1,0]`, ore `[0,0]`, and final material `[1,0]`; the
diagnostic then stopped before artifact publication.

Cleanup debt:

- Validate the fifteen-word ABI with partial batches and a non-empty
  Beardifier row before considering the resident chain reusable.
- Validate the integer-carrier similarity-scaled pressure contribution against
  the CPU oracle at boundary cases and across partial batches.
- Replace stage-log/exception evidence with a structured resident-chain
  sidecar containing row ABI, stage hashes, device identity and counts.

The latest saved resident chain hash was
`74075d46231a3bc1ead10da39f0f69ae566307be9cb4f01c2662573b5567e60c`. The
integer-carrier scale is now present in the generated aquifer consumer, but
the CPU threshold sweep, GPU density carrier and full-chunk parity gates are
still open.

## Rational aquifer boundary fix — 2026-09-22

An eight-density real RTX 5070 Ti probe at seed `0`, chunk `(32,32)`, point
`(512,-1,526)` isolated two wrong decisions at the binary64 boundary around
`-3.024`. The GPU's direct integer-rational comparison was correct; the
dynamic pressure helper was wrong when it added a zero barrier carrier. The
exact aquifer emitter now skips that add when the barrier is ineligible. A
fresh eight-case actual-decision sweep passed against the captured CPU oracle,
including both previously failing boundary values. This is one focused
aquifer decision, not full GPU parity or a release gate.

Cleanup debt: remove or consolidate the temporary comparator/threshold debug
switches and exception-text reports into a structured, reusable GPU arithmetic
probe. Expand pressure boundary coverage to eligible-barrier pairs, partial
batches, changed seeds and dimensions. Keep the full native-draft density and
material replay separate from this focused pass; the live hook remains disabled.

The barrier rational scaler also had a 64-bit overflow risk: it shifted the
53-bit mantissa product by three rounding bits before dividing by `625`. The
draft now divides the bounded product first, then derives guard/round/sticky
bits from the remainder. A second real-GPU sweep at `(516,8,525)` passed five
CPU-oracle density cases around the `+0.9265164618291399` threshold. Its CPU
trace had barrier `-0.20285961571493188` and pre-barrier pressure `-0.85`, so
the barrier contribution was eligible and exercised. This is still a single
point/seed and does not qualify the full aquifer stage.

A full seed-0 Overworld `(32,32)` native-draft-density/exact-aquifer/external-
barrier replay was also attempted. It produced no comparison after 8m33s;
the active candidate thread was inside Vulkan `vkCreateComputePipelines` from
the host-staged Perlin route, so the disposable process was stopped. There is
no full-chunk artifact or new parity count from that run. The broader Perlin
compiler envelope remains a separate blocker from the focused aquifer fix.

The opt-in metadata-backed shared Perlin sampler now loops only through its
highest populated octave. The host row records that bound (rather than the
number of calls, which is wrong for sparse octave levels). A one-point real
GPU staged `wg_node_2130` probe at `(512,-63,512)` compiled and matched the
CPU semantic value exactly, `-0.32610927750827157`. This is a single-node
compiler/correctness witness, not full density or material parity. Keep the
shared route opt-in until the full graph and partial/sparse-level batches
pass; preserve the older host-staged route for comparison.

The subsequent opt-in full seed-0 Overworld `(32,32)` replay used native-draft
GPU density, the shared metadata Perlin route, exact integer-carrier aquifer
with external GPU barrier, and GPU ore/final material. It completed all
`98,304` storage blocks in `4m27s` and failed closed on only **one** state:
CPU water versus GPU stone at `(526,13,526)`. No chunk artifact or receipt was
published. This is substantial prototype progress from the prior `62/98,304`
native-draft baseline, but still not an exact GPU parity or release pass.
Isolate that point's GPU density versus aquifer decision before modifying the
full route; keep the shared route opt-in until more seeds/contexts and partial
batches qualify.

Follow-up isolation at `(526,13,526)` showed the mismatch survived an explicit
CPU-density input, so the GPU aquifer decision was wrong independently of
native density. The compact pressure helper was miscompiled with the default
`wg_,noise_` forced DontInline policy: the GPU returned a doubled operand
instead of a cancelling sum for the eligible barrier. The exact external-
barrier aquifer stage now defaults to `aquiferDontInlinePrefix=NONE` without
changing the ore/material policy; an explicit property can still override it.
The eight-case threshold sweep improved from five failures to one case one
ULP beyond the CPU pair-23 threshold. Complete threshold parity is still
open; the current rational pressure route does not reproduce every Java
intermediate rounding step.

The next full seed-0 Overworld `(32,32)` run completed real GPU density,
barrier, aquifer, ore and final-material work for all `98,304` blocks with
**zero state mismatches** in `2m31s`. It wrote a chunk artifact and a schema-1
receipt marked `DRAFT_PARITY_PASS`, route `GPU_NATIVE_DRAFT`, on the RTX 5070 Ti.
The receipt explicitly records `preserveDenorm64=false`; it is a one-case
prototype witness, not the exact-profile multi-context G6 pass or approval
for the live hook. Clean up the exploratory comparator output and duplicate
aquifer addition helper after a correctly rounded numeric implementation is
qualified. Verify changed seeds, partial batches, FULL/SAVED and terrain-mod
contexts before any promotion.

A second full replay used seed `1`, the same Overworld chunk, and batch `127`
(a non-empty partial tail). All GPU stages completed, but the independent
state gate failed `1,424/98,304` blocks, all reported as CPU air versus GPU
stone. The first mismatch was `(512,67,523)`. This shows the seed-0 draft
receipt does not generalize and cannot be promoted. The widespread locations
make a tail-only error unlikely; isolate the density graph at the first
mismatch before attributing cause to native arithmetic or shared metadata.

### Seed-1 density investigation resumed — 2026-09-29

The CPU-seeded `wg_node_50` and `wg_node_51` parents match their CPU semantic
values at `(512,67,523)`. A scalar `wg_node_51` stage oracle finds its first
large difference at the FlatCache wrapper `wg_node_85`, while its noise
child and intervening wrapper match exactly. This scalar probe uses a block
coordinate, whereas the ordinary density path evaluates the child at cell
corners; it must not be treated as the cause of the full-chunk failure until
the same interpolation domain is compared. Earlier `first=` stage values
were corner values and are not directly comparable to block-point semantics.

The optional per-stage CPU oracle now compares every coordinate in a stage,
including all eight corners of a one-block probe, rather than just the first
element. Its explicit tolerance is only a diagnostic filter for locating
large errors and cannot qualify numerical parity. Clean up the diagnostic
exception/status protocol, add spline-node metadata for precise nested-spline
isolation, and preserve distinct scalar versus captured-interpolation domains
in future debug helpers. The GPU production gate remains open.

The ordered spline trace proved a CPU capture cache-key collision: distinct
continentalness and erosion FlatCache producers both used `root/holder` at
the same quart coordinate. Capture now gives each source marker a distinct
reader-local identity while shared source objects retain their descriptor.
The real Vulkan CPU-seeded spline probe now agrees exactly at
`(512,64,520)`: `0.12996654212474823`. This is a diagnostic fix, not whole-GPU
parity. A full seed-1 replay with batch 4,096 still fails 440 of 98,304 blocks
(CPU stone versus GPU air), first `(527,73,524)`, in 2m42s.

Spline capture now retains a shared coordinate lowerer and spline identity
DAG instead of duplicating noise/coordinate subgraphs for every knot. This
is intended to reduce shader stages and repeated work; throughput is not yet
measured. Reader capture is synchronized because its descriptor caches are
shared by live workers. Revalidate the affected CPU corpus after the marker
identity change; historical 1,500-case evidence is not a test of this revision.
Remove or isolate the temporary thread-local ordered spline/FlatCache trace
after the remaining discrepancy is resolved. The new real-loader marker
collision regression has been added but not executed yet.

The focused JVM/compiler/NeoForge tests pass (183 tests, zero failures).
The real-loader run passed 11/12 tests, including the new marker-collision
regression; it exposed an unrelated single-receipt admission teardown leak.
The rejected evidence failed before entering the bundle-registration cleanup
handler. That early failure now closes only the uninstalled provider; this
small lifecycle correction still needs its follow-up loader run.

The remaining density error was localized to a spline coordinate whose noise
sampler stayed inside a 66,873-character module. That combined module chose
endpoint `6.3` rather than `4.69`; the independently staged GPU coordinate and
spline returned `4.69`, matching the CPU. The planner now always stages spline
coordinate boundaries but leaves literal constant knots inline to avoid extra
dispatches. A full chunk replay is running; do not promote this containment
change or advertise a speedup before recording its result.

The separate-coordinate full replay now passes all 98,304 GPU-vs-CPU states
and all ten fields against an independently captured original Minecraft seed-1
NOISE chunk. Receipt: `gpu-seed1-dag-staged-b4096-20260929a.chunk.gpu-receipt.json`.
The rebuilt jar and 187 focused tests pass; all 12 loader tests now pass too.
The new shared spline kernel takes GPU predecessor carriers and immutable
coefficients, survives ordinary pipeline reclamation, and is still opt-in.
Its first `...shared-spline...20260929a` launcher run did not forward the new
flag into Minecraft: it is another ordinary-path control, not shared-kernel
coverage. Explicit forwarding is now added; the `20260929b` run checks the
actual stage-mode log before any performance claim. Add automated launcher
flag-contract coverage and structured per-kernel counters later.

The real shared-kernel run (`20260929b`) used 118 shared spline stages and
passes all ten independent oracle fields. Executor elapsed drops from
17.574s to 13.423s, but full wall time stayed around 2m38s. DAG reuse shrank
the graph to 624k characters, below the old one-million-character parser-cache
threshold. The cache/linear-parser threshold is now 64k with bounded content
LRUs (16 source entries; 8,192 function bodies; 512 root sets per source).
Call-body caches retain raw names and resolve them against each current
module to avoid definition-set aliases. The full cached seed-1 replay and
independent ten-field comparison pass in 2m09s. Repeat warm/cold matched
benchmarks, profile remaining host construction, reduce repeated metadata
uploads and add true device timestamps before any TPS claim. Seed-0 with a
4,095-element partial-tail batch also passes all 98,304 states and ten
independent fields (`gpu-seed0-partial-cached-20260929a`). These are two draft
vanilla cases, not the full GPU corpus or a live TPS result.

The next performance slice replaces per-sample duplicated Perlin tables with
one immutable shared metadata suffix per batch in the generic sampler. The
existing raw-request slicing contract preserves/rebases that suffix; the
unrolled fallback retains its old row layout. Verify partial-tail parity and
input-byte counters before promoting this change. Avoid allocating the unused
resident-chain row array on the shared-sampler path. Persistent immutable
device-table storage, true GPU timestamp queries, and matched warm/live
benchmarks remain follow-up work.

The host packing audit found quadratic defensive-copy traffic: direct and
embedded normal-noise row builders, interpolation corners, paired Perlin
splits and two diagnostic loops called `RawResult.outputWords()` inside the
sample loop (that accessor clones the entire array). They now take one owned
local copy per result. Array immutability and GPU provenance are unchanged;
no unsafe/public backing-array accessor was added. Recheck full-chunk parity
after this batched edit and profile other defensive-copy/descriptor hot paths
later; the draft still returns through host buffers between most stages.

The suffix + linear-packing seed-1 partial-batch replay passes all 98,304
states and all ten independent oracle fields in a 40s fresh Gradle process.
Recorded input drops from 1,496,478,000 to 129,876,088 bytes; neither this
single-run timing nor executor elapsed is live TPS evidence. The suffix-only
control had a 76.212s cold pipeline compile and took 3m17s, so future
benchmarks must report/cache-match compilation explicitly. Add fine-grained
host/compile/device timestamps and stage-binary provenance in receipts later.

Two launcher-only postprocessing failures occurred after successful GPU
artifacts: redirected-output locks, then Windows PowerShell's empty stderr
becoming null in a conditional pipeline. The wrapper now drains redirected
output, uses shared-reader content access and preserves explicit empty
strings; the final seed-0 run checks status publication. Add driver-free
launcher-report fixtures for empty/locked logs, timeout and failed receipts.

Seed-0 also passes the same current linear-packing revision and independent
ten-field comparison in 41s. The empty-log reporting defect persisted through
explicit casts; marker detection is now a null-safe escaped match operator.
Its pure report fragment correctly recognizes the retained passing receipt;
the negative-seed replay checks the complete launcher path next. GPU receipts
and independent comparisons remain distinct from wrapper postprocessing.

The signed seed `-1` replay fails 38/98,304 states (all air to stone), first
`(512,1,521)`; no artifact or receipt is promoted. A scalar child FlatCache
probe does not match the ordinary interpolation domain and must not be
misidentified as the cause. The full-root eight-corner probe first exceeds
the 1e-6 diagnostic tolerance at `wg_node_46` by about 1.07e-6, which alone
does not prove a material/sign bug. Recheck current CPU-vs-original seed -1,
then locate the large/sign-changing divergence in the same corner domain.
Keep production GPU generation disabled until qualified same-stack evidence.

The worker writes its native Gradle exit code before shutdown; null redirected
process-handle status is recovered only from this durable record. The failed
probe now publishes explicit code 1 correctly. The generic Perlin source cache
is capped at sixteen entries to avoid retaining every graph on a long-lived
worker. Repeat sustained-memory/queue workloads before live promotion.

The current CPU seed -1 artifact independently matches all ten original
Minecraft fields and 98,304 states, so the 38-state discrepancy is GPU-side.
The point launcher now exposes `-StageOracleTolerance` solely as a diagnostic
filter. Keep its raw-bit difference reporting and the zero-state-mismatch
artifact gate separate; higher tolerance is never qualification evidence.

The larger same-corner discrepancy is a staged RangeChoice parent: its three
GPU children match CPU, yet `wg_node_685` returns fallback -1 instead of
0.05127210181866899 at `(512,0,520)`. The native-draft FP64 comparison helpers
now use hardware comparisons; exact integer-profile emission/noise leaves
are unchanged. The bounded all-GPU parent probe matches exactly after the
change. Recheck whole negative-seed material output and passing controls;
expand comparison conformance around signed zero/range thresholds, NaNs,
infinities and denormals before exact/native qualification. Source-contract
tests verify rewrite scope only, not numerical correctness.

The full seed -1 native-comparison replay now passes all 98,304 states and
all ten independent original-only fields with a 4,095-element partial batch
(`gpu-negative1-nativecmp-full-20260929a`, fresh Gradle 55s). The wrapper
publishes the correct successful draft status with a recorded worker exit.
This closes the 38-state counterexample; it does not close arbitrary seeds,
exact arithmetic, terrain-mod GPU contexts, FULL/SAVED or live TPS gates.
The final comparison rewrite's seed-1 control also passes all 98,304 states
and ten independent fields (`gpu-seed1-nativecmp-control-20260929a`, launcher
52,571 ms). Refresh the seed-0 control and expand remaining seeds/coordinates,
mod contexts and lifecycle gates. Avoid conflating these draft controls with
a release corpus or warm/live TPS measurements.

### Exact-profile containment and range regression — 2026-09-29

The launcher can now run `GPU_IEEE_BITS` explicitly, without altering the
native-draft default. Its first seed -1 full replay reached multiple exact
blended groups before the six-minute bound and published TIMEOUT, not parity.
The opt-in shared blended sampler replaces forty captured shader compilations
with two GPU coordinate kernels and one GPU sampler; immutable captured
offsets/smear/permutations are batch-suffix data, never host-computed noise.
The corrected follow-up completed all forty sample stages and their fan-in;
full material/artifact parity still needs its own completed result.

Cleanup: replace the rigid source-wrapper/array parsers with compiler-owned
sampler/resource metadata; keep the ABI rejection tests; qualify all seeds,
contexts, coordinate edges, sample controls and partial batches before changing
defaults. Consolidate the five-input/two-word/71-word layouts into a shared
typed contract. The current sampler still reads intermediate values back to
the host between coordinate and noise dispatches; move these buffers to a
fenced resident chain only after the existing resident descriptor issues are
resolved. Returned stage hashes are final-dispatch provenance, not a complete
ordered manifest of every contributing kernel; add that manifest before
release. Repeated coordinate preparation per octave and source-shaped cache
classification are intentional prototype debt.

The actual-device range-parent regression covers 192 vectors under exact and
draft profiles at three slice sizes (including three-element tails), all with
zero mismatches. It does not prove every captured parent or the exact corpus,
and its successful primitive result must not be used to admit the live hook.
The worker hit the account usage limit after leaving its two source/test files;
the coordinator reviewed/integrated them and no replacement agent was spawned.
See [exact GPU evidence](../evidence/v0.2-gpu-exact-overworld.md).

The first shared-blended full run then exposed an opaque-boundary staging bug:
small ancestors estimated an interpolation as already materialized but still
inlined its entire captured noise closure. Every path to an opaque boundary
now splits at its children, making the generated shader actually consume the
seeded GPU carrier. A focused small-ancestor test checks planning and emitted
source. The old run was stopped only after identifying its owned process tree
and confirming native pipeline compilation; it has no passing artifact. The
corrected full retry is retained separately. Recheck the affected exact/draft
corpus before claiming this planner change preserves all captured domains.

The corrected exact replay crosses density in about 70s but fails material
input on a NaN. The first large FP32 spline divergence was isolated to a parent
with matching CPU-supplied diagnostic coefficients. `wg_u32_mul` reversed
GLSL's msb/lsb output order relative to the caller's low/high carrier ABI; the
exact FP32 multiplier now assigns those limbs correctly, with a source-contract
regression. Refresh arithmetic and full exact Minecraft evidence on this
revision. Do not treat historical source/SPIR-V hashes or native-draft spline
success as qualification for the current software multiplier.

The current arithmetic-only native refresh passes 1,829,120 comparisons after
the limb fix; captured-noise/End/weird-scaled campaigns and the million-vector
release gate are excluded explicitly. The full exact replay still sees NaN
knot carriers from a bundled predecessor. The generic spline now uses normal
helper inlining like the shared samplers; an explicit `-InlineIeeeHelpers`
prototype flag also retains only graph/spline callable boundaries in ordinary
stages. Qualify this policy against the matrix before promoting it into a
runtime default. First-row GPU carrier logging was added for spline diagnostics;
move this source-level trace behind a structured bounded diagnostics sink later.

Graph bundling was also swallowing requested shared spline stages before the
shared dispatch selector ran. Shared spline roots with staged children now
form explicit bundle boundaries; constant leaves keep the old bundling path.
This removes repeated integer-helper spline closures rather than hiding a
compiler timeout. It needs current exact/draft controls and broader context
evidence before any default or live promotion.

The latest generic exact root clears the subsequent 133 stone-to-air errors:
seed -1 and seed 0 both pass full Overworld chunks and ten independent fields.
The density-root diagnostic itself was exact; the separate exact-only
two-branch reconstruction was the divergent path. Exact and draft Overworld/
Nether now share the generic root planner. Remove the unused specialized
split/material reconstruction and its hard-coded semantic paths after the
remaining controls; do not confuse those old diagnostics with the actual
shipping route. Consolidate staging defaults and compiler-owned root/resource
metadata, then refresh native controls, all dimensions/mod contexts, and
qualification identities. The one-point material diagnostic now checks the
actual state and fluid mark rather than merely observing dispatch completion.

Throughput debt: the passing exact replay still performs 1,388 bounded
dispatches and uploads about 154 MB per chunk. Keep reusable pipelines and
immutable table suffixes, but move host-carried intermediates to bounded,
fenced resident buffers and avoid rebuilding/parsing full captured source per
request. Match a warm CPU/GPU benchmark before making performance or TPS
claims. The full release numeric/noise campaigns, GPU corpus, live rollback,
FULL/SAVED, terrain-mod and stability gates remain open.

Live-backend duplication: the provider previously called the replay API and
regenerated an entire CPU chunk per GPU request. `generateDevice` now shares
the actual GPU pipeline but assembles the result directly from captured
prerequisite metadata and device state/fluid arrays; heightmaps are computed
from those states without CPU terrain. The replay API still performs its full
CPU comparison. Device-only artifacts have zero compared blocks and null
mismatch count, never PASS. Tests challenge option rejection and metadata/
heightmap/array ownership without even supplying a CPU-capable router.
Measure warm execution and qualification/lifecycle controls before any TPS
claim. Numeric policy still uses prototype system properties: replace these
with an immutable, versioned execution-policy identity before release.

Vanilla Nether has aquifers disabled. An exact shared-stage request now skips
the external barrier carrier when capture explicitly records no aquifer
consumer; blank enabled-barrier metadata is still rejected. The initial
Nether retry reached density but failed that old guard and has no artifact.
The old End route compiled large selector-driven captured tables and entered
native pipeline construction without output; its validated owned helper tree
was stopped. End now uses the generic captured-root planner too; the unused
specialized End/split planners remain cleanup debt until refreshed controls.

The generic End retry also exposed a graph-bundle compiler envelope. The
captured island leaf is now an explicit bundle boundary, and both ordinary
and direct stage requests retain its integer-helper call boundaries. Inlining
the full 25x25 simplex/island loop into a multi-output kernel is not bounded
enough on the current driver. Unused specialized diagnostics, source-shaped
leaf detection and duplicated execution-policy selectors should be replaced
with compiler-owned stage metadata after parity controls. No stopped End run
is counted as a generation or oracle success. Disabled aquifer stages also
need the captured global-fluid entry point, not an enabled-pressure name;
the selector now accepts that ABI while rejecting helper-only source.

End containment is now a working two-kernel GPU prototype: 625 neighbor rows
per unique scaled XZ, then GPU maximum/reduction and power-of-two normalization.
The seed-0 full End chunk and ten independent fields pass, as does the far
island scalar root. Replace source-shaped island detection and captured-simplex
extraction with compiler-owned stage/resource metadata; qualify more seeds,
signed/edge coordinates, FP32 remainder-by-13 vectors and batch suffix layouts.
The host only prepares integer domains/group IDs and expands device words.
Remove unused specialized End/split planners after refreshed native controls.

Dispatch overhead prototype: reuse one serialized, fence-completed pair of
mapped storage buffers plus command pool/buffer, descriptor pool and fence.
Reset only after proven completion; loss/timeouts quarantine the actual retained
capacities and terminate the session. Descriptor ranges remain request-sized
so smaller batches cannot read stale suffix data. Opposite-shaped high-water
marks shrink under the native budget; chains release idle scratch before their
own admission. Device recreation/close release the idle slot. Bulk integer
copies replace per-word Java loops. Allocation/reuse counters are observations,
not TPS or parity claims. Still qualify loss/reset/driver validation, actual
VkMemoryRequirements allocation overhead, warm multi-chunk replay and matched
CPU/GPU throughput. Multi-stage host round-trips and pipeline compilation remain
the larger resident-buffer/planner debt; do not enable the production hook.

The first scratch-reuse full Overworld retry failed before material output:
the recently added End leaf detector looked up a virtual spline root which has
no captured function body. Detection now only parses captured `wg_node_`
roots in modules containing island calls. The regression checks virtual spline
roots, absent island modules and unknown real nodes (still fail closed). This
is cross-dimension planner integration debt, not a storage/device failure;
the failed attempt has no artifact or parity verdict.

The corrected scratch-reuse checkpoint passes exact full NOISE replay and all
ten independent original-only fields in Overworld seed -1, Nether seed 0 and
End seed 0, `(32,32)`, batch 4,095. Actual allocations are 10/8/10 versus
1,388/150/218 dispatches respectively. Focused tests: 183 passed, none skipped.
This preserves the GPU witnesses while removing native allocation churn, not
a full matrix, warm throughput or live TPS result. Keep shader compilation,
host-carried intermediates, sampled metadata/source parsing and disabled-hook
qualification work ahead of cosmetic cleanup. Evidence and current jar hash
are in `docs/evidence/v0.2-gpu-exact-overworld.md`.

Resident normal-noise draft: `RawStage` now owns a separate immutable input
suffix. Each chained sampler receives its captured tables after the resident
rows without host intermediate copies or repeated per-row table upload.
Output descriptors exclude the next stage's suffix; total buffer admission,
quarantine and source identity include all suffixes. Raw-chain telemetry now
records actual kernel dispatches/rows and initial/suffix/final host traffic.
The native 37-element, two-stage suffix smoke passes full/7/1 slices and device
recreation at its 900-byte logical buffer budget. This does not qualify terrain.

The opt-in five-stage normal-noise consumer preserves original child carriers,
both coordinate triples and Perlin values on-device. Source ABI rewriting uses
unique checked anchors; move it into compiler-owned stage/resource metadata.
The first fully inlined dynamic row-copy attempt exceeded 5 GB resident and
hit the six-minute owned process deadline before a first completed leaf; no
artifact or parity success. Explicit row stores and narrow `wg_shared_` sampler
boundaries contain the next attempt. Keep the old passing route as default.
Chain buffers/command objects are still allocated per batch and need bounded
fence-safe pooling; combine-source compilation/parser caching and versioned
policy identity remain debt. Cache classification now also recognizes the
actual generic `wg_shared_perlin_generic` entry point so its stable pipeline
can survive normal reclamation under the existing bounded cache.

The explicit-store full-row retry also hit its three-minute process deadline,
with roughly 16 GB resident before a first leaf. Both a/b attempts have zero
artifacts and no parity verdict. The new `halves` containment mode instead
keeps the already-passing generic sampler source unchanged, chains its GPU
coordinate producer, and exports only six needed first-half coordinates after
the shared fence. The second half returns only its Perlin value; parent combine
remains the original GPU shader. This is an intermediate implementation toward
the complete resident pipeline, not a redefinition of the v0.2 objective.
Bounded export slices, shape-preserving metadata updates and actual-device
export/recreation controls are tested. Capture/compiler-owned ABI and chain
pooling remain necessary cleanup. Do not promote reduced traffic into a speed
claim: the first full half-chain replay is state-exact but has more allocations
and higher cold execution time than the previous host-staged witness.

### Chain pooling and wider batch draft — 2026-09-30

Raw/chain submissions now share bounded high-water mapped slots and reusable
command buffer/pool, descriptor pool and fence. Unused chain slots remain in
admission and quarantine totals; opposite shapes drop excess retained storage
before allocating replacements. Successful fences permit reuse, not teardown
after an unproven submission. The historical chain `vkDeviceWaitIdle` guard
remains before buffers are actually freed on capacity changes/recreation/close.
The native control checks metadata changes, partial slices, raw/chain switches,
tight-budget shrink/regrowth and device recreation. The full half-chain witness
falls from 411 allocations to 12 and from 33.58 s recorded executor time to
13.65 s while preserving 98,304 states and all ten independent oracle fields.
Its same-size host control is 12.92 s. These are individual fresh-process
observations, not a matched TPS uplift; keep residency opt-in.

The hard row ceiling increases to 16,384, with the old 4,096 standalone default
preserved. Explicit 16,383-row raw and chain ABI controls pass a four-row tail;
the exact maximum single dispatch also passes. This is not a replacement for
sustained device/lifecycle/numeric qualification. Preserve the ten-second fence
deadline and native admission rather than assuming larger kernels are safe on
every driver. Still qualify terrain/mod witnesses and matched warm throughput.

Remaining cleanup: move source-shaped stage ABI rewriting into compiler-owned
resource metadata; cache coordinate/combine source compilation; remove the
obsolete two-buffer-only capacity helper after consolidating its tests; account
for actual `VkMemoryRequirements`/descriptor/pipeline overhead rather than only
mapped storage capacities; validate driver loss/idle-failure paths and long
multi-chunk lifetimes. Chain pipelines must remain alive through recording and
submission even when a future long chain exceeds the bounded pipeline caches;
current two-stage normal-noise witnesses do not establish that general case.
Do not enable the qualified production generation hook on these witnesses.

The new seed -1 negative-coordinate corpus preflight passes all three vanilla
dimensions and 30 independent fields (229,376 storage states) on the current
jar, slice 16,383. The corpus runner now accepts a normalized relative-path
`CaseFilter`, preserves hashed original-only input copies and explicitly denies
full coverage for subsets. Current End records 170.21 s executor wall time,
maximum call 103.70 s, whereas Nether records 1.01 s. The counter includes
compilation/pipeline creation; do not infer a slow GPU kernel or a causal driver
diagnosis. Remove seed-specialized End table compilation by moving permutation
metadata to a stable shader ABI, instrument compilation versus queue/readback,
and qualify multi-seed/coordinate cases. The corpus runner now has an owned
process deadline and retained worker status; launcher-exception cleanup still
needs injected validation. The subsequent Tectonic timeout exercises actual
owned-tree deadline cleanup successfully.

### Shared End and modded spline continuation — 2026-09-30

The opt-in shared End shader now consumes one owned 256-word permutation suffix
per bounded batch, canonicalizing only table/function identity. Four source/
metadata tests reject ambiguous, malformed and changed ABI. Source-shaped
rewriting should become compiler-owned resource metadata before release.
The negative outer-island pair caught an existing signed-remainder bug: both
static and shared tables failed 887 states before artifact publication. Both
End shader paths now reproduce Java's remainder by subtracting the truncating
quotient, and simplex attenuation keeps original left-associated arithmetic.
The corrected pair passes 131,072 storage states and 20 independent original
fields. Keep that counterexample; central-island and one-point witnesses missed
it. Broader seed/coordinate/numeric campaigns are still mandatory.

Corrected a Vulkan builder bug that wrote `DISABLE_OPTIMIZATION` to shader-stage
flags instead of compute flags. Internal pipeline ABI is bumped; native control
proves distinct pipelines for compute policies 0/1 and repeat-cache reuse with
stage flags zero. End now honors the same explicit optimization experiment as
other density kernels. Requalify current policies; prior nominal policy/timing
evidence cannot justify release settings.

Executor-lifetime shaderc/native-pipeline phase counters now expose cold costs.
The corrected far End pair spends 36.05 s in first-case pipeline creation, then
adds zero shaderc compilations and only 12.29 ms of pipeline creation in its
second case. Do not subtract overlapping counters or advertise those two calls
as sustained/live TPS. Add per-case windows, queue/readback/device timestamps,
warm campaigns and failed-compile accounting later.

Both GPU launchers attempt owned-tree cleanup after post-launch exceptions;
the corpus runner records worker PID/exited/exit code/timeouts and refuses to
advance on unknown completion. The observed parity/compiler-failure branches
exit cleanly. A later five-minute Tectonic deadline records worker exit proven
and tree-kill exit zero, without artifact/receipt. Injected launcher-exception
teardown still needs validation before unattended service use.

Pinned Tectonic/Lithostitched's first exact GPU preflight reaches a real missing
feature: one-knot splines were rejected before GPU output. Regular and shared
emitters now support original linear extension, including nested values and
signed-zero derivatives. Source contracts pass; full modded replay and numeric
device evidence must establish runtime parity before claiming stack support.
The original unsupported run is retained without artifact/receipt.

The one-knot shared stage now has a real-device 96-vector regression at slices
96/7/1 under both compute policies, including signed-zero derivatives and FP64
coordinate-to-FP32 edges. The next Tectonic full attempt stops at its emitted
source guard (1,120,997 > 900,000 characters), without device output. The runners
now expose a finite `MaxShaderSourceChars` override rather than silently lifting
the default. Separate whole-graph emission size from actual compiled-stage
size, qualify the larger graph envelope, and avoid counting primitive coverage
as full terrain-mod success. Current bounded experiment uses 1,500,000 source
characters and a five-minute owned deadline; per-stage/fence budgets stay fixed.

That larger-source run hits its owned five-minute deadline in CPU-side stage
planning, not a proved driver compile stall: a live worker stack is in
`noiseTableUsers`. Replaced table-by-function regex scans with one identifier
scan per function and exact-name lookup. Immutable result ownership, prefix
collisions and empty inputs are covered by a focused regression. The subsequent
full-stack retry must establish actual speed/parity; this structural reduction
is not itself a TPS claim. Whole-graph parser/analysis caching and per-case
phase timers remain prototype cleanup.

The linear-planner retry reaches native shaderc through
`executeFp64DivisionHostStages` by its 124-second thread snapshot, then hits
the same five-minute external deadline without artifact/receipt. Its later
working set is about 11 GB; owned-worker exit and tree cleanup are proved.
This is no longer the same observed table-scan stall, but does not establish a
causal speedup or full modded parity. Next capture the exact divider shader,
retain per-stage compile diagnostics on failure, and contain that compiler
envelope without weakening the exact arithmetic or enabling a live hook.

The packaged linear-planner/one-knot/compute-flag checkpoint additionally passes
new seed -1 `(-32,-32)` GPU NOISE replay in all three vanilla dimensions:
229,376 storage states and 30 separately compared original-only fields, zero
differences, batch 16,383. Focused coverage is 200 tests, none failed/skipped;
packaging passes. This is integration preflight, not full G6 or live TPS.

## Graph-independent exact divider and failure tracing — 2026-09-30

The three-minute Tectonic trace makes progress through stage 736 and completes
eight entire FP64 division sequences. Its chunk modules are small (about 9 KB)
and their same-index hashes already match. Do not infer a persistent compiler
hang from a single native thread snapshot. The bounded run still times out
without a candidate artifact; cleanup proves owned-worker exit.

Moved the unchanged restoring divider into compiler-owned canonical stages,
removing captured-graph copying/reachability work and unrelated declarations
from that path. A physical 1,508-pair campaign passes five runs (7,540 results)
under both compute policies and partial slices, with reversed slots and
independent BigInteger intermediate-carrier checks. Focused compiler/NeoForge
tests and packaging pass. Full modded replay is separate, not implied.

Cleanup still needed:

- Replace the remaining candidate GLSL-header inspection with a typed compiler
  stage manifest; the divider now owns its source but integration still reads
  local size from the known generated header.
- The helper-closure parser accepts only this emitter's controlled declarations,
  not arbitrary GLSL. Migrate helpers to structured declarations if syntax or
  overloads grow; retain fail-closed unknown-dependency checks.
- The conservative divider still performs 29 host-mediated dispatch/readback
  phases. Qualify fewer-bit-group / bounded resident topologies independently;
  do not enable the historically crashing long resident chain for speed.
- Expand to the full release numerical campaign, driver matrix and all captured
  contexts. NaN-class checks are deliberately not NaN-payload qualification.
- `TraceStages` writes every submitted divider shader and adds file/host overhead.
  Bound/deduplicate trace artifacts and add per-stage timing/failure metadata;
  leave tracing disabled in matched throughput measurements.
- Measure whole-case planning, compiler, pipeline and dispatch costs before
  attributing a TPS gain. Existing compilation cache reuse already existed;
  graph-independent emission alone is not evidence of additional cache hits.

See [shared divider evidence](../evidence/v0.2-shared-fp64-divider.md).

The first full Tectonic seed-0 `(32,32)` artifact now passes all 98,304 states
and ten independent original-only fields with zero differences. It costs 1,893
shaderc compilations and 2,185 pipeline creations across 4,451 dispatches;
these counts motivated replacing the tiny 64-entry module cache with a strict
64-MiB/2,048-entry owned LRU. Native retention limits remain unchanged. Cache
byte/count eviction, replacement, oversize, ownership and invalid-input paths
are covered; the primitive divider still passes 7,540 comparisons, and focused
coverage is 209 passing tests. The new artifact's negative/positive full-stack
replay is a separate integration check, documented below.

That new artifact now passes both seed-0 `(-32,-32)` and `(32,32)` Tectonic
chunks against the independent original-only captures: 196,608 states and
20 fields, zero differences. Retained module cache grows from 825 to 826
entries (17,460,956 bytes), without eviction. The second case adds one shaderc
compilation and 4,450 cache hits, but still creates 2,179 native pipelines.
These are isolated counter deltas, not live TPS or a matched benchmark.
The full 250-case modded context, other mod stacks, unified host heap budget,
expensive planning/source copying and host-mediated readbacks remain open.

Remaining cache cleanup: account for transient compilation/clone peaks and
host graph caches in a unified heap budget, add cancellation/close-race
coverage, measure representative graph working sets before choosing cache
defaults, and qualify persistent native pipeline-cache reuse separately.
No current cache observation establishes live TPS or general modded parity.
