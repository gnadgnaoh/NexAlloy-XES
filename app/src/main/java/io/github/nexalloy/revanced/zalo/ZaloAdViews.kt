package io.github.nexalloy.revanced.zalo

import android.view.View
import android.view.ViewGroup
import java.util.Collections
import java.util.WeakHashMap

private val listenerInstalled: MutableSet<View> =
    Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<View, Boolean>()))

internal fun View.collapseAsAd() {
    if (visibility != View.GONE) visibility = View.GONE

    val lp = layoutParams ?: return
    if (lp.height != 0) {
        lp.height = 0
        if (lp is ViewGroup.MarginLayoutParams) {
            lp.topMargin = 0
            lp.bottomMargin = 0
        }
        layoutParams = lp
    }
}

internal fun View.keepHiddenAsAd() {
    collapseAsAd()

    if (!listenerInstalled.add(this)) return
    addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = v.collapseAsAd()
        override fun onViewDetachedFromWindow(v: View) = Unit
    })
}
