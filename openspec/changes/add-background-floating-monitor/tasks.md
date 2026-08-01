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

- [x] Add concise settings copy explaining that enabling the monitor takes effect after the running server is restarted.
- [x] Keep hot application to an already-running `ServerService` out of scope.

### 3. Tap suppression cannot latch permanently

- [x] RED: a reported-successful Activity launch that never reaches foreground SHALL NOT hide the monitor indefinitely.
- [x] Replace the synthetic `appIsForeground || tapSuppressed` encoding with an explicit bounded suppression input/state.
- [x] Preserve detach-before-launch and PendingIntent/direct fallback behavior.

Evidence: helper RED `b0da73d9` failed before the bounded state existed; initial GREEN `4ee62b00` passed compile/JVM/lint in Actions `30476236683`. Review then found that an immediate total launch failure could still depend on the unrelated ticker because `StateFlow` may conflate a same-turn `true → false`. Integration RED `75e69f15` failed on the missing coordinator in Actions `30477061732`; GREEN `93486e9c` added explicit detach/launch/reconcile coordination and passed compile/JVM/lint in Actions `30478480764`. Focused follow-up review closed both prior blockers with no new blocking Standards or Spec findings.

### 4. Preserve the existing bounded one-second reconciliation

- [x] Supersede the earlier proposal to eliminate the visible idle RUNNING 1 Hz loop.
- [x] Revert the isolated failing RED that required `shouldScheduleFloatingMonitorRefresh()`.
- [x] Keep state transitions event-driven while retaining the existing one-second metric, elapsed, permission-health, retry, and WindowManager reconciliation cadence whenever the monitor is visible.
- [x] Keep all ticker work stopped while hidden or disposed.

## Active static visual delta

The active contract is `floating-monitor-visual-delta.md`. The breathing/carousel design is retained separately in `floating-monitor-breathing-carousel-delta.md` and is INACTIVE.

Implement these as focused RED → minimal GREEN slices:

- [x] RED/GREEN: compact previous-latency formatter (`—`, exact `ms`, ASCII-dot one-decimal `s`, and cap).
- [x] RED/GREEN: grouped request/error drawing retains commas while punctuation uses a narrower proportional advance.
- [x] RED/GREEN: controller latches existing `lastLatencyMs` once per new PROCESSING `inferenceSequence`; render model exposes that previous-success snapshot.
- [x] RED/GREEN: View uses 88 × 100dp, approved palette/alpha, fixed type hierarchy, and unchanged RUNNING information layout.
- [x] RED/GREEN: changed dimensions preserve normalized-position restoration and clamp geometry across rotation/inset changes.
- [x] RED/GREEN: PROCESSING lower area renders `proc | last` with fixed 25%/75% centers, 66%/78% baselines, `textScaleX = 0.68`, ≤40dp composite runs, smaller units, divider, and no clipping at caps.
- [x] Verify that no breathing, carousel timer, new polling loop, average latency, Logo, or inference lifecycle behavior is introduced.

Evidence: formatter/latch RED `635d61da` failed only on the missing formatter/latch symbols in Actions `30710155769`; GREEN `2b41b2f1` passed compile/JVM/lint in Actions `30710330369`. Controller/render-model RED `b0938ed5` failed only on the missing render contracts in Actions `30711752727`; GREEN `fb932e38` passed compile/JVM/lint in Actions `30711954831` and focused implementation review returned APPROVE. Canvas/geometry RED `f5c7dceb` failed only on the missing visual contracts in Actions `30712849087`; GREEN `502d01a0` passed compile/JVM/lint in Actions `30713079340`, kept the longest conservatively measured lower composite run below 40dp, and focused implementation review returned APPROVE with no blocking findings.

## Final verification

- [x] Run `git diff --check` and focused compile/JVM/lint on the exact final HEAD.
- [x] Re-run dual-axis review against the fixed monitor baseline.
- [ ] Deferred until separately authorized: real-device saved/draft permission behavior, grant/deny/revoke, restart hint, RUNNING/PROCESSING rendering, foreground/non-running hiding, tap-failure recovery, drag/placement, rotation/insets, reset, Service stop, and inference smoke.
- [x] Confirm overlay failures never stop the server, the existing visible one-second reconciliation remains bounded, and hidden/disposed monitor work stops.
- [ ] Do not archive the OpenSpec change until all required evidence is complete.
