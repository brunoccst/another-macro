package io.github.brunoccst.anothermacro

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.anthropic.client.okhttp.AnthropicOkHttpClient

/**
 * The Claude screen: the user's own API key, model and limits, and a task for Claude to
 * play. Claude then runs over the game like a macro (floating Stop/Pause work), and its
 * progress notes show on the live log and in the log below.
 */
class ClaudeActivity : Activity() {
    private fun dp(v: Int) = Ui.dp(this, v)
    private lateinit var keyStatus: TextView
    private lateinit var keyField: EditText
    private lateinit var taskField: EditText
    private lateinit var status: TextView
    private lateinit var log: TextView
    private val logListener: (String) -> Unit = { text -> if (::log.isInitialized) log.text = text.lines().takeLast(40).joinToString("\n") }
    private val agentListener: () -> Unit = { runOnUiThread { refresh() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(32))
        }
        column.addView(Ui.text(this, "Claude", 26f, bold = true))
        column.addView(Ui.text(this, "Tell Claude what to do in the game, or ask it to write a macro for you. It plays by looking at the screen and tapping, with your own Claude API key.", 13f, Ui.MUTED).apply { setPadding(0, dp(2), 0, dp(8)) })

        // --- task ------------------------------------------------------------------
        column.addView(Ui.section(this, "Task"))
        column.addView(Ui.card(this).apply {
            taskField = field("e.g. \"Finish today's cat scratchbook\" or \"Make a macro that farms battles on the current map until 30 wins\"", multiLine = true).apply { setText(ClaudeSettings.lastTask(this@ClaudeActivity)) }
            addView(taskField)
            status = Ui.text(this@ClaudeActivity, "", 13f, Ui.MUTED).apply { setPadding(0, dp(8), 0, 0) }
            addView(status)
            addView(Ui.button(this@ClaudeActivity, "▶  Start Claude", primary = true) { start() })
            addView(Ui.button(this@ClaudeActivity, "Stop", color = Ui.BAD) { startService(MacroService.intent(this@ClaudeActivity, MacroService.ACTION_STOP_RUN)) })
        })

