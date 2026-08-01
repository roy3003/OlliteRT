# Delta Spec: Compact Static Floating Monitor with Previous Latency

## Summary

Apply a small, static visual refresh to the existing point-top hexagonal Floating monitor without replacing its established top/bottom information hierarchy.

The control becomes one size smaller at 88 × 100dp. RUNNING keeps its current layout: grouped request count on top and grouped error count on the bottom. PROCESSING keeps request count on top, while the existing lower metric area is divided into two columns: current processing elapsed on the left and the previous successful request latency on the right.

RUNNING uses `#4ADE80`; PROCESSING uses `#AFC6FF`. Only the fill is rendered at 80% alpha (`0xCC`). The same-state-color 2dp edge and ordinary text remain fully opaque. Previous-latency content uses 85% black (`0xD9000000`) to remain visually secondary.

This revision is deliberately static. It does not implement breathing, carousel rotation, state-triggered metric paging, average latency, or a Logo. The alternative breathing/carousel design is preserved independently in `floating-monitor-breathing-carousel-delta.md`, marked INACTIVE, and is superseded for current implementation by this document.

## Scope

### In

- Point-top hexagon retained.
- Control geometry changed from 96 × 108dp to 88 × 100dp.
- RUNNING fill `#4ADE80` at alpha `0xCC`.
- PROCESSING fill `#AFC6FF` at alpha `0xCC`.
- Same-state-color 2dp edge at 100% opacity.
- Ordinary labels, request/error values, and current processing elapsed at opaque black `#000000`.
- Previous-latency label, value, and unit at 85% black `#D9000000`.
- Existing top request block retained.
- RUNNING lower error block retained as one centered full-width metric.
- PROCESSING lower area split into:
  - left: current processing elapsed and `proc` label;
  - right: previous successful latency and `last` label.
- A 1dp low-emphasis vertical divider between PROCESSING lower columns.
- Request/error count grouping retained through 99,999.
- Grouping comma rendered with narrower proportional punctuation advance instead of a full monospace digit cell.
- Main request and RUNNING error values use a fixed 20dp-equivalent Canvas text size.
- PROCESSING `proc` and `last` values use a fixed 16dp-equivalent Canvas text size and fixed `textScaleX = 0.68`.
- Labels and `s`/`ms` unit suffixes use a fixed 10dp-equivalent Canvas text size; PROCESSING units share the same fixed horizontal scale as their value run.
- These custom-Canvas sizes multiply by display `density`, not `scaledDensity`; the fixed geometry does not resize with the user's font-scale setting. Full screen-reader wording remains mandatory through `contentDescription`.
- Each complete PROCESSING value-plus-unit run SHALL measure at most 40dp after the fixed horizontal scale is applied.
- Existing one-second metric and elapsed refresh cadence retained.

### Out

- Breathing, pulsing, rotation, fade, slide, scale, or geometry animation.
- Carousel pages or state-triggered page selection.
- Displaying average latency, peak latency, TTFB, token speed, queue depth, or HTTP handler count.
- Logo, bitmap, blur, gradient, shadow, or glow.
- Whole-View alpha changes.
- Per-frame `WindowManager.updateViewLayout()`.
- New latency aggregation or changes to `ServerMetrics.recordLatency()` semantics.
- RUNNING/PROCESSING state semantics, admission, queueing, cancellation, recovery, Engine/Conversation ownership, model unload, or SSE behavior.
- LiteRT-LM dependency or runtime upgrades; this phase remains on `litertlm-android:0.11.0`.

## Current Behavior

- The control measures 96 × 108dp.
- The point-top hexagon is fully opaque.
- Production colors are RUNNING `#55D68B` and PROCESSING `#FFB74D`.
- The same-state-color edge is 2dp and opaque.
- All text is opaque black.
- The top block always shows `req` and request count.
- RUNNING shows centered error count plus `err` in the lower block.
- PROCESSING shows centered current elapsed seconds plus `proc` in the lower block.
- Values use an 18dp-equivalent bold monospace Canvas size; labels and the seconds suffix use 10dp-equivalent Canvas sizes.
- Request/error counts retain comma grouping and cap as `99,999+`.
- Because the whole count uses a monospace typeface, the ASCII comma consumes a full digit-width cell.
- No previous-latency value is present in the overlay.
- `ServerMetrics.lastLatencyMs` already exists and is displayed on the Status screen as Last Latency.
- `lastLatencyMs` is updated by the existing successful inference completion path; errors and cancellations do not replace it.
- State changes are event-driven; visible metrics, elapsed, permission health, and window reconciliation retain an existing one-second controller cadence.

