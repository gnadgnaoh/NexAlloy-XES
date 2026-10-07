package io.github.nexalloy.morphe.tiktok.watermark

import app.morphe.extension.shared.Logger
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import io.github.nexalloy.callStaticMethod
import io.github.nexalloy.findClassOrNull
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import java.lang.reflect.Member
import java.lang.reflect.Method

private const val TAG = "[TikTok watermark]"

val RemoveDownloadWatermark = patch(
    name = "Remove download watermark",
    description = "Saves videos and photos without the TikTok watermark.",
) {
    val installed = mutableListOf<String>()
    val skipped = mutableListOf<String>()

    fun optional(label: String, block: () -> Unit) {
        runCatching(block)
            .onSuccess { installed += label }
            .onFailure { skipped += "$label (${it.javaClass.simpleName}: ${it.message})" }
    }

    val transcode: Method = ACL_COMMON_SHARE_CLASS.findClassOrNull(classLoader)
        ?.let { runCatching { it.getDeclaredMethod(TRANSCODE_GETTER) }.getOrNull() }
        ?: AclCommonShareTranscodeFingerprint.method

    check(transcode.returnType == Int::class.javaPrimitiveType) {
        "$TRANSCODE_GETTER returns ${transcode.returnType.simpleName}, not int"
    }
    transcode.hookMethod(XC_MethodReplacement.returnConstant(TRANSCODE_NO_WATERMARK))
    installed += "transcode=$TRANSCODE_NO_WATERMARK"

    optional("clean address fallback") {
        val cleanAddr = VIDEO_CLASS.findClassOrNull(classLoader)
            ?.let { runCatching { it.getDeclaredMethod(CLEAN_ADDR_GETTER) }.getOrNull() }
            ?: error("$VIDEO_CLASS.$CLEAN_ADDR_GETTER not found")
        CleanDownloadAddress.install(cleanAddr)
    }

    optional("deoptimize readers") {
        val readers = (::transcodeReaderFingerprints.dexMethodList + ::cleanAddrReaderFingerprints.dexMethodList)
            .mapNotNull { runCatching { it.toMethod() }.getOrNull() }
            .distinct()
        check(readers.isNotEmpty()) { "no reader of $TRANSCODE_GETTER or $CLEAN_ADDR_GETTER found" }

        val count = readers.count { reader ->
            runCatching {
                XposedBridge::class.java.callStaticMethod("deoptimizeMethod", reader as Member)
            }.isSuccess
        }
        check(count > 0) { "deoptimizeMethod unavailable" }
        Logger.printDebug { "$TAG ${count}/${readers.size} readers deoptimized" }
    }

    Logger.printInfo { "$TAG installed=[${installed.joinToString()}] skipped=[${skipped.joinToString("; ")}]" }
}
