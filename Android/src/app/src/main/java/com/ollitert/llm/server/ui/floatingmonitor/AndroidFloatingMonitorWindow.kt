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

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import kotlin.math.roundToInt

internal class AndroidFloatingMonitorWindow(
  context: Context,
  onTap: () -> Unit,
) : FloatingMonitorWindowPort {
  private val appContext = context.applicationContext
  private val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
  private val view = FloatingMonitorView(appContext, onTap)
  private val layoutParams = WindowManager.LayoutParams(
    ViewGroup.LayoutParams.WRAP_CONTENT,
    ViewGroup.LayoutParams.WRAP_CONTENT,
    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
      WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
    PixelFormat.TRANSLUCENT,
  ).apply {
    gravity = Gravity.TOP or Gravity.END
    x = dp(16f)
    y = dp(120f)
  }

  override val isAttached: Boolean
    get() = view.parent != null

  override fun attach(model: FloatingMonitorRenderModel) {
    if (isAttached) {
      update(model)
      return
    }

    view.render(model)
    windowManager.addView(view, layoutParams)
  }

  override fun update(model: FloatingMonitorRenderModel) {
    view.render(model)
  }

  override fun detach() {
    if (!isAttached) return
    windowManager.removeViewImmediate(view)
  }

  private fun dp(value: Float): Int =
    (value * appContext.resources.displayMetrics.density).roundToInt()
}
