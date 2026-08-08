/*
 * Copyright 2025-2026 @NightMean (https://github.com/NightMean)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ollitert.llm.server.ui.floatingmonitor

import com.ollitert.llm.server.service.SuccessfulInferenceLatencySnapshot

class PreviousSuccessfulLatencyLatch {
  private var processingSequence: Long? = null
  private var latchedSuccessfulSequence = 0L
  private var latchedLatencyMs = 0L

  fun valueFor(
    isProcessing: Boolean,
    inferenceSequence: Long,
    latestSuccessfulLatency: SuccessfulInferenceLatencySnapshot,
  ): Long {
    if (!isProcessing) return latchedLatencyMs

    if (processingSequence != inferenceSequence) {
      processingSequence = inferenceSequence
      latchedSuccessfulSequence = 0L
      latchedLatencyMs = 0L
    }
    if (
      latestSuccessfulLatency.inferenceSequence in (latchedSuccessfulSequence + 1) until inferenceSequence
    ) {
      latchedSuccessfulSequence = latestSuccessfulLatency.inferenceSequence
      latchedLatencyMs = latestSuccessfulLatency.latencyMs.coerceAtLeast(0)
    }
    return latchedLatencyMs
  }
}
