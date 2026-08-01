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

package com.ollitert.llm.server.service

import android.util.Log
import com.ollitert.llm.server.data.BLOCKING_TIMEOUT_SECONDS
import com.ollitert.llm.server.data.STREAMING_TIMEOUT_SECONDS
import com.ollitert.llm.server.service.InferenceGateway.executeStreaming
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

data class InferenceResult(
  val output: String?,
  val thinking: String?,
  val error: String?,
  val totalMs: Long,
  val ttfbMs: Long,
)

typealias InferenceFn = (
  prompt: String,
  onPartial: (partial: String, done: Boolean, thought: String?) -> Unit,
  onError: (message: String) -> Unit,
) -> Unit

private const val TAG = "OlliteRT.Gateway"

object InferenceGateway {

  internal interface NativeOperation {
    fun prepare()

    fun dispatch(
      onPartial: (partial: String, done: Boolean, thought: String?) -> Unit,
      onError: (message: String) -> Unit,
    )

    fun cancel()

    fun recover()

    fun finish()
  }

  internal enum class CancellationReason {
    CALLER,
    EXTERNAL,
    STOP_SEQUENCE,
  }

  internal sealed interface ExecutionOutcome {
    data object Success : ExecutionOutcome
    data object Timeout : ExecutionOutcome
    data object CallerCancelled : ExecutionOutcome
    data object ExternalCancelled : ExecutionOutcome
    data object StopSequence : ExecutionOutcome
    data class Error(val message: String) : ExecutionOutcome
  }

  private enum class ExecutionPhase {
    QUEUED,
    PREPARING,
    DISPATCHING,
    RUNNING,
    CANCELLING,
    SETTLING,
    FINISHED,
    CANCELLED_BEFORE_DISPATCH,
  }

  /** Owns the phase and single terminal outcome for one request. */
  internal class InferenceExecution(
    private val cancelNative: () -> Unit,
  ) {
    private val stateLock = Any()
    private var phase = ExecutionPhase.QUEUED
    private var terminalOutcome: ExecutionOutcome? = null

    fun beginPreparation(): Boolean = synchronized(stateLock) {
      if (phase != ExecutionPhase.QUEUED || terminalOutcome != null) return@synchronized false
      phase = ExecutionPhase.PREPARING
      true
    }

    fun dispatch(startNative: () -> Unit): Boolean {
      var cancelAfterDispatch = false
      synchronized(stateLock) {
        if (phase != ExecutionPhase.PREPARING || terminalOutcome != null) return false
        phase = ExecutionPhase.DISPATCHING
        try {
          startNative()
        } catch (t: Throwable) {
          phase = ExecutionPhase.SETTLING
          throw t
        }
        val outcome = terminalOutcome
        phase = when {
          outcome == null -> ExecutionPhase.RUNNING
          outcome.requiresNativeCancellation() -> {
            cancelAfterDispatch = true
            ExecutionPhase.CANCELLING
          }
          else -> ExecutionPhase.SETTLING
        }
      }
      if (cancelAfterDispatch) cancelNative()
      return true
    }

    fun trySetOutcome(outcome: ExecutionOutcome): Boolean {
      var cancelNow = false
      synchronized(stateLock) {
        if (terminalOutcome != null || phase == ExecutionPhase.FINISHED) return false
        terminalOutcome = outcome
        phase = when (phase) {
          ExecutionPhase.QUEUED,
          ExecutionPhase.PREPARING -> if (outcome.requiresNativeCancellation()) {
            ExecutionPhase.CANCELLED_BEFORE_DISPATCH
          } else {
            ExecutionPhase.SETTLING
          }

          ExecutionPhase.DISPATCHING -> ExecutionPhase.DISPATCHING
          ExecutionPhase.RUNNING -> if (outcome.requiresNativeCancellation()) {
            cancelNow = true
            ExecutionPhase.CANCELLING
          } else {
            ExecutionPhase.SETTLING
          }

          ExecutionPhase.SETTLING -> ExecutionPhase.SETTLING
          ExecutionPhase.CANCELLING,
          ExecutionPhase.FINISHED,
          ExecutionPhase.CANCELLED_BEFORE_DISPATCH -> return false
        }
      }
      if (cancelNow) cancelNative()
      return true
    }

    fun outcome(): ExecutionOutcome? = synchronized(stateLock) { terminalOutcome }

    fun beginRecovery(): Boolean = synchronized(stateLock) {
      if (phase != ExecutionPhase.CANCELLING) return@synchronized false
      phase = ExecutionPhase.SETTLING
      true
    }

    fun finish() {
      synchronized(stateLock) {
        phase = ExecutionPhase.FINISHED
      }
    }
  }

