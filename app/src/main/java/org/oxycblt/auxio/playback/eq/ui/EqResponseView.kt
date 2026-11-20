/*
 * Copyright (c) 2026 Auxio Project
 * EqResponseView.kt is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
 
package org.oxycblt.auxio.playback.eq.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.text.TextPaint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.annotation.AttrRes
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import org.oxycblt.auxio.playback.eq.BiquadDesign
import org.oxycblt.auxio.playback.eq.ParametricEq

/**
 * Plots the combined magnitude response of a [ParametricEq]'s bands from 20 Hz to 20 kHz on a
 * logarithmic frequency axis, with a symmetric dB axis that scales to fit the curve.
 */
class EqResponseView
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null, @AttrRes defStyleAttr: Int = 0) :
    View(context, attrs, defStyleAttr) {
    private val density = resources.displayMetrics.density
    private val primary = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary)

    private val curvePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2 * density
            strokeJoin = Paint.Join.ROUND
            color = primary
        }
    private val fillPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = ColorUtils.setAlphaComponent(primary, 0x26)
        }
    private val gridPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = density / 2
            color =
                MaterialColors.getColor(
                    this@EqResponseView,
                    com.google.android.material.R.attr.colorOutlineVariant,
                )
        }
    private val zeroPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = density
            color = ColorUtils.setAlphaComponent(primary, 0x80)
        }
    private val labelPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize =
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10f, resources.displayMetrics)
            color =
                MaterialColors.getColor(
                    this@EqResponseView,
                    com.google.android.material.R.attr.colorOnSurfaceVariant,
                )
        }

    /** Response in dB at each of [FREQUENCIES]. */
    private val response = DoubleArray(POINTS)
    private var dbHalfRange = DB_STEP
    private val curvePath = Path()
    private val fillPath = Path()

    /** Show the response of [eq], or a flat line if null. */
    fun setEq(eq: ParametricEq?) {
        response.fill(0.0)
        if (eq != null) {
            val coefficients =
                eq.bands
                    .filter { it.enabled && it.frequency > 0 && it.frequency < SAMPLE_RATE / 2.0 }
                    .map {
                        BiquadDesign.design(it.filterType, SAMPLE_RATE, it.frequency, it.gain, it.q)
                    }
            for (i in 0 until POINTS) {
                // Filters run in series, so their gains multiply and their dB values add.
                response[i] = coefficients.sumOf {
                    BiquadDesign.magnitudeDb(it, SAMPLE_RATE, FREQUENCIES[i])
                }
            }
        }
        val peak = response.maxOf { abs(it) }
        dbHalfRange = max(ceil((peak + 1.0) / DB_STEP) * DB_STEP, DB_STEP)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val gap = 4 * density
        val textHeight = labelPaint.fontMetrics.let { it.descent - it.ascent }
        val labelWidth = labelPaint.measureText(formatDb(-dbHalfRange))

        val plotLeft = paddingLeft + labelWidth + gap
        val plotRight = width - paddingRight - gap
        val plotTop = paddingTop + textHeight / 2
        val plotBottom = height - paddingBottom - textHeight - gap
        if (plotRight <= plotLeft || plotBottom <= plotTop) return

        fun xOf(frequency: Double) =
            plotLeft +
                ((log10(frequency) - LOG_MIN) / (LOG_MAX - LOG_MIN) * (plotRight - plotLeft))
                    .toFloat()
        fun yOf(db: Double) =
            plotTop + ((dbHalfRange - db) / (2 * dbHalfRange) * (plotBottom - plotTop)).toFloat()

        // Horizontal grid lines, labelling only as many as fit without overlapping.
        val steps = (2 * dbHalfRange / DB_STEP).toInt()
        val stepPx = (plotBottom - plotTop) / steps
        val labelEvery = max(1, ceil(textHeight * 1.5f / stepPx).toInt())
        for (i in 0..steps) {
            val db = -dbHalfRange + i * DB_STEP
            val y = yOf(db)
            val isZero = abs(db) < 0.01
            canvas.drawLine(plotLeft, y, plotRight, y, if (isZero) zeroPaint else gridPaint)
            if ((steps / 2 - i) % labelEvery == 0) {
                val label = formatDb(db)
                canvas.drawText(
                    label,
                    plotLeft - gap - labelPaint.measureText(label),
                    y - (labelPaint.ascent() + labelPaint.descent()) / 2,
                    labelPaint,
                )
            }
        }

        // Vertical frequency landmarks.
        for ((frequency, label) in LANDMARKS) {
            val x = xOf(frequency)
            canvas.drawLine(x, plotTop, x, plotBottom, gridPaint)
            canvas.drawText(
                label,
                x - labelPaint.measureText(label) / 2,
                plotBottom + gap - labelPaint.ascent(),
                labelPaint,
            )
        }

        // The response curve itself, with a translucent fill down to the bottom of the plot.
        curvePath.rewind()
        fillPath.rewind()
        for (i in 0 until POINTS) {
            val x = xOf(FREQUENCIES[i])
            val y = yOf(response[i])
            if (i == 0) {
                curvePath.moveTo(x, y)
                fillPath.moveTo(x, plotBottom)
            } else {
                curvePath.lineTo(x, y)
            }
            fillPath.lineTo(x, y)
        }
        fillPath.lineTo(xOf(FREQUENCIES.last()), plotBottom)
        fillPath.close()
        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(curvePath, curvePaint)
    }

    private fun formatDb(db: Double) =
        if (db == db.toLong().toDouble()) db.toLong().toString() else "%.1f".format(db)

    private companion object {
        const val SAMPLE_RATE = 48000
        const val POINTS = 200
        const val MIN_FREQ = 20.0
        const val MAX_FREQ = 20000.0
        const val DB_STEP = 2.5
        val LOG_MIN = log10(MIN_FREQ)
        val LOG_MAX = log10(MAX_FREQ)
        val FREQUENCIES =
            DoubleArray(POINTS) { MIN_FREQ * (MAX_FREQ / MIN_FREQ).pow(it / (POINTS - 1.0)) }
        val LANDMARKS = listOf(100.0 to "100", 1000.0 to "1k", 10000.0 to "10k")
    }
}
