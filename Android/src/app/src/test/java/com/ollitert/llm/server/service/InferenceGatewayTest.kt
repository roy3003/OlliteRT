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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class InferenceGatewayTest {

  private val directExecutor = Executor { it.run() }
  private val lock = Any()
  private var clock = 0L
  private fun tick(): Long { clock += 10; return clock }

  @Test
  fun successfulInferenceReturnsOutput() = runBlocking {
    val result = InferenceGateway.execute(
      prompt = "hello",
      timeoutSeconds = 5,
      executor = directExecutor,
      inferenceLock = lock,
      resetConversation = {},
      runInference = { _, onPartial, _ ->
        onPartial("world", false, null)
        onPartial("", true, null)
      },
      cancelInference = {},
      elapsedMs = { tick() },
    )
    assertEquals("world", result.output)
    assertNull(result.error)
    assertTrue(result.ttfbMs >= 0)
  }

  @Test
  fun multiplePartialsAccumulate() = runBlocking {
    val result = InferenceGateway.execute(
      prompt = "hi",
      timeoutSeconds = 5,
      executor = directExecutor,
      inferenceLock = lock,
      resetConversation = {},
      runInference = { _, onPartial, _ ->
        onPartial("a", false, null)
        onPartial("b", false, null)
        onPartial("c", false, null)
        onPartial("", true, null)
      },
      cancelInference = {},
      elapsedMs = { tick() },
    )
    assertEquals("abc", result.output)
    assertNull(result.error)
  }

  @Test
  fun errorFromInferenceIsReported() = runBlocking {
    val result = InferenceGateway.execute(
      prompt = "fail",
      timeoutSeconds = 5,
      executor = directExecutor,
      inferenceLock = lock,
      resetConversation = {},
      runInference = { _, _, onError ->
        onError("model crashed")
      },
      cancelInference = {},
      elapsedMs = { tick() },
    )
    assertNull(result.output)
    assertEquals("model crashed", result.error)
  }

  @Test
  fun exceptionDuringInferenceIsCaught() = runBlocking {
    val result = InferenceGateway.execute(
      prompt = "boom",
      timeoutSeconds = 5,
      executor = directExecutor,
      inferenceLock = lock,
      resetConversation = { throw RuntimeException("reset failed") },
      runInference = { _, _, _ -> },
      cancelInference = {},
      elapsedMs = { tick() },
    )
    assertNull(result.output)
    assertNotNull(result.error)
    assertTrue(result.error.orEmpty().contains("reset failed"))
  }

  @Test
  fun exceptionWithNullMessageReportsUnknownError() = runBlocking {
    val result = InferenceGateway.execute(
      prompt = "null-msg",
      timeoutSeconds = 5,
      executor = directExecutor,
      inferenceLock = lock,
      resetConversation = { throw object : RuntimeException(null as String?) {} },
      runInference = { _, _, _ -> },
      cancelInference = {},
      elapsedMs = { tick() },
    )
    assertNull(result.output)
    assertEquals("unknown_error", result.error)
  }

  @Test
  fun timeoutPreparesInferenceOnlyOnce() = runBlocking {
    val preparationCalls = AtomicInteger(0)

    val result = InferenceGateway.execute(
      prompt = "long",
      timeoutSeconds = 0,
      executor = directExecutor,
      inferenceLock = lock,
      resetConversation = { preparationCalls.incrementAndGet() },
      runInference = { _, _, _ -> /* never completes */ },
      cancelInference = {},
      elapsedMs = { 0L },
    )

    assertEquals("timeout", result.error)
    assertEquals(
      "timeout recovery must not repeat request preparation",
      1,
      preparationCalls.get(),
    )
  }

  @Test
  fun cancelInferenceCalledOnError() = runBlocking {
    var cancelled = false
    InferenceGateway.execute(
      prompt = "x",
      timeoutSeconds = 5,
      executor = directExecutor,
      inferenceLock = lock,
      resetConversation = {},
      runInference = { _, _, onError -> onError("err") },
      cancelInference = { cancelled = true },
      elapsedMs = { tick() },
    )
    assertTrue(cancelled)
  }

  @Test
  fun emptyPartialDoesNotCountAsTtfb() = runBlocking {
    val result = InferenceGateway.execute(
      prompt = "x",
      timeoutSeconds = 5,
      executor = directExecutor,
      inferenceLock = lock,
      resetConversation = {},
      runInference = { _, onPartial, _ ->
        onPartial("", false, null)
        onPartial("tok", false, null)
        onPartial("", true, null)
      },
      cancelInference = {},
      elapsedMs = { tick() },
    )
    assertEquals("tok", result.output)
    assertTrue(result.ttfbMs > 0)
  }

  @Test
  fun totalMsIsTracked() = runBlocking {
    clock = 0
    val result = InferenceGateway.execute(
      prompt = "x",
      timeoutSeconds = 5,
      executor = directExecutor,
      inferenceLock = lock,
      resetConversation = {},
      runInference = { _, onPartial, _ ->
        onPartial("ok", false, null)
        onPartial("", true, null)
      },
      cancelInference = {},
      elapsedMs = { tick() },
    )
    assertTrue(result.totalMs > 0)
  }

  @Test
  fun thinkingContentAccumulates() = runBlocking {
    val result = InferenceGateway.execute(
      prompt = "think",
      timeoutSeconds = 5,
      executor = directExecutor,
      inferenceLock = lock,
      resetConversation = {},
      runInference = { _, onPartial, _ ->
        onPartial("", false, "step1 ")
        onPartial("", false, "step2")
        onPartial("answer", false, null)
        onPartial("", true, null)
      },
      cancelInference = {},
      elapsedMs = { tick() },
    )
    assertEquals("answer", result.output)
    assertEquals("step1 step2", result.thinking)
  }

  @Test
  fun noThinkingReturnsNull() = runBlocking {
    val result = InferenceGateway.execute(
      prompt = "no-think",
      timeoutSeconds = 5,
      executor = directExecutor,
      inferenceLock = lock,
      resetConversation = {},
      runInference = { _, onPartial, _ ->
        onPartial("plain", false, null)
        onPartial("", true, null)
      },
      cancelInference = {},
      elapsedMs = { tick() },
    )
    assertEquals("plain", result.output)
    assertNull(result.thinking)
  }

  // ── execute() onInferenceFinished tests ──────────────────────────────────

  @Test
  fun blockingOnInferenceFinishedCalledInsideLock() = runBlocking {
    var finishedCalled = false
    var lockHeldDuringFinished = false
    InferenceGateway.execute(
      prompt = "x",
      timeoutSeconds = 5,
      executor = directExecutor,
      inferenceLock = lock,
      resetConversation = {},
      runInference = { _, onPartial, _ ->
        onPartial("tok", false, null)
        onPartial("", true, null)
      },
      cancelInference = {},
      onInferenceFinished = {
        finishedCalled = true
        lockHeldDuringFinished = Thread.holdsLock(lock)
      },
      elapsedMs = { tick() },
    )
    assertTrue("onInferenceFinished must be called", finishedCalled)
    assertTrue("onInferenceFinished must run inside inferenceLock", lockHeldDuringFinished)
  }

  @Test
  fun blockingOnInferenceFinishedCalledOnError() = runBlocking {
    var finishedCalled = false
    InferenceGateway.execute(
      prompt = "x",
      timeoutSeconds = 5,
      executor = directExecutor,
      inferenceLock = lock,
      resetConversation = {},
      runInference = { _, _, onError -> onError("boom") },
      cancelInference = {},
      onInferenceFinished = { finishedCalled = true },
      elapsedMs = { tick() },
    )
    assertTrue("onInferenceFinished must be called on error path", finishedCalled)
  }

  @Test
  fun blockingOnInferenceFinishedCalledOnException() = runBlocking {
    var finishedCalled = false
    InferenceGateway.execute(
      prompt = "x",
      timeoutSeconds = 5,
      executor = directExecutor,
      inferenceLock = lock,
      resetConversation = { throw RuntimeException("crash") },
      runInference = { _, _, _ -> },
      cancelInference = {},
      onInferenceFinished = { finishedCalled = true },
      elapsedMs = { tick() },
    )
    assertTrue("onInferenceFinished must be called on exception path", finishedCalled)
  }

  // ── executeStreaming tests ────────────────────────────────────────────────

  private fun streaming(
    runInference: InferenceFn,
    cancelInference: () -> Unit = {},
    onToken: (String, Boolean, String?) -> Unit,
    onError: (String) -> Unit = { fail("unexpected error: $it") },
    onInferenceFinished: () -> Unit = {},
  ) {
    InferenceGateway.executeStreaming(
      prompt = "p",
      timeoutSeconds = 5,
      executor = directExecutor,
      inferenceLock = lock,
      resetConversation = {},
      runInference = runInference,
      cancelInference = cancelInference,
      onToken = onToken,
      onError = onError,
      onInferenceFinished = onInferenceFinished,
    )
  }

  @Test
  fun streamingTokensAreDeliveredInOrder() {
    val tokens = mutableListOf<String>()
    var doneReceived = false
    streaming(
      runInference = { _, onPartial, _ ->
        onPartial("foo", false, null)
        onPartial("bar", false, null)
        onPartial("", true, null)
      },
      onToken = { partial, done, _ ->
        if (partial.isNotEmpty()) tokens.add(partial)
        if (done) doneReceived = true
      },
    )
    assertEquals(listOf("foo", "bar"), tokens)
    assertTrue(doneReceived)
  }

  @Test
  fun streamingDoneSignalDeliveredWithLastToken() {
    var lastTokenWasDone = false
    streaming(
      runInference = { _, onPartial, _ ->
        onPartial("tok", true, null)
      },
      onToken = { partial, done, _ ->
        if (partial == "tok" && done) lastTokenWasDone = true
      },
    )
    assertTrue(lastTokenWasDone)
  }

  @Test
  fun streamingErrorIsReported() {
    var errorMsg: String? = null
    var cancelled = false
    streaming(
      runInference = { _, _, onError -> onError("boom") },
      cancelInference = { cancelled = true },
      onToken = { _, _, _ -> fail("should not receive tokens on error") },
      onError = { errorMsg = it },
    )
    assertEquals("boom", errorMsg)
    assertTrue(cancelled)
  }

  @Test
  fun streamingExceptionIsReportedAsError() {
    var errorMsg: String? = null
    streaming(
      runInference = { _, _, _ -> throw RuntimeException("crash") },
      onToken = { _, _, _ -> fail("should not receive tokens") },
      onError = { errorMsg = it },
    )
    assertNotNull(errorMsg)
    assertTrue(errorMsg.orEmpty().contains("crash"))
  }

  @Test
  fun streamingThinkingTokensAreForwarded() {
    val thoughts = mutableListOf<String>()
    val tokens = mutableListOf<String>()
    streaming(
      runInference = { _, onPartial, _ ->
        onPartial("", false, "thinking...")
        onPartial("answer", false, null)
        onPartial("", true, null)
      },
      onToken = { partial, _, thought ->
        if (!thought.isNullOrEmpty()) thoughts.add(thought)
        if (partial.isNotEmpty()) tokens.add(partial)
      },
    )
    assertEquals(listOf("thinking..."), thoughts)
    assertEquals(listOf("answer"), tokens)
  }

  // ── Terminal authority tests ──────────────────────────────────────────────

  @Test
  fun executionStateChoosesOneTerminalOutcomeUnderRace() {
    val cancelCalls = AtomicInteger(0)
    val execution = InferenceGateway.InferenceExecution { cancelCalls.incrementAndGet() }
    assertTrue(execution.beginPreparation())
    assertTrue(execution.dispatch {})

    val contenders = listOf(
      InferenceGateway.ExecutionOutcome.Success,
      InferenceGateway.ExecutionOutcome.Timeout,
      InferenceGateway.ExecutionOutcome.CallerCancelled,
      InferenceGateway.ExecutionOutcome.ExternalCancelled,
      InferenceGateway.ExecutionOutcome.StopSequence,
      InferenceGateway.ExecutionOutcome.Error("native_error"),
    )
    val ready = CountDownLatch(contenders.size)
    val start = CountDownLatch(1)
    val finished = CountDownLatch(contenders.size)
    val winners = java.util.Collections.synchronizedList(
      mutableListOf<InferenceGateway.ExecutionOutcome>(),
    )
    val racers = Executors.newFixedThreadPool(contenders.size)

    try {
      contenders.forEach { outcome ->
        racers.execute {
          ready.countDown()
          start.await()
          if (execution.trySetOutcome(outcome)) winners += outcome
          finished.countDown()
        }
      }
      assertTrue("terminal contenders did not become ready", ready.await(2, TimeUnit.SECONDS))
      start.countDown()
      assertTrue("terminal contenders did not finish", finished.await(2, TimeUnit.SECONDS))

      assertEquals("exactly one terminal outcome must win", 1, winners.size)
      assertEquals(winners.single(), execution.outcome())
      assertTrue("native cancel may run at most once", cancelCalls.get() <= 1)
    } finally {
      start.countDown()
      racers.shutdownNow()
    }
  }

  @Test
  fun normalCompletionCannotBeOverwrittenByExternalCancellation() = runBlocking {
    val externalCancel = AtomicReference<(() -> Unit)?>(null)
    val cancelCalls = AtomicInteger(0)
    val recoverCalls = AtomicInteger(0)

    val result = InferenceGateway.execute(
      timeoutSeconds = 30,
      executor = directExecutor,
      inferenceLock = lock,
      operation = object : InferenceGateway.NativeOperation {
        override fun prepare() = Unit

        override fun dispatch(
          onPartial: (partial: String, done: Boolean, thought: String?) -> Unit,
          onError: (message: String) -> Unit,
        ) {
          onPartial("complete", true, null)
          externalCancel.get()!!.invoke()
        }

        override fun cancel() {
          cancelCalls.incrementAndGet()
        }

        override fun recover() {
          recoverCalls.incrementAndGet()
        }

        override fun finish() = Unit
      },
      elapsedMs = { tick() },
      onCancellationReady = { cancel -> externalCancel.set { cancel(
        InferenceGateway.CancellationReason.EXTERNAL,
      ) } },
    )

    assertEquals("complete", result.output)
    assertNull(result.error)
    assertEquals(0, cancelCalls.get())
    assertEquals(0, recoverCalls.get())
  }

  // ── Cancellation tests ──────────────────────────────────────────────────

  @Test
  fun queuedCancellationDoesNotCancelOrDispatchNativeInference() = runBlocking {
    val threadPool = Executors.newSingleThreadExecutor()
    val submissions = AtomicInteger(0)
    val secondSubmitted = CountDownLatch(1)
    val trackingExecutor = Executor { command ->
      threadPool.execute(command)
      if (submissions.incrementAndGet() == 2) secondSubmitted.countDown()
    }
    val firstStarted = CountDownLatch(1)
    val releaseFirst = CountDownLatch(1)
    val secondDispatched = AtomicBoolean(false)
    val secondCancelCalls = AtomicInteger(0)

    try {
      val firstJob = launch(Dispatchers.Default) {
        InferenceGateway.execute(
          prompt = "first",
          timeoutSeconds = 30,
          executor = trackingExecutor,
          inferenceLock = lock,
          resetConversation = {},
          runInference = { _, onPartial, _ ->
            firstStarted.countDown()
            releaseFirst.await()
            onPartial("", true, null)
          },
          cancelInference = {},
          elapsedMs = { tick() },
        )
      }
      assertTrue("first inference should own the executor", firstStarted.await(5, TimeUnit.SECONDS))

      val queuedJob = launch(Dispatchers.Default) {
        InferenceGateway.execute(
          prompt = "queued",
          timeoutSeconds = 30,
          executor = trackingExecutor,
          inferenceLock = lock,
          resetConversation = {},
          runInference = { _, onPartial, _ ->
            secondDispatched.set(true)
            onPartial("", true, null)
          },
          cancelInference = { secondCancelCalls.incrementAndGet() },
          elapsedMs = { tick() },
        )
      }
      assertTrue("second inference should be queued", secondSubmitted.await(5, TimeUnit.SECONDS))

      queuedJob.cancel()
      queuedJob.join()
      releaseFirst.countDown()
      firstJob.join()

      val queueDrained = CountDownLatch(1)
      threadPool.execute { queueDrained.countDown() }
      assertTrue("executor queue should drain", queueDrained.await(5, TimeUnit.SECONDS))

      assertEquals("queued cancellation must not call shared native cancel", 0, secondCancelCalls.get())
      assertTrue("cancelled queued request must never dispatch", !secondDispatched.get())
    } finally {
      releaseFirst.countDown()
      threadPool.shutdownNow()
    }
  }

  @Test
  fun callerCancellationWaitsForRecoveryAndFinish() = runBlocking {
    val threadPool = Executors.newSingleThreadExecutor()
    val nativeStarted = CountDownLatch(1)
    val recoveryStarted = CountDownLatch(1)
    val allowRecoveryToFinish = CountDownLatch(1)
    val callerFinished = CountDownLatch(1)
    val nativeError = AtomicReference<((String) -> Unit)?>(null)

    try {
      val job = launch(Dispatchers.Default) {
        try {
          InferenceGateway.execute(
            timeoutSeconds = 30,
            executor = threadPool,
            inferenceLock = Any(),
            operation = object : InferenceGateway.NativeOperation {
              override fun prepare() = Unit

              override fun dispatch(
                onPartial: (partial: String, done: Boolean, thought: String?) -> Unit,
                onError: (message: String) -> Unit,
              ) {
                nativeError.set(onError)
                nativeStarted.countDown()
              }

              override fun cancel() {
                nativeError.get()?.invoke("cancelled")
              }

              override fun recover() {
                recoveryStarted.countDown()
                allowRecoveryToFinish.await(2, TimeUnit.SECONDS)
              }

              override fun finish() = Unit
            },
            elapsedMs = { 0L },
          )
        } finally {
          callerFinished.countDown()
        }
      }

      assertTrue("native inference did not start", nativeStarted.await(2, TimeUnit.SECONDS))
      job.cancel()
      assertTrue("recovery did not start", recoveryStarted.await(2, TimeUnit.SECONDS))
      assertTrue(
        "caller returned before recovery and finish completed",
        !callerFinished.await(100, TimeUnit.MILLISECONDS),
      )

      allowRecoveryToFinish.countDown()
      assertTrue("caller did not finish after recovery", callerFinished.await(2, TimeUnit.SECONDS))
      job.join()
    } finally {
      allowRecoveryToFinish.countDown()
      threadPool.shutdownNow()
    }
  }

  @Test
  fun throwingNativeCancelDoesNotBypassBlockingOwnerSettlement() = runBlocking {
    val threadPool = Executors.newSingleThreadExecutor()
    val nativeStarted = CountDownLatch(1)
    val externalCancel = AtomicReference<(() -> Unit)?>(null)
    val recoverCalls = AtomicInteger(0)
    val finishCalls = AtomicInteger(0)
    val deferred = async {
      InferenceGateway.execute(
        timeoutSeconds = 30,
        executor = threadPool,
        inferenceLock = Any(),
        operation = object : InferenceGateway.NativeOperation {
          override fun prepare() = Unit

          override fun dispatch(
            onPartial: (String, Boolean, String?) -> Unit,
            onError: (String) -> Unit,
          ) {
            nativeStarted.countDown()
          }

          override fun cancel() {
            throw IllegalStateException("cancel failed")
          }

          override fun recover() {
            recoverCalls.incrementAndGet()
          }

          override fun finish() {
            finishCalls.incrementAndGet()
          }
        },
        elapsedMs = { 0L },
        onCancellationReady = { cancel ->
          externalCancel.set { cancel(InferenceGateway.CancellationReason.EXTERNAL) }
        },
      )
    }

    try {
      assertTrue("native inference did not start", nativeStarted.await(2, TimeUnit.SECONDS))
      val cancelFailure = runCatching { externalCancel.get()!!.invoke() }.exceptionOrNull()
      assertNull("cancel failure must remain on the execution owner", cancelFailure)

      val result = withTimeout(2_000) { deferred.await() }
      assertEquals("client_disconnected", result.error)
      assertEquals(1, recoverCalls.get())
      assertEquals(1, finishCalls.get())
    } finally {
      threadPool.shutdownNow()
      deferred.cancel()
      runCatching { deferred.await() }
      threadPool.awaitTermination(2, TimeUnit.SECONDS)
    }
  }

  @Test
  fun externalCancellationUsesExecutionOwner() = runBlocking {
    val threadPool = Executors.newSingleThreadExecutor()
    val nativeStarted = CountDownLatch(1)
    val callerFinished = CountDownLatch(1)
    val externalCancel = AtomicReference<(() -> Unit)?>(null)
    val resultRef = AtomicReference<InferenceResult?>(null)
    val cancelCalls = AtomicInteger(0)
    val recoverCalls = AtomicInteger(0)
    val finishCalls = AtomicInteger(0)

    try {
      launch(Dispatchers.Default) {
        try {
          resultRef.set(
            InferenceGateway.execute(
              timeoutSeconds = 30,
              executor = threadPool,
              inferenceLock = lock,
              operation = object : InferenceGateway.NativeOperation {
                override fun prepare() = Unit

                override fun dispatch(
                  onPartial: (partial: String, done: Boolean, thought: String?) -> Unit,
                  onError: (message: String) -> Unit,
                ) {
                  nativeStarted.countDown()
                }

                override fun cancel() {
                  cancelCalls.incrementAndGet()
                }

                override fun recover() {
                  recoverCalls.incrementAndGet()
                }

                override fun finish() {
                  finishCalls.incrementAndGet()
                }
              },
              elapsedMs = { tick() },
              onCancellationReady = { cancel ->
                externalCancel.set { cancel(InferenceGateway.CancellationReason.EXTERNAL) }
              },
            ),
          )
        } finally {
          callerFinished.countDown()
        }
      }

      assertTrue("native inference should start", nativeStarted.await(2, TimeUnit.SECONDS))
      externalCancel.get()!!.invoke()
      assertTrue("caller should return after recovery", callerFinished.await(2, TimeUnit.SECONDS))
      assertEquals("client_disconnected", resultRef.get()?.error)
      assertEquals("external cancellation must cancel native exactly once", 1, cancelCalls.get())
      assertEquals("external cancellation must recover exactly once", 1, recoverCalls.get())
      assertEquals("external cancellation must finish exactly once", 1, finishCalls.get())
    } finally {
      threadPool.shutdownNow()
    }
  }

  @Test
  fun cancellationTriggersCancelInference() = runBlocking {
    val threadPool = Executors.newSingleThreadExecutor()
    var cancelled = false
    val inferenceStarted = CountDownLatch(1)
    try {
      val job = launch(Dispatchers.Default) {
        InferenceGateway.execute(
          prompt = "long",
          timeoutSeconds = 30,
          executor = threadPool,
          inferenceLock = lock,
          resetConversation = {},
          runInference = { _, _, _ ->
            inferenceStarted.countDown()
            Thread.sleep(5000)
          },
          cancelInference = { cancelled = true },
          elapsedMs = { tick() },
        )
      }
      assertTrue("inference should start within 5s", inferenceStarted.await(5, TimeUnit.SECONDS))
      job.cancel()
      job.join()
      assertTrue("cancelInference should be called on coroutine cancellation", cancelled)
    } finally {
      threadPool.shutdownNow()
    }
  }

  @Test
  fun concurrentErrorAndTimeoutFirstErrorWins() = runBlocking {
    val threadPool = Executors.newSingleThreadExecutor()
    try {
      val result = InferenceGateway.execute(
        prompt = "race",
        timeoutSeconds = 1,
        executor = threadPool,
        inferenceLock = lock,
        resetConversation = {},
        runInference = { _, _, onError ->
          onError("inference_failed")
          // Don't signal done — let the latch timeout
          Thread.sleep(3000)
        },
        cancelInference = {},
        elapsedMs = { tick() },
      )
      // The onError callback fires first with "inference_failed", then the
      // lifecycleLatch times out. The first error must win.
      assertEquals("inference_failed", result.error)
    } finally {
      threadPool.shutdownNow()
    }
  }

  @Test
  fun cancellationSetsClientDisconnectedError() = runBlocking {
    val threadPool = Executors.newSingleThreadExecutor()
    var inferenceResult: InferenceResult? = null
    val inferenceStarted = CountDownLatch(1)
    try {
      val job = launch(Dispatchers.Default) {
        inferenceResult = InferenceGateway.execute(
          prompt = "long",
          timeoutSeconds = 30,
          executor = threadPool,
          inferenceLock = lock,
          resetConversation = {},
          runInference = { _, _, _ ->
            inferenceStarted.countDown()
            Thread.sleep(5000)
          },
          cancelInference = {},
          elapsedMs = { tick() },
        )
      }
      assertTrue("inference should start within 5s", inferenceStarted.await(5, TimeUnit.SECONDS))
      job.cancel()
      job.join()
      assertNotNull("result should be set after cancellation", inferenceResult)
      assertEquals("client_disconnected", inferenceResult?.error)
    } finally {
      threadPool.shutdownNow()
    }
  }

  // ── onInferenceFinished tests ───────────────────────────────────────────

  @Test
  fun streamingOnInferenceFinishedCalledInsideLock() {
    var finishedCalled = false
    var lockHeldDuringFinished = false
    streaming(
      runInference = { _, onPartial, _ ->
        onPartial("tok", false, null)
        onPartial("", true, null)
      },
      onToken = { _, _, _ -> },
      onInferenceFinished = {
        finishedCalled = true
        lockHeldDuringFinished = Thread.holdsLock(lock)
      },
    )
    assertTrue("onInferenceFinished must be called", finishedCalled)
    assertTrue("onInferenceFinished must run inside inferenceLock", lockHeldDuringFinished)
  }

  @Test
  fun streamingOnInferenceFinishedCalledOnError() {
    var finishedCalled = false
    streaming(
      runInference = { _, _, onError -> onError("boom") },
      onToken = { _, _, _ -> },
      onError = { },
      onInferenceFinished = { finishedCalled = true },
    )
    assertTrue("onInferenceFinished must be called on error path", finishedCalled)
  }

  @Test
  fun streamingOnInferenceFinishedCalledOnException() {
    var finishedCalled = false
    streaming(
      runInference = { _, _, _ -> throw RuntimeException("crash") },
      onToken = { _, _, _ -> },
      onError = { },
      onInferenceFinished = { finishedCalled = true },
    )
    assertTrue("onInferenceFinished must be called on exception path", finishedCalled)
  }

  @Test
  fun streamingTimeoutPreparesInferenceOnlyOnce() {
    val preparationCalls = AtomicInteger(0)
    val recoveryCalls = AtomicInteger(0)

    InferenceGateway.executeStreaming(
      prompt = "p",
      timeoutSeconds = 0,
      executor = directExecutor,
      inferenceLock = lock,
      resetConversation = {
        if (preparationCalls.incrementAndGet() > 1) recoveryCalls.incrementAndGet()
      },
      runInference = { _, _, _ -> },
      cancelInference = {},
      onToken = { _, _, _ -> },
      onError = {},
    )

    assertEquals("streaming preparation must run once", 1, preparationCalls.get())
    assertEquals("recovery must not repeat preparation", 0, recoveryCalls.get())
  }

  @Test
  fun streamingStopSequenceWinnerEmitsOneCompletionNotification() {
    val threadPool = Executors.newSingleThreadExecutor()
    val nativeStarted = CountDownLatch(1)
    val lifecycleFinished = CountDownLatch(1)
    val cancelAction = AtomicReference<((InferenceGateway.CancellationReason) -> Unit)?>(null)
    val doneCalls = AtomicInteger(0)
    val cancelCalls = AtomicInteger(0)
    val recoverCalls = AtomicInteger(0)

    try {
      InferenceGateway.executeStreaming(
        timeoutSeconds = 30,
        executor = threadPool,
        inferenceLock = Any(),
        operation = object : InferenceGateway.NativeOperation {
          override fun prepare() = Unit

          override fun dispatch(
            onPartial: (String, Boolean, String?) -> Unit,
            onError: (String) -> Unit,
          ) {
            nativeStarted.countDown()
          }

          override fun cancel() {
            cancelCalls.incrementAndGet()
          }

          override fun recover() {
            recoverCalls.incrementAndGet()
          }

          override fun finish() {
            lifecycleFinished.countDown()
          }
        },
        onToken = { _, done, _ -> if (done) doneCalls.incrementAndGet() },
        onError = { fail("stop sequence must not surface as an error: $it") },
        onCancellationReady = { cancel -> cancelAction.set(cancel) },
      )

      assertTrue("native inference did not start", nativeStarted.await(2, TimeUnit.SECONDS))
      cancelAction.get()!!.invoke(InferenceGateway.CancellationReason.STOP_SEQUENCE)
      assertTrue("lifecycle did not finish", lifecycleFinished.await(2, TimeUnit.SECONDS))

      assertEquals("stop sequence must complete the stream exactly once", 1, doneCalls.get())
      assertEquals(1, cancelCalls.get())
      assertEquals(1, recoverCalls.get())
    } finally {
      threadPool.shutdownNow()
      threadPool.awaitTermination(2, TimeUnit.SECONDS)
    }
  }

  @Test
  fun throwingNativeCancelDoesNotBypassStreamingOwnerSettlement() {
    val threadPool = Executors.newSingleThreadExecutor()
    val nativeStarted = CountDownLatch(1)
    val lifecycleFinished = CountDownLatch(1)
    val cancelAction = AtomicReference<((InferenceGateway.CancellationReason) -> Unit)?>(null)
    val recoverCalls = AtomicInteger(0)
    val finishCalls = AtomicInteger(0)

    try {
      InferenceGateway.executeStreaming(
        timeoutSeconds = 30,
        executor = threadPool,
        inferenceLock = Any(),
        operation = object : InferenceGateway.NativeOperation {
          override fun prepare() = Unit

          override fun dispatch(
            onPartial: (String, Boolean, String?) -> Unit,
            onError: (String) -> Unit,
          ) {
            nativeStarted.countDown()
          }

          override fun cancel() {
            throw IllegalStateException("cancel failed")
          }

          override fun recover() {
            recoverCalls.incrementAndGet()
          }

          override fun finish() {
            finishCalls.incrementAndGet()
            lifecycleFinished.countDown()
          }
        },
        onToken = { _, _, _ -> Unit },
        onError = { fail("external cancellation must not surface as an error: $it") },
        onCancellationReady = { cancel -> cancelAction.set(cancel) },
      )

      assertTrue("native inference did not start", nativeStarted.await(2, TimeUnit.SECONDS))
      val cancelFailure = runCatching {
        cancelAction.get()!!.invoke(InferenceGateway.CancellationReason.EXTERNAL)
      }.exceptionOrNull()
      assertNull("cancel failure must remain on the execution owner", cancelFailure)
      assertTrue("lifecycle did not finish", lifecycleFinished.await(2, TimeUnit.SECONDS))
      assertEquals(1, recoverCalls.get())
      assertEquals(1, finishCalls.get())
    } finally {
      threadPool.shutdownNow()
      threadPool.awaitTermination(2, TimeUnit.SECONDS)
    }
  }

  @Test
  fun streamingExternalCancellationCancelsOnceAndRecoversBeforeFinish() {
    val threadPool = Executors.newSingleThreadExecutor()
    val nativeStarted = CountDownLatch(1)
    val lifecycleFinished = CountDownLatch(1)
    val cancelAction = AtomicReference<(() -> Unit)?>(null)
    val events = java.util.Collections.synchronizedList(mutableListOf<String>())

    try {
      InferenceGateway.executeStreaming(
        timeoutSeconds = 30,
        executor = threadPool,
        inferenceLock = Any(),
        operation = object : InferenceGateway.NativeOperation {
          override fun prepare() = Unit
          override fun dispatch(
            onPartial: (String, Boolean, String?) -> Unit,
            onError: (String) -> Unit,
          ) {
            events += "dispatch"
            nativeStarted.countDown()
          }
          override fun cancel() { events += "cancel" }
          override fun recover() { events += "recover" }
          override fun finish() {
            events += "finish"
            lifecycleFinished.countDown()
          }
        },
        onToken = { _, _, _ -> },
        onError = {},
        onCancellationReady = { cancel ->
          cancelAction.set { cancel(InferenceGateway.CancellationReason.EXTERNAL) }
        },
      )

      assertTrue(nativeStarted.await(2, TimeUnit.SECONDS))
      cancelAction.get()!!.invoke()
      cancelAction.get()!!.invoke()
      assertTrue(lifecycleFinished.await(2, TimeUnit.SECONDS))
      cancelAction.get()!!.invoke()
      assertEquals(listOf("dispatch", "cancel", "recover", "finish"), events)
    } finally {
      threadPool.shutdownNow()
    }
  }

  // Uses 1s real-time wait — CountDownLatch.await() can't use virtual time (Java blocking primitive).
  @Test
  fun streamingOnInferenceFinishedCalledOnTimeout() {
    var finishedCalled = false
    InferenceGateway.executeStreaming(
      prompt = "p",
      timeoutSeconds = 1,
      executor = directExecutor,
      inferenceLock = lock,
      resetConversation = {},
      runInference = { _, _, _ -> },
      cancelInference = {},
      onToken = { _, _, _ -> },
      onError = { },
      onInferenceFinished = { finishedCalled = true },
    )
    assertTrue("onInferenceFinished must be called on timeout", finishedCalled)
  }
}
