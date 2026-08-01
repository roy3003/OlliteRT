# Delta Spec: Serialized Native Inference Lane

## Summary

Formalize OlliteRT's existing single-active-native-inference behavior as an explicit lifecycle contract. Ktor may receive and validate HTTP requests concurrently, but model admission, Engine/Conversation mutation, native generation, cancellation, recovery, and unload coordination SHALL operate through one serialized native lane.

This delta does not add parallel inference. It sharpens the existing lifecycle specification by separating three concepts that must not be conflated:

1. HTTP request concurrency;
2. request admission and cancellable queueing;
3. the single active native execution permit.

The lifecycle work already completed on this branch remains accepted. It SHALL NOT be rewritten merely because the upstream LiteRT-LM source confirms that one Engine does not provide a dependable request-level parallel decode contract.

## Scope

### In

- A formal capacity-one native inference lane.
- Atomic reject-or-queue admission semantics.
- Request-scoped queued cancellation.
- Separation of model-admission leases from the active native execution permit.
- A single terminal authority for success, timeout, cancellation, disconnect, stop sequence, and error.
- Recovery and finish before the next request may acquire native execution.
- Shared ownership semantics for blocking and streaming inference.
- Stable observability semantics for `ServerMetrics.isInferring` and the Floating monitor.
- Coordination between admitted requests and idle model unload.

### Out

- Multiple active native generations.
- Multiple Engine instances or concurrent Conversation execution.
- Dynamic scheduler capacity or batched multi-request inference.
- LiteRT-LM dependency upgrades; the project remains on `litertlm-android:0.11.0` in this phase.
- Prompt, sampler, seed, schema injection, tool calling, stop-sequence, token/thinking, payload, or wire-format changes.
- Floating monitor color, opacity, typography, breathing, cadence, permission, placement, or interaction changes.
- Audio transcription cancellation.
- Queue capacity limits, queue persistence, priority scheduling, fairness across clients, or a new public queue-status API.
- Native performance, throughput, or benchmark work.

## Current Behavior

- Ktor/CIO may receive multiple requests concurrently.
- `ServerService` supplies a single-thread inference executor.
- `InferenceGateway` additionally serializes whole-generation work with `inferenceLock`.
- The service owns one selected model instance containing one Engine and one active/reusable Conversation path.
- With `rejectWhenBusy=false`, accepted calls may wait behind earlier work; waiting is queueing, not native parallelism.
- The current pre-handler `rejectWhenBusy` check observes `ServerMetrics.isInferring` and is not an atomic admission decision.
- The existing lifecycle specification and task guardrails already prohibit native parallelization.
- Completed ownership, queued-cancellation, recovery, metrics, and cache changes were implemented against this single-lane baseline.

### Evidence

- `ServerService.kt`: single-thread inference executor construction.
- `InferenceGateway.kt`: per-request owner and whole-generation `inferenceLock` boundary.
- `KtorServer.kt`: concurrent HTTP handling and current busy check.
- `ServerLlmModelHelper.kt`: one Engine/Conversation model instance and callback-based dispatch; read-only for this change.
- `ModelLifecycle.kt`: keep-alive generation and idle-unload coordination.
- `specs/inference-lifecycle.md`: existing single-owner, admission, recovery, and compatibility requirements.
- `tasks.md`: explicit guardrail not to parallelize native inference.
- LiteRT-LM `v0.11.0` and `v0.14.0` source: multiple sessions may submit work, while shared executor access remains mutex-protected; this is not an implementation request to upgrade the dependency.

### Known Inconsistencies

- The capacity-one native lane is currently enforced by implementation details rather than named as a public internal contract.
- Metrics are currently consulted by the early busy check even though metrics are observability, not a resource lock.
- Model-admission ownership and active native execution are easy to conflate, although queued requests must protect model selection without becoming active native owners.
- The existing lifecycle spec says an admission lease lasts through the complete SSE writer lifecycle. The narrower required boundary is the last possible model/native access and owner settlement; unrelated buffered network drain SHALL NOT hold the native lane.
- Several terminal signals and grace bounds remain to be consolidated even though the normal path is already single-owner.

## Behavior Deltas

### D-S01 — Make the capacity-one native lane explicit