### Evidence

- `FloatingMonitorView.kt`: geometry, state fill, edge, text paint, and current top/bottom layout.
- `FloatingMonitorFormatter.kt`: grouped count and processing elapsed formatting.
- `FloatingMonitorState.kt`: Hidden/RUNNING/PROCESSING derivation.
- `FloatingMonitorController.kt`: one-second visible refresh and current metric snapshot.
- `ServerMetrics.kt`: `requestCount`, `errorCount`, `isInferring`, `lastLatencyMs`, and `avgLatencyMs` producers.
- `StatusScreen.kt`: existing Last Latency and Avg Latency display.

### Superseded Planning Contract

`floating-monitor-breathing-carousel-delta.md` specifies continuous breathing and a four-page `req → proc → err → last` carousel. It is retained as an INACTIVE alternative and is not an implementation requirement for this phase.

## Behavior Deltas

### D-01 — Reduce the control footprint without changing its shape

- Type: MODIFIED
- Before: The point-top hexagonal View is 96 × 108dp.
- After: The same shape and rectangular touch window are 88 × 100dp.
- Reason: Reduce screen obstruction while retaining a touch target comfortably above the Android minimum.
- Affected actors/contracts: View measurement, hit rectangle, Canvas coordinates, drag clamping, restored normalized placement, and geometry tests.

#### Scenario: Existing placement remains valid

- GIVEN a previously persisted normalized overlay position
- WHEN the smaller View attaches
- THEN the position SHALL be restored and clamped using the new measured dimensions
- AND no raw-pixel migration SHALL be required.

### D-02 — Apply the approved translucent state palette per layer

- Type: MODIFIED
- Before: RUNNING uses opaque `#55D68B`; PROCESSING uses opaque `#FFB74D`.
- After: RUNNING fill uses `#4ADE80` at alpha `0xCC`; PROCESSING fill uses `#AFC6FF` at alpha `0xCC`. The 2dp edge uses the same state hue at 100% opacity. Ordinary text remains opaque.
- Reason: Align the monitor with the App state palette while keeping text and edge clear.
- Affected actors/contracts: Canvas paint constants and visual tests only.

#### Scenario: Fill alpha does not fade content

- GIVEN either renderable state
- WHEN the View draws
- THEN alpha `0xCC` SHALL be applied only to the fill paint
- AND the View, edge, ordinary text, and touch target SHALL not inherit that alpha.

### D-03 — Retain grouped counts with narrow punctuation

- Type: MODIFIED
- Before: Grouped counts such as `12,345` are drawn entirely with a monospace value paint, so the comma occupies a full digit-width cell.
- After: Request and error counts retain ASCII comma grouping, but comma punctuation SHALL use a narrower proportional advance while digits remain visually stable. Counts remain exact through 99,999 and cap as `99,999+`.
- Reason: Preserve familiar grouping and exact activity visibility while reclaiming width in the smaller control.
- Affected actors/contracts: Count drawing helper, text centering, typeface/paint selection, formatter tests, and screenshot tests.

#### Scenario: Grouped count remains centered

- GIVEN values `999`, `1,000`, `12,345`, `99,999`, and a value above 99,999
- WHEN the request or RUNNING error value is drawn
- THEN the output SHALL be `999`, `1,000`, `12,345`, `99,999`, and `99,999+` respectively
- AND the complete composite string SHALL remain centered despite the narrower comma.

### D-04 — Keep RUNNING information layout unchanged

- Type: MODIFIED
- Before: RUNNING shows `req` plus request count in the top block and `err` plus error count in the centered lower block.
- After: RUNNING retains that same information hierarchy and full-width lower error metric, adjusted only for the smaller geometry, new palette, fixed 20dp-equivalent main Canvas values, and narrow-comma drawing.
- Reason: Preserve immediate request/error observability and avoid adding irrelevant previous latency while idle.
- Affected actors/contracts: RUNNING Canvas baselines and visual tests.

