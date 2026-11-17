package com.gamesub.tv

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.media.tv.TvView
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.gamesub.tv.config.AppConfigManager
import com.gamesub.tv.server.RemoteControlServer
import com.gamesub.tv.service.GameSubDirectService
import com.gamesub.tv.service.GameSubTvService
import com.gamesub.tv.ui.TvSubtitleOverlayView
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Main Activity displayed on the Android TV screen.
 * Supports:
 *  1. Direct Mode (`MediaProjection` + `GameSubDirectService`) with `targetSdk = 22` auto-granted overlay.
 *  2. Accessibility Service Mode (`GameSubTvService`) with `canTakeScreenshot="true"` fallback.
 *  3. Built-in HDMI `TvView` Passthrough Mode so HDMI 1 (Xbox) can be rendered right inside the app.
 */
@SuppressLint("NewApi")
class TvMainActivity : AppCompatActivity() {

    companion object {
        private const val REQ_MEDIA_PROJECTION = 2001
    }

    private var server: RemoteControlServer? = null
    private val uiHandler = Handler(Looper.getMainLooper())

    private lateinit var hdmiTvView: TvView
    private lateinit var dashboardContainer: LinearLayout
    private lateinit var inAppOverlayContainer: FrameLayout
    private var inAppOverlayView: TvSubtitleOverlayView? = null
    private var lastSyncedSubtitle: String = ""

    private lateinit var tvServiceStatus: TextView
    private lateinit var tvPhoneStatus: TextView
    private lateinit var tvEngineStatus: TextView
    private lateinit var tvServerUrl: TextView
    private lateinit var ivQrCode: ImageView
    private lateinit var btnEnableAccessibility: Button
    private lateinit var btnToggleCapture: Button
    private lateinit var btnTestOverlay: Button

    private val statusRefreshRunnable = object : Runnable {
        override fun run() {
            refreshStatusLabels()
            syncInAppOverlayIfNeeded()
            uiHandler.postDelayed(this, 450)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tv_main)

        AppConfigManager.init(this)

        hdmiTvView = findViewById(R.id.hdmiTvView)
        dashboardContainer = findViewById(R.id.dashboardContainer)
        inAppOverlayContainer = findViewById(R.id.inAppOverlayContainer)

        tvServiceStatus = findViewById(R.id.tvServiceStatus)
        tvPhoneStatus = findViewById(R.id.tvPhoneStatus)
        tvEngineStatus = findViewById(R.id.tvEngineStatus)
        tvServerUrl = findViewById(R.id.tvServerUrl)
        ivQrCode = findViewById(R.id.ivQrCode)
        btnEnableAccessibility = findViewById(R.id.btnEnableAccessibility)
        btnToggleCapture = findViewById(R.id.btnToggleCapture)
        btnTestOverlay = findViewById(R.id.btnTestOverlay)

        btnToggleCapture.text = "▶ 1. Hemen Başlat (Doğrudan Mod)"
        btnEnableAccessibility.text = "⚙️ Alternatif: Erişilebilirlik İzni"
        btnTestOverlay.text = "📺 HDMI (Xbox) TV İçinde Aç / Test"

        // Setup in-app fallback overlay (used when HDMI TvView is active and service overlay isn't attached)
        inAppOverlayView = TvSubtitleOverlayView(this).also {
            inAppOverlayContainer.addView(
                it,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }

        // Primary Recommended Button: Direct Mode (No Accessibility menu or Settings UI needed!)
        btnToggleCapture.setOnClickListener {
            if (GameSubDirectService.instance != null || GameSubTvService.instance != null) {
                val cfg = AppConfigManager.currentConfig
                val obj = cfg.toJson().put("paused", !cfg.paused)
                val updated = AppConfigManager.updateFromJson(obj.toString())
                val stateMsg = if (updated.paused) "⏸ Çeviri Duraklatıldı" else "▶ Canlı Çeviri Aktif"
                GameSubDirectService.instance?.showSubtitleEverywhere(stateMsg)
                GameSubTvService.instance?.showTestSubtitle(stateMsg)
                refreshStatusLabels()
            } else {
                startDirectModeWithPermissionCheck()
            }
        }

        // Secondary Button: Accessibility Settings (now unlocked on sideloaded APKs via targetSdk = 22)
        btnEnableAccessibility.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                Toast.makeText(
                    this,
                    "Erişilebilirlik -> GameSub TV'yi açın. (Doğrudan Mod ile hibrit çalışır!)",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                startDirectModeWithPermissionCheck()
            }
        }

        // Built-in HDMI TvView Mode / Test Overlay
        btnTestOverlay.setOnClickListener {
            val tuned = tryOpenHdmiInsideApp()
            val msg = "🎯 GameSub TV Altyazı Testi Başarılı! Telefonunuzdan konumu değiştirebilirsiniz."
            GameSubDirectService.instance?.showSubtitleEverywhere(msg)
            GameSubTvService.instance?.showTestSubtitle(msg)
            if (!hasAnyServiceOverlay()) {
                inAppOverlayView?.showSubtitle(msg)
            }
            if (!tuned && GameSubDirectService.instance == null && GameSubTvService.instance == null) {
                Toast.makeText(
                    this,
                    "Dahili HDMI akışı bulunamadı; Doğrudan Mod başlatılıyor...",
                    Toast.LENGTH_SHORT
                ).show()
                startDirectModeWithPermissionCheck()
            }
        }

        startRemoteServerAndQr()
    }