- Type: MODIFIED
- Before: Native work is serialized by a single-thread executor plus a whole-generation lock, but the capacity-one behavior is primarily an implementation guardrail.
- After: At most one request SHALL own native preparation, dispatch, generation, cancellation, recovery, or finish at any instant. This invariant applies to blocking and streaming requests and to every operation that mutates the shared Engine/Conversation state.
- Reason: The application and upstream runtime do not provide a dependable request-level parallel decode contract; explicit serialization is the safest supported service model.
- Affected actors/contracts: Inference Gateway, Runner, model lifecycle, request handlers, tests, metrics, and operational documentation.

#### Scenario: Concurrent HTTP requests reach inference

- GIVEN requests A and B pass request parsing concurrently
- WHEN both require native inference
- THEN at most one request SHALL enter native preparation or generation
- AND the other request SHALL either be atomically rejected or wait according to its admission policy
- AND HTTP health, metrics, and non-model work MAY remain concurrent

### D-S02 — Make reject-or-queue admission atomic

- Type: MODIFIED
- Before: `rejectWhenBusy` may consult `ServerMetrics.isInferring` before the request owns admission, allowing near-simultaneous callers to observe the same idle value.
- After: With `rejectWhenBusy=true`, admission SHALL use an atomic zero-owner try-acquire and return the existing busy response when any earlier request already owns admission. With `rejectWhenBusy=false`, an accepted request SHALL acquire model admission before model selection and join the existing execution queue. Acquire and try-acquire SHALL linearize under `keepAliveLock`, in the same synchronization protocol used by idle unload, before model selection occurs.
- Reason: Busy policy must decide ownership, not infer it from delayed observability. Sharing the unload lock closes the interval in which unload could observe zero admissions immediately before a request increments the lease.
- Affected actors/contracts: Inference POST handlers, ModelLifecycle, Gateway submission, busy responses, queued cancellation, and idle unload.

#### Scenario: A held admission excludes a second reject-enabled request

- GIVEN the native lane and admission set are initially empty
- AND a barrier-controlled request A has acquired admission and remains held
- WHEN request B performs a `rejectWhenBusy=true` try-acquire before A releases
- THEN B SHALL receive the existing busy response
- AND B SHALL NOT be queued for native dispatch
- AND after A releases, a later independent request MAY acquire admission

#### Scenario: Queueing is enabled

- GIVEN request A is active
- WHEN request B arrives with `rejectWhenBusy=false`
- THEN request B MAY acquire model admission and wait in executor-enqueue order
- AND request B SHALL NOT be counted as active native processing while it waits

### D-S03 — Preserve request-scoped queued cancellation

- Type: MODIFIED
- Before: Queued cancellation has been implemented, but its role in the formal single-lane contract is implicit.
- After: A queued or pre-dispatch request SHALL be cancellable without calling shared native cancellation, changing active Conversation state, or later performing ghost dispatch. Cancellation SHALL release that request's model-admission ownership exactly once.
- Reason: In a shared single lane, only the active owner may cancel the shared Conversation.
- Affected actors/contracts: InferenceExecution, Gateway queueing, caller cancellation, timeout handling, and model admission.

#### Scenario: A queued caller disconnects

- GIVEN request A owns native execution
- AND request B is admitted and queued
- WHEN request B's caller is cancelled
- THEN request B SHALL leave the queue without native dispatch
- AND request B SHALL NOT call `cancelProcess()`
- AND request A SHALL remain unaffected

### D-S04 — Separate model admission from native execution ownership

- Type: MODIFIED
- Before: The planned admission counter protects idle unload, while the executor and lock serialize native work, but their lifetimes are not stated as two independent contracts.
- After: A model-admission lease SHALL be acquired under `keepAliveLock` before model selection and held while a queued or active request may still access model/native state. Idle unload SHALL check the lease count and detach the active model under that same lock, making admission versus unload a single ordered decision. A distinct capacity-one execution permit SHALL be held only by the active native owner. Queued requests MAY contribute to the admission count but SHALL NOT own the execution permit or set active-processing metrics. Lease release SHALL run exactly once in `finally`, after the execution owner no longer holds `inferenceLock` or the execution state monitor; it SHALL then take `keepAliveLock` to decrement ownership and begin any new idle period only after the last admission releases. The lock order remains `keepAliveLock` → whole-generation `inferenceLock` → execution state monitor.
- Reason: Idle unload must not cross an accepted queued request, while queue depth must not be misrepresented as simultaneous native processing.
- Affected actors/contracts: ModelLifecycle, keep-alive timeout, Gateway, ServerMetrics, and Floating monitor state mapping.

