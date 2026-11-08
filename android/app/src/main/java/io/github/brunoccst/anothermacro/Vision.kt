package io.github.brunoccst.anothermacro

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import kotlin.math.max
import kotlin.math.roundToInt

const val DEFAULT_THRESHOLD = 0.85

data class Match(val name: String, val score: Double, val region: Region) {
    val center: Point get() = region.center
}

/**
 * A small image cut from a real screenshot. Same format as the Python tool's
 * library.yaml: `ref` is the size of the screenshot it came from, so it is
 * rescaled to this phone (the game UI scales with the screen height).
 */
class Template(
    val name: String,
    private val image: Mat?,
    val ref: IntArray?,
    val region: Region,
    val threshold: Double,
    val scales: List<Double>,
    /** Parts of the template (fractions of it) that must be bright on screen, at least [dimBy] above every [dim] part. */
    val bright: List<Region> = emptyList(),
    val dim: List<Region> = emptyList(),
    val minBright: Double = 200.0,
    val dimBy: Double = 70.0,
) {
    val available: Boolean get() = image != null && ref != null

    /** The best score of the last [match], also when it was under the threshold (for the log). */
    @Volatile var lastScore = 0.0

    fun match(frame: Mat): Match? {
        val img = image ?: return null
        val r = ref ?: return null
        val fw = frame.cols()
        val fh = frame.rows()
        val (left, top, right, bottom) = region.toPx(fw, fh).let { listOf(it[0], it[1], it[2], it[3]) }
        if (right - left < 4 || bottom - top < 4) return null
        val area = frame.submat(top, bottom, left, right)
        val base = fh.toDouble() / r[1]
        var best: DoubleArray? = null // score, x, y, w, h
        var seen = 0.0 // best raw score (checked templates)
        for (extra in scales) {
            val tpl = scaled(img, base * extra)
            if (tpl.rows() < 4 || tpl.cols() < 4 || tpl.rows() > area.rows() || tpl.cols() > area.cols()) continue
            val res = Mat()
            Imgproc.matchTemplate(area, tpl, res, Imgproc.TM_CCOEFF_NORMED)
            if (bright.isEmpty()) {
                val mm = Core.minMaxLoc(res)
                if (best == null || mm.maxVal > best[0]) {
                    best = doubleArrayOf(mm.maxVal, mm.maxLoc.x, mm.maxLoc.y, tpl.cols().toDouble(), tpl.rows().toDouble())
                }
            } else {
                // the best few spots over the threshold, each checked for its bright and dim parts
                for (i in 0 until 6) {
                    val mm = Core.minMaxLoc(res)
                    seen = maxOf(seen, mm.maxVal)
                    if (mm.maxVal < threshold || (best != null && mm.maxVal <= best[0])) break
                    val x = mm.maxLoc.x.toInt(); val y = mm.maxLoc.y.toInt()
                    if (looksRight(area.submat(y, y + tpl.rows(), x, x + tpl.cols()))) {
                        best = doubleArrayOf(mm.maxVal, mm.maxLoc.x, mm.maxLoc.y, tpl.cols().toDouble(), tpl.rows().toDouble())
                        break
                    }
                    // not it: blank that spot and look at the next best
                    val r0 = maxOf(0, y - tpl.rows() / 2); val c0 = maxOf(0, x - tpl.cols() / 2)
                    res.submat(r0, minOf(res.rows(), y + tpl.rows() / 2 + 1), c0, minOf(res.cols(), x + tpl.cols() / 2 + 1)).setTo(org.opencv.core.Scalar(-1.0))
                }
            }
            res.release()
            if (tpl !== img) tpl.release()
        }
        area.release()
        if (bright.isNotEmpty()) lastScore = seen
        val b = best ?: return null
        if (bright.isEmpty()) lastScore = b[0]
        if (b[0] < threshold) return null
        return Match(name, b[0], Region((left + b[1]) / fw, (top + b[2]) / fh, b[3] / fw, b[4] / fh))
    }

    private fun looksRight(patch: Mat): Boolean {
        fun mean(r: Region): Double {
            val x0 = (r.x * patch.cols()).toInt(); val y0 = (r.y * patch.rows()).toInt()
            val x1 = maxOf(x0 + 1, ((r.x + r.w) * patch.cols()).toInt()); val y1 = maxOf(y0 + 1, ((r.y + r.h) * patch.rows()).toInt())
            return Core.mean(patch.submat(y0, minOf(y1, patch.rows()), x0, minOf(x1, patch.cols()))).`val`[0]
        }
        val b = bright.map(::mean)
        val d = dim.map(::mean)
        return b.all { it >= minBright } && d.all { dv -> b.all { it - dv >= dimBy } }
    }

    private fun scaled(img: Mat, factor: Double): Mat {
        if (kotlin.math.abs(factor - 1.0) < 1e-3) return img
        val out = Mat()
        val size = Size(max(1, (img.cols() * factor).roundToInt()).toDouble(), max(1, (img.rows() * factor).roundToInt()).toDouble())
        Imgproc.resize(img, out, size, 0.0, 0.0, if (factor < 1) Imgproc.INTER_AREA else Imgproc.INTER_LINEAR)
        return out
    }
}

