package io.github.brunoccst.anothermacro

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** The app's look: a dark theme with rounded cards, built in code (no XML layouts). */
object Ui {
    // Colours from the app's logo: near-black ink, deep navy, chrome steel and misty off-white.
    val BG = Color.parseColor("#0A0E18")
    val CARD = Color.parseColor("#121A2B")
    val CARD_ALT = Color.parseColor("#1A2440")
    val LINE = Color.parseColor("#28344F")
    val TEXT = Color.parseColor("#EEF0F4")
    val MUTED = Color.parseColor("#97A3BC")
    /** Chrome steel blue: links, selections, the main highlight. */
    val ACCENT = Color.parseColor("#B4C8F0")
    /** The logo's navy: selected rows, tonal fills. */
    val ACCENT_DIM = Color.parseColor("#1E2D5C")
    /** Text and glyphs on an [ACCENT] fill or a [bezel] face: the logo's navy ink. */
    val ON_ACCENT = Color.parseColor("#0A1230")
    /** Background of a folded section of options taken over from an inner macro (Macros page). */
    val INNER_SECTION = Color.parseColor("#0F1626")
    val GOOD = Color.parseColor("#6FCF97")
    val WARN = Color.parseColor("#E6B85C")
    val BAD = Color.parseColor("#E5707A")
    /** Extra chart/diagram hues, picked to sit with the steel blues. */
    val VIOLET = Color.parseColor("#A99BE0")
    val SLATE = Color.parseColor("#8A94A8")
    /** Floating windows over the game (overlay picker, prompts, live log): navy, slightly see-through. */
    val OVERLAY_BG = Color.parseColor("#EE0D1426")
    val OVERLAY_ITEM = Color.parseColor("#E61A2440")
    /** The logo's chrome: light at the top, a darker steel band, light again at the bottom. */
    private val CHROME = intArrayOf(Color.parseColor("#F2F5FA"), Color.parseColor("#B9C7E2"), Color.parseColor("#7E93BE"), Color.parseColor("#D5DDEC"))
    /** The overlay buttons' faces: misty off-white. */
    val FACE = Color.parseColor("#F7F6F2")
    val NAVY = Color.parseColor("#182656")

    fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()

    fun rounded(color: Int, radius: Float, stroke: Int? = null, strokeW: Int = 0) = GradientDrawable().apply {
        cornerRadius = radius
        setColor(color)
        if (stroke != null) setStroke(strokeW, stroke)
    }

    /** A rounded fill in the logo's chrome (top-to-bottom steel gradient), optionally with a navy rim. */
    fun chrome(radius: Float, rim: Int? = null, rimW: Int = 0) =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, CHROME).apply {
            cornerRadius = radius
            if (rim != null) setStroke(rimW, rim)
        }

    /**
     * Styled like the overlay buttons: a chrome bezel, a thin navy ring, a misty off-white face.
     * Pad the view by about [ringW] * 2 more than usual so text clears the bezel.
     */
    fun bezel(c: Context, radiusDp: Int, ringW: Int = dp(c, 2)): android.graphics.drawable.Drawable {
        val r = dp(c, radiusDp).toFloat()
        val ring = rounded(NAVY, r - ringW)
        val face = rounded(FACE, r - ringW * 1.6f)
        return android.graphics.drawable.LayerDrawable(arrayOf(chrome(r), ring, face)).apply {
            setLayerInset(1, ringW, ringW, ringW, ringW)
            val f = (ringW * 1.6f).toInt()
            setLayerInset(2, f, f, f, f)
        }
    }

    /**
     * Centres a label in its pill both ways: no extra line spacing or font padding, which
     * would push the text above the middle of the face.
     */
    fun centered(v: TextView) = v.apply {
        gravity = Gravity.CENTER
        includeFontPadding = false
        setLineSpacing(0f, 1f)
    }

    /** A [bezel] (like the overlay buttons) that ripples when tapped: selected tabs, primary buttons. */
    fun pressableBezel(c: Context, radiusDp: Int = 14) =
        RippleDrawable(ColorStateList.valueOf(Color.parseColor("#33182656")), bezel(c, radiusDp), null)

    /** A rounded background that ripples when tapped. */
    fun pressable(c: Context, color: Int, radiusDp: Int = 14, stroke: Int? = null) =
        RippleDrawable(ColorStateList.valueOf(Color.parseColor("#33FFFFFF")),
            rounded(color, dp(c, radiusDp).toFloat(), stroke, dp(c, 2)), null)

    fun text(c: Context, s: CharSequence, size: Float = 14f, color: Int = TEXT, bold: Boolean = false) = TextView(c).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setLineSpacing(0f, 1.1f)
    }

    /** A section title above cards. */
    fun section(c: Context, s: String) = text(c, s.uppercase(), 12f, MUTED, bold = true).apply {
        letterSpacing = 0.08f
        setPadding(dp(c, 6), dp(c, 18), 0, dp(c, 8))
    }

    fun card(c: Context, color: Int = CARD) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(color, dp(c, 18).toFloat())
        setPadding(dp(c, 16), dp(c, 14), dp(c, 16), dp(c, 14))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = dp(c, 10) }
    }

    /** A filled (primary) or tonal button. */
    fun button(c: Context, label: String, primary: Boolean = false, color: Int = ACCENT, onClick: () -> Unit) = TextView(c).apply {
        text = label
        textSize = 15f
        gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(if (primary) ON_ACCENT else color)
        // a primary button in the accent looks like the overlay buttons; other colours stay flat
        background = if (primary && color == ACCENT) pressableBezel(c, 24) else pressable(c, if (primary) color else CARD_ALT, 24)
        setPadding(dp(c, 18), dp(c, 12), dp(c, 18), dp(c, 12))
        centered(this)
        isClickable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(c, 8) }
    }

    /** A small status pill: a coloured dot and a label. */
    fun chip(c: Context) = TextView(c).apply {
        textSize = 12f
        setTextColor(TEXT)
        background = rounded(CARD_ALT, dp(c, 14).toFloat())
        setPadding(dp(c, 12), dp(c, 6), dp(c, 12), dp(c, 6))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { rightMargin = dp(c, 8) }
    }

    fun setChip(v: TextView, label: String, color: Int) {
        v.text = android.text.SpannableString("●  $label").apply {
            setSpan(android.text.style.ForegroundColorSpan(color), 0, 1, 0)
        }
    }

    fun divider(c: Context) = View(c).apply {
        setBackgroundColor(LINE)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { topMargin = dp(c, 10); bottomMargin = dp(c, 10) }
    }

    fun icon(c: Context, res: Int, sizeDp: Int = 22) = android.widget.ImageView(c).apply {
        setImageResource(res)
        layoutParams = LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)).apply { rightMargin = dp(c, 10) }
    }

    /** A row: an icon, then [content]. */
    fun withIcon(c: Context, res: Int, content: View, sizeDp: Int = 22) = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(icon(c, res, sizeDp))
        addView(content, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }

    /** The app's name as a small logo: the round "AM" badge, then the name in a serif face. */
    fun brand(c: Context, sizeSp: Float, badgeDp: Int) = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(android.widget.ImageView(c).apply { setImageResource(R.drawable.overlay_bubble) },
            LinearLayout.LayoutParams(dp(c, badgeDp), dp(c, badgeDp)).apply { rightMargin = dp(c, 10) })
        addView(text(c, "Another Macro", sizeSp).apply {
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            letterSpacing = 0.03f
        })
    }

    /** "fight_all" → "Fight all". */
    fun title(key: String) = key.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