        // --- key and limits ---------------------------------------------------------
        column.addView(Ui.section(this, "Your Claude API key"))
        column.addView(Ui.card(this).apply {
            addView(Ui.text(this@ClaudeActivity,
                "Create a key at platform.claude.com (Claude Console → API keys). Usage is billed to that account, separately from a Claude Pro/Max subscription, which apps like this one can't use. Setting a monthly spend limit there is a good idea.", 13f, Ui.MUTED))
            keyStatus = Ui.text(this@ClaudeActivity, "", 13f).apply { setPadding(0, dp(6), 0, 0) }
            addView(keyStatus)
            keyField = field("sk-ant-…", multiLine = false).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
            addView(keyField)
            addView(Ui.button(this@ClaudeActivity, "Save key", primary = true) { saveKey() })
            addView(Ui.button(this@ClaudeActivity, "Test key") { testKey() })
            addView(Ui.button(this@ClaudeActivity, "Remove key", color = Ui.MUTED) { ClaudeSettings.clearApiKey(this@ClaudeActivity); refresh() })
            addView(Ui.button(this@ClaudeActivity, "Open Claude Console") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://platform.claude.com/settings/keys"))) })
            addView(Ui.text(this@ClaudeActivity, "The key stays on this phone, encrypted with a key from Android's Keystore, and is only sent to api.anthropic.com.", 12f, Ui.MUTED).apply { setPadding(0, dp(6), 0, 0) })
        })

        column.addView(Ui.section(this, "Model and limits"))
        column.addView(Ui.card(this).apply {
            addView(Ui.text(this@ClaudeActivity, "Model", 15f, bold = true))
            addView(Ui.text(this@ClaudeActivity, "Opus reads busy screens best (about 2 US cents a step); Sonnet costs about half.", 12.5f, Ui.MUTED))
            addView(pills(ClaudeSettings.MODELS.map { it.id to it.label }, ClaudeSettings.model(this@ClaudeActivity).id) { ClaudeSettings.setModel(this@ClaudeActivity, it) })
            addView(Ui.divider(this@ClaudeActivity))
            addView(Ui.text(this@ClaudeActivity, "Effort", 15f, bold = true))
            addView(Ui.text(this@ClaudeActivity, "How hard Claude thinks before each step. Medium suits most tasks.", 12.5f, Ui.MUTED))
            addView(pills(ClaudeSettings.EFFORTS.map { it to it }, ClaudeSettings.effort(this@ClaudeActivity)) { ClaudeSettings.setEffort(this@ClaudeActivity, it) })
            addView(Ui.divider(this@ClaudeActivity))
            addView(Ui.text(this@ClaudeActivity, "Step cap per task", 15f, bold = true))
            addView(field("150", multiLine = false).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setText(ClaudeSettings.maxSteps(this@ClaudeActivity).toString())
                onChange { it.toIntOrNull()?.let { n -> ClaudeSettings.setMaxSteps(this@ClaudeActivity, n) } }
            })
            addView(Ui.text(this@ClaudeActivity, "Cost cap per task (US$)", 15f, bold = true).apply { setPadding(0, dp(8), 0, 0) })
            addView(field("3", multiLine = false).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                setText(ClaudeSettings.maxCostUsd(this@ClaudeActivity).toString())
                onChange { it.toDoubleOrNull()?.let { v -> ClaudeSettings.setMaxCostUsd(this@ClaudeActivity, v) } }
            })
        })

        column.addView(Ui.section(this, "Safety"))
        column.addView(Ui.card(this).apply {
            addView(Ui.text(this@ClaudeActivity,
                "• Claude can only tap inside Another Eden.\n" +
                    "• It never buys anything: purchase screens are refused by the app, not just by Claude.\n" +
                    "• Taps on things that can't be undone (sell, discard, release, exchange…) or that mention Chronos Stones ask you first.\n" +
                    "• Each task stops at the step and cost caps. The floating ■ stops it at any time.\n" +
                    "• Screenshots are sent to Anthropic's API only while a task runs, and aren't saved on the phone.", 13f))
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
        ClaudeAgent.listeners += agentListener
        log.text = MacroLog.text().lines().takeLast(40).joinToString("\n")
        refresh()
    }

    override fun onPause() {
        MacroLog.unlisten(logListener)
        ClaudeAgent.listeners -= agentListener
        super.onPause()
    }

    private fun refresh() {
        keyStatus.text = if (ClaudeSettings.hasKey(this)) "Saved key: ${ClaudeSettings.keyHint(this)}" else "No key saved yet."
        val cost = "${"$%.2f".format(ClaudeAgent.costUsd)} in ${ClaudeAgent.steps} steps"
        status.text = when {
            ClaudeAgent.isRunning -> "Claude is playing: $cost so far."
            ClaudeAgent.lastResult.isNotEmpty() -> "Last task: ${ClaudeAgent.lastResult} ($cost)"
            else -> ""
        }
    }

    private fun start() {
        val task = taskField.text.toString().trim()
        when {
            task.isEmpty() -> toast("Write a task first.")
            !ClaudeSettings.hasKey(this) -> toast("Save your Claude API key first.")
            MacroService.capture == null -> toast("Allow screen capture first (main screen, step 2).")
            InputService.instance == null -> toast("Turn on taps first (main screen, step 1).")
            Runner.isRunning -> toast("A macro or Claude is already running.")
            else -> {
                ClaudeSettings.setLastTask(this, task)
                startService(MacroService.intent(this, MacroService.ACTION_CLAUDE).putExtra(MacroService.EXTRA_TASK, task))
                moveTaskToBack(true)
            }
        }
    }

    private fun saveKey() {
        val k = keyField.text.toString().trim()
        if (!k.startsWith("sk-ant-")) { toast("That doesn't look like a Claude API key (sk-ant-…)."); return }
        ClaudeSettings.setApiKey(this, k)
        keyField.setText("")
        refresh()
        toast("Key saved.")
    }

    /** Looks up the chosen model with the key: free, and shows whether the key works. */
    private fun testKey() {
        val key = ClaudeSettings.apiKey(this) ?: run { toast("Save a key first."); return }
        val model = ClaudeSettings.model(this)
        keyStatus.text = "Testing…"
        Thread {
            val r = runCatching {
                AnthropicOkHttpClient.builder().apiKey(key).build().models().retrieve(model.id).displayName()
            }
            runOnUiThread {
                keyStatus.text = r.fold(
                    { "Key works: ${model.label} ($it) is available." },
                    { "Key test failed: ${it.message?.take(200)}" },
                )
            }
        }.start()
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

    /** A row of pills: tap one to pick it. */
    private fun pills(options: List<Pair<String, String>>, current: String, picked: (String) -> Unit): View {
        var cur = current
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, dp(6)) }
        val views = ArrayList<TextView>()
        fun paint() = views.forEach { t ->
            val sel = t.tag == cur
            t.setTextColor(if (sel) Color.parseColor("#0B1A22") else Ui.TEXT)
            t.background = Ui.pressable(this, if (sel) Ui.ACCENT else Ui.CARD_ALT, 16)
        }
        for ((id, label) in options) {
            val t = Ui.text(this, label, 14f).apply {
                tag = id
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(8), dp(14), dp(8))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) }
                setOnClickListener { cur = id; picked(id); paint() }
            }
            views += t; row.addView(t)
        }
        paint()
        return row
    }
}