  private fun CancellationReason.toOutcome(): ExecutionOutcome = when (this) {
    CancellationReason.CALLER -> ExecutionOutcome.CallerCancelled
    CancellationReason.EXTERNAL -> ExecutionOutcome.ExternalCancelled
    CancellationReason.STOP_SEQUENCE -> ExecutionOutcome.StopSequence
  }

  private fun ExecutionOutcome.requiresNativeCancellation(): Boolean =
    this !is ExecutionOutcome.Success

  private fun ExecutionOutcome.errorMessage(): String? = when (this) {
    ExecutionOutcome.Success,
    ExecutionOutcome.StopSequence -> null
    ExecutionOutcome.Timeout -> "timeout"
    ExecutionOutcome.CallerCancelled,
    ExecutionOutcome.ExternalCancelled -> "client_disconnected"
    is ExecutionOutcome.Error -> message
  }

  private fun ExecutionOutcome.isSuccessful(): Boolean =
    this is ExecutionOutcome.Success || this is ExecutionOutcome.StopSequence

  /**
   * Fires inference on [executor] and delivers tokens via [onToken] as they arrive.
   * Returns immediately; the caller receives the stream via [onToken]/[onError] callbacks.
   * [onToken] is called with (partial, done, thought) for each token and (*, true, *) once when done.
   * [onError] is called instead of [onToken] if inference fails.
   *
   * @param onCaughtThrowable Optional callback invoked with the full [Throwable] when an
   *   exception is caught during inference. Used by [ServerService] to emit verbose debug
   *   stack traces when debug mode is enabled. The gateway itself only forwards [Throwable.message]
   *   via [onError] — this callback preserves the full stack trace for diagnostics.
   */

  fun executeStreaming(
    prompt: String,
    timeoutSeconds: Long = STREAMING_TIMEOUT_SECONDS,
    executor: Executor,
    inferenceLock: Any,
    resetConversation: () -> Unit,
    runInference: InferenceFn,
    cancelInference: () -> Unit,
    onToken: (partial: String, done: Boolean, thought: String?) -> Unit,
    onError: (error: String) -> Unit,
    onInferenceFinished: () -> Unit = {},
    onCaughtThrowable: ((Throwable) -> Unit)? = null,
  ) = executeStreaming(
    timeoutSeconds = timeoutSeconds,
    executor = executor,
    inferenceLock = inferenceLock,
    operation = object : NativeOperation {
      override fun prepare() = resetConversation()

      override fun dispatch(
        onPartial: (partial: String, done: Boolean, thought: String?) -> Unit,
        onError: (message: String) -> Unit,
      ) = runInference(prompt, onPartial, onError)

      override fun cancel() = cancelInference()

      override fun recover() = Unit

      override fun finish() = onInferenceFinished()
    },
    onToken = onToken,
    onError = onError,
    onCaughtThrowable = onCaughtThrowable,
  )

