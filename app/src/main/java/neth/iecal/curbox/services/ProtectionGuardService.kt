package neth.iecal.curbox.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import neth.iecal.curbox.R
import neth.iecal.curbox.ui.activity.FragmentActivity
import neth.iecal.curbox.ui.fragments.main.reducers.advanced.ServiceProtectionFragment
import neth.iecal.curbox.utils.DataStoreManager
import neth.iecal.curbox.utils.ServiceProtectionManager

/**
 * Keeps the accessibility service switched on while protection is locked. It lives in the main
 * process, so it survives the accessibility service being unbound, and it reacts to the secure
 * setting changing instead of waiting for the periodic watchdog. A switch flipped off in Settings
 * is put back within a fraction of a second.
 *
 * It also catches the service being listed as enabled while the system no longer has it bound,
 * and turns it off and on again to recover.
 */
class ProtectionGuardService : Service() {

    companion object {
        private const val TAG = "ProtectionGuard"
        private const val CHANNEL_ID = "protection_guard_channel"
        private const val NOTIFICATION_ID = 7713
        private const val CHECK_INTERVAL_MS = 15_000L
        private const val CHANGE_DEBOUNCE_MS = 150L

        // A service that is mid bind looks unbound for a moment, so wait for two failed checks.
        private const val UNBOUND_STRIKES_TO_RESTART = 2
        private const val RESTART_COOLDOWN_MS = 5 * 60_000L

        /** Starts the guard if protection asks for it. Safe to call from anywhere, any time. */
        fun startIfNeeded(context: Context) {
            val appContext = context.applicationContext
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val settings = DataStoreManager(appContext).settings.first()
                    if (ServiceProtectionManager.shouldKeepAccessibilityOn(settings)) {
                        start(appContext)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to read settings", e)
                }
            }
        }

        fun start(context: Context) {
            try {
                val intent = Intent(context, ProtectionGuardService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                // Background start limits can refuse this; the next entry point tries again.
                Log.e(TAG, "Failed to start guard", e)
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var workerThread: HandlerThread
    private lateinit var worker: Handler
    private lateinit var dataStoreManager: DataStoreManager

    @Volatile private var isGuarding = false
    private var unboundStrikes = 0
    private var lastRestartAtMs = 0L

    private val checkRunnable = Runnable { checkAndRepair() }
    private val periodicRunnable = object : Runnable {
        override fun run() {
            checkAndRepair()
            worker.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    private val settingsObserver by lazy {
        object : ContentObserver(worker) {
            override fun onChange(selfChange: Boolean) {
                worker.removeCallbacks(checkRunnable)
                worker.postDelayed(checkRunnable, CHANGE_DEBOUNCE_MS)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        dataStoreManager = DataStoreManager(this)
        workerThread = HandlerThread(TAG).also { it.start() }
        worker = Handler(workerThread.looper)

        val resolver = contentResolver
        listOf(
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            Settings.Secure.ACCESSIBILITY_ENABLED
        ).forEach { key ->
            resolver.registerContentObserver(Settings.Secure.getUriFor(key), false, settingsObserver)
        }

        scope.launch {
            dataStoreManager.settings.collect { settings ->
                isGuarding = ServiceProtectionManager.shouldKeepAccessibilityOn(settings)
                if (isGuarding) {
                    worker.post(checkRunnable)
                } else {
                    stopSelf()
                }
            }
        }
        worker.postDelayed(periodicRunnable, CHECK_INTERVAL_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        return START_STICKY
    }

    private fun checkAndRepair() {
        if (!isGuarding) return
        try {
            ServiceProtectionManager.removeFromAccessibilityShortcuts(this)
            if (!ServiceProtectionManager.isAppBlockerEnabled(this)) {
                unboundStrikes = 0
                ServiceProtectionManager.restoreAccessibilityService(this)
                return
            }
            if (ServiceProtectionManager.isAppBlockerBound(this)) {
                unboundStrikes = 0
                return
            }
            unboundStrikes++
            val now = SystemClock.elapsedRealtime()
            val cooldownOver = lastRestartAtMs == 0L || now - lastRestartAtMs >= RESTART_COOLDOWN_MS
            if (unboundStrikes >= UNBOUND_STRIKES_TO_RESTART && cooldownOver) {
                if (ServiceProtectionManager.restartAccessibilityService(this)) {
                    lastRestartAtMs = SystemClock.elapsedRealtime()
                    unboundStrikes = 0
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Guard check failed", e)
        }
    }

    private fun startAsForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.protection_guard_channel_name),
                NotificationManager.IMPORTANCE_MIN
            )
        )
        val openIntent = Intent(this, FragmentActivity::class.java).apply {
            putExtra("fragment", ServiceProtectionFragment.FRAGMENT_ID)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            this, NOTIFICATION_ID, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.protection_guard_notification_title))
            .setContentText(getString(R.string.protection_guard_notification_text))
            .setSmallIcon(R.drawable.icon)
            .setContentIntent(pending)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            contentResolver.unregisterContentObserver(settingsObserver)
        } catch (_: Exception) {
        }
        scope.cancel()
        worker.removeCallbacksAndMessages(null)
        workerThread.quitSafely()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
