package neth.iecal.curbox.utils

import android.app.Dialog
import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.view.View
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.ChipGroup

/**
 * Android TV and Fire TV are driven with a remote, have no touchscreen and lack several phone
 * features, so screens and blockers check this before offering them.
 */
object TvUtils {

    private const val FIRE_TV_FEATURE = "amazon.hardware.fire_tv"

    fun isTv(context: Context): Boolean {
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        if (uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true
        val pm = context.packageManager
        return pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            pm.hasSystemFeature(FIRE_TV_FEATURE)
    }

    /**
     * A landscape TV opens bottom sheets half way and a remote cannot drag them up, which hides
     * the lower settings. Call from a BottomSheetDialogFragment's onCreateDialog.
     */
    fun expandBottomSheetOnTv(dialog: Dialog) {
        if (!isTv(dialog.context)) return
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    /**
     * Picking a chip with the remote sends focus to the first focusable view on screen, which
     * loses the user's place. Call from a ChipGroup's checked state listener to put it back.
     */
    fun keepFocusOnCheckedChip(group: ChipGroup, checkedIds: List<Int>) {
        val checkedId = checkedIds.firstOrNull() ?: return
        if (!isTv(group.context)) return
        group.post { group.findViewById<View>(checkedId)?.requestFocus() }
    }
}
