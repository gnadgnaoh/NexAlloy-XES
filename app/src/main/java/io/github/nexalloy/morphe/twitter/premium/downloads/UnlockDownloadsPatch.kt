package io.github.nexalloy.morphe.twitter.premium.downloads

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement.returnConstant
import de.robv.android.xposed.XposedBridge
import io.github.nexalloy.patch
import io.github.nexalloy.ScopedHook
import java.lang.reflect.Member

val UnlockDownloads = patch(
    name = "Unlock downloads",
    description = "Unlocks media downloads and offline video saving in the X app.",
) {
    val subscriptionChecks: List<Member> = ::handlerSubscriptionChecksFingerprint.dexMethodList
        .map { it.toMember() }
    if (subscriptionChecks.isEmpty()) {
        throw Exception("No subscription checks resolved for NewX download handlers")
    }

    val forceChecksWhileRunning = ScopedHook().apply {
        subscriptionChecks.forEach { check ->
            hookInnerMethod(check, before = {}, after = { param -> param.result = true })
        }
    }

    ::timelineDownloadHandlerFingerprint.hookMethod(forceChecksWhileRunning)
    ::videoTabDownloadHandlersFingerprint.dexMethodList.forEach { handler ->
        handler.hookMethod(forceChecksWhileRunning)
    }

    ::hasAnyPremiumFingerprint.hookMethod(returnConstant(true))
    ::offlinePremiumFingerprint.hookMethod(returnConstant(true))
    ::offlineVideoEnabledFingerprint.hookMethod(returnConstant(true))

    listOf(
        ::videoDownloadableField to ::videoDownloadableAccessors,
        ::gifDownloadableField to ::gifDownloadableAccessors,
        ::imageDownloadableField to ::imageDownloadableAccessors,
    ).forEach { (fieldRef, accessorsRef) ->
        val field = fieldRef.field.apply { isAccessible = true }

        val accessors = accessorsRef.dexMethodList
        if (accessors.isEmpty()) throw Exception("No isDownloadable accessor for ${field.declaringClass.name}")
        accessors.forEach { it.hookMethod(returnConstant(true)) }

        XposedBridge.hookAllConstructors(field.declaringClass, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                param.thisObject?.let { runCatching { field.setBoolean(it, true) } }
            }
        })
    }
}
