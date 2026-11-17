package com.gamesub.tv.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat
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
import java.io.ByteArrayOutputStream

/**
 * Direct Screen Capture & Subtitle Overlay Service (`MediaProjection` + `TYPE_APPLICATION_OVERLAY`).
 * Works on 100% of Android TVs even when the TV firmware disables/blocks Accessibility Services!
 */
class GameSubDirectService : Service() {

    companion object {
        private const val CHANNEL_ID = "gamesub_tv_direct_channel"
        private const val NOTIF_ID = 1001
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        @Volatile
        var instance: GameSubDirectService? = null
            private set
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pipelineJob: Job? = null
    private var windowManager: WindowManager? = null
    private var overlayView: TvSubtitleOverlayView? = null

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private val ocrEngine by lazy { MlKitOcrEngine() }
    private val translator by lazy { TvTranslator() }

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

    override fun onCreate() {
        super.onCreate()
        instance = this
        AppConfigManager.init(this)
        RemoteControlServer.ensureStarted(this)
        AppConfigManager.addListener(configListener)
        startForeground(NOTIF_ID, createNotification())
        setupOverlayWindow()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode == Activity.RESULT_OK && resultData != null) {
            startMediaProjectionCapture(resultCode, resultData)
        }
        return START_STICKY
    }

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "GameSub TV Canlı Çeviri",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GameSub TV Aktif")
            .setContentText("Canlı altyazı çevirisi ve telefon kontrolü çalışıyor.")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
    }

    private fun setupOverlayWindow() {
        Handler(Looper.getMainLooper()).post {
            try {
                if (overlayView != null) return@post
                windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
                overlayView = TvSubtitleOverlayView(this)

                val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    overlayType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                }

                if (Settings.canDrawOverlays(this)) {
                    windowManager?.addView(overlayView, params)
                    overlayView?.showSubtitle("GameSub TV Aktif (Doğrudan Mod) — Telefonunuzdan yönetebilirsiniz!")
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun startMediaProjectionCapture(resultCode: Int, resultData: Intent) {
        try {
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection?.stop()
            mediaProjection = mpManager.getMediaProjection(resultCode, resultData)

            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            val screenW = metrics.widthPixels.coerceAtLeast(1280)
            val screenH = metrics.heightPixels.coerceAtLeast(720)
            val density = metrics.densityDpi

            imageReader?.close()
            imageReader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2)
            virtualDisplay?.release()
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "GameSubTVDisplay",
                screenW,
                screenH,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                null
            )

            startCaptureLoop()
        } catch (_: Throwable) {
        }
    }

    private fun startCaptureLoop() {
        pipelineJob?.cancel()
        pipelineJob = serviceScope.launch {
            while (isActive) {
                val cfg = AppConfigManager.currentConfig
                if (cfg.paused) {
                    delay(250)
                    continue
                }

                val t0 = System.currentTimeMillis()
                val fullBitmap = grabFrameFromReader()
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
                                showSubtitleEverywhere("")
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
                                    showSubtitleEverywhere(tr)
                                }
                            }
                        }
                    }
                    roiBitmap.recycle()
                }

                val elapsed = System.currentTimeMillis() - t0
                val waitMs = (cfg.scanIntervalMs - elapsed).coerceAtLeast(140L)
                delay(waitMs)
            }
        }
    }

    fun showSubtitleEverywhere(text: String) {
        if (text.isEmpty()) {
            overlayView?.clearSubtitle()
            GameSubTvService.instance?.showTestSubtitle("")
        } else {
            overlayView?.showSubtitle(text)
            GameSubTvService.instance?.showTestSubtitle(text)
        }
    }

    fun hideAndStop() {
        val cfg = AppConfigManager.currentConfig
        val obj = cfg.toJson().put("paused", true)
        AppConfigManager.updateFromJson(obj.toString())
        showSubtitleEverywhere("")
    }

    private fun grabFrameFromReader(): Bitmap? {
        val reader = imageReader ?: return null
        val image = try {
            reader.acquireLatestImage()
        } catch (_: Throwable) {
            null
        } ?: return null

        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width
            val paddedWidth = image.width + rowPadding / pixelStride

            val bmp = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(buffer)
            if (paddedWidth != image.width) {
                val cropped = Bitmap.createBitmap(bmp, 0, 0, image.width, image.height)
                bmp.recycle()
                cropped
            } else {
                bmp
            }
        } catch (_: Throwable) {
            null
        } finally {
            try {
                image.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun updatePhonePreviewJpeg(bitmap: Bitmap) {
        try {
            val scaled = Bitmap.createScaledBitmap(bitmap, 640, 360, true)
            val bos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 68, bos)
            GameSubTvService.updateLatestJpeg(bos.toByteArray())
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        instance = null
        AppConfigManager.removeListener(configListener)
        pipelineJob?.cancel()
        serviceScope.cancel()
        try {
            virtualDisplay?.release()
            imageReader?.close()
            mediaProjection?.stop()
        } catch (_: Throwable) {
        }
        overlayView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Throwable) {
            }
        }
        super.onDestroy()
    }
}