#### Scenario: RUNNING does not show last latency

- GIVEN visual state RUNNING
- WHEN the monitor draws
- THEN the upper block SHALL show grouped `req`
- AND the centered lower block SHALL show grouped `err`
- AND no divider, `proc`, `last`, `s`, or `ms` content SHALL be present.

### D-05 — Split only the PROCESSING lower metric area

- Type: MODIFIED
- Before: PROCESSING uses the entire centered lower area for current elapsed seconds and `proc`.
- After: PROCESSING retains grouped `req` in the top block. Its lower area has two equal logical columns centered at 25% and 75% of View width: left `proc`, right `last`. Each complete value-plus-unit run uses the existing bold monospace family with fixed `textScaleX = 0.68`, is centered as one measured composite run, and SHALL be no wider than 40dp. PROCESSING value and label baselines are 66% and 78% of View height so their glyph bounds remain above approximately 80dp, before the point-bottom hexagon narrows sharply. A centered 1dp divider uses `0x33000000` from 54% through 80% of View height. Values sit above their labels. RUNNING does not use this split and retains its existing full-width lower baselines.
- Reason: Compare current elapsed time with the previous successful latency without carousel delay or additional View height, while fitting the already-approved bounded strings in an 88dp View.
- Affected actors/contracts: PROCESSING Canvas geometry, fixed lower-run horizontal scale, divider paint, render model, and visual tests.

#### Scenario: PROCESSING exposes current and previous timing together

- GIVEN visual state PROCESSING
- WHEN the monitor draws
- THEN current elapsed SHALL be rendered in the left lower column
- AND previous successful latency SHALL be rendered in the right lower column
- AND the labels below SHALL read `proc` and `last`
- AND a low-emphasis vertical divider SHALL separate the columns.

### D-06 — Format current processing elapsed compactly

- Type: MODIFIED
- Before: Current processing elapsed is a centered 18dp-equivalent Canvas value with a separate 10dp-equivalent `s` suffix and a `9999+` cap.
- After: Current elapsed remains whole seconds with a separate 10dp-equivalent `s` suffix and the existing `9999+` cap, but uses a fixed 16dp-equivalent Canvas value and fixed `textScaleX = 0.68` in the left PROCESSING column. The measured composite value-plus-unit run SHALL be centered and no wider than 40dp.
- Reason: Preserve the existing elapsed semantics while fitting two stable columns without runtime-dependent font shrinking.
- Affected actors/contracts: Processing elapsed formatter, left-column centering, and boundary tests.

#### Scenario: Proc remains current-request elapsed

- GIVEN an active inference
- WHEN the controller refreshes once per second
- THEN `proc` SHALL show the current request's elapsed whole seconds
- AND it SHALL reset according to the existing inference-sequence contract
- AND it SHALL not use `lastLatencyMs` or average latency.

### D-07 — Display previous successful latency with deterministic units

- Type: ADDED
- Before: The overlay does not display `lastLatencyMs`.
- After: The PROCESSING right column displays a latency snapshot latched once when a new PROCESSING `inferenceSequence` begins. That snapshot reads the then-current successful `lastLatencyMs` and remains unchanged for the whole sequence even if the live metric updates before PROCESSING settles. It uses deterministic compact formatting:
  - zero or absent: `—`, with no unit;
  - `1..9999ms`: exact integer milliseconds with a separate `ms` suffix;
  - `10000..999999ms`: seconds truncated to one decimal place with a separate `s` suffix, producing `10.0..999.9`;
  - `1000000ms` or greater: `999+` with a separate `s` suffix.
  Decimal construction SHALL use integer arithmetic and an ASCII `.`; it SHALL NOT depend on the process locale or runtime text measurement.
- Reason: Preserve millisecond precision for short requests, guarantee that `last` means the request before the current sequence, and bound text width for longer LLM generations.
- Affected actors/contracts: Controller inference-sequence snapshot, render model, right-column formatter, unit paint, and accessibility output.

#### Scenario: First inference has no previous successful latency

- GIVEN `lastLatencyMs` is zero
- WHEN the first request is PROCESSING
- THEN the right column SHALL show `—`
- AND no `ms` or `s` suffix SHALL be drawn.

