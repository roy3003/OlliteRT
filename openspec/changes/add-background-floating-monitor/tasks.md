# Tasks: Minimal Background Floating Monitor

**Implementation baseline:** `8ab20cb0ac43a825465752694b59f0e3907ac3e3`

The feature is implemented and CI-green. The remaining work is bounded review cleanup, documentation alignment, and complete device acceptance; it is not a redesign.

## Completed implementation

- [x] Establish attributable RED → GREEN evidence through the repository GitHub Actions workflow when no local JDK is available.
- [x] Implement pure `Hidden | Running | Processing` state and display mapping.
- [x] Show only for saved-enabled, permitted, background, live-service, RUNNING state.
- [x] Add persisted setting, default Off behavior, search/save/reset/change-detection wiring, and overlay permission flow.
- [x] Add point-top hexagon renderer, stable-width exact request/error counts, drag, tap-to-open, normalized persistence, clamping, and reset.
- [x] Isolate controller scope and WindowManager failures from `ServerService` and LLM/Ktor lifecycle.
- [x] Dispose best-effort before existing Service model cleanup.
- [x] Keep the existing Service and notification; do not add model/Ktor/token-progress/network polling behavior.
- [x] Use full fill `#55D68B` for RUNNING and `#FFB74D` for PROCESSING with pure black text.
- [x] Render processing time as centered plain seconds `0..9999`, then `9999+`, with a smaller separate `s` suffix and per-request sequence reset.
- [x] GitHub Actions `30265814145`: compile, JVM tests, and Android lint passed for the floating-monitor baseline.

## Documentation alignment

- [x] Backfill proposal, design, and spec from stale `M:SS` / dark-background wording to the implemented plain-seconds visual contract.
- [x] Replace the all-unchecked historical task list with implemented-versus-remaining status.

## Remaining review findings

Implement each as a focused RED → minimal GREEN cycle.

### 1. Saved permission intent

- [x] RED: an unsaved draft toggle SHALL NOT expose or launch overlay permission settings.
- [x] Gate the permission-required row and Grant action on the saved setting value, not the draft value.

### 2. Restart-required affordance

- [ ] Add concise settings copy explaining that enabling the monitor takes effect after the running server is restarted.
- [ ] Keep hot application to an already-running `ServerService` out of scope.

### 3. Tap suppression cannot latch permanently

- [ ] RED: a reported-successful Activity launch that never reaches foreground SHALL NOT hide the monitor indefinitely.
- [ ] Replace the synthetic `appIsForeground || tapSuppressed` encoding with an explicit bounded suppression input/state.
- [ ] Preserve detach-before-launch and PendingIntent/direct fallback behavior.

### 4. Avoid perpetual idle polling

- [ ] RED: a visible idle RUNNING monitor has no perpetual 1 Hz reconciliation loop.
- [ ] Make state and metric changes event-driven; throttle/coalesce changing visible values to at most once per second.
- [ ] Keep processing elapsed updates at 1 Hz while visible and stop all ticker work while hidden/disposed.
- [ ] Preserve bounded retry after WindowManager failures and permission-revocation detection.

## Final verification

- [ ] Run `git diff --check` and focused compile/JVM/lint on the exact final HEAD.
- [ ] Re-run dual-axis review against the fixed monitor baseline.
- [ ] Real device: saved/draft permission behavior, grant/deny/revoke, restart hint, RUNNING/PROCESSING, foreground/non-running hiding, tap failure recovery, drag, rotation/insets, reset, Service stop, and a real inference request.
- [ ] Confirm overlay failures never stop the server and idle RUNNING does not wake at 1 Hz.
- [ ] Do not archive the OpenSpec change until all required evidence is complete.