#### Scenario: Idle unload races an admission

- GIVEN an idle timeout and request B are stopped on barrier-controlled sides of `keepAliveLock`
- WHEN either unload or B's admission acquire wins that lock first
- THEN if B acquires first, unload SHALL observe active admission and SHALL NOT clear or close the selected model
- AND if unload detaches the model first, B SHALL observe the post-unload lifecycle state and SHALL NOT select the detached model
- AND no interleaving SHALL admit B against a model concurrently being cleared or closed

### D-S05 — Release the lane only after authoritative settlement

- Type: MODIFIED
- Before: Preparation, dispatch, callbacks, timeout, caller cancellation, recovery, operation finish, and grace waits still contain more than one terminal signal or writer.
- After: Execution phase plus one phase-owned terminal outcome SHALL classify the request. On a dispatched timeout, cancellation, disconnect, stop sequence, native error, or uncertain synchronous dispatch failure, required cancellation, configuration restoration, Conversation recovery, and operation finish SHALL settle before the execution permit is released to the next request. If recovery or finish itself fails, the owner SHALL fail closed: mark the lane unavailable, prevent every later request from entering native preparation, and return a deterministic unavailable/error result until the uncertain Engine/Conversation is either successfully recovered or replaced/quarantined. Quarantine/replacement SHALL follow the documented outer-to-inner lock order; it SHALL NOT acquire `keepAliveLock` while `inferenceLock` or the execution monitor is held. A permit MAY transfer only after the shared native instance is known reusable or is unreachable by later requests.
- Reason: A caller returning early must not allow a later request to reset or use shared Conversation state while an earlier native callback or recovery is still active. Fail-closed quarantine avoids both unsafe reuse and an indefinitely blocked queue.
- Affected actors/contracts: Gateway state, Runner waits, timeout behavior, cancellation sources, recovery, metrics, cache eligibility, model availability, and the next queued request.

#### Scenario: Timeout is followed by another request

- GIVEN request A owns native execution and times out
- AND request B is queued
- WHEN request A begins cancellation and recovery
- THEN request B SHALL remain outside native preparation
- AND A's terminal outcome SHALL be chosen once
- AND only after A's recovery and finish settle MAY B acquire the execution permit

#### Scenario: Recovery or finish fails

- GIVEN request A owns native execution and required recovery or finish throws
- WHEN request B is waiting for native execution
- THEN A's owner SHALL transition the lane to an explicit unavailable state
- AND B SHALL NOT enter native preparation against the uncertain Engine/Conversation
- AND B SHALL receive deterministic unavailable/error behavior unless a fresh valid native instance replaces or safely quarantines the failed one
- AND the lane SHALL NOT remain silently queued forever

### D-S06 — Keep network writing outside the native lane

- Type: MODIFIED
- Before: The existing lifecycle text can be read as retaining model admission and native ownership through the entire SSE writer lifecycle, including unrelated network drain.
- After: The parent Ktor request SHALL remain capable of signalling cancellation while native work is active. `NonCancellable` MAY wrap only bounded owner cancellation and settlement cleanup. The native execution permit SHALL be released after native settlement, not after unrelated JSON rendering, buffered SSE drain, or slow client network I/O. The model-admission lease SHALL remain held through owner-sensitive cleanup: signalling cancellation while the owner is active, awaiting owner settlement, restoring request/native configuration, deciding success-qualified incremental-cache publication, and ensuring no path can again invoke model/native operations. It SHALL release exactly once after that boundary and before ordinary response encoding, channel close/drain, or slow network delivery that cannot touch model/native state.
- Reason: Serializing network backpressure would reduce service availability without protecting Engine/Conversation ownership, while releasing before owner-sensitive cleanup would reintroduce unload and cross-request races.
- Affected actors/contracts: Ktor SSE writer, Gateway owner, channel cleanup, model admission, response latency, cache publication, and queue progress.

#### Scenario: Native streaming completes before buffered delivery

- GIVEN native generation and owner finish have settled
- AND buffered SSE data remains to be written
- WHEN a queued request is ready
- THEN the completed request SHALL no longer hold the native execution permit
- AND buffered network delivery SHALL not mutate shared Engine/Conversation state
- AND ordinary writer cleanup SHALL remain outside the native lock