/** How macros are grouped in the app and the overlay (a macro's `group:`). */
object Groups {
    /** [iconRes]: an original vector icon (res/drawable), drawn for this app: no game art. */
    data class Group(val id: String, val title: String, val iconRes: Int)

    val ALL = listOf(
        Group("daily", "Daily", R.drawable.ic_sun),
        Group("dungeon", "Another Dungeon", R.drawable.ic_keycard),
        Group("farm", "Farming", R.drawable.ic_swords),
        Group("game", "Game", R.drawable.ic_power),
        // building blocks other macros run (watch-ad, battle, grind-battles, ...)
        Group("basic", "Basic", R.drawable.ic_blocks),
        Group("other", "Other", R.drawable.ic_puzzle),
        // saved by Claude through the phone connector, not from the macro library
        Group("custom", "Custom", R.drawable.ic_wrench),
    )
    private val OTHER = ALL.first { it.id == "other" }

    /** A macro's own icon (`icon:` in its yaml), else its group's. */
    fun icon(m: Scenario): Int = when (m.icon) {
        "chest" -> R.drawable.ic_chest
        "paw" -> R.drawable.ic_paw
        "crystal" -> R.drawable.ic_crystal
        "key" -> R.drawable.ic_keycard
        "swords" -> R.drawable.ic_swords
        "sun" -> R.drawable.ic_sun
        "power" -> R.drawable.ic_power
        else -> of(m.group).iconRes
    }

    fun of(id: String) = ALL.firstOrNull { it.id == id } ?: OTHER

    /** Macros by group, in the groups' order. */
    fun sort(macros: List<Scenario>): List<Pair<Group, List<Scenario>>> =
        ALL.map { g -> g to macros.filter { of(it.group).id == g.id } }.filter { it.second.isNotEmpty() }
}

/**
 * The macros the user chose to use (Macros page). The others stay in their group's tab but
 * leave the "In use" tab and the floating buttons' list. Stored as the names switched off,
 * so new macros start in use.
 */
object MacroChoice {
    private const val PREFS = "macro_choice"
    private const val KEY = "not_used"

    fun notUsed(c: Context): Set<String> =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY, emptySet()).orEmpty()

    fun isUsed(c: Context, name: String) = name !in notUsed(c)

    fun setUsed(c: Context, name: String, used: Boolean) {
        val set = notUsed(c).toMutableSet()
        if (used) set -= name else set += name
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY, set).apply()
    }

    /** The macros in use (all of them if none is: an empty list helps nobody). */
    fun used(c: Context, macros: List<Scenario>): List<Scenario> {
        val off = notUsed(c)
        return macros.filter { it.name !in off }.ifEmpty { macros }
    }
}
