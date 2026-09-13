package io.github.nexalloy.mrxsin.gmail.ads

import android.view.View
import android.view.ViewGroup
import app.morphe.extension.shared.Logger
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch

val HideAds = patch(
    name = "Hide ads",
    description = "Removes the sponsored rows Gmail renders between conversations.",
) {
    val constructors = ::adViewConstructors.dexMethodList
    check(constructors.isNotEmpty()) { "No ad view constructor found in $AD_PACKAGE" }

    val hooked = constructors.mapNotNull { dexMethod ->
        val constructor = runCatching { dexMethod.toConstructor() }.getOrNull()
            ?: return@mapNotNull null
        if (!View::class.java.isAssignableFrom(constructor.declaringClass)) {
            return@mapNotNull null
        }

        constructor.isAccessible = true
        constructor.hookMethod {
            after { param -> (param.thisObject as? View)?.hideAsAd() }
        }
        constructor.declaringClass.simpleName
    }

    check(hooked.isNotEmpty()) { "No ad view among ${constructors.size} classes in $AD_PACKAGE" }
    Logger.printInfo { "Gmail ad views hooked: ${hooked.joinToString()}" }
}

private fun View.hideAsAd() {
    collapse()
    addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = v.collapse()
        override fun onViewDetachedFromWindow(v: View) = Unit
    })
}

private fun View.collapse() {
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
