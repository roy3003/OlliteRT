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

import com.ollitert.llm.server.common.ServerStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class FloatingMonitorStateTest {

  @Test
  fun `running server without inference maps to running monitor`() {
    assertEquals(
      FloatingMonitorVisualState.Running,
      deriveFloatingMonitorVisualState(
        status = ServerStatus.RUNNING,
        isInferring = false,
      ),
    )
  }

  @Test
  fun `running server with inference maps to processing monitor`() {
    assertEquals(
      FloatingMonitorVisualState.Processing,
      deriveFloatingMonitorVisualState(
        status = ServerStatus.RUNNING,
        isInferring = true,
      ),
    )
  }
}
