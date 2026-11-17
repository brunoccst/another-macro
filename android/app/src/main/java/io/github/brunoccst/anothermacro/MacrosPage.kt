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
    /** "Statistics: N runs ›": opens the open macro's own page on Statistics. */
    private val detailStats = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        background = Ui.pressable(a, Ui.CARD, 12)
        setPadding(0, dp(4), 0, dp(4))
        setOnClickListener { scenario?.let { s -> a.show(MainActivity.Page.STATS); a.stats.open(s.name) } }
    }
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
                addView(Ui.divider(a))
                addView(detailStats)
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
            tabsRow.addView(Ui.text(a, title, 14f, if (sel) Ui.ON_ACCENT else Ui.TEXT, bold = true).apply {
                Ui.centered(this)
                setPadding(dp(16), dp(10), dp(16), dp(10))
                background = if (sel) Ui.pressableBezel(a, 18) else Ui.pressable(a, Ui.CARD_ALT, 18)
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

    private fun drawStatsLink(s: Scenario) {
        RunStats.init(a)
        val runs = RunStats.all().filter { it.macro == s.name }
        detailStats.removeAllViews()
        detailStats.addView(Ui.icon(a, R.drawable.ic_chart, 22))
        detailStats.addView(LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            addView(Ui.text(a, "Statistics", 15f, bold = true))
            addView(Ui.text(a, if (runs.isEmpty()) "No runs yet" else
                "${runs.size} run(s) · ${RunStats.duration(runs.sumOf { it.ms })} · last ${java.text.SimpleDateFormat("EEE d MMM, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(runs.maxOf { it.start }))}",
                12.5f, Ui.MUTED))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        detailStats.addView(Ui.text(a, "›", 22f, Ui.MUTED).apply { setPadding(dp(8), 0, dp(4), 0) })
    }

    private fun toggleDiagram() {
        val show = diagramCard.visibility != View.VISIBLE
        diagramCard.visibility = if (show) View.VISIBLE else View.GONE
        diagramButton.text = if (show) "Hide" else "Show"
        if (show) drawDiagram()
    }

    /** Redraw "How it works" when an option changes (its steps follow the options as set). */
    private fun optionChanged() { if (diagramCard.visibility == View.VISIBLE) diagramCard.post { drawDiagram() } }

    /** The open macro's options as set now on the page (else as saved, else their defaults). */
    private fun currentOptions(s: Scenario): Map<String, String> {
        val last = MacroParams.load(a, s.name)
        return s.params.entries.associate { (k, p) ->
            k to (paramFields[k]?.invoke() ?: last[k] ?: if (p.from != null) InnerParams.effective(k, p) { MacroParams.load(a, it) } else p.default)
        }
    }

    /**
     * "How it works": the macro's steps in order with the options as set ([MacroSteps]), then,
     * folded, what its file does on each screen ([MacroDiagram]).
     */
    private fun drawDiagram() {
        val s = scenario as? FlowScenario ?: return
        val openMacro = { name: String -> if (macros.any { it.name == name }) open(name) else a.toast("No macro named $name.") }
        diagramBox.removeAllViews()
        val files = MacroLibrary.files(a)
        val steps = runCatching {
            MacroSteps.build(s, currentOptions(s), find = { n -> macros.firstOrNull { it.name == n } as? FlowScenario },
                saved = { MacroParams.load(a, it) },
                file = { path -> runCatching { files.open(path).bufferedReader().use { it.readText() } }.getOrNull() },
                explain = { action, arg, env -> Logic.current().entry.explain(action, arg, env) })
        }.getOrElse { MacroLog.e("how it works: ${it.message}"); emptyList() }
        if (steps.isNotEmpty()) {
            diagramBox.addView(Ui.text(a, "Step by step", 14.5f, Ui.TEXT, bold = true))
            diagramBox.addView(Ui.text(a, "What it does, in order, with the options as set above. Tap a step that runs another macro to open it.", 12f, Ui.MUTED)
                .apply { setPadding(0, 0, 0, dp(6)) })
            diagramBox.addView(MacroDiagram.renderSteps(a, steps, openMacro))
        }
        val rules = MacroDiagram.render(a, MacroDiagram.build(s.spec), openMacro)
        if (steps.isEmpty()) { diagramBox.addView(rules); return }
        rules.visibility = View.GONE
        diagramBox.addView(Ui.text(a, "▸ Screen by screen (the macro file's rules)", 13.5f, Ui.ACCENT, bold = true).apply {
            setPadding(0, dp(16), 0, dp(8))
            isClickable = true
            setOnClickListener {
                val show = rules.visibility != View.VISIBLE
                rules.visibility = if (show) View.VISIBLE else View.GONE
                text = (if (show) "▾" else "▸") + " Screen by screen (the macro file's rules)"
            }
        })
        diagramBox.addView(rules)
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
        drawStatsLink(s)
        // after the options below are drawn: the steps read them
        if (diagramCard.visibility == View.VISIBLE) diagramCard.post { drawDiagram() }
        paramsBox.removeAllViews()
        paramFields.clear()
        if (s.params.isEmpty()) paramsBox.addView(Ui.text(a, "This macro has no options.", 13f, Ui.MUTED))
        val last = MacroParams.load(a, s.name)
        // params shown only while a switch is on: their views, by that switch
        val dependents = HashMap<String, MutableList<View>>()
        val switches = HashMap<String, Boolean>()
        var first = true
        // switches drawn on the side of another option's box (all_keys beside runs), by that option
        val besides = s.params.filter { (_, p) -> p.isBool && p.beside != null && p.beside in s.params }.entries.associateBy { it.value.beside!! }
        // the options taken over from the macros it runs (battle.skill_1, see InnerParams): one
        // folded section per macro, after its own options
        var section: LinearLayout? = null
        var sectionOf: String? = null
        for ((key, p) in s.params) {
            if (p.isBool && besides[p.beside]?.key == key) continue
            val value = last[key] ?: if (p.from != null) InnerParams.effective(key, p) { MacroParams.load(a, it) } else p.default
            if (p.from != sectionOf) {
                sectionOf = p.from
                section = p.from?.let { innerSection(it, first) }
                first = true
            }
            val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            (section ?: paramsBox).addView(box)
            p.showIf?.let { dependents.getOrPut(it) { ArrayList() } += box; box.visibility = if (switches[it] == true) View.VISIBLE else View.GONE }
            if (!first) box.addView(Ui.divider(a))
            first = false
            if (p.isBool) {
                var cur = FlowScenario.typed(value) == true
                switches[key] = cur
                box.addView(switchRow(Ui.title(p.innerKey(key)), p.help, cur) { on ->
                    cur = on
                    dependents[key]?.forEach { it.visibility = if (on) View.VISIBLE else View.GONE }
                    optionChanged()
                })
                paramFields[key] = { cur.toString() }
                continue
            }
            box.addView(Ui.text(a, Ui.title(p.innerKey(key)), 15f, bold = true))
            if (p.help.isNotBlank()) box.addView(Ui.text(a, p.help, 12.5f, Ui.MUTED))
            if (p.choices.isNotEmpty()) {
                val options = if (value in p.choices) p.choices else p.choices + value
                // choices as a row of pills: tap one to pick it
                var cur = value
                val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
                val pills = ArrayList<TextView>()
                fun paint() = pills.forEach { t ->
                    val sel = t.tag == cur
                    t.setTextColor(if (sel) Ui.ON_ACCENT else Ui.TEXT)
                    t.background = if (sel) Ui.pressableBezel(a, 16) else Ui.pressable(a, Ui.CARD_ALT, 16)
                }
                for (o in options) {
                    val t = Ui.text(a, o, 14f).apply {
                        tag = o
                        Ui.centered(this)
                        minWidth = dp(44)
                        setPadding(dp(14), dp(8), dp(14), dp(8))
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) }
                        setOnClickListener { cur = o; paint(); optionChanged() }
                    }
                    pills += t; row.addView(t)
                }
                paint()
                box.addView(HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(row) })
                paramFields[key] = { cur }
                continue
            }
            // a number box keeps only a number (an old saved "all" becomes the side switch, below)
            val text = if (p.isNumber && value.trim().removeSuffix(".0").toIntOrNull() == null) p.default else value
            val field = EditText(a).apply {
                setText(text)
                inputType = if (p.isNumber) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
                setSingleLine()
                setTextColor(Ui.TEXT)
                background = Ui.rounded(Ui.CARD_ALT, dp(12).toFloat())
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            paramFields[key] = { field.text.toString() }
            val side = besides[key]
            if (side == null) {
                box.addView(field, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
                continue
            }
            // the box and, on its side, a switch (all_keys beside runs) that greys the box out while on
            val (sideKey, sideParam) = side.key to side.value
            var on = FlowScenario.typed(last[sideKey] ?: sideParam.default) == true || value.trim().lowercase() == "all"
            fun grey() { field.isEnabled = !on; field.alpha = if (on) 0.4f else 1f }
            grey()
            paramFields[sideKey] = { on.toString() }
            box.addView(LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(field, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(Ui.text(a, Ui.title(sideKey), 14f).apply { setPadding(dp(14), 0, dp(6), 0) })
                addView(switch(on) { v -> on = v; grey(); optionChanged() })
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        }
    }

    /**
     * A folded section for the options taken over from the macro [inner]: a header to unfold it
     * (and open that macro), its rows go in the returned box.
     */
    private fun innerSection(inner: String, firstOfAll: Boolean): LinearLayout {
        val body = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            // the inner macro's options on their own tinted card, apart from the macro's own options
            background = Ui.rounded(Ui.INNER_SECTION, dp(14).toFloat(), Ui.ACCENT_DIM, dp(1))
            setPadding(dp(12), dp(4), dp(12), dp(10))
        }
        val arrow = Ui.text(a, "▸", 16f, Ui.ACCENT, bold = true).apply { setPadding(0, 0, dp(10), 0) }
        val header = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            isClickable = true
            addView(arrow)
            addView(LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                addView(Ui.text(a, "${Ui.title(inner)} options", 15f, Ui.ACCENT, bold = true))
                addView(Ui.text(a, "This macro runs $inner and keeps its own settings for it: they start as $inner's own, and are saved with this macro.", 12.5f, Ui.MUTED))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(Ui.text(a, "Open", 13f, Ui.ACCENT).apply {
                setPadding(dp(10), dp(6), dp(4), dp(6))
                isClickable = true
                setOnClickListener { open(inner) }
            })
            setOnClickListener {
                val show = body.visibility != View.VISIBLE
                body.visibility = if (show) View.VISIBLE else View.GONE
                arrow.text = if (show) "▾" else "▸"
            }
        }
        if (!firstOfAll) paramsBox.addView(Ui.divider(a))
        paramsBox.addView(header)
        paramsBox.addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8)
        })
        return body
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
