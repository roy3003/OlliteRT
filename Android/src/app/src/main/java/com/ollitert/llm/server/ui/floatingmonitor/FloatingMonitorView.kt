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
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.view.View
import kotlin.math.roundToInt

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
    color = LABEL_COLOR
    textAlign = Paint.Align.CENTER
    textSize = 10f * density
    typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
  }
  private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.WHITE
    textAlign = Paint.Align.CENTER
    textSize = 13f * density
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
    contentDescription = buildString {
      append(nextModel.visualState.name)
      append(", req ")
      append(nextModel.requestValue)
      append(", ")
      append(nextModel.secondaryLabel)
      append(' ')
      append(nextModel.secondaryValue)
    }
    invalidate()
  }

  override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
    val desiredWidth = (MONITOR_WIDTH_DP * density).roundToInt()
    val desiredHeight = (MONITOR_HEIGHT_DP * density).roundToInt()
    setMeasuredDimension(
      resolveSize(desiredWidth, widthMeasureSpec),
      resolveSize(desiredHeight, heightMeasureSpec),
    )
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    val current = model ?: return
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
    fillPaint.color = if (processing) PROCESSING_FILL else RUNNING_FILL
    borderPaint.color = if (processing) PROCESSING_BORDER else RUNNING_BORDER
    canvas.drawPath(hexPath, fillPaint)
    canvas.drawPath(hexPath, borderPaint)

    val centerX = width / 2f
    canvas.drawText("req", centerX, height * 0.27f, labelPaint)
    canvas.drawText(current.requestValue, centerX, height * 0.44f, valuePaint)
    canvas.drawText(current.secondaryValue, centerX, height * 0.64f, valuePaint)
    canvas.drawText(current.secondaryLabel, centerX, height * 0.80f, labelPaint)
  }

  private companion object {
    const val MONITOR_WIDTH_DP = 96f
    const val MONITOR_HEIGHT_DP = 108f
    const val LABEL_COLOR = 0xFFADB5BD.toInt()
    const val RUNNING_FILL = 0xFF12271E.toInt()
    const val RUNNING_BORDER = 0xFF55D68B.toInt()
    const val PROCESSING_FILL = 0xFF252036.toInt()
    const val PROCESSING_BORDER = 0xFFFFB74D.toInt()
  }
}
