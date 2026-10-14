package io.github.nexalloy.revanced.zalo.telemetry

import de.robv.android.xposed.XC_MethodReplacement
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch

private const val AD_ID_CLIENT_CLASS = "com.google.android.gms.ads.identifier.AdvertisingIdClient"
private const val ZERO_AD_ID = "00000000-0000-0000-0000-000000000000"

val DisableAdvertisingId = patch(
    name = "Disable Advertising ID",
    description = "Gives Zalo and its ad/analytics SDKs an all-zero advertising ID with " +
        "ad tracking limited, instead of your real Google advertising ID.",
) {
    val client = classLoader.loadClass(AD_ID_CLIENT_CLASS)
    val info = classLoader.loadClass("$AD_ID_CLIENT_CLASS\$Info")
    val infoConstructor = info.getDeclaredConstructor(String::class.java, Boolean::class.javaPrimitiveType)
        .apply { isAccessible = true }

    val zeroInfo = XC_MethodReplacement.returnConstant(infoConstructor.newInstance(ZERO_AD_ID, true))
    client.getDeclaredMethod("getAdvertisingIdInfo", android.content.Context::class.java).hookMethod(zeroInfo)
    client.getDeclaredMethod("getInfo").hookMethod(zeroInfo)

    runCatching {
        client.getDeclaredMethod("getIsAdIdFakeForDebugLogging", android.content.Context::class.java)
    }.getOrNull()?.hookMethod(XC_MethodReplacement.returnConstant(true))
}
