package com.gamesub.tv.translate

import com.gamesub.tv.config.AppConfigManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max

/**
 * Multi-Engine Zero-Cost Translator for Android TV with Live Speed Benchmarking (ms):
 *  - `hybrid`: Google Fast Keep-Alive Neural (~55 ms)
 *  - `groq_fast`: Groq AI LLaMA-3.1-8B-Instant (~70 ms)
 *  - `lingva`: Lingva Open-Source Neural (~110 ms)
 *  - `groq_ai`: Groq AI LLaMA-3.3-70B (~135 ms)
 *  - `mymemory`: MyMemory Keep-Alive Neural (~165 ms)
 *  - `gemini_ai`: Google Gemini 2.0 Flash AI (~190 ms)
 */
class TvTranslator {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(2500, TimeUnit.MILLISECONDS)
        .readTimeout(3000, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val systemPrompt =
        "Sen profesyonel bir video oyunu çevirmenisin (İngilizce -> Türkçe). " +
        "Verilen oyun altyazısını bağlamına, oyun argosuna ve deyimlere dikkat ederek " +
        "doğal, akıcı bir konuşma Türkçesine çevir. Özel karakter/yer isimlerini çevirme. " +
        "SADECE Türkçe çeviriyi yaz, hiçbir açıklama ekleme."

    // Thread-safe LRU Cache
    private val cache = object : LinkedHashMap<String, String>(400, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > 500
        }
    }

    @Synchronized
    fun clearCache() {
        cache.clear()
    }

    @Synchronized
    private fun getCached(key: String): String? = cache[key.lowercase().trim()]

    @Synchronized
    private fun putCache(key: String, value: String) {
        if (key.isNotBlank() && value.isNotBlank()) {
            cache[key.lowercase().trim()] = value
        }
    }

    fun isSameDialogue(newText: String, prevText: String): Boolean {
        if (prevText.isBlank()) return false
        val a = newText.lowercase().trim()
        val b = prevText.lowercase().trim()
        if (a == b) return true

        val sim = similarityRatio(a, b)
        // Strict 0.98 threshold for short 1-4 word dialogues (< 32 chars)
        if (a.length < 32) return sim >= 0.98
        // Anti-flicker 0.84 threshold for long 2-3 line paragraphs (> 75 chars)
        if (a.length > 75 && abs(a.length - b.length) <= 4) return sim >= 0.84
        return sim >= 0.90
    }

    private fun similarityRatio(s1: String, s2: String): Double {
        val maxLen = max(s1.length, s2.length)
        if (maxLen == 0) return 1.0
        val dist = levenshteinDistance(s1, s2)
        return (maxLen - dist).toDouble() / maxLen.toDouble()
    }

    private fun levenshteinDistance(lhs: CharSequence, rhs: CharSequence): Int {
        val lhsLen = lhs.length
        val rhsLen = rhs.length
        var cost = IntArray(lhsLen + 1) { it }
        var newCost = IntArray(lhsLen + 1)
        for (i in 1..rhsLen) {
            newCost[0] = i
            for (j in 1..lhsLen) {
                val match = if (lhs[j - 1] == rhs[i - 1]) 0 else 1
                val costReplace = cost[j - 1] + match
                val costInsert = cost[j] + 1
                val costDelete = newCost[j - 1] + 1
                newCost[j] = minOf(costInsert, costDelete, costReplace)
            }
            val swap = cost
            cost = newCost
            newCost = swap
        }
        return cost[lhsLen]
    }

    fun translate(text: String, engine: String, apiKey: String): String {
        if (text.isBlank()) return ""
        getCached(text)?.let {
            AppConfigManager.lastUsedEngineLabel = "$engine (Önbellek)"
            return it
        }

        val result = when (engine.lowercase()) {
            "groq_fast" -> translateGroq(text, apiKey, "llama-3.1-8b-instant").ifBlank { translateGoogleFast(text) }
            "groq_ai" -> translateGroq(text, apiKey, "llama-3.3-70b-versatile").ifBlank { translateGoogleFast(text) }
            "gemini_ai" -> translateGemini(text, apiKey).ifBlank { translateGoogleFast(text) }
            "lingva" -> translateLingva(text).ifBlank { translateGoogleFast(text) }
            "mymemory" -> translateMyMemory(text).ifBlank { translateGoogleFast(text) }
            else -> translateGoogleFast(text).ifBlank { translateMyMemory(text) }
        }

        if (result.isNotBlank()) {
            putCache(text, result)
        }
        return result
    }

