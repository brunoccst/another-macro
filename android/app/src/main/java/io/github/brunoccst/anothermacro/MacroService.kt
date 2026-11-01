package io.github.brunoccst.anothermacro

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import org.opencv.android.OpenCVLoader
import kotlin.math.max
import kotlin.math.min

/**
 * Foreground service holding the screen capture (MediaProjection needs one) and
 * running macros on a worker thread. Its notification has a Stop button.
 */
class MacroService : Service() {

    private var projection: MediaProjection? = null
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!OpenCVLoader.initLocal()) MacroLog.e("OpenCV failed to load")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_CAPTURE -> startCapture(intent)
            ACTION_RUN -> runMacro(intent)
            ACTION_STOP_RUN -> { Runner.requestStop(); MacroLog.i("stop requested") }
            ACTION_PAUSE -> Runner.setPaused(!Runner.isPaused)
            ACTION_RESTART -> restart()
            ACTION_SHUTDOWN -> shutdown()
        }
        return START_NOT_STICKY
    }

    private fun startCapture(intent: Intent) {
        // the foreground service (type mediaProjection) must be running before the projection is created
        val notification = notification("Screen capture on — ready to run macros")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val data: Intent = intent.getParcelableExtra(EXTRA_RESULT_DATA) ?: return
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        capture?.release(); capture = null
        projection?.stop()
        val p = mpm.getMediaProjection(code, data) ?: run { MacroLog.e("screen capture was not granted"); return }
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                MacroLog.i("screen capture stopped")
                Runner.requestStop()
                capture?.release(); capture = null
            }
        }, Handler(Looper.getMainLooper()))
        projection = p
        val display = (getSystemService(DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        // the game is landscape-only: capture at long side x short side
        val w = max(metrics.widthPixels, metrics.heightPixels)
        val h = min(metrics.widthPixels, metrics.heightPixels)
        capture = Capture(p, w, h, metrics.densityDpi)
        MacroLog.i("screen capture on (${w}x$h)")
        InputService.instance?.showControls(true)
    }

    /** Stop the running macro (if any) and start the last one again with the same params. */
    private fun restart() {
        Runner.requestStop()
        Thread {
            val w = worker
            if (w != null && w.isAlive) w.join(15000)
            val last = LastRun.load(this) ?: run { MacroLog.i("nothing to restart yet"); return@Thread }
            startService(LastRun.intent(this, last.first, last.second))
        }.start()
    }

    private fun runMacro(intent: Intent) {
        val cap = capture ?: run { MacroLog.i("allow screen capture first"); return }
        if (Runner.isRunning || worker?.isAlive == true) { MacroLog.i("a macro is already running"); return }
        val name = intent.getStringExtra(EXTRA_SCENARIO) ?: return
        val params = HashMap<String, String>()
        intent.getBundleExtra(EXTRA_PARAMS)?.let { b -> b.keySet().forEach { k -> b.getString(k)?.let { params[k] = it } } }
        val scenario = try { Scenarios.get(name) } catch (e: Exception) { MacroLog.e(e.message ?: "unknown macro"); return }
        LastRun.save(this, name, params)
        updateNotification("Running ${scenario.name}")
        worker = Thread({
            val reason = Runner.run(scenario, params, cap, assets)
            updateNotification("Stopped: $reason")
        }, "macro").apply { start() }
    }

    private fun shutdown() {
        Runner.requestStop()
        InputService.instance?.showControls(false)
        capture?.release(); capture = null
        projection?.stop(); projection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        Runner.requestStop()
        capture?.release(); capture = null
        projection?.stop(); projection = null
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Macros", NotificationManager.IMPORTANCE_LOW))
        }
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MacroService::class.java).setAction(ACTION_STOP_RUN),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Another Macro")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop macro", stop).build())
            .build()
    }

    private fun updateNotification(text: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification(text))
    }

    companion object {
        const val ACTION_START_CAPTURE = "start_capture"
        const val ACTION_RUN = "run"
        const val ACTION_STOP_RUN = "stop_run"
        const val ACTION_SHUTDOWN = "shutdown"
        const val ACTION_PAUSE = "pause"
        const val ACTION_RESTART = "restart"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_SCENARIO = "scenario"
        const val EXTRA_PARAMS = "params"
        private const val CHANNEL = "macros"
        private const val NOTIFICATION_ID = 1

        @Volatile
        var capture: Capture? = null
            private set

        fun intent(context: Context, action: String) = Intent(context, MacroService::class.java).setAction(action)
    }
}

/** The last macro started and its params, so the overlay can start/restart it without the app. */
object LastRun {
    private const val PREFS = "last_run"

    fun save(context: Context, name: String, params: Map<String, String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("name", name)
            .putString("params", params.entries.joinToString("\n") { "${it.key}=${it.value}" })
            .apply()
    }

    fun load(context: Context): Pair<String, Map<String, String>>? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val name = prefs.getString("name", null) ?: return null
        val params = prefs.getString("params", "").orEmpty().lines().mapNotNull {
            val kv = it.split("=", limit = 2)
            if (kv.size == 2) kv[0] to kv[1] else null
        }.toMap()
        return name to params
    }

    fun intent(context: Context, name: String, params: Map<String, String>): Intent {
        val bundle = android.os.Bundle().apply { params.forEach { (k, v) -> putString(k, v) } }
        return MacroService.intent(context, MacroService.ACTION_RUN)
            .putExtra(MacroService.EXTRA_SCENARIO, name)
            .putExtra(MacroService.EXTRA_PARAMS, bundle)
    }
}
