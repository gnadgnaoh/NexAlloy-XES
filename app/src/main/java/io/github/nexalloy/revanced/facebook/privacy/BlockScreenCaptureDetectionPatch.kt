package io.github.nexalloy.revanced.facebook.privacy

import android.app.Activity
import android.os.Build
import io.github.nexalloy.findClassOrNull
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import java.lang.reflect.Modifier

val BlockScreenCaptureDetection = patch(
    name = "Block screenshot detection",
    description = "Stops Facebook from detecting screenshots and screen recordings.",
) {
    Activity::class.java.declaredMethods
        .filter { it.name == "registerScreenCaptureCallback" }
        .forEach { m -> m.hookMethod { before { param -> param.result = null } } }

    if (Build.VERSION.SDK_INT >= 35) {
        "android.view.ScreenRecordingCallbacks".findClassOrNull(null)?.declaredMethods
            ?.filter { !Modifier.isAbstract(it.modifiers) && (it.name == "addCallback" || it.name == "removeCallback" || it.name == "notifyCallbacks") }
            ?.forEach { m ->
                m.isAccessible = true
                val result: Any? = if (m.returnType == Int::class.javaPrimitiveType) 0 else null
                m.hookMethod { before { param -> param.result = result } }
            }
    }

    android.os.FileObserver::class.java.declaredMethods
        .filter { !Modifier.isAbstract(it.modifiers) && (it.name == "startWatching" || it.name == "onEvent") }
        .forEach { m -> m.hookMethod { before { param -> param.result = null } } }
}
