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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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

  /** Owns the dispatch/cancel boundary for one request. */
  private class InferenceExecution(
    private val cancelNative: () -> Unit,
  ) {
    private val stateLock = Any()
    private var phase = ExecutionPhase.QUEUED

    fun beginPreparation(): Boolean = synchronized(stateLock) {
      if (phase != ExecutionPhase.QUEUED) return@synchronized false
      phase = ExecutionPhase.PREPARING
      true
    }

    fun dispatch(startNative: () -> Unit): Boolean = synchronized(stateLock) {
      if (phase != ExecutionPhase.PREPARING) return@synchronized false
      phase = ExecutionPhase.DISPATCHING
      try {
        startNative()
        phase = ExecutionPhase.RUNNING
        true
      } catch (t: Throwable) {
        phase = ExecutionPhase.SETTLING
        throw t
      }
    }

    fun cancel() {
      synchronized(stateLock) {
        when (phase) {
          ExecutionPhase.QUEUED,
          ExecutionPhase.PREPARING -> phase = ExecutionPhase.CANCELLED_BEFORE_DISPATCH

          ExecutionPhase.RUNNING -> {
            phase = ExecutionPhase.CANCELLING
            cancelNative()
          }

          ExecutionPhase.DISPATCHING,
          ExecutionPhase.CANCELLING,
          ExecutionPhase.SETTLING,
          ExecutionPhase.FINISHED,
          ExecutionPhase.CANCELLED_BEFORE_DISPATCH -> Unit
        }
      }
    }

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
  ) {
    executor.execute {
      synchronized(inferenceLock) {
        val latch = CountDownLatch(1)
        var errorOccurred = false
        try {
          resetConversation()
          runInference(
            prompt,
            { partial, done, thought ->
              onToken(partial, done, thought)
              if (done) latch.countDown()
            },
            { e ->
              errorOccurred = true
              onError(e)
              try { cancelInference() } catch (t: Throwable) {
                Log.w(TAG, "cancelInference() failed during error callback cleanup", t)
              }
              latch.countDown()
            },
          )
          val completed = latch.await(timeoutSeconds, TimeUnit.SECONDS)
          if (!completed && !errorOccurred) {
            onError("timeout")
            cancelInference()
            // Safe: entire block holds inferenceLock, so no concurrent inference can start
            // between cancelInference() and resetConversation().
            resetConversation()
          }
        } catch (t: Throwable) {
          // Reclaim memory before reporting the error if OOM
          if (t is OutOfMemoryError) System.gc()
          onCaughtThrowable?.invoke(t)
          if (!errorOccurred) {
            onError(t.message ?: "unknown_error")
            try { cancelInference() } catch (t2: Throwable) {
              Log.w(TAG, "cancelInference() failed during exception recovery", t2)
            }
          }
        } finally {
          try { onInferenceFinished() } catch (t: Throwable) {
            Log.w(TAG, "onInferenceFinished() failed", t)
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
    earlyUnblock: ((CountDownLatch) -> Unit)? = null,
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
    earlyUnblock = earlyUnblock,
  )

  internal suspend fun execute(
    timeoutSeconds: Long = BLOCKING_TIMEOUT_SECONDS,
    executor: Executor,
    inferenceLock: Any,
    operation: NativeOperation,
    elapsedMs: () -> Long,
    onCaughtThrowable: ((Throwable) -> Unit)? = null,
    earlyUnblock: ((CountDownLatch) -> Unit)? = null,
  ): InferenceResult {
    val sb = StringBuilder()
    val thinkingSb = StringBuilder()
    val inferenceLatch = CountDownLatch(1)
    val lifecycleLatch = CountDownLatch(1)
    val execution = InferenceExecution(operation::cancel)
    earlyUnblock?.invoke(lifecycleLatch)
    val error = AtomicReference<String?>(null)
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
                if (partial.isNotEmpty()) {
                  if (firstTokenMs == null) {
                    firstTokenMs = elapsedMs() - startMs
                  }
                  sb.append(partial)
                }
                if (!thought.isNullOrEmpty()) {
                  thinkingSb.append(thought)
                }
                if (done) inferenceLatch.countDown()
              },
              { e -> error.compareAndSet(null, e); inferenceLatch.countDown() },
            )
          }
          if (!dispatched) return@synchronized
          val completed = inferenceLatch.await(timeoutSeconds, TimeUnit.SECONDS)
          if (!completed && error.get() == null) {
            error.compareAndSet(null, "timeout")
            execution.cancel()
            if (execution.beginRecovery()) operation.recover()
          } else if (error.get() != null) {
            execution.cancel()
            if (execution.beginRecovery()) operation.recover()
          }
        } catch (t: Throwable) {
          if (t is OutOfMemoryError) System.gc()
          onCaughtThrowable?.invoke(t)
          error.compareAndSet(null, t.message ?: "unknown_error")
          inferenceLatch.countDown()
        } finally {
          try { operation.finish() } catch (t: Throwable) {
            Log.w(TAG, "NativeOperation.finish() failed", t)
          }
          execution.finish()
          lifecycleLatch.countDown()
        }
      }
    }

    try {
      withContext(Dispatchers.IO) {
        runInterruptible {
          val completed = lifecycleLatch.await(timeoutSeconds + 5, TimeUnit.SECONDS)
          if (!completed) {
            error.compareAndSet(null, "timeout")
          }
        }
      }
    } catch (_: InterruptedException) {
      error.compareAndSet(null, "client_disconnected")
      inferenceLatch.countDown()
      execution.cancel()
      withContext(NonCancellable + Dispatchers.IO) {
        lifecycleLatch.await(timeoutSeconds + 5, TimeUnit.SECONDS)
      }
    } catch (_: CancellationException) {
      error.compareAndSet(null, "client_disconnected")
      inferenceLatch.countDown()
      execution.cancel()
      withContext(NonCancellable + Dispatchers.IO) {
        lifecycleLatch.await(timeoutSeconds + 5, TimeUnit.SECONDS)
      }
    }
    val totalMs = elapsedMs() - startMs
    val thinkingResult = thinkingSb.toString().takeIf { it.isNotEmpty() }
    val finalError = error.get()
    // On error, discard all accumulated tokens — SDK errors may leave the output buffer
    // in a corrupted/incomplete state. The streaming path (executeStreaming) preserves
    // partial output because tokens are already delivered to the client via onToken callbacks.
    return InferenceResult(
      output = if (finalError != null) null else sb.toString(),
      thinking = if (finalError != null) null else thinkingResult,
      error = finalError,
      totalMs = totalMs,
      ttfbMs = firstTokenMs ?: -1,
    )
  }
}
