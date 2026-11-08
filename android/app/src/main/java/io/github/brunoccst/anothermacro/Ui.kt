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
    val BG = Color.parseColor("#0F1216")
    val CARD = Color.parseColor("#1A1F26")
    val CARD_ALT = Color.parseColor("#232A33")
    val LINE = Color.parseColor("#2C3440")
    val TEXT = Color.parseColor("#E9EDF2")
    val MUTED = Color.parseColor("#94A0AE")
    val ACCENT = Color.parseColor("#4FC3F7")
    val ACCENT_DIM = Color.parseColor("#1D3A4A")
    val GOOD = Color.parseColor("#66BB6A")
    val WARN = Color.parseColor("#FFB74D")
    val BAD = Color.parseColor("#EF5350")

    fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()

    fun rounded(color: Int, radius: Float, stroke: Int? = null, strokeW: Int = 0) = GradientDrawable().apply {
        cornerRadius = radius
        setColor(color)
        if (stroke != null) setStroke(strokeW, stroke)
    }

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
        setTextColor(if (primary) Color.parseColor("#0B1A22") else color)
        background = pressable(c, if (primary) color else CARD_ALT, 24)
        setPadding(dp(c, 18), dp(c, 12), dp(c, 18), dp(c, 12))
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
        // building blocks other macros run (watch-ad, grind-battles)
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
