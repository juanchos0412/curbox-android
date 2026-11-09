package neth.iecal.curbox.ui.views

import android.content.Context
import android.util.AttributeSet
import android.widget.HorizontalScrollView

/**
 * Holds decorative art wider than the screen. HorizontalScrollView makes itself focusable in its
 * constructor, ignoring the layout attribute, which leaves a dead focus stop for a TV remote.
 */
class DecorativeHorizontalScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : HorizontalScrollView(context, attrs) {

    init {
        isFocusable = false
        isFocusableInTouchMode = false
    }
}
