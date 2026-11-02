package neth.iecal.curbox.blockers

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import neth.iecal.curbox.CrashLogger
import neth.iecal.curbox.R
import neth.iecal.curbox.blockers.uihider.NodeFinder
import neth.iecal.curbox.data.models.AntiUninstallConfig
import neth.iecal.curbox.services.AppBlockerService
import neth.iecal.curbox.services.BaseBlockingService
import neth.iecal.curbox.utils.AntiUninstallManager
import neth.iecal.curbox.utils.ServiceProtectionManager
import java.util.Locale


/**
 * Keeps Curbox installed and running by bouncing the user away from the screens that could undo
 * protection while it is locked: the device admin deactivation screen and the accessibility service
 * disable screens. When a timed or cooldown unlock finishes, the admin is removed so the user can
 * leave freely.
 *
 * This is the first layer only. If a screen slips through, ProtectionGuardService turns the
 * accessibility service straight back on.
 */
class AntiUninstallBlocker : BaseBlocker() {

    private companion object {
        // The AOSP activity that activates or deactivates a device admin.
        const val DEVICE_ADMIN_SCREEN_CLASS = "DeviceAdminAdd"
        const val SETTINGS_PACKAGE = "com.android.settings"
        const val ACCESSIBILITY_SHORTCUT_TITLE = "accessibility_shortcut_title"
        const val DEVICE_ADMIN_LIST_TITLE = "manage_device_admin"
        const val SCREEN_SCAN_INTERVAL_MS = 300L
        const val BOUNCE_DEBOUNCE_MS = 800L
        const val HOME_AFTER_SETTINGS_RESET_MS = 350L
        // Settings shows the start of the description on the service page. A short prefix still
        // matches when the rest is wrapped, trimmed or scrolled off screen.
        const val DESCRIPTION_MATCH_LENGTH = 40
        // A page restored from Recents can settle after its last event, so look again a few times.
        val RESCAN_DELAYS_MS = longArrayOf(250L, 700L, 1500L)
        val SERVICE_LABELS = listOf("App Blocker")
    }

    @Volatile private var config: AntiUninstallConfig = AntiUninstallConfig()
    private var lastScreenScan = 0L
    private var lastBounce = 0L
    private lateinit var service: AppBlockerService
    private var settingsJob: kotlinx.coroutines.Job? = null
    private val handler = Handler(Looper.getMainLooper())
    private val rescanRunnable = Runnable { rescanSettings() }

    // Resolving these needs binder calls, so build the labels once per blocker instance.
    private val shortcutTitle by lazy { buildShortcutTitle() }
    private val serviceLabel by lazy { accessibilityServiceLabel() }
    private val descriptionSnippet by lazy { buildDescriptionSnippet() }
    private val deviceAdminListTitle by lazy {
        settingsString(DEVICE_ADMIN_LIST_TITLE) ?: "device admin app"
    }

    private var foundCount = 0
    fun setupBlocker(service: BaseBlockingService) {
        if (service !is AppBlockerService) return
        this.service = service
        settingsJob?.cancel()
        settingsJob = CoroutineScope(Dispatchers.IO).launch {
            service.dataStoreManager.settings.collectLatest { config = it.antiUninstallConfig2 }
        }
    }

    fun onDestroy() {
        settingsJob?.cancel()
        handler.removeCallbacksAndMessages(null)
    }

