package io.github.brunoccst.anothermacro

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * System: everything about the app itself, in tabs like the Macros page: Setup (taps, screen
 * capture), Claude (the phone connector: relay address, tokens, on/off, and its safety rules),
 * Settings, Updates and the Log.
 */
class SystemPage(private val a: MainActivity) {
    private fun dp(v: Int) = a.dp(v)

    private lateinit var tapsStatus: TextView
    private lateinit var captureStatus: TextView
    private lateinit var tapsButton: TextView
    private lateinit var captureButton: TextView
    private lateinit var connectorStatus: TextView
    private lateinit var connectorButton: TextView
    private lateinit var updateInfo: TextView
    private lateinit var updateButton: TextView
    private lateinit var log: TextView
    private var available: Updater.Release? = null
    private val logListener: (String) -> Unit = { text -> if (::log.isInitialized) log.text = text }
    private val connectorListener: () -> Unit = { a.runOnUiThread { refreshConnector() } }
    val view: View
    private val tabsRow = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(16), dp(12), dp(16), dp(4)) }
    /** Each tab's content, by tab id. */
    private val tabs = LinkedHashMap<String, LinearLayout>()
    private val tabTitles = mapOf("setup" to "Setup", "claude" to "Claude", "settings" to "Settings", "updates" to "Updates", "log" to "Log")
    private var tab = a.getSharedPreferences("system_page", android.content.Context.MODE_PRIVATE).getString("tab", "setup") ?: "setup"
    private lateinit var scroll: ScrollView

    private fun tab(id: String) = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }.also { tabs[id] = it }

    init {
        val column = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(32))
        }
        var into = tab("setup")

        // --- setup: taps and screen capture ------------------------------------------------
        into.addView(Ui.section(a, "Setup"))
        into.addView(Ui.card(a).apply {
            tapsStatus = Ui.text(a, "", 15f, bold = true)
            addView(tapsStatus)
            addView(Ui.text(a, "In Accessibility open Another Macro (Samsung: \"Installed apps\") and switch it on. " +
                "Greyed out (\"Restricted setting\")? App info → ⋮ → Allow restricted settings.", 12.5f, Ui.MUTED))
            tapsButton = Ui.button(a, "Turn on taps", primary = true) { a.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            addView(tapsButton)
            addView(Ui.button(a, "App info") {
                a.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.packageName}")))
            })
            addView(Ui.divider(a))
            captureStatus = Ui.text(a, "", 15f, bold = true)
            addView(captureStatus)
            addView(Ui.text(a, "Lets the macros see the game. Allow it with \"Start now\", again after every update.", 12.5f, Ui.MUTED))
            captureButton = Ui.button(a, "Allow screen capture", primary = true) { a.requestCapture() }
            addView(captureButton)
            addView(Ui.button(a, "Turn off screen capture", color = Ui.MUTED) { a.startService(MacroService.intent(a, MacroService.ACTION_SHUTDOWN)) })
        })

        // --- Claude: the phone connector ---------------------------------------------------
        into = tab("claude")
        into.addView(Ui.section(a, "Claude phone connector"))
        into.addView(Ui.card(a).apply {
            addView(Ui.text(a,
                "Chat with Claude in the Claude app or claude.ai, on your own plan, and let it see and control the game through this app. No API key is needed. It reaches the phone through your relay, a small free Cloudflare Worker on your account.", 13f, Ui.MUTED))
            connectorStatus = Ui.text(a, "", 13f).apply { setPadding(0, dp(6), 0, 0) }
            addView(connectorStatus)
            connectorButton = Ui.button(a, "Turn on", primary = true) { toggleConnector() }
            addView(connectorButton)
            addView(Ui.text(a, "Relay address", 15f, bold = true).apply { setPadding(0, dp(10), 0, 0) })
            addView(field("https://another-macro.<you>.workers.dev").apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                setText(ClaudeSettings.relayUrl(a))
                onChange { ClaudeSettings.setRelayUrl(a, it) }
            })
            addView(Ui.text(a,
                "Setup, once: 1) Make tokens. 2) Deploy the relay and paste the two tokens into it (guide below). 3) Paste the relay address above. 4) In claude.ai → Settings → Connectors → Add custom connector, paste the connector address.", 12.5f, Ui.MUTED).apply { setPadding(0, dp(6), 0, 0) })
            addView(Ui.button(a, "Make new tokens") { newTokens() })
            addView(Ui.button(a, "Copy phone token (for the relay)") { copy("phone token", ClaudeSettings.phoneToken(a)) })
            addView(Ui.button(a, "Copy connector token (for the relay)") { copy("connector token", ClaudeSettings.connectorToken(a)) })
            addView(Ui.button(a, "Copy connector address (for claude.ai)") { copyConnectorAddress() })
            addView(Ui.button(a, "Relay setup guide") { a.openUrl(ClaudeSettings.RELAY_GUIDE) })
            addView(Ui.text(a,
                "The connector address holds the secret token: keep it private. Making new tokens cuts off the old ones once the relay has the new pair. The connector turns itself off after 30 minutes without use, and the floating ■ stops what Claude is doing.", 12f, Ui.MUTED).apply { setPadding(0, dp(6), 0, 0) })
        })

        into.addView(Ui.section(a, "What Claude may do"))
        into.addView(Ui.card(a).apply {
            addView(Ui.text(a,
                "• Claude can only tap inside Another Eden.\n" +
                    "• It never buys anything: purchase screens are refused by the app, not just by Claude.\n" +
                    "• Taps on things that can't be undone (sell, discard, release, exchange…) or that mention Chronos Stones ask you first, here on the phone.\n" +
                    "• The connector is off until you turn it on, and turns itself off after 30 minutes without use. The floating ■ stops what Claude is doing.\n" +
                    "• Screenshots go through your relay to your Claude chat only while Claude uses the phone, and aren't saved on the phone.", 13f))
        })

        // --- settings ----------------------------------------------------------------------
        into = tab("settings")
        into.addView(Ui.section(a, "Settings"))
        into.addView(Ui.card(a).apply {
            addView(switchRow("Live log", "A line at the bottom right saying what the macro is doing, while it runs.",
                LiveLog.enabled(a)) { on -> LiveLog.setEnabled(a, on) })
            addView(Ui.divider(a))
            addView(Ui.text(a, "Floating buttons", 15f, bold = true))
            addView(Ui.text(a, "While screen capture is on: the selected macro (tap it to pick another), ▶ start, ❚❚ pause, ■ stop, ↻ restart. Drag ≡ to move them, tap it to fold them.", 12.5f, Ui.MUTED))
        })

        // --- updates -----------------------------------------------------------------------
        into = tab("updates")
        into.addView(Ui.section(a, "App updates"))
        into.addView(Ui.card(a).apply {
            updateInfo = Ui.text(a, "", 13f, Ui.MUTED)
            addView(updateInfo)
            updateButton = Ui.button(a, "Check for updates") { onUpdateButton() }
            addView(updateButton)
        })

        into.addView(Ui.text(a, "Macros update by themselves, without an app update: Macros → Macro library.", 12.5f, Ui.MUTED).apply { setPadding(dp(6), dp(2), dp(6), 0) })

        // --- log -----------------------------------------------------------------------------
        into = tab("log")
        into.addView(LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(Ui.section(a, "Log"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(Ui.text(a, "Copy", 13f, Ui.ACCENT, bold = true).apply {
                setPadding(dp(12), dp(18), dp(6), dp(8))
                isClickable = true
                setOnClickListener { copy("log", MacroLog.text(), sensitive = false) }
            })
        })
        into.addView(Ui.card(a, Color.parseColor("#0B0E12")).apply {
            log = TextView(a).apply {
                typeface = Typeface.MONOSPACE
                textSize = 11f
                setTextColor(Color.parseColor("#B8C2CC"))
                setTextIsSelectable(true)
            }
            addView(log)
        })

        for (t in tabs.values) column.addView(t)
        if (tab !in tabs) tab = "setup"
        drawTabs()
        scroll = ScrollView(a).apply {
            setBackgroundColor(Ui.BG)
            addView(LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                addView(android.widget.HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(tabsRow) })
                addView(column)
            })
        }
        view = scroll
    }

    /** Show the tab [id] (setup, claude, settings, updates, log). */
    fun showTab(id: String) {
        if (id !in tabs) return
        tab = id
        a.getSharedPreferences("system_page", android.content.Context.MODE_PRIVATE).edit().putString("tab", id).apply()
        drawTabs()
        scroll.post { scroll.scrollTo(0, 0) }
    }

    private fun drawTabs() {
        for ((id, box) in tabs) box.visibility = if (id == tab) View.VISIBLE else View.GONE
        tabsRow.removeAllViews()
        for ((id, title) in tabTitles) {
            val sel = id == tab
            tabsRow.addView(Ui.text(a, title, 14f, if (sel) Color.parseColor("#0B1A22") else Ui.TEXT, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(9), dp(16), dp(9))
                background = Ui.pressable(a, if (sel) Ui.ACCENT else Ui.CARD_ALT, 18)
                isClickable = true
                setOnClickListener { showTab(id) }
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) }
            })
        }
    }

    fun onResume() {
        MacroLog.listen(logListener)
        PhoneConnector.listeners += connectorListener
        log.text = MacroLog.text()
        refreshConnector()
        checkForUpdates(automatic = true)
    }

    fun onPause() {
        MacroLog.unlisten(logListener)
        PhoneConnector.listeners -= connectorListener
    }

    fun refreshSetup(taps: Boolean, capture: Boolean) {
        tapsStatus.text = if (taps) "✓  Taps are on" else "Taps are off"
        tapsStatus.setTextColor(if (taps) Ui.GOOD else Ui.TEXT)
        tapsButton.visibility = if (taps) View.GONE else View.VISIBLE
        captureStatus.text = if (capture) "✓  Screen capture is on" else "Screen capture is off"
        captureStatus.setTextColor(if (capture) Ui.GOOD else Ui.TEXT)
        captureButton.visibility = if (capture) View.GONE else View.VISIBLE
    }

    // --- connector ---------------------------------------------------------------------------

    private fun refreshConnector() {
        connectorStatus.text = when (PhoneConnector.state) {
            PhoneConnector.State.ONLINE -> "● On: ${PhoneConnector.detail}"
            PhoneConnector.State.CONNECTING -> "◌ Connecting: ${PhoneConnector.detail}"
            PhoneConnector.State.OFF -> "Off" + PhoneConnector.detail.let { if (it.isEmpty() || it == "off") "." else ": $it" }
        }
        connectorButton.text = if (PhoneConnector.isOn) "Turn off" else "Turn on"
    }

    private fun toggleConnector() {
        if (PhoneConnector.isOn) { PhoneConnector.stopConnector(); return }
        when {
            ClaudeSettings.phoneToken(a) == null -> a.toast("Make tokens first.")
            ClaudeSettings.relayUrl(a).isEmpty() -> a.toast("Paste your relay address first.")
            MacroService.capture == null -> a.toast("Allow screen capture first (the Setup tab).")
            InputService.instance == null -> a.toast("Turn on taps first (the Setup tab).")
            else -> PhoneConnector.start(a)
        }
        refreshConnector()
    }

    private fun newTokens() {
        if (PhoneConnector.isOn) { a.toast("Turn the connector off first."); return }
        ClaudeSettings.newTokens(a)
        a.toast("New tokens made. Put both in the relay, then update the connector address in claude.ai.")
    }

    private fun copyConnectorAddress() {
        val url = ClaudeSettings.relayUrl(a)
        val token = ClaudeSettings.connectorToken(a)
        if (url.isEmpty() || token == null) { a.toast("Paste the relay address and make tokens first."); return }
        copy("connector address", "$url/mcp/$token")
    }

    private fun copy(what: String, text: String?, sensitive: Boolean = true) {
        if (text == null) { a.toast("Make tokens first."); return }
        val cm = a.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText(what, text)
        if (sensitive && android.os.Build.VERSION.SDK_INT >= 33) {
            clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        }
        cm.setPrimaryClip(clip)
        a.toast("Copied the $what.")
    }

    // --- updates -----------------------------------------------------------------------------

    private fun checkForUpdates(automatic: Boolean) {
        if (BuildConfig.VERSION_NAME.contains("-dev")) {
            // test builds from CI: a release would always look newer and replace them
            // so it follows its own branch's CI builds instead (also by itself while the connector is idle)
            val branch = Updater.channel(a)
            updateInfo.text = "Development build (${BuildConfig.VERSION_NAME}) of $branch: it follows that branch's test builds, " +
                "not releases. It installs newer ones by itself while the connector is on and idle, or now with the button."
            updateButton.text = "Install the newest build"
            return
        }
        val prefs = a.getSharedPreferences("updates", android.content.Context.MODE_PRIVATE)
        if (automatic && System.currentTimeMillis() - prefs.getLong("last_check", 0) < 6 * 3600_000L) {
            if (updateInfo.text.isNullOrEmpty()) updateInfo.text = "Version ${BuildConfig.VERSION_NAME}."
            return
        }
        prefs.edit().putLong("last_check", System.currentTimeMillis()).apply()
        updateInfo.text = "Checking GitHub for updates…"
        Thread {
            val result = runCatching { Updater.checkNewer() }
            a.runOnUiThread {
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
        if (BuildConfig.VERSION_NAME.contains("-dev")) return updateDevBuild()
        val r = available ?: return checkForUpdates(automatic = false)
        if (!Updater.canInstall(a)) {
            updateInfo.text = "Allow Another Macro to install apps (\"Install unknown apps\"), come back and tap Update again."
            Updater.openInstallPermission(a)
            return
        }
        if (Runner.isRunning) {
            updateInfo.text = "Stop the running macro first."
            return
        }
        updateButton.isEnabled = false
        updateInfo.text = "Downloading ${r.version}…"
        Thread {
            val result = runCatching { Updater.downloadAndInstall(a, r) }
            a.runOnUiThread {
                updateButton.isEnabled = true
                result.onSuccess { updateInfo.text = "Installing ${r.version}: confirm with Update if Android asks." }
                    .onFailure { updateInfo.text = "Update failed: ${it.message}" }
            }
        }.start()
    }

    /** A dev build: install the newest CI build of the branch it follows, if newer. */
    private fun updateDevBuild() {
        if (!Updater.canInstall(a)) {
            updateInfo.text = "Allow Another Macro to install apps (\"Install unknown apps\"), come back and tap the button again."
            Updater.openInstallPermission(a)
            return
        }
        if (Runner.isRunning) {
            updateInfo.text = "Stop the running macro first."
            return
        }
        val branch = Updater.channel(a)
        updateButton.isEnabled = false
        updateInfo.text = "Checking the newest build of $branch…"
        Thread {
            val result = runCatching { Updater.update(a, branch) }
            a.runOnUiThread {
                updateButton.isEnabled = true
                result.onSuccess { updateInfo.text = it.replaceFirstChar { c -> c.uppercase() } + "." }
                    .onFailure { updateInfo.text = "Update failed: ${it.message}" }
            }
        }.start()
    }

    // --- views -------------------------------------------------------------------------------

    private fun switchRow(title: String, help: String, on: Boolean, changed: (Boolean) -> Unit): View = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            addView(Ui.text(a, title, 15f, bold = true))
            if (help.isNotBlank()) addView(Ui.text(a, help, 12.5f, Ui.MUTED))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(android.widget.Switch(a).apply {
            isChecked = on
            thumbTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Ui.ACCENT, Ui.MUTED))
            trackTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Ui.ACCENT_DIM, Ui.LINE))
            setOnCheckedChangeListener { _, v -> changed(v) }
        })
    }

    private fun field(hint: String) = EditText(a).apply {
        this.hint = hint
        setHintTextColor(Ui.MUTED)
        setTextColor(Ui.TEXT)
        setSingleLine()
        background = Ui.rounded(Ui.CARD_ALT, dp(12).toFloat())
        setPadding(dp(12), dp(10), dp(12), dp(10))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
    }

    private fun EditText.onChange(f: (String) -> Unit) = addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) { f(s?.toString().orEmpty()) }
    })
}
