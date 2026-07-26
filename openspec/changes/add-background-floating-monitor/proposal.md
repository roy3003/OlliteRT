# Change: Add Background Floating Monitor

## Why

When OlliteRT serves as a local network LLM endpoint, users cannot see whether the server is idle or processing after leaving the app. A small opt-in overlay can expose essential health without reopening the app, but it must remain strictly subordinate to the existing LLM service so UI failures cannot stop serving.

## Goals

- Add a Floating monitor setting that defaults Off and is saved only by explicit user action.
- Require explicit Android overlay permission and expose `Permission required` when intent is On but permission is absent.
- Show the monitor only while the app is backgrounded, the current `ServerService` is alive, and server state is RUNNING.
- Map RUNNING + idle to Running and RUNNING + `isInferring` to Processing; hide all other server states.
- RUNNING statically shows exact `req` / `err`; PROCESSING statically shows exact `req` / current `proc` elapsed.
- Keep state changes event-driven and coalesce visible metric redraws to at most once per second.
- Support tap-to-open, drag, normalized position persistence, safe-area clamping, and reset position.
- Draw a dynamic point-top hexagon inspired by `ic_brand` without relying on a fixed status bitmap.
- Isolate all overlay lifecycle, exceptions, and coroutines from model/Ktor lifecycle.

## Non-Goals

- LOADING, ERROR, or STOP renderers.
- Long-press start/stop or any `StartDefaultModel` action.
- A second foreground service, notification, service ownership handoff, or capability gate.
- Hot applying the enabled setting to an already-running `ServerService` instance.
- Continuous carousel/marquee animation, swipe pages, or token-progress instrumentation.
- HTTP polling, network scanning, `/proc` sampling, model sampling, or changes to inference/Ktor behavior.
- Reliable touch pass-through through transparent hexagon corners; Android overlay input bounds remain rectangular.

## User-Visible Behavior

The monitor is disabled by default. When enabled and authorized, it appears only while OlliteRT is in the background and the existing server is running.

```text
RUNNING                 PROCESSING
    req                      req
   1,234                    1,235
      1                      12s
    err                     proc
```

Counts are exact from 0 through 99,999 and fixed at `99,999+` from 100,000 onward. Processing elapsed uses `0s..59s`, `M:SS` through `99:59`, and `99m+` thereafter.

## Scope and Impact

The monitor is an attached observation UI for the existing `ServerService`. It reads existing `ServerMetrics.status`, `isInferring`, `requestCount`, and `errorCount`. Current processing elapsed is measured locally by the controller from the observed false→true `isInferring` transition using a monotonic clock.

Expected changes include settings metadata/UI, preferences, Manifest overlay permission, observable application lifecycle, a lightweight WindowManager controller/view, minimal Service wiring, strings, and focused tests. There are no HTTP API, database, model format, inference protocol, lock, keep-alive, or notification semantic changes.

## Stability Boundary

- Controller uses its own `SupervisorJob + Dispatchers.Main.immediate` scope.
- It never reuses, cancels, or blocks `ServerService.serviceScope`.
- Initialization lives outside the Service's critical LLM initialization try/catch.
- Controller/View/permission/WindowManager failures disable only the monitor and must never call or cause `stopSelf()`.
- `onDestroy()` best-effort detaches/disposes the monitor before existing potentially long LLM cleanup.

## Risks and Mitigations

- **Overlay permission flow briefly backgrounds the Activity:** use a shared suppression StateFlow and recheck permission before clearing it.
- **Permission can be revoked without a reliable callback:** re-evaluate on state events and each visible metric tick, bounded by 1s.
- **WindowManager calls can race or throw:** serialize on Main, make attach/remove idempotent, and reconcile attached state after failures.
- **Transparent corners still intercept touch:** keep a tight rectangular window and document the limitation.
- **Stale RUNNING during Service cleanup:** dispose at the start of `onDestroy()`.
- **Metric text jitter or overflow:** use stable-width numerals and bounded exact formats.

## Validation Plan

- JVM tests: state/display mapping, visibility reducer, lifecycle StateFlow, five-digit exact counts, bounded processing elapsed, 1s coalescing, gesture/geometry reducers, Window lifecycle, and failure isolation.
- Android tests: setting default/save/reset/search/change detection, permission-required state, and permission Activity Result/suppression behavior.
- Real-device checks: permission grant/deny/revoke, background RUNNING/PROCESSING, foreground hiding, non-RUNNING hiding, tap, drag, rotation/insets, reset, Service destruction, and a real inference request.
- Run target Kotlin compilation and unit tests; retain exact logs for any environment blocker and never claim an unobserved pass.
