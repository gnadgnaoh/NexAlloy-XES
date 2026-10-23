package io.github.nexalloy.revanced.messenger.privacy

import android.app.Service
import android.app.job.JobParameters
import android.content.Context
import android.content.Intent
import android.os.Message
import android.os.Messenger
import app.morphe.extension.shared.Logger
import io.github.nexalloy.findClassOrNull
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch

val StopAnalyticsUploads = patch(
    name = "Stop analytics uploads",
    description = "Stops the uploads Messenger uses to send usage statistics: its background services, " +
        "the upload it starts inside the app, Google Play's direct tasks and the native batch timer. " +
        "Events are still recorded on the device. Crash reports are left alone.",
) {
    val base = "com.facebook.analytics2.logger"
    val startCommand = arrayOf<Class<*>>(Intent::class.java, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)

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
                runCatching { ((param.args[0] as? Intent)?.extras?.get(UPLOAD_STARTER) as? Messenger)?.send(Message.obtain()) }
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

    "$base.legacy.uploader.Analytics2UploadService".findClassOrNull(classLoader)?.let { service ->
        val parent = service.superclass
        parent.declaredMethods.filter {
            (it.name == "onStartJob" && it.parameterTypes.contentEquals(arrayOf(JobParameters::class.java))) ||
                (it.name == "onStartCommand" && it.parameterTypes.contentEquals(startCommand))
        }.forEach { m ->
            m.hookMethod {
                before { param ->
                    if (param.thisObject?.javaClass != service) return@before
                    if (m.name == "onStartJob") param.result = false
                    else {
                        runCatching { (param.thisObject as Service).stopSelf(param.args[2] as Int) }
                        param.result = Service.START_NOT_STICKY
                    }
                }
            }
            hooked++
        }
    } ?: missing.add("Analytics2UploadService")

    runCatching {
        val play = classLoader.loadClass("$base.GooglePlayUploadService")
        val run = ::boundUploadTaskFingerprint.method
        val task = run.declaringClass
        val serviceField = task.declaredFields.single { it.type.isAssignableFrom(play) && it.type != Any::class.java }
            .apply { isAccessible = true }
        val reporter = task.declaredMethods.single {
            java.lang.reflect.Modifier.isPrivate(it.modifiers) && it.returnType == Void.TYPE &&
                it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
        }.apply { isAccessible = true }
        run.hookMethod {
            before { param ->
                if (!play.isInstance(serviceField.get(param.thisObject))) return@before
                reporter.invoke(param.thisObject, 0)
                param.result = null
            }
        }
        hooked++
    }.onFailure { Logger.printInfo { "Messenger: bound analytics task not hooked: $it" } }

    runCatching { ::xAnalyticsUploadTimerFingerprint.hookMethod { before { param -> param.result = null } }; hooked++ }
        .onFailure { Logger.printInfo { "Messenger: XAnalytics timer not hooked: $it" } }
    runCatching { ::inProcessUploadStartFingerprint.hookMethod { before { param -> param.result = null } }; hooked++ }
        .onFailure { Logger.printInfo { "Messenger: in-process analytics upload not hooked: $it" } }

    if (missing.isNotEmpty()) Logger.printInfo { "Messenger: analytics entry points not present: ${missing.joinToString()}" }
    check(hooked > 0) { "no analytics upload entry point found (${missing.joinToString()})" }
}

private const val UPLOAD_STARTER = "_messenger"
