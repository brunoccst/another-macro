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

    // --- dev builds: CI publishes every push's APK as a pre-release "dev-<branch>" -----------

    /** The branch whose CI builds this phone follows ("main" unless Claude picked another for a test). */
    fun channel(c: Context): String = c.getSharedPreferences("updates", Context.MODE_PRIVATE).getString("channel", BuildConfig.BRANCH.ifEmpty { "main" })!!
    fun setChannel(c: Context, branch: String) = c.getSharedPreferences("updates", Context.MODE_PRIVATE).edit().putString("channel", branch).apply()

    /** "claude/project-thread-x" -> "dev-claude-project-thread-x": the pre-release CI keeps for a branch. */
    fun devTag(branch: String) = "dev-" + branch.trim().replace(Regex("[^A-Za-z0-9._-]"), "-")

    /** The newest CI build of [branch] if its versionCode is above this build's, else null. Blocking. */
    fun checkDev(branch: String): Release? {
        val conn = (URL("https://api.github.com/repos/$REPO/releases/tags/${devTag(branch)}").openConnection() as HttpURLConnection).apply {
            setRequestProperty("Accept", "application/vnd.github+json")
            connectTimeout = 10000
            readTimeout = 15000
        }
        try {
            if (conn.responseCode == 404) throw IllegalStateException("no CI build published for branch '$branch' yet")
            if (conn.responseCode != 200) throw IllegalStateException("GitHub answered ${conn.responseCode}")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val body = json.optString("body")
            val code = Regex("""versionCode:\s*(\d+)""").find(body)?.groupValues?.get(1)?.toLongOrNull()
                ?: throw IllegalStateException("the build's notes carry no versionCode")
            val name = Regex("""versionName:\s*(\S+)""").find(body)?.groupValues?.get(1) ?: code.toString()
            val assets = json.getJSONArray("assets")
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.getString("name").endsWith(".apk") } ?: throw IllegalStateException("the build has no APK")
            val release = Release(name, apk.getString("browser_download_url"), body, json.optString("html_url"))
            return if (code > BuildConfig.VERSION_CODE.toLong()) release else null
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Until when (ms) the app may confirm its own update and its own screen-capture request on
     * the user's behalf (the accessibility service presses Update / Start now), and that after the
     * restart it should turn capture and the connector back on. Set only by [update].
     */
    fun resumeUntil(c: Context) = c.getSharedPreferences("updates", Context.MODE_PRIVATE).getLong("resume_until", 0)
    fun clearResume(c: Context) = c.getSharedPreferences("updates", Context.MODE_PRIVATE).edit().remove("resume_until").remove("auto_confirm_until").apply()

    /**
     * Until when (ms) the accessibility service may press the installer's Update and Play Protect's
     * "Got it" by itself. Only for an update nobody is watching (Claude's update_app or the automatic
     * check), never for one the user started: then every dialog is the user's.
     */
    fun autoConfirmUntil(c: Context) = c.getSharedPreferences("updates", Context.MODE_PRIVATE).getLong("auto_confirm_until", 0)

    /** Play Protect blocked [version]: stop pressing anything and don't retry it by itself (the user installs it). */
    fun blocked(c: Context, version: String?) {
        val e = c.getSharedPreferences("updates", Context.MODE_PRIVATE).edit()
            .remove("auto_confirm_until").remove("resume_until")
        if (version != null) e.putString("blocked_version", version)
        e.apply()
    }
    fun blockedVersion(c: Context): String? = c.getSharedPreferences("updates", Context.MODE_PRIVATE).getString("blocked_version", null)

    /** Check [branch]'s CI build and, if newer, install it with no taps. Blocking. A message for the log/Claude. */
    fun update(c: Context, branch: String, userStarted: Boolean = false): String {
        setChannel(c, branch)
        val r = checkDev(branch) ?: return "up to date: this is already the newest build of '$branch' (${BuildConfig.VERSION_NAME})"
        if (!canInstall(c)) {
            openInstallPermission(c)
            return "the phone doesn't let Another Macro install apps yet: the user must allow \"Install unknown apps\" for it once (the settings page is open on the phone)"
        }
        c.getSharedPreferences("updates", Context.MODE_PRIVATE).edit()
            .putLong("resume_until", System.currentTimeMillis() + 4 * 60_000L)
            .putLong("auto_confirm_until", if (userStarted) 0L else System.currentTimeMillis() + 4 * 60_000L)
            .putString("installing_version", r.version)
            .putBoolean("resume_connector", PhoneConnector.isOn)
            .apply()
        downloadAndInstall(c, r)
        return "installing ${r.version} (was ${BuildConfig.VERSION_NAME}): the app restarts, turns screen capture and the connector back on by itself and opens the game (about a minute)"
    }

    fun resumeConnector(c: Context) = c.getSharedPreferences("updates", Context.MODE_PRIVATE).getBoolean("resume_connector", true)

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
                else -> {
                    val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    MacroLog.e("update failed ($status): $message")
                    // Play Protect said no: the automatic check must not loop on it (each try pops the
                    // dialog again and closes it); the user installs this one with "Install anyway"
                    if (message?.contains("VERIFICATION", ignoreCase = true) == true || status == PackageInstaller.STATUS_FAILURE_BLOCKED) {
                        val v = context.getSharedPreferences("updates", Context.MODE_PRIVATE).getString("installing_version", null)
                        blocked(context, v)
                        MacroLog.i("Play Protect blocked $v: no more automatic tries; install it from System → Updates and choose Install anyway")
                    }
                }
            }
        }
    }
}