### D-S07 — Define metrics and monitor as observers

- Type: MODIFIED
- Before: `ServerMetrics.isInferring` both drives visible PROCESSING state and is consulted by the pre-handler busy check.
- After: `ServerMetrics.isInferring` SHALL describe active native processing/settlement only. It SHALL NOT represent HTTP handler count, queued admissions, or act as the admission lock. The Floating monitor SHALL map active lane occupancy to PROCESSING and a ready, idle lane to RUNNING without exposing queue depth.
- Reason: Admission correctness must not depend on UI/metrics timing, and queued requests must not be mislabeled as parallel inference.
- Affected actors/contracts: ServerMetrics, busy policy, Floating monitor reducer, elapsed timer, request/error counters, and observability tests.

#### Scenario: A request waits in an otherwise observable queue

- GIVEN a request is admitted but has not acquired native execution
- WHEN observers read `isInferring`
- THEN the value SHALL be derived from the active native owner rather than the admission count
- AND queue state SHALL not be inferred from the Floating monitor

## Disposition of Completed Lifecycle Work

The following completed work remains valid and SHALL NOT be reopened merely to restate the serial architecture:

- Gateway as the per-request native owner.
- Queued/preparing cancellation without shared native cancel or ghost dispatch.
- Prepare-once separation from timeout recovery.
- Blocking caller cancellation routed through the owner and existing settlement path.
- Shared blocking/streaming ownership.
- Streaming disconnect, external stop, and stop sequence routed through owner cancellation.
- Streaming metrics completion after Gateway operation finish.
- Incremental cache metadata committed only for eligible completed results.
- Keep-alive generation invalidation for stale dispatched timeout callbacks.

Focused refactoring remains permitted only when a new invariant test proves that a completed path violates D-S01 through D-S07.

## Remaining Work Reframed for the Serialized Lane

1. **Terminal authority:** ensure one terminal outcome controls when the capacity-one execution permit may transfer.
2. **Deterministic exception recovery:** ensure prepare, synchronous dispatch, cancel, recovery, and finish failures cannot strand the lane or expose an uncertain native instance; recovery/finish failure must fail closed into deterministic lane unavailability until safe replacement/quarantine.
3. **Recovery grace removal:** remove the `timeoutSeconds + 5` early return only after every path is proven to reach owner settlement.
4. **Atomic model admission:** implement the admission lease independently of metrics and protect model selection/unload.
5. **SSE parent cancellation:** narrow `NonCancellable` to bounded owner cleanup and preserve existing SSE framing.
6. **Validation gates:** use source/JVM/CI evidence in this phase; real-device inference acceptance remains explicitly deferred until separately authorized.

These are hardening steps for one serialized native lane, not work toward parallel inference.

## Compatibility Impact

- API/protocol: Existing OpenAI/Anthropic request, response, error, and SSE formats remain unchanged. Existing busy behavior remains, but the decision becomes atomic.
- Data/schema: None.
- Configuration: `rejectWhenBusy` retains its user-facing meaning: reject when true, queue when false.
- Client/provider/adapter: Clients may observe more deterministic busy rejection and cancellation ordering. No new client field or endpoint is introduced.
- User-visible behavior: Requests continue to execute serially. A queued cancellation SHALL settle without affecting the active request. Timeout recovery may keep a request visibly PROCESSING longer rather than returning before cleanup.
- Operations/observability: Metrics remain observers. Queue depth is not added to the Floating monitor or public metrics by this delta.
- Security/privacy: None.

## Migration / Rollout / Rollback

### Preconditions

- Preserve the current `0.11.0` LiteRT-LM dependency and protected `ServerLlmModelHelper.kt` boundary.
- Remove or revert any unrelated failing RED that requires event-driven idle Floating monitor refresh before claiming final branch CI.
- Keep the current single-thread executor and whole-generation lock until focused evidence proves one layer safely redundant.

### Rollout Steps

1. Add invariant tests for capacity one, atomic rejection, queued cancellation, recovery-before-handoff, and unload exclusion.
2. Complete terminal-authority and deterministic exception recovery changes.
3. Remove the blocking recovery grace only after settlement tests are GREEN.
4. Add atomic model-admission ownership and keep-alive coordination.
5. Narrow the SSE cancellation boundary independently.
6. Run project-native compile, JVM tests, lint, diff checks, and focused review on the exact HEAD.