  internal fun executeStreaming(
    timeoutSeconds: Long = STREAMING_TIMEOUT_SECONDS,
    executor: Executor,
    inferenceLock: Any,
    operation: NativeOperation,
    onToken: (partial: String, done: Boolean, thought: String?) -> Unit,
    onError: (error: String) -> Unit,
    onCaughtThrowable: ((Throwable) -> Unit)? = null,
    onCancellationReady: ((cancel: (CancellationReason) -> Unit) -> Unit)? = null,
  ) {
    val nativeCompletion = CountDownLatch(1)
    val execution = InferenceExecution(operation::cancel)
    onCancellationReady?.invoke { reason ->
      if (execution.trySetOutcome(reason.toOutcome())) nativeCompletion.countDown()
    }

    executor.execute {
      synchronized(inferenceLock) {
        try {
          if (!execution.beginPreparation()) return@synchronized
          operation.prepare()
          if (!execution.dispatch {
              operation.dispatch(
                { partial, done, thought ->
                  if (done) {
                    if (execution.trySetOutcome(ExecutionOutcome.Success)) {
                      onToken(partial, true, thought)
                      nativeCompletion.countDown()
                    }
                  } else if (execution.outcome() == null) {
                    onToken(partial, false, thought)
                  }
                },
                { error ->
                  if (execution.trySetOutcome(ExecutionOutcome.Error(error))) {
                    onError(error)
                    nativeCompletion.countDown()
                  }
                },
              )
            }) return@synchronized

          val completed = nativeCompletion.await(timeoutSeconds, TimeUnit.SECONDS)
          if (!completed && execution.trySetOutcome(ExecutionOutcome.Timeout)) {
            onError("timeout")
          }
          if (execution.beginRecovery()) operation.recover()
        } catch (t: Throwable) {
          if (t is OutOfMemoryError) System.gc()
          onCaughtThrowable?.invoke(t)
          val message = t.message ?: "unknown_error"
          if (execution.trySetOutcome(ExecutionOutcome.Error(message))) {
            onError(message)
            nativeCompletion.countDown()
          }
          if (execution.beginRecovery()) operation.recover()
        } finally {
          execution.finish()
          try {
            operation.finish()
          } catch (t: Throwable) {
            Log.w(TAG, "Streaming inference finish failed", t)
          }
        }
      }
    }
  }

  /**
   * @param onCaughtThrowable Optional callback invoked with the full [Throwable] when an
   *   exception is caught during inference. See [executeStreaming] for details.
   */
  suspend fun execute(
    prompt: String,
    timeoutSeconds: Long = BLOCKING_TIMEOUT_SECONDS,
    executor: Executor,
    inferenceLock: Any,
    resetConversation: () -> Unit,
    runInference: InferenceFn,
    cancelInference: () -> Unit,
    onInferenceFinished: () -> Unit = {},
    elapsedMs: () -> Long,
    onCaughtThrowable: ((Throwable) -> Unit)? = null,
    onCancellationReady: ((cancel: () -> Unit) -> Unit)? = null,
  ): InferenceResult = execute(
    timeoutSeconds = timeoutSeconds,
    executor = executor,
    inferenceLock = inferenceLock,
    operation = object : NativeOperation {
      override fun prepare() = resetConversation()

      override fun dispatch(
        onPartial: (partial: String, done: Boolean, thought: String?) -> Unit,
        onError: (message: String) -> Unit,
      ) = runInference(prompt, onPartial, onError)

      override fun cancel() = cancelInference()

      override fun recover() = Unit

      override fun finish() = onInferenceFinished()
    },
    elapsedMs = elapsedMs,
    onCaughtThrowable = onCaughtThrowable,
    onCancellationReady = if (onCancellationReady == null) null else { cancel ->
      onCancellationReady.invoke { cancel(CancellationReason.EXTERNAL) }
    },
  )

