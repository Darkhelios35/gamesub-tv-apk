package com.gamesub.tv.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.PixelFormat
import android.os.Build
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.gamesub.tv.config.AppConfigManager
import com.gamesub.tv.config.TvSubConfig
import com.gamesub.tv.ocr.MlKitOcrEngine
import com.gamesub.tv.translate.TvTranslator
import com.gamesub.tv.ui.TvSubtitleOverlayView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.math.abs

/**
 * Android TV Background Accessibility & Subtitle Overlay Service.
 * - Attaches a full-screen click-through `TvSubtitleOverlayView` using `TYPE_ACCESSIBILITY_OVERLAY`
 *   (requires zero hidden "Draw Over Other Apps" permissions on Android TV!).
 * - Captures the screen silently via Android 11+ `takeScreenshot` API, crops the ROI configured
 *   from the user's phone, runs ML Kit OCR + TvTranslator, and displays the Turkish subtitle.
 */
class GameSubTvService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: GameSubTvService? = null
            private set

        @Volatile
        var latestScreenshotJpeg: ByteArray? = null
            private set
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pipelineJob: Job? = null
    private var windowManager: WindowManager? = null
    private var overlayView: TvSubtitleOverlayView? = null
    private val screenshotExecutor = Executors.newSingleThreadExecutor()

    val ocrEngine = MlKitOcrEngine()
    val translator = TvTranslator()

    private var prevHash: Long = 0L
    private var lastTranslatedDialogue: String = ""
    private var emptyStreak: Int = 0

    private val configListener: (TvSubConfig) -> Unit = { newCfg ->
        translator.clearCache()
        lastTranslatedDialogue = ""
        prevHash = 0L
        overlayView?.applyConfig(newCfg)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        AppConfigManager.init(this)
        com.gamesub.tv.server.RemoteControlServer.ensureStarted(this)
        AppConfigManager.addListener(configListener)
        setupOverlayWindow()
        startCapturePipeline()
    }

    private fun setupOverlayWindow() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        overlayView = TvSubtitleOverlayView(this)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        try {
            windowManager?.addView(overlayView, params)
            overlayView?.showSubtitle("GameSub TV Aktif — Telefonunuzdan QR kodu okutarak yönetebilirsiniz.")
        } catch (_: Exception) {
        }
    }

    fun showTestSubtitle(text: String) {
        overlayView?.showSubtitle(text)
    }

    fun hideAndStopOverlay() {
        val cfg = AppConfigManager.currentConfig
        val obj = cfg.toJson().put("paused", true)
        AppConfigManager.updateFromJson(obj.toString())
        overlayView?.clearSubtitle()
    }

    fun translateAndDisplayExternalText(rawEnglish: String): String {
        val cleaned = ocrEngine.cleanAndRepairOcrText(rawEnglish)
        if (cleaned.isEmpty()) return ""
        val cfg = AppConfigManager.currentConfig
        AppConfigManager.lastEnglishText = cleaned
        val tr = translator.translate(cleaned, cfg.engine, cfg.apiKey)
        if (tr.isNotBlank()) {
            AppConfigManager.lastTurkishText = tr
            overlayView?.showSubtitle(tr)
        }
        return tr
    }

    suspend fun processExternalCameraFrame(jpegBytes: ByteArray): Pair<String, String> {
        val bmp = android.graphics.BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
            ?: return Pair("", "")
        return try {
            val cleaned = ocrEngine.recognizeMultiStage(bmp)
            if (cleaned.isEmpty()) {
                Pair("", "")
            } else {
                val cfg = AppConfigManager.currentConfig
                if (!translator.isSameDialogue(cleaned, lastTranslatedDialogue)) {
                    lastTranslatedDialogue = cleaned
                    AppConfigManager.lastEnglishText = cleaned
                    val tr = translator.translate(cleaned, cfg.engine, cfg.apiKey)
                    if (tr.isNotBlank()) {
                        AppConfigManager.lastTurkishText = tr
                        overlayView?.showSubtitle(tr)
                    }
                }
                Pair(AppConfigManager.lastEnglishText, AppConfigManager.lastTurkishText)
            }
        } finally {
            bmp.recycle()
        }
    }

    private fun startCapturePipeline() {
        pipelineJob?.cancel()
        pipelineJob = serviceScope.launch {
            while (isActive) {
                val cfg = AppConfigManager.currentConfig
                if (cfg.paused) {
                    delay(250)
                    continue
                }

                val t0 = System.currentTimeMillis()
                val fullBitmap = captureTvScreen()
                if (fullBitmap != null) {
                    // Save a compressed JPEG snapshot for the Phone's "TV Ekranını Getir" feature
                    updatePhonePreviewJpeg(fullBitmap)

                    // Crop the ROI region selected on the phone
                    val rx = (fullBitmap.width * cfg.roiX).toInt().coerceIn(0, fullBitmap.width - 32)
                    val ry = (fullBitmap.height * cfg.roiY).toInt().coerceIn(0, fullBitmap.height - 24)
                    val rw = (fullBitmap.width * cfg.roiW).toInt().coerceIn(32, fullBitmap.width - rx)
                    val rh = (fullBitmap.height * cfg.roiH).toInt().coerceIn(24, fullBitmap.height - ry)

                    // Avoid reading our own Turkish overlay if ROI and Overlay overlap vertically
                    val roiBitmap = Bitmap.createBitmap(fullBitmap, rx, ry, rw, rh)
                    fullBitmap.recycle()

                    val currHash = computeFastHash(roiBitmap)
                    val changed = (prevHash == 0L) || (java.lang.Long.bitCount(currHash xor prevHash) >= 1)
                    prevHash = currHash

                    if (changed) {
                        val cleanedText = ocrEngine.recognizeMultiStage(roiBitmap)
                        if (cleanedText.isEmpty()) {
                            emptyStreak++
                            if (emptyStreak >= 5 && lastTranslatedDialogue.isNotEmpty()) {
                                lastTranslatedDialogue = ""
                                overlayView?.clearSubtitle()
                            }
                        } else {
                            emptyStreak = 0
                            // Ignore if OCR accidentally read our own Turkish translation
                            if (!translator.isSameDialogue(cleanedText, lastTranslatedDialogue) &&
                                !translator.isSameDialogue(cleanedText, AppConfigManager.lastTurkishText)
                            ) {
                                lastTranslatedDialogue = cleanedText
                                AppConfigManager.lastEnglishText = cleanedText

                                val tr = translator.translate(cleanedText, cfg.engine, cfg.apiKey)
                                if (tr.isNotBlank()) {
                                    AppConfigManager.lastTurkishText = tr
                                    overlayView?.showSubtitle(tr)
                                }
                            }
                        }
                    }
                    roiBitmap.recycle()
                }

                val elapsed = System.currentTimeMillis() - t0
                val waitMs = (cfg.scanIntervalMs - elapsed).coerceAtLeast(120L)
                delay(waitMs)
            }
        }
    }

    private fun updatePhonePreviewJpeg(bitmap: Bitmap) {
        try {
            val scaled = Bitmap.createScaledBitmap(bitmap, 640, 360, true)
            val bos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 68, bos)
            latestScreenshotJpeg = bos.toByteArray()
            scaled.recycle()
        } catch (_: Exception) {
        }
    }

    private fun computeFastHash(bmp: Bitmap): Long {
        val small = Bitmap.createScaledBitmap(bmp, 9, 8, true)
        var hash = 0L
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                val p1 = small.getPixel(x, y) and 0xFF
                val p2 = small.getPixel(x + 1, y) and 0xFF
                val bit = if (p2 > p1) 1L else 0L
                hash = (hash shl 1) or bit
            }
        }
        small.recycle()
        return hash
    }

    private suspend fun captureTvScreen(): Bitmap? = suspendCancellableCoroutine { cont ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    screenshotExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            try {
                                val hwBuffer = screenshot.hardwareBuffer
                                val hwBmp = Bitmap.wrapHardwareBuffer(hwBuffer, screenshot.colorSpace)
                                val softBmp = hwBmp?.copy(Bitmap.Config.ARGB_8888, false)
                                hwBmp?.recycle()
                                hwBuffer.close()
                                if (cont.isActive) cont.resume(softBmp)
                            } catch (_: Exception) {
                                if (cont.isActive) cont.resume(null)
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                )
            } catch (_: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        } else {
            if (cont.isActive) cont.resume(null)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        AppConfigManager.removeListener(configListener)
        pipelineJob?.cancel()
        serviceScope.cancel()
        overlayView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {
            }
        }
        super.onDestroy()
    }
}
