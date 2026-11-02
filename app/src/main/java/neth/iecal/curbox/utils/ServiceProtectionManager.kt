package neth.iecal.curbox.utils

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityManager
import kotlinx.coroutines.flow.first
import neth.iecal.curbox.BuildConfig
import neth.iecal.curbox.data.models.Settings as CurboxSettings
import neth.iecal.curbox.receivers.AdminReceiver
import neth.iecal.curbox.services.AppBlockerService

/**
 * The brain behind the Service Protection screen and the watchdog. It checks how well protected the
 * service is and, when Shizuku is available, repairs the things an OEM battery manager tends to
 * break: it turns the accessibility service back on and re keeps the app out of doze and background
 * limits.
 *
 * Nothing here can make the app truly un killable. A force stop still wins. The goal is only to make
 * the service hard to kill and fast to bring back.
 */
object ServiceProtectionManager {

    private const val TAG = "ServiceProtection"

    fun isAppBlockerEnabled(context: Context): Boolean =
        PermissionUtils.isAccessibilityServiceEnabled(context, AppBlockerService::class.java)

    /** Shizuku is the only thing that lets us silently turn services back on, so it gates self healing. */
    fun canSelfHeal(): Boolean =
        PermissionUtils.isShizukuAvailable() && PermissionUtils.hasShizukuPermission()

    /**
     * True when Curbox can put its accessibility service back on by itself. WRITE_SECURE_SETTINGS
     * survives reboots and needs no running Shizuku, so it is tried first.
     */
    fun canRepairAccessibility(context: Context): Boolean =
        PermissionUtils.hasWriteSecureSettings(context) || canSelfHeal()

    /**
     * Whether the accessibility service must be kept on: service protection is on, or tamper
     * protection is locked and no timed or cooldown unlock has finished.
     */
    fun shouldKeepAccessibilityOn(settings: CurboxSettings): Boolean {
        val antiUninstall = settings.antiUninstallConfig2
        val tamperLocked = BuildConfig.SUPPORTS_ANTI_UNINSTALL && antiUninstall.isEnabled &&
            !AntiUninstallManager.isUnlockComplete(antiUninstall)
        return settings.serviceProtectionConfig.isEnabled || tamperLocked
    }

