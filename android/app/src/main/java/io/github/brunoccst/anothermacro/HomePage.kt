package io.github.brunoccst.anothermacro

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Home: what the app is, what's missing, how to use it, and quick links. */
class HomePage(private val a: MainActivity) {
    private fun dp(v: Int) = a.dp(v)
    private val setupCard: LinearLayout
    private val quickCard: LinearLayout
    private val quickText: TextView
    private val quickRun: TextView
    val view: View

    init {
        val column = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }

        column.addView(Ui.text(a, "Another Macro", 26f, bold = true))
        column.addView(Ui.text(a,
            "Automation for the mobile game Another Eden that runs on this phone: no PC and no root. " +
                "Macros read the game screen (screen capture) and tap for you (accessibility), " +
                "for the daily routine, Another Dungeon runs, farming and more.", 13.5f, Ui.MUTED).apply { setPadding(0, dp(4), 0, dp(12)) })
        column.addView(a.statusChips())

        // --- what's still missing before a macro can run --------------------------------
        setupCard = Ui.card(a, Ui.ACCENT_DIM).apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(14)
            addView(Ui.text(a, "Get started", 17f, bold = true))
            addView(Ui.text(a, "Macros need two things, once: taps (accessibility) and screen capture (again after every update).", 13f).apply { setPadding(0, dp(6), 0, dp(4)) })
            addView(Ui.button(a, "Set up in System", primary = true) { a.show(MainActivity.Page.SYSTEM) })
        }
        column.addView(setupCard)

        // --- the last macro, one tap away -------------------------------------------------
        quickText = Ui.text(a, "", 13f, Ui.MUTED).apply { setPadding(0, dp(4), 0, 0) }
        quickRun = Ui.button(a, "Run", primary = true) {
            val (name, params) = LastRun.load(a) ?: return@button
            a.run(name, params)
        }
        quickCard = Ui.card(a).apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(14)
            addView(Ui.text(a, "Last macro", 17f, bold = true))
            addView(quickText)
            addView(quickRun)
        }
        column.addView(quickCard)

        // --- how to use it -----------------------------------------------------------------
        column.addView(Ui.section(a, "How to use it"))
        column.addView(Ui.card(a).apply {
            step(this, "1", "Set up", "System → Setup: turn on taps and allow screen capture. Use ≡ at the top left to move between pages.")
            step(this, "2", "Pick your macros", "Macros: the tabs group them (Daily, Another Dungeon, Farming, Game). Switch on the ones you use: they show in the \"In use\" tab and on the floating buttons.")
            step(this, "3", "Run", "Tap a macro, set its options and Run. The game comes to the front and the macro plays; the log is in System.")
            step(this, "4", "Floating buttons", "Over the game: ▶ start, ❚❚ pause, ■ stop, ↻ restart, ≡ pick another macro, 💬 live log, 📷 capture a template.")
            step(this, "5", "Claude (optional)", "System → Claude: turn on the phone connector to let Claude play or write macros from your Claude chat.", last = true)
        })
        column.addView(Ui.button(a, "Open macros", primary = true) { a.show(MainActivity.Page.MACROS) })

        // --- quick links ---------------------------------------------------------------------
        column.addView(Ui.section(a, "Links"))
        column.addView(Ui.card(a).apply {
            setPadding(dp(6), dp(6), dp(6), dp(6))
            link(this, "GitHub project", "Code, issues and releases", MainActivity.REPO)
            link(this, "Writing macros", "The macro (.yaml) format and how macros work", "${MainActivity.REPO}/blob/main/docs/writing-scenarios.md")
            link(this, "Claude relay guide", "Set up the phone connector", ClaudeSettings.RELAY_GUIDE)
            link(this, "Releases", "What's new in each version", "${MainActivity.REPO}/releases")
            link(this, "Report a problem", "Open an issue on GitHub", "${MainActivity.REPO}/issues", last = true)
        })

        view = ScrollView(a).apply { setBackgroundColor(Ui.BG); addView(column) }
    }

    private fun step(card: LinearLayout, n: String, title: String, body: String, last: Boolean = false) {
        card.addView(LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Ui.text(a, n, 14f, Ui.ACCENT, bold = true).apply {
                gravity = Gravity.CENTER
                background = Ui.rounded(Ui.ACCENT_DIM, dp(13).toFloat())
                layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)).apply { rightMargin = dp(12) }
            })
            addView(LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                addView(Ui.text(a, title, 15f, bold = true))
                addView(Ui.text(a, body, 13f, Ui.MUTED))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        })
        if (!last) card.addView(Ui.divider(a))
    }

    private fun link(card: LinearLayout, title: String, help: String, url: String, last: Boolean = false) {
        card.addView(LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = Ui.pressable(a, Ui.CARD, 12)
            isClickable = true
            setOnClickListener { a.openUrl(url) }
            addView(LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                addView(Ui.text(a, title, 15f, Ui.ACCENT, bold = true))
                addView(Ui.text(a, help, 12.5f, Ui.MUTED))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(Ui.text(a, "↗", 16f, Ui.MUTED))
        })
        if (!last) card.addView(android.view.View(a).apply {
            setBackgroundColor(Ui.LINE)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { leftMargin = dp(12); rightMargin = dp(12) }
        })
    }

    fun refresh(taps: Boolean, capture: Boolean) {
        setupCard.visibility = if (taps && capture) View.GONE else View.VISIBLE
        val last = LastRun.load(a)
        quickCard.visibility = if (last == null) View.GONE else View.VISIBLE
        if (last != null) {
            val (name, params) = last
            quickText.text = if (params.isEmpty()) "$name, with its default options." else "$name: " + params.entries.joinToString(", ") { "${Ui.title(it.key).lowercase()} ${it.value}" }
            quickRun.text = if (Runner.isRunning) "Running: ${Runner.current}" else "▶  Run $name again"
            quickRun.isEnabled = !Runner.isRunning
        }
    }
}
