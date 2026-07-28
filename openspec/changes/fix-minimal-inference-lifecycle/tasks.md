# Tasks: Minimal Inference Lifecycle

**Branch:** `fix/minimal-inference-lifecycle`

**Baseline:** `8ab20cb0ac43a825465752694b59f0e3907ac3e3`

This is the short continuation plan. Implement each remaining behavior as one focused RED → minimal GREEN cycle. Do not archive this OpenSpec change until CI, review, APK, and real-device evidence are complete.

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
- [x] GitHub Actions `30334882872`: stableDebug compile, JVM tests, and Android lint passed at `0dc2996c`.

## Remaining blocking lifecycle work

### 1. Complete model admission ownership

- [ ] RED: prove idle unload cannot cross a request admitted before model selection.
- [ ] Add a small `ModelLifecycle` request-admission lease/counter; do not use metrics as the resource lock.
- [ ] Acquire it in the common inference POST path before model selection.
- [ ] Release it in `finally` only after the blocking response or full SSE writer lifecycle settles.
- [ ] Keep the generation guard for stale background timeout callbacks; unload only with zero active admissions.

### 2. Make Ktor/SSE caller cancellation authoritative

- [ ] RED: prove parent Ktor cancellation reaches an active SSE execution.
- [ ] Remove the full-writer `NonCancellable` wrapper.
- [ ] Use non-cancellable context only for cleanup that signals owner cancellation and awaits settlement.
- [ ] Preserve SSE headers, event framing, output shaping, and writer timeout behavior.

### 3. Remove blocking recovery early return

- [ ] RED: hold recovery beyond the old grace period and prove the caller does not return first.
- [ ] Remove the `timeoutSeconds + 5` lifecycle early-return path.
- [ ] Keep the request owned and metrics processing until recovery and finish complete.

### 4. Recover deterministic exception paths

- [ ] RED: cover preparation failure and dispatch failure that may have committed native work.
- [ ] Ensure dispatch/cancel/recovery ownership remains exactly once when an operation throws.
- [ ] Ensure configuration restoration, Conversation recovery when required, finish, and terminal state cannot be skipped.

## Final verification and delivery

- [ ] Run `git diff --check` and audit all changes from the fixed baseline.
- [ ] Re-run the full GitHub Actions compile/JVM/lint workflow on final HEAD.
- [ ] Re-run dual-axis `gm-code-review`:
  - concurrency, dispatch/cancel, settlement, and model admission;
  - normal inference, errors, config, cache, metrics, API compatibility, and unnecessary complexity.
- [ ] Resolve all blocking review findings with focused RED → GREEN evidence.
- [ ] Build the persistently signed dev APK and record artifact identity and SHA-256.
- [ ] Have the user install through the phone installer if Flyme continues to reject ADB installation.
- [ ] Run Gemma-4-E4B-it device closure:
  - long blocking request times out;
  - native generation stops;
  - config and Conversation recovery finish;
  - `requests_processing=0` and monitor returns to `RUNNING`;
  - a subsequent short request returns HTTP 200 without force-stop.
- [ ] Smoke streaming disconnect, queued cancellation, stop sequence, and cache fallback.
- [ ] Keep production failure evidence for audit; retry production tasks by cloning rather than rewriting history.

## Guardrails

- Do not modify `ServerLlmModelHelper.runInference()`, LiteRT Engine/Conversation wrappers, model backend initialization, prompt/sampler/token/thinking/tool-call behavior, payloads, SSE wire format, notifications, or floating-monitor visuals without a new deterministic RED proving necessity.
- Do not split blocking and streaming into separate lifecycle state machines.
- Do not introduce multiple uncoordinated global booleans or use metrics as admission state.
- Stop and reassess if lifecycle callbacks must cross three or more layers or if native ownership can no longer remain in Gateway.
