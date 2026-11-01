package io.github.brunoccst.anothermacro

import android.content.Context
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import org.snakeyaml.engine.v2.api.Dump
import org.snakeyaml.engine.v2.api.DumpSettings
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.common.FlowStyle
import java.io.File

/**
 * Templates the user captures in the app: a PNG per template plus a library.yaml in
 * the app's storage (files/templates/user), same format as the shipped ones, so
 * macros can use them by name (`found('my_button')`, `tap: my_button`).
 */
object TemplateCapture {
    private val NAME = Regex("^[a-z][a-z0-9_]{1,40}$")

    fun validName(name: String) = NAME.matches(name)

    /** Save the current screen (colour) to the cache; returns its path. */
    fun saveScreen(context: Context): String? {
        val bgr = MacroService.capture?.grabColor() ?: return null
        val file = File(context.cacheDir, "screen.png")
        Imgcodecs.imwrite(file.path, bgr)
        bgr.release()
        return file.path
    }

    /** Cut [box] (pixels) out of the screen at [screenPath] and register it as [name]. */
    fun save(context: Context, screenPath: String, name: String, box: Rect, notes: String): File {
        require(validName(name)) { "use lowercase letters, digits and _ (e.g. my_button)" }
        val screen = Imgcodecs.imread(screenPath)
        require(!screen.empty()) { "screen image missing" }
        val dir = Scenarios.userTemplates(context)
        val png = File(dir, "$name.png")
        val crop = Mat(screen, box)
        Imgcodecs.imwrite(png.path, crop)
        crop.release()
        val w = screen.cols().toDouble()
        val h = screen.rows().toDouble()
        // search a bit around where it was cut, like the Python capture-template does
        val pad = 0.05
        val x0 = (box.x / w - pad).coerceAtLeast(0.0)
        val y0 = (box.y / h - pad).coerceAtLeast(0.0)
        val x1 = ((box.x + box.width) / w + pad).coerceAtMost(1.0)
        val y1 = ((box.y + box.height) / h + pad).coerceAtMost(1.0)
        val entry = linkedMapOf<String, Any>(
            "file" to "$name.png",
            "ref" to listOf(screen.cols(), screen.rows()),
            "region" to listOf(x0, y0, x1 - x0, y1 - y0).map { Math.round(it * 10000) / 10000.0 },
        )
        if (notes.isNotBlank()) entry["notes"] = notes
        screen.release()
        updateLibrary(dir) { it[name] = entry }
        return png
    }

    /** Best match score of [name] on the screen at [screenPath] (to check a new template). */
    fun test(context: Context, screenPath: String, name: String): Match? {
        val lib = Library.load(context.assets).apply { addDir(Scenarios.userTemplates(context)) }
        val tpl = lib.templates[name] ?: return null
        val gray = Imgcodecs.imread(screenPath, Imgcodecs.IMREAD_GRAYSCALE)
        return try { tpl.match(gray) } finally { gray.release() }
    }

    fun list(context: Context): List<String> = libraryData(Scenarios.userTemplates(context)).keys.sorted()

    fun delete(context: Context, name: String) {
        val dir = Scenarios.userTemplates(context)
        File(dir, "$name.png").delete()
        updateLibrary(dir) { it.remove(name) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun libraryData(dir: File): LinkedHashMap<String, Any?> {
        val f = File(dir, "library.yaml")
        if (!f.isFile) return LinkedHashMap()
        val data = Load(LoadSettings.builder().build()).loadFromString(f.readText()) as? Map<String, Any?> ?: return LinkedHashMap()
        return LinkedHashMap((data["templates"] as? Map<String, Any?>).orEmpty())
    }

    private fun updateLibrary(dir: File, change: (LinkedHashMap<String, Any?>) -> Unit) {
        val templates = libraryData(dir)
        change(templates)
        val dump = Dump(DumpSettings.builder().setDefaultFlowStyle(FlowStyle.BLOCK).build())
        File(dir, "library.yaml").writeText(
            "# Templates captured in the Another Macro app.\n" + dump.dumpToString(mapOf("templates" to templates))
        )
    }
}
