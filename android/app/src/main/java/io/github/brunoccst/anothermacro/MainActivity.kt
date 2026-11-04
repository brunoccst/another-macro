package io.github.brunoccst.anothermacro

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
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

    private lateinit var log: TextView
    /** Each param's current value, read from its text box, dropdown or switch. */
    private val paramFields = LinkedHashMap<String, () -> String>()
    private var scenario: Scenario? = null
    private lateinit var macros: List<Scenario>
    private lateinit var paramsBox: LinearLayout
    private lateinit var userTemplates: TextView
    private lateinit var detailCard: LinearLayout
    private lateinit var detailTitle: TextView
    private lateinit var detailIcon: android.widget.ImageView
    private lateinit var detailDescription: TextView
    private lateinit var runButton: TextView
    private lateinit var setupCard: LinearLayout
    private lateinit var tapsChip: TextView
    private lateinit var captureChip: TextView
    private lateinit var runChip: TextView
    private lateinit var scroll: ScrollView
    private val macroRows = HashMap<String, View>()
    private val logListener: (String) -> Unit = { text -> if (::log.isInitialized) log.text = text }
    private val runListener: () -> Unit = { runOnUiThread { refreshStatus() } }

    private fun dp(v: Int) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(32))
        }

        // --- header --------------------------------------------------------------
        column.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            addView(Ui.text(this@MainActivity, "Another Macro", 26f, bold = true))
            addView(Ui.text(this@MainActivity, "  ${BuildConfig.VERSION_NAME}", 12f, Ui.MUTED).apply { setPadding(0, 0, 0, dp(4)) })
        })
        column.addView(Ui.text(this, "Automation for Another Eden, on this phone", 13f, Ui.MUTED).apply { setPadding(0, dp(2), 0, dp(12)) })
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tapsChip = Ui.chip(this); captureChip = Ui.chip(this); runChip = Ui.chip(this)
        chips.addView(tapsChip); chips.addView(captureChip); chips.addView(runChip)
        column.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) })

        // --- first-run guide (only while something is missing) ----------------------
        setupCard = Ui.card(this, Ui.ACCENT_DIM).apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(14)
            addView(Ui.text(this@MainActivity, "Get started", 17f, bold = true))
            addView(Ui.text(this@MainActivity,
                "1. Turn on taps: in Accessibility open Another Macro (Samsung: \"Installed apps\") and switch it on. " +
                    "Greyed out (\"Restricted setting\")? App info → ⋮ → Allow restricted settings.\n" +
                    "2. Allow screen capture → \"Start now\" (again after every update).", 13f, Ui.TEXT).apply { setPadding(0, dp(6), 0, dp(4)) })
            addView(Ui.button(this@MainActivity, "Turn on taps", primary = true) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
            addView(Ui.button(this@MainActivity, "Allow screen capture", primary = true) { requestCapture() })
            addView(Ui.button(this@MainActivity, "App info") {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            })
        }
        column.addView(setupCard)

        // --- macros, grouped ---------------------------------------------------------
        macros = Scenarios.all(this)
        for ((group, list) in Groups.sort(macros)) {
            column.addView(Ui.withIcon(this, group.iconRes, Ui.section(this, group.title).apply { setPadding(0, dp(18), 0, dp(8)) }, 18).apply { setPadding(dp(6), 0, 0, 0) })
            val card = Ui.card(this).apply { setPadding(dp(6), dp(6), dp(6), dp(6)) }
            for ((i, m) in list.withIndex()) {
                if (i > 0) card.addView(View(this).apply {
                    setBackgroundColor(Ui.LINE)
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { leftMargin = dp(12); rightMargin = dp(12) }
                })
                card.addView(macroRow(m))
            }
            column.addView(card)
        }

        // --- the selected macro: its options and Run --------------------------------------
        detailCard = Ui.card(this, Ui.CARD).apply { visibility = View.GONE }
        detailTitle = Ui.text(this, "", 19f, bold = true)
        detailDescription = Ui.text(this, "", 13f, Ui.MUTED).apply { setPadding(0, dp(4), 0, dp(6)) }
        paramsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        runButton = Ui.button(this, "Run", primary = true) { scenario?.let { run(it.name, paramFields.mapValues { f -> f.value() }) } }
        detailIcon = Ui.icon(this, R.drawable.ic_puzzle, 30)
        detailCard.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(detailIcon); addView(detailTitle)
        })
        detailCard.addView(detailDescription)
        detailCard.addView(paramsBox)
        detailCard.addView(runButton)
        detailCard.addView(Ui.button(this, "Stop", color = Ui.BAD) { startService(MacroService.intent(this, MacroService.ACTION_STOP_RUN)) })
        column.addView(Ui.section(this, "Selected macro"))
        column.addView(detailCard)

        // --- settings ----------------------------------------------------------------------
        column.addView(Ui.section(this, "Settings"))
        column.addView(Ui.card(this).apply {
            addView(switchRow("Live log", "A line at the bottom right saying what the macro is doing, while it runs. Also on the overlay's 💬.",
                LiveLog.enabled(this@MainActivity)) { on -> LiveLog.setEnabled(this@MainActivity, on) })
            addView(Ui.divider(this@MainActivity))
            addView(Ui.text(this@MainActivity, "Floating buttons", 15f, bold = true))
            addView(Ui.text(this@MainActivity, "While screen capture is on: ▶ start, ❚❚ pause, ■ stop, ↻ restart the selected macro, 💬 live log, 📷 capture a template, ⏺ record a run. Drag ≡ to move them, tap it to fold them.", 13f, Ui.MUTED))
            addView(Ui.button(this@MainActivity, "Turn off screen capture", color = Ui.MUTED) { startService(MacroService.intent(this@MainActivity, MacroService.ACTION_SHUTDOWN)) })
            addView(Ui.divider(this@MainActivity))
            addView(Ui.text(this@MainActivity, "Recording runs (⏺)", 15f, bold = true))
            addView(Ui.text(this@MainActivity, "⏺ on the floating buttons records what you play (taps, walks, turns) into a macro that replays it. Needs the app paired with Wireless debugging once.", 13f, Ui.MUTED))
            addView(Ui.button(this@MainActivity, "Set up recording") { startActivity(Intent(this@MainActivity, AdbSetupActivity::class.java)) })
        })

        column.addView(Ui.section(this, "Your macros and templates"))
        column.addView(Ui.card(this).apply {
            addView(Ui.text(this@MainActivity, "Macros are .yaml files (see macros/ in the repo). Templates you capture with 📷 can be used in them by name.", 13f, Ui.MUTED))
            userTemplates = Ui.text(this@MainActivity, "", 13f).apply { setPadding(0, dp(6), 0, 0) }
            addView(userTemplates)
            addView(Ui.button(this@MainActivity, "Import macro (.yaml)") {
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQUEST_IMPORT)
            })
        })

        column.addView(Ui.section(this, "Updates"))
        column.addView(Ui.card(this).apply {
            updateInfo = Ui.text(this@MainActivity, "", 13f, Ui.MUTED)
            addView(updateInfo)
            updateButton = Ui.button(this@MainActivity, "Check for updates") { onUpdateButton() }
            addView(updateButton)
        })

        column.addView(Ui.section(this, "Log"))
        column.addView(Ui.card(this, Color.parseColor("#0B0E12")).apply {
            log = TextView(this@MainActivity).apply {
                typeface = Typeface.MONOSPACE
                textSize = 11f
                setTextColor(Color.parseColor("#B8C2CC"))
                setTextIsSelectable(true)
            }
            addView(log)
        })

        scroll = ScrollView(this).apply { setBackgroundColor(Ui.BG); addView(column) }
        setContentView(scroll)
        val last = LastRun.load(this)?.first
        (macros.firstOrNull { it.name == last } ?: macros.firstOrNull())?.let { showMacro(it) }
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        handleAdbIntent(intent)
    }

    /** One macro in its group's list: name and description; tap to select it. */
    private fun macroRow(m: Scenario): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        isClickable = true
        addView(Ui.icon(this@MainActivity, Groups.icon(m), 30).apply { (layoutParams as LinearLayout.LayoutParams).rightMargin = dp(14) })
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(Ui.text(this@MainActivity, m.name, 15f, bold = true))
            if (m.description.isNotBlank()) addView(Ui.text(this@MainActivity, m.description, 12.5f, Ui.MUTED).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        setOnClickListener {
            showMacro(m)
            scroll.post { scroll.smoothScrollTo(0, detailCard.top - dp(40)) }
        }
        macroRows[m.name] = this
    }

    private fun switchRow(title: String, help: String, on: Boolean, changed: (Boolean) -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(Ui.text(this@MainActivity, title, 15f, bold = true))
            if (help.isNotBlank()) addView(Ui.text(this@MainActivity, help, 12.5f, Ui.MUTED))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(android.widget.Switch(this@MainActivity).apply {
            isChecked = on
            thumbTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Ui.ACCENT, Ui.MUTED))
            trackTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Ui.ACCENT_DIM, Ui.LINE))
            setOnCheckedChangeListener { _, v -> changed(v) }
        })
    }

    /** Show [s]'s description and its options (prefilled with the last values used). */
    private fun showMacro(s: Scenario) {
        scenario = s
        for ((name, row) in macroRows) row.background = if (name == s.name) Ui.rounded(Ui.ACCENT_DIM, dp(12).toFloat()) else null
        detailCard.visibility = View.VISIBLE
        detailTitle.text = s.name
        detailIcon.setImageResource(Groups.icon(s))
        detailDescription.text = s.description
        runButton.text = "▶  Run ${s.name}"
        paramsBox.removeAllViews()
        paramFields.clear()
        val last = LastRun.load(this)?.takeIf { it.first == s.name }?.second
        // params shown only while a switch is on: their views, by that switch
        val dependents = HashMap<String, MutableList<View>>()
        val switches = HashMap<String, Boolean>()
        val outer = paramsBox
        for ((key, p) in s.params) {
            val value = last?.get(key) ?: p.default
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            outer.addView(box)
            p.showIf?.let { dependents.getOrPut(it) { ArrayList() } += box; box.visibility = if (switches[it] == true) View.VISIBLE else View.GONE }
            val paramsBox = box
            paramsBox.addView(Ui.divider(this))
            if (p.isBool) {
                var cur = FlowScenario.typed(value) == true
                switches[key] = cur
                paramsBox.addView(switchRow(Ui.title(key), p.help, cur) { on ->
                    cur = on
                    dependents[key]?.forEach { it.visibility = if (on) View.VISIBLE else View.GONE }
                })
                paramFields[key] = { cur.toString() }
                continue
            }
            paramsBox.addView(Ui.text(this, Ui.title(key), 15f, bold = true))
            if (p.help.isNotBlank()) paramsBox.addView(Ui.text(this, p.help, 12.5f, Ui.MUTED))
            if (p.choices.isNotEmpty()) {
                val options = if (value in p.choices) p.choices else p.choices + value
                // choices as a row of pills: tap one to pick it
                var cur = value
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
                val pills = ArrayList<TextView>()
                fun paint() = pills.forEach { t ->
                    val sel = t.tag == cur
                    t.setTextColor(if (sel) Color.parseColor("#0B1A22") else Ui.TEXT)
                    t.background = Ui.pressable(this, if (sel) Ui.ACCENT else Ui.CARD_ALT, 16)
                }
                for (o in options) {
                    val t = Ui.text(this, o, 14f).apply {
                        tag = o
                        gravity = Gravity.CENTER
                        minWidth = dp(44)
                        setPadding(dp(14), dp(8), dp(14), dp(8))
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) }
                        setOnClickListener { cur = o; paint() }
                    }
                    pills += t; row.addView(t)
                }
                paint()
                paramsBox.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) })
                paramFields[key] = { cur }
                continue
            }
            val field = EditText(this).apply {
                setText(value)
                inputType = InputType.TYPE_CLASS_TEXT
                setSingleLine()
                setTextColor(Ui.TEXT)
                background = Ui.rounded(Ui.CARD_ALT, dp(12).toFloat())
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
            }
            paramFields[key] = { field.text.toString() }
            paramsBox.addView(field)
        }
    }

    // --- updates ---------------------------------------------------------------
    private lateinit var updateInfo: TextView
    private lateinit var updateButton: TextView
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
        Runner.listeners += runListener
        log.text = MacroLog.text()
        refreshStatus()
        checkForUpdates(automatic = true)
        val mine = TemplateCapture.list(this)
        userTemplates.text = if (mine.isEmpty()) "Captured templates: none yet." else "Captured templates: ${mine.joinToString()}"
    }

    override fun onPause() {
        MacroLog.unlisten(logListener)
        Runner.listeners -= runListener
        super.onPause()
    }

    private fun refreshStatus() {
        val taps = InputService.instance != null
        val cap = MacroService.capture
        Ui.setChip(tapsChip, if (taps) "Taps on" else "Taps off", if (taps) Ui.GOOD else Ui.BAD)
        Ui.setChip(captureChip, if (cap != null) "Capture on" else "Capture off", if (cap != null) Ui.GOOD else Ui.BAD)
        Ui.setChip(runChip, when {
            Runner.isPaused -> "Paused: ${Runner.current}"
            Runner.isRunning -> "Running: ${Runner.current}"
            else -> "Idle"
        }, when { Runner.isPaused -> Ui.WARN; Runner.isRunning -> Ui.ACCENT; else -> Ui.MUTED })
        setupCard.visibility = if (taps && cap != null) View.GONE else View.VISIBLE
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
        if (requestCode == REQUEST_IMPORT) { importMacro(resultCode, data); return }
        if (requestCode != REQUEST_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            MacroLog.i("screen capture was not allowed")
            return
        }
        val svc = MacroService.intent(this, MacroService.ACTION_START_CAPTURE)
            .putExtra(MacroService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(MacroService.EXTRA_RESULT_DATA, data)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc) else startService(svc)
        log.postDelayed({ refreshStatus() }, 800)
    }

    /** Copy a picked .yaml into the user's macros (after checking it parses), then reload. */
    private fun importMacro(resultCode: Int, data: Intent?) {
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        try {
            val text = contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
            val macro = FlowScenario.parse(text, uri.lastPathSegment ?: "import")
            java.io.File(Scenarios.userDir(this), "${macro.name}.yaml").writeText(text)
            MacroLog.i("imported macro '${macro.name}'")
            recreate()
        } catch (e: Exception) {
            MacroLog.e("could not import: ${e.message}")
        }
    }

    private fun run(name: String, params: Map<String, String>) {
        if (MacroService.capture == null) { MacroLog.i("allow screen capture first (step 2)"); return }
        if (InputService.instance == null) { MacroLog.i("turn on taps first (step 1)"); return }
        startService(LastRun.intent(this, name, params))
        // get out of the way so the game is in front
        moveTaskToBack(true)
    }

    private fun handleAdbIntent(intent: Intent?) {
        // the floating ⏺ asks for Termux's "Run commands" permission here (only an activity can ask)
        if (intent?.getBooleanExtra(Termux.EXTRA_ASK, false) == true) {
            intent.removeExtra(Termux.EXTRA_ASK)
            requestPermissions(arrayOf(Termux.PERMISSION), REQUEST_TERMUX)
            return
        }
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
        private const val REQUEST_IMPORT = 3
        private const val REQUEST_TERMUX = 4
    }
}