    fun doAntiUninstallCheck(event: AccessibilityEvent?) {
        event ?: return
        if (!isLocked()) return

        // isAdminActive is a binder call, so only pay for it on the screens we may bounce from.
        val onAdminScreen = event.className?.toString()?.contains(DEVICE_ADMIN_SCREEN_CLASS) == true
        val inSettings = event.packageName?.toString() == SETTINGS_PACKAGE
        if (!onAdminScreen && !inSettings) return
        if (!AntiUninstallManager.isAdminActive(service)) return

        if (onAdminScreen) {
            bounce()
            return
        }

        // Bounce the moment the user taps Curbox's row in accessibility settings.
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED && clickHitsOurService(event.source)) {
            bounce()
            return
        }

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            scheduleRescans()
            if (eventNamesOurService(event)) {
                bounce()
                return
            }
        }

        val now = SystemClock.uptimeMillis()
        if (now - lastScreenScan < SCREEN_SCAN_INTERVAL_MS) return
        if (settingsShowsProtectedScreen()) {
            bounce()
        }
        lastScreenScan = SystemClock.uptimeMillis()
    }

    /** False when protection is off, and lifts it for good once a timed or cooldown unlock ends. */
    private fun isLocked(): Boolean {
        val current = config
        if (!current.isEnabled) return false
        if (AntiUninstallManager.isUnlockComplete(current)) {
            finishUnlock()
            return false
        }
        return true
    }

    private fun scheduleRescans() {
        handler.removeCallbacks(rescanRunnable)
        RESCAN_DELAYS_MS.forEach { handler.postDelayed(rescanRunnable, it) }
    }

    private fun rescanSettings() {
        try {
            if (!isLocked() || !AntiUninstallManager.isAdminActive(service)) return
            if (settingsShowsProtectedScreen()) bounce()
        } catch (e: Exception) {
            CrashLogger(service).logNonFatalError(e)
        }
    }

    /**
     * The window title of the service page is the service label, and the turn off dialog names
     * the service, so both show up in the window state event text.
     */
    private fun eventNamesOurService(event: AccessibilityEvent): Boolean {
        val isDialog = event.className?.toString()?.contains("Dialog") == true
        return event.text.any { text ->
            val value = text?.toString()?.trim() ?: return@any false
            value.equals(serviceLabel, ignoreCase = true) ||
                (isDialog && value.contains(serviceLabel, ignoreCase = true))
        }
    }

    /**
     * Looks at every Settings window on screen, not only the focused one, so a Settings page in
     * split screen or pop up view is caught as well.
     */
    private fun settingsShowsProtectedScreen(): Boolean {
        val windows = try {
            service.windows
        } catch (_: Exception) {
            null
        }
        if (windows.isNullOrEmpty()) {
            return isProtectedScreen(service.rootInActiveWindow ?: return false, null)
        }
        return windows.any { window ->
            val root = window.root ?: return@any false
            isProtectedScreen(root, window.title?.toString())
        }
    }

    /** Checks one window root and recycles it. */
    private fun isProtectedScreen(root: AccessibilityNodeInfo, windowTitle: String?): Boolean {
        try {
            if (root.packageName?.toString() != SETTINGS_PACKAGE) return false
            if (windowTitle?.trim()?.equals(serviceLabel, ignoreCase = true) == true) return true
            if (containsText(root, shortcutTitle)) return true
            if (descriptionSnippet.isNotEmpty() && containsText(root, descriptionSnippet)) return true
            return containsText(root, service.getString(R.string.app_name)) &&
                containsText(root, deviceAdminListTitle)
        } finally {
            NodeFinder.recycle(root)
        }
    }

    private fun containsText(root: AccessibilityNodeInfo, text: String): Boolean {
        if (text.isBlank()) return false
        val matches = root.findAccessibilityNodeInfosByText(text)
        val found = !matches.isNullOrEmpty()
        matches?.forEach { NodeFinder.recycle(it) }
        return found
    }

    /**
     * Resets the Settings task to its main page before going home. Without that, the protected
     * page stays at the top of the Settings task and Recents brings the user straight back to it.
     */
    private fun bounce() {
        val now = SystemClock.uptimeMillis()
        if (now - lastBounce < BOUNCE_DEBOUNCE_MS) return
        lastBounce = now
        try {
            service.startActivity(
                Intent(Settings.ACTION_SETTINGS).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                )
            )
            handler.postDelayed({ service.pressHome() }, HOME_AFTER_SETTINGS_RESET_MS)
        } catch (e: Exception) {
            service.pressBack()
            service.pressHome()
        }
    }

    /** True if the clicked node, or anything under it, is one of Curbox's accessibility services. */
    private fun clickHitsOurService(source: AccessibilityNodeInfo?): Boolean {
        source ?: return false
        try {
            return SERVICE_LABELS.any { label ->
                val matches = source.findAccessibilityNodeInfosByText(label)
                val found = !matches.isNullOrEmpty()
                matches?.forEach { NodeFinder.recycle(it) }
                found
            }
        } finally {
            NodeFinder.recycle(source)
        }
    }
    /**
     * The title Settings shows for the accessibility shortcut. The format string lives in the
     * Settings app, so read it from there to match the device language and Android version.
     */
    private fun buildShortcutTitle(): String {
        val serviceLabel = accessibilityServiceLabel()
        val settingsResources = try {
            service.packageManager.getResourcesForApplication(SETTINGS_PACKAGE)
        } catch (_: Exception) {
            null
        }
        val titleId = settingsResources?.getIdentifier(
            ACCESSIBILITY_SHORTCUT_TITLE,
            "string",
            SETTINGS_PACKAGE
        ) ?: 0
        if (settingsResources != null && titleId != 0) {
            try {
                return settingsResources.getString(titleId, serviceLabel)
            } catch (_: Exception) {
            }
        }
        // Fallback for a Settings build that does not expose the string.
        return "$serviceLabel shortcut"
    }

    /** The label Settings lists our accessibility service under. */
    private fun accessibilityServiceLabel(): String {
        return try {
            val component = ComponentName(service, AppBlockerService::class.java)
            service.packageManager.getServiceInfo(component, 0)
                .loadLabel(service.packageManager)
                .toString()
        } catch (_: Exception) {
            // The label declared on AppBlockerService in the manifest.
            "Curbox App Blocker"
        }
    }

    /** The start of the description exactly as Settings loads it, which trims the text. */
    private fun buildDescriptionSnippet(): String {
        return try {
            val description = service.serviceInfo
                ?.loadDescription(service.packageManager)
                ?.trim()
                ?: return ""
            description.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() }
                ?.take(DESCRIPTION_MATCH_LENGTH)
                ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    /** A string from the Settings app, in the device language. */
    private fun settingsString(name: String): String? {
        return try {
            val resources = service.packageManager.getResourcesForApplication(SETTINGS_PACKAGE)
            val id = resources.getIdentifier(name, "string", SETTINGS_PACKAGE)
            if (id == 0) null else resources.getString(id)
        } catch (_: Exception) {
            null
        }
    }

    private fun traverseNodesForKeywords(node: AccessibilityNodeInfo?) {
        if (node == null) {
            return
        }

        if (node.getClassName() != null && node.getClassName() == "android.widget.TextView") {
            val nodeText = node.text.toString() + node.contentDescription.toString()
            val textContent = nodeText.toString().lowercase(Locale.getDefault())
            if(textContent.contains("curbox")){
                foundCount++
            }
        }

        for (i in 0..<node.getChildCount()) {
            val childNode = node.getChild(i)
            traverseNodesForKeywords(childNode)
        }
    }

    private fun finishUnlock() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                service.dataStoreManager.updateAntiUninstallConfig {
                    it.copy(isEnabled = false, unlockRequestedAtMs = 0L)
                }
                ServiceProtectionManager.removeDeviceAdmin(service)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                CrashLogger(service).logNonFatalError(e)
            }
        }
    }
}
