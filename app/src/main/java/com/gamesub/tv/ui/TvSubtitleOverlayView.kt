package com.gamesub.tv.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import android.view.View
import com.gamesub.tv.config.AppConfigManager
import com.gamesub.tv.config.TvSubConfig

/**
 * Transparent, Gamepad/Remote Click-Through Subtitle Overlay View for Android TV.
 * Renders high-contrast stroked Turkish subtitles inside the user's phone-configured box (`ovlX, ovlY, ovlW, ovlH`).
 */
@SuppressLint("NewApi")
class TvSubtitleOverlayView(context: Context) : View(context) {

    private var currentSubtitle: String = ""
    private val mainHandler = Handler(Looper.getMainLooper())
    private val clearRunnable = Runnable {
        currentSubtitle = ""
        invalidate()
    }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val strokePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        color = Color.BLACK
    }

    private val fillPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        color = Color.WHITE
    }

    private val boxRect = RectF()

    fun showSubtitle(text: String) {
        mainHandler.post {
            currentSubtitle = text.trim()
            invalidate()
            mainHandler.removeCallbacks(clearRunnable)
            val hideSec = AppConfigManager.currentConfig.autoHideSec
            if (hideSec > 0 && currentSubtitle.isNotEmpty()) {
                mainHandler.postDelayed(clearRunnable, hideSec * 1000L)
            }
        }
    }

    fun clearSubtitle() {
        mainHandler.post {
            currentSubtitle = ""
            invalidate()
        }
    }

    fun applyConfig(cfg: TvSubConfig) {
        mainHandler.post {
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val text = currentSubtitle
        if (text.isEmpty() || width <= 0 || height <= 0) return

        val cfg = AppConfigManager.currentConfig
        val left = width * cfg.ovlX
        val top = height * cfg.ovlY
        val right = (left + width * cfg.ovlW).coerceAtMost(width.toFloat() - 8f)
        val bottom = (top + height * cfg.ovlH).coerceAtMost(height.toFloat() - 8f)

        boxRect.set(left, top, right, bottom)

        // Draw translucent rounded background box
        bgPaint.color = Color.argb(cfg.bgOpacity.coerceIn(0, 250), 0, 0, 0)
        canvas.drawRoundRect(boxRect, 18f, 18f, bgPaint)

        // Configure font size & colors
        val pxSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            cfg.fontSize.toFloat(),
            resources.displayMetrics
        )
        strokePaint.textSize = pxSize
        strokePaint.strokeWidth = cfg.strokeWidth.toFloat() * 2f

        fillPaint.textSize = pxSize
        fillPaint.color = try {
            Color.parseColor(cfg.textColor)
        } catch (_: Exception) {
            Color.WHITE
        }

        val textWidth = (boxRect.width() - 36f).toInt().coerceAtLeast(100)

        val strokeLayout = StaticLayout.Builder
            .obtain(text, 0, text.length, strokePaint, textWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .build()

        val fillLayout = StaticLayout.Builder
            .obtain(text, 0, text.length, fillPaint, textWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .build()

        val textX = boxRect.left + 18f
        val textY = boxRect.top + ((boxRect.height() - fillLayout.height) / 2f).coerceAtLeast(8f)

        canvas.save()
        canvas.translate(textX, textY)
        if (cfg.strokeWidth > 0) {
            strokeLayout.draw(canvas)
        }
        fillLayout.draw(canvas)
        canvas.restore()
    }
}
