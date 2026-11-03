package io.github.brunoccst.anothermacro

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Run log: shown in the app, and in logcat under the tag "AnotherMacro" (adb logcat -s AnotherMacro). */
object MacroLog {
    private const val TAG = "AnotherMacro"
    private val lines = ArrayDeque<String>()
    private val listeners = mutableListOf<(String) -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun i(message: String) {
        Log.i(TAG, message)
        lines.addLast("${fmt.format(Date())} $message")
        while (lines.size > 200) lines.removeFirst()
        val text = text()
        main.post { listeners.forEach { it(text) } }
    }

    fun e(message: String, t: Throwable? = null) {
        Log.e(TAG, message, t)
        i("ERROR: $message${t?.let { " (${it.javaClass.simpleName}: ${it.message})" } ?: ""}")
    }

    /** What the macro is doing now, in plain words (shown by the live log overlay). */
    @Volatile var status: String = ""
        private set
    private val statusListeners = java.util.concurrent.CopyOnWriteArrayList<(String) -> Unit>()

    /** Which step of a routine is running (e.g. "Daily 2/5"), put in front of the live log; empty outside routines. */
    @Volatile var prefix: String = ""

    /** Log a step in plain words and show it on the live log (a repeat of the same words is shown, not logged again). */
    fun status(message: String) {
        if (message != status) i(message)
        show(message)
    }

    /** Show [message] on the live log without a new log line (e.g. back to the task after a battle). */
    fun show(message: String) {
        status = message
        val shown = if (prefix.isEmpty()) message else "$prefix · $message"
        main.post { statusListeners.forEach { it(shown) } }
    }

    /** What the live log shows now (with the routine's step in front). */
    val shown: String get() = if (prefix.isEmpty() || status.isEmpty()) status else "$prefix · $status"

    fun onStatus(l: (String) -> Unit) { statusListeners += l }
    fun offStatus(l: (String) -> Unit) { statusListeners -= l }

    @Synchronized
    fun text(): String = lines.joinToString("\n")

    @Synchronized
    fun listen(l: (String) -> Unit) { listeners += l }

    @Synchronized
    fun unlisten(l: (String) -> Unit) { listeners -= l }
}
