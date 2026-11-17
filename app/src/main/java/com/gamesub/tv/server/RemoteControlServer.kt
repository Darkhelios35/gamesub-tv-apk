package com.gamesub.tv.server

import android.content.Context
import com.gamesub.tv.config.AppConfigManager
import com.gamesub.tv.ocr.MlKitOcrEngine
import com.gamesub.tv.service.GameSubDirectService
import com.gamesub.tv.service.GameSubTvService
import com.gamesub.tv.translate.TvTranslator
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.ByteArrayInputStream

/**
 * Embedded HTTP Server running inside the Android TV app on port 8765.
 * Works seamlessly with BOTH `GameSubDirectService` (Direct Mode) and `GameSubTvService` (Accessibility Mode).
 */
class RemoteControlServer(
    private val context: Context,
    port: Int = 8765
) : NanoHTTPD(port) {

    companion object {
        @Volatile
        private var sharedInstance: RemoteControlServer? = null

        @Synchronized
        fun ensureStarted(context: Context): RemoteControlServer {
            val existing = sharedInstance
            if (existing != null && existing.isAlive) {
                return existing
            }
            val server = RemoteControlServer(context.applicationContext, 8765)
            try {
                server.start()
            } catch (_: Exception) {
            }
            sharedInstance = server
            return server
        }
    }

    private val benchmarkTranslator by lazy { TvTranslator() }
    private val fallbackOcr by lazy { MlKitOcrEngine() }

    private fun pushSubtitleToActiveView(text: String) {
        GameSubTvService.instance?.showTestSubtitle(text)
        GameSubDirectService.instance?.showSubtitleEverywhere(text)
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: "/"
        val method = session.method

        AppConfigManager.phoneConnectedCount++

        return try {
            when {
                uri == "/" || uri == "/index.html" -> serveHtmlPanel()
                uri == "/api/config" && method == Method.GET -> {
                    val json = AppConfigManager.currentConfig.toJson().toString()
                    newFixedLengthResponse(Response.Status.OK, "application/json", json)
                }
                uri == "/api/config" && method == Method.POST -> {
                    val bodyMap = HashMap<String, String>()
                    session.parseBody(bodyMap)
                    val postData = bodyMap["postData"] ?: "{}"
                    val updated = AppConfigManager.updateFromJson(postData)
                    pushSubtitleToActiveView(
                        "✅ Telefon Ayarları TV'ye Uygulandı! (${updated.engine})"
                    )
                    val resp = JSONObject().put("ok", true).toString()
                    newFixedLengthResponse(Response.Status.OK, "application/json", resp)
                }
                uri == "/api/status" -> {
                    val isRunning = (GameSubTvService.instance != null) || (GameSubDirectService.instance != null)
                    val obj = JSONObject().apply {
                        put("serviceRunning", isRunning)
                        put("engine", AppConfigManager.lastUsedEngineLabel)
                        put("lastEn", AppConfigManager.lastEnglishText)
                        put("lastTr", AppConfigManager.lastTurkishText)
                    }
                    newFixedLengthResponse(Response.Status.OK, "application/json", obj.toString())
                }
                uri == "/api/screenshot" -> {
                    val jpeg = GameSubTvService.latestScreenshotJpeg
                    if (jpeg != null && jpeg.isNotEmpty()) {
                        newFixedLengthResponse(
                            Response.Status.OK,
                            "image/jpeg",
                            ByteArrayInputStream(jpeg),
                            jpeg.size.toLong()
                        )
                    } else {
                        newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "No screenshot yet")
                    }
                }
                uri == "/api/stop" && method == Method.POST -> {
                    GameSubTvService.instance?.hideAndStopOverlay()
                    GameSubDirectService.instance?.hideAndStop()
                    val resp = JSONObject().put("ok", true).put("msg", "TV Altyazı servisi durduruldu ve gizlendi.").toString()
                    newFixedLengthResponse(Response.Status.OK, "application/json", resp)
                }
                uri == "/api/display_tr" && method == Method.POST -> {
                    val bodyMap = HashMap<String, String>()
                    session.parseBody(bodyMap)
                    val postData = JSONObject(bodyMap["postData"] ?: "{}")
                    val en = postData.optString("en", "")
                    val tr = postData.optString("tr", "")
                    if (tr.isNotBlank()) {
                        AppConfigManager.lastEnglishText = en
                        AppConfigManager.lastTurkishText = tr
                        pushSubtitleToActiveView(tr)
                    }
                    val resp = JSONObject().put("ok", true).toString()
                    newFixedLengthResponse(Response.Status.OK, "application/json", resp)
                }
                uri == "/api/push_text" && method == Method.POST -> {
                    val bodyMap = HashMap<String, String>()
                    session.parseBody(bodyMap)
                    val postData = JSONObject(bodyMap["postData"] ?: "{}")
                    val enText = postData.optString("text", "")
                    val cleaned = fallbackOcr.cleanAndRepairOcrText(enText)
                    var tr = ""
                    if (cleaned.isNotEmpty()) {
                        val cfg = AppConfigManager.currentConfig
                        AppConfigManager.lastEnglishText = cleaned
                        tr = benchmarkTranslator.translate(cleaned, cfg.engine, cfg.apiKey)
                        if (tr.isNotBlank()) {
                            AppConfigManager.lastTurkishText = tr
                            pushSubtitleToActiveView(tr)
                        }
                    }
                    val resp = JSONObject().put("ok", true).put("tr", tr).toString()
                    newFixedLengthResponse(Response.Status.OK, "application/json", resp)
                }
                uri == "/api/ocr_frame" && method == Method.POST -> {
                    val bodyMap = HashMap<String, String>()
                    session.parseBody(bodyMap)
                    val postData = JSONObject(bodyMap["postData"] ?: "{}")
                    val b64 = postData.optString("imageBase64", "")
                    val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                    val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    var enOut = ""
                    var trOut = ""
                    if (bmp != null) {
                        val cleaned = kotlinx.coroutines.runBlocking {
                            fallbackOcr.recognizeMultiStage(bmp)
                        }
                        bmp.recycle()
                        if (cleaned.isNotEmpty()) {
                            val cfg = AppConfigManager.currentConfig
                            AppConfigManager.lastEnglishText = cleaned
                            enOut = cleaned
                            trOut = benchmarkTranslator.translate(cleaned, cfg.engine, cfg.apiKey)
                            if (trOut.isNotBlank()) {
                                AppConfigManager.lastTurkishText = trOut
                                pushSubtitleToActiveView(trOut)
                            }
                        }
                    }
                    val resp = JSONObject().apply {
                        put("ok", true)
                        put("en", enOut)
                        put("tr", trOut)
                    }.toString()
                    newFixedLengthResponse(Response.Status.OK, "application/json", resp)
                }
                uri == "/api/benchmark" && method == Method.POST -> {
                    val targetEngine = session.parameters["engine"]?.firstOrNull() ?: "all"
                    val cfg = AppConfigManager.currentConfig
                    val enginesToTest = if (targetEngine == "all") {
                        listOf("hybrid", "groq_fast", "lingva", "groq_ai", "mymemory", "gemini_ai")
                    } else {
                        listOf(targetEngine)
                    }

                    val speedsObj = JSONObject()
                    val reportLines = mutableListOf<String>()

                    for (code in enginesToTest) {
                        val (ok, ms, out) = benchmarkTranslator.benchmarkEngine(code, cfg.apiKey)
                        if (ok) {
                            speedsObj.put(code, "✅ $ms ms")
                            reportLines.add("✅ $code ($ms ms): $out")
                        } else {
                            speedsObj.put(code, "❌ Hata")
                            reportLines.add("❌ $code ($ms ms): $out")
                        }
                    }

                    if (enginesToTest.size == 1) {
                        val firstLine = reportLines.firstOrNull().orEmpty()
                        pushSubtitleToActiveView(firstLine)
                    }

                    val respObj = JSONObject().apply {
                        put("report", reportLines.joinToString("\n"))
                        put("speeds", speedsObj)
                    }
                    newFixedLengthResponse(Response.Status.OK, "application/json", respObj.toString())
                }
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
            }
        } catch (e: Exception) {
            val err = JSONObject().put("error", e.message ?: "Server error").toString()
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", err)
        }
    }

    private fun serveHtmlPanel(): Response {
        val html = context.assets.open("remote_panel.html").bufferedReader(Charsets.UTF_8).use {
            it.readText()
        }
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }
}
