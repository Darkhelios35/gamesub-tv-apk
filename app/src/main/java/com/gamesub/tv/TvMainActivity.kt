package com.gamesub.tv

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.wifi.WifiManager
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
import com.gamesub.tv.service.GameSubTvService
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Main Activity displayed on the Android TV screen.
 * - Starts the embedded HTTP server (`:8765`)
 * - Generates and displays the pairing QR Code (`http://<TV_IP>:8765`)
 * - Shows live service & phone pairing status
 */
class TvMainActivity : AppCompatActivity() {

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

        btnEnableAccessibility.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                Toast.makeText(
                    this,
                    "Ayarlar -> Erişilebilirlik menüsünden 'GameSub TV Canlı Altyazı Çevirici'yi açın.",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Erişilebilirlik menüsü açılamadı: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }

        btnToggleCapture.setOnClickListener {
            val cfg = AppConfigManager.currentConfig
            val obj = cfg.toJson().put("paused", !cfg.paused)
            val updated = AppConfigManager.updateFromJson(obj.toString())
            val stateMsg = if (updated.paused) "⏸ Çeviri Duraklatıldı" else "▶ Canlı Çeviri Başlatıldı"
            GameSubTvService.instance?.showTestSubtitle(stateMsg)
            refreshStatusLabels()
        }

        btnTestOverlay.setOnClickListener {
            val svc = GameSubTvService.instance
            if (svc == null) {
                Toast.makeText(this, "Önce 1. Buton ile TV Servis İznini Açmalısınız!", Toast.LENGTH_LONG).show()
            } else {
                svc.showTestSubtitle("🎯 GameSub TV Altyazı Testi — Telefonunuzdan konum ve boyutu değiştirebilirsiniz!")
            }
        }

        startRemoteServerAndQr()
    }

    private fun startRemoteServerAndQr() {
        val ip = getLocalIpAddress()
        val url = "http://$ip:8765"
        tvServerUrl.text = url
        ivQrCode.setImageBitmap(generateQrBitmap(url, 512))
        server = RemoteControlServer.ensureStarted(this)
    }

    private fun refreshStatusLabels() {
        val isServiceActive = GameSubTvService.instance != null
        if (isServiceActive) {
            tvServiceStatus.text = "✅ 1. Adım: TV Altyazı & Ekran Okuma Servisi AKTİF"
            tvServiceStatus.setTextColor(Color.parseColor("#34D399"))
        } else {
            tvServiceStatus.text = "⚠️ 1. Adım: TV Servisi Kapalı ('1. TV Servis İznini Aç' butonuna basın)"
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
        // Do NOT stop RemoteControlServer here so it keeps running in the background
        // when the user switches TV Source to HDMI (Xbox)!
        super.onDestroy()
    }
}
