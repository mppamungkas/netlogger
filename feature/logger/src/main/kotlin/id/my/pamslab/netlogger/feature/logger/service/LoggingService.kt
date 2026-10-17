package id.my.pamslab.netlogger.feature.logger.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import id.my.pamslab.netlogger.core.model.session.Scenario
import id.my.pamslab.netlogger.core.model.speedtest.SpeedTestTarget
import id.my.pamslab.netlogger.feature.logger.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

/**
 * Foreground service (type location) that owns a logging session: holds a partial wake lock for exactly its
 * lifetime and runs [LoggingEngine] until stopped. Started only from the foreground (Live screen), so no background
 * location permission is needed.
 */
@AndroidEntryPoint
class LoggingService : Service() {

    @Inject lateinit var engine: LoggingEngine

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var sessionJob: Job? = null
    private var notificationJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(
                intent.getLongExtra(EXTRA_INTERVAL_MS, DEFAULT_INTERVAL_MS),
                intent.getStringExtra(EXTRA_SCENARIO)?.let { name -> Scenario.entries.firstOrNull { it.name == name } },
            )
            ACTION_STOP -> stop()
            ACTION_MARKER -> scope.launch {
                engine.addMarker()?.let { Log.i(TAG, "marker stored at ${it.elapsedRealtimeNs}") }
            }
            // In the service scope, so the test finishes when the Live screen is left.
            ACTION_SPEED_TEST -> if (sessionJob?.isActive == true) {
                // The notification action carries no target: the user's own server.
                val target = SpeedTestTarget.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_TARGET) } ?: SpeedTestTarget.PRIMARY
                scope.launch { engine.runSpeedTest(target) }
            }
            ACTION_SPEED_TEST_STOP -> engine.stopSpeedTest()
            // A restart without our intent (e.g. after the process was killed): don't resume silently.
            else -> stop()
        }
        return START_NOT_STICKY
    }

    private fun start(intervalMs: Long, scenario: Scenario?) {
        if (sessionJob?.isActive == true) return
        val missing = REQUIRED_PERMISSIONS.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            Log.w(TAG, "logging not started, missing $missing")
            stopSelf()
            return
        }
        try {
            createChannel()
            startForeground(NOTIFICATION_ID, notification("Starting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } catch (e: RuntimeException) {
            // ForegroundServiceStartNotAllowedException / SecurityException: started from the background or
            // permission revoked between the check and here.
            Log.w(TAG, "startForeground failed", e)
            stopSelf()
            return
        }
        acquireWakeLock()
        sessionJob = scope.launch {
            try {
                engine.run(intervalMs, scenario)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "logging session failed", e)
                stop()
            }
        }
        notificationJob = scope.launch { updateNotification() }
        Log.i(TAG, "logging started, interval $intervalMs ms")
    }

    /** Waits for the session to close in the database before the wake lock and the service go away. */
    private fun stop() {
        val job = sessionJob
        sessionJob = null
        notificationJob?.cancel()
        notificationJob = null
        scope.launch(Dispatchers.Main) {
            job?.cancelAndJoin()
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    @OptIn(FlowPreview::class)
    private suspend fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        engine.state.filterIsInstance<LoggingState.Running>().sample(NOTIFICATION_UPDATE_MS).collect { s ->
            val tick = s.latest
            val gps = tick?.location?.accuracyM?.let { "GPS ±%.0f m".format(Locale.ROOT, it) } ?: "GPS ${tick?.locationStatus ?: "–"}"
            val text = "${tick?.radio?.networkType ?: "–"} · ${s.stats.recorded} ticks " +
                "(%.1f %%) · $gps · ${s.markers.size} markers".format(Locale.ROOT, s.stats.recordedPct)
            manager.notify(NOTIFICATION_ID, notification(text))
        }
    }

    private fun notification(text: String): Notification {
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_logging)
            .setContentTitle("NetLogger is logging")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(action("Stop", ACTION_STOP, 1))
            .addAction(action("Add marker", ACTION_MARKER, 2))
            .addAction(action("Speed test", ACTION_SPEED_TEST, 3))
            .build()
    }

    private fun action(title: String, action: String, requestCode: Int): Notification.Action {
        val intent = Intent(this, LoggingService::class.java).setAction(action)
        val pending = PendingIntent.getService(this, requestCode, intent, PendingIntent.FLAG_IMMUTABLE)
        return Notification.Action.Builder(null, title, pending).build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Logging", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while a logging session runs"
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NetLogger:logging")
            ?.apply {
                setReferenceCounted(false)
                // Released in stop()/onDestroy(); the timeout only guards against a leak if both are skipped.
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    companion object {
        private const val TAG = "NetLogger"
        private const val CHANNEL_ID = "logging"
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_UPDATE_MS = 5_000L
        private const val WAKE_LOCK_TIMEOUT_MS = 24 * 60 * 60 * 1000L
        const val DEFAULT_INTERVAL_MS = 1_000L

        private const val ACTION_START = "id.my.pamslab.netlogger.logging.START"
        private const val ACTION_STOP = "id.my.pamslab.netlogger.logging.STOP"
        private const val ACTION_MARKER = "id.my.pamslab.netlogger.logging.MARKER"
        private const val ACTION_SPEED_TEST = "id.my.pamslab.netlogger.logging.SPEED_TEST"
        private const val ACTION_SPEED_TEST_STOP = "id.my.pamslab.netlogger.logging.SPEED_TEST_STOP"
        private const val EXTRA_TARGET = "target"
        private const val EXTRA_INTERVAL_MS = "intervalMs"
        private const val EXTRA_SCENARIO = "scenario"

        private val REQUIRED_PERMISSIONS = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.READ_PHONE_STATE,
        )

        /** Call from the foreground only (a location FGS cannot be started from the background). */
        fun start(context: Context, scenario: Scenario?, intervalMs: Long = DEFAULT_INTERVAL_MS) {
            context.startForegroundService(
                Intent(context, LoggingService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_INTERVAL_MS, intervalMs)
                    .putExtra(EXTRA_SCENARIO, scenario?.name),
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, LoggingService::class.java).setAction(ACTION_STOP))
        }

        fun addMarker(context: Context) {
            context.startService(Intent(context, LoggingService::class.java).setAction(ACTION_MARKER))
        }

        /** Stops a running speed test; its finished phases are kept. */
        fun stopSpeedTest(context: Context) {
            context.startService(Intent(context, LoggingService::class.java).setAction(ACTION_SPEED_TEST_STOP))
        }

        /** Runs a speed test in the logging session; ignored when not logging. */
        fun speedTest(context: Context, target: SpeedTestTarget = SpeedTestTarget.PRIMARY) {
            context.startService(
                Intent(context, LoggingService::class.java).setAction(ACTION_SPEED_TEST).putExtra(EXTRA_TARGET, target.name),
            )
        }
    }
}
