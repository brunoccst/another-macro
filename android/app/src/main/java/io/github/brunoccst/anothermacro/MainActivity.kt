package io.github.brunoccst.anothermacro

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The app: a top bar with ≡, a sidebar (drawer) and three pages:
 *  - Home: what the app is, how to use it, quick links (HomePage)
 *  - Macros: the macros in tabs by group (downloaded from the GitHub project), the ones you use,
 *    what each does, its options and "Select this macro"; the floating buttons run it (MacrosPage)
 *  - System: taps / screen capture, the Claude phone connector, settings, updates and the log (SystemPage)
 *
 * Can also be driven from adb for testing:
 *   am start -n io.github.brunoccst.anothermacro/.MainActivity --es run grind-battles --es params "battles=3,mode=skill"
 *   am start -n io.github.brunoccst.anothermacro/.MainActivity --es page system
 */
class MainActivity : Activity() {

    enum class Page(val id: String, val title: String, val iconRes: Int) {
        HOME("home", "Home", R.drawable.ic_home),
        MACROS("macros", "Macros", R.drawable.ic_macros),
        SYSTEM("system", "System", R.drawable.ic_sliders),
    }

    internal lateinit var home: HomePage
    internal lateinit var macros: MacrosPage
    internal lateinit var system: SystemPage
    private lateinit var pages: Map<Page, View>
    var page = Page.HOME
        private set

    private lateinit var pageTitle: TextView
    private lateinit var drawer: LinearLayout
    private lateinit var scrim: View
    private var drawerOpen = false
    private val navRows = HashMap<Page, LinearLayout>()
    /** Every "taps / capture / running" chip trio on screen (Home and the sidebar). */
    private val chipSets = ArrayList<Triple<TextView, TextView, TextView>>()
    private val runListener: () -> Unit = { runOnUiThread { refreshStatus() } }

    internal fun dp(v: Int) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logic.init(this)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG

        home = HomePage(this)
        macros = MacrosPage(this)
        system = SystemPage(this)
        pages = mapOf(Page.HOME to home.view, Page.MACROS to macros.view, Page.SYSTEM to system.view)

