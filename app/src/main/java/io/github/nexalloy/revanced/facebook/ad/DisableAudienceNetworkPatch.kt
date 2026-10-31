package io.github.nexalloy.revanced.facebook.ad

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import io.github.nexalloy.findClassOrNull
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch

private val AUDIENCE_NETWORK_SERVICES = setOf(
    "com.facebook.ads.internal.ipc.AudienceNetworkRemoteService",
    "com.facebook.audiencenetwork.AudienceNetworkService",
)
private val AUDIENCE_NETWORK_ACTIVITIES = setOf(
    "com.facebook.ads.internal.ipc.AudienceNetworkRemoteActivity",
    "com.facebook.ads.internal.ipc.AudienceNetworkExportedActivity",
    "com.facebook.ads.AudienceNetworkActivity",
)

val DisableAudienceNetwork = patch(
    name = "Disable Audience Network",
    description = "Stops Facebook serving its ads inside other apps. Rewarded ads in those apps may fail.",
    use = false,
) {
    var services = 0
    AUDIENCE_NETWORK_SERVICES.forEach { name ->
        val onBind = name.findClassOrNull(classLoader)?.getMethod("onBind", Intent::class.java) ?: return@forEach
        onBind.hookMethod {
            before { param -> if (param.thisObject.javaClass.name in AUDIENCE_NETWORK_SERVICES) param.result = null }
        }
        services++
    }
    check(services > 0) { "no Audience Network service found" }

    Activity::class.java.getDeclaredMethod("onCreate", Bundle::class.java).hookMethod {
        after { param ->
            val activity = param.thisObject as Activity
            if (activity.javaClass.name in AUDIENCE_NETWORK_ACTIVITIES) activity.finish()
        }
    }
}
