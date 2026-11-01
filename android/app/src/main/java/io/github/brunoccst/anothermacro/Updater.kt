package io.github.brunoccst.anothermacro

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Auto-update from GitHub Releases: the latest release's APK asset is compared
 * with the installed version and, if newer, downloaded and handed to Android's
 * PackageInstaller. Android asks the user to confirm (one tap); on Android 12+
 * an app updating itself may be installed without asking. All releases are signed
 * with the same key, so the update keeps the app's settings and permissions.
 */
object Updater {
    private const val REPO = "brunoccst/another-macro"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"

    data class Release(val version: String, val apkUrl: String, val notes: String, val page: String)

    /** The latest release if it is newer than this build, else null. Blocking: call off the main thread. */
    fun checkNewer(): Release? {
        val conn = (URL(API).openConnection() as HttpURLConnection).apply {
            setRequestProperty("Accept", "application/vnd.github+json")
            connectTimeout = 10000
            readTimeout = 15000
        }
        try {
            if (conn.responseCode == 404) return null // no release published yet
            if (conn.responseCode != 200) throw IllegalStateException("GitHub answered ${conn.responseCode}")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val version = json.getString("tag_name").removePrefix("v")
            val assets = json.getJSONArray("assets")
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.getString("name").endsWith(".apk") } ?: return null
            val release = Release(version, apk.getString("browser_download_url"), json.optString("body"), json.optString("html_url"))
            return if (isNewer(version, BuildConfig.VERSION_NAME)) release else null
        } finally {
            conn.disconnect()
        }
    }

    /** Semantic version compare; dev builds ("0.0.0-dev") are older than any release. */
    fun isNewer(candidate: String, installed: String): Boolean {
        fun parts(v: String) = Regex("""^(\d+)\.(\d+)\.(\d+)""").find(v)?.destructured
            ?.let { (a, b, c) -> listOf(a.toInt(), b.toInt(), c.toInt()) } ?: listOf(0, 0, 0)
        val a = parts(candidate)
        val b = parts(installed)
        for (i in 0..2) if (a[i] != b[i]) return a[i] > b[i]
        return false
    }

    /** Android must allow this app to install packages ("Install unknown apps"). */
    fun canInstall(context: Context) =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Download [release]'s APK and start installing it. Blocking: call off the main thread. */
    fun downloadAndInstall(context: Context, release: Release) {
        MacroLog.i("downloading update ${release.version}…")
        val file = File(context.cacheDir, "update.apk")
        val conn = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15000
            readTimeout = 60000
        }
        try {
            conn.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
        } finally {
            conn.disconnect()
        }
        MacroLog.i("installing update ${release.version} (${file.length() / 1_000_000} MB)")
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("another-macro.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val callback = PendingIntent.getBroadcast(context, id, Intent(context, InstallResult::class.java), flags)
            session.commit(callback.intentSender)
        }
    }

    /** Install progress: shows Android's confirmation when needed, logs the result. */
    class InstallResult : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { context.startActivity(it) }
                }
                PackageInstaller.STATUS_SUCCESS -> MacroLog.i("update installed")
                else -> MacroLog.e("update failed ($status): ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
            }
        }
    }
}
