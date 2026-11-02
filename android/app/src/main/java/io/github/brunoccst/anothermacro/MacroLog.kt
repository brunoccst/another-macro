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

    /** Log a step in plain words and show it on the live log. */
    fun status(message: String) {
        i(message)
        status = message
        main.post { statusListeners.forEach { it(message) } }
    }

    fun onStatus(l: (String) -> Unit) { statusListeners += l }
    fun offStatus(l: (String) -> Unit) { statusListeners -= l }

    @Synchronized
    fun text(): String = lines.joinToString("\n")

    @Synchronized
    fun listen(l: (String) -> Unit) { listeners += l }

    @Synchronized
    fun unlisten(l: (String) -> Unit) { listeners -= l }
}