    private fun hasAnyServiceOverlay(): Boolean {
        return (GameSubDirectService.instance?.hasOverlay() == true) ||
            (GameSubTvService.instance?.hasOverlay() == true)
    }

    private fun syncInAppOverlayIfNeeded() {
        if (hdmiTvView.visibility != View.VISIBLE) return
        if (hasAnyServiceOverlay()) {
            inAppOverlayView?.clearSubtitle()
            return
        }
        val latestTr = AppConfigManager.lastTurkishText
        if (latestTr.isNotBlank() && latestTr != lastSyncedSubtitle) {
            lastSyncedSubtitle = latestTr
            inAppOverlayView?.showSubtitle(latestTr)
        }
    }

    private fun tryOpenHdmiInsideApp(): Boolean {
        return try {
            val tvInputManager = getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager ?: return false
            val hdmiInputs = tvInputManager.tvInputList.filter { it.type == TvInputInfo.TYPE_HDMI }
            if (hdmiInputs.isEmpty()) {
                return false
            }

            // Pick the first HDMI input (typically HDMI 1 / Xbox)
            val targetInput = hdmiInputs.firstOrNull {
                val lbl = it.loadLabel(this)?.toString().orEmpty()
                lbl.contains("1") || lbl.contains("xbox", ignoreCase = true)
            } ?: hdmiInputs.first()

            val channelUri = TvContract.buildChannelUriForPassthroughInput(targetInput.id)
            dashboardContainer.visibility = View.GONE
            hdmiTvView.visibility = View.VISIBLE
            hdmiTvView.tune(targetInput.id, channelUri)

            val label = targetInput.loadLabel(this)?.toString() ?: "HDMI 1"
            Toast.makeText(
                this,
                "📺 $label uygulama içinde açıldı! Menüyü açmak için kumandadan Geri (Back) tuşuna basın.",
                Toast.LENGTH_LONG
            ).show()
            true
        } catch (_: Throwable) {
            hdmiTvView.visibility = View.GONE
            dashboardContainer.visibility = View.VISIBLE
            false
        }
    }

