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

import org.junit.Assert.assertEquals
import org.junit.Test

class FloatingMonitorVisualSpecTest {

  @Test
  fun runningUsesFilledGreenSurface() {
    assertEquals(0xFF207A4D.toInt(), floatingMonitorFillColor(FloatingMonitorVisualState.Running))
  }

  @Test
  fun processingUsesFilledOrangeSurface() {
    assertEquals(0xFF9A5300.toInt(), floatingMonitorFillColor(FloatingMonitorVisualState.Processing))
  }

  @Test(expected = IllegalStateException::class)
  fun hiddenStateHasNoRenderableFill() {
    floatingMonitorFillColor(FloatingMonitorVisualState.Hidden)
  }

  @Test
  fun labelsMoveOutwardAndValuesUseLargerCenterSpace() {
    assertEquals(18f, FLOATING_MONITOR_VALUE_TEXT_SIZE_SP)
    assertEquals(0.19f, FLOATING_MONITOR_TOP_LABEL_BASELINE_FRACTION)
    assertEquals(0.43f, FLOATING_MONITOR_TOP_VALUE_BASELINE_FRACTION)
    assertEquals(0.70f, FLOATING_MONITOR_BOTTOM_VALUE_BASELINE_FRACTION)
    assertEquals(0.88f, FLOATING_MONITOR_BOTTOM_LABEL_BASELINE_FRACTION)
  }
}
