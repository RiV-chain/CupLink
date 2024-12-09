package org.rivchain.cuplink.renderer

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import org.rivchain.cuplink.R
import kotlin.math.abs

class VisualizerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val barColors = intArrayOf(ContextCompat.getColor(context, R.color.fftChartEdgeColor), ContextCompat.getColor(context, R.color.lightRedStatus))

    private var fftData: ByteArray? = null

    // Default bar width in dp
    private var barWidthDp: Float = 2f

    // Update FFT data for rendering
    fun updateFFT(data: ByteArray?) {
        fftData = data
        invalidate() // Trigger redraw
    }

    // Set bar width in dp dynamically
    fun setBarWidth(dp: Float) {
        barWidthDp = dp
        invalidate() // Trigger redraw
    }

    private val paintBar = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val fft = fftData ?: return
        if (fft.isEmpty()) return

        val width = width.toFloat()
        val height = height.toFloat()
        val centerX = width / 2
        val centerY = height / 2
        val scaleFactor = 8.0f // Amplify bar height

        // Convert bar width from dp to pixels
        val barWidthPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, barWidthDp, resources.displayMetrics
        )

        // Total number of bars (mirrored left and right)
        val totalBars = fft.size
        val totalWidth = totalBars * barWidthPx

        // Spacing between bars
        val spacing = (width - totalWidth) / (2 * totalBars - 1)

        fft.forEachIndexed { i, value ->
            val magnitude = if (i % 2 == 0) abs(value.toInt()) else 0 // Zero height for odd-indexed bars

            // Normalize magnitude and scale
            val normalizedMagnitude = (magnitude / 128.0f).coerceIn(0.0f, 1.0f)
            val barHeight = (normalizedMagnitude * height / 2 * scaleFactor).coerceAtMost(height / 2)

            // Left-side bar positions
            val leftBarLeft = centerX - (i + 1) * (barWidthPx + spacing)
            val leftBarRight = leftBarLeft + barWidthPx

            // Right-side bar positions
            val rightBarLeft = centerX + i * (barWidthPx + spacing)
            val rightBarRight = rightBarLeft + barWidthPx

            // Top and bottom positions for symmetry
            val top = centerY - barHeight
            val bottom = centerY + barHeight


            paintBar.shader = LinearGradient(
                0f, top, 0f, bottom,
                barColors, // Green to Red
                null,
                Shader.TileMode.CLAMP
            )

            // Draw bars on the left
            canvas.drawRect(leftBarLeft, top, leftBarRight, bottom, paintBar)

            // Draw bars on the right
            canvas.drawRect(rightBarLeft, top, rightBarRight, bottom, paintBar)
        }
    }
}
