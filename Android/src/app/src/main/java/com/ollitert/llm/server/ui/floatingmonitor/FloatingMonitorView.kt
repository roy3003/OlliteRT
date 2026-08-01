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

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.Log
import android.view.View
import kotlin.math.roundToInt

internal const val FLOATING_MONITOR_WIDTH_DP = 96f
internal const val FLOATING_MONITOR_HEIGHT_DP = 108f
internal const val FLOATING_MONITOR_VALUE_TEXT_SIZE_SP = 18f
internal const val FLOATING_MONITOR_SECONDS_SUFFIX_TEXT_SIZE_SP = 10f
internal const val FLOATING_MONITOR_TEXT_COLOR = 0xFF000000.toInt()
internal const val FLOATING_MONITOR_TOP_LABEL_BASELINE_FRACTION = 0.19f
internal const val FLOATING_MONITOR_TOP_VALUE_BASELINE_FRACTION = 0.43f
internal const val FLOATING_MONITOR_BOTTOM_VALUE_BASELINE_FRACTION = 0.70f
internal const val FLOATING_MONITOR_BOTTOM_LABEL_BASELINE_FRACTION = 0.88f

internal fun floatingMonitorFillColor(state: FloatingMonitorVisualState): Int =
  when (state) {
    FloatingMonitorVisualState.Running -> 0xFF55D68B.toInt()
    FloatingMonitorVisualState.Processing -> 0xFFFFB74D.toInt()
    FloatingMonitorVisualState.Hidden -> error("Hidden monitor has no renderable fill")
  }

internal fun floatingMonitorSecondsSuffixStartX(
  centerX: Float,
  numericWidth: Float,
  gap: Float,
): Float = centerX + numericWidth / 2f + gap

@SuppressLint("ViewConstructor")
internal class FloatingMonitorView(
  context: Context,
  onTap: () -> Unit,
) : View(context) {
  private val density = resources.displayMetrics.density
  private val hexPath = Path()
  private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
  private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeWidth = 2f * density
  }
  private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = FLOATING_MONITOR_TEXT_COLOR
    textAlign = Paint.Align.CENTER
    textSize = 10f * density
    typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
  }
  private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = FLOATING_MONITOR_TEXT_COLOR
    textAlign = Paint.Align.CENTER
    textSize = FLOATING_MONITOR_VALUE_TEXT_SIZE_SP * density
    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
  }
  private val secondsSuffixPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = FLOATING_MONITOR_TEXT_COLOR
    textAlign = Paint.Align.LEFT
    textSize = FLOATING_MONITOR_SECONDS_SUFFIX_TEXT_SIZE_SP * density
    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
  }

  private var model: FloatingMonitorRenderModel? = null

  init {
    isClickable = true
    isFocusable = false
    setOnClickListener { onTap() }
  }

  fun render(nextModel: FloatingMonitorRenderModel) {
    model = nextModel
    contentDescription = floatingMonitorContentDescription(nextModel)
    invalidate()
  }

  override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
    val desiredWidth = (FLOATING_MONITOR_WIDTH_DP * density).roundToInt()
    val desiredHeight = (FLOATING_MONITOR_HEIGHT_DP * density).roundToInt()
    setMeasuredDimension(
      resolveSize(desiredWidth, widthMeasureSpec),
      resolveSize(desiredHeight, heightMeasureSpec),
    )
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    val current = model ?: return
    try {
      drawMonitor(canvas, current)
    } catch (e: RuntimeException) {
      Log.w(TAG, "Floating monitor draw failed", e)
    }
  }

  private fun drawMonitor(canvas: Canvas, current: FloatingMonitorRenderModel) {
    val width = width.toFloat()
    val height = height.toFloat()

    hexPath.reset()
    hexPath.moveTo(width / 2f, 0f)
    hexPath.lineTo(width, height * 0.25f)
    hexPath.lineTo(width, height * 0.75f)
    hexPath.lineTo(width / 2f, height)
    hexPath.lineTo(0f, height * 0.75f)
    hexPath.lineTo(0f, height * 0.25f)
    hexPath.close()

    val processing = current.visualState == FloatingMonitorVisualState.Processing
    fillPaint.color = floatingMonitorFillColor(current.visualState)
    borderPaint.color = if (processing) PROCESSING_BORDER else RUNNING_BORDER
    canvas.drawPath(hexPath, fillPaint)
    canvas.drawPath(hexPath, borderPaint)

    val centerX = width / 2f
    canvas.drawText("req", centerX, height * FLOATING_MONITOR_TOP_LABEL_BASELINE_FRACTION, labelPaint)
    canvas.drawText(current.requestValue, centerX, height * FLOATING_MONITOR_TOP_VALUE_BASELINE_FRACTION, valuePaint)
    val secondaryBaseline = height * FLOATING_MONITOR_BOTTOM_VALUE_BASELINE_FRACTION
    if (processing) {
      canvas.drawText(current.secondaryValue, centerX, secondaryBaseline, valuePaint)
      canvas.drawText(
        "s",
        floatingMonitorSecondsSuffixStartX(
          centerX = centerX,
          numericWidth = valuePaint.measureText(current.secondaryValue),
          gap = SECONDS_SUFFIX_GAP_DP * density,
        ),
        secondaryBaseline,
        secondsSuffixPaint,
      )
    } else {
      canvas.drawText(current.secondaryValue, centerX, secondaryBaseline, valuePaint)
    }
    canvas.drawText(current.secondaryLabel, centerX, height * FLOATING_MONITOR_BOTTOM_LABEL_BASELINE_FRACTION, labelPaint)
  }

  private companion object {
    const val TAG = "OlliteRT.FloatView"
    const val SECONDS_SUFFIX_GAP_DP = 2f
    const val RUNNING_BORDER = 0xFF55D68B.toInt()
    const val PROCESSING_BORDER = 0xFFFFB74D.toInt()
  }
}
