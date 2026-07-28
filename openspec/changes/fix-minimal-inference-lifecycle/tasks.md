# Tasks: Minimal Inference Lifecycle

**Branch:** `fix/minimal-inference-lifecycle`

**Baseline:** `8ab20cb0ac43a825465752694b59f0e3907ac3e3`

This is the short continuation plan. Complete each code group as one focused deterministic RED → minimal GREEN cycle. Do not archive this change until final CI, review, signed APK, and real-device evidence are complete.

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

## Gate 0: Verify the current direction on device

Do this before more lifecycle code. Commit `168c293` already separated preparation from recovery and may have removed the primary stuck-counter symptom.

- [ ] Build a persistently signed APK from the current branch HEAD and record commit, artifact identity, and SHA-256.
- [ ] Have the user install it through the phone installer if Flyme rejects ADB installation.
- [ ] With Gemma-4-E4B-it, run a long blocking request to timeout.
- [ ] Record whether native generation stops, `requests_processing` becomes `0`, the monitor returns to `RUNNING`, and a subsequent short request returns HTTP 200 without force-stop.
- [ ] If this gate fails, stop and revise the diagnosis before hardening the state machine.

## Remaining blocking lifecycle work

### 1. Make execution state the single terminal authority

- [ ] RED: race normal callback, timeout, caller cancellation, and external cancellation; prove only one terminal outcome wins.
- [ ] Make execution phase plus a phase-owned outcome authoritative for success, timeout, cancellation, disconnect, stop sequence, and error.
- [ ] Keep native-completion and lifecycle-finished wait signals only as owner-driven notification primitives required by the handle-less SDK callback bridge.
- [ ] Remove independent terminal writers; do not add a new `AtomicBoolean`, `AtomicReference`, or latch.

### 2. Recover deterministic exception paths

This group MUST precede removal of the lifecycle grace bound.

- [ ] RED: cover preparation failure.
- [ ] RED: cover synchronous dispatch failure that may have committed native work.
- [ ] RED: cover cancellation, recovery, and finish failures reaching exactly one terminal state.
- [ ] Ensure dispatch/cancel/recovery ownership remains exactly once when an operation throws.
- [ ] Ensure configuration restoration, Conversation recovery when required, finish, and terminal notification cannot be skipped.

### 3. Remove blocking recovery early return

Begin only after group 2 proves every path reaches the lifecycle-finished signal.

- [ ] RED: hold recovery beyond the old grace period and prove the caller does not return first.
- [ ] Remove the `timeoutSeconds + 5` lifecycle early-return path.
- [ ] Keep the request owned and metrics processing until recovery and finish complete.

### 4. Complete model admission ownership

- [ ] Record lock order: `keepAliveLock` → whole-generation `inferenceLock` → execution state monitor.
- [ ] RED: prove idle unload cannot cross a request admitted before model selection.
- [ ] RED: prove `rejectWhenBusy=true` atomically rejects a second admitted request rather than consulting metrics.
- [ ] Add a small `ModelLifecycle` request-admission lease/counter; do not use metrics as the resource lock.
- [ ] Acquire before model selection. With reject disabled, allow admitted requests to queue; with reject enabled, use an atomic zero-owner try-acquire.
- [ ] Release in `finally` only after the blocking response or full SSE writer lifecycle settles.
- [ ] Under `keepAliveLock`, unload only when the timeout generation is current and active admission count is zero.

## Gate 5: Verify settlement and admission on device

- [ ] Repeat the Gemma timeout closure after groups 1–4.
- [ ] Verify model idle unload cannot cross an admitted/queued request.
- [ ] Verify `requests_processing=0`, monitor `RUNNING`, and a subsequent short HTTP 200 response.

### 6. Make Ktor/SSE caller cancellation authoritative

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
- [ ] Build the persistently signed dev APK and record artifact identity and SHA-256.
- [ ] Have the user install through the phone installer if required.
- [ ] Run final Gemma-4-E4B-it closure and smoke streaming disconnect, queued cancellation, stop sequence, and cache fallback.
- [ ] Keep production failure evidence for audit; retry production tasks by cloning rather than rewriting history.

## Guardrails

- Keep `InferenceGateway` as the single request/native execution owner.
- Do not modify `ServerLlmModelHelper.runInference()`, LiteRT Engine/Conversation wrappers, backend initialization, prompt/sampler/token/thinking/tool-call behavior, payloads, SSE wire format, notifications, or floating-monitor visuals without a new deterministic RED proving necessity.
- Do not split blocking and streaming into separate lifecycle state machines.
- Do not parallelize native inference; request ownership depends on the single-thread executor plus whole-generation `inferenceLock`.
- `cancelNative()` may run under the execution state monitor only while the adapter acquires neither outer lock.
- Do not use metrics as admission state or introduce parallel terminal flags.
- Stop and reassess if callbacks must cross three or more layers or native ownership can no longer remain in Gateway.
