package io.github.nexalloy.revanced.messenger.privacy

import android.app.Service
import android.app.job.JobParameters
import android.content.Context
import android.content.Intent
import app.morphe.extension.shared.Logger
import io.github.nexalloy.findClassOrNull
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch

val StopAnalyticsUploads = patch(
    name = "Stop analytics uploads",
    description = "Blocks the background services Messenger's analytics logger uploads through. " +
        "Events are still recorded on the device.",
) {
    val base = "com.facebook.analytics2.logger"
    val startCommand = arrayOf<Class<*>>(Intent::class.java, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)

    // Tên class viết cứng (không phải fingerprint), có bản không có → dò bằng findClassOrNull.
    // Đếm số hook; không hook được cái nào thì ném lỗi để có toast + log.
    var hooked = 0
    val missing = mutableListOf<String>()

    fun declared(className: String, name: String, vararg params: Class<*>) =
        className.findClassOrNull(classLoader)?.declaredMethods?.firstOrNull {
            it.name == name && it.parameterTypes.contentEquals(params)
        }.also { if (it == null) missing += "$className.$name" }

    fun hookStartCommand(className: String) {
        val m = declared(className, "onStartCommand", *startCommand) ?: return
        m.hookMethod {
            before { param ->
                val service = param.thisObject as? Service
                val startId = param.args.getOrNull(2) as? Int
                if (service != null && startId != null) runCatching { service.stopSelf(startId) }
                param.result = Service.START_NOT_STICKY
                Logger.printDebug { "Messenger: analytics onStartCommand neutralised ($className)" }
            }
        }
        hooked++
    }

    fun hookStartJob(className: String) {
        val m = declared(className, "onStartJob", JobParameters::class.java) ?: return
        m.hookMethod { before { param -> param.result = false } }
        hooked++
    }

    hookStartCommand("$base.legacy.uploader.AlarmBasedUploadService")

    hookStartCommand("$base.legacy.uploader.LollipopUploadService")
    hookStartJob("$base.legacy.uploader.LollipopUploadService")

    hookStartCommand("$base.service.LollipopUploadSafeService")
    hookStartJob("$base.service.LollipopUploadSafeService")

    hookStartCommand("$base.GooglePlayUploadService")

    declared("$base.legacy.uploader.HighPriUploadRetryReceiver", "onReceive", Context::class.java, Intent::class.java)
        ?.hookMethod { before { param -> param.result = null } }
        ?.also { hooked++ }

    if (missing.isNotEmpty()) Logger.printInfo { "Messenger: analytics entry points not present: ${missing.joinToString()}" }
    check(hooked > 0) { "no analytics upload entry point found (${missing.joinToString()})" }
}
