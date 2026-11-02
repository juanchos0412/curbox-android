package neth.iecal.curbox.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import neth.iecal.curbox.services.ProtectionGuardService
import neth.iecal.curbox.services.ServiceWatchdogJob
import neth.iecal.curbox.utils.DataStoreManager
import neth.iecal.curbox.utils.ServiceProtectionManager

/**
 * After a reboot or an app update the scheduled watchdog job and the live services are gone. This
 * re schedules the watchdog and, while protection is locked, runs one immediate repair pass and
 * starts the guard so protection comes back without the user opening the app.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ServiceWatchdogJob.schedule(appContext)
                val settings = DataStoreManager(appContext).settings.first()
                if (ServiceProtectionManager.shouldKeepAccessibilityOn(settings)) {
                    ServiceProtectionManager.healNow(appContext)
                    // Boot and package replaced broadcasts may start a foreground service.
                    ProtectionGuardService.start(appContext)
                }
            } catch (e: Exception) {
                Log.e("BootReceiver", "boot repair failed", e)
            } finally {
                pending.finish()
            }
        }
    }
}