data class ScreenDef(val name: String, val require: List<String>, val reject: List<String>)

/** Templates + screens merged from one or more asset folders (each with a library.yaml). */
class Library private constructor() {
    val templates = LinkedHashMap<String, Template>()
    val screens = LinkedHashMap<String, ScreenDef>()

    fun missing(): List<String> = templates.values.filter { !it.available }.map { it.name }.sorted()

    companion object {
        fun load(assets: LibraryFiles, vararg folders: String): Library {
            val lib = Library()
            for (folder in folders) lib.addFolder(assets, folder)
            return lib
        }
    }

    /** Templates the user captured in the app: a folder on disk with its own library.yaml. */
    fun addDir(dir: java.io.File) {
        val yaml = java.io.File(dir, "library.yaml")
        if (!yaml.isFile) return
        parse(yaml.readText()) { file -> java.io.File(dir, file).takeIf { it.isFile }?.readBytes() }
    }

    fun addScreens(defs: Map<String, ScreenDef>) { screens.putAll(defs) }

    /** [other]'s templates and screens that this library doesn't have yet. */
    fun addMissing(other: Library) {
        for ((k, v) in other.templates) templates.putIfAbsent(k, v)
        for ((k, v) in other.screens) screens.putIfAbsent(k, v)
    }

    private fun addFolder(assets: LibraryFiles, folder: String) {
        val yamlText = try {
            assets.open("$folder/library.yaml").bufferedReader().use { it.readText() }
        } catch (e: java.io.IOException) {
            return
        }
        parse(yamlText) { file -> try { assets.open("$folder/$file").use { it.readBytes() } } catch (e: java.io.IOException) { null } }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parse(yamlText: String, read: (String) -> ByteArray?) {
        val data = Load(LoadSettings.builder().build()).loadFromString(yamlText) as? Map<String, Any?> ?: return
        val tpls = data["templates"] as? Map<String, Any?> ?: emptyMap()
        for ((name, specAny) in tpls) {
            val spec = specAny as? Map<String, Any?> ?: emptyMap()
            val file = spec["file"] as? String ?: "$name.png"
            val ref = (spec["ref"] as? List<*>)?.map { (it as Number).toInt() }?.toIntArray()
            val image = if (ref != null) read(file)?.let { decodeGray(it) } else null
            templates[name] = Template(
                name = name,
                image = image,
                ref = ref,
                region = Region.parse(spec["region"]),
                threshold = (spec["threshold"] as? Number)?.toDouble() ?: DEFAULT_THRESHOLD,
                scales = (spec["scales"] as? List<*>)?.map { (it as Number).toDouble() } ?: listOf(1.0),
                bright = (spec["bright"] as? List<*>)?.map { Region.parse(it) } ?: emptyList(),
                dim = (spec["dim"] as? List<*>)?.map { Region.parse(it) } ?: emptyList(),
            )
        }
        val scr = data["screens"] as? Map<String, Any?> ?: emptyMap()
        for ((name, specAny) in scr) {
            val spec = specAny as? Map<String, Any?> ?: emptyMap()
            screens[name] = ScreenDef(
                name,
                (spec["require"] as? List<*>)?.map { it as String } ?: emptyList(),
                (spec["reject"] as? List<*>)?.map { it as String } ?: emptyList(),
            )
        }
    }

    private fun decodeGray(bytes: ByteArray): Mat? {
        val img = Imgcodecs.imdecode(MatOfByte(*bytes), Imgcodecs.IMREAD_GRAYSCALE)
        return if (img.empty()) null else img
    }
}

/** Matches a library against one frame at a time; results are cached per frame. */
class Vision(val library: Library) {
    private var frameId: Any? = null
    private val cache = HashMap<String, Match?>()

    fun find(frame: Frame, name: String): Match? {
        if (frameId !== frame) {
            frameId = frame
            cache.clear()
        }
        return cache.getOrPut(name) {
            val tpl = library.templates[name] ?: throw IllegalArgumentException("unknown template '$name'")
            if (tpl.available) tpl.match(frame.gray) else null
        }
    }

    fun isScreen(frame: Frame, screen: String): Boolean {
        val sd = library.screens[screen] ?: return false
        if (sd.require.isEmpty()) return false
        return sd.require.all { find(frame, it) != null } && sd.reject.none { find(frame, it) != null }
    }

    fun identify(frame: Frame, candidates: List<String>): String? =
        candidates.firstOrNull { library.screens.containsKey(it) && isScreen(frame, it) }
}
