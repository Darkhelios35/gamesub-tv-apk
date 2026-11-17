package com.gamesub.tv.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 100% Free On-Device OCR Engine using Google ML Kit Text Recognition v2.
 * Includes:
 *  - Quiet Margin Padding (prevents edge-clipping of subtitles)
 *  - Center-Region Crop Fallback (catches short 1-4 word centered dialogues inside wide ROI boxes)
 *  - English Contraction & ALL-CAPS Repair ("Ill" -> "I'll", "Id" -> "I'd", "wont" -> "won't")
 */
class MlKitOcrEngine {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private val contractionRepairs = listOf(
        Regex("\\b(?:Ill|lll|I11)\\b(?=\\s+[a-z])") to "I'll",
        Regex("\\b(?:Id|ld)\\b(?=\\s+(?:like|love|better|rather|have|be|say|go|do|think|know|never|take|give|make|tell|see|want|need)\\b)", RegexOption.IGNORE_CASE) to "I'd",
        Regex("\\bIm\\b") to "I'm",
        Regex("\\bIve\\b", RegexOption.IGNORE_CASE) to "I've",
        Regex("\\bdont\\b", RegexOption.IGNORE_CASE) to "don't",
        Regex("\\bcant\\b", RegexOption.IGNORE_CASE) to "can't",
        Regex("\\bwont\\b", RegexOption.IGNORE_CASE) to "won't",
        Regex("\\bdidnt\\b", RegexOption.IGNORE_CASE) to "didn't",
        Regex("\\bdoesnt\\b", RegexOption.IGNORE_CASE) to "doesn't",
        Regex("\\bisnt\\b", RegexOption.IGNORE_CASE) to "isn't",
        Regex("\\barent\\b", RegexOption.IGNORE_CASE) to "aren't",
        Regex("\\bwasnt\\b", RegexOption.IGNORE_CASE) to "wasn't",
        Regex("\\bwerent\\b", RegexOption.IGNORE_CASE) to "weren't",
        Regex("\\bhavent\\b", RegexOption.IGNORE_CASE) to "haven't",
        Regex("\\bwouldnt\\b", RegexOption.IGNORE_CASE) to "wouldn't",
        Regex("\\bcouldnt\\b", RegexOption.IGNORE_CASE) to "couldn't",
        Regex("\\bshouldnt\\b", RegexOption.IGNORE_CASE) to "shouldn't",
        Regex("\\byoure\\b", RegexOption.IGNORE_CASE) to "you're",
        Regex("\\btheyre\\b", RegexOption.IGNORE_CASE) to "they're",
        Regex("\\bthats\\b", RegexOption.IGNORE_CASE) to "that's",
        Regex("\\bwhats\\b", RegexOption.IGNORE_CASE) to "what's",
        Regex("^Lets\\b") to "Let's",
        Regex("\\bl\\b(?=\\s+(?:am|was|will|would|should|could|can|can't|don't|didn't|won't|have|had|need|want|think|know|see|mean)\\b)") to "I"
    )

    suspend fun recognizeMultiStage(roiBitmap: Bitmap): String {
        // Stage 1: Full ROI with 14px dark quiet margin
        val paddedFull = addQuietMargin(roiBitmap, 14)
        val rawFull = runMlKit(paddedFull)
        val cleanedFull = cleanAndRepairOcrText(rawFull)
        if (cleanedFull.isNotEmpty()) {
            return cleanedFull
        }

        // Stage 2: Center 54% Crop (catches short 1-4 word centered dialogues in wide boxes)
        if (roiBitmap.width >= 240) {
            val cropW = (roiBitmap.width * 0.54f).toInt().coerceAtLeast(120)
            val left = (roiBitmap.width - cropW) / 2
            val centerCrop = Bitmap.createBitmap(roiBitmap, left, 0, cropW, roiBitmap.height)
            val paddedCenter = addQuietMargin(centerCrop, 14)
            val rawCenter = runMlKit(paddedCenter)
            val cleanedCenter = cleanAndRepairOcrText(rawCenter)
            if (cleanedCenter.isNotEmpty()) {
                return cleanedCenter
            }
        }

        return ""
    }

    private fun addQuietMargin(src: Bitmap, pad: Int): Bitmap {
        val out = Bitmap.createBitmap(src.width + pad * 2, src.height + pad * 2, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.rgb(12, 12, 12))
        canvas.drawBitmap(src, pad.toFloat(), pad.toFloat(), null)
        return out
    }

    private suspend fun runMlKit(bitmap: Bitmap): String = suspendCancellableCoroutine { cont ->
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val joined = visionText.textBlocks.joinToString(" ") { block ->
                    block.lines.joinToString(" ") { it.text.trim() }
                }.trim()
                if (cont.isActive) cont.resume(joined)
            }
            .addOnFailureListener {
                if (cont.isActive) cont.resume("")
            }
    }

    fun cleanAndRepairOcrText(raw: String): String {
        if (raw.isBlank()) return ""

        var text = raw
            .replace("|", "I")
            .replace("—", "-")
            .replace("…", "...")
            .replace(Regex("\\s+"), " ")
            .trim()

        // Strip leading/trailing non-dialogue symbols
        text = text.replace(Regex("^[^A-Za-z0-9\"'\\[\\(\\-]+"), "")
        text = text.replace(Regex("[^A-Za-z0-9\"'\\]\\)\\.\\!\\?\\,\\-\\:;]+$"), "")
        if (text.length < 2) return ""

        // Keep valid English words (including 1-letter "I", "a", "o" and short 1-4 word lines)
        val words = text.split(" ")
        val validWords = mutableListOf<String>()
        var realWordCount = 0

        for (w in words) {
            val stripped = w.replace(Regex("[^A-Za-z0-9']"), "")
            val alphaCount = stripped.count { it in 'A'..'Z' || it in 'a'..'z' }
            if (alphaCount >= 2) {
                realWordCount++
                validWords.add(w)
            } else if (stripped.lowercase() in setOf("i", "a", "o", "l")) {
                realWordCount++
                validWords.add(w)
            } else if (stripped.all { it.isDigit() } && stripped.isNotEmpty()) {
                validWords.add(w)
            }
        }

        if (realWordCount == 0) return ""
        text = validWords.joinToString(" ")

        // Normalize ALL-CAPS subtitles into natural sentence case
        val letters = text.filter { it.isLetter() }
        if (letters.length >= 4 && letters.all { it.isUpperCase() }) {
            text = text.lowercase().replaceFirstChar { it.uppercase() }
            text = text.replace(Regex("\\bi\\b"), "I")
        }

        // Repair missing apostrophes ("Ill" -> "I'll", "Id" -> "I'd", "wont" -> "won't")
        for ((pattern, replacement) in contractionRepairs) {
            text = pattern.replace(text, replacement)
        }

        return text.trim()
    }
}
