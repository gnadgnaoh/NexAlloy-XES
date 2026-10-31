package io.github.nexalloy.revanced.messenger.ads

import io.github.nexalloy.morphe.findMethodListDirect
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Modifier

private fun DexKitBridge.pluginGates(vararg anchors: String) = anchors.flatMap { anchor ->
    findMethod {
        matcher { returnType = "boolean"; paramCount = 0; usingEqStrings(anchor) }
    }.filter { !Modifier.isStatic(it.modifiers) }
}.distinctBy { it.descriptor }.also { check(it.isNotEmpty()) }

val inboxPromotionGatesFingerprint = findMethodListDirect {
    pluginGates(
        "com.facebook.messaging.quickpromotion.plugins.threadlist.QuickpromotionThreadlistKillSwitch",
        "com.facebook.messaging.quickpromotion.plugins.threadlistmsys.QuickpromotionThreadlistmsysKillSwitch",
    )
}

val chatPromotionGatesFingerprint = findMethodListDirect {
    pluginGates(
        "com.facebook.messaging.quickpromotion.plugins.threadview.QuickpromotionThreadviewKillSwitch",
        "com.facebook.messaging.quickpromotion.plugins.threadviewmsys.QuickpromotionThreadviewmsysKillSwitch",
    )
}

val adContextBannerGatesFingerprint = findMethodListDirect {
    pluginGates(
        "com.facebook.messaging.nativepagereply.plugins.adscontextprovider.threadviewbanner.CTMAdsThreadViewBannerImplementation",
    )
}
