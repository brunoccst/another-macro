package io.github.brunoccst.anothermacro

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Record a manual run (⏺ on the floating buttons) so it can become a fixed macro.
 *
 * Android gives an app no way to watch the touches another app gets, but ADB's shell can
 * read the touchscreen itself (`getevent`) without getting in the way. So while recording the
 * game gets every touch as usual (live, long holds, turns mid-swipe) and the touchscreen is read
 * through the app's own ADB ([AppAdb], paired once with Wireless debugging), or through Termux
 * (`another-macro record-touches`, if the app isn't paired and Termux may run commands). A
 * frame of the screen is kept every [FRAME_EVERY_MS].
 *
 * Saved per recording, in the app's external files folder (adb can pull it; never commit it):
 *   recordings/<name>/recording.json   the moves (see [toJson])
 *   recordings/<name>/frames/NNN.jpg   the screen when each move started (for syncing the replay
 *                                      and for fine-tuning the script; these are screenshots of
 *                                      the account, so they stay on the phone)
 * and a macro `files/macros/<name>.yaml` that replays it (`replay:` action, see [Replay]).
 */
object Recorder {
    /** A move as the finger made it: [points] are (ms since the press, x, y), x and y as fractions of the screen. */
    class Gesture(
        val downMs: Long,
        val points: List<Triple<Long, Double, Double>>,
        val multiTouch: Boolean,
    ) {
        val durMs: Long get() = points.last().first
        var echoMs = 0L
        var echoOk = false
        var gapMs = 0L
        var frame: String? = null
        var label: String? = null
    }

    /** A recording in progress: [LiveSession] (touches read through ADB) or [Session] (caught and passed on). */
    interface Recording {
        /** Stop recording (main thread). */
        fun close()
        /** Write recording.json (background thread, after [close]); null (and nothing kept) if no move was made.
         *  Moves that start inside [exclude] (the floating buttons) are left out. */
        fun finish(exclude: Region?): File?
    }

    @Volatile
    var session: Recording? = null
        private set
    val isRecording get() = session != null

    /** Notified (on the main thread) when recording starts or stops. */
    val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    private fun changed() = listeners.forEach { runCatching { it() } }

    private val NAME = Regex("^[a-z0-9][a-z0-9_-]{0,47}$")
    fun validName(name: String) = NAME.matches(name)

    /** Turn "Cat Acteul" into "cat-acteul". */
    fun suggestName(raw: String) = raw.trim().lowercase().replace(Regex("[^a-z0-9_-]+"), "-").trim('-').take(48)

    fun root(context: Context): File = File(context.getExternalFilesDir(null) ?: context.filesDir, "recordings").apply { mkdirs() }

    fun list(context: Context): List<String> =
        root(context).listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith(".") && File(it, "recording.json").isFile }
            .map { it.name }.sorted()

    /**
     * Start recording (call on the main thread): through the app's own ADB when it is paired,
     * else through Termux when it may run commands, else open the pairing setup.
     */
    fun start(service: InputService): Boolean {
        if (session != null) return false
        if (Runner.isRunning) { MacroLog.i("stop the macro before recording"); return false }
        val source = when {
            AppAdb.paired(service) -> Source.APP_ADB
            Termux.installed(service) && Termux.allowed(service) -> Source.TERMUX
            else -> {
                toast(service, "Set up recording once: pair the app with Wireless debugging")
                service.startActivity(android.content.Intent(service, AdbSetupActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                return false
            }
        }
        root(service).listFiles().orEmpty().filter { it.name.startsWith(".pending") }.forEach { it.deleteRecursively() }
        val dir = File(root(service), ".pending-${System.currentTimeMillis()}").apply { mkdirs() }
        val s = LiveSession(service, dir, source).takeIf { it.start() } ?: return false
        session = s
        if (MacroService.capture == null) MacroLog.i("recording touches without the screen (allow screen capture to keep a frame per move)")
        MacroLog.status("recording: make your moves, then ⏹")
        changed()
        return true
    }

    /**
     * Stop (call on the main thread), then [done] gets the pending folder with what was recorded,
     * or null if nothing was, on the main thread once the last move has been played and saved.
     */
    fun stop(exclude: Region?, done: (File?) -> Unit) {
        val s = session ?: return  // a second ⏹ while the first one saves
        session = null
        s.close()
        val main = Handler(Looper.getMainLooper())
        Thread {
            val dir = runCatching { s.finish(exclude) }.onFailure { MacroLog.e("recording: could not save", it) }.getOrNull()
            main.post { changed(); done(dir) }
        }.start()
    }

    /** Give a pending recording its name, and make the macro that replays it. */
    fun save(context: Context, pending: File, name: String): File {
        require(validName(name)) { "use lowercase letters, digits, - and _ (e.g. cat-acteul)" }
        val dest = File(root(context), name)
        if (dest.exists()) dest.deleteRecursively()
        require(pending.renameTo(dest)) { "could not save the recording" }
        val json = File(dest, "recording.json")
        val data = JSONObject(json.readText()).put("name", name)
        json.writeText(data.toString(1))
        writeMacro(context, name, data)
        MacroLog.i("recording '$name' saved (${data.getJSONArray("gestures").length()} moves); macro '$name' replays it")
        return dest
    }

    fun delete(context: Context, name: String) {
        File(root(context), name).deleteRecursively()
        val macro = File(Scenarios.userDir(context), "$name.yaml")
        if (macro.isFile && macro.readText().contains("replay:")) macro.delete()
    }

    private fun writeMacro(context: Context, name: String, data: JSONObject) {
        val g = data.getJSONArray("gestures")
        val labels = (0 until g.length()).mapNotNull { g.getJSONObject(it).optString("label").takeIf { l -> l.isNotBlank() } }
        val summary = "${g.length()} moves" + if (labels.isEmpty()) "" else ": " + labels.take(6).joinToString(" › ").replace("\"", "'")
        File(Scenarios.userDir(context), "$name.yaml").writeText(
            """
            |# Made by the recorder (⏺): replays the recording '$name' move by move.
            |name: "$name"
            |description: "Recorded run, $summary"
            |group: recorded
            |params:
            |  sync: {default: true, type: bool, help: "before each move, wait until the screen looks like it did when it was recorded"}
            |  pace: {default: 1.0, help: "how much of the recorded pause to keep between moves (1 = all, 0.5 = half)"}
            |templates: [another_dungeon]
            |screens: []
            |setup:
            |  - status: "replaying $name"
            |  - replay: {name: "$name", sync: sync, pace: pace}
            |  - stop: "replayed $name"
            |""".trimMargin()
        )
    }

    /** Write [Gesture]s as recording.json. */
    internal fun toJson(gestures: List<Gesture>, width: Int, height: Int, endMs: Long): JSONObject {
        val arr = JSONArray()
        gestures.forEachIndexed { i, g ->
            arr.put(JSONObject().apply {
                put("i", i)
                put("down_ms", g.downMs)
                put("gap_ms", g.gapMs)
                put("dur_ms", g.durMs)
                put("echo_ms", g.echoMs)
                put("echo_ok", g.echoOk)
                if (g.multiTouch) put("multi_touch", true)
                g.frame?.let { put("frame", it) }
                g.label?.let { put("label", it) }
                put("points", JSONArray().apply {
                    g.points.forEach { (t, x, y) -> put(JSONArray().put(t).put(round4(x)).put(round4(y))) }
                })
            })
        }
        return JSONObject().apply {
            put("version", 1)
            put("created", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US).format(java.util.Date()))
            put("app_version", BuildConfig.VERSION_NAME)
            put("screen", JSONArray().put(width).put(height))
            put("end_ms", endMs)
            put("gestures", arr)
        }
    }

    private fun round4(v: Double) = Math.round(v * 10000) / 10000.0

    /** Who reads the touchscreen: the app's own ADB, or Termux's. */
    enum class Source { APP_ADB, TERMUX }

    /** Ends a recording that couldn't start reading touches (from any thread). */
    internal fun abort(s: Recording, why: String) {
        Handler(Looper.getMainLooper()).post {
            if (session !== s) return@post
            session = null
            s.close()
            MacroLog.i("recording failed: $why")
            toast((s as LiveSession).service, "Recording failed: $why")
            changed()
        }
    }

    /**
     * Live recording: the touches go to the game as usual, and `getevent` reads them from the
     * touchscreen, through the app's own ADB ([Source.APP_ADB]: the log comes straight into
     * [dir]) or Termux's (`another-macro record-touches`, touch_recorder.py: on ⏹ it pushes
     * touches.json into [dir]). So walking, long holds and turns mid-swipe work. Meanwhile the
     * app keeps a frame of the screen every [FRAME_EVERY_MS]; each move gets the last frame
     * before it and, for a tap, the text under the finger.
     */
    class LiveSession(internal val service: InputService, val dir: File, private val source: Source) : Recording {
        @Volatile private var stream: io.github.muntashirakon.adb.AdbStream? = null
        private var reader: Thread? = null
        @Volatile private var failed: String? = null
        private val startUptime = SystemClock.uptimeMillis()
        private val startWall = System.currentTimeMillis()
        @Volatile private var running = true
        private var width = 0
        private var height = 0
        private var rotation = 1
        private val frames = File(dir, "frames")

        fun start(): Boolean {
            val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val p = android.graphics.Point()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealSize(p)
            width = p.x; height = p.y
            @Suppress("DEPRECATION") rotation = wm.defaultDisplay.rotation
            frames.mkdirs()
            if (source == Source.TERMUX) {
                val cmd = "another-macro record-touches start --out ${Termux.quote(dir.path)} --rotation $rotation"
                if (!Termux.run(service, cmd)) {
                    toast(service, "Termux didn't take the command: see the app's log")
                    dir.deleteRecursively()
                    return false
                }
            } else {
                reader = Thread { readTouches() }.apply { isDaemon = true; start() }
            }
            Thread { saveFrames() }.apply { isDaemon = true }.start()
            return true
        }

        /** The app's own ADB: `getevent -pl` once, then `getevent -lt` line by line into touches.log. */
        private fun readTouches() {
            val err = AppAdb.connect(service)
            if (err != null) { failed = err; abort(this, err); return }
            try {
                File(dir, "device.txt").writeText(AppAdb.shell(service, "getevent -pl"))
                val s = AppAdb.stream(service, "getevent -lt")
                stream = s
                MacroLog.i("recording: reading the touchscreen")
                File(dir, "touches.log").bufferedWriter().use { out ->
                    s.openInputStream().bufferedReader().forEachLine { line ->
                        out.write(line.trimEnd('\r')); out.newLine(); out.flush()
                    }
                }
            } catch (e: Exception) {
                if (running) { failed = "the touch reader stopped (${e.javaClass.simpleName})"; MacroLog.e("recording: touch reader", e) }
            }
        }

        private fun saveFrames() {
            var last = -1L
            while (running) {
                val cap = MacroService.capture
                val mat = cap?.grabColor()
                if (mat != null) {
                    try {
                        val now = SystemClock.uptimeMillis()
                        if (now != last) {
                            val small = Mat()
                            val scale = FRAME_WIDTH.toDouble() / mat.cols()
                            Imgproc.resize(mat, small, Size(FRAME_WIDTH.toDouble(), Math.round(mat.rows() * scale).toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
                            Imgcodecs.imwrite(File(frames, "t$now.jpg").path, small, MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 75))
                            small.release()
                            last = now
                        }
                    } finally {
                        mat.release()
                    }
                }
                Thread.sleep(FRAME_EVERY_MS)
            }
        }

        override fun close() {
            running = false
            if (source == Source.TERMUX) Termux.run(service, "another-macro record-touches stop --out ${Termux.quote(dir.path)}")
            else Thread { runCatching { stream?.close() } }.start()
        }

        override fun finish(exclude: Region?): File? {
            val endMs = SystemClock.uptimeMillis() - startUptime
            val data = if (source == Source.TERMUX) termuxResult() else appResult()
            if (data == null || data.has("error")) {
                val why = data?.optString("error")
                    ?: "no touches came back from Termux (allow-external-apps = true in ~/.termux/termux.properties? another-macro connect?)"
                MacroLog.i("recording failed: $why")
                toast(service, "Recording failed: $why")
                dir.deleteRecursively()
                return null
            }
            return build(data, exclude, endMs)
        }

        /** The app's own reader: parse touches.log here (TouchLog, the Kotlin twin of touch_recorder.py). */
        private fun appResult(): JSONObject? {
            reader?.join(5000)
            failed?.let { return JSONObject().put("error", it) }
            val device = File(dir, "device.txt").takeIf { it.isFile }?.readText()
                ?: return JSONObject().put("error", "the touch reader never started")
            val log = File(dir, "touches.log").takeIf { it.isFile }?.readText().orEmpty()
            val ts = TouchLog.parseDevices(device, log) ?: return JSONObject().put("error", "no touchscreen found in getevent")
            val moves = TouchLog.parseEvents(log, ts, rotation)
            val arr = JSONArray()
            for (m in moves) arr.put(JSONObject().apply {
                put("down_s", m.downS)
                put("multi_touch", m.multiTouch)
                put("points", JSONArray().apply { m.points.forEach { (t, x, y) -> put(JSONArray().put(t).put(x).put(y)) } })
            })
            val clock = if (moves.firstOrNull()?.let { it.downS > 1e8 } == true) "realtime" else "monotonic"
            // keep the raw log out of the saved recording (it is big); touches are in recording.json
            File(dir, "touches.log").delete()
            return JSONObject().put("clock", clock).put("device", ts.path).put("gestures", arr)
        }

        /** Termux's reader: wait for the touches.json it pushes. */
        private fun termuxResult(): JSONObject? {
            val file = File(dir, "touches.json")
            val until = SystemClock.uptimeMillis() + 30000
            while (!file.isFile && SystemClock.uptimeMillis() < until) Thread.sleep(200)
            Thread.sleep(300) // adb push may still be writing it
            return runCatching { JSONObject(file.readText()) }.getOrNull()
        }

        private fun build(data: JSONObject, exclude: Region?, endMs: Long): File? {
            // getevent's clock: since boot (same as uptimeMillis) or wall time
            val offset = if (data.optString("clock") == "realtime") startUptime - startWall else 0L
            val shots = frames.listFiles().orEmpty().mapNotNull { f ->
                f.name.removePrefix("t").removeSuffix(".jpg").toLongOrNull()?.let { it to f }
            }.sortedBy { it.first }
            val arr = data.getJSONArray("gestures")
            val list = ArrayList<Gesture>()
            var prevEnd = startUptime
            for (k in 0 until arr.length()) {
                val o = arr.getJSONObject(k)
                val down = Math.round(o.getDouble("down_s") * 1000) + offset
                if (down < startUptime - 500) continue
                val pa = o.getJSONArray("points")
                val pts = (0 until pa.length()).map { j -> pa.getJSONArray(j).let { Triple(it.getLong(0), it.getDouble(1), it.getDouble(2)) } }
                if (pts.isEmpty()) continue
                // the ⏹ press itself (and anything else on the floating buttons)
                if (exclude != null && exclude.contains(Point(pts[0].second, pts[0].third))) continue
                val g = Gesture((down - startUptime).coerceAtLeast(0), pts, o.optBoolean("multi_touch"))
                g.gapMs = (down - prevEnd).coerceAtLeast(0)
                g.echoMs = g.downMs
                g.echoOk = true
                prevEnd = down + g.durMs
                shots.lastOrNull { it.first <= down - 30 }?.let { (_, f) ->
                    g.frame = "frames/${f.name}"
                    if (isTap(g)) {
                        val bgr = Imgcodecs.imread(f.path)
                        if (!bgr.empty()) g.label = runCatching { labelAt(bgr, pts[0].second, pts[0].third) }.getOrNull()
                        bgr.release()
                    }
                }
                list += g
            }
            if (list.isEmpty()) {
                MacroLog.status("recording: nothing recorded")
                dir.deleteRecursively()
                return null
            }
            val json = toJson(list, width, height, endMs)
                .put("source", if (source == Source.APP_ADB) "app-adb" else "termux").put("rotation", rotation)
            File(dir, "recording.json").writeText(json.toString(1))
            MacroLog.status("recording: ${list.size} moves, ${endMs / 1000}s; name it to save")
            return dir
        }
    }

    internal fun toast(context: Context, text: String) {
        Handler(Looper.getMainLooper()).post { android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_LONG).show() }
    }

    /** The text line a tap at ([x], [y]) landed on (or the nearest one close by), read by OCR on [bgr]. */
    internal fun labelAt(bgr: Mat, x: Double, y: Double): String? {
            val rgba = Mat()
            Imgproc.cvtColor(bgr, rgba, Imgproc.COLOR_BGR2RGBA)
            val bmp = android.graphics.Bitmap.createBitmap(rgba.cols(), rgba.rows(), android.graphics.Bitmap.Config.ARGB_8888)
            org.opencv.android.Utils.matToBitmap(rgba, bmp)
            rgba.release()
            val lines = runCatching { Ocr.read(bmp) }.getOrDefault(emptyList())
            bmp.recycle()
            val p = Point(x, y)
            lines.firstOrNull { Region(it.box.x - 0.01, it.box.y - 0.015, it.box.w + 0.02, it.box.h + 0.03).contains(p) }?.let { return it.text }
            return lines.map { it to hypot(it.center.x - x, (it.center.y - y) * 0.45) }
                .filter { it.second < 0.04 }.minByOrNull { it.second }?.first?.text
    }

    internal fun isTap(g: Gesture): Boolean {
        val (_, x0, y0) = g.points.first()
        val far = g.points.maxOf { hypot(it.second - x0, (it.third - y0) * 0.45) }
        return g.durMs < 500 && far < 0.012
    }

    const val FRAME_WIDTH = 1200
    /** Live recording: a frame of the screen this often (for syncing the replay and fine-tuning). */
    const val FRAME_EVERY_MS = 400L
}

/**
 * Play recorded moves to the game: one move with its own path and timing ([play]), or a whole
 * recording as a macro step (`replay: {name: cat-acteul, sync: true, pace: 1}` in a macro).
 */
object Replay {
    /** Parts of a long move: the finger stays down between them (continued strokes), so timing is kept. */
    private const val PART_MS = 40L

    /**
     * Play one move: [points] are (ms since the press, x, y) with x, y as fractions of a
     * [width] x [height] screen. Blocks until the finger lifts; false if Android cut it short.
     */
    fun play(service: AccessibilityService, points: List<Triple<Long, Double, Double>>, width: Int, height: Int): Boolean {
        if (points.isEmpty()) return false
        fun px(p: Triple<Long, Double, Double>) =
            (p.second * width).toFloat().coerceIn(0f, width - 1f) to (p.third * height).toFloat().coerceIn(0f, height - 1f)
        val total = points.last().first
        val main = Handler(Looper.getMainLooper())
        val done = CountDownLatch(1)
        var ok = false

        if (total < PART_MS * 2) {
            // a tap or a quick flick: one stroke along the whole path
            val (sx, sy) = px(points.first())
            val path = Path().apply { moveTo(sx, sy); points.drop(1).forEach { val (x, y) = px(it); lineTo(x, y) } }
            val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, total.coerceAtLeast(1))).build()
            main.post {
                val accepted = service.dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) { ok = true; done.countDown() }
                    override fun onCancelled(gestureDescription: GestureDescription?) { done.countDown() }
                }, null)
                if (!accepted) done.countDown()
            }
            done.await(total + 3000, TimeUnit.MILLISECONDS)
            return ok
        }

        // a hold or a slide: parts of ~40 ms chained as one continued stroke
        val ends = ArrayList<Triple<Long, Double, Double>>()
        var lastT = 0L
        for (p in points.drop(1)) if (p.first - lastT >= PART_MS) { ends += p; lastT = p.first }
        if (ends.isEmpty() || ends.last().first != total) {
            if (ends.isNotEmpty() && total - ends.last().first < PART_MS / 2) ends[ends.size - 1] = points.last() else ends += points.last()
        }
        var cur = px(points.first())
        var prevT = 0L
        var nudge = 1f
        var prev: GestureDescription.StrokeDescription? = null
        fun send(i: Int) {
            val target = ends[i]
            var (bx, by) = px(target)
            // a continued part needs a length: stand still by moving one pixel back and forth
            if (bx == cur.first && by == cur.second) { bx = (cur.first + nudge).coerceIn(0f, width - 1f); nudge = -nudge }
            val path = Path().apply { moveTo(cur.first, cur.second); lineTo(bx, by) }
            val dur = (target.first - prevT).coerceAtLeast(1)
            val last = i == ends.size - 1
            val stroke = try {
                prev?.continueStroke(path, 0, dur, !last) ?: GestureDescription.StrokeDescription(path, 0, dur, !last)
            } catch (e: Exception) {
                MacroLog.e("could not continue the move", e); null
            } ?: return done.countDown()
            prev = stroke
            cur = bx to by
            prevT = target.first
            val accepted = service.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (last) { ok = true; done.countDown() } else send(i + 1)
                }
                override fun onCancelled(gestureDescription: GestureDescription?) { done.countDown() }
            }, null)
            if (!accepted) done.countDown()
        }
        main.post { send(0) }
        done.await(total + 5000, TimeUnit.MILLISECONDS)
        return ok
    }

    /** The `replay:` macro action: play the recording [name] move by move. */
    fun run(ctx: io.github.brunoccst.anothermacro.Context, name: String, sync: Boolean, pace: Double, from: Int, to: Int?) {
        val service = InputService.instance ?: throw StopScenario("taps are off: enable Another Macro in Accessibility settings")
        val dir = File(Recorder.root(service), name)
        val json = File(dir, "recording.json").takeIf { it.isFile } ?: throw StopScenario("no recording named '$name'")
        val data = JSONObject(json.readText())
        val all = data.getJSONArray("gestures")
        val last = (to ?: all.length()).coerceAtMost(all.length())
        for (i in from.coerceAtLeast(0) until last) {
            val g = all.getJSONObject(i)
            val pts = g.getJSONArray("points").let { a ->
                (0 until a.length()).map { k -> a.getJSONArray(k).let { Triple(it.getLong(0), it.getDouble(1), it.getDouble(2)) } }
            }
            ctx.wait(g.optLong("gap_ms", 0) * pace / 1000.0)
            val frame = g.optString("frame").takeIf { it.isNotBlank() }?.let { File(dir, it) }?.takeIf { it.isFile }
            if (sync && frame != null) waitForScreen(ctx, frame, i)
            MacroLog.show("$name: move ${i + 1}/${all.length()}" + (g.optString("label").takeIf { it.isNotBlank() }?.let { " ('$it')" } ?: ""))
            val bubble = if (frame != null && isTap(pts)) recordedBubble(ctx, frame, pts.first()) else null
            if (bubble != null) {
                openChest(ctx, name, i, Point(pts.first().second, pts.first().third))
                continue
            }
            if (!ctx.gesture(pts)) MacroLog.i("$name: move ${i + 1} was cut short")
        }
    }

    /** The "!" over something the party can use (a chest, a door): shared with the dungeon macros. */
    private const val BUBBLE = "field_interact_bubble"

    private fun isTap(pts: List<Triple<Long, Double, Double>>): Boolean {
        val (x0, y0) = pts.first().second to pts.first().third
        return pts.last().first < 500 && pts.all { hypot(it.second - x0, (it.third - y0) * 0.45) < 0.012 }
    }

    /** True when the recorded [frame] showed the "!" bubble right where the move tapped. */
    private fun recordedBubble(ctx: io.github.brunoccst.anothermacro.Context, frame: File, tap: Triple<Long, Double, Double>): Boolean {
        val tpl = ctx.vision.library.templates[BUBBLE]?.takeIf { it.available } ?: return false
        val img = Imgcodecs.imread(frame.path, Imgcodecs.IMREAD_GRAYSCALE)
        if (img.empty()) return false
        val m = try { tpl.match(img) } finally { img.release() } ?: return false
        return abs(m.center.x - tap.second) < 0.08 && abs(m.center.y - tap.third) < 0.15
    }

    /**
     * A recorded tap on a "!" (a chest): tap the "!" where it is now, found by its image (the walk
     * can end a little off the recorded spot), then wait for the chest's "Obtained …" before the
     * next leg starts. No "!" at all means the walk went wrong: stop rather than tap blind.
     */
    private fun openChest(ctx: io.github.brunoccst.anothermacro.Context, name: String, i: Int, recorded: Point) {
        var found: Match? = null
        val until = System.currentTimeMillis() + 6000
        while (found == null && System.currentTimeMillis() < until) {
            ctx.refresh()
            found = ctx.find(BUBBLE)
            if (found == null) ctx.wait(0.3)
        }
        val b = found ?: throw StopScenario("$name: no \"!\" on screen before move ${i + 1} (recorded at ${"%.3f".format(recorded.x)}, ${"%.3f".format(recorded.y)}): the walk ended somewhere else")
        MacroLog.i("$name: move ${i + 1}: \"!\" at (${"%.3f".format(b.center.x)}, ${"%.3f".format(b.center.y)}), recorded at (${"%.3f".format(recorded.x)}, ${"%.3f".format(recorded.y)})")
        ctx.tap(b)
        val wait = System.currentTimeMillis() + 10000
        while (System.currentTimeMillis() < wait) {
            ctx.wait(0.5)
            ctx.refresh()
            val text = ctx.readText().joinToString(" ") { it.text }
            if ("obtained" in Ocr.norm(text)) { MacroLog.i("$name: ${text.take(100)}"); return }
        }
        MacroLog.i("$name: no \"Obtained\" after the \"!\" of move ${i + 1}; going on")
    }

    /** Wait (up to 20 s) until the screen looks like [frame] did when the move was recorded. */
    private fun waitForScreen(ctx: io.github.brunoccst.anothermacro.Context, frame: File, i: Int) {
        val src = Imgcodecs.imread(frame.path, Imgcodecs.IMREAD_GRAYSCALE)
        val want = thumb(src).also { src.release() } ?: return
        try {
            val until = System.currentTimeMillis() + 20000
            var best = Double.MAX_VALUE
            var hits = 0
            while (System.currentTimeMillis() < until) {
                val f = ctx.refresh()
                val have = thumb(f.gray) ?: return
                val diff = org.opencv.core.Core.norm(want, have, org.opencv.core.Core.NORM_L1) / (THUMB_W * THUMB_H)
                have.release()
                best = minOf(best, diff)
                if (diff < SYNC_DIFF) { if (++hits >= 2) return } else hits = 0
                ctx.wait(0.15)
            }
            MacroLog.i("replay: the screen never looked like it did before move ${i + 1} (closest ${"%.1f".format(best)}, needs < $SYNC_DIFF); going on")
        } finally {
            want.release()
        }
    }

    private const val THUMB_W = 64
    private const val THUMB_H = 29
    private const val SYNC_DIFF = 14.0

    /** A tiny blurred copy for comparing whole screens (animations and small moves barely change it). */
    private fun thumb(gray: Mat?): Mat? {
        if (gray == null || gray.empty()) return null
        val small = Mat()
        Imgproc.resize(gray, small, Size(THUMB_W.toDouble(), THUMB_H.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        Imgproc.GaussianBlur(small, small, Size(3.0, 3.0), 0.0)
        return small
    }
}
