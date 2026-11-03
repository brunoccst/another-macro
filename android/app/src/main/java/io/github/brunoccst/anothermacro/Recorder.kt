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
 * Android gives an app no way to watch touches meant for another app (an accessibility
 * service can only see them by taking them over, and an overlay that lets them through
 * gets no coordinates). So while recording, a see-through accessibility overlay covers
 * the screen and **catches** each touch: when the finger lifts, the overlay steps aside
 * and the same gesture (same path, same timing) is played to the game with the
 * accessibility gestures the macros use. The game sees every move a moment late (a tap
 * ~0.1 s, a hold after the finger lifts), and the recording is exactly what was played,
 * so replaying it does what the run did.
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

    @Volatile
    var session: Session? = null
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

    /** Start catching touches. Call on the main thread. */
    fun start(service: InputService): Boolean {
        if (session != null) return false
        if (Runner.isRunning) { MacroLog.i("stop the macro before recording"); return false }
        root(service).listFiles().orEmpty().filter { it.name.startsWith(".pending") }.forEach { it.deleteRecursively() }
        val s = Session(service, File(root(service), ".pending-${System.currentTimeMillis()}").apply { mkdirs() })
        if (!s.show()) return false
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
    fun stop(done: (File?) -> Unit) {
        val s = session ?: return done(null)
        session = null
        s.close()
        val main = Handler(Looper.getMainLooper())
        Thread {
            val dir = runCatching { s.finish() }.onFailure { MacroLog.e("recording: could not save", it) }.getOrNull()
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

    /** The see-through overlay that catches touches and plays them on to the game. */
    class Session(private val service: InputService, val dir: File) {
        private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        private val main = Handler(Looper.getMainLooper())
        private val worker = Executors.newSingleThreadExecutor()
        private val startedAt = SystemClock.uptimeMillis()
        private val gestures = java.util.Collections.synchronizedList(ArrayList<Gesture>())
        private var width = 0
        private var height = 0
        @Volatile private var echoing = false
        private var lastEchoEnd = 0L
        private val catcher = Catcher(service)
        private val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )

        // the move being made
        private var downAt = 0L
        private var points = ArrayList<Triple<Long, Double, Double>>()
        private var multi = false
        private var frameMat: Mat? = null

        @SuppressLint("ClickableViewAccessibility")
        fun show(): Boolean {
            catcher.setOnTouchListener { _, e -> onTouch(e); true }
            return runCatching { wm.addView(catcher, params); true }
                .onFailure { MacroLog.e("could not start recording", it) }.getOrDefault(false)
        }

        private fun screenSize() {
            val p = android.graphics.Point()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealSize(p)
            width = p.x; height = p.y
        }

        private fun onTouch(e: MotionEvent) {
            if (echoing) return
            val now = SystemClock.uptimeMillis()
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    screenSize()
                    downAt = now
                    points = ArrayList()
                    multi = false
                    add(0L, e.rawX, e.rawY)
                    // the screen as it was when the move started (the game gets nothing while the finger is down)
                    frameMat?.release()
                    frameMat = MacroService.capture?.grabColor()
                }
                MotionEvent.ACTION_MOVE -> {
                    // rawX/rawY of the history aren't available on API < 29: offset the view-relative ones
                    val dx = e.rawX - e.x
                    val dy = e.rawY - e.y
                    for (h in 0 until e.historySize) add(e.getHistoricalEventTime(h) - e.downTime, e.getHistoricalX(h) + dx, e.getHistoricalY(h) + dy)
                    add(e.eventTime - e.downTime, e.rawX, e.rawY)
                }
                MotionEvent.ACTION_POINTER_DOWN -> multi = true
                MotionEvent.ACTION_UP -> {
                    add(e.eventTime - e.downTime, e.rawX, e.rawY)
                    finishGesture()
                }
                MotionEvent.ACTION_CANCEL -> { frameMat?.release(); frameMat = null }
            }
        }

        private fun add(t: Long, xPx: Float, yPx: Float) {
            val x = (xPx / width.coerceAtLeast(1)).toDouble().coerceIn(0.0, 1.0)
            val y = (yPx / height.coerceAtLeast(1)).toDouble().coerceIn(0.0, 1.0)
            val last = points.lastOrNull()
            // keep a point every 16 ms (one per frame) at most; always the last one
            if (last != null && t - last.first < 16 && points.size > 1) points[points.size - 1] = Triple(t, x, y)
            else if (last == null || t > last.first || x != last.second || y != last.third) points += Triple(t, x, y)
        }

        private fun finishGesture() {
            val g = Gesture(downAt - startedAt, points, multi)
            g.gapMs = (downAt - lastEchoEnd.coerceAtLeast(startedAt)).coerceAtLeast(0)
            val index = gestures.size
            gestures += g
            val mat = frameMat
            frameMat = null
            if (multi) MacroLog.i("recording: move ${index + 1} used two fingers; only the first is played")
            // step aside, then play the move to the game
            echoing = true
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            runCatching { wm.updateViewLayout(catcher, params) }
            val w = width; val h = height
            Thread {
                Thread.sleep(60) // let the window manager apply it first, or the move lands on the overlay
                g.echoMs = SystemClock.uptimeMillis() - startedAt
                g.echoOk = Replay.play(service, g.points, w, h)
                lastEchoEnd = SystemClock.uptimeMillis()
                if (!g.echoOk) MacroLog.i("recording: move ${index + 1} was cut short (touch the screen only after the last move finished)")
                main.post {
                    params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                    if (catcher.isAttachedToWindow) runCatching { wm.updateViewLayout(catcher, params) }
                    echoing = false
                }
            }.start()
            MacroLog.status("recording: move ${index + 1} (${describe(g)})")
            if (mat != null) worker.execute { saveFrame(g, index, mat) }
        }

        /** Save the frame of move [index] (scaled down) and, for a tap, the text under the finger. */
        private fun saveFrame(g: Gesture, index: Int, bgr: Mat) {
            try {
                val frames = File(dir, "frames").apply { mkdirs() }
                val name = "%03d.jpg".format(index)
                val small = Mat()
                val scale = FRAME_WIDTH.toDouble() / bgr.cols()
                Imgproc.resize(bgr, small, Size(FRAME_WIDTH.toDouble(), Math.round(bgr.rows() * scale).toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
                Imgcodecs.imwrite(File(frames, name).path, small, MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 80))
                small.release()
                g.frame = "frames/$name"
                if (isTap(g)) g.label = labelAt(bgr, g.points.first().second, g.points.first().third)
            } catch (e: Exception) {
                MacroLog.e("recording: could not save the frame of move ${index + 1}", e)
            } finally {
                bgr.release()
            }
        }

        /** The text line the tap landed on (or the nearest one close by), read by OCR. */
        private fun labelAt(bgr: Mat, x: Double, y: Double): String? {
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

        /** Stop catching touches (main thread). */
        fun close() {
            runCatching { wm.removeView(catcher) }
            frameMat?.release(); frameMat = null
        }

        /** Write recording.json (background thread, after [close]); null (and nothing kept) if no move was made. */
        fun finish(): File? {
            // let the last echo and frame finish
            val until = SystemClock.uptimeMillis() + 15000
            while (echoing && SystemClock.uptimeMillis() < until) Thread.sleep(20)
            worker.shutdown()
            worker.awaitTermination(15, TimeUnit.SECONDS)
            val endMs = SystemClock.uptimeMillis() - startedAt
            val list = synchronized(gestures) { gestures.toList() }
            if (list.isEmpty()) {
                dir.deleteRecursively()
                MacroLog.status("recording: nothing recorded")
                return null
            }
            File(dir, "recording.json").writeText(toJson(list, width, height, endMs).toString(1))
            MacroLog.status("recording: ${list.size} moves, ${endMs / 1000}s; name it to save")
            return dir
        }
    }

    /** Fully see-through, with a thin red frame so it's clear the screen is being recorded. */
    private class Catcher(context: Context) : View(context) {
        private val paint = Paint().apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#C62828")
            strokeWidth = 3 * context.resources.displayMetrics.density
        }
        override fun onDraw(canvas: Canvas) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }
    }

    internal fun isTap(g: Gesture): Boolean {
        val (_, x0, y0) = g.points.first()
        val far = g.points.maxOf { hypot(it.second - x0, (it.third - y0) * 0.45) }
        return g.durMs < 500 && far < 0.012
    }

    private fun describe(g: Gesture): String {
        if (isTap(g)) return "tap"
        val (_, x0, y0) = g.points.first()
        val (_, x1, y1) = g.points.last()
        val dx = x1 - x0
        val dy = (y1 - y0) * 0.45
        val dir = if (abs(dx) >= abs(dy)) (if (dx > 0) "right" else "left") else (if (dy > 0) "down" else "up")
        return "$dir ${g.durMs} ms"
    }

    const val FRAME_WIDTH = 1200
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
            if (!ctx.gesture(pts)) MacroLog.i("$name: move ${i + 1} was cut short")
        }
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
