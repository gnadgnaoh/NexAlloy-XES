package io.github.nexalloy.revanced.messenger.privacy

import android.app.Service
import android.app.job.JobParameters
import android.content.Context
import android.content.Intent
import app.morphe.extension.shared.Logger
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch

val StopAnalyticsUploads = patch(
    name = "Stop analytics uploads",
    description = "Blocks the background services Messenger's analytics logger uploads through. " +
        "Events are still recorded on the device.",
) {
    val base = "com.facebook.analytics2.logger"
    val startCommand = arrayOf<Class<*>>(Intent::class.java, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)

    fun hookStartCommand(className: String) = runCatching {
        classLoader.loadClass(className)
            .getDeclaredMethod("onStartCommand", *startCommand)
            .hookMethod {
                before { param ->
                    val service = param.thisObject as? Service
                    val startId = param.args.getOrNull(2) as? Int
                    if (service != null && startId != null) runCatching { service.stopSelf(startId) }
                    param.result = Service.START_NOT_STICKY
                    Logger.printDebug { "Messenger: analytics onStartCommand neutralised ($className)" }
                }
            }
    }.onFailure { Logger.printDebug { "Messenger: no onStartCommand on $className" } }

    fun hookStartJob(className: String) = runCatching {
        classLoader.loadClass(className)
            .getDeclaredMethod("onStartJob", JobParameters::class.java)
            .hookMethod { before { param -> param.result = false } }
    }.onFailure { Logger.printDebug { "Messenger: no onStartJob on $className" } }

    hookStartCommand("$base.legacy.uploader.AlarmBasedUploadService")

    hookStartCommand("$base.legacy.uploader.LollipopUploadService")
    hookStartJob("$base.legacy.uploader.LollipopUploadService")

    hookStartCommand("$base.service.LollipopUploadSafeService")
    hookStartJob("$base.service.LollipopUploadSafeService")

    hookStartCommand("$base.GooglePlayUploadService")

    runCatching {
        classLoader.loadClass("$base.legacy.uploader.HighPriUploadRetryReceiver")
            .getDeclaredMethod("onReceive", Context::class.java, Intent::class.java)
            .hookMethod { before { param -> param.result = null } }
    }.onFailure { Logger.printDebug { "Messenger: no onReceive on HighPriUploadRetryReceiver" } }
}
