package io.github.brunoccst.anothermacro

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Proof-of-concept screen: turn on taps (accessibility), allow screen capture,
 * pick a macro and its parameters, run/stop, and watch the log. Once screen
 * capture is on, floating buttons over the game start/pause/stop/restart the
 * last macro (see ControlsOverlay).
 *
 * Can also be driven from adb for testing:
 *   am start -n io.github.brunoccst.anothermacro/.MainActivity --es run grind-battles --es params "battles=3,mode=skill"
 */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var log: TextView
    private val paramFields = LinkedHashMap<String, EditText>()
    private val scenario: Scenario = Scenarios.all.first()
    private val logListener: (String) -> Unit = { text -> if (::log.isInitialized) log.text = text }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun label(text: String, bold: Boolean = false) = TextView(this).apply {
            this.text = text
            textSize = if (bold) 20f else 14f
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, pad / 2, 0, pad / 4)
        }
        fun button(text: String, onClick: () -> Unit) = Button(this).apply {
            this.text = text
            setOnClickListener { onClick() }
        }

        column.addView(label("Another Macro (proof of concept)", bold = true))
        status = label("")
        column.addView(status)
        column.addView(button("1. Turn on taps (Accessibility)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        column.addView(button("2. Allow screen capture") { requestCapture() })

        column.addView(label("${scenario.name}: ${scenario.description}", bold = true))
        for ((key, p) in scenario.params) {
            column.addView(label("$key — ${p.help}"))
            val field = EditText(this).apply {
                setText(p.default)
                inputType = InputType.TYPE_CLASS_TEXT
                setSingleLine()
            }
            paramFields[key] = field
            column.addView(field)
        }
        column.addView(button("Run ${scenario.name}") { run(scenario.name, paramFields.mapValues { it.value.text.toString() }) })
        column.addView(button("Stop") { startService(MacroService.intent(this, MacroService.ACTION_STOP_RUN)) })
        column.addView(button("Turn off screen capture") { startService(MacroService.intent(this, MacroService.ACTION_SHUTDOWN)) })

        column.addView(label("Log", bold = true))
        log = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
        }
        column.addView(log)

        setContentView(ScrollView(this).apply { addView(column) })
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        handleAdbIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAdbIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        MacroLog.listen(logListener)
        log.text = MacroLog.text()
        refreshStatus()
    }

    override fun onPause() {
        MacroLog.unlisten(logListener)
        super.onPause()
    }

    private fun refreshStatus() {
        val taps = if (InputService.instance != null) "on" else "OFF — step 1"
        val cap = MacroService.capture?.let { "on (${it.width}x${it.height})" } ?: "OFF — step 2"
        status.text = "Taps: $taps\nScreen capture: $cap\nMacro running: ${if (Runner.isRunning) "yes" else "no"}"
    }

    private fun requestCapture() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQUEST_CAPTURE)
    }

    @Deprecated("startActivityForResult is enough here")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            MacroLog.i("screen capture was not allowed")
            return
        }
        val svc = MacroService.intent(this, MacroService.ACTION_START_CAPTURE)
            .putExtra(MacroService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(MacroService.EXTRA_RESULT_DATA, data)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc) else startService(svc)
        status.postDelayed({ refreshStatus() }, 800)
    }

    private fun run(name: String, params: Map<String, String>) {
        if (MacroService.capture == null) { MacroLog.i("allow screen capture first (step 2)"); return }
        if (InputService.instance == null) { MacroLog.i("turn on taps first (step 1)"); return }
        startService(LastRun.intent(this, name, params))
        // get out of the way so the game is in front
        moveTaskToBack(true)
    }

    private fun handleAdbIntent(intent: Intent?) {
        val name = intent?.getStringExtra("run") ?: return
        val params = intent.getStringExtra("params").orEmpty().split(",").mapNotNull {
            val kv = it.split("=", limit = 2)
            if (kv.size == 2) kv[0].trim() to kv[1].trim() else null
        }.toMap()
        intent.removeExtra("run")
        run(name, params)
    }

    companion object {
        private const val REQUEST_CAPTURE = 1
    }
}