    /**
     * True when the system has actually bound the service, not only listed it as enabled. A
     * service can stay listed after its connection died, which leaves blocking silently off.
     */
    fun isAppBlockerBound(context: Context): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val ours = appBlockerComponent(context)
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { ComponentName.unflattenFromString(it.id) == ours }
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Intent that lets the user drop battery optimization for the app. When we hold the
     * REQUEST_IGNORE_BATTERY_OPTIMIZATIONS permission (full and fdroid flavors) we can show the one
     * tap allow dialog. The playstore flavor ships without that permission, so we open the battery
     * optimization list instead, which needs no permission.
     */
    fun ignoreBatteryOptimizationsIntent(context: Context): Intent =
        if (canRequestIgnoreBatteryOptimizations(context)) {
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}")
            )
        } else {
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        }

    /** True only when the permission is declared in the merged manifest (install time grant). */
    private fun canRequestIgnoreBatteryOptimizations(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Checks both services and, if either is off and Shizuku can help, turns them back on. Always
     * re applies the doze and background exemptions so they survive system house keeping.
     *
     * Safe to call from anywhere. The actual shell work runs on its own thread inside [ShizukuRunner].
     */
    fun healNow(context: Context) {
        try {
            reinforceBackgroundExecution(context)
            if (!isAppBlockerEnabled(context)) {
                restoreAccessibilityService(context)
            }
        } catch (e: Exception) {
            Log.e(TAG, "healNow failed", e)
        }
    }

    /**
     * Adds the Curbox accessibility service back into the secure settings list. Writes the
     * setting directly when WRITE_SECURE_SETTINGS is held, otherwise goes through Shizuku.
     * Returns false when neither path is available.
     */
    fun restoreAccessibilityService(context: Context): Boolean {
        val ours = appBlockerComponent(context)
        val existing = readComponentList(context, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        if (existing.none { ComponentName.unflattenFromString(it) == ours }) {
            existing.add(ours.flattenToString())
        }
        val merged = existing.joinToString(":")

        if (PermissionUtils.hasWriteSecureSettings(context)) {
            try {
                Settings.Secure.putString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    merged
                )
                Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                Log.i(TAG, "Re enabled accessibility service via secure settings")
                return true
            } catch (e: SecurityException) {
                Log.e(TAG, "WRITE_SECURE_SETTINGS was revoked", e)
            }
        }
        if (!canSelfHeal()) return false
        runShell(
            "settings put secure enabled_accessibility_services '$merged'; " +
                "settings put secure accessibility_enabled 1"
        )
        Log.i(TAG, "Re enabled accessibility service via Shizuku")
        return true
    }

    /**
     * Turns the service off and on again, the same as the user flipping its switch twice. Used
     * when the service is listed as enabled but the system no longer has it bound. Blocks the
     * calling thread for a moment, so never call it on the main thread.
     */
    fun restartAccessibilityService(context: Context): Boolean {
        if (!PermissionUtils.hasWriteSecureSettings(context)) return false
        val ours = appBlockerComponent(context)
        return try {
            val without = readComponentList(context, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                .filterNot { ComponentName.unflattenFromString(it) == ours }
            Settings.Secure.putString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                without.joinToString(":")
            )
            // Gives the system time to drop the dead connection before binding again.
            Thread.sleep(900)
            restoreAccessibilityService(context)
        } catch (e: Exception) {
            Log.e(TAG, "restart failed", e)
            false
        }
    }

    /**
     * Removes Curbox from the accessibility shortcuts (volume keys, the floating button, quick
     * settings), which can switch the service off without opening Settings.
     */
    fun removeFromAccessibilityShortcuts(context: Context) {
        if (!PermissionUtils.hasWriteSecureSettings(context)) return
        val ours = appBlockerComponent(context)
        SHORTCUT_TARGET_KEYS.forEach { key ->
            try {
                val targets = readComponentList(context, key)
                val kept = targets.filterNot { ComponentName.unflattenFromString(it) == ours }
                if (kept.size != targets.size) {
                    Settings.Secure.putString(context.contentResolver, key, kept.joinToString(":"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clean shortcut $key", e)
            }
        }
    }

    private val SHORTCUT_TARGET_KEYS = listOf(
        "accessibility_shortcut_target_service",
        "accessibility_button_targets",
        "accessibility_qs_targets",
        "accessibility_gesture_targets"
    )

    private fun appBlockerComponent(context: Context): ComponentName =
        ComponentName(context, AppBlockerService::class.java)

    private fun readComponentList(context: Context, key: String): LinkedHashSet<String> {
        val current = Settings.Secure.getString(context.contentResolver, key)
        val existing = linkedSetOf<String>()
        if (!current.isNullOrEmpty()) {
            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(current)
            while (splitter.hasNext()) {
                val entry = splitter.next().trim()
                if (entry.isNotEmpty()) existing.add(entry)
            }
        }
        return existing
    }

    /** Keeps the app out of doze and lets it run freely in the background. Needs Shizuku. */
    fun reinforceBackgroundExecution(context: Context) {
        if (!canSelfHeal()) return
        val pkg = context.packageName
        runShell(
            "dumpsys deviceidle whitelist +$pkg; " +
                "cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow; " +
                "cmd appops set $pkg RUN_IN_BACKGROUND allow"
        )
    }

    /**
     * The strongest tier. Makes Curbox a device owner, which survives force stop and blocks uninstall
     * outright. Only works on a device with no added accounts and is hard to undo, so it stays behind
     * a clear warning in the UI. Needs Shizuku.
     */
    fun setDeviceOwner(context: Context) {
        if (!canSelfHeal()) return
        val admin = ComponentName(context, AdminReceiver::class.java).flattenToString()
        runShell("dpm set-device-owner '$admin'")
    }

    fun isDeviceOwner(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isDeviceOwnerApp(context.packageName)
    }

    /** Returns false when tamper protection still requires the device admin. */
    @Suppress("DEPRECATION")
    suspend fun removeDeviceAdmin(context: Context): Boolean {
        if (!BuildConfig.SUPPORTS_ANTI_UNINSTALL) return false
        if (DataStoreManager(context).settings.first().antiUninstallConfig2.isEnabled) return false

        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (dpm.isDeviceOwnerApp(context.packageName)) {
            dpm.clearDeviceOwnerApp(context.packageName)
        } else {
            AntiUninstallManager.removeProtection(context)
        }
        return true
    }

    private fun runShell(command: String) {
        ShizukuRunner.executeCommand(command, object : ShizukuRunner.CommandResultListener {
            override fun onCommandError(error: String) {
                Log.e(TAG, "shell error: $error")
            }
        })
    }
}
