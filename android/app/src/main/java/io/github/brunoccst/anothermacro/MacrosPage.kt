package io.github.brunoccst.anothermacro

import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Macros: tabs ("In use", then one per group), a list of the tab's macros with a switch to
 * choose the ones you use, and, after tapping one, its options and Run.
 */
class MacrosPage(private val a: MainActivity) {
    private fun dp(v: Int) = a.dp(v)

    private var macros: List<Scenario> = emptyList()
    /** The tab shown: [IN_USE] or a group's id. */
    private var tab: String = a.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getString("tab", IN_USE) ?: IN_USE

    private val tabsRow = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(16), dp(12), dp(16), dp(4)) }
    private val listBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    private val listScreen: LinearLayout
    private val detailScreen: LinearLayout
    private val scroll: ScrollView
    private val userTemplates: TextView

    // the open macro
    private var scenario: Scenario? = null
    /** Each param's current value, read from its text box, pills or switch. */
    private val paramFields = LinkedHashMap<String, () -> String>()
    private val detailTitle = Ui.text(a, "", 20f, bold = true)
    private val detailIcon: ImageView = Ui.icon(a, R.drawable.ic_puzzle, 32)
    private val detailGroup = Ui.text(a, "", 12f, Ui.MUTED)
    private val detailDescription = Ui.text(a, "", 13.5f, Ui.MUTED).apply { setPadding(0, dp(8), 0, dp(6)) }
    private val paramsBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    private val runButton: TextView
    private val detailUse: android.widget.Switch

    val view: View

    init {
        // --- list: tabs, the tab's macros, your own macros --------------------------------
        listScreen = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            addView(HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(tabsRow) })
            addView(listBox.apply { setPadding(dp(16), dp(6), dp(16), 0) })
        }
        userTemplates = Ui.text(a, "", 13f).apply { setPadding(0, dp(6), 0, 0) }
        listScreen.addView(LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(32))
            addView(Ui.section(a, "Your macros and templates"))
            addView(Ui.card(a).apply {
                addView(Ui.text(a, "Macros are .yaml files (see macros/ in the GitHub project). Templates you capture with 📷 can be used in them by name.", 13f, Ui.MUTED))
                addView(userTemplates)
                addView(Ui.button(a, "Import macro (.yaml)") { a.pickMacroFile() })
            })
        })

        // --- detail: the open macro's options and Run ---------------------------------------
        runButton = Ui.button(a, "Run", primary = true) { scenario?.let { a.run(it.name, paramFields.mapValues { f -> f.value() }) } }
        detailUse = switch(false) { on -> scenario?.let { MacroChoice.setUsed(a, it.name, on) } }
        detailScreen = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(32))
            visibility = View.GONE
            addView(Ui.text(a, "‹  All macros", 15f, Ui.ACCENT, bold = true).apply {
                setPadding(dp(4), dp(10), dp(12), dp(10))
                isClickable = true
                setOnClickListener { back() }
            })
            addView(Ui.card(a).apply {
                addView(LinearLayout(a).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    addView(detailIcon)
                    addView(LinearLayout(a).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(detailTitle); addView(detailGroup)
                    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                })
                addView(detailDescription)
                addView(Ui.divider(a))
                addView(LinearLayout(a).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    addView(LinearLayout(a).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(Ui.text(a, "In use", 15f, bold = true))
                        addView(Ui.text(a, "Show it in the \"In use\" tab and on the floating buttons.", 12.5f, Ui.MUTED))
                    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    addView(detailUse)
                })
            })
            addView(Ui.section(a, "Options"))
            addView(Ui.card(a).apply { addView(paramsBox) })
            addView(runButton)
            addView(Ui.button(a, "Stop", color = Ui.BAD) { a.startService(MacroService.intent(a, MacroService.ACTION_STOP_RUN)) })
        }

        scroll = ScrollView(a).apply {
            setBackgroundColor(Ui.BG)
            addView(LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                addView(listScreen); addView(detailScreen)
            })
        }
        view = scroll
        reload()
    }

    /** Load the macros again (after an import) and redraw the tabs and list. */
    fun reload() {
        macros = Scenarios.all(a)
        if (tab != IN_USE && Groups.sort(macros).none { it.first.id == tab }) tab = IN_USE
        drawTabs()
        drawList()
    }

    fun onResume() {
        val mine = TemplateCapture.list(a)
        userTemplates.text = if (mine.isEmpty()) "Captured templates: none yet." else "Captured templates: ${mine.joinToString()}"
        if (detailScreen.visibility != View.VISIBLE) drawList()
    }

    /** Back from the open macro to the list; false when the list is already shown. */
    fun back(): Boolean {
        if (detailScreen.visibility != View.VISIBLE) return false
        detailScreen.visibility = View.GONE
        listScreen.visibility = View.VISIBLE
        drawList()
        scroll.post { scroll.scrollTo(0, 0) }
        return true
    }

    /** Open the macro named [name], if there is one. */
    fun open(name: String) { macros.firstOrNull { it.name == name }?.let { showMacro(it) } }

    private fun drawTabs() {
        tabsRow.removeAllViews()
        val tabs = listOf(IN_USE to "In use") + Groups.sort(macros).map { it.first.id to it.first.title }
        for ((id, title) in tabs) {
            val sel = id == tab
            tabsRow.addView(Ui.text(a, title, 14f, if (sel) Color.parseColor("#0B1A22") else Ui.TEXT, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(9), dp(16), dp(9))
                background = Ui.pressable(a, if (sel) Ui.ACCENT else Ui.CARD_ALT, 18)
                isClickable = true
                setOnClickListener {
                    tab = id
                    a.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().putString("tab", id).apply()
                    drawTabs(); drawList()
                }
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) }
            })
        }
    }

    private fun drawList() {
        listBox.removeAllViews()
        val notUsed = MacroChoice.notUsed(a)
        val sections: List<Pair<Groups.Group, List<Scenario>>> =
            if (tab == IN_USE) Groups.sort(macros.filter { it.name !in notUsed })
            else Groups.sort(macros).filter { it.first.id == tab }
        if (tab == IN_USE) {
            listBox.addView(Ui.text(a, "The macros you use. Pick them in the other tabs with the switches.", 13f, Ui.MUTED).apply { setPadding(dp(4), dp(8), 0, 0) })
            if (sections.isEmpty()) listBox.addView(Ui.card(a).apply {
                (layoutParams as LinearLayout.LayoutParams).topMargin = dp(12)
                addView(Ui.text(a, "None yet: open a tab and switch on the macros you use.", 14f))
            })
        }
        for ((group, list) in sections) {
            listBox.addView(Ui.withIcon(a, group.iconRes, Ui.section(a, group.title).apply { setPadding(0, dp(14), 0, dp(8)) }, 18).apply { setPadding(dp(6), 0, 0, 0) })
            val card = Ui.card(a).apply { setPadding(dp(6), dp(6), dp(6), dp(6)) }
            for ((i, m) in list.withIndex()) {
                if (i > 0) card.addView(View(a).apply {
                    setBackgroundColor(Ui.LINE)
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { leftMargin = dp(12); rightMargin = dp(12) }
                })
                card.addView(macroRow(m, m.name !in notUsed))
            }
            listBox.addView(card)
        }
    }

    /** One macro: icon, name and description (tap to open it), and its "in use" switch. */
    private fun macroRow(m: Scenario, used: Boolean): View = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(10), dp(8), dp(10))
        background = Ui.pressable(a, Ui.CARD, 12)
        isClickable = true
        val last = LastRun.load(a)?.first
        addView(Ui.icon(a, Groups.icon(m), 30).apply { (layoutParams as LinearLayout.LayoutParams).rightMargin = dp(14) })
        addView(LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            addView(Ui.text(a, if (m.name == last) "${m.name}  ·  last run" else m.name, 15f, bold = true))
            if (m.description.isNotBlank()) addView(Ui.text(a, m.description, 12.5f, Ui.MUTED).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(switch(used) { on ->
            MacroChoice.setUsed(a, m.name, on)
            // in the "In use" tab a switched-off macro leaves the list
            if (tab == IN_USE && !on) post { drawList() }
        })
        setOnClickListener { showMacro(m) }
    }

    private fun switch(on: Boolean, changed: (Boolean) -> Unit) = android.widget.Switch(a).apply {
        isChecked = on
        thumbTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Ui.ACCENT, Ui.MUTED))
        trackTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Ui.ACCENT_DIM, Ui.LINE))
        setOnCheckedChangeListener { _, v -> changed(v) }
    }

    private fun switchRow(title: String, help: String, on: Boolean, changed: (Boolean) -> Unit): View = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            addView(Ui.text(a, title, 15f, bold = true))
            if (help.isNotBlank()) addView(Ui.text(a, help, 12.5f, Ui.MUTED))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(switch(on, changed))
    }

    /** Open [s]: its description and its options (prefilled with the last values used). */
    private fun showMacro(s: Scenario) {
        scenario = s
        listScreen.visibility = View.GONE
        detailScreen.visibility = View.VISIBLE
        scroll.post { scroll.scrollTo(0, 0) }
        detailTitle.text = s.name
        detailIcon.setImageResource(Groups.icon(s))
        detailGroup.text = Groups.of(s.group).title
        detailDescription.text = s.description
        detailDescription.visibility = if (s.description.isBlank()) View.GONE else View.VISIBLE
        detailUse.setOnCheckedChangeListener(null)
        detailUse.isChecked = MacroChoice.isUsed(a, s.name)
        detailUse.setOnCheckedChangeListener { _, on -> MacroChoice.setUsed(a, s.name, on) }
        runButton.text = "▶  Run ${s.name}"
        paramsBox.removeAllViews()
        paramFields.clear()
        if (s.params.isEmpty()) paramsBox.addView(Ui.text(a, "This macro has no options.", 13f, Ui.MUTED))
        val last = LastRun.load(a)?.takeIf { it.first == s.name }?.second
        // params shown only while a switch is on: their views, by that switch
        val dependents = HashMap<String, MutableList<View>>()
        val switches = HashMap<String, Boolean>()
        var first = true
        for ((key, p) in s.params) {
            val value = last?.get(key) ?: p.default
            val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            paramsBox.addView(box)
            p.showIf?.let { dependents.getOrPut(it) { ArrayList() } += box; box.visibility = if (switches[it] == true) View.VISIBLE else View.GONE }
            if (!first) box.addView(Ui.divider(a))
            first = false
            if (p.isBool) {
                var cur = FlowScenario.typed(value) == true
                switches[key] = cur
                box.addView(switchRow(Ui.title(key), p.help, cur) { on ->
                    cur = on
                    dependents[key]?.forEach { it.visibility = if (on) View.VISIBLE else View.GONE }
                })
                paramFields[key] = { cur.toString() }
                continue
            }
            box.addView(Ui.text(a, Ui.title(key), 15f, bold = true))
            if (p.help.isNotBlank()) box.addView(Ui.text(a, p.help, 12.5f, Ui.MUTED))
            if (p.choices.isNotEmpty()) {
                val options = if (value in p.choices) p.choices else p.choices + value
                // choices as a row of pills: tap one to pick it
                var cur = value
                val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
                val pills = ArrayList<TextView>()
                fun paint() = pills.forEach { t ->
                    val sel = t.tag == cur
                    t.setTextColor(if (sel) Color.parseColor("#0B1A22") else Ui.TEXT)
                    t.background = Ui.pressable(a, if (sel) Ui.ACCENT else Ui.CARD_ALT, 16)
                }
                for (o in options) {
                    val t = Ui.text(a, o, 14f).apply {
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
                box.addView(HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(row) })
                paramFields[key] = { cur }
                continue
            }
            val field = EditText(a).apply {
                setText(value)
                inputType = InputType.TYPE_CLASS_TEXT
                setSingleLine()
                setTextColor(Ui.TEXT)
                background = Ui.rounded(Ui.CARD_ALT, dp(12).toFloat())
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
            }
            paramFields[key] = { field.text.toString() }
            box.addView(field)
        }
    }

    companion object {
        private const val PREFS = "macros_page"
        private const val IN_USE = "in_use"
    }
}
