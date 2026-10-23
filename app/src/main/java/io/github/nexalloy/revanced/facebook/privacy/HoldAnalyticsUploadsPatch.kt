package io.github.nexalloy.revanced.facebook.privacy

import android.app.job.JobParameters
import app.morphe.extension.shared.Logger
import de.robv.android.xposed.XC_MethodReplacement
import io.github.nexalloy.findClassOrNull
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import io.github.nexalloy.revanced.facebook.ad.sponsoredPoolAddMethodFingerprint

private const val XANALYTICS = "com.facebook.xanalytics.XAnalyticsNative"
private const val PAPAYA_SERVICE = "com.facebook.papaya.fb.client.services.FBPapayaJobService"

val HoldAnalyticsUploads = patch(
    name = "Hold back analytics uploads",
    description = "Stops Facebook's background usage report uploads and the learning jobs it runs on your phone.",
) {
    ::sponsoredPoolAddMethodFingerprint.method

    val upload = XANALYTICS.findClassOrNull(classLoader)?.declaredMethods?.filter {
        (it.name == "kickOffUpload" && it.parameterCount == 0) ||
            (it.name == "resumeUploading" && it.parameterTypes.contentEquals(arrayOf(String::class.java)))
    }.orEmpty()
    val papaya = PAPAYA_SERVICE.findClassOrNull(classLoader)
    val onStartJob = papaya?.getMethod("onStartJob", JobParameters::class.java)
    check(upload.isNotEmpty() || onStartJob != null) { "no analytics upload entry point found" }

    var nativeHooked = 0
    upload.forEach { m -> runCatching { m.hookMethod(XC_MethodReplacement.DO_NOTHING) }.onSuccess { nativeHooked++ } }
    if (nativeHooked < 2) {
        ::xAnalyticsForegroundUploadFingerprint.dexMethodList.forEach { it.hookMethod(XC_MethodReplacement.DO_NOTHING) }
        Logger.printInfo { "Facebook: XAnalytics native upload not hookable, holding its foreground task instead" }
    }

    onStartJob?.hookMethod {
        before { param -> if (papaya.isInstance(param.thisObject)) param.result = false }
    }
}