#### Scenario: Previous latency changes units at a fixed threshold

- GIVEN previous successful latency values of 842ms, 9999ms, 10000ms, 12449ms, 999999ms, and 1000000ms
- WHEN each value is formatted
- THEN the outputs SHALL be `842 ms`, `9999 ms`, `10.0 s`, `12.4 s`, `999.9 s`, and `999+ s`
- AND formatting SHALL not depend on runtime text measurement.

#### Scenario: Last remains previous successful work during processing

- GIVEN request A completed successfully and request B enters a new PROCESSING `inferenceSequence`
- WHEN the controller snapshots `lastLatencyMs` for request B
- THEN `last` SHALL show request A's recorded latency for the whole B sequence
- AND a later live latency update during B SHALL NOT replace the latched value
- AND completion, error, or cancellation semantics SHALL not be inferred in the visual layer.

### D-08 — Establish fixed visual hierarchy and contrast

- Type: MODIFIED
- Before: Main values use an 18dp-equivalent Canvas text size and all text is opaque black.
- After:
  - grouped `req` and RUNNING `err` values use a fixed 20dp-equivalent Canvas text size;
  - PROCESSING `proc` and `last` values use a fixed 16dp-equivalent Canvas text size with `textScaleX = 0.68`;
  - labels and unit suffixes use a fixed 10dp-equivalent Canvas text size; PROCESSING units share `textScaleX = 0.68`;
  - custom Canvas text remains font-scale-independent because the fixed 88 × 100dp geometry uses display `density`, not `scaledDensity`;
  - ordinary content uses opaque black `#000000`;
  - `last` label, value, dash, and unit use 85% black `#D9000000`;
  - no value-dependent or runtime-computed text scale is permitted.
- Reason: Keep primary request/current-state content dominant while making historical latency visibly secondary and enforcing one deterministic lower-run fit policy.
- Affected actors/contracts: Text paints, baselines, value centering, and longest-string tests.

#### Scenario: Long values fit with one fixed lower-run scale

- GIVEN `99,999+` in the top block, `9999+ s` in the left lower column, and `999.9 s` or `999+ s` in the right lower column
- WHEN PROCESSING draws at 88 × 100dp
- THEN every complete lower value-plus-unit run SHALL measure at most 40dp with fixed `textScaleX = 0.68`
- AND all content SHALL remain inside the visible hexagon without clipping or overlap
- AND neither text size nor horizontal scale SHALL vary by value or runtime measurement.

#### Scenario: State descriptions remain complete and actionable

- GIVEN the custom Canvas monitor is exposed to accessibility services
- WHEN RUNNING is rendered
- THEN its `contentDescription` SHALL speak the state, requests, and errors using full words
- AND WHEN PROCESSING is rendered
- THEN its `contentDescription` SHALL speak the state, requests, current elapsed in seconds, and last successful latency in milliseconds/seconds or “no previous successful latency”
- AND the existing accessible click action SHALL remain available.

### D-09 — Keep visual refresh independent from server behavior

- Type: MODIFIED
- Before: State changes are event-driven and visible metrics/window health reconcile once per second.
- After: The same cadence remains. Adding `last` does not create another ticker, animation clock, callback, or server poll.
- Reason: Keep the optional overlay outside the inference and Service critical paths.
- Affected actors/contracts: Controller/View responsibility boundary and lifecycle tests.

#### Scenario: Static rendering adds no animation work

- GIVEN the monitor is attached
- WHEN no state or one-second metric snapshot changes
- THEN no animation frame SHALL be scheduled
- AND no WindowManager layout update SHALL occur solely for visual effects.

## Compatibility Impact

- API/protocol: None.
- Data/schema: None; existing `lastLatencyMs` is read without changing its producer.
- Configuration: None; no new setting is introduced.
- Client/provider/adapter: None.
- User-visible behavior: Intentional palette, size, typography, and PROCESSING layout change. RUNNING information hierarchy remains unchanged.
- Placement/touch behavior: The window becomes smaller; normalized persisted position remains compatible but must be clamped using the new dimensions.
- Operations/observability: Existing one-second visible reconciliation remains; no new continuous animation or polling overhead.
- Security/privacy: None; overlay permission and tap/drag behavior do not change.

## Validation Matrix

