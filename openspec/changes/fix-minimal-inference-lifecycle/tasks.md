# Tasks: Minimal Inference Lifecycle

**Branch:** `fix/minimal-inference-lifecycle`

**Baseline:** `8ab20cb0ac43a825465752694b59f0e3907ac3e3`

This is the short continuation plan. Complete each code group as one focused deterministic RED → minimal GREEN cycle. The current phase is source/JVM/CI-first; signed APK and real-device inference gates remain explicitly deferred until separately authorized. Do not archive this change until deferred runtime evidence is either completed or explicitly dispositioned.

## Completed and verified

- [x] Create a clean implementation worktree and preserve the experimental race-evidence worktree.
- [x] Make Gateway the per-request native execution owner.
- [x] Cancel queued/preparing requests without shared native cancel or ghost dispatch.
- [x] Separate preparation from timeout recovery; prepare once for blocking and streaming.
- [x] Wake blocking inference wait on caller cancellation and wait for the existing recovery/finish path.
- [x] Route blocking external cancellation through the request owner.
- [x] Share Gateway ownership with streaming while preserving token/thinking/SSE shaping.
- [x] Route streaming disconnect, external stop, and stop sequence through owner cancellation.
- [x] Move streaming metrics completion behind Gateway operation finish.
- [x] Commit incremental cache metadata only for completed, non-stop-sequence results.
- [x] Add an initial keep-alive generation guard so already-dispatched stale timeout callbacks can be invalidated.
- [x] GitHub Actions `30334882872`: stableDebug compile, JVM tests, and Android lint passed at code commit `0dc2996c`.
- [x] Complete lifecycle Group 1 through code HEAD `80f6063a`: execution phase plus its phase-owned outcome is the single terminal authority; StopSequence winners complete the stream once; terminal notification precedes owner-only native cancellation; a throwing cancel cannot bypass recovery/finish settlement.
- [x] GitHub Actions `30717340493`: stableDebug compile, JVM tests, and Android lint passed at exact code HEAD `80f6063a`.
- [x] Focused independent review `deleg_95c629e7` approved the complete Group 1 slice `933e53cc..80f6063a` with no blocking finding.

## Deferred Gate 0: Verify the current direction on device

Commit `168c293` already separated preparation from recovery and may have removed the primary stuck-counter symptom. The user has directed the current phase to inspect and harden source without loading a model, sending inference requests, or running a phone stress/timeout check.

- [x] Record Gate 0 as deferred rather than treating missing device evidence as source proof.
- [ ] When separately authorized, build a persistently signed APK and record commit, artifact identity, and SHA-256.
- [ ] When separately authorized, run the bounded Gemma timeout → processing zero → RUNNING → next HTTP 200 closure.
- [ ] If that future gate fails, stop and revise the diagnosis before runtime acceptance.

## Blocking lifecycle work

### 1. Make execution state the single terminal authority

- [x] RED: race normal callback, timeout, caller cancellation, and external cancellation; prove only one terminal outcome wins.
- [x] Make execution phase plus a phase-owned outcome authoritative for success, timeout, cancellation, disconnect, stop sequence, and error.
- [x] Keep native-completion and lifecycle-finished wait signals only as owner-driven notification primitives required by the handle-less SDK callback bridge.
- [x] Remove independent terminal writers; do not add a new terminal `AtomicBoolean`, `AtomicReference`, or latch.

### 2. Recover deterministic exception paths

This group MUST precede removal of the lifecycle grace bound.

- [ ] RED: cover preparation failure.
- [ ] RED: cover synchronous dispatch failure that may have committed native work.
- [ ] RED: cover cancellation, recovery, and finish failures reaching exactly one terminal state.
- [ ] RED: force recovery/finish failure while a second request waits; prove the lane fails closed, the uncertain native instance is never reused, and the waiter receives deterministic unavailable/error behavior rather than hanging.
- [ ] Ensure dispatch/cancel/recovery ownership remains exactly once when an operation throws.
- [ ] Ensure configuration restoration, Conversation recovery when required, finish, and terminal notification cannot be skipped.
- [ ] Quarantine or replace an uncertain Engine/Conversation before any later native preparation, without acquiring `keepAliveLock` under an inner lifecycle lock.

### 3. Remove blocking recovery early return

