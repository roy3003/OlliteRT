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

private const val MAX_DISPOSE_DETACH_ATTEMPTS = 2

data class FloatingMonitorRenderModel(
  val visualState: FloatingMonitorVisualState,
  val requestValue: String,
  val secondaryValue: String,
  val secondaryLabel: String,
)

fun deriveFloatingMonitorRenderModel(
  visualState: FloatingMonitorVisualState,
  requestCount: Long,
  errorCount: Long,
  processingElapsedMillis: Long?,
): FloatingMonitorRenderModel? {
  if (visualState == FloatingMonitorVisualState.Hidden) return null

  return FloatingMonitorRenderModel(
    visualState = visualState,
    requestValue = formatFloatingMonitorCount(requestCount),
    secondaryValue = when (visualState) {
      FloatingMonitorVisualState.Running -> formatFloatingMonitorCount(errorCount)
      FloatingMonitorVisualState.Processing -> formatProcessingElapsed(processingElapsedMillis ?: 0)
      FloatingMonitorVisualState.Hidden -> error("Hidden was handled above")
    },
    secondaryLabel = when (visualState) {
      FloatingMonitorVisualState.Running -> "err"
      FloatingMonitorVisualState.Processing -> "proc"
      FloatingMonitorVisualState.Hidden -> error("Hidden was handled above")
    },
  )
}

interface FloatingMonitorWindowPort {
  val isAttached: Boolean

  fun attach(model: FloatingMonitorRenderModel)

  fun update(model: FloatingMonitorRenderModel)

  fun detach()
}

class FloatingMonitorWindowReconciler(
  private val window: FloatingMonitorWindowPort,
  private val onFailure: (RuntimeException) -> Unit = {},
) {
  private var disposed = false

  fun reconcile(model: FloatingMonitorRenderModel?) {
    if (disposed) return

    if (model == null) {
      detachIfAttached()
      return
    }

    if (!window.isAttached) {
      try {
        window.attach(model)
      } catch (e: RuntimeException) {
        reportFailure(e)
      }
      return
    }

    try {
      window.update(model)
    } catch (e: RuntimeException) {
      reportFailure(e)
      detachIfAttached()
    }
  }

  fun dispose() {
    if (disposed) return
    var attempts = 0
    while (window.isAttached && attempts < MAX_DISPOSE_DETACH_ATTEMPTS) {
      detachIfAttached()
      attempts += 1
    }
    disposed = true
  }

  private fun detachIfAttached() {
    if (!window.isAttached) return
    try {
      window.detach()
    } catch (e: RuntimeException) {
      reportFailure(e)
    }
  }

  private fun reportFailure(failure: RuntimeException) {
    try {
      onFailure(failure)
    } catch (_: RuntimeException) {
      // Diagnostics must not turn a disposable overlay failure into a server failure.
    }
  }
}
