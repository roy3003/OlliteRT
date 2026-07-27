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

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class InferenceRequestLeaseTest {

  @Before
  fun setUp() = InferenceRequestLease.resetForTesting()

  @After
  fun tearDown() = InferenceRequestLease.resetForTesting()

  @Test
  fun leaseCoversQueuedAndPreparationWorkIndependentlyOfProcessingMetric() {
    assertFalse(InferenceRequestLease.isActive)
    assertFalse(ServerMetrics.isInferring.value)

    val lease = InferenceRequestLease.acquire()

    assertTrue(InferenceRequestLease.isActive)
    assertFalse(ServerMetrics.isInferring.value)

    lease.close()
    assertFalse(InferenceRequestLease.isActive)
  }

  @Test
  fun leaseCloseIsIdempotent() {
    val lease = InferenceRequestLease.acquire()

    lease.close()
    lease.close()

    assertFalse(InferenceRequestLease.isActive)
  }
}
