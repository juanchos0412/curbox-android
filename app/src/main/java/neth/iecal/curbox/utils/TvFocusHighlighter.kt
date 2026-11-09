package neth.iecal.curbox.utils

import android.app.Activity
import android.app.Application
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import com.google.android.material.color.MaterialColors
import com.google.android.material.shape.Shapeable
import neth.iecal.curbox.R

/**
 * Draws a ring around the focused view on TV. Material components only tint their state layer on
 * focus, which is too faint to follow with a remote from across the room.
 */
object TvFocusHighlighter {

    private const val RING_WIDTH_DP = 3
    private const val DEFAULT_RADIUS_DP = 12
    private const val FULL_SCREEN_RATIO = 0.9f

    fun register(application: Application) {
        if (!TvUtils.isTv(application)) return
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            // onStart runs after setContentView, so touching the decor view cannot break
            // window feature requests made in onCreate.
            override fun onActivityStarted(activity: Activity) = attach(activity)
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    private fun attach(activity: Activity) {
        val decor = activity.window?.decorView ?: return
        if (decor.getTag(R.id.tv_focus_highlighter) != null) return
        decor.setTag(R.id.tv_focus_highlighter, true)

        val density = activity.resources.displayMetrics.density
        val ring = GradientDrawable().apply {
            setColor(android.graphics.Color.TRANSPARENT)
            setStroke(
                (RING_WIDTH_DP * density).toInt(),
                MaterialColors.getColor(decor, com.google.android.material.R.attr.colorPrimary)
            )
        }
        // Keeps the ring matched to the focused view when it resizes, such as a card expanding.
        val resizeListener = View.OnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            ring.setBounds(0, 0, view.width, view.height)
        }

        // The focus callback's old view can be null or a different view when focus is cleared or
        // views rebuild, so remember where the ring actually is.
        var ringedView: View? = null

        decor.viewTreeObserver.addOnGlobalFocusChangeListener { _, newFocus ->
            try {
                ringedView?.overlay?.remove(ring)
                ringedView?.removeOnLayoutChangeListener(resizeListener)
                ringedView = null
                newFocus ?: return@addOnGlobalFocusChangeListener
                // A pager or list holding focus itself would get a ring around the whole screen.
                if (newFocus.width >= decor.width * FULL_SCREEN_RATIO &&
                    newFocus.height >= decor.height * FULL_SCREEN_RATIO
                ) return@addOnGlobalFocusChangeListener
                ring.cornerRadius = cornerRadiusOf(newFocus, density)
                ring.setBounds(0, 0, newFocus.width, newFocus.height)
                newFocus.overlay.add(ring)
                newFocus.addOnLayoutChangeListener(resizeListener)
                ringedView = newFocus
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // Material 3 buttons and cards take their corners from the shape appearance, not cornerRadius.
    private fun cornerRadiusOf(view: View, density: Float): Float = when (view) {
        is Shapeable -> view.shapeAppearanceModel.topLeftCornerSize
            .getCornerSize(RectF(0f, 0f, view.width.toFloat(), view.height.toFloat()))
        else -> DEFAULT_RADIUS_DP * density
    }
}
