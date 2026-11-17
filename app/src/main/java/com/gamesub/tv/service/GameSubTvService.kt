package com.gamesub.tv.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.gamesub.tv.config.AppConfigManager
import com.gamesub.tv.config.TvSubConfig
import com.gamesub.tv.ocr.MlKitOcrEngine
import com.gamesub.tv.server.RemoteControlServer
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

/**
 * Crash-Proof Android TV Accessibility Service.
 * - Uses `by lazy` initialization for ML Kit so `<init>()` NEVER crashes before `attachBaseContext()`!
 * - Attaches `TYPE_ACCESSIBILITY_OVERLAY` safely on the main Looper after a 400ms token-registration delay.
 */
class GameSubTvService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: GameSubTvService? = null
            private set

        @Volatile
        var latestScreenshotJpeg: ByteArray? = null
            private set

        fun updateLatestJpeg(bytes: ByteArray) {
            latestScreenshotJpeg = bytes
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pipelineJob: Job? = null
    private var windowManager: WindowManager? = null
    private var overlayView: TvSubtitleOverlayView? = null
    private val screenshotExecutor = Executors.newSingleThreadExecutor()

    // IMPORTANT: Must be `by lazy` so ML Kit is NOT initialized inside <init>() before Context is attached!
    val ocrEngine by lazy { MlKitOcrEngine() }
    val translator by lazy { TvTranslator() }

    private var prevHash: Long = 0L
    private var lastTranslatedDialogue: String = ""
    private var emptyStreak: Int = 0

    private val configListener: (TvSubConfig) -> Unit = { newCfg ->
        try {
            translator.clearCache()
            lastTranslatedDialogue = ""
            prevHash = 0L
            overlayView?.applyConfig(newCfg)
        } catch (_: Throwable) {
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            instance = this
            AppConfigManager.init(this)
            RemoteControlServer.ensureStarted(this)
            AppConfigManager.addListener(configListener)

            // Wait 400ms on Main Looper so Android TV WindowManager finishes registering the Accessibility token
            Handler(Looper.getMainLooper()).postDelayed({
                setupOverlayWindow()
                startCapturePipeline()
            }, 400)
        } catch (_: Throwable) {
        }
    }

    private fun setupOverlayWindow() {
        try {
            if (overlayView != null) return
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

            windowManager?.addView(overlayView, params)
            overlayView?.showSubtitle("GameSub TV Aktif — Telefonunuzdan QR kodu okutarak yönetebilirsiniz.")
        } catch (_: Throwable) {
        }
    }

    fun showTestSubtitle(text: String) {
        try {
            if (text.isEmpty()) overlayView?.clearSubtitle()
            else overlayView?.showSubtitle(text)
        } catch (_: Throwable) {
        }
    }

    fun hideAndStopOverlay() {
        try {
            val cfg = AppConfigManager.currentConfig
            val obj = cfg.toJson().put("paused", true)
            AppConfigManager.updateFromJson(obj.toString())
            overlayView?.clearSubtitle()
            GameSubDirectService.instance?.hideAndStop()
        } catch (_: Throwable) {
        }
    }

    fun translateAndDisplayExternalText(rawEnglish: String): String {
        return try {
            val cleaned = ocrEngine.cleanAndRepairOcrText(rawEnglish)
            if (cleaned.isEmpty()) return ""
            val cfg = AppConfigManager.currentConfig
            AppConfigManager.lastEnglishText = cleaned
            val tr = translator.translate(cleaned, cfg.engine, cfg.apiKey)
            if (tr.isNotBlank()) {
                AppConfigManager.lastTurkishText = tr
                overlayView?.showSubtitle(tr)
                GameSubDirectService.instance?.showSubtitleEverywhere(tr)
            }
            tr
        } catch (_: Throwable) {
            ""
        }
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
                        GameSubDirectService.instance?.showSubtitleEverywhere(tr)
                    }
                }
                Pair(AppConfigManager.lastEnglishText, AppConfigManager.lastTurkishText)
            }
        } catch (_: Throwable) {
            Pair("", "")
        } finally {
            bmp.recycle()
        }
    }

    private fun startCapturePipeline() {
        pipelineJob?.cancel()
        pipelineJob = serviceScope.launch {
            while (isActive) {
                val cfg = AppConfigManager.currentConfig
                // If Direct MediaProjection service is already running, let it handle screen capture
                if (cfg.paused || GameSubDirectService.instance != null) {
                    delay(300)
                    continue
                }

                val t0 = System.currentTimeMillis()
                val fullBitmap = captureTvScreen()
                if (fullBitmap != null) {
                    updatePhonePreviewJpeg(fullBitmap)

                    val rx = (fullBitmap.width * cfg.roiX).toInt().coerceIn(0, fullBitmap.width - 32)
                    val ry = (fullBitmap.height * cfg.roiY).toInt().coerceIn(0, fullBitmap.height - 24)
                    val rw = (fullBitmap.width * cfg.roiW).toInt().coerceIn(32, fullBitmap.width - rx)
                    val rh = (fullBitmap.height * cfg.roiH).toInt().coerceIn(24, fullBitmap.height - ry)

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
                val waitMs = (cfg.scanIntervalMs - elapsed).coerceAtLeast(150L)
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
        } catch (_: Throwable) {
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
                            } catch (_: Throwable) {
                                if (cont.isActive) cont.resume(null)
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                )
            } catch (_: Throwable) {
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
            } catch (_: Throwable) {
            }
        }
        super.onDestroy()
    }
}
