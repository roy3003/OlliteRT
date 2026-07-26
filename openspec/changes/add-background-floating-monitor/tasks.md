# Tasks: Minimal Background Floating Monitor

Implementation follows the local ticket order under `.scratch/add-background-floating-monitor/issues/` and uses one RED→GREEN→REFACTOR cycle at a time.

## 1. Establish attributable baseline

- [ ] Record branch, HEAD, working tree, Java/Gradle environment, command, complete output, and exit code.
- [ ] Prefer the local command `./gradlew :app:compileDevDebugKotlin :app:testDevDebugUnitTest --no-daemon --console=plain` from `Android/src` when a JDK/SDK exists.
- [ ] When the local Android toolchain is unavailable, use the repository's `jvm-tests.yml` workflow on the exact WIP branch; retain run URL/ID, commit SHA, and failing step/log.
- [ ] Classify any existing failure before feature tests; do not repair unrelated baseline issues without approval.

## 2. State and lifecycle seams

- [ ] RED: test RUNNING/idle -> Running, RUNNING/inferring -> Processing, all other server states -> Hidden.
- [ ] Implement `Hidden | Running | Processing` pure state model.
- [ ] RED: test Running -> req/err and Processing -> req/proc-elapsed display mapping.
- [ ] RED: test visibility combinations for setting snapshot, permission, suppression, app foreground, service alive, and state.
- [ ] Convert `OlliteRTLifecycleProvider` to StateFlow written from MainActivity ON_START/ON_STOP.
- [ ] Remove old NavGraph/ViewModel ON_PAUSE/ON_RESUME writers and migrate synchronous readers such as DownloadRepository to `.value`.
- [ ] Do not add Loading/Error/Stop or long-press branches.

## 3. Settings, persistence, and permission

- [ ] RED: test `ServerPrefs` default false, save/reload, normalized position, and reset.
- [ ] Wire metadata definitions, allSettingDefs/allCardDefs, search, typed ViewModel accessors, save/reset, and change detection.
- [ ] Add Floating monitor UI to the existing server behavior/Auto-Launch area.
- [ ] Show On + `Permission required` + explicit Grant action when permission is absent.
- [ ] Add Manifest overlay permission and Activity Result flow.
- [ ] Set shared suppression before launching system settings; recheck permission before clearing it on result/resume.
- [ ] Do not launch permission settings for an unsaved draft toggle.

## 4. Renderer and formatters

- [ ] RED: exact count tests for `0`, `999`, `1,000`, `12,345`, `99,999`, `100,000 -> 99,999+`, and `Long.MAX_VALUE -> 99,999+`.
- [ ] RED: elapsed tests for `0s`, `59s`, `60s -> 1:00`, `5999s -> 99:59`, and `6000s -> 99m+`.
- [ ] Draw a point-top hexagon with distinct Running/Processing semantic palettes.
- [ ] Use identical four-line geometry: Running req/request/error/err; Processing req/request/elapsed/proc.
- [ ] Use stable-width numerals; do not implement K/M formatting or carousel animation.
- [ ] Add a tight rectangular hit target of at least 48dp; do not promise transparent-corner pass-through.

## 5. Controller and Service integration

- [ ] RED: fake-clock processing transition tests and fake-WindowManager attach/remove/idempotence/failure tests.
- [ ] Implement independent `SupervisorJob + Dispatchers.Main.immediate` controller scope.
- [ ] Subscribe only to existing status/isInferring/requestCount/errorCount plus lifecycle/permission/setting inputs.
- [ ] Record controller-local monotonic processing start on false→true and clear on true→false/dispose; do not modify inference callbacks.
- [ ] Make state updates immediate and coalesce visible metrics with a single one-second ticker; hidden state has no ticker.
- [ ] Create controller via an application EntryPoint if needed; keep construction outside the Service critical LLM try/catch.
- [ ] Catch all overlay failures locally; never call/cause `stopSelf()` and never cancel/block Service scope.
- [ ] Recheck permission on state events and visible ticks; revoke/removal bound is 1s while visible.
- [ ] Best-effort dispose at the start of `onDestroy()` before existing LLM cleanup.
- [ ] Serialize all WindowManager calls on Main and reconcile attached state after exceptions.
- [ ] Do not add a second Service, notification, polling, `/proc`, network scan, or model task.

## 6. Tap, drag, and safe position

- [ ] RED: gesture tests distinguishing tap from drag threshold and preventing drag-end launch.
- [ ] Tap best-effort detaches, then opens MainActivity with PendingIntent or NEW_TASK|SINGLE_TOP fallback.
- [ ] RED: normalized-coordinate and safe-clamping tests for missing/out-of-range values, rotation, insets, cutout, split-screen, and resolution changes.
- [ ] Clamp before saving normalized X/Y at drag end.
- [ ] Reconstruct and re-clamp on attach/configuration change.
- [ ] Reset position takes effect on the next attach without restarting Service.
- [ ] Do not implement long press or swipe.

## 7. Verification and review

- [ ] `git diff --check`.
- [ ] Re-run target compile/unit command with complete logs.
- [ ] JVM: state/display, visibility, five-digit counts, elapsed, 1s coalescing, Window lifecycle, gestures, position, and isolation.
- [ ] Android: preference wiring and permission Activity Result/suppression.
- [ ] Run `gm-code-review` against fixed base/head on Standards and Spec axes.
- [ ] Turn accepted findings into RED tests before fixes.
- [ ] Real device: grant/deny/revoke, system-settings suppression, Running/Processing, foreground/non-running hiding, tap, drag, rotation/insets, reset, Service stop, and a real inference request.
- [ ] Audit no second Service/notification, handoff, Loading/Error/Stop UI, long press, polling, token instrumentation, or model/Ktor behavior change.
- [ ] Do not archive OpenSpec until all required evidence is complete.
