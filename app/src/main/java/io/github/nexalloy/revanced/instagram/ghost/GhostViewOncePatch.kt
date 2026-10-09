package io.github.nexalloy.revanced.instagram.ghost

import app.morphe.extension.shared.Logger
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import io.github.nexalloy.patch
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

val GhostViewOnce = patch(
    name = "Ghost view once",
    description = "Prevents view-once / view-twice seen and replay notifications from being sent.",
) {
    runCatching {
        ::visualSeenSendFingerprint.hookMethod { before { param -> block(param, "view-once seen") } }
    }.onFailure { Logger.printException({ "Ghost: view-once seen sender not found" }, it) }

    runCatching {
        ::visualReplayedSendFingerprint.hookMethod { before { param -> block(param, "view-twice replay") } }
    }.onFailure { Logger.printException({ "Ghost: replay sender not found" }, it) }

    runCatching {
        ::permanentMediaSeenSendFingerprint.hookMethod { before { param -> block(param, "permanent media seen") } }
    }.onFailure { Logger.printException({ "Ghost: permanent media seen sender not found" }, it) }
}

private fun block(param: MethodHookParam, what: String) {
    param.result = null
    param.args.getOrNull(1)?.let { reportSuccessLater(param.method as Method, it) }
    Logger.printDebug { "Ghost: $what blocked" }
}

@Volatile
private var successMethod: Method? = null

private val executor: ScheduledExecutorService by lazy {
    Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "nx-ghost-viewonce").apply { isDaemon = true } }
}

private fun reportSuccessLater(sender: Method, callback: Any) {
    val method = successMethod ?: runCatching {
        sender.parameterTypes[1].methods.single { m ->
            !Modifier.isStatic(m.modifiers) && m.returnType == Void.TYPE && m.parameterTypes.size == 2 &&
                m.parameterTypes[1] == String::class.java && !m.parameterTypes[0].isPrimitive
        }
    }.onFailure { Logger.printException({ "Ghost: view-once callback not found" }, it) }
        .getOrNull()?.also { successMethod = it } ?: return

    executor.schedule({
        runCatching { method.invoke(callback, null, null) }
            .onFailure { Logger.printException({ "Ghost: could not complete view-once mutation" }, it) }
    }, 250, TimeUnit.MILLISECONDS)
}
