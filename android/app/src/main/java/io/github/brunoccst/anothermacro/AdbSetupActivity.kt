package io.github.brunoccst.anothermacro

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * One-time setup for recording runs (⏺): pair the app with the phone's Wireless debugging,
 * so it can read the touchscreen itself (see AppAdb). The pairing-code dialog closes when you
 * switch apps, so the code is typed into a notification (pull it down over Settings), or into
 * this screen in split screen.
 */
class AdbSetupActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (14 * resources.displayMetrics.density).toInt()
        status = text("")
        val port = EditText(this).apply {
            hint = "port (optional)"; inputType = InputType.TYPE_CLASS_NUMBER; setSingleLine()
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
        }
        val code = EditText(this).apply {
            hint = "6-digit pairing code"; inputType = InputType.TYPE_CLASS_NUMBER; setSingleLine()
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#1E272C"))
            addView(LinearLayout(this@AdbSetupActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, pad)
                addView(text("Recording runs: let the app read the touchscreen", 18f))
                addView(text(
                    "Once only. The app pairs with Android's Wireless debugging (like Shizuku does), then reads your " +
                        "touches while you record, without Termux or a PC.\n\n" +
                        "1. Settings → About phone → tap Build number 7 times (unlocks Developer options).\n" +
                        "2. Settings → Developer options → turn on Wireless debugging (needs Wi-Fi).\n" +
                        "3. Tap \"Show pairing notification\" below.\n" +
                        "4. In Wireless debugging, tap \"Pair device with pairing code\".\n" +
                        "5. Pull the notification down (the code dialog stays open), tap Enter code, type the 6 digits.\n\n" +
                        "After a restart or a Wi-Fi change, only step 2 is needed again."
                ))
                addView(button("Show pairing notification") { showPairingNotification(this@AdbSetupActivity); status.text = "Now open Wireless debugging → Pair device with pairing code." })
                addView(button("Open Developer options") {
                    runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
                })
                addView(text("\nOr in split screen with Settings: type the code (and the port shown under it) here."))
                addView(code)
                addView(port)
                addView(button("Pair") {
                    val c = code.text.toString().trim()
                    val p = port.text.toString().trim().toIntOrNull()
                    status.text = "Pairing…"
                    Thread {
                        val err = AppAdb.pair(this@AdbSetupActivity, c, p) ?: AppAdb.connect(this@AdbSetupActivity)
                        runOnUiThread { status.text = err ?: "Paired and connected. ⏺ now records your touches." }
                    }.start()
                })
                addView(button("Test the connection") { test() })
                addView(button("Forget the pairing") { AppAdb.forget(this@AdbSetupActivity); refresh() })
                addView(status)
            })
        })
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        if (!::status.isInitialized) return
        status.text = if (AppAdb.paired(this)) "Paired. ⏺ records with the app's own ADB (Wireless debugging must be on)." else "Not paired yet."
    }

    private fun test() {
        status.text = "Connecting…"
        Thread {
            val msg = AppAdb.connect(this) ?: runCatching {
                val dev = TouchLog.parseDevices(AppAdb.shell(this, "getevent -pl"))
                if (dev == null) "Connected, but no touchscreen found in getevent." else "Connected. Touchscreen: ${dev.name} (${dev.path})."
            }.getOrElse { "Connected, but the shell failed: ${it.message}" }
            runOnUiThread { status.text = msg }
        }.start()
    }

    private fun text(t: String, size: Float = 14f) = TextView(this).apply { text = t; textSize = size; setTextColor(Color.WHITE); setPadding(0, 8, 0, 8) }
    private fun button(t: String, onClick: () -> Unit) = Button(this).apply { text = t; setOnClickListener { onClick() } }

    companion object {
        private const val CHANNEL = "adb_pairing"
        private const val NOTIFICATION_ID = 7
        const val KEY_CODE = "code"

        /** A notification with a reply field for the pairing code (type it while the code dialog stays open). */
        fun showPairingNotification(context: Context, message: String = "Open Wireless debugging → Pair device with pairing code, then enter the code here") {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "ADB pairing", NotificationManager.IMPORTANCE_HIGH))
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val reply = PendingIntent.getBroadcast(context, 0, Intent(context, AdbPairReceiver::class.java), flags)
            val action = Notification.Action.Builder(null, "Enter code", reply)
                .addRemoteInput(RemoteInput.Builder(KEY_CODE).setLabel("6-digit pairing code").build())
                .build()
            val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(context)
            nm.notify(NOTIFICATION_ID, b.setSmallIcon(android.R.drawable.ic_menu_edit)
                .setContentTitle("Another Macro: pair Wireless debugging")
                .setContentText(message)
                .setStyle(Notification.BigTextStyle().bigText(message))
                .addAction(action)
                .setOnlyAlertOnce(true)
                .build())
        }

        fun done(context: Context, message: String) {
            val nm = context.getSystemService(NotificationManager::class.java)
            val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(context)
            nm.notify(NOTIFICATION_ID, b.setSmallIcon(android.R.drawable.ic_menu_edit)
                .setContentTitle("Another Macro").setContentText(message)
                .setStyle(Notification.BigTextStyle().bigText(message)).setAutoCancel(true).build())
        }
    }
}

/** Gets the pairing code typed into the notification and pairs. */
class AdbPairReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(AdbSetupActivity.KEY_CODE)?.toString()?.trim().orEmpty()
        if (code.isEmpty()) return
        AdbSetupActivity.done(context, "Pairing…")
        val pending = goAsync()
        Thread {
            try {
                val err = AppAdb.pair(context, code) ?: AppAdb.connect(context)
                if (err == null) AdbSetupActivity.done(context, "Paired. ⏺ now records your touches.")
                else AdbSetupActivity.showPairingNotification(context, "Not paired: $err")
            } finally {
                pending.finish()
            }
        }.start()
    }
}
