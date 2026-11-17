package macroforge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Notifications that reach the user when they aren't looking at the game: a macro ended (how it
 * went), a scheduled run couldn't start, Claude is waiting for an Allow/Deny answer. The running
 * macro's own notification (MacroService) is silent and always there; these are separate and
 * make the usual sound. The user can turn them off in System → Settings.
 */
object Alerts {
    private const val PREFS = "alerts"
    private const val CHANNEL = "alerts"
    private const val OUTCOME_ID = 10
    private const val SCHEDULE_ID = 11
    const val APPROVAL_ID = 12

    fun enabled(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("on", true)

    fun setEnabled(c: Context, on: Boolean) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("on", on).apply()
        MacroLog.i("notifications ${if (on) "on" else "off"}")
    }

    private fun manager(c: Context): NotificationManager {
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Macro results and questions", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "When a macro ends, a scheduled run can't start, or Claude waits for your answer"
            })
        }
        return nm
    }

    private fun builder(c: Context, title: String, text: String): Notification.Builder {
        val open = PendingIntent.getActivity(c, 20, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(c, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(c)
        return b.setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text.lineSequence().firstOrNull().orEmpty())
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
    }

    private fun post(c: Context, id: Int, n: Notification) {
        if (!enabled(c)) return
        runCatching { manager(c).notify(id, n) }.onFailure { MacroLog.e("could not show a notification", it) }
    }

    /** A macro (or routine) ended. */
    fun outcome(c: Context, title: String, text: String) = post(c, OUTCOME_ID, builder(c, title, text).build())

    /** A scheduled run started or couldn't start. */
    fun schedule(c: Context, title: String, text: String) = post(c, SCHEDULE_ID, builder(c, title, text).build())

    /** Claude asked to tap something risky and nobody answered on the screen: ask here, with Allow and Deny. */
    fun approval(c: Context, question: String) {
        fun action(label: String, allow: Boolean): Notification.Action {
            val pi = PendingIntent.getBroadcast(c, if (allow) 21 else 22,
                Intent(c, ApprovalReceiver::class.java).putExtra(ApprovalReceiver.EXTRA_ALLOW, allow),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            return Notification.Action.Builder(null, label, pi).build()
        }
        // always shown, even with the other notifications off: it's the only place to answer
        runCatching {
            manager(c).notify(APPROVAL_ID, builder(c, "Claude asks", question)
                .setAutoCancel(false)
                .setTimeoutAfter(Approvals.TTL_MS)
                .addAction(action("Allow", true))
                .addAction(action("Deny", false))
                .build())
        }.onFailure { MacroLog.e("could not show the question", it) }
    }

    fun cancelApproval(c: Context) { runCatching { manager(c).cancel(APPROVAL_ID) } }
}

/** The Allow / Deny buttons of [Alerts.approval]. */
class ApprovalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Approvals.answer(context.applicationContext, intent.getBooleanExtra(EXTRA_ALLOW, false))
    }

    companion object { const val EXTRA_ALLOW = "allow" }
}