Begin only after group 2 proves every path reaches the lifecycle-finished signal.

- [ ] RED: hold recovery beyond the old grace period and prove the caller does not return first.
- [ ] Remove the `timeoutSeconds + 5` lifecycle early-return path.
- [ ] Keep the request owned and metrics processing until recovery and finish complete.

### 4. Complete model admission ownership

- [ ] Record lock order: `keepAliveLock` → whole-generation `inferenceLock` → execution state monitor; release must leave inner locks before reacquiring `keepAliveLock`.
- [ ] RED: barrier-control both admission-first and unload-first ordering; prove admission-first blocks unload and unload-first prevents selection of the detached model.
- [ ] RED: hold request A's admission while request B performs `rejectWhenBusy=true` try-acquire; prove B is atomically rejected rather than consulting metrics.
- [ ] Add a small `ModelLifecycle` request-admission lease/counter; do not use metrics as the resource lock.
- [ ] Acquire/try-acquire under `keepAliveLock` before model selection. With reject disabled, allow admitted requests to queue; with reject enabled, use an atomic zero-owner try-acquire.
- [ ] Release exactly once in `finally`, after leaving `inferenceLock` and the execution monitor and after all owner-sensitive cleanup (owner settlement, request/native restoration, cache publication, and any remaining model/native access). Ordinary response encoding, SSE channel drain/close, and slow network delivery stay outside the lease.
- [ ] Under `keepAliveLock`, unload only when the timeout generation is current and active admission count is zero; after the last release, start a new idle period instead of reusing the old timeout.

### 5. Make Ktor/SSE caller cancellation authoritative

Keep this group last so its outer-timeout/caller-cancellation behavior can be reviewed or reverted independently.

- [ ] RED: prove parent Ktor cancellation reaches an active SSE execution.
- [ ] Remove the full-writer `NonCancellable` wrapper.
- [ ] Use non-cancellable context only for bounded cleanup that signals owner cancellation and awaits settlement.
- [ ] Preserve SSE headers, event framing, output shaping, and writer timeout behavior.
- [ ] Deliberately exclude audio transcription. Change its separate `NonCancellable` path only after a focused audio cancellation RED.

## Final verification and delivery

- [ ] Run `git diff --check` and audit all changes from the fixed baseline.
- [ ] Re-run full GitHub Actions compile/JVM/lint on final HEAD.
- [ ] Re-run dual-axis `gm-code-review`:
  - lifecycle state, dispatch/cancel, settlement, lock order, and model admission;
  - normal inference, errors, config, cache, metrics, API compatibility, and unnecessary complexity.
- [ ] Resolve blocking review findings with focused RED → GREEN evidence.
- [ ] Keep the persistently signed dev APK, install, Gemma closure, and streaming/device smoke deferred until separately authorized; do not represent compile/CI as runtime acceptance.
- [ ] Keep production failure evidence for audit; retry production tasks by cloning rather than rewriting history.

## Deferred Gate 5: Verify settlement and admission on device

Run only when separately authorized and only after all five source groups, final review, and exact-HEAD CI are complete:

- [ ] Repeat the Gemma timeout closure.
- [ ] Verify model idle unload cannot cross an admitted/queued request.
- [ ] Verify active SSE disconnect reaches cancellation and settlement without altering wire behavior.
- [ ] Verify `requests_processing=0`, monitor `RUNNING`, and a subsequent short HTTP 200 response.

## Guardrails

- Keep `InferenceGateway` as the single request/native execution owner.
- Do not modify `ServerLlmModelHelper.runInference()`, LiteRT Engine/Conversation wrappers, backend initialization, prompt/sampler/token/thinking/tool-call behavior, payloads, SSE wire format, notifications, or floating-monitor visuals without a new deterministic RED proving necessity.
- Do not split blocking and streaming into separate lifecycle state machines.
- Do not parallelize native inference; request ownership depends on the single-thread executor plus whole-generation `inferenceLock`.
- Claim cancellation under the execution state monitor, publish the winner notification, then let the execution owner call `cancelNative()` outside the monitor at most once.
- Do not use metrics as admission state or introduce parallel terminal flags.
- Stop and reassess if callbacks must cross three or more layers or native ownership can no longer remain in Gateway.