    fun translateGoogleFast(text: String): String {
        val q = URLEncoder.encode(text, "UTF-8")
        // 1. Primary GTX JSON endpoint
        try {
            val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=tr&dt=t&q=$q"
            val req = Request.Builder().url(url).header("User-Agent", userAgent).build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    val arr = JSONArray(body).optJSONArray(0)
                    if (arr != null) {
                        val sb = StringBuilder()
                        for (i in 0 until arr.length()) {
                            val seg = arr.optJSONArray(i)?.optString(0).orEmpty()
                            sb.append(seg)
                        }
                        val out = sb.toString().trim()
                        if (out.isNotEmpty()) {
                            AppConfigManager.lastUsedEngineLabel = "Google Hızlı Nöral"
                            return out
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }

        // 2. Fallback Clients5 Dict API
        try {
            val url2 = "https://clients5.google.com/translate_a/t?client=dict-chrome-ex&sl=en&tl=tr&q=$q"
            val req2 = Request.Builder().url(url2).header("User-Agent", userAgent).build()
            httpClient.newCall(req2).execute().use { resp ->
                if (resp.isSuccessful) {
                    val arr = JSONArray(resp.body?.string().orEmpty())
                    val first = arr.optString(0).trim()
                    if (first.isNotEmpty()) {
                        AppConfigManager.lastUsedEngineLabel = "Google Dict Nöral"
                        return first
                    }
                }
            }
        } catch (_: Exception) {
        }

        return ""
    }

    fun translateLingva(text: String): String {
        val q = URLEncoder.encode(text, "UTF-8")
        val hosts = listOf("https://lingva.ml", "https://translate.plausibility.cloud")
        for (host in hosts) {
            try {
                val req = Request.Builder()
                    .url("$host/api/v1/en/tr/$q")
                    .header("User-Agent", userAgent)
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val tr = JSONObject(resp.body?.string().orEmpty()).optString("translation").trim()
                        if (tr.isNotEmpty()) {
                            AppConfigManager.lastUsedEngineLabel = "Lingva Nöral"
                            return tr
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        return ""
    }

    fun translateMyMemory(text: String): String {
        val q = URLEncoder.encode(text.take(480), "UTF-8")
        try {
            val url = "https://api.mymemory.translated.net/get?q=$q&langpair=en|tr"
            val req = Request.Builder().url(url).header("User-Agent", userAgent).build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val obj = JSONObject(resp.body?.string().orEmpty())
                    val tr = obj.optJSONObject("responseData")?.optString("translatedText").orEmpty().trim()
                    if (tr.isNotEmpty() && !tr.uppercase().contains("MYMEMORY WARNING")) {
                        AppConfigManager.lastUsedEngineLabel = "MyMemory Nöral"
                        return tr
                    }
                }
            }
        } catch (_: Exception) {
        }
        return ""
    }

    fun translateGroq(text: String, apiKey: String, model: String): String {
        val cleanKey = apiKey.trim().trim('"', '\'')
        if (cleanKey.isEmpty()) return ""

        val payload = JSONObject().apply {
            put("model", model)
            put("temperature", 0.15)
            put("max_tokens", 180)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", systemPrompt))
                put(JSONObject().put("role", "user").put("content", text))
            })
        }

        try {
            val req = Request.Builder()
                .url("https://api.groq.com/openai/v1/chat/completions")
                .header("User-Agent", userAgent)
                .header("Authorization", "Bearer $cleanKey")
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val obj = JSONObject(resp.body?.string().orEmpty())
                    val content = obj.optJSONArray("choices")
                        ?.optJSONObject(0)
                        ?.optJSONObject("message")
                        ?.optString("content")
                        .orEmpty()
                        .trim()
                        .trim('"')
                    if (content.isNotEmpty()) {
                        AppConfigManager.lastUsedEngineLabel =
                            if (model.contains("8b")) "Groq AI 8B" else "Groq AI 70B"
                        return content
                    }
                }
            }
        } catch (_: Exception) {
        }
        return ""
    }

    fun translateGemini(text: String, apiKey: String): String {
        val cleanKey = apiKey.trim().trim('"', '\'')
        if (cleanKey.isEmpty()) return ""

        val payload = JSONObject().apply {
            put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
            put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", text)))))
            put("generationConfig", JSONObject().put("temperature", 0.15).put("maxOutputTokens", 180))
        }

        for (model in listOf("gemini-2.0-flash", "gemini-1.5-flash")) {
            try {
                val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$cleanKey"
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", userAgent)
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val obj = JSONObject(resp.body?.string().orEmpty())
                        val out = obj.optJSONArray("candidates")
                            ?.optJSONObject(0)
                            ?.optJSONObject("content")
                            ?.optJSONArray("parts")
                            ?.optJSONObject(0)
                            ?.optString("text")
                            .orEmpty()
                            .trim()
                            .trim('"')
                        if (out.isNotEmpty()) {
                            AppConfigManager.lastUsedEngineLabel = "Gemini Flash AI"
                            return out
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        return ""
    }

    fun benchmarkEngine(engineCode: String, apiKey: String): Triple<Boolean, Long, String> {
        val sample = "Hold on, I'll check the northern gate before midnight."
        val t0 = System.nanoTime()
        val out = when (engineCode) {
            "hybrid" -> translateGoogleFast(sample)
            "groq_fast" -> translateGroq(sample, apiKey, "llama-3.1-8b-instant")
            "groq_ai" -> translateGroq(sample, apiKey, "llama-3.3-70b-versatile")
            "lingva" -> translateLingva(sample)
            "mymemory" -> translateMyMemory(sample)
            "gemini_ai" -> translateGemini(sample, apiKey)
            else -> translateGoogleFast(sample)
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        return if (out.isNotBlank()) {
            Triple(true, ms, out)
        } else {
            val err = if (engineCode.startsWith("groq") || engineCode.startsWith("gemini")) {
                "API Anahtarı Eksik veya Hatalı"
            } else {
                "Sunucu yanıt vermedi"
            }
            Triple(false, ms, err)
        }
    }
}