| Delta | Verification seam | Planned check | Pass condition |
|---|---|---|---|
| D-01 | Geometry constants and placement helper | JVM contract test plus source review | Size is 88 × 100dp and restored placement clamps with new dimensions |
| D-02 | Paint constants | JVM visual-contract test | State hues match; only fill uses `0xCC`; edge/text remain opaque |
| D-03 | Count formatter and composite text measurement | JVM boundaries plus bounded screenshot | Grouping/caps are exact, comma is narrower than a digit cell, full text remains centered |
| D-04 | RUNNING render model/View | JVM model test plus screenshot | Only req and centered err appear; no split, last, or units appear |
| D-05 | PROCESSING layout helper | JVM geometry/measurement test plus bounded screenshot | Column centers, 66%/78% baselines, `0x33000000` divider endpoints, and ≤40dp lower runs are stable and non-overlapping |
| D-06 | Processing elapsed formatter | JVM boundary/sequence tests | Whole seconds and `9999+` semantics remain unchanged; left value uses fixed 16dp-equivalent size and `textScaleX = 0.68` |
| D-07 | Previous latency formatter | JVM tests at 0, 842, 9999, 10000, 12449, 999999, and 1000000ms under a non-dot default locale | Outputs exactly match the ASCII-dot deterministic unit/cap contract |
| D-07 | Metric source | Latch/render-model test | Each new PROCESSING `inferenceSequence` snapshots existing `lastLatencyMs` once; later live updates do not replace it; avg/error/cancel inference is absent |
| D-08 | Text constants, Canvas measurement, and content description | JVM contract plus longest-string/accessibility checks | Fixed density-scaled 20/16/10 hierarchy, fixed lower `textScaleX`, `0xD9` last tint, ≤40dp runs, complete spoken metrics, and click action are preserved |
| D-09 | Controller/View lifecycle | Source review and focused lifecycle tests | One-second cadence is unchanged; no animator, carousel timer, or per-frame WindowManager work exists |
| D-01–D-09 | Real-device visual acceptance | Short bounded RUNNING/PROCESSING recording when authorized | Colors, narrow comma, split layout, units, fit, and state transitions match the contract |

## Risks and Mitigations

- The smaller PROCESSING lower half has limited horizontal width.
  - Mitigation: fixed `textScaleX = 0.68`, ≤40dp measured composite runs, 66%/78% PROCESSING baselines, small separate units, deterministic last-unit conversion, and longest-string validation.
- Mixed stable-width digits and proportional comma punctuation can be miscentered if widths are estimated rather than measured.
  - Mitigation: center the measured composite run and test every grouping boundary.
- `lastLatencyMs` updates on successful completion and remains stale after a failed/cancelled request.
  - Mitigation: snapshot it once per new PROCESSING `inferenceSequence`; label it `last`, retain that snapshot for the sequence, and do not infer error/cancellation duration in the visual layer.
- The latest successful value is hidden while RUNNING and becomes visible only during the next PROCESSING request.
  - Mitigation: this is the approved compare-current-versus-previous design; the full Status screen remains the durable latency surface.
- A translucent state fill inherits some variation from the underlying App.
  - Mitigation: keep ordinary text and edge opaque, keep last at a still-dark `0xD9`, and require representative light/dark screenshot checks.

## Open Questions

None required before focused source/JVM implementation review. The fixed 0.68 lower-run scale, 40dp width bound, and PROCESSING baselines are part of the contract; a failed fit check blocks implementation and is not permission for dynamic scaling or geometry expansion.

## Handoff

- Artifact path: `openspec/changes/add-background-floating-monitor/floating-monitor-visual-delta.md`
- Delta IDs: D-01 through D-09
- Compatibility-sensitive IDs: none externally; D-01, D-03, D-05, D-07, and D-08 require focused visual/measurement review
- Validation matrix status: complete for source/JVM planning; real-device visual checks remain deferred until authorized
- Inactive alternative: `openspec/changes/add-background-floating-monitor/floating-monitor-breathing-carousel-delta.md`
- Suggested next skill: `gm-tdd`
- Review depth: moderate because fixed-width grouped text and two compact PROCESSING columns must be measured honestly
- Implementation approval status: focused independent re-review APPROVE; production implementation not started by this document
