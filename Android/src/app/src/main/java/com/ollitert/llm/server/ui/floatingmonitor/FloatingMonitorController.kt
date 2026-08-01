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

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.ollitert.llm.server.MainActivity
import com.ollitert.llm.server.OlliteRTLifecycleProvider
import com.ollitert.llm.server.common.ServerStatus
import com.ollitert.llm.server.service.ServerMetrics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class FloatingMonitorController(
  context: Context,
  private val lifecycleProvider: OlliteRTLifecycleProvider,
  private val permissionCoordinator: FloatingMonitorPermissionCoordinator,
  private val settingEnabled: Boolean,
) {
  private val appContext = context.applicationContext
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val elapsedTracker = ProcessingElapsedTracker(SystemClock::elapsedRealtime)
  private val previousSuccessfulLatencyLatch = PreviousSuccessfulLatencyLatch()
  private lateinit var reconciler: FloatingMonitorWindowReconciler
  private val window = AndroidFloatingMonitorWindow(appContext) { handleTap() }
  private val tapCoordinator = FloatingMonitorTapCoordinator(
    scope = scope,
    timeoutMillis = TAP_SUPPRESSION_TIMEOUT_MILLIS,
    detach = { reconciler.reconcile(null) },
    launch = { openMainActivity() },
    reconcile = { reconcileAfterTapSuppression() },
  )
  private var monitorJob: Job? = null
  private var latestInput: CoreInput? = null
  private var disposed = false

  init {
    reconciler = FloatingMonitorWindowReconciler(window) { failure ->
      Log.w(TAG, "Floating monitor window operation failed", failure)
    }
  }

  fun start() {
    if (disposed || monitorJob != null) return
    monitorJob = scope.launch {
      try {
        val coreInputs = combine(
          ServerMetrics.status,
          ServerMetrics.isInferring,
          ServerMetrics.inferenceSequence,
          lifecycleProvider.isAppInForeground,
          permissionCoordinator.permissionFlowInProgress,
        ) { status, isInferring, inferenceSequence, appIsForeground, permissionFlowInProgress ->
          CoreInput(
            status = status,
            isInferring = isInferring,
            inferenceSequence = inferenceSequence,
            appIsForeground = appIsForeground,
            permissionFlowInProgress = permissionFlowInProgress,
            launchSuppressionActive = false,
          )
        }
        combine(coreInputs, tapCoordinator.suppressionActive) { input, launchSuppressionActive ->
          input.copy(launchSuppressionActive = launchSuppressionActive)
        }.collectLatest { input ->
          latestInput = input
          if (input.appIsForeground) tapCoordinator.onAppForegrounded()
          val retryBudget = FloatingMonitorRetryBudget(MAX_CONSECUTIVE_WINDOW_FAILURES)
          elapsedTracker.update(input.isInferring, input.inferenceSequence)
          if (!render(input, retryBudget)) return@collectLatest

          while (currentCoroutineContext().isActive) {
            delay(METRIC_REFRESH_MILLIS)
            if (!render(input, retryBudget)) return@collectLatest
          }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: RuntimeException) {
        Log.w(TAG, "Floating monitor observer stopped after a contained failure", e)
        reconciler.reconcile(null)
      }
    }
  }

  fun dispose() {
    if (disposed) return
    disposed = true
    monitorJob?.cancel()
    monitorJob = null
    tapCoordinator.dispose()
    scope.cancel()
    elapsedTracker.dispose()
    reconciler.dispose()
  }

  private fun render(input: CoreInput, retryBudget: FloatingMonitorRetryBudget): Boolean {
    val visualState = deriveFloatingMonitorVisualState(
      status = input.status,
      isInferring = input.isInferring,
    )
    val previousSuccessfulLatencyMs = previousSuccessfulLatencyLatch.valueFor(
      isProcessing = visualState == FloatingMonitorVisualState.Processing,
      inferenceSequence = input.inferenceSequence,
      liveLatencyMs = ServerMetrics.lastLatencyMs.value,
    )
    val permissionGranted = try {
      Settings.canDrawOverlays(appContext)
    } catch (e: RuntimeException) {
      Log.w(TAG, "Unable to evaluate overlay permission", e)
      false
    }
    val visible = shouldShowFloatingMonitor(
      settingEnabled = settingEnabled,
      overlayPermissionGranted = permissionGranted,
      permissionFlowInProgress = input.permissionFlowInProgress,
      appIsForeground = input.appIsForeground,
      launchSuppressionActive = input.launchSuppressionActive,
      serviceIsAlive = !disposed,
      visualState = visualState,
    )

    val model = if (visible) {
      deriveFloatingMonitorRenderModel(
        visualState = visualState,
        requestCount = ServerMetrics.requestCount.value,
        errorCount = ServerMetrics.errorCount.value,
        processingElapsedMillis = elapsedTracker.elapsedMillis(),
        previousSuccessfulLatencyMs = previousSuccessfulLatencyMs,
      )
    } else {
      null
    }
    val reconciled = reconciler.reconcile(model)
    val retryAllowed = retryBudget.record(reconciled)
    if (!reconciled && !retryAllowed) {
      Log.w(TAG, "Floating monitor retry budget exhausted; waiting for a state change")
    }
    return shouldContinueFloatingMonitorReconciliation(
      modelVisible = model != null,
      reconciled = reconciled,
      retryAllowed = retryAllowed,
    )
  }

  private fun handleTap() {
    if (disposed) return
    tapCoordinator.handleTap()
  }

  private fun reconcileAfterTapSuppression() {
    if (disposed) return
    val input = latestInput ?: return
    render(
      input = input.copy(launchSuppressionActive = false),
      retryBudget = FloatingMonitorRetryBudget(MAX_CONSECUTIVE_WINDOW_FAILURES),
    )
  }

  private fun openMainActivity(): Boolean {
    val intent = Intent(appContext, MainActivity::class.java).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
    try {
      PendingIntent.getActivity(
        appContext,
        OPEN_ACTIVITY_REQUEST_CODE,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      ).send()
      return true
    } catch (e: PendingIntent.CanceledException) {
      Log.w(TAG, "Floating monitor PendingIntent was cancelled; using direct fallback", e)
    } catch (e: RuntimeException) {
      Log.w(TAG, "Unable to create or send floating monitor PendingIntent", e)
    }

    return try {
      appContext.startActivity(intent)
      true
    } catch (e: RuntimeException) {
      Log.w(TAG, "Unable to open MainActivity from floating monitor", e)
      false
    }
  }

  private data class CoreInput(
    val status: ServerStatus,
    val isInferring: Boolean,
    val inferenceSequence: Long,
    val appIsForeground: Boolean,
    val permissionFlowInProgress: Boolean,
    val launchSuppressionActive: Boolean,
  )

  private companion object {
    const val TAG = "OlliteRT.FloatMonitor"
    const val METRIC_REFRESH_MILLIS = 1_000L
    const val TAP_SUPPRESSION_TIMEOUT_MILLIS = 3_000L
    const val MAX_CONSECUTIVE_WINDOW_FAILURES = 3
    const val OPEN_ACTIVITY_REQUEST_CODE = 72
  }
}
