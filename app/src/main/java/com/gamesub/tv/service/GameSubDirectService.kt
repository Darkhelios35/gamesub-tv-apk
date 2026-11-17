package com.gamesub.tv.service

import android.annotation.SuppressLint
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
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
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
 * Direct Screen Capture & Subtitle Overlay Service (`MediaProjection` + Multi-Type Overlay).
 * - Acquires `PARTIAL_WAKE_LOCK` + `WifiLock` so switching to HDMI 1 never sleeps the service.
 * - Registers `MediaProjection.Callback` on a `HandlerThread` (Android 13/14 requirement)
 *   and captures at 1280x720 to avoid 4K buffer exhaustion when the HDMI scaler turns on.
 * - Falls back to `GameSubTvService.captureScreenExternal()` if MediaProjection sees a black HDMI frame.
 */
@SuppressLint("NewApi", "WakelockTimeout")
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
    private var projThread: HandlerThread? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

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
        acquireWakeLocks()
        startForeground(NOTIF_ID, createNotification())
        setupOverlayWindow()
    }

    private fun acquireWakeLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GameSubTV::WakeLock")?.apply {
                if (!isHeld) acquire()
            }
        } catch (_: Throwable) {
        }
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "GameSubTV::WifiLock")?.apply {
                if (!isHeld) acquire()
            }
        } catch (_: Throwable) {
        }
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
                val candidateView = TvSubtitleOverlayView(this)

                val candidateTypes = mutableListOf<Int>()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    candidateTypes.add(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                } else {
                    @Suppress("DEPRECATION")
                    candidateTypes.add(WindowManager.LayoutParams.TYPE_PHONE)
                }
                @Suppress("DEPRECATION")
                candidateTypes.add(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT)
                @Suppress("DEPRECATION")
                candidateTypes.add(WindowManager.LayoutParams.TYPE_TOAST)

                for (overlayType in candidateTypes) {
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

                    try {
                        windowManager?.addView(candidateView, params)
                        overlayView = candidateView
                        overlayView?.showSubtitle("GameSub TV Aktif (Doğrudan Mod) — Telefonunuzdan yönetebilirsiniz!")
                        break
                    } catch (_: Throwable) {
                    }
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun startMediaProjectionCapture(resultCode: Int, resultData: Intent) {
        try {
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            virtualDisplay?.release()
            imageReader?.close()
            mediaProjection?.stop()
            projThread?.quitSafely()

            projThread = HandlerThread("GameSubProjectionThread").apply { start() }
            val projHandler = Handler(projThread!!.looper)

            mediaProjection = mpManager.getMediaProjection(resultCode, resultData)?.apply {
                registerCallback(object : MediaProjection.Callback() {}, projHandler)
            }

            // Capture at fixed 1280x720 to prevent 4K buffer exhaustion when HDMI scaler activates
            val captureW = 1280
            val captureH = 720
            val density = resources.displayMetrics.densityDpi

            imageReader = ImageReader.newInstance(captureW, captureH, PixelFormat.RGBA_8888, 2)
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "GameSubTVDisplay",
                captureW,
                captureH,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                projHandler
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
                var fullBitmap = grabFrameFromReader()
                if (fullBitmap == null || GameSubTvService.isBitmapAllBlack(fullBitmap)) {
                    fullBitmap?.recycle()
                    fullBitmap = GameSubTvService.instance?.captureScreenExternal()
                }
                if (fullBitmap != null && GameSubTvService.isBitmapAllBlack(fullBitmap)) {
                    fullBitmap.recycle()
                    fullBitmap = null
                }

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
                                    if (cfg.forwardToTvIp.isNotBlank()) {
                                        // If running on Phone reading Xbox Remote Play, beam Turkish subtitle to TV!
                                        translator.forwardTurkishSubtitleToTv(cfg.forwardToTvIp, cleanedText, tr)
                                    } else {
                                        showSubtitleEverywhere(tr)
                                    }
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

    fun hasOverlay(): Boolean = overlayView != null

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
            projThread?.quitSafely()
        } catch (_: Throwable) {
        }
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            if (wifiLock?.isHeld == true) wifiLock?.release()
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