        // --- top bar: ≡ and the page's name --------------------------------------------
        pageTitle = Ui.text(this, "", 20f, bold = true)
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Ui.BG)
            setPadding(dp(6), dp(6), dp(16), dp(6))
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_menu)
                contentDescription = "Menu"
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = Ui.pressable(this@MainActivity, Ui.BG, 24)
                isClickable = true
                setOnClickListener { if (drawerOpen) closeDrawer() else openDrawer() }
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(6) }
            })
            addView(pageTitle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(Ui.text(this@MainActivity, BuildConfig.VERSION_NAME, 11f, Ui.MUTED))
        }

        val content = FrameLayout(this).apply { setBackgroundColor(Ui.BG) }
        for (v in pages.values) content.addView(v, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val main = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            addView(topBar)
            addView(View(this@MainActivity).apply { setBackgroundColor(Ui.LINE) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1))
            addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }

        // --- the sidebar, over the page --------------------------------------------------
        scrim = View(this).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#99000000"))
            alpha = 0f
            visibility = View.GONE
            isClickable = true
            setOnClickListener { closeDrawer() }
        }
        val width = minOf(dp(300), (resources.displayMetrics.widthPixels * 0.82).toInt())
        drawer = buildDrawer().apply { translationX = -width.toFloat(); visibility = View.INVISIBLE }

        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Ui.BG)
            addView(main, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(scrim, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(drawer, FrameLayout.LayoutParams(width, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.START))
        })

        val start = savedInstanceState?.getString(STATE_PAGE) ?: intent?.getStringExtra(EXTRA_PAGE)
        show(Page.values().firstOrNull { it.id == start } ?: Page.HOME)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        Updater.clearNotification(this)
        handleAdbIntent(intent)
    }

    private fun buildDrawer(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Ui.CARD)
        isClickable = true // taps on the sidebar don't fall through to the page
        setPadding(dp(12), dp(24), dp(12), dp(16))
        addView(Ui.text(this@MainActivity, "Another Macro", 22f, bold = true).apply { setPadding(dp(8), 0, 0, 0) })
        addView(Ui.text(this@MainActivity, "Automation for Another Eden, on this phone", 12.5f, Ui.MUTED).apply { setPadding(dp(8), dp(2), 0, dp(10)) })
        addView(statusChips().apply { setPadding(dp(8), 0, 0, dp(14)) })
        addView(View(this@MainActivity).apply { setBackgroundColor(Ui.LINE) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { bottomMargin = dp(10) })
        for (p in Page.values()) {
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                isClickable = true
                addView(Ui.icon(this@MainActivity, p.iconRes, 22).apply { (layoutParams as LinearLayout.LayoutParams).rightMargin = dp(16) })
                addView(Ui.text(this@MainActivity, p.title, 16f, bold = true))
                setOnClickListener { show(p); closeDrawer() }
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) }
            }
            navRows[p] = row
            addView(row)
        }
        addView(View(this@MainActivity), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(Ui.text(this@MainActivity, "Version ${BuildConfig.VERSION_NAME}", 12f, Ui.MUTED).apply { setPadding(dp(8), 0, 0, 0) })
    }

    /** Switch to page [p]. */
    fun show(p: Page) {
        page = p
        for ((k, v) in pages) v.visibility = if (k == p) View.VISIBLE else View.GONE
        pageTitle.text = p.title
        for ((k, row) in navRows) {
            val sel = k == p
            row.background = if (sel) Ui.rounded(Ui.ACCENT_DIM, dp(14).toFloat()) else Ui.pressable(this, Ui.CARD, 14)
            ((row.getChildAt(0) as ImageView)).imageTintList = android.content.res.ColorStateList.valueOf(if (sel) Ui.ACCENT else Ui.MUTED)
            (row.getChildAt(1) as TextView).setTextColor(if (sel) Ui.ACCENT else Ui.TEXT)
        }
    }

    private fun openDrawer() {
        drawerOpen = true
        drawer.visibility = View.VISIBLE
        scrim.visibility = View.VISIBLE
        drawer.animate().translationX(0f).setDuration(200).start()
        scrim.animate().alpha(1f).setDuration(200).start()
    }

    private fun closeDrawer() {
        if (!drawerOpen) return
        drawerOpen = false
        drawer.animate().translationX(-drawer.width.toFloat()).setDuration(180).withEndAction { if (!drawerOpen) drawer.visibility = View.INVISIBLE }.start()
        scrim.animate().alpha(0f).setDuration(180).withEndAction { if (!drawerOpen) scrim.visibility = View.GONE }.start()
    }

    @Deprecated("Back: close the sidebar, then the open macro, then Home, then leave")
    override fun onBackPressed() {
        when {
            drawerOpen -> closeDrawer()
            page == Page.MACROS && macros.back() -> {}
            page != Page.HOME -> show(Page.HOME)
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PAGE, page.id)
    }

    // --- status chips: taps, capture, running ---------------------------------------------

    /** A row of three status chips, kept up to date by [refreshStatus]. */
    internal fun statusChips(): View {
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val set = Triple(Ui.chip(this), Ui.chip(this), Ui.chip(this))
        chips.addView(set.first); chips.addView(set.second); chips.addView(set.third)
        chipSets += set
        return android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) }
    }

    internal fun refreshStatus() {
        val taps = InputService.instance != null
        val cap = MacroService.capture != null
        for ((tapsChip, captureChip, runChip) in chipSets) {
            Ui.setChip(tapsChip, if (taps) "Taps on" else "Taps off", if (taps) Ui.GOOD else Ui.BAD)
            Ui.setChip(captureChip, if (cap) "Capture on" else "Capture off", if (cap) Ui.GOOD else Ui.BAD)
            Ui.setChip(runChip, when {
                Runner.isPaused -> "Paused: ${Runner.current}"
                Runner.isRunning -> "Running: ${Runner.current}"
                else -> "Idle"
            }, when { Runner.isPaused -> Ui.WARN; Runner.isRunning -> Ui.ACCENT; else -> Ui.MUTED })
        }
        home.refresh(taps, cap)
        system.refreshSetup(taps, cap)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_PAGE)?.let { id -> Page.values().firstOrNull { it.id == id }?.let { show(it) } }
        handleAdbIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        inFront = true
        Runner.listeners += runListener
        system.onResume()
        macros.onResume()
        refreshStatus()
    }

    override fun onPause() {
        inFront = false
        Runner.listeners -= runListener
        system.onPause()
        macros.onPause()
        super.onPause()
    }

    // --- screen capture, run (adb) -----------------------------------------------------

    internal fun requestCapture() {
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
        pageTitle.postDelayed({ refreshStatus() }, 800)
        if (Updater.resumePending(this)) pageTitle.postDelayed({ resumeAfterUpdate() }, 2000)
    }

    /** After a self-update: the connector back on (if it was), the game in front. */
    private fun resumeAfterUpdate() {
        if (MacroService.capture == null) { pageTitle.postDelayed({ resumeAfterUpdate() }, 1000); return }
        val connector = Updater.resumeConnector(this)
        Updater.clearResume(this)
        if (connector) PhoneConnector.start(applicationContext)
        MacroLog.i("update done (${BuildConfig.VERSION_NAME}): capture on${if (connector) ", connector on" else ""}")
        InputService.instance?.launchApp(ClaudeSafety.GAME)
    }

    /** Start a macro now: only for adb testing (users start macros from the floating buttons). */
    internal fun run(name: String, params: Map<String, String>) {
        if (MacroService.capture == null) { MacroLog.i("allow screen capture first (System → Setup)"); toast("Allow screen capture first: System → Setup."); return }
        if (InputService.instance == null) { MacroLog.i("turn on taps first (System → Setup)"); toast("Turn on taps first: System → Setup."); return }
        startService(LastRun.intent(this, name, params))
        // get out of the way so the game is in front
        moveTaskToBack(true)
    }

    internal fun toast(s: String) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show()

    internal fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
            .onFailure { toast("No browser to open $url") }
    }

    private fun handleAdbIntent(intent: Intent?) {
        // back from an update Claude started: allow screen capture again (the accessibility
        // service presses "Start now"), then the connector and the game come back on
        if (intent?.getBooleanExtra(EXTRA_RESUME, false) == true) {
            intent.removeExtra(EXTRA_RESUME)
            if (!Updater.resumePending(this)) return
            if (MacroService.capture == null) { MacroLog.i("updated to ${BuildConfig.VERSION_NAME}: turning screen capture back on"); requestCapture() }
            else resumeAfterUpdate()
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
        const val EXTRA_RESUME = "resume_after_update"
        /** Open on this page: home, macros or system. */
        const val EXTRA_PAGE = "page"
        private const val STATE_PAGE = "page"
        const val REPO = "https://github.com/brunoccst/another-macro"
        /** This app's screen is the one in front (resumed): the floating ⌂ then goes back to the game. */
        @Volatile
        var inFront = false
            private set
    }
}