### Verification Gates

- After step 1: tests must show maximum active native ownership of one under concurrent submissions.
- After step 2: every success/error/cancellation race must produce exactly one terminal outcome and lane release.
- After step 3: callers must not return before required recovery/finish.
- After step 4: idle unload must not cross any active admission; reject-when-busy must be atomic.
- After step 5: parent SSE cancellation must reach the owner without wrapping normal network writing in `NonCancellable`.
- Final: compile/JVM/lint must pass. Runtime/device closure remains deferred and must not be fabricated from build evidence.

### Rollback Boundary

- Each implementation group SHALL remain a focused change that can be reverted independently before release.
- Existing completed ownership work is the rollback baseline; reverting the formalization SHALL NOT reintroduce request-level parallel dispatch.

### Recovery

- On a failed hardening slice, retain audit evidence, revert or revise only that slice, and keep the previous single-thread/whole-generation serialization intact.

## Validation Matrix

| Delta | Verification seam | Planned check | Pass condition |
|---|---|---|---|
| D-S01 | Gateway/executor test seam | Submit overlapping blocking and streaming operations with instrumented native hooks | Maximum simultaneous native owners is exactly one |
| D-S02 | Admission seam | Hold request A's admission behind a barrier while request B performs reject-enabled try-acquire; separately queue reject-disabled requests | B is deterministically rejected while A remains held; reject-disabled requests enqueue without parallel dispatch |
| D-S03 | Queued cancellation seam | Cancel a queued request while another request is active | Queued request never dispatches or calls native cancel; active request is unaffected |
| D-S04 | ModelLifecycle seam | Barrier-control both admission-first and unload-first ordering under `keepAliveLock`, including stale/current idle callbacks | Admission-first prevents unload; unload-first prevents selection of the detached model; queued admissions do not own active metrics |
| D-S05 | Execution state seam | Race completion, timeout, disconnect, external cancel, stop sequence, and operation exceptions; force recovery and finish failures | One terminal outcome wins; next request starts only after successful settlement, or receives deterministic unavailable behavior while the failed native instance is quarantined/replaced |
| D-S06 | SSE parent/owner seam | Cancel an active writer and separately delay buffered network output after native completion | Active cancellation reaches owner settlement; network drain does not retain native ownership |
| D-S07 | Metrics/reducer seam | Observe active, queued-only, settled, and ready-idle states | `isInferring` tracks active native settlement only; monitor maps PROCESSING/RUNNING without queue semantics |

## Risks and Mitigations

- Holding the lane through recovery can increase tail latency after timeout.
  - Mitigation: correctness takes priority; instrument settlement duration without releasing ownership early.
- Admission and execution permits can be accidentally collapsed into one counter.
  - Mitigation: name and test their different lifetimes explicitly.
- An unbounded existing queue can admit more waiting callers than desired.
  - Mitigation: queue capacity is intentionally unchanged in this delta; specify a separate policy change if operational evidence requires bounds.
- Removing either the executor or lock too early can create a bypass.
  - Mitigation: retain both initially and simplify only with focused no-bypass evidence.
- Slow SSE clients can block queue progress if writer lifetime is conflated with native settlement.
  - Mitigation: keep ordinary network drain outside native ownership while preserving bounded cancellation cleanup.
- Upgrading LiteRT-LM during lifecycle hardening would confound native callback and cache behavior.
  - Mitigation: keep `0.11.0` pinned for this phase and evaluate upgrades independently later.

## Open Questions

None block this delta. Queue capacity, multi-Engine scheduling, and LiteRT-LM upgrades require separate decisions and evidence.

## Handoff

- Artifact path: `openspec/changes/fix-minimal-inference-lifecycle/serialized-inference-lane-delta.md`
- Delta IDs: D-S01 through D-S07
- Compatibility-sensitive IDs: D-S02, D-S04, D-S05, D-S06
- Validation matrix status: complete for source/JVM/CI planning; real-device inference validation is deferred by current instruction
- Tickets needed: no new tracker items; map existing remaining lifecycle groups to these delta IDs
- Suggested next workflow: align lifecycle tasks, then implement focused RED → GREEN slices
- Review depth: high because timeout, cancellation, model unload, and shared native ownership interact
- Implementation approval status: independent specification re-review APPROVE; production implementation not started by this document
