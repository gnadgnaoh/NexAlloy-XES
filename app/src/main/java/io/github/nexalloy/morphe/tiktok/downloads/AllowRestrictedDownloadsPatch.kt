package io.github.nexalloy.morphe.tiktok.downloads

import app.morphe.extension.shared.Logger
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.github.nexalloy.callMethodOrNull
import io.github.nexalloy.callStaticMethod
import io.github.nexalloy.findClassOrNull
import io.github.nexalloy.hookMethod
import io.github.nexalloy.morphe.tiktok.shared.realMatches
import io.github.nexalloy.morphe.tiktok.watermark.ACL_COMMON_SHARE_CLASS
import io.github.nexalloy.patch
import java.lang.reflect.Member

private const val TAG = "[TikTok restricted downloads]"

val AllowRestrictedDownloads = patch(
    name = "Allow restricted downloads",
    description = "Shows and enables \"Save video\" (and save photo / GIF / Live Photo) even when " +
        "the creator turned downloads off, and keeps the sound in saved videos.",
) {
    run { // downloadVerdicts
        val share = AWEME_ACL_SHARE_CLASS.findClassOrNull(classLoader) ?: error("$AWEME_ACL_SHARE_CLASS not found")
        val getters = share.declaredMethods.filter {
            it.parameterCount == 0 && it.name.startsWith(DOWNLOAD_ACL_GETTER_PREFIX) &&
                it.returnType.name == ACL_COMMON_SHARE_CLASS
        }
        check(getters.isNotEmpty()) { "AwemeACLShare has no download verdict getter" }
        getters.forEach { getter ->
            getter.hookMethod {
                after { param -> param.result?.let(::unlockDownload) }
            }
        }
    }

    run { // preventDownload
        val aweme = AWEME_CLASS.findClassOrNull(classLoader) ?: error("$AWEME_CLASS not found")
        val getters = aweme.declaredMethods.filter {
            it.parameterCount == 0 && it.returnType == Boolean::class.javaPrimitiveType &&
                (it.name == "isPreventDownload" || it.name.startsWith("getPreventDownload"))
        }
        check(getters.isNotEmpty()) { "Aweme has no prevent-download getter" }
        getters.forEach { it.hookMethod(XC_MethodReplacement.returnConstant(false)) }
    }


    run { // deoptimize readers
        val readers = ::downloadPermissionReaderFingerprints.dexMethodList.realMatches()
            .map { it.toMember() }
            .distinct()
        check(readers.isNotEmpty()) { "no reader found" }
        readers.forEach { reader ->
            XposedBridge::class.java.callStaticMethod("deoptimizeMethod", reader as Member)
        }
        Logger.printDebug { "$TAG ${readers.size} readers deoptimized" }
    }

}

private fun unlockDownload(acl: Any) {
    fun set(field: String, setter: String, value: Any) {
        runCatching {
            when (value) {
                is Int -> XposedHelpers.setIntField(acl, field, value)
                is Boolean -> XposedHelpers.setBooleanField(acl, field, value)
            }
        }.onFailure { acl.callMethodOrNull(setter, value) }
    }
    set("code", "setCode", ACL_ALLOWED)
    set("showType", "setShowType", ACL_SHOW_ENABLED)
    set("mute", "setMute", false)
}
