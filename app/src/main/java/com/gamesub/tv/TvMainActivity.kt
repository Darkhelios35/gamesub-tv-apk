package com.gamesub.tv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.gamesub.tv.config.AppConfigManager
import com.gamesub.tv.server.RemoteControlServer
import com.gamesub.tv.service.GameSubDirectService
import com.gamesub.tv.service.GameSubTvService
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Main Activity displayed on the Android TV screen.
 * Supports TWO independent ways to run on any Android TV:
 *  1. Direct Mode (`MediaProjection` + `GameSubDirectService`): Works even on Android TVs whose
 *     firmware disables/blocks Accessibility Services!
 *  2. Accessibility Service Mode (`GameSubTvService`): Crash-proof background service.
 */
class TvMainActivity : AppCompatActivity() {

    companion object {
        private const val REQ_MEDIA_PROJECTION = 2001
        private const val REQ_OVERLAY_PERM = 2002
    }

    private var server: RemoteControlServer? = null
    private val uiHandler = Handler(Looper.getMainLooper())

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
            uiHandler.postDelayed(this, 1200)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tv_main)

        AppConfigManager.init(this)

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

        // Primary Recommended Button: Direct Mode (No Accessibility menu required!)
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

        // Secondary Button: Accessibility Settings (for TVs that support it)
        btnEnableAccessibility.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                Toast.makeText(
                    this,
                    "Erişilebilirlik -> GameSub TV'yi açın. (Eğer TV'niz otomatik kapatıyorsa yanındaki 'Hemen Başlat (Doğrudan Mod)' butonunu kullanın!)",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                startDirectModeWithPermissionCheck()
            }
        }

        btnTestOverlay.setOnClickListener {
            val directSvc = GameSubDirectService.instance
            val accSvc = GameSubTvService.instance
            if (directSvc == null && accSvc == null) {
                Toast.makeText(
                    this,
                    "Şimdi Doğrudan Mod başlatılıyor...",
                    Toast.LENGTH_SHORT
                ).show()
                startDirectModeWithPermissionCheck()
            } else {
                val msg = "🎯 GameSub TV Altyazı Testi Başarılı! Telefonunuzdan konumu değiştirebilirsiniz."
                directSvc?.showSubtitleEverywhere(msg)
                accSvc?.showTestSubtitle(msg)
            }
        }

        startRemoteServerAndQr()
    }

    private fun startDirectModeWithPermissionCheck() {
        // 1. Check Overlay Permission first; if not granted, try opening settings or proceed anyway
        if (!Settings.canDrawOverlays(this)) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                @Suppress("DEPRECATION")
                startActivityForResult(intent, REQ_OVERLAY_PERM)
                Toast.makeText(
                    this,
                    "Lütfen 'Diğer uygulamaların üzerinde göster' iznini açıp geri dönün.",
                    Toast.LENGTH_LONG
                ).show()
                return
            } catch (_: Throwable) {
                // Some Android TVs don't have the Manage Overlay screen; proceed directly to MediaProjection
            }
        }
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
        if (requestCode == REQ_OVERLAY_PERM) {
            requestMediaProjectionCapture()
        } else if (requestCode == REQ_MEDIA_PROJECTION) {
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
                    "✅ Doğrudan Mod Başlatıldı! Şimdi uygulamayı arka plana atıp oyununuzu açabilirsiniz.",
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
            val modeName = if (isDirectActive) "Doğrudan Mod" else "Erişilebilirlik Modu"
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
        super.onDestroy()
    }
}
