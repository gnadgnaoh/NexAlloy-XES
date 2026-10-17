package io.github.nexalloy.morphe.tiktok.telemetry

import android.app.Activity
import android.content.Context
import de.robv.android.xposed.XC_MethodReplacement
import io.github.nexalloy.findClassOrNull
import io.github.nexalloy.hookMethod
import io.github.nexalloy.morphe.tiktok.shared.realMatches
import io.github.nexalloy.patch
import java.lang.reflect.Method
import java.lang.reflect.Modifier

private const val TAG = "[TikTok telemetry]"

private const val PACK_SENT_STATUS = 200

private const val DELIVERED_PRIORITY_DATA = "{\"message\":\"success\",\"magic_tag\":\"ss_app_log\"}"

val DisableTelemetry = patch(
    name = "Disable telemetry",
    description = "Stops ByteDance AppLog analytics, AppsFlyer attribution, Firebase screen reports " +
        "and the Npth/MonitorCrash crash reporters. The For You feed then no longer learns from " +
        "what you watch.",
    use = false,
) {
    fun Class<*>.staticVoidMethods(names: Set<String>): List<Method> = declaredMethods.filter {
        it.name in names && Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE
    }

    run { // AppLog + legacyAppLog: tùy bản có một hoặc cả hai → hook cái có, cần ít nhất 1.
        val entryPoints =
            APP_LOG_CLASS.findClassOrNull(classLoader)?.staticVoidMethods(APP_LOG_ENTRY_POINTS).orEmpty() +
                LEGACY_APP_LOG_CLASS.findClassOrNull(classLoader)?.staticVoidMethods(LEGACY_APP_LOG_ENTRY_POINTS).orEmpty()
        check(entryPoints.isNotEmpty()) { "no AppLog entry point found ($APP_LOG_CLASS / $LEGACY_APP_LOG_CLASS)" }
        entryPoints.forEach { it.hookMethod(XC_MethodReplacement.DO_NOTHING) }
    }

    run { // startupTasks
        val hooked = TELEMETRY_STARTUP_TASKS.count { name ->
            val run = name.findClassOrNull(classLoader)
                ?.declaredMethods?.firstOrNull { it.name == "run" && it.parameterTypes.contentEquals(arrayOf<Class<*>>(Context::class.java)) }
                ?: return@count false
            run.hookMethod(XC_MethodReplacement.DO_NOTHING)
            true
        }
        check(hooked > 0) { "none of the ${TELEMETRY_STARTUP_TASKS.size} startup tasks found" }
    }

    run { // appsFlyerEvents
        val methods = ::appsFlyerEventFingerprints.dexMethodList.realMatches()
        check(methods.isNotEmpty()) { "no AppsFlyer implementation found" }
        methods.forEach { it.toMethod().hookMethod(XC_MethodReplacement.DO_NOTHING) }
    }

    run { // firebaseScreens
        FIREBASE_ANALYTICS_CLASS.findClassOrNull(classLoader)!!
            .getDeclaredMethod("setCurrentScreen", Activity::class.java, String::class.java, String::class.java)
            .hookMethod(XC_MethodReplacement.DO_NOTHING)
    }

    run { // monitorCrash
        val methods = MONITOR_CRASH_CLASS.findClassOrNull(classLoader)!!.declaredMethods.filter {
            (it.name == "reportCustomErr" || it.name == "reportEvent") && it.returnType == Void.TYPE
        }
        check(methods.isNotEmpty()) { "no MonitorCrash report method found" }
        methods.forEach { it.hookMethod(XC_MethodReplacement.DO_NOTHING) }
    }

    run { // packSend
        val methods = ::appLogPackSendFingerprints.dexMethodList.realMatches()
        check(methods.size == 1) { "expected one AppLog pack send, found ${methods.size}" }
        methods.single().toMethod().hookMethod(XC_MethodReplacement.returnConstant(PACK_SENT_STATUS))
    }

    run { // forwardSend
        val methods = ::appLogForwardSendFingerprints.dexMethodList.realMatches()
        check(methods.size == 1) { "expected one AppLog forward send, found ${methods.size}" }
        methods.single().toMethod().hookMethod(XC_MethodReplacement.DO_NOTHING)
    }

    run { // installActiveCheck
        val methods = ::installActiveCheckFingerprints.dexMethodList.realMatches()
        check(methods.size == 1) { "expected one activation check, found ${methods.size}" }
        methods.single().toMethod().hookMethod(XC_MethodReplacement.returnConstant(true))
    }

    run { // prioritySend
        val callback = APP_LOG_PRIORITY_CALLBACK_CLASS.findClassOrNull(classLoader)!!
        val response = APP_LOG_PRIORITY_RESPONSE_CLASS.findClassOrNull(classLoader)!!
            .getDeclaredConstructor(Int::class.javaPrimitiveType, String::class.java, String::class.java)
        val send = callback.declaredMethods.single { it.name == "doHttpPost" }
        send.hookMethod {
            before { param ->
                param.result = runCatching { response.newInstance(PACK_SENT_STATUS, "", DELIVERED_PRIORITY_DATA) }
                    .getOrElse { return@before }
            }
        }
    }

}