    private fun exitHdmiInsideApp() {
        try {
            hdmiTvView.reset()
        } catch (_: Throwable) {
        }
        hdmiTvView.visibility = View.GONE
        inAppOverlayView?.clearSubtitle()
        dashboardContainer.visibility = View.VISIBLE
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && hdmiTvView.visibility == View.VISIBLE) {
            exitHdmiInsideApp()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (hdmiTvView.visibility == View.VISIBLE) {
            exitHdmiInsideApp()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    private fun startDirectModeWithPermissionCheck() {
        // With targetSdk = 22, SYSTEM_ALERT_WINDOW is auto-granted on Android TV!
        // Go straight to MediaProjection screen capture prompt without getting stuck on Settings UI.
        requestMediaProjectionCapture()
    }

    private fun requestMediaProjectionCapture() {
        try {
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            @Suppress("DEPRECATION")
            startActivityForResult(mpManager.createScreenCaptureIntent(), REQ_MEDIA_PROJECTION)
        } catch (e: Throwable) {
            Toast.makeText(this, "Ekran yakalama başlatılamadı: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_MEDIA_PROJECTION) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                val svcIntent = Intent(this, GameSubDirectService::class.java).apply {
                    putExtra(GameSubDirectService.EXTRA_RESULT_CODE, resultCode)
                    putExtra(GameSubDirectService.EXTRA_RESULT_DATA, data)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(svcIntent)
                } else {
                    startService(svcIntent)
                }
                Toast.makeText(
                    this,
                    "✅ Doğrudan Mod Başlatıldı! Şimdi 'HDMI (Xbox) TV İçinde Aç' veya TV kumandasından HDMI kaynağına geçebilirsiniz.",
                    Toast.LENGTH_LONG
                ).show()
                refreshStatusLabels()
            }
        }
    }

    private fun startRemoteServerAndQr() {
        val ip = getLocalIpAddress()
        val url = "http://$ip:8765"
        tvServerUrl.text = url
        ivQrCode.setImageBitmap(generateQrBitmap(url, 512))
        server = RemoteControlServer.ensureStarted(this)
    }

    private fun refreshStatusLabels() {
        val isDirectActive = GameSubDirectService.instance != null
        val isAccActive = GameSubTvService.instance != null

        if (isDirectActive || isAccActive) {
            val modeName = when {
                isDirectActive && isAccActive -> "Hibrit (Doğrudan + Erişilebilirlik)"
                isDirectActive -> "Doğrudan Mod"
                else -> "Erişilebilirlik Modu"
            }
            tvServiceStatus.text = "✅ 1. Adım: TV Altyazı & Ekran Okuma Servisi AKTİF ($modeName)"
            tvServiceStatus.setTextColor(Color.parseColor("#34D399"))
        } else {
            tvServiceStatus.text = "👉 1. Adım: Aşağıdaki yeşil '▶ 1. Hemen Başlat (Doğrudan Mod)' butonuna basın"
            tvServiceStatus.setTextColor(Color.parseColor("#FBBF24"))
        }

        if (AppConfigManager.phoneConnectedCount > 0) {
            tvPhoneStatus.text = "🟢 2. Adım: Telefon Eşleşti! (Ayarlar telefondan canlı yönetiliyor)"
            tvPhoneStatus.setTextColor(Color.parseColor("#34D399"))
        } else {
            tvPhoneStatus.text = "📱 2. Adım: Sağdaki QR kodu telefonunuzla okutarak bağlanın"
            tvPhoneStatus.setTextColor(Color.parseColor("#E2E8F0"))
        }

        val cfg = AppConfigManager.currentConfig
        val stateTag = if (cfg.paused) "DURAKLATILDI" else "ÇALIŞIYOR"
        tvEngineStatus.text = "⚡ Motor: ${cfg.engine.uppercase()} | Font: ${cfg.fontSize}sp | Durum: $stateTag"
    }

    private fun getLocalIpAddress(): String {
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val ipInt = wifiManager?.connectionInfo?.ipAddress ?: 0
            if (ipInt != 0) {
                return String.format(
                    "%d.%d.%d.%d",
                    ipInt and 0xff,
                    ipInt shr 8 and 0xff,
                    ipInt shr 16 and 0xff,
                    ipInt shr 24 and 0xff
                )
            }
        } catch (_: Exception) {
        }

        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress ?: "192.168.1.1"
                    }
                }
            }
        } catch (_: Exception) {
        }
        return "127.0.0.1"
    }

    private fun generateQrBitmap(content: String, size: Int): Bitmap {
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bmp.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
        return bmp
    }

    override fun onResume() {
        super.onResume()
        uiHandler.post(statusRefreshRunnable)
    }

    override fun onPause() {
        uiHandler.removeCallbacks(statusRefreshRunnable)
        super.onPause()
    }

    override fun onDestroy() {
        if (hdmiTvView.visibility == View.VISIBLE) {
            try {
                hdmiTvView.reset()
            } catch (_: Throwable) {
            }
        }
        super.onDestroy()
    }
}
