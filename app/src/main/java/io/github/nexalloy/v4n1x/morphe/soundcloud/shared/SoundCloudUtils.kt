package io.github.nexalloy.v4n1x.morphe.soundcloud.shared

import android.view.View
import android.view.ViewGroup
import io.github.nexalloy.isStatic
import java.util.Collections
import java.util.WeakHashMap

internal fun Class<*>.singletonInstance(): Any {
    val type = this
    return declaredFields.singleOrNull { it.isStatic && it.type == type }
        ?.apply { isAccessible = true }
        ?.get(null)
        ?: error("Singleton instance of $name not found")
}

private val listenerInstalled: MutableSet<View> =
    Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<View, Boolean>()))

internal fun View.collapse() {
    if (visibility != View.GONE) visibility = View.GONE

    val lp = layoutParams ?: return
    if (lp.width != 0 || lp.height != 0) {
        lp.width = 0
        lp.height = 0
        if (lp is ViewGroup.MarginLayoutParams) {
            lp.setMargins(0, 0, 0, 0)
        }
        layoutParams = lp
    }
}

internal fun View.keepCollapsed() {
    collapse()

    if (!listenerInstalled.add(this)) return
    addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = v.collapse()
        override fun onViewDetachedFromWindow(v: View) = Unit
    })
}
