package io.github.brunoccst.anothermacro

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Main screen: first-run guide (taps, screen capture), updates from GitHub
 * Releases, and the macro with its params. Turn on taps (accessibility), allow screen capture,
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
    private var scenario: Scenario = Scenarios.all.first()
    private lateinit var paramsBox: LinearLayout
    private lateinit var macroDescription: TextView
    private lateinit var runButton: Button
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

        column.addView(label("Another Macro ${BuildConfig.VERSION_NAME}", bold = true))
        status = label("")
        column.addView(status)

        // --- first-run guide ---------------------------------------------------
        column.addView(label("Setup", bold = true))
        column.addView(button("1. Turn on taps (Accessibility)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        column.addView(label(
            "In Accessibility, open Another Macro (Samsung: under \"Installed apps\") and switch it on. " +
                "If it is greyed out with \"Restricted setting\" (Android 13+ for apps installed from a browser): " +
                "open App info below, tap ⋮ (top right) → Allow restricted settings, then try again."
        ))
        column.addView(button("Open App info") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })
        column.addView(button("2. Allow screen capture") { requestCapture() })
        column.addView(label("Android asks \"Start recording or casting?\" → Start now. It is needed again after the app restarts or updates."))

        // --- updates -----------------------------------------------------------
        column.addView(label("Updates", bold = true))
        updateInfo = label("")
        column.addView(updateInfo)
        updateButton = button("Check for updates") { onUpdateButton() }
        column.addView(updateButton)

        // --- macro -------------------------------------------------------------
        column.addView(label("Macro", bold = true))
        val names = Scenarios.all.map { it.name }
        val picker = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, names)
        }
        column.addView(picker)
        macroDescription = label("")
        column.addView(macroDescription)
        paramsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(paramsBox)
        runButton = button("Run") { run(scenario.name, paramFields.mapValues { it.value.text.toString() }) }
        column.addView(runButton)
        picker.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = showMacro(Scenarios.all[position])
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        val last = LastRun.load(this)?.first
        picker.setSelection(names.indexOf(last).coerceAtLeast(0))
        column.addView(button("Stop") { startService(MacroService.intent(this, MacroService.ACTION_STOP_RUN)) })
        column.addView(button("Turn off screen capture") { startService(MacroService.intent(this, MacroService.ACTION_SHUTDOWN)) })
        column.addView(label("While screen capture is on, floating buttons over the game start ▶, pause ❚❚, stop ■ and restart ↻ the last macro. Drag ≡ to move them, tap it to fold them."))

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

    /** Show [s]'s description and a field per param (prefilled with the last values used). */
    private fun showMacro(s: Scenario) {
        scenario = s
        macroDescription.text = s.description
        runButton.text = "Run ${s.name}"
        paramsBox.removeAllViews()
        paramFields.clear()
        val last = LastRun.load(this)?.takeIf { it.first == s.name }?.second
        for ((key, p) in s.params) {
            paramsBox.addView(TextView(this).apply { text = "$key — ${p.help}"; textSize = 14f })
            val field = EditText(this).apply {
                setText(last?.get(key) ?: p.default)
                inputType = InputType.TYPE_CLASS_TEXT
                setSingleLine()
            }
            paramFields[key] = field
            paramsBox.addView(field)
        }
    }

    // --- updates ---------------------------------------------------------------
    private lateinit var updateInfo: TextView
    private lateinit var updateButton: Button
    private var available: Updater.Release? = null

    private fun checkForUpdates(automatic: Boolean) {
        if (BuildConfig.VERSION_NAME.contains("-dev")) {
            // test builds from CI: a release would always look newer and replace them
            updateInfo.text = "Development build (${BuildConfig.VERSION_NAME}): updates are off."
            updateButton.isEnabled = false
            return
        }
        val prefs = getSharedPreferences("updates", MODE_PRIVATE)
        if (automatic && System.currentTimeMillis() - prefs.getLong("last_check", 0) < 6 * 3600_000L) return
        prefs.edit().putLong("last_check", System.currentTimeMillis()).apply()
        updateInfo.text = "Checking GitHub for updates…"
        Thread {
            val result = runCatching { Updater.checkNewer() }
            runOnUiThread {
                result.onSuccess { r ->
                    available = r
                    updateInfo.text = if (r == null) "Up to date (${BuildConfig.VERSION_NAME})."
                    else "Version ${r.version} is available (you have ${BuildConfig.VERSION_NAME}).\n${r.notes.take(400)}"
                    updateButton.text = if (r == null) "Check for updates" else "Update to ${r.version}"
                }.onFailure { updateInfo.text = "Could not check for updates: ${it.message}" }
            }
        }.start()
    }

    private fun onUpdateButton() {
        val r = available ?: return checkForUpdates(automatic = false)
        if (!Updater.canInstall(this)) {
            updateInfo.text = "Allow Another Macro to install apps (\"Install unknown apps\"), come back and tap Update again."
            Updater.openInstallPermission(this)
            return
        }
        if (Runner.isRunning) {
            updateInfo.text = "Stop the running macro first."
            return
        }
        updateButton.isEnabled = false
        updateInfo.text = "Downloading ${r.version}…"
        Thread {
            val result = runCatching { Updater.downloadAndInstall(this, r) }
            runOnUiThread {
                updateButton.isEnabled = true
                result.onSuccess { updateInfo.text = "Installing ${r.version}: confirm with Update if Android asks." }
                    .onFailure { updateInfo.text = "Update failed: ${it.message}" }
            }
        }.start()
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
        checkForUpdates(automatic = true)
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
