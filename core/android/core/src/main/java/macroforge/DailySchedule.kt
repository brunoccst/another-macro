package macroforge

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * A daily run with nobody watching: one macro (by default the daily routine) starts every day a
 * few minutes after the game's daily reset, while screen capture is on. Android's alarm wakes
 * the app; the run then goes like one started with ▶, with the macro's own saved options, and
 * ends with the usual outcome message and notification.
 *
 * If screen capture is off at that time, nothing runs and a notification says so. If another
 * macro is running, it tries again every [RETRY_MS], up to [RETRIES] times.
 */
object DailySchedule {
    private const val PREFS = "schedule"
    /** The game's daily reset, in UTC. */
    const val RESET_HOUR_UTC = 15
    const val DEFAULT_MACRO = "daily-routine"
    const val DEFAULT_DELAY_MIN = 10
    const val RETRY_MS = 15 * 60_000L
    const val RETRIES = 4
    private const val EXTRA_TRY = "try"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(c: Context) = prefs(c).getBoolean("on", false)
    fun macro(c: Context) = prefs(c).getString("macro", null)?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_MACRO
    fun delayMin(c: Context) = prefs(c).getInt("delay_min", DEFAULT_DELAY_MIN)

    fun setEnabled(c: Context, on: Boolean) { prefs(c).edit().putBoolean("on", on).apply(); arm(c) }
    fun setMacro(c: Context, name: String) { prefs(c).edit().putString("macro", name.trim()).apply() }
    fun setDelayMin(c: Context, min: Int) { prefs(c).edit().putInt("delay_min", min.coerceIn(0, 23 * 60)).apply(); arm(c) }

    /** The next run after [now]: today's (or tomorrow's) reset plus [delayMin] minutes. */
    fun nextAt(now: Long, delayMin: Int, resetHourUtc: Int = RESET_HOUR_UTC): Long {
        val day = 24 * 3_600_000L
        val offset = resetHourUtc * 3_600_000L + delayMin * 60_000L
        var at = Math.floorDiv(now, day) * day + offset
        while (at <= now) at += day
        while (at - day > now) at -= day
        return at
    }

    /** When the next run is, in the phone's time ("16:10"), for the settings page. */
    fun describeNext(c: Context): String {
        val at = nextAt(System.currentTimeMillis(), delayMin(c))
        return java.text.SimpleDateFormat("EEE HH:mm", java.util.Locale.getDefault()).format(java.util.Date(at))
    }

    private fun intent(c: Context, attempt: Int) = PendingIntent.getBroadcast(c, 30,
        Intent(c, ScheduleReceiver::class.java).putExtra(EXTRA_TRY, attempt),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** Set (or clear) the alarm for the next run. Called at start-up, on a settings change and after each run. */
    fun arm(c: Context) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = intent(c, 0)
        am.cancel(pi)
        if (!enabled(c)) return
        // inexact on purpose: no special permission, and a few minutes late doesn't matter here
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextAt(System.currentTimeMillis(), delayMin(c)), pi)
    }

    private fun retry(c: Context, attempt: Int) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + RETRY_MS, intent(c, attempt))
    }

    /** The alarm went off. */
    fun fire(c: Context, attempt: Int) {
        if (!enabled(c)) return
        if (attempt == 0) arm(c) // tomorrow's, before anything else can go wrong
        PhoneEvents.init(c)
        val name = macro(c)
        fun problem(title: String, text: String) {
            MacroLog.i("schedule: $title — $text")
            PhoneEvents.add("schedule", title, text, ok = false)
            Alerts.schedule(c, title, text)
        }
        if (MacroService.capture == null) {
            problem("Scheduled $name didn't run", "Screen capture was off. Open ${GameProfile.current.appName} and allow screen capture to let it run tomorrow.")
            return
        }
        if (Runner.isRunning) {
            if (attempt < RETRIES) {
                MacroLog.i("schedule: ${Runner.current} is running; trying $name again in ${RETRY_MS / 60_000} minutes")
                retry(c, attempt + 1)
            } else problem("Scheduled $name didn't run", "${Runner.current ?: "Another macro"} was still running after ${RETRIES * RETRY_MS / 60_000} minutes.")
            return
        }
        MacroLog.i("schedule: starting $name")
        PhoneEvents.add("schedule", "Scheduled $name started", "", ok = true)
        c.startService(LastRun.intent(c, name, MacroParams.load(c, name)).putExtra(MacroService.EXTRA_SOURCE, "schedule"))
    }
}

/** The [DailySchedule] alarm. */
class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        DailySchedule.fire(context.applicationContext, intent.getIntExtra("try", 0))
    }
}
