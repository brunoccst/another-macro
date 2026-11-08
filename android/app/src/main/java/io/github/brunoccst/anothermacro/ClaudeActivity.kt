package io.github.brunoccst.anothermacro

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The Claude screen: the phone connector (relay address, tokens, on/off), what Claude may
 * and may not do, and the log.
 */
class ClaudeActivity : Activity() {
    companion object {
        const val RELAY_GUIDE = "https://github.com/brunoccst/another-macro/blob/main/relay/README.md"
    }
    private fun dp(v: Int) = Ui.dp(this, v)
    private lateinit var log: TextView
    private lateinit var connectorStatus: TextView
    private lateinit var connectorButton: TextView
    private val logListener: (String) -> Unit = { text -> if (::log.isInitialized) log.text = text.lines().takeLast(40).joinToString("\n") }
    private val connectorListener: () -> Unit = { runOnUiThread { refresh() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(32))
        }
        column.addView(Ui.text(this, "Claude", 26f, bold = true))
        column.addView(Ui.text(this, "Let Claude play or write macros for you from your Claude chat (Claude app, claude.ai): it sees the screen and taps through this app, on your own Claude plan.", 13f, Ui.MUTED).apply { setPadding(0, dp(2), 0, dp(8)) })

        // --- phone connector -----------------------------------------------------------
        column.addView(Ui.section(this, "Phone connector"))
        column.addView(Ui.card(this).apply {
            addView(Ui.text(this@ClaudeActivity,
                "Chat with Claude in the Claude app or claude.ai, on your own plan, and let it see and control the game through this app. No API key is needed. It reaches the phone through your relay, a small free Cloudflare Worker on your account.", 13f, Ui.MUTED))
            connectorStatus = Ui.text(this@ClaudeActivity, "", 13f).apply { setPadding(0, dp(6), 0, 0) }
            addView(connectorStatus)
            connectorButton = Ui.button(this@ClaudeActivity, "Turn on", primary = true) { toggleConnector() }
            addView(connectorButton)
            addView(Ui.text(this@ClaudeActivity, "Relay address", 15f, bold = true).apply { setPadding(0, dp(10), 0, 0) })
            addView(field("https://another-macro.<you>.workers.dev", multiLine = false).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                setText(ClaudeSettings.relayUrl(this@ClaudeActivity))
                onChange { ClaudeSettings.setRelayUrl(this@ClaudeActivity, it) }
            })
            addView(Ui.text(this@ClaudeActivity,
                "Setup, once: 1) Make tokens. 2) Deploy the relay and paste the two tokens into it (guide below). 3) Paste the relay address above. 4) In claude.ai → Settings → Connectors → Add custom connector, paste the connector address.", 12.5f, Ui.MUTED).apply { setPadding(0, dp(6), 0, 0) })
            addView(Ui.button(this@ClaudeActivity, "Make new tokens") { newTokens() })
            addView(Ui.button(this@ClaudeActivity, "Copy phone token (for the relay)") { copy("phone token", ClaudeSettings.phoneToken(this@ClaudeActivity)) })
            addView(Ui.button(this@ClaudeActivity, "Copy connector token (for the relay)") { copy("connector token", ClaudeSettings.connectorToken(this@ClaudeActivity)) })
            addView(Ui.button(this@ClaudeActivity, "Copy connector address (for claude.ai)") { copyConnectorAddress() })
            addView(Ui.button(this@ClaudeActivity, "Relay setup guide") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RELAY_GUIDE))) })
            addView(Ui.text(this@ClaudeActivity,
                "The connector address holds the secret token: keep it private. Making new tokens cuts off the old ones once the relay has the new pair. The connector turns itself off after 30 minutes without use, and the floating ■ stops what Claude is doing.", 12f, Ui.MUTED).apply { setPadding(0, dp(6), 0, 0) })
        })

        column.addView(Ui.section(this, "Safety"))
        column.addView(Ui.card(this).apply {
            addView(Ui.text(this@ClaudeActivity,
                "• Claude can only tap inside Another Eden.\n" +
                    "• It never buys anything: purchase screens are refused by the app, not just by Claude.\n" +
                    "• Taps on things that can't be undone (sell, discard, release, exchange…) or that mention Chronos Stones ask you first, here on the phone.\n" +
                    "• The connector is off until you turn it on, and turns itself off after 30 minutes without use. The floating ■ stops what Claude is doing.\n" +
                    "• Screenshots go through your relay to your Claude chat only while Claude uses the phone, and aren't saved on the phone.", 13f))
        })

        column.addView(Ui.section(this, "Log"))
        column.addView(Ui.card(this, Color.parseColor("#0B0E12")).apply {
            log = TextView(this@ClaudeActivity).apply {
                typeface = Typeface.MONOSPACE
                textSize = 11f
                setTextColor(Color.parseColor("#B8C2CC"))
                setTextIsSelectable(true)
            }
            addView(log)
        })
        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BG); addView(column) })
    }

    override fun onResume() {
        super.onResume()
        MacroLog.listen(logListener)
        PhoneConnector.listeners += connectorListener
        log.text = MacroLog.text().lines().takeLast(40).joinToString("\n")
        refresh()
    }

    override fun onPause() {
        MacroLog.unlisten(logListener)
        PhoneConnector.listeners -= connectorListener
        super.onPause()
    }

    private fun refresh() {
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
            ClaudeSettings.phoneToken(this) == null -> toast("Make tokens first.")
            ClaudeSettings.relayUrl(this).isEmpty() -> toast("Paste your relay address first.")
            MacroService.capture == null -> toast("Allow screen capture first (main screen, step 2).")
            InputService.instance == null -> toast("Turn on taps first (main screen, step 1).")
            else -> PhoneConnector.start(this)
        }
        refresh()
    }

    private fun newTokens() {
        if (PhoneConnector.isOn) { toast("Turn the connector off first."); return }
        ClaudeSettings.newTokens(this)
        toast("New tokens made. Put both in the relay, then update the connector address in claude.ai.")
    }

    private fun copyConnectorAddress() {
        val url = ClaudeSettings.relayUrl(this)
        val token = ClaudeSettings.connectorToken(this)
        if (url.isEmpty() || token == null) { toast("Paste the relay address and make tokens first."); return }
        copy("connector address", "$url/mcp/$token")
    }

    private fun copy(what: String, text: String?) {
        if (text == null) { toast("Make tokens first."); return }
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText(what, text)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        }
        cm.setPrimaryClip(clip)
        toast("Copied the $what.")
    }

    private fun toast(s: String) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show()

    private fun field(hint: String, multiLine: Boolean) = EditText(this).apply {
        this.hint = hint
        setHintTextColor(Ui.MUTED)
        setTextColor(Ui.TEXT)
        if (multiLine) { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; minLines = 3; gravity = Gravity.TOP } else setSingleLine()
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
