package io.github.brunoccst.anothermacro

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.TimeUnit

/** On-device text recognition (ML Kit, bundled Latin model): no network, no Google Play services. */
object Ocr {
    /** One recognised line; box is normalized (0..1) to the screen. */
    data class Line(val text: String, val box: Region) {
        val center: Point get() = box.center
    }

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /** Blocking: call from the macro thread. */
    fun read(bmp: Bitmap): List<Line> {
        val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bmp, 0)), 10, TimeUnit.SECONDS)
        val w = bmp.width.toDouble()
        val h = bmp.height.toDouble()
        return result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
            val b = line.boundingBox ?: return@mapNotNull null
            Line(line.text, Region(b.left / w, b.top / h, b.width() / w, b.height() / h))
        }
    }

    /** Lowercase letters and digits only, single spaces: for comparing text loosely. */
    fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    /** Similarity 0..1 of two texts (on normalized words; tolerant of OCR slips). */
    fun similarity(a: String, b: String): Double {
        val x = norm(a)
        val y = norm(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        val d = levenshtein(x, y)
        return 1.0 - d.toDouble() / maxOf(x.length, y.length)
    }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }
}
