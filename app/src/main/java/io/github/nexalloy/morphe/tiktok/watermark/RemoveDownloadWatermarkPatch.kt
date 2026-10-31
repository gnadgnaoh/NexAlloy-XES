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
    val transcode: Method = ACL_COMMON_SHARE_CLASS.findClassOrNull(classLoader)
        ?.declaredMethods?.firstOrNull { it.name == TRANSCODE_GETTER && it.parameterCount == 0 }
        ?: AclCommonShareTranscodeFingerprint.method

    check(transcode.returnType == Int::class.javaPrimitiveType) {
        "$TRANSCODE_GETTER returns ${transcode.returnType.simpleName}, not int"
    }
    transcode.hookMethod(XC_MethodReplacement.returnConstant(TRANSCODE_NO_WATERMARK))

    run {
        val cleanAddr = VIDEO_CLASS.findClassOrNull(classLoader)
            ?.declaredMethods?.firstOrNull { it.name == CLEAN_ADDR_GETTER && it.parameterCount == 0 }
            ?: error("$VIDEO_CLASS.$CLEAN_ADDR_GETTER not found")
        CleanDownloadAddress.install(cleanAddr)
    }

    run {
        val readers = (::transcodeReaderFingerprints.dexMethodList + ::cleanAddrReaderFingerprints.dexMethodList)
            .filterNot { it.isStaticInitializer }
            .map { it.toMember() }
            .distinct()
        check(readers.isNotEmpty()) { "no reader of $TRANSCODE_GETTER or $CLEAN_ADDR_GETTER found" }

        readers.forEach { reader ->
            XposedBridge::class.java.callStaticMethod("deoptimizeMethod", reader as Member)
        }
        Logger.printDebug { "$TAG ${readers.size} readers deoptimized" }
    }

}