  internal suspend fun execute(
    timeoutSeconds: Long = BLOCKING_TIMEOUT_SECONDS,
    executor: Executor,
    inferenceLock: Any,
    operation: NativeOperation,
    elapsedMs: () -> Long,
    onCaughtThrowable: ((Throwable) -> Unit)? = null,
    onCancellationReady: ((cancel: (CancellationReason) -> Unit) -> Unit)? = null,
  ): InferenceResult {
    val sb = StringBuilder()
    val thinkingSb = StringBuilder()
    val nativeCompletion = CountDownLatch(1)
    val lifecycleFinished = CountDownLatch(1)
    val execution = InferenceExecution(operation::cancel)
    onCancellationReady?.invoke { reason ->
      if (execution.trySetOutcome(reason.toOutcome())) nativeCompletion.countDown()
    }
    val startMs = elapsedMs()
    var firstTokenMs: Long? = null

    executor.execute {
      synchronized(inferenceLock) {
        try {
          if (!execution.beginPreparation()) return@synchronized
          operation.prepare()
          val dispatched = execution.dispatch {
            operation.dispatch(
              { partial, done, thought ->
                if (done) {
                  if (execution.trySetOutcome(ExecutionOutcome.Success)) {
                    if (partial.isNotEmpty()) {
                      if (firstTokenMs == null) firstTokenMs = elapsedMs() - startMs
                      sb.append(partial)
                    }
                    if (!thought.isNullOrEmpty()) thinkingSb.append(thought)
                    nativeCompletion.countDown()
                  }
                } else if (execution.outcome() == null) {
                  if (partial.isNotEmpty()) {
                    if (firstTokenMs == null) firstTokenMs = elapsedMs() - startMs
                    sb.append(partial)
                  }
                  if (!thought.isNullOrEmpty()) thinkingSb.append(thought)
                }
              },
              { message ->
                if (execution.trySetOutcome(ExecutionOutcome.Error(message))) {
                  nativeCompletion.countDown()
                }
              },
            )
          }
          if (!dispatched) return@synchronized
          val completed = nativeCompletion.await(timeoutSeconds, TimeUnit.SECONDS)
          if (!completed) execution.trySetOutcome(ExecutionOutcome.Timeout)
          if (execution.beginRecovery()) operation.recover()
        } catch (t: Throwable) {
          if (t is OutOfMemoryError) System.gc()
          onCaughtThrowable?.invoke(t)
          val message = t.message ?: "unknown_error"
          if (execution.trySetOutcome(ExecutionOutcome.Error(message))) {
            nativeCompletion.countDown()
          }
          if (execution.beginRecovery()) operation.recover()
        } finally {
          try { operation.finish() } catch (t: Throwable) {
            Log.w(TAG, "NativeOperation.finish() failed", t)
          }
          execution.finish()
          lifecycleFinished.countDown()
        }
      }
    }

    try {
      withContext(Dispatchers.IO) {
        runInterruptible {
          val completed = lifecycleFinished.await(timeoutSeconds + 5, TimeUnit.SECONDS)
          if (!completed && execution.trySetOutcome(ExecutionOutcome.Timeout)) {
            nativeCompletion.countDown()
          }
        }
      }
    } catch (_: InterruptedException) {
      if (execution.trySetOutcome(ExecutionOutcome.CallerCancelled)) {
        nativeCompletion.countDown()
      }
      lifecycleFinished.await(timeoutSeconds + 5, TimeUnit.SECONDS)
    } catch (_: CancellationException) {
      if (execution.trySetOutcome(ExecutionOutcome.CallerCancelled)) {
        nativeCompletion.countDown()
      }
      lifecycleFinished.await(timeoutSeconds + 5, TimeUnit.SECONDS)
    }
    val totalMs = elapsedMs() - startMs
    val thinkingResult = thinkingSb.toString().takeIf { it.isNotEmpty() }
    val finalOutcome = execution.outcome()
    val finalError = finalOutcome?.errorMessage() ?: if (finalOutcome == null) "unknown_error" else null
    val successful = finalOutcome?.isSuccessful() == true
    // On error, discard all accumulated tokens — SDK errors may leave the output buffer
    // in a corrupted/incomplete state. The streaming path (executeStreaming) preserves
    // partial output because tokens are already delivered to the client via onToken callbacks.
    return InferenceResult(
      output = if (successful) sb.toString() else null,
      thinking = if (successful) thinkingResult else null,
      error = finalError,
      totalMs = totalMs,
      ttfbMs = firstTokenMs ?: -1,
    )
  }
}
