package io.github.brunoccst.anothermacro

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/**
 * Run a command in Termux (its RUN_COMMAND service), for what only ADB can do: reading the
 * touchscreen while a run is recorded (`another-macro record-touches`, see touch_recorder.py).
 *
 * Needs, once:
 *  - this app's "Run commands in Termux environment" permission (asked by the app),
 *  - `allow-external-apps = true` in Termux's ~/.termux/termux.properties,
 *  - another-macro installed in Termux and ADB paired (`another-macro connect`).
 */
object Termux {
    const val PACKAGE = "com.termux"
    const val PERMISSION = "com.termux.permission.RUN_COMMAND"
    /** MainActivity extra: ask for [PERMISSION]. */
    const val EXTRA_ASK = "ask_termux_permission"
    private const val BASH = "/data/data/com.termux/files/usr/bin/bash"
    private const val HOME = "/data/data/com.termux/files/home"

    fun installed(context: Context) = runCatching { context.packageManager.getPackageInfo(PACKAGE, 0) }.isSuccess

    fun allowed(context: Context) = context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Open the app to ask for the permission (a service can't ask). */
    fun ask(context: Context) {
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_ASK, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }

    /** Run [command] with bash -lc in the background; false if Termux refused to take it. */
    fun run(context: Context, command: String): Boolean {
        val intent = Intent("com.termux.RUN_COMMAND")
            .setClassName(PACKAGE, "com.termux.app.RunCommandService")
            .putExtra("com.termux.RUN_COMMAND_PATH", BASH)
            .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", command))
            .putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME)
            .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        return runCatching {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
            MacroLog.i("termux: $command")
            true
        }.onFailure { MacroLog.e("could not run a command in Termux", it) }.getOrDefault(false)
    }

    /** Single-quote [s] for the shell. */
    fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"
}
