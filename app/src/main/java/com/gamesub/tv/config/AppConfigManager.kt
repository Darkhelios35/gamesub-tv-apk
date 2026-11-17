package com.gamesub.tv.config

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

data class TvSubConfig(
    var roiX: Float = 0.18f,
    var roiY: Float = 0.76f,
    var roiW: Float = 0.64f,
    var roiH: Float = 0.16f,
    var ovlX: Float = 0.18f,
    var ovlY: Float = 0.78f,
    var ovlW: Float = 0.64f,
    var ovlH: Float = 0.14f,
    var engine: String = "hybrid",
    var apiKey: String = "",
    var fontSize: Int = 26,
    var bgOpacity: Int = 175,
    var textColor: String = "#FFFFFF",
    var strokeWidth: Int = 4,
    var autoHideSec: Int = 6,
    var scanIntervalMs: Int = 320,
    var paused: Boolean = false,
    var forwardToTvIp: String = ""
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("roiX", roiX.toDouble())
            put("roiY", roiY.toDouble())
            put("roiW", roiW.toDouble())
            put("roiH", roiH.toDouble())
            put("ovlX", ovlX.toDouble())
            put("ovlY", ovlY.toDouble())
            put("ovlW", ovlW.toDouble())
            put("ovlH", ovlH.toDouble())
            put("engine", engine)
            put("apiKey", apiKey)
            put("fontSize", fontSize)
            put("bgOpacity", bgOpacity)
            put("textColor", textColor)
            put("strokeWidth", strokeWidth)
            put("autoHideSec", autoHideSec)
            put("scanIntervalMs", scanIntervalMs)
            put("paused", paused)
            put("forwardToTvIp", forwardToTvIp)
        }
    }

    companion object {
        fun fromJson(obj: JSONObject): TvSubConfig {
            return TvSubConfig(
                roiX = obj.optDouble("roiX", 0.18).toFloat().coerceIn(0f, 0.9f),
                roiY = obj.optDouble("roiY", 0.76).toFloat().coerceIn(0f, 0.9f),
                roiW = obj.optDouble("roiW", 0.64).toFloat().coerceIn(0.1f, 1.0f),
                roiH = obj.optDouble("roiH", 0.16).toFloat().coerceIn(0.05f, 1.0f),
                ovlX = obj.optDouble("ovlX", 0.18).toFloat().coerceIn(0f, 0.9f),
                ovlY = obj.optDouble("ovlY", 0.78).toFloat().coerceIn(0f, 0.9f),
                ovlW = obj.optDouble("ovlW", 0.64).toFloat().coerceIn(0.15f, 1.0f),
                ovlH = obj.optDouble("ovlH", 0.14).toFloat().coerceIn(0.05f, 1.0f),
                engine = obj.optString("engine", "hybrid"),
                apiKey = obj.optString("apiKey", "").trim(),
                fontSize = obj.optInt("fontSize", 26).coerceIn(12, 64),
                bgOpacity = obj.optInt("bgOpacity", 175).coerceIn(0, 250),
                textColor = obj.optString("textColor", "#FFFFFF"),
                strokeWidth = obj.optInt("strokeWidth", 4).coerceIn(0, 12),
                autoHideSec = obj.optInt("autoHideSec", 6).coerceIn(2, 30),
                scanIntervalMs = obj.optInt("scanIntervalMs", 320).coerceIn(150, 2000),
                paused = obj.optBoolean("paused", false),
                forwardToTvIp = obj.optString("forwardToTvIp", "").trim()
            )
        }
    }
}

object AppConfigManager {
    private const val PREF_NAME = "gamesub_tv_prefs"
    private const val KEY_CONFIG_JSON = "config_json"

    @Volatile
    var currentConfig: TvSubConfig = TvSubConfig()
        private set

    @Volatile
    var lastEnglishText: String = ""

    @Volatile
    var lastTurkishText: String = ""

    @Volatile
    var lastUsedEngineLabel: String = "Google Hızlı Nöral"

    @Volatile
    var phoneConnectedCount: Int = 0

    private var prefs: SharedPreferences? = null
    private val listeners = CopyOnWriteArrayList<(TvSubConfig) -> Unit>()

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val saved = prefs?.getString(KEY_CONFIG_JSON, null)
            if (!saved.isNullOrBlank()) {
                try {
                    currentConfig = TvSubConfig.fromJson(JSONObject(saved))
                } catch (_: Exception) {
                }
            }
        }
    }

    fun updateFromJson(jsonStr: String): TvSubConfig {
        val obj = JSONObject(jsonStr)
        val updated = TvSubConfig.fromJson(obj)
        currentConfig = updated
        prefs?.edit()?.putString(KEY_CONFIG_JSON, updated.toJson().toString())?.apply()
        listeners.forEach { it.invoke(updated) }
        return updated
    }

    fun addListener(listener: (TvSubConfig) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (TvSubConfig) -> Unit) {
        listeners.remove(listener)
    }
}
