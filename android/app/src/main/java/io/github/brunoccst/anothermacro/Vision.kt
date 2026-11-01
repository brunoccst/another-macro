package io.github.brunoccst.anothermacro

import android.content.res.AssetManager
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
) {
    val available: Boolean get() = image != null && ref != null

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
        for (extra in scales) {
            val tpl = scaled(img, base * extra)
            if (tpl.rows() < 4 || tpl.cols() < 4 || tpl.rows() > area.rows() || tpl.cols() > area.cols()) continue
            val res = Mat()
            Imgproc.matchTemplate(area, tpl, res, Imgproc.TM_CCOEFF_NORMED)
            val mm = Core.minMaxLoc(res)
            res.release()
            if (best == null || mm.maxVal > best[0]) {
                best = doubleArrayOf(mm.maxVal, mm.maxLoc.x, mm.maxLoc.y, tpl.cols().toDouble(), tpl.rows().toDouble())
            }
            if (tpl !== img) tpl.release()
        }
        area.release()
        val b = best ?: return null
        if (b[0] < threshold) return null
        return Match(name, b[0], Region((left + b[1]) / fw, (top + b[2]) / fh, b[3] / fw, b[4] / fh))
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
        fun load(assets: AssetManager, vararg folders: String): Library {
            val lib = Library()
            for (folder in folders) lib.addFolder(assets, folder)
            return lib
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun addFolder(assets: AssetManager, folder: String) {
        val yamlText = try {
            assets.open("$folder/library.yaml").bufferedReader().use { it.readText() }
        } catch (e: java.io.IOException) {
            return
        }
        val data = Load(LoadSettings.builder().build()).loadFromString(yamlText) as? Map<String, Any?> ?: return
        val tpls = data["templates"] as? Map<String, Any?> ?: emptyMap()
        for ((name, specAny) in tpls) {
            val spec = specAny as? Map<String, Any?> ?: emptyMap()
            val file = spec["file"] as? String ?: "$name.png"
            val ref = (spec["ref"] as? List<*>)?.map { (it as Number).toInt() }?.toIntArray()
            val image = if (ref != null) readGray(assets, "$folder/$file") else null
            templates[name] = Template(
                name = name,
                image = image,
                ref = ref,
                region = Region.parse(spec["region"]),
                threshold = (spec["threshold"] as? Number)?.toDouble() ?: DEFAULT_THRESHOLD,
                scales = (spec["scales"] as? List<*>)?.map { (it as Number).toDouble() } ?: listOf(1.0),
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

    private fun readGray(assets: AssetManager, path: String): Mat? = try {
        val bytes = assets.open(path).use { it.readBytes() }
        val img = Imgcodecs.imdecode(MatOfByte(*bytes), Imgcodecs.IMREAD_GRAYSCALE)
        if (img.empty()) null else img
    } catch (e: java.io.IOException) {
        null
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
