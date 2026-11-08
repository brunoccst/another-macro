package io.github.brunoccst.anothermacro

import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Macros: a search box, tabs ("In use", then one per group), the tab's macros with a switch to
 * choose the ones you use, and the macro library (downloaded from the GitHub project). Tapping a
 * macro opens it: what it does (as a diagram), its options and "Select this macro". Macros are
 * started, paused and stopped from the floating buttons over the game, not from here.
 */
class MacrosPage(private val a: MainActivity) {
    private fun dp(v: Int) = a.dp(v)

    private var macros: List<Scenario> = emptyList()
    /** The tab shown: [IN_USE] or a group's id. */
    private var tab: String = a.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getString("tab", IN_USE) ?: IN_USE
    private var query = ""

    private val search: EditText
    private val tabsRow = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(16), dp(6), dp(16), dp(4)) }
    private val tabsScroll = HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(tabsRow) }
    private val listBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    private val listScreen: LinearLayout
    private val detailScreen: LinearLayout
    private val scroll: ScrollView
    private val libraryInfo = Ui.text(a, "", 13f, Ui.MUTED)
    private val libraryButton: TextView

    // the open macro, and the ones opened before it (a macro's diagram opens the macros it runs)
    private var scenario: Scenario? = null
    private val opened = ArrayList<String>()
    /** Each param's current value, read from its text box, pills or switch. */
    private val paramFields = LinkedHashMap<String, () -> String>()
    private val detailTitle = Ui.text(a, "", 20f, bold = true)
    private val detailIcon: ImageView = Ui.icon(a, R.drawable.ic_puzzle, 32)
    private val detailGroup = Ui.text(a, "", 12f, Ui.MUTED)
    private val detailDescription = Ui.text(a, "", 13.5f, Ui.MUTED).apply { setPadding(0, dp(8), 0, dp(6)) }
    private val detailUses = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    private val paramsBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    private val selectButton: TextView
    private val customCard: LinearLayout
    private val selectHint = Ui.text(a, "", 12.5f, Ui.MUTED).apply { setPadding(dp(6), dp(6), dp(6), 0) }
    private val diagramBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    private val diagramCard: LinearLayout
    private val diagramButton: TextView
    private val detailUse: android.widget.Switch
    private val libraryListener: () -> Unit = { a.runOnUiThread { reload() } }

    val view: View

    init {
        // --- list: search, tabs, the tab's macros, the library --------------------------------
        search = EditText(a).apply {
            hint = "Search macros"
            setHintTextColor(Ui.MUTED)
            setTextColor(Ui.TEXT)
            textSize = 15f
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            background = null
            setPadding(dp(4), dp(10), dp(4), dp(10))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    query = s?.toString()?.trim().orEmpty()
                    tabsScroll.visibility = if (query.isEmpty()) View.VISIBLE else View.GONE
                    drawList()
                }
            })
        }
        val searchBar = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(Ui.CARD, dp(24).toFloat())
            setPadding(dp(14), 0, dp(8), 0)
            addView(Ui.icon(a, R.drawable.ic_search, 20))
            addView(search, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(Ui.text(a, "✕", 15f, Ui.MUTED).apply {
                setPadding(dp(10), dp(8), dp(10), dp(8))
                isClickable = true
                setOnClickListener { search.setText("") }
            })
        }
        libraryButton = Ui.button(a, "Check for macro updates") { updateLibrary(force = true) }
        listScreen = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            addView(searchBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = dp(16); rightMargin = dp(16); topMargin = dp(12)
            })
            addView(tabsScroll)
            addView(listBox.apply { setPadding(dp(16), dp(6), dp(16), 0) })
            addView(LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), 0, dp(16), dp(32))
                addView(Ui.section(a, "Macro library"))
                addView(Ui.card(a).apply {
                    addView(Ui.text(a, "The macros come from the GitHub project and update by themselves, without updating the app.", 13f, Ui.MUTED))
                    addView(libraryInfo.apply { setPadding(0, dp(6), 0, 0) })
                    addView(libraryButton)
                })
            })
        }

        // --- detail: what the open macro does, its options, Select ---------------------------
        selectButton = Ui.button(a, "Select this macro", primary = true) { select() }
        detailUse = switch(false) { on -> scenario?.let { MacroChoice.setUsed(a, it.name, on) } }
        diagramButton = Ui.text(a, "Show", 13.5f, Ui.ACCENT, bold = true).apply {
            setPadding(dp(12), dp(18), dp(6), dp(8))
            isClickable = true
            setOnClickListener { toggleDiagram() }
        }
        diagramCard = Ui.card(a).apply { addView(diagramBox); visibility = View.GONE }
        customCard = Ui.card(a).apply {
            visibility = View.GONE
            addView(Ui.text(a, "Your own macro, kept only on this phone.", 13f, Ui.MUTED))
            addView(Ui.button(a, "Export the file") { exportCustom() })
            addView(Ui.button(a, "Delete this macro") { deleteCustom() })
        }
        detailScreen = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(32))
            visibility = View.GONE
            addView(Ui.text(a, "‹  Back", 15f, Ui.ACCENT, bold = true).apply {
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
                addView(detailUses)
                addView(Ui.divider(a))
                addView(LinearLayout(a).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    addView(LinearLayout(a).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(Ui.text(a, "In use", 15f, bold = true))
                        addView(Ui.text(a, "Show it in the \"In use\" tab (here and on the floating buttons).", 12.5f, Ui.MUTED))
                    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    addView(detailUse)
                })
            })
            addView(Ui.section(a, "Options"))
            addView(Ui.card(a).apply { addView(paramsBox) })
            addView(selectButton)
            addView(selectHint)
            addView(LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(Ui.section(a, "How it works"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(diagramButton)
            })
            addView(diagramCard)
            addView(Ui.section(a, "Custom macro"))
            addView(customCard)
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

    /** Load the macros again (after a library update) and redraw the tabs and list. */
    fun reload() {
        macros = Scenarios.all(a)
        if (tab != IN_USE && Groups.sort(macros).none { it.first.id == tab }) tab = IN_USE
        drawTabs()
        drawList()
        refreshLibrary()
        // the open macro may have changed (or gone)
        scenario?.let { s -> if (detailScreen.visibility == View.VISIBLE) macros.firstOrNull { it.name == s.name }?.let { showMacro(it) } ?: back() }
    }

    fun onResume() {
        MacroLibrary.listeners += libraryListener
        if (detailScreen.visibility != View.VISIBLE) drawList()
        updateLibrary(force = !MacroLibrary.isInstalled(a))
    }

    fun onPause() { MacroLibrary.listeners -= libraryListener }

    /** Back to the macro opened before, or to the list; false when the list is already shown. */
    fun back(): Boolean {
        if (detailScreen.visibility != View.VISIBLE) return false
        opened.removeLastOrNull()
        val prev = opened.removeLastOrNull()?.let { n -> macros.firstOrNull { it.name == n } }
        if (prev != null) { showMacro(prev); return true }
        scenario = null
        detailScreen.visibility = View.GONE
        listScreen.visibility = View.VISIBLE
        drawList()
        scroll.post { scroll.scrollTo(0, 0) }
        return true
    }

    /** Open the macro named [name], if there is one. */
    fun open(name: String) { macros.firstOrNull { it.name == name }?.let { showMacro(it) } }

    // --- the library ------------------------------------------------------------------------

    private fun updateLibrary(force: Boolean) {
        MacroLibrary.updateAsync(a, force) { msg -> a.runOnUiThread { refreshLibrary(msg) } }
        refreshLibrary()
    }

    private fun refreshLibrary(last: String = "") {
        val inst = MacroLibrary.installed(a)
        libraryInfo.text = listOf(
            if (inst == null) "No macros downloaded yet: the phone needs internet once."
            else "Installed: ${macros.count { !(it is FlowScenario && it.custom) }} macros from ${inst.branch}@${inst.commit.take(7)}, ${inst.built}.",
            MacroLibrary.state, last,
        ).filter { it.isNotBlank() }.distinct().joinToString("\n")
    }

    // --- the list ---------------------------------------------------------------------------

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
        if (macros.isEmpty()) {
            listBox.addView(Ui.card(a).apply {
                (layoutParams as LinearLayout.LayoutParams).topMargin = dp(12)
                addView(Ui.text(a, if (MacroLibrary.isInstalled(a)) "No macros." else "Downloading the macros…", 14f))
            })
            return
        }
        val sections: List<Pair<Groups.Group, List<Scenario>>> = when {
            query.isNotEmpty() -> Groups.sort(macros.filter { matches(it, query) })
            tab == IN_USE -> Groups.sort(macros.filter { it.name !in notUsed })
            else -> Groups.sort(macros).filter { it.first.id == tab }
        }
        if (query.isNotEmpty() && sections.isEmpty()) listBox.addView(Ui.card(a).apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(12)
            addView(Ui.text(a, "No macro matches \"$query\".", 14f))
        })
        if (query.isEmpty() && tab == IN_USE) {
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
        val selected = LastRun.load(a)?.first
        addView(Ui.icon(a, Groups.icon(m), 30).apply { (layoutParams as LinearLayout.LayoutParams).rightMargin = dp(14) })
        addView(LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            addView(Ui.text(a, if (m.name == selected) "${m.name}  ·  selected" else m.name, 15f, bold = true))
            if (m.description.isNotBlank()) addView(Ui.text(a, m.description, 12.5f, Ui.MUTED).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(switch(used) { on ->
            MacroChoice.setUsed(a, m.name, on)
            // in the "In use" tab a switched-off macro leaves the list
            if (tab == IN_USE && query.isEmpty() && !on) post { drawList() }
        })
        setOnClickListener { opened.clear(); showMacro(m) }
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

    // --- custom macros: export, delete ---------------------------------------------------------

    private fun customFile(): java.io.File? {
        val s = scenario as? FlowScenario ?: return null
        return java.io.File(Scenarios.userDir(a), s.source).takeIf { s.custom && it.isFile }
    }

    /** Save the open custom macro's YAML to Downloads (Another Macro folder), then offer to share it. */
    private fun exportCustom() {
        val f = customFile() ?: return a.toast("This macro has no file on the phone.")
        val text = f.readText()
        val saved = runCatching { exportToDownloads(f.name, text) }
        saved.onSuccess { a.toast("Saved to Downloads/Another Macro/${f.name}.") }
            .onFailure { MacroLog.e("export of ${f.name} failed: ${it.message}") }
        a.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_SUBJECT, f.name)
            putExtra(android.content.Intent.EXTRA_TEXT, text)
        }, if (saved.isSuccess) "Saved to Downloads. Also share ${f.name}" else "Share ${f.name}"))
    }

    private fun exportToDownloads(name: String, text: String) {
        if (android.os.Build.VERSION.SDK_INT < 29) throw IllegalStateException("Downloads export needs Android 10+")
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/x-yaml")
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/Another Macro")
        }
        val resolver = a.contentResolver
        val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("could not create the file")
        resolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } ?: throw IllegalStateException("could not write the file")
    }

    /** Delete the open custom macro, after asking. Library macros can't be deleted here. */
    private fun deleteCustom() {
        val s = scenario ?: return
        val f = customFile() ?: return a.toast("This macro has no file on the phone.")
        if (Runner.isRunning) { a.toast("Stop the running macro first (■ on the floating buttons)."); return }
        android.app.AlertDialog.Builder(a)
            .setTitle("Delete ${s.name}?")
            .setMessage("This removes your custom macro from the phone. Export it first if you want to keep a copy.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                if (!f.delete()) { a.toast("Could not delete ${f.name}."); return@setPositiveButton }
                MacroChoice.setUsed(a, s.name, false)
                MacroLog.i("deleted custom macro '${s.name}'")
                a.toast("Deleted ${s.name}.")
                Runner.changed()
                reload()
            }.show()
    }

    // --- the open macro ---------------------------------------------------------------------

    /** Make [s] what the floating ▶ starts, with the options as set here. */
    private fun select() {
        val s = scenario ?: return
        if (Runner.isRunning) { a.toast("Stop the running macro first (■ on the floating buttons)."); return }
        val params = paramFields.mapValues { it.value() }
        MacroParams.save(a, s.name, params)
        LastRun.save(a, s.name, params)
        MacroChoice.setUsed(a, s.name, true)
        detailUse.isChecked = true
        Runner.changed() // the floating buttons show the new name
        MacroLog.i("selected macro: ${s.name}")
        a.toast("${s.name} selected: start it with ▶ on the floating buttons.")
        showSelected(s)
    }

    private fun showSelected(s: Scenario) {
        val sel = LastRun.load(a)?.first == s.name
        selectButton.text = if (sel) "✓  Selected: save these options" else "Select this macro"
        selectHint.text = if (sel) "This is the macro the floating ▶ starts. Start, pause and stop it there, over the game."
        else "The floating buttons over the game then start, pause and stop it."
    }

    private fun toggleDiagram() {
        val show = diagramCard.visibility != View.VISIBLE
        diagramCard.visibility = if (show) View.VISIBLE else View.GONE
        diagramButton.text = if (show) "Hide" else "Show"
        if (show) drawDiagram()
    }

    private fun drawDiagram() {
        val s = scenario as? FlowScenario ?: return
        diagramBox.removeAllViews()
        diagramBox.addView(MacroDiagram.render(a, MacroDiagram.build(s.spec)) { name ->
            if (macros.any { it.name == name }) open(name) else a.toast("No macro named $name.")
        })
    }

    /** Open [s]: its description, what it runs, its options (as last chosen) and its diagram. */
    private fun showMacro(s: Scenario) {
        scenario = s
        if (opened.lastOrNull() != s.name) opened += s.name
        listScreen.visibility = View.GONE
        detailScreen.visibility = View.VISIBLE
        scroll.post { scroll.scrollTo(0, 0) }
        detailTitle.text = s.name
        detailIcon.setImageResource(Groups.icon(s))
        detailGroup.text = (s as? FlowScenario)?.takeIf { it.custom }?.let { c ->
            if (c.declaredGroup in setOf("custom", "other")) "Custom" else "Custom (draft for ${Groups.of(c.declaredGroup).title}: to be published to the library)"
        } ?: Groups.of(s.group).title
        val custom = (s as? FlowScenario)?.custom == true
        customCard.visibility = if (custom) View.VISIBLE else View.GONE
        (customCard.parent as? ViewGroup)?.let { p -> p.getChildAt(p.indexOfChild(customCard) - 1)?.visibility = customCard.visibility }
        detailDescription.text = s.description
        detailDescription.visibility = if (s.description.isBlank()) View.GONE else View.VISIBLE
        detailUses.removeAllViews()
        (s as? FlowScenario)?.uses?.takeIf { it.isNotEmpty() }?.let { uses ->
            detailUses.addView(Ui.text(a, "Runs these macros:", 12.5f, Ui.MUTED).apply { setPadding(0, dp(4), 0, dp(2)) })
            detailUses.addView(HorizontalScrollView(a).apply {
                isHorizontalScrollBarEnabled = false
                addView(LinearLayout(a).apply {
                    orientation = LinearLayout.HORIZONTAL
                    for (u in uses) addView(Ui.text(a, "▶ $u", 13f, Ui.ACCENT, bold = true).apply {
                        setPadding(dp(12), dp(6), dp(12), dp(6))
                        background = Ui.pressable(a, Ui.CARD_ALT, 14)
                        isClickable = true
                        setOnClickListener { open(u) }
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) }
                    })
                })
            })
        }
        detailUse.setOnCheckedChangeListener(null)
        detailUse.isChecked = MacroChoice.isUsed(a, s.name)
        detailUse.setOnCheckedChangeListener { _, on -> MacroChoice.setUsed(a, s.name, on) }
        showSelected(s)
        if (diagramCard.visibility == View.VISIBLE) drawDiagram()
        paramsBox.removeAllViews()
        paramFields.clear()
        if (s.params.isEmpty()) paramsBox.addView(Ui.text(a, "This macro has no options.", 13f, Ui.MUTED))
        val last = MacroParams.load(a, s.name)
        // params shown only while a switch is on: their views, by that switch
        val dependents = HashMap<String, MutableList<View>>()
        val switches = HashMap<String, Boolean>()
        var first = true
        for ((key, p) in s.params) {
            val value = last[key] ?: p.default
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

        /** Search: every word of [q] in the macro's name, description or group. */
        fun matches(m: Scenario, q: String): Boolean {
            val hay = "${m.name} ${m.description} ${Groups.of(m.group).title}".lowercase()
            return q.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }.all { it in hay }
        }
    }
}
